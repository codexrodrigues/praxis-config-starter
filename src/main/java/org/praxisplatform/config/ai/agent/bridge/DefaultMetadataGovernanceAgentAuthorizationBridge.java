package org.praxisplatform.config.ai.agent.bridge;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.springframework.beans.BeanUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

/**
 * Implementacao padrao de {@link AgentAuthorizationBridge} orientada por metadados declarados em
 * DTOs ({@link DomainGovernance}, {@code @AiUsagePolicy} e {@link UISchema}).
 *
 * <p>Garante que hosts sem {@code praxis-authz-starter} (ou com OPA desabilitado) continuem
 * aplicando visibilidade por authorities para a UI e supressao/mascaramento estrito para o LLM.
 */
public class DefaultMetadataGovernanceAgentAuthorizationBridge implements AgentAuthorizationBridge {

    private record FieldGovernanceRule(
            Set<String> visibleForAuthorities,
            AiVisibilityMode aiVisibility,
            DomainClassification classification
    ) {
        static final FieldGovernanceRule OPEN =
                new FieldGovernanceRule(Set.of(), AiVisibilityMode.ALLOW, DomainClassification.INTERNAL);
    }

    private final Map<Class<?>, Map<String, FieldGovernanceRule>> rulesCache = new ConcurrentHashMap<>();

    @Override
    public boolean canReadResource(AgentExecutionContext context, String resourcePath) {
        return true;
    }

    @Override
    public boolean canInvokeAction(
            AgentExecutionContext context,
            String resourcePath,
            String actionId,
            Object entityId) {
        return true;
    }

    @Override
    public DualProjectedPayload projectRecord(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Map<String, Object> rawRecord) {
        if (rawRecord == null || rawRecord.isEmpty()) {
            return new DualProjectedPayload(Map.of(), Map.of(), List.of(), List.of());
        }
        Map<String, FieldGovernanceRule> rules = dtoType == null
                ? Map.of()
                : rulesCache.computeIfAbsent(dtoType, DefaultMetadataGovernanceAgentAuthorizationBridge::inspectRules);

        Map<String, Object> uiRecord = new LinkedHashMap<>();
        Map<String, Object> modelSafeRecord = new LinkedHashMap<>();
        List<String> hiddenFields = new ArrayList<>();
        List<String> maskedForAiFields = new ArrayList<>();

        for (Map.Entry<String, Object> entry : rawRecord.entrySet()) {
            String fieldName = entry.getKey();
            Object rawValue = entry.getValue();
            FieldGovernanceRule rule = rules.getOrDefault(fieldName, FieldGovernanceRule.OPEN);

            if (!hasAnyAuthority(context, rule.visibleForAuthorities())) {
                hiddenFields.add(fieldName);
                continue;
            }
            uiRecord.put(fieldName, rawValue);

            AiVisibilityMode mode = effectiveAiMode(rule);
            switch (mode) {
                case DENY -> maskedForAiFields.add(fieldName);
                case MASK -> {
                    maskedForAiFields.add(fieldName);
                    if (rawValue != null) {
                        modelSafeRecord.put(fieldName, maskValueForAi(fieldName, rawValue));
                    }
                }
                case SUMMARIZE_ONLY -> {
                    maskedForAiFields.add(fieldName);
                    modelSafeRecord.put(fieldName, summarizeForAi(rawValue));
                }
                case ALLOW -> {
                    if (rawValue != null) {
                        modelSafeRecord.put(fieldName, rawValue);
                    }
                }
            }
        }
        return new DualProjectedPayload(uiRecord, modelSafeRecord, hiddenFields, maskedForAiFields);
    }

    private static AiVisibilityMode effectiveAiMode(FieldGovernanceRule rule) {
        if (rule.aiVisibility() != null && rule.aiVisibility() != AiVisibilityMode.ALLOW) {
            return rule.aiVisibility();
        }
        if (rule.classification() == DomainClassification.RESTRICTED) {
            return AiVisibilityMode.MASK;
        }
        return AiVisibilityMode.ALLOW;
    }

