package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHead;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSet;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetScope;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedConceptRootSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedRuleDocumentationSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedRuleLegalBasisSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyRegistry;
import org.praxisplatform.config.service.DomainRuleProductProfile;
import org.praxisplatform.config.service.RuleLegalBasisUrlValidator;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;
import org.praxisplatform.uischema.domain.ConceptRootBinding;
import org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider;
import org.praxisplatform.uischema.domain.DomainRuleSetDescriptor;
import org.praxisplatform.uischema.domain.RuleDocumentationDescriptor;
import org.praxisplatform.uischema.domain.RuleLegalBasisDescriptor;
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
        return createProvider(familyRegistry, headSetReaderProvider, null, null, defaultEnvironment);
    }

    static DomainRuleSetCatalogProvider createProvider(
            GovernedRuleFamilyRegistry familyRegistry,
            ObjectProvider<PublishedRuleSnapshotHeadSetReader> headSetReaderProvider,
            ObjectProvider<DomainRuleDefinitionRepository> definitionRepositoryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            String defaultEnvironment) {
        return (resourceKey, tenantId) -> {
            if (resourceKey == null || resourceKey.isBlank()) {
                return List.of();
            }
            String normalizedResourceKey = resourceKey.trim();
            PublishedRuleSnapshotHeadSetReader reader =
                    headSetReaderProvider != null ? headSetReaderProvider.getIfAvailable() : null;
            DomainRuleDefinitionRepository definitionRepository =
                    definitionRepositoryProvider != null
                            ? definitionRepositoryProvider.getIfAvailable()
                            : null;
            ObjectMapper objectMapper =
                    objectMapperProvider != null
                            ? objectMapperProvider.getIfAvailable(ObjectMapper::new)
                            : new ObjectMapper();
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
                    Optional<PublishedRuleSnapshot> activeSnapshot =
                            activeHeadSet
                                    .map(set -> set.heads().get(productDef.ref().operationKey()))
                                    .map(PublishedRuleSnapshotHead::snapshot);
                    RuleSetDefinition effectiveDef =
                            activeSnapshot.map(PublishedRuleSnapshot::ruleSet).orElse(productDef);

                    Map<String, ConceptRootBinding> conceptBindings =
                            mapConceptBindings(family, effectiveDef);
                    RuleDocumentationDescriptor ruleSetDoc =
                            toRuleDoc(family.ruleSetDocumentation().orElse(null));
                    Map<String, RuleDocumentationDescriptor> mergedSlotDocs =
                            toRuleDocsMap(family.slotDocumentation());
                    Map<String, RuleDocumentationDescriptor> mergedRuleDocs =
                            toRuleDocsMap(family.ruleDocumentation());

                    propagateBaselineDocsAcrossCustomerRenames(
                            productDef, effectiveDef, mergedRuleDocs);

                    if (activeSnapshot.isPresent() && definitionRepository != null) {
                        mergeTenantDefinitionsDocumentation(
                                activeSnapshot.get(),
                                effectiveDef,
                                tenantId,
                                definitionRepository,
                                objectMapper,
                                mergedSlotDocs,
                                mergedRuleDocs);
                    }

                    descriptors.add(
                            DomainRuleSetDescriptor.of(
                                    normalizedResourceKey,
                                    effectiveDef,
                                    family.title().orElse(null),
                                    family.description().orElse(null),
                                    family.reservedProductErrorCatalog().orElse(null),
                                    conceptBindings,
                                    mergedSlotDocs,
                                    mergedRuleDocs,
                                    ruleSetDoc));
                }
            }
            return List.copyOf(descriptors);
        };
    }

    private static void propagateBaselineDocsAcrossCustomerRenames(
            RuleSetDefinition productDef,
            RuleSetDefinition effectiveDef,
            Map<String, RuleDocumentationDescriptor> mergedRuleDocs) {
        if (mergedRuleDocs.isEmpty()
                || productDef == null
                || effectiveDef == null
                || effectiveDef.bindings() == null) {
            return;
        }
        Map<String, DecisionSlot> slotsByKey = indexSlots(effectiveDef);
        Map<String, DecisionBinding> productBindingsBySlot = new LinkedHashMap<>();
        if (productDef.bindings() != null) {
            for (DecisionBinding pb : productDef.bindings()) {
                if (pb != null && pb.enabled() && pb.source() == DecisionSource.PRODUCT) {
                    productBindingsBySlot.putIfAbsent(pb.slotKey(), pb);
                }
            }
        }
        for (DecisionBinding binding : effectiveDef.bindings()) {
            if (binding == null
                    || !binding.enabled()
                    || binding.source() != DecisionSource.CUSTOMER) {
                continue;
            }
            if (binding.compositionPolicy() != CompositionPolicy.PARAMETERIZE
                    && binding.compositionPolicy() != CompositionPolicy.REPLACE_EXACT) {
                continue;
            }
            if (mergedRuleDocs.containsKey(binding.bindingKey())) {
                continue;
            }
            RuleDocumentationDescriptor baseline = null;
            DecisionBinding productBinding = productBindingsBySlot.get(binding.slotKey());
            if (productBinding != null) {
                baseline = mergedRuleDocs.get(productBinding.bindingKey());
                if (baseline == null) {
                    String productCode =
                            resolveBindingReasonCode(productBinding, slotsByKey.get(binding.slotKey()), productDef);
                    if (productCode != null) {
                        baseline = mergedRuleDocs.get(productCode);
                    }
                }
            }
            if (baseline == null) {
                String effectiveCode =
                        resolveBindingReasonCode(binding, slotsByKey.get(binding.slotKey()), effectiveDef);
                if (effectiveCode != null) {
                    baseline = mergedRuleDocs.get(effectiveCode);
                }
            }
            if (baseline != null && !baseline.isEmpty()) {
                mergedRuleDocs.putIfAbsent(binding.bindingKey(), baseline);
            }
        }
    }

    private static void mergeTenantDefinitionsDocumentation(
            PublishedRuleSnapshot activeSnapshot,
            RuleSetDefinition effectiveDef,
            String tenantId,
            DomainRuleDefinitionRepository definitionRepository,
            ObjectMapper objectMapper,
            Map<String, RuleDocumentationDescriptor> mergedSlotDocs,
            Map<String, RuleDocumentationDescriptor> mergedRuleDocs) {
        Set<UUID> definitionIds = new LinkedHashSet<>();
        if (activeSnapshot.sources() != null) {
            for (RuleSnapshotSource source : activeSnapshot.sources()) {
                if (source == null || source.definitionId() == null) {
                    continue;
                }
                String rawId = source.definitionId().trim();
                if (rawId.isEmpty() || rawId.startsWith("product:")) {
                    continue;
                }
                try {
                    definitionIds.add(UUID.fromString(rawId));
                } catch (IllegalArgumentException ignored) {
                    // Synthetic or non-UUID source ids do not map to repository rows.
                }
            }
        }
        if (definitionIds.isEmpty()) {
            return;
        }
        List<DomainRuleDefinition> loaded;
        try {
            loaded = definitionRepository.findAllById(definitionIds);
        } catch (RuntimeException ex) {
            log.debug(
                    "Skipping tenant didactic documentation enrichment for ruleSet '{}': {}",
                    effectiveDef.ref() != null ? effectiveDef.ref().ruleSetKey() : "unknown",
                    ex.getClass().getSimpleName());
            return;
        }
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        String normalizedTenant = normalizeNullable(tenantId);
        Map<String, DecisionSlot> slotsByKey = indexSlots(effectiveDef);
        for (DomainRuleDefinition def : loaded) {
            if (def == null) {
                continue;
            }
            if (normalizedTenant != null
                    && def.getTenantId() != null
                    && !normalizedTenant.equals(def.getTenantId().trim())) {
                continue;
            }
            JsonNode defJson = parseJson(objectMapper, def.getDefinition());
            if (defJson == null || !defJson.isObject()) {
                continue;
            }
            String defSlotKey = text(defJson, "slotKey");
            String defBindingKey = text(defJson, "bindingKey");
            String defReasonCode = text(defJson, "falseReasonCode");

            JsonNode slotDocNode = defJson.path("slotDocumentation");
            if (defSlotKey != null && slotDocNode.isObject()) {
                ParsedDoc parsedSlotDoc = extractDocumentation(slotDocNode, slotDocNode);
                if (!parsedSlotDoc.isEmpty()) {
                    RuleDocumentationDescriptor baseSlotDoc = mergedSlotDocs.get(defSlotKey);
                    mergedSlotDocs.put(defSlotKey, mergeParsed(baseSlotDoc, parsedSlotDoc));
                }
            }

            ParsedDoc parsedRuleDoc = extractDocumentation(defJson.path("documentation"), defJson);
            if (parsedRuleDoc.isEmpty()) {
                continue;
            }
            List<DecisionBinding> matchedBindings =
                    matchTargetBindings(effectiveDef, def, defSlotKey, defBindingKey, defReasonCode);
            for (DecisionBinding matched : matchedBindings) {
                String effectiveCode =
                        resolveBindingReasonCode(matched, slotsByKey.get(matched.slotKey()), effectiveDef);
                RuleDocumentationDescriptor baseRuleDoc = mergedRuleDocs.get(matched.bindingKey());
                if (baseRuleDoc == null && effectiveCode != null) {
                    baseRuleDoc = mergedRuleDocs.get(effectiveCode);
                }
                RuleDocumentationDescriptor merged = mergeParsed(baseRuleDoc, parsedRuleDoc);
                if (merged != null && !merged.isEmpty()) {
                    mergedRuleDocs.put(matched.bindingKey(), merged);
                    if (effectiveCode != null && isUnambiguousReasonCode(effectiveDef, effectiveCode)) {
                        mergedRuleDocs.put(effectiveCode, merged);
                    }
                }
            }
        }
    }

    private static List<DecisionBinding> matchTargetBindings(
            RuleSetDefinition effectiveDef,
            DomainRuleDefinition def,
            String defSlotKey,
            String defBindingKey,
            String defReasonCode) {
        if (effectiveDef.bindings() == null || effectiveDef.bindings().isEmpty()) {
            return List.of();
        }
        List<DecisionBinding> enabled =
                effectiveDef.bindings().stream()
                        .filter(b -> b != null && b.enabled())
                        .toList();
        if (enabled.isEmpty()) {
            return List.of();
        }
        String ruleKey = normalizeNullable(def.getRuleKey());

        // 1. Direct bindingKey match
        List<DecisionBinding> directMatches = new ArrayList<>();
        for (DecisionBinding b : enabled) {
            if ((defBindingKey != null && defBindingKey.equals(b.bindingKey()))
                    || (ruleKey != null && ruleKey.equals(b.bindingKey()))) {
                directMatches.add(b);
            }
        }
        if (!directMatches.isEmpty()) {
            return directMatches;
        }

        // 2. Reason-code match (scoped by slotKey if present)
        if (defReasonCode != null) {
            String normalizedAugmentCode =
                    (!defReasonCode.startsWith("CUST-") && !defReasonCode.startsWith("CERG-"))
                            ? normalizeCustomerAugmentReasonCode(defReasonCode)
                            : null;
            List<DecisionBinding> reasonMatches = new ArrayList<>();
            Map<String, DecisionSlot> slotsByKey = indexSlots(effectiveDef);
            for (DecisionBinding b : enabled) {
                if (defSlotKey != null && !defSlotKey.equals(b.slotKey())) {
                    continue;
                }
                String bindingReason =
                        resolveBindingReasonCode(b, slotsByKey.get(b.slotKey()), effectiveDef);
                boolean directReasonMatch = defReasonCode.equals(bindingReason);
                boolean augmentNormalizedMatch =
                        normalizedAugmentCode != null
                                && b.source() == DecisionSource.CUSTOMER
                                && b.compositionPolicy() == CompositionPolicy.AUGMENT
                                && normalizedAugmentCode.equals(bindingReason);
                if (directReasonMatch || augmentNormalizedMatch) {
                    reasonMatches.add(b);
                }
            }
            if (!reasonMatches.isEmpty()) {
                return reasonMatches;
            }
        }

        // 3. Customer slot-level fallback for PARAMETERIZE / REPLACE_EXACT (single active binding per slot)
        if (defSlotKey != null) {
            List<DecisionBinding> slotMatches = new ArrayList<>();
            for (DecisionBinding b : enabled) {
                if (defSlotKey.equals(b.slotKey())
                        && b.source() == DecisionSource.CUSTOMER
                        && (b.compositionPolicy() == CompositionPolicy.PARAMETERIZE
                                || b.compositionPolicy() == CompositionPolicy.REPLACE_EXACT)) {
                    slotMatches.add(b);
                }
            }
            if (!slotMatches.isEmpty()) {
                return slotMatches;
            }
        }
        return List.of();
    }

    private static boolean isUnambiguousReasonCode(RuleSetDefinition def, String reasonCode) {
        if (def == null || def.bindings() == null || reasonCode == null) {
            return false;
        }
        Map<String, DecisionSlot> slotsByKey = indexSlots(def);
        int matches = 0;
        for (DecisionBinding b : def.bindings()) {
            if (b != null && b.enabled()) {
                String code = resolveBindingReasonCode(b, slotsByKey.get(b.slotKey()), def);
                if (reasonCode.equals(code)) {
                    matches++;
                }
            }
        }
        return matches <= 1;
    }

    private static Map<String, DecisionSlot> indexSlots(RuleSetDefinition def) {
        Map<String, DecisionSlot> slotsByKey = new LinkedHashMap<>();
        if (def != null && def.slots() != null) {
            for (DecisionSlot slot : def.slots()) {
                if (slot != null && slot.slotKey() != null) {
                    slotsByKey.put(slot.slotKey(), slot);
                }
            }
        }
        return slotsByKey;
    }

    private static String resolveBindingReasonCode(
            DecisionBinding binding, DecisionSlot slot, RuleSetDefinition def) {
        if (binding == null) {
            return null;
        }
        String direct = normalizeNullable(binding.falseReasonCode());
        if (direct != null) {
            return direct;
        }
        if (def != null && def.errorDescriptors() != null) {
            RuleErrorDescriptor err = def.errorDescriptors().get(binding.bindingKey());
            if (err != null && normalizeNullable(err.code()) != null) {
                return normalizeNullable(err.code());
            }
        }
        if (slot != null && slot.parameterTemplate() != null) {
            return normalizeNullable(slot.parameterTemplate().falseReasonCode());
        }
        return null;
    }

    private static String normalizeCustomerAugmentReasonCode(String rawReasonCode) {
        if (rawReasonCode == null || rawReasonCode.isBlank()) {
            return null;
        }
        String trimmed = rawReasonCode.trim();
        if (trimmed.startsWith("CUST-") || trimmed.startsWith("CERG-")) {
            return trimmed.length() > 48 ? trimmed.substring(0, 48) : trimmed;
        }
        String suffix =
                trimmed.toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        String candidate = "CUST-" + (suffix.isEmpty() ? "RULE" : suffix);
        return candidate.length() > 48 ? candidate.substring(0, 48) : candidate;
    }

    private static ParsedDoc extractDocumentation(JsonNode primaryNode, JsonNode fallbackNode) {
        JsonNode docObj = primaryNode != null && primaryNode.isObject() ? primaryNode : null;
        String title = firstText(text(docObj, "title"), text(fallbackNode, "title"));
        String summary = firstText(text(docObj, "summary"), text(fallbackNode, "summary"));
        String whenApplies =
                firstText(text(docObj, "whenApplies"), text(fallbackNode, "whenApplies"));
        String rationale = firstText(text(docObj, "rationale"), text(fallbackNode, "rationale"));
        String customizationGuidance =
                firstText(
                        text(docObj, "customizationGuidance"),
                        text(fallbackNode, "customizationGuidance"));

        boolean hasExplicitLegalBasis = false;
        JsonNode basisNode = null;
        if (docObj != null && docObj.has("legalBasis")) {
            hasExplicitLegalBasis = true;
            basisNode = docObj.get("legalBasis");
        } else if (fallbackNode != null && fallbackNode.isObject() && fallbackNode.has("legalBasis")) {
            hasExplicitLegalBasis = true;
            basisNode = fallbackNode.get("legalBasis");
        }
        List<RuleLegalBasisDescriptor> legalBasis = parseLegalBasis(basisNode);
        return new ParsedDoc(
                title,
                summary,
                whenApplies,
                rationale,
                legalBasis,
                hasExplicitLegalBasis,
                customizationGuidance);
    }

    private static List<RuleLegalBasisDescriptor> parseLegalBasis(JsonNode basisNode) {
        if (basisNode == null || !basisNode.isArray() || basisNode.isEmpty()) {
            return List.of();
        }
        List<RuleLegalBasisDescriptor> result = new ArrayList<>();
        for (JsonNode item : basisNode) {
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                String textVal = normalizeNullable(item.asText());
                if (textVal != null) {
                    result.add(RuleLegalBasisDescriptor.of(textVal, textVal, null));
                }
                continue;
            }
            if (item.isObject()) {
                String code = text(item, "code");
                String title = text(item, "title");
                String article = text(item, "article");
                String jurisdiction = text(item, "jurisdiction");
                String summary = text(item, "summary");
                String url = RuleLegalBasisUrlValidator.sanitizeOptionalHttpUrl(text(item, "url"));
                String effectiveCode = firstText(code, title, article, summary);
                if (effectiveCode != null) {
                    result.add(
                            new RuleLegalBasisDescriptor(
                                    effectiveCode, title, article, jurisdiction, summary, url));
                }
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    private static RuleDocumentationDescriptor mergeParsed(
            RuleDocumentationDescriptor base, ParsedDoc override) {
        if (override == null || override.isEmpty()) {
            return base;
        }
        List<RuleLegalBasisDescriptor> effectiveBasis;
        if (override.hasExplicitLegalBasis()) {
            effectiveBasis = override.legalBasis();
        } else {
            effectiveBasis = base != null ? base.legalBasis() : List.of();
        }
        RuleDocumentationDescriptor candidate =
                new RuleDocumentationDescriptor(
                        firstText(override.title(), base != null ? base.title() : null),
                        firstText(override.summary(), base != null ? base.summary() : null),
                        firstText(override.whenApplies(), base != null ? base.whenApplies() : null),
                        firstText(override.rationale(), base != null ? base.rationale() : null),
                        effectiveBasis,
                        firstText(
                                override.customizationGuidance(),
                                base != null ? base.customizationGuidance() : null));
        return candidate.isEmpty() ? null : candidate;
    }

    private static Map<String, RuleDocumentationDescriptor> toRuleDocsMap(
            Map<String, GovernedRuleDocumentationSpec> specs) {
        Map<String, RuleDocumentationDescriptor> result = new LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            return result;
        }
        specs.forEach(
                (key, spec) -> {
                    RuleDocumentationDescriptor converted = toRuleDoc(spec);
                    if (key != null && !key.isBlank() && converted != null) {
                        result.put(key.trim(), converted);
                    }
                });
        return result;
    }

    private static RuleDocumentationDescriptor toRuleDoc(GovernedRuleDocumentationSpec spec) {
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        List<RuleLegalBasisDescriptor> basis = new ArrayList<>();
        for (GovernedRuleLegalBasisSpec item : spec.legalBasis()) {
            if (item == null) {
                continue;
            }
            String effectiveCode =
                    firstText(item.code(), item.title(), item.article(), item.summary());
            if (effectiveCode != null) {
                basis.add(
                        new RuleLegalBasisDescriptor(
                                effectiveCode,
                                item.title(),
                                item.article(),
                                item.jurisdiction(),
                                item.summary(),
                                RuleLegalBasisUrlValidator.sanitizeOptionalHttpUrl(item.url())));
            }
        }
        RuleDocumentationDescriptor descriptor =
                new RuleDocumentationDescriptor(
                        spec.title(),
                        spec.summary(),
                        spec.whenApplies(),
                        spec.rationale(),
                        basis,
                        spec.customizationGuidance());
        return descriptor.isEmpty() ? null : descriptor;
    }

    private static JsonNode parseJson(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode val = node.get(field);
        if (val == null || !val.isTextual()) {
            return null;
        }
        return normalizeNullable(val.asText());
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private record ParsedDoc(
            String title,
            String summary,
            String whenApplies,
            String rationale,
            List<RuleLegalBasisDescriptor> legalBasis,
            boolean hasExplicitLegalBasis,
            String customizationGuidance) {
        boolean isEmpty() {
            return title == null
                    && summary == null
                    && whenApplies == null
                    && rationale == null
                    && !hasExplicitLegalBasis
                    && customizationGuidance == null;
        }
    }
}
