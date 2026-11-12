package org.praxisplatform.config.autoconfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHead;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSet;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetScope;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedConceptRootSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyRegistry;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.uischema.domain.ConceptRootBinding;
import org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider;
import org.praxisplatform.uischema.domain.DomainRuleSetDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Isolates bytecode references to optional {@code praxis-metadata-starter} types so {@link
 * GovernedRulesStudioAutoConfiguration} loads cleanly when {@code praxis-metadata-starter} is not
 * on the host classpath.
 */
final class MetadataDomainRuleSetCatalogBridgeSupport {

    private static final Logger log =
            LoggerFactory.getLogger(MetadataDomainRuleSetCatalogBridgeSupport.class);

    private MetadataDomainRuleSetCatalogBridgeSupport() {}

    static DomainRuleSetCatalogProvider createProvider(
            GovernedRuleFamilyRegistry familyRegistry,
            ObjectProvider<PublishedRuleSnapshotHeadSetReader> headSetReaderProvider,
            String defaultEnvironment) {
        return (resourceKey, tenantId) -> {
            if (resourceKey == null || resourceKey.isBlank()) {
                return List.of();
            }
            String normalizedResourceKey = resourceKey.trim();
            PublishedRuleSnapshotHeadSetReader reader = headSetReaderProvider.getIfAvailable();
            List<DomainRuleSetDescriptor> descriptors = new ArrayList<>();

            for (GovernedRuleFamilyDescriptor<?> family : familyRegistry.families()) {
                boolean matchesFamilyResource =
                        normalizedResourceKey.equals(family.resolvedResourceKey());
                boolean matchesAnyProfile =
                        matchesFamilyResource
                                || family.productProfiles().stream()
                                        .anyMatch(
                                                profile ->
                                                        normalizedResourceKey.equals(
                                                                profile.ruleSet()
                                                                        .ref()
                                                                        .boundedContextKey()));
                if (!matchesAnyProfile) {
                    continue;
                }
                Optional<PublishedRuleSnapshotHeadSet> activeHeadSet =
                        resolveActiveHeadSet(reader, tenantId, defaultEnvironment, family);

                for (DomainRuleProductProfile profile : family.productProfiles()) {
                    RuleSetDefinition productDef = profile.ruleSet();
                    if (!matchesFamilyResource
                            && !normalizedResourceKey.equals(productDef.ref().boundedContextKey())) {
                        continue;
                    }
                    RuleSetDefinition effectiveDef =
                            activeHeadSet
                                    .map(set -> set.heads().get(productDef.ref().operationKey()))
                                    .map(PublishedRuleSnapshotHead::snapshot)
                                    .map(snapshot -> snapshot.ruleSet())
                                    .orElse(productDef);

                    Map<String, ConceptRootBinding> conceptBindings =
                            mapConceptBindings(family, effectiveDef);
                    descriptors.add(
                            DomainRuleSetDescriptor.of(
                                    normalizedResourceKey,
                                    effectiveDef,
                                    family.title().orElse(null),
                                    family.description().orElse(null),
                                    family.reservedProductErrorCatalog().orElse(null),
                                    conceptBindings));
                }
            }
            return List.copyOf(descriptors);
        };
    }

    private static Optional<PublishedRuleSnapshotHeadSet> resolveActiveHeadSet(
            PublishedRuleSnapshotHeadSetReader reader,
            String tenantId,
            String defaultEnvironment,
            GovernedRuleFamilyDescriptor<?> family) {
        if (reader == null || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        try {
            return reader.findActive(
                    new PublishedRuleSnapshotHeadSetScope(
                            tenantId.trim(),
                            defaultEnvironment == null || defaultEnvironment.isBlank()
                                    ? "local"
                                    : defaultEnvironment.trim(),
                            family.familyKey(),
                            family.operationKeys()));
        } catch (RuntimeException ex) {
            log.debug(
                    "Falling back to product baseline for governed rule family '{}' on catalog lookup: {}",
                    family.familyKey(),
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Map<String, ConceptRootBinding> mapConceptBindings(
            GovernedRuleFamilyDescriptor<?> family, RuleSetDefinition definition) {
        Map<String, ConceptRootBinding> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, GovernedConceptRootSpec> entry :
                family.conceptRootSpecs().entrySet()) {
            String root = entry.getKey();
            if (!definition.availableRoots().contains(root)) {
                continue;
            }
            GovernedConceptRootSpec spec = entry.getValue();
            mapped.put(
                    root,
                    new ConceptRootBinding(
                            spec.role(), spec.serviceKey(), spec.contextKey(), spec.resourceKey()));
        }
        for (String conceptRoot : definition.conceptRoots()) {
            mapped.computeIfAbsent(
                    conceptRoot,
                    root ->
                            new ConceptRootBinding(
                                    root,
                                    family.ownerServiceKey(),
                                    definition.ref().boundedContextKey(),
                                    family.resolvedResourceKey()));
        }
        return Map.copyOf(mapped);
    }
}
