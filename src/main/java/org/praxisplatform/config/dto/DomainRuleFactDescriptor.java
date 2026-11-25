package org.praxisplatform.config.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Safe, governed description of one fact accepted by a domain decision. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomainRuleFactDescriptor(
        String path,
        String valueType,
        boolean nullable,
        Map<String, String> labels,
        Map<String, String> descriptions,
        String providerRef,
        List<String> evidenceRefs,
        String sensitivity,
        String redaction,
        Map<String, String> hints,
        String groupKey,
        Map<String, String> groupLabels,
        String valuePresentation,
        String unit,
        String mask,
        List<AllowedValue> allowedValues,
        OptionSourceRef optionSourceRef,
        String example,
        Constraints constraints,
        GovernanceMetadata governance,
        LineageMetadata lineage
) {

    public DomainRuleFactDescriptor {
        labels = labels == null ? null : Map.copyOf(labels);
        descriptions = descriptions == null ? null : Map.copyOf(descriptions);
        evidenceRefs = evidenceRefs == null ? null : List.copyOf(evidenceRefs);
        hints = hints == null ? null : Map.copyOf(hints);
        groupLabels = groupLabels == null ? null : Map.copyOf(groupLabels);
        allowedValues = allowedValues == null ? null : List.copyOf(allowedValues);
    }

    /** Backward-compatible 9-argument constructor for minimal persisted/curated descriptors. */
    public DomainRuleFactDescriptor(
            String path,
            String valueType,
            boolean nullable,
            Map<String, String> labels,
            Map<String, String> descriptions,
            String providerRef,
            List<String> evidenceRefs,
            String sensitivity,
            String redaction
    ) {
        this(
                path,
                valueType,
                nullable,
                labels,
                descriptions,
                providerRef,
                evidenceRefs,
                sensitivity,
                redaction,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AllowedValue(
            Object value,
            Map<String, String> labels,
            String icon,
            String tone,
            String description
    ) {
        public AllowedValue {
            labels = labels == null ? null : Map.copyOf(labels);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionSourceRef(
            String sourceKey,
            String resourceKey
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Constraints(
            BigDecimal min,
            BigDecimal max,
            BigDecimal step,
            Integer minLength,
            Integer maxLength,
            String pattern
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GovernanceMetadata(
            String classification,
            String dataCategory,
            Boolean exportable,
            String aiVisibility,
            List<String> complianceTags
    ) {
        public GovernanceMetadata {
            complianceTags = complianceTags == null ? null : List.copyOf(complianceTags);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineageMetadata(
            String attributeId,
            String sourceResourceKey,
            String transformation
    ) {
    }
}