    private static boolean hasAnyAuthority(AgentExecutionContext context, Set<String> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (context == null || context.authorities() == null || context.authorities().isEmpty()) {
            return false;
        }
        for (String authority : required) {
            if (context.authorities().contains(authority)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, FieldGovernanceRule> inspectRules(Class<?> dtoType) {
        Map<String, FieldGovernanceRule> rules = new LinkedHashMap<>();
        if (dtoType == null || Object.class.equals(dtoType)) {
            return Map.of();
        }
        if (dtoType.isRecord()) {
            for (RecordComponent component : dtoType.getRecordComponents()) {
                rules.put(component.getName(), extractRule(
                        component.getAnnotation(UISchema.class),
                        component.getAnnotation(DomainGovernance.class),
                        findFieldAnnotation(dtoType, component.getName(), UISchema.class),
                        findFieldAnnotation(dtoType, component.getName(), DomainGovernance.class),
                        findMethodAnnotation(component.getAccessor(), UISchema.class),
                        findMethodAnnotation(component.getAccessor(), DomainGovernance.class)));
            }
        }
        for (PropertyDescriptor pd : BeanUtils.getPropertyDescriptors(dtoType)) {
            if ("class".equals(pd.getName())) {
                continue;
            }
            FieldGovernanceRule rule = extractRule(
                    findFieldAnnotation(dtoType, pd.getName(), UISchema.class),
                    findFieldAnnotation(dtoType, pd.getName(), DomainGovernance.class),
                    findMethodAnnotation(pd.getReadMethod(), UISchema.class),
                    findMethodAnnotation(pd.getReadMethod(), DomainGovernance.class),
                    null,
                    null);
            rules.putIfAbsent(pd.getName(), rule);
        }
        Class<?> current = dtoType;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                rules.putIfAbsent(field.getName(), extractRule(
                        AnnotatedElementUtils.findMergedAnnotation(field, UISchema.class),
                        AnnotatedElementUtils.findMergedAnnotation(field, DomainGovernance.class),
                        null,
                        null,
                        null,
                        null));
            }
            current = current.getSuperclass();
        }
        return Map.copyOf(rules);
    }

    private static FieldGovernanceRule extractRule(
            UISchema uiPrimary,
            DomainGovernance govPrimary,
            UISchema uiSecondary,
            DomainGovernance govSecondary,
            UISchema uiTertiary,
            DomainGovernance govTertiary) {
        UISchema uiSchema = firstNonNull(uiPrimary, uiSecondary, uiTertiary);
        DomainGovernance governance = firstNonNull(govPrimary, govSecondary, govTertiary);
        Set<String> visibleAuthorities = uiSchema == null || uiSchema.visibleForAuthorities() == null
                ? Set.of()
                : Arrays.stream(uiSchema.visibleForAuthorities())
                        .filter(StringUtils::hasText)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        AiVisibilityMode aiMode = governance != null && governance.aiUsage() != null
                ? governance.aiUsage().visibility()
                : AiVisibilityMode.ALLOW;
        DomainClassification classification = governance != null
                ? governance.classification()
                : DomainClassification.INTERNAL;
        return new FieldGovernanceRule(visibleAuthorities, aiMode, classification);
    }

    private static <A extends Annotation> A findFieldAnnotation(
            Class<?> targetClass, String fieldName, Class<A> annotationType) {
        Class<?> current = targetClass;
        while (current != null && !Object.class.equals(current)) {
            try {
                Field field = current.getDeclaredField(fieldName);
                A ann = AnnotatedElementUtils.findMergedAnnotation(field, annotationType);
                if (ann != null) {
                    return ann;
                }
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static <A extends Annotation> A findMethodAnnotation(Method method, Class<A> annotationType) {
        return method == null ? null : AnnotatedElementUtils.findMergedAnnotation(method, annotationType);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Object maskValueForAi(String fieldName, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text = rawValue.toString().trim();
        if (text.isEmpty()) {
            return "";
        }
        String lowerName = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        String digits = text.replaceAll("\\D", "");
        if ((lowerName.contains("cpf") || lowerName.contains("tax") || digits.length() == 11) && digits.length() == 11) {
            return "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**";
        }
        if ((lowerName.contains("cnpj") || digits.length() == 14) && digits.length() == 14) {
            return "**." + digits.substring(2, 5) + "." + digits.substring(5, 8) + "/****-**";
        }
        int atIndex = text.indexOf('@');
        if (atIndex > 0 && atIndex < text.length() - 1) {
            return text.charAt(0) + "***" + text.substring(atIndex);
        }
        if (text.length() <= 4) {
            return "***";
        }
        return "***" + text.substring(text.length() - 2);
    }

    private static Object summarizeForAi(Object rawValue) {
        if (rawValue == null) {
            return "[empty]";
        }
        String text = rawValue.toString().trim();
        return text.isEmpty() ? "[empty]" : "[present]";
    }
}
