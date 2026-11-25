package org.praxisplatform.config.runtime;

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
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleFactDescriptor;
import org.praxisplatform.config.service.DomainRuleFactMetadataOverlayProvider;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.facts.RuleFactDescriptor;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactConstraints;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactOption;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactOptionSourceRef;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactPropertyMetadata;
import org.springframework.util.StringUtils;

/**
 * Implementação read-only de {@link DomainRuleFactMetadataOverlayProvider} apoiada em {@link
 * DtoMetadataFactCatalogEnricher} e {@link GovernedRuleFamilyRegistry}, enriquecendo definições
 * persistidas de {@code factCatalog} a partir do catálogo curado e das anotações vivas dos DTOs
 * vinculados em tempo de leitura.
 */
public class MetadataDtoFactMetadataOverlayProvider
        implements DomainRuleFactMetadataOverlayProvider {

    private static final String DEFAULT_LOCALE = "pt-BR";
    private static final int MAX_COMPLIANCE_TAGS = 32;

    private final GovernedRuleFamilyRegistry familyRegistry;
    private final DtoMetadataFactCatalogEnricher enricher;

    public MetadataDtoFactMetadataOverlayProvider(
            GovernedRuleFamilyRegistry familyRegistry, ObjectMapper ignoredObjectMapper) {
        this(familyRegistry, new DtoMetadataFactCatalogEnricher());
    }

    public MetadataDtoFactMetadataOverlayProvider(GovernedRuleFamilyRegistry familyRegistry) {
        this(familyRegistry, new DtoMetadataFactCatalogEnricher());
    }

    MetadataDtoFactMetadataOverlayProvider(
            GovernedRuleFamilyRegistry familyRegistry,
            DtoMetadataFactCatalogEnricher enricher) {
        this.familyRegistry = Objects.requireNonNull(familyRegistry, "familyRegistry is required");
        this.enricher = Objects.requireNonNull(enricher, "enricher is required");
    }

    @Override
    public List<DomainRuleFactDescriptor> enrich(
            DomainRuleDefinitionResponse definition,
            List<DomainRuleFactDescriptor> persistedFacts) {
        if (definition == null || persistedFacts == null || persistedFacts.isEmpty()) {
            return persistedFacts;
        }
        Optional<GovernedRuleFamilyDescriptor<?>> matchingFamily = resolveMatchingFamily(definition);
        if (matchingFamily.isEmpty() || !hasFactMetadata(matchingFamily.get())) {
            return persistedFacts;
        }
        GovernedRuleFamilyDescriptor<?> family = matchingFamily.get();
        Map<String, Class<?>> rootBindings = family.rootDtoBindings();
        RuleFactCatalogDocument curatedCatalog = family.factCatalogDocument().orElse(null);
        List<DomainRuleFactDescriptor> enriched = new ArrayList<>(persistedFacts.size());
        for (DomainRuleFactDescriptor persisted : persistedFacts) {
            if (persisted == null || !StringUtils.hasText(persisted.path())) {
                enriched.add(persisted);
                continue;
            }
            Optional<DtoFactPropertyMetadata> dtoMeta =
                    rootBindings.isEmpty()
                            ? Optional.empty()
                            : enricher.resolveFactPath(persisted.path(), rootBindings);
            Optional<RuleFactDescriptor> curatedMeta =
                    curatedCatalog == null
                            ? Optional.empty()
                            : curatedCatalog.findFactAcrossOperations(persisted.path());
            if (dtoMeta.isEmpty() && curatedMeta.isEmpty()) {
                enriched.add(persisted);
                continue;
            }
            enriched.add(
                    overlayFactMetadata(
                            persisted, curatedMeta.orElse(null), dtoMeta.orElse(null)));
        }
        return List.copyOf(enriched);
    }

    private Optional<GovernedRuleFamilyDescriptor<?>> resolveMatchingFamily(
            DomainRuleDefinitionResponse definition) {
        JsonNode defJson = definition.definition();
        String ruleSetKey =
                defJson != null ? normalize(defJson.path("ruleSetKey").asText(null)) : null;
        if (ruleSetKey != null) {
            Optional<GovernedRuleFamilyDescriptor<?>> byFamily =
                    familyRegistry.findByFamilyKey(ruleSetKey);
            if (byFamily.isPresent() && hasFactMetadata(byFamily.get())) {
                return byFamily;
            }
            for (GovernedRuleFamilyDescriptor<?> family : familyRegistry.families()) {
                if (hasFactMetadata(family)
                        && family.productProfiles().stream()
                                .anyMatch(
                                        profile ->
                                                ruleSetKey.equals(
                                                        profile.ruleSet().ref().ruleSetKey()))) {
                    return Optional.of(family);
                }
            }
        }
        String resourceKey = normalize(definition.resourceKey());
        if (resourceKey != null) {
            for (GovernedRuleFamilyDescriptor<?> family : familyRegistry.families()) {
                if (hasFactMetadata(family)
                        && resourceKey.equals(normalize(family.resolvedResourceKey()))) {
                    return Optional.of(family);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasFactMetadata(GovernedRuleFamilyDescriptor<?> family) {
        return family != null
                && (!family.rootDtoBindings().isEmpty() || family.factCatalogDocument().isPresent());
    }

    private DomainRuleFactDescriptor overlayFactMetadata(
            DomainRuleFactDescriptor persisted,
            RuleFactDescriptor curated,
            DtoFactPropertyMetadata dto) {
        String persistedSensitivity =
                StringUtils.hasText(persisted.sensitivity())
                        ? RuleFactDescriptor.normalizeSensitivity(persisted.sensitivity())
                        : null;
        String curatedSensitivity =
                curated != null && StringUtils.hasText(curated.sensitivity())
                        ? RuleFactDescriptor.normalizeSensitivity(curated.sensitivity())
                        : null;
        String dtoSensitivity =
                dto != null && StringUtils.hasText(dto.sensitivity())
                        ? RuleFactDescriptor.normalizeSensitivity(dto.sensitivity())
                        : null;
        String sensitivity =
                strictestSensitivity(persistedSensitivity, curatedSensitivity, dtoSensitivity);
        String redaction =
                strictestRedaction(
                        persisted.redaction(),
                        curated != null ? normalize(curated.redaction()) : null,
                        dto != null ? normalize(dto.redaction()) : null);
        boolean restricted = isRestricted(sensitivity, redaction);

        Map<String, String> mergedLabels = new LinkedHashMap<>();
        String curatedPtLabel =
                curated != null && curated.labels() != null
                        ? normalize(curated.labels().get(DEFAULT_LOCALE))
                        : null;
        if (curated != null && curated.labels() != null) {
            curated.labels().forEach((locale, rawLabel) -> {
                String loc = normalize(locale);
                String trimmed = normalize(rawLabel);
                if (loc != null
                        && trimmed != null
                        && !DtoMetadataFactCatalogEnricher.isRawPathOrLeafLabel(
                                trimmed, persisted.path(), dto)) {
                    mergedLabels.put(
                            loc,
                            truncate(trimmed, DtoMetadataFactCatalogEnricher.MAX_LABEL_LENGTH));
                }
            });
        }
        boolean dtoHasLabel = dto != null && StringUtils.hasText(dto.labelPtBr());
        boolean curatedHasRichPtLabel =
                curatedPtLabel != null
                        && !DtoMetadataFactCatalogEnricher.isRawPathOrLeafLabel(
                                curatedPtLabel, persisted.path(), dto);
        if (dtoHasLabel || curatedHasRichPtLabel) {
            String unifiedPtLabel =
                    DtoMetadataFactCatalogEnricher.mergeEditorialLabelPtBr(
                            persisted.path(), curatedPtLabel, dto);
            if (unifiedPtLabel != null) {
                mergedLabels.put(DEFAULT_LOCALE, unifiedPtLabel);
            }
        }
        Map<String, String> labels =
                mergedLabels.isEmpty() ? persisted.labels() : Map.copyOf(mergedLabels);

        String dtoExplicitDesc = dto != null ? normalize(dto.descriptionPtBr()) : null;
        String curatedPtDesc =
                curated != null && curated.descriptions() != null
                        ? normalize(curated.descriptions().get(DEFAULT_LOCALE))
                        : null;
        Map<String, String> mergedDescriptions = new LinkedHashMap<>();
        if (curated != null && curated.descriptions() != null) {
            curated.descriptions().forEach((locale, rawDesc) -> {
                String loc = normalize(locale);
                String trimmed = normalize(rawDesc);
                if (loc == null
                        || trimmed == null
                        || DtoMetadataFactCatalogEnricher.isGenericTemplateDescription(trimmed)) {
                    return;
                }
                mergedDescriptions.put(
                        loc,
                        truncate(trimmed, DtoMetadataFactCatalogEnricher.MAX_DESCRIPTION_LENGTH));
            });
        }
        boolean curatedHasRichPtDesc =
                curatedPtDesc != null
                        && !DtoMetadataFactCatalogEnricher.isGenericTemplateDescription(
                                curatedPtDesc);
        if (dtoExplicitDesc != null
                || curatedHasRichPtDesc
                || (dto != null
                        && hasOnlyBoilerplateDescriptions(persisted.descriptions())
                        && (StringUtils.hasText(dto.labelPtBr())
                                || StringUtils.hasText(dto.helpTextPtBr())))) {
            String unifiedPtDesc =
                    DtoMetadataFactCatalogEnricher.mergeEditorialDescriptionPtBr(
                            curatedPtDesc, dto);
            if (unifiedPtDesc != null) {
                mergedDescriptions.put(DEFAULT_LOCALE, unifiedPtDesc);
            }
        }
        Map<String, String> descriptions =
                mergedDescriptions.isEmpty()
                        ? persisted.descriptions()
                        : Map.copyOf(mergedDescriptions);

        List<String> evidenceRefs = mergeEvidenceRefs(persisted.evidenceRefs(), curated, dto);

        if (dto == null) {
            DomainRuleFactDescriptor.GovernanceMetadata governance =
                    enforceRestrictedGovernance(persisted.governance(), restricted);
            return new DomainRuleFactDescriptor(
                    persisted.path(),
                    persisted.valueType(),
                    persisted.nullable(),
                    labels,
                    descriptions,
                    persisted.providerRef(),
                    evidenceRefs,
                    sensitivity,
                    redaction,
                    persisted.hints(),
                    persisted.groupKey(),
                    persisted.groupLabels(),
                    persisted.valuePresentation(),
                    persisted.unit(),
                    persisted.mask(),
                    restricted ? null : persisted.allowedValues(),
                    persisted.optionSourceRef(),
                    restricted ? null : persisted.example(),
                    persisted.constraints(),
                    governance,
                    persisted.lineage());
        }

        Map<String, String> hints = persisted.hints();
        if (StringUtils.hasText(dto.helpTextPtBr())) {
            Map<String, String> mergedHints = new LinkedHashMap<>();
            if (persisted.hints() != null) {
                mergedHints.putAll(persisted.hints());
            }
            mergedHints.put(DEFAULT_LOCALE, truncate(dto.helpTextPtBr().trim(), 500));
            hints = Map.copyOf(mergedHints);
        }
        String groupKey =
                StringUtils.hasText(dto.groupKey()) ? dto.groupKey().trim() : persisted.groupKey();
        Map<String, String> groupLabels = persisted.groupLabels();
        if (StringUtils.hasText(dto.groupLabelPtBr())) {
            Map<String, String> mergedGroupLabels = new LinkedHashMap<>();
            if (persisted.groupLabels() != null
                    && (persisted.groupKey() == null
                            || !StringUtils.hasText(dto.groupKey())
                            || Objects.equals(persisted.groupKey(), dto.groupKey().trim()))) {
                mergedGroupLabels.putAll(persisted.groupLabels());
            }
            mergedGroupLabels.put(DEFAULT_LOCALE, truncate(dto.groupLabelPtBr().trim(), 120));
            groupLabels = Map.copyOf(mergedGroupLabels);
        }

        List<DomainRuleFactDescriptor.AllowedValue> allowedValues = null;
        if (!restricted && dto.allowedValues() != null && !dto.allowedValues().isEmpty()) {
            int limit =
                    Math.min(
                            dto.allowedValues().size(),
                            DtoMetadataFactCatalogEnricher.MAX_ALLOWED_VALUES);
            List<DomainRuleFactDescriptor.AllowedValue> options = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                DtoFactOption opt = dto.allowedValues().get(i);
                String label =
                        StringUtils.hasText(opt.labelPtBr())
                                ? truncate(
                                        opt.labelPtBr().trim(),
                                        DtoMetadataFactCatalogEnricher.MAX_LABEL_LENGTH)
                                : String.valueOf(opt.value());
                options.add(
                        new DomainRuleFactDescriptor.AllowedValue(
                                opt.value(),
                                Map.of(DEFAULT_LOCALE, label),
                                normalize(opt.icon()),
                                normalize(opt.tone()),
                                normalize(opt.descriptionPtBr())));
            }
            allowedValues = List.copyOf(options);
        } else if (!restricted) {
            allowedValues = persisted.allowedValues();
        }

        DtoFactOptionSourceRef sourceRef = dto.optionSourceRef();
        DomainRuleFactDescriptor.OptionSourceRef optionSourceRef =
                sourceRef != null && StringUtils.hasText(sourceRef.sourceKey())
                        ? new DomainRuleFactDescriptor.OptionSourceRef(
                                sourceRef.sourceKey().trim(), normalize(sourceRef.resourceKey()))
                        : persisted.optionSourceRef();

        DtoFactConstraints rawConstraints = dto.constraints();
        DomainRuleFactDescriptor.Constraints constraints =
                rawConstraints != null && !rawConstraints.isEmpty()
                        ? new DomainRuleFactDescriptor.Constraints(
                                rawConstraints.min(),
                                rawConstraints.max(),
                                rawConstraints.step(),
                                rawConstraints.minLength(),
                                rawConstraints.maxLength(),
                                normalize(rawConstraints.pattern()))
                        : persisted.constraints();

        DomainRuleFactDescriptor.GovernanceMetadata governance =
                mergeGovernance(persisted.governance(), dto, restricted);

        DomainRuleFactDescriptor.LineageMetadata lineage = persisted.lineage();
        if (dto.lineageAttributeId() != null
                || dto.lineageSourceResourceKey() != null
                || dto.lineageTransformation() != null) {
            lineage =
                    new DomainRuleFactDescriptor.LineageMetadata(
                            normalize(dto.lineageAttributeId()),
                            normalize(dto.lineageSourceResourceKey()),
                            normalize(dto.lineageTransformation()));
        }

        String valuePresentation =
                normalize(dto.valuePresentation()) != null
                        ? normalize(dto.valuePresentation())
                        : persisted.valuePresentation();
        String unit = normalize(dto.unit()) != null ? normalize(dto.unit()) : persisted.unit();
        String mask = normalize(dto.mask()) != null ? normalize(dto.mask()) : persisted.mask();
        String example =
                restricted
                        ? null
                        : (normalize(dto.example()) != null
                                ? normalize(dto.example())
                                : persisted.example());

        return new DomainRuleFactDescriptor(
                persisted.path(),
                persisted.valueType(),
                persisted.nullable(),
                labels,
                descriptions,
                persisted.providerRef(),
                evidenceRefs,
                sensitivity,
                redaction,
                hints,
                groupKey,
                groupLabels,
                valuePresentation,
                unit,
                mask,
                allowedValues,
                optionSourceRef,
                example,
                constraints,
                governance,
                lineage);
    }

    private static DomainRuleFactDescriptor.GovernanceMetadata mergeGovernance(
            DomainRuleFactDescriptor.GovernanceMetadata existing,
            DtoFactPropertyMetadata dto,
            boolean restricted) {
        if (!dto.explicitGovernanceDeclared() && !restricted) {
            return existing;
        }
        String existingClass = existing != null ? normalize(existing.classification()) : null;
        String dtoClass =
                dto.explicitGovernanceDeclared() ? normalize(dto.classification()) : null;
        String classification = strictestClassification(existingClass, dtoClass);

        String dtoDataCategory =
                dto.explicitGovernanceDeclared() ? normalize(dto.dataCategory()) : null;
        String dataCategory =
                dtoDataCategory != null
                        ? dtoDataCategory
                        : (existing != null ? normalize(existing.dataCategory()) : null);

        boolean existingDisallowsExport = existing != null && Boolean.FALSE.equals(existing.exportable());
        boolean dtoDisallowsExport = dto.explicitGovernanceDeclared() && !dto.exportable();
        boolean exportable = !restricted && !existingDisallowsExport && !dtoDisallowsExport;

        String existingVis = existing != null ? normalize(existing.aiVisibility()) : null;
        String dtoVis = dto.explicitGovernanceDeclared() ? normalize(dto.aiVisibility()) : null;
        String aiVisibility = restricted ? "deny" : strictestAiVisibility(existingVis, dtoVis);

        Set<String> tags = new LinkedHashSet<>();
        if (existing != null && existing.complianceTags() != null) {
            for (String tag : existing.complianceTags()) {
                String n = normalize(tag);
                if (n != null && tags.size() < MAX_COMPLIANCE_TAGS) {
                    tags.add(n);
                }
            }
        }
        if (dto.complianceTags() != null) {
            for (String tag : dto.complianceTags()) {
                String n = normalize(tag);
                if (n != null && tags.size() < MAX_COMPLIANCE_TAGS) {
                    tags.add(n);
                }
            }
        }
        return new DomainRuleFactDescriptor.GovernanceMetadata(
                classification,
                dataCategory,
                exportable,
                aiVisibility,
                tags.isEmpty() ? null : List.copyOf(tags));
    }

    private static List<String> mergeEvidenceRefs(
            List<String> persistedRefs, RuleFactDescriptor curated, DtoFactPropertyMetadata dto) {
        List<String> dtoRefs = dto != null ? dto.buildEvidenceRefs(null) : List.of();
        boolean hasCurated =
                curated != null && curated.evidenceRefs() != null && !curated.evidenceRefs().isEmpty();
        if (!hasCurated && dtoRefs.isEmpty()) {
            return persistedRefs;
        }
        Set<String> union = new LinkedHashSet<>();
        if (persistedRefs != null) {
            for (String ref : persistedRefs) {
                String normalized = normalize(ref);
                if (normalized != null) {
                    union.add(normalized);
                }
            }
        }
        if (hasCurated) {
            for (String ref : curated.evidenceRefs()) {
                String normalized = normalize(ref);
                if (normalized != null) {
                    union.add(normalized);
                }
            }
        }
        for (String ref : dtoRefs) {
            String normalized = normalize(ref);
            if (normalized != null) {
                union.add(normalized);
            }
        }
        return union.isEmpty() ? persistedRefs : List.copyOf(union);
    }

    private static DomainRuleFactDescriptor.GovernanceMetadata enforceRestrictedGovernance(
            DomainRuleFactDescriptor.GovernanceMetadata existing, boolean restricted) {
        if (!restricted) {
            return existing;
        }
        if (existing == null) {
            return new DomainRuleFactDescriptor.GovernanceMetadata(
                    null, null, false, "deny", null);
        }
        if ("deny".equalsIgnoreCase(existing.aiVisibility())
                && Boolean.FALSE.equals(existing.exportable())) {
            return existing;
        }
        return new DomainRuleFactDescriptor.GovernanceMetadata(
                existing.classification(),
                existing.dataCategory(),
                false,
                "deny",
                existing.complianceTags());
    }

    private static boolean hasOnlyBoilerplateDescriptions(Map<String, String> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return true;
        }
        for (String value : descriptions.values()) {
            if (value != null
                    && !value.isBlank()
                    && !DtoMetadataFactCatalogEnricher.isGenericTemplateDescription(value)) {
                return false;
            }
        }
        return true;
    }

    private static String strictestClassification(String first, String second) {
        String c1 = normalize(first);
        String c2 = normalize(second);
        if (c1 == null) {
            return c2;
        }
        if (c2 == null) {
            return c1;
        }
        return classificationRank(c1.toLowerCase(Locale.ROOT))
                        >= classificationRank(c2.toLowerCase(Locale.ROOT))
                ? c1.toLowerCase(Locale.ROOT)
                : c2.toLowerCase(Locale.ROOT);
    }

    private static int classificationRank(String value) {
        return switch (value) {
            case "public" -> 0;
            case "internal" -> 1;
            case "confidential" -> 2;
            case "restricted" -> 3;
            default -> -1;
        };
    }

    private static String strictestAiVisibility(String first, String second) {
        String a1 = normalize(first);
        String a2 = normalize(second);
        if (a1 == null) {
            return a2;
        }
        if (a2 == null) {
            return a1;
        }
        return aiVisibilityRank(a1.toLowerCase(Locale.ROOT))
                        >= aiVisibilityRank(a2.toLowerCase(Locale.ROOT))
                ? a1.toLowerCase(Locale.ROOT)
                : a2.toLowerCase(Locale.ROOT);
    }

    private static int aiVisibilityRank(String value) {
        return switch (value) {
            case "allow" -> 0;
            case "summarize_only" -> 1;
            case "mask" -> 2;
            case "deny" -> 3;
            default -> -1;
        };
    }

    private static String strictestSensitivity(String... candidates) {
        String best = null;
        int bestRank = -1;
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized == null) {
                continue;
            }
            String canonical = RuleFactDescriptor.normalizeSensitivity(normalized);
            int rank = sensitivityRank(canonical);
            if (best == null || rank > bestRank) {
                best = canonical;
                bestRank = rank;
            }
        }
        return best;
    }

    private static int sensitivityRank(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "NON_SENSITIVE" -> 0;
            case "PERSONAL" -> 1;
            case "SENSITIVE" -> 2;
            case "SECRET" -> 3;
            default -> -1;
        };
    }

    private static String strictestRedaction(String... candidates) {
        String best = null;
        int bestRank = -1;
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized == null) {
                continue;
            }
            String upper = normalized.toUpperCase(Locale.ROOT);
            int rank = redactionRank(upper);
            if (best == null || rank > bestRank) {
                best = upper;
                bestRank = rank;
            }
        }
        return best;
    }

    private static int redactionRank(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "NONE" -> 0;
            case "MASK" -> 1;
            case "HASH" -> 2;
            case "OMIT" -> 3;
            default -> -1;
        };
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private static boolean isRestricted(String sensitivity, String redaction) {
        return "SECRET".equalsIgnoreCase(sensitivity) || "OMIT".equalsIgnoreCase(redaction);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
