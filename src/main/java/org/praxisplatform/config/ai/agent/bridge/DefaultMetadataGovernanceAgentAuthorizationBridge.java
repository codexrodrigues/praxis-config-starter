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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.ResourceIntent;
import org.praxisplatform.uischema.annotation.UiSurface;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Implementacao padrao de {@link AgentAuthorizationBridge} orientada por metadados declarados em
 * controllers ({@code @PreAuthorize}, {@link WorkflowAction}) e DTOs ({@link DomainGovernance},
 * {@code @AiUsagePolicy} e {@link UISchema}).
 *
 * <p>Garante que hosts sem {@code praxis-authz-starter} (ou com OPA desabilitado) continuem
 * aplicando visibilidade/editabilidade por authorities e controle de acesso em processo, alem de
 * supressao/mascaramento estrito para o LLM.
 */
public class DefaultMetadataGovernanceAgentAuthorizationBridge implements AgentAuthorizationBridge {

    private static final Pattern AUTHORITY_CALL_PATTERN = Pattern.compile(
            "^(hasAuthority|hasAnyAuthority|hasRole|hasAnyRole)\\s*\\((.*)\\)$",
            Pattern.DOTALL);
    private static final Pattern QUOTED_LITERAL_PATTERN = Pattern.compile("['\"]([^'\"]+)['\"]");

    private record FieldGovernanceRule(
            boolean hasUiSchema,
            Set<String> visibleForAuthorities,
            Set<String> editableForAuthorities,
            boolean readOnly,
            AiVisibilityMode aiVisibility,
            DomainClassification classification
    ) {
        static final FieldGovernanceRule OPEN = new FieldGovernanceRule(
                false, Set.of(), Set.of(), false, AiVisibilityMode.ALLOW, DomainClassification.INTERNAL);
    }

    private record ActionAuthRule(
            String preAuthorize,
            Set<String> requiredAuthorities
    ) {
    }

    private record ControllerAuthPolicy(
            Class<?> responseDtoType,
            String classPreAuthorize,
            List<String> readPreAuthorizes,
            String createPreAuthorize,
            String updatePreAuthorize,
            String deletePreAuthorize,
            Map<String, ActionAuthRule> actionRules
    ) {
    }

    private final Map<Class<?>, Map<String, FieldGovernanceRule>> rulesCache = new ConcurrentHashMap<>();
    private final Map<String, ControllerAuthPolicy> policiesByPath;

    public DefaultMetadataGovernanceAgentAuthorizationBridge() {
        this(List.of());
    }

    public DefaultMetadataGovernanceAgentAuthorizationBridge(List<?> controllers) {
        this.policiesByPath = indexPolicies(controllers);
    }

