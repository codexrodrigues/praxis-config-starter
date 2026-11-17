package org.praxisplatform.config.service;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernanceKind;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Ponte isolada para descoberta de chaves sensíveis anotadas com {@link DomainGovernance} e
 * {@link Filterable} quando {@code praxis-metadata-starter} está disponível no classpath.
 */
public final class MetadataGovernanceSensitiveCriteriaDiscoverer implements SensitiveCriteriaDiscoverySource {

    private static final Set<String> SENSITIVE_COMPLIANCE_MARKERS = Set.of(
            "lgpd", "pii", "sensitive", "financial", "credential", "pci", "hipaa", "security");

    private final List<String> basePackages;
    private final List<Class<?>> explicitClasses;
    private final ClassLoader classLoader;

    public MetadataGovernanceSensitiveCriteriaDiscoverer(List<String> basePackages) {
        this(basePackages, List.of(), MetadataGovernanceSensitiveCriteriaDiscoverer.class.getClassLoader());
    }

    public MetadataGovernanceSensitiveCriteriaDiscoverer(
            List<String> basePackages, List<Class<?>> explicitClasses) {
        this(basePackages, explicitClasses, MetadataGovernanceSensitiveCriteriaDiscoverer.class.getClassLoader());
    }

    public MetadataGovernanceSensitiveCriteriaDiscoverer(
            List<String> basePackages, List<Class<?>> explicitClasses, ClassLoader classLoader) {
        this.basePackages = basePackages == null ? List.of() : List.copyOf(basePackages);
        this.explicitClasses = explicitClasses == null ? List.of() : List.copyOf(explicitClasses);
        this.classLoader = classLoader != null
                ? classLoader
                : MetadataGovernanceSensitiveCriteriaDiscoverer.class.getClassLoader();
    }

    @Override
    public Set<String> discoverNormalizedSensitiveKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Class<?> dtoClass : explicitClasses) {
            inspectClassHierarchy(dtoClass, keys);
        }
        if (!basePackages.isEmpty()) {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter((metadataReader, factory) -> true);
            for (String basePackage : basePackages) {
                if (!StringUtils.hasText(basePackage)) {
                    continue;
                }
                for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage.trim())) {
                    String className = candidate.getBeanClassName();
                    if (!StringUtils.hasText(className)) {
                        continue;
                    }
                    try {
                        Class<?> type = ClassUtils.forName(className, classLoader);
                        inspectClassHierarchy(type, keys);
                    } catch (Throwable ignored) {
                        // Ignore optional or unresolvable classes during classpath scan.
                    }
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public static Set<String> inspectDtoClasses(Collection<Class<?>> dtoClasses) {
        if (dtoClasses == null || dtoClasses.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (Class<?> dtoClass : dtoClasses) {
            inspectClassHierarchy(dtoClass, keys);
        }
        return Collections.unmodifiableSet(keys);
    }

    private static void inspectClassHierarchy(Class<?> type, Set<String> targetKeys) {
        for (Class<?> current = type;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                DomainGovernance governance = field.getAnnotation(DomainGovernance.class);
                if (governance == null || !isSensitive(governance)) {
                    continue;
                }
                addNormalizedKey(targetKeys, field.getName());
                Filterable filterable = field.getAnnotation(Filterable.class);
                if (filterable != null) {
                    addRelationKeys(targetKeys, filterable.relation());
                    addRelationKeys(targetKeys, filterable.endRelation());
                    for (String relation : filterable.relations()) {
                        addRelationKeys(targetKeys, relation);
                    }
                }
            }
        }
    }

    static boolean isSensitive(DomainGovernance governance) {
        DomainGovernancePreset preset = governance.preset();
        if (preset != null && preset != DomainGovernancePreset.NONE) {
            if (isSensitiveClassification(preset.classification())
                    || isSensitiveCategory(preset.dataCategory())
                    || !preset.exportable()
                    || preset.aiVisibility() != AiVisibilityMode.ALLOW
                    || preset.kind() == DomainGovernanceKind.PRIVACY
                    || preset.kind() == DomainGovernanceKind.SECURITY
                    || hasSensitiveTag(preset.complianceTags())) {
                return true;
            }
        }
        return isSensitiveClassification(governance.classification())
                || isSensitiveCategory(governance.dataCategory())
                || !governance.exportable()
                || (governance.aiUsage() != null
                        && governance.aiUsage().visibility() != AiVisibilityMode.ALLOW)
                || governance.kind() == DomainGovernanceKind.SECURITY
                || hasSensitiveTag(List.of(governance.complianceTags()));
    }

    private static boolean isSensitiveClassification(DomainClassification classification) {
        return classification == DomainClassification.CONFIDENTIAL
                || classification == DomainClassification.RESTRICTED;
    }

    private static boolean isSensitiveCategory(DomainDataCategory category) {
        return category == DomainDataCategory.CREDENTIAL
                || category == DomainDataCategory.SENSITIVE_PERSONAL
                || category == DomainDataCategory.PERSONAL
                || category == DomainDataCategory.FINANCIAL;
    }

    private static boolean hasSensitiveTag(Collection<String> tags) {
        if (tags == null) {
            return false;
        }
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String lower = tag.trim().toLowerCase(Locale.ROOT);
            for (String marker : SENSITIVE_COMPLIANCE_MARKERS) {
                if (lower.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addRelationKeys(Set<String> target, String relation) {
        if (!StringUtils.hasText(relation)) {
            return;
        }
        String trimmed = relation.trim();
        addNormalizedKey(target, trimmed);
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < trimmed.length()) {
            addNormalizedKey(target, trimmed.substring(lastDot + 1));
        }
    }

    private static void addNormalizedKey(Set<String> target, String raw) {
        String normalized = SensitiveTenantConfigGuard.normalizeKey(raw);
        if (StringUtils.hasText(normalized)) {
            target.add(normalized);
        }
    }

    @SuppressWarnings("unused")
    private static AnnotationTypeFilter markerTypeCheck() {
        return new AnnotationTypeFilter(DomainGovernance.class);
    }
}