    @Override
    public boolean canReadResource(AgentExecutionContext context, String resourcePath) {
        ControllerAuthPolicy policy = resolvePolicy(resourcePath);
        if (policy == null) {
            return true;
        }
        if (StringUtils.hasText(policy.classPreAuthorize())
                && !evaluatePreAuthorize(policy.classPreAuthorize(), context)) {
            return false;
        }
        for (String expr : policy.readPreAuthorizes()) {
            if (StringUtils.hasText(expr) && !evaluatePreAuthorize(expr, context)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canInvokeAction(
            AgentExecutionContext context,
            String resourcePath,
            String actionId,
            Object entityId) {
        ControllerAuthPolicy policy = resolvePolicy(resourcePath);
        if (policy == null) {
            return true;
        }
        if (StringUtils.hasText(policy.classPreAuthorize())
                && !evaluatePreAuthorize(policy.classPreAuthorize(), context)) {
            return false;
        }
        String normalizedAction = actionId == null ? "" : actionId.trim().toLowerCase(Locale.ROOT);
        ActionAuthRule workflowRule = policy.actionRules().get(normalizedAction);
        if (workflowRule != null) {
            if (StringUtils.hasText(workflowRule.preAuthorize())
                    && !evaluatePreAuthorize(workflowRule.preAuthorize(), context)) {
                return false;
            }
            if (!hasAllAuthorities(context, workflowRule.requiredAuthorities())) {
                return false;
            }
            return true;
        }
        if ("update".equals(normalizedAction) || "edit".equals(normalizedAction)) {
            if (StringUtils.hasText(policy.updatePreAuthorize())
                    && !evaluatePreAuthorize(policy.updatePreAuthorize(), context)) {
                return false;
            }
            if (!allowsAnyFieldUpdate(context, policy.responseDtoType())) {
                return false;
            }
            return true;
        }
        if ("create".equals(normalizedAction) && StringUtils.hasText(policy.createPreAuthorize())) {
            return evaluatePreAuthorize(policy.createPreAuthorize(), context);
        }
        if ("delete".equals(normalizedAction) && StringUtils.hasText(policy.deletePreAuthorize())) {
            return evaluatePreAuthorize(policy.deletePreAuthorize(), context);
        }
        return true;
    }

    @Override
    public boolean canModifyFields(
            AgentExecutionContext context,
            String resourcePath,
            Class<?> dtoType,
            Set<String> fieldNames) {
        if (dtoType == null || Object.class.equals(dtoType) || fieldNames == null || fieldNames.isEmpty()) {
            return true;
        }
        Map<String, FieldGovernanceRule> rules = rulesCache.computeIfAbsent(
                dtoType, DefaultMetadataGovernanceAgentAuthorizationBridge::inspectRules);
        for (String fieldName : fieldNames) {
            if (!StringUtils.hasText(fieldName)) {
                continue;
            }
            FieldGovernanceRule rule = rules.get(fieldName);
            if (rule == null) {
                continue;
            }
            if (!rule.editableForAuthorities().isEmpty()
                    && !hasAnyAuthority(context, rule.editableForAuthorities())) {
                return false;
            }
        }
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

    private boolean allowsAnyFieldUpdate(AgentExecutionContext context, Class<?> dtoType) {
        if (dtoType == null || Object.class.equals(dtoType)) {
            return true;
        }
        Map<String, FieldGovernanceRule> rules = rulesCache.computeIfAbsent(
                dtoType, DefaultMetadataGovernanceAgentAuthorizationBridge::inspectRules);
        boolean sawGatedEditableField = false;
        boolean sawUnrestrictedEditableField = false;
        for (FieldGovernanceRule rule : rules.values()) {
            if (!rule.hasUiSchema() || rule.readOnly()) {
                continue;
            }
            if (!rule.editableForAuthorities().isEmpty()) {
                sawGatedEditableField = true;
                if (hasAnyAuthority(context, rule.editableForAuthorities())) {
                    return true;
                }
            } else {
                sawUnrestrictedEditableField = true;
            }
        }
        return !sawGatedEditableField || sawUnrestrictedEditableField;
    }

    private ControllerAuthPolicy resolvePolicy(String resourcePath) {
        if (!StringUtils.hasText(resourcePath) || policiesByPath.isEmpty()) {
            return null;
        }
        return policiesByPath.get(normalizePath(resourcePath));
    }

    private static Map<String, ControllerAuthPolicy> indexPolicies(List<?> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            return Map.of();
        }
        Class<? extends Annotation> preAuthorizeClass = loadPreAuthorizeAnnotationClass();
        Map<String, ControllerAuthPolicy> indexed = new LinkedHashMap<>();
        for (Object controller : controllers) {
            if (controller == null) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(controller);
            String basePath = extractBasePath(controller, targetClass);
            if (!StringUtils.hasText(basePath)) {
                continue;
            }
            Class<?> dtoType = resolveResponseDtoType(targetClass);
            String classExpr = extractPreAuthorizeValue(targetClass, preAuthorizeClass);
            List<String> readExprs = new ArrayList<>();
            String createExpr = null;
            String updateExpr = null;
            String deleteExpr = null;
            Map<String, ActionAuthRule> actionRules = new LinkedHashMap<>();

            for (Method method : ReflectionUtils.getAllDeclaredMethods(targetClass)) {
                if (method.isBridge() || method.isSynthetic() || method.getDeclaringClass() == Object.class) {
                    continue;
                }
                String methodExpr = extractPreAuthorizeValue(method, preAuthorizeClass);
                WorkflowAction wf = AnnotatedElementUtils.findMergedAnnotation(method, WorkflowAction.class);
                if (wf != null && StringUtils.hasText(wf.id())) {
                    Set<String> reqAuths = wf.requiredAuthorities() == null
                            ? Set.of()
                            : Arrays.stream(wf.requiredAuthorities())
                                    .filter(StringUtils::hasText)
                                    .map(String::trim)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    actionRules.put(
                            wf.id().trim().toLowerCase(Locale.ROOT),
                            new ActionAuthRule(methodExpr, reqAuths));
                } else {
                    ResourceIntent intent = AnnotatedElementUtils.findMergedAnnotation(method, ResourceIntent.class);
                    if (intent != null && StringUtils.hasText(intent.id())) {
                        UiSurface surf = AnnotatedElementUtils.findMergedAnnotation(method, UiSurface.class);
                        Set<String> reqAuths = surf != null && surf.requiredAuthorities() != null
                                ? Arrays.stream(surf.requiredAuthorities())
                                        .filter(StringUtils::hasText)
                                        .map(String::trim)
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                                : Set.of();
                        actionRules.put(
                                intent.id().trim().toLowerCase(Locale.ROOT),
                                new ActionAuthRule(methodExpr, reqAuths));
                    }
                }
                if (!StringUtils.hasText(methodExpr)) {
                    continue;
                }
                String name = method.getName();
                if ("filter".equals(name) || "findById".equals(name) || "getById".equals(name)
                        || "filterByCursor".equals(name) || "locate".equals(name)) {
                    readExprs.add(methodExpr);
                } else if ("create".equals(name)) {
                    createExpr = firstNonBlank(createExpr, methodExpr);
                } else if ("update".equals(name)) {
                    updateExpr = firstNonBlank(updateExpr, methodExpr);
                } else if ("delete".equals(name)) {
                    deleteExpr = firstNonBlank(deleteExpr, methodExpr);
                }
            }

            indexed.put(normalizePath(basePath), new ControllerAuthPolicy(
                    dtoType,
                    classExpr,
                    List.copyOf(readExprs),
                    createExpr,
                    updateExpr,
                    deleteExpr,
                    Map.copyOf(actionRules)));
        }
        return Map.copyOf(indexed);
    }

    private static String extractBasePath(Object controller, Class<?> targetClass) {
        if (controller instanceof AbstractResourceQueryController<?, ?, ?> queryController) {
            try {
                Method m = AbstractResourceQueryController.class.getDeclaredMethod("getBasePath");
                m.trySetAccessible();
                Object val = m.invoke(queryController);
                if (val instanceof String str && StringUtils.hasText(str)) {
                    return str.trim();
                }
            } catch (Exception ignored) {
            }
        }
        ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(targetClass, ApiResource.class);
        if (apiResource != null) {
            if (apiResource.value().length > 0 && StringUtils.hasText(apiResource.value()[0])) {
                return apiResource.value()[0].trim();
            }
            if (apiResource.path().length > 0 && StringUtils.hasText(apiResource.path()[0])) {
                return apiResource.path()[0].trim();
            }
        }
        RequestMapping req = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
        if (req != null) {
            if (req.path().length > 0 && StringUtils.hasText(req.path()[0])) {
                return req.path()[0].trim();
            }
            if (req.value().length > 0 && StringUtils.hasText(req.value()[0])) {
                return req.value()[0].trim();
            }
        }
        return null;
    }

    private static Class<?> resolveResponseDtoType(Class<?> targetClass) {
        if (!AbstractResourceQueryController.class.isAssignableFrom(targetClass)) {
            return Object.class;
        }
        Class<?> resolved = ResolvableType.forClass(targetClass)
                .as(AbstractResourceQueryController.class)
                .getGeneric(0)
                .resolve(Object.class);
        return resolved != null ? resolved : Object.class;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> loadPreAuthorizeAnnotationClass() {
        try {
            return (Class<? extends Annotation>) Class.forName(
                    "org.springframework.security.access.prepost.PreAuthorize");
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }

    private static String extractPreAuthorizeValue(
            java.lang.reflect.AnnotatedElement element,
            Class<? extends Annotation> preAuthorizeClass) {
        if (element == null || preAuthorizeClass == null) {
            return null;
        }
        Annotation ann = AnnotatedElementUtils.findMergedAnnotation(element, preAuthorizeClass);
        if (ann == null) {
            return null;
        }
        try {
            Object val = preAuthorizeClass.getMethod("value").invoke(ann);
            return val instanceof String str && StringUtils.hasText(str) ? str.trim() : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static boolean evaluatePreAuthorize(String expression, AgentExecutionContext context) {
        if (!StringUtils.hasText(expression)) {
            return true;
        }
        String trimmed = stripOuterParentheses(expression.trim());
        List<String> orParts = splitTopLevelLogical(trimmed, "or", "||");
        if (orParts.size() > 1) {
            for (String part : orParts) {
                if (evaluatePreAuthorize(part, context)) {
                    return true;
                }
            }
            return false;
        }
        List<String> andParts = splitTopLevelLogical(trimmed, "and", "&&");
        if (andParts.size() > 1) {
            for (String part : andParts) {
                if (!evaluatePreAuthorize(part, context)) {
                    return false;
                }
            }
            return true;
        }
        if (trimmed.startsWith("!")) {
            return !evaluatePreAuthorize(trimmed.substring(1).trim(), context);
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ("permitall()".equals(lower)) {
            return true;
        }
        if ("denyall()".equals(lower)) {
            return false;
        }
        if ("isauthenticated()".equals(lower)) {
            return context != null && (StringUtils.hasText(context.userId())
                    || (context.authorities() != null && !context.authorities().isEmpty()));
        }
        if ("isanonymous()".equals(lower)) {
            return context == null || (!StringUtils.hasText(context.userId())
                    && (context.authorities() == null || context.authorities().isEmpty()));
        }
        Matcher callMatcher = AUTHORITY_CALL_PATTERN.matcher(trimmed);
        if (callMatcher.matches()) {
            String fn = callMatcher.group(1);
            List<String> required = extractQuotedLiterals(callMatcher.group(2));
            if (required.isEmpty()) {
                return false;
            }
            boolean roleMode = "hasRole".equals(fn) || "hasAnyRole".equals(fn);
            for (String req : required) {
                String expected = roleMode && !req.startsWith("ROLE_") ? "ROLE_" + req : req;
                if (context != null && context.hasAuthority(expected)) {
                    return true;
                }
            }
            return false;
        }
        // Expressao SpEL customizada nao reconhecida: falha fechada para evitar bypass em processo.
        return false;
    }

    private static String stripOuterParentheses(String expr) {
        String current = expr;
        while (current.startsWith("(") && current.endsWith(")")) {
            int depth = 0;
            boolean inQuote = false;
            char quoteChar = 0;
            boolean wrapsWhole = true;
            for (int i = 0; i < current.length(); i++) {
                char c = current.charAt(i);
                if (inQuote) {
                    if (c == quoteChar) {
                        inQuote = false;
                    }
                } else if (c == '\'' || c == '"') {
                    inQuote = true;
                    quoteChar = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0 && i < current.length() - 1) {
                        wrapsWhole = false;
                        break;
                    }
                }
            }
            if (!wrapsWhole || depth != 0) {
                break;
            }
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static List<String> splitTopLevelLogical(String expr, String keywordOp, String symbolOp) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                if (expr.regionMatches(i, symbolOp, 0, symbolOp.length())) {
                    parts.add(expr.substring(start, i).trim());
                    i += symbolOp.length() - 1;
                    start = i + 1;
                } else if (Character.isWhitespace(c)) {
                    int opStart = i + 1;
                    if (expr.regionMatches(true, opStart, keywordOp, 0, keywordOp.length())) {
                        int opEnd = opStart + keywordOp.length();
                        if (opEnd < expr.length() && Character.isWhitespace(expr.charAt(opEnd))) {
                            parts.add(expr.substring(start, i).trim());
                            start = opEnd + 1;
                            i = opEnd;
                        }
                    }
                }
            }
        }
        if (parts.isEmpty()) {
            return List.of(expr);
        }
        parts.add(expr.substring(start).trim());
        return parts;
    }

    private static List<String> extractQuotedLiterals(String rawArgs) {
        List<String> values = new ArrayList<>();
        Matcher matcher = QUOTED_LITERAL_PATTERN.matcher(rawArgs);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static String normalizePath(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : (StringUtils.hasText(second) ? second : null);
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

    private static boolean hasAllAuthorities(AgentExecutionContext context, Set<String> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (context == null || context.authorities() == null || context.authorities().isEmpty()) {
            return false;
        }
        return context.authorities().containsAll(required);
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
        Set<String> editableAuthorities = uiSchema == null || uiSchema.editableForAuthorities() == null
                ? Set.of()
                : Arrays.stream(uiSchema.editableForAuthorities())
                        .filter(StringUtils::hasText)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean readOnly = uiSchema != null && uiSchema.readOnly();
        AiVisibilityMode aiMode = governance != null && governance.aiUsage() != null
                ? governance.aiUsage().visibility()
                : AiVisibilityMode.ALLOW;
        DomainClassification classification = governance != null
                ? governance.classification()
                : DomainClassification.INTERNAL;
        return new FieldGovernanceRule(
                uiSchema != null,
                visibleAuthorities,
                editableAuthorities,
                readOnly,
                aiMode,
                classification);
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
