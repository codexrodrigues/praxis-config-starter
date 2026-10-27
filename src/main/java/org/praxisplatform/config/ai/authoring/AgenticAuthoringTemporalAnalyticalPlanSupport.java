package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringGenericUiCompositionPlanProvider.DashboardDimension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringGenericUiCompositionPlanProvider.FieldCandidate;

/**
 * Domain-agnostic support for synthesizing temporal and analytical metric evolution dashboards
 * from governed resource capabilities ({@code /capabilities -> stats.fields}) and filter schemas
 * ({@code /schemas/filtered?path=/{resource}/filter&schemaType=request -> x-ui.optionSource}).
 */
final class AgenticAuthoringTemporalAnalyticalPlanSupport {

    private final Function<String, String> dashboardFieldStemFn;
    private final Function<String, String> titleFromResourcePathFn;
    private final BiFunction<String, String, String> canonicalDashboardFilterFieldFn;

    AgenticAuthoringTemporalAnalyticalPlanSupport(
            Function<String, String> dashboardFieldStemFn,
            Function<String, String> titleFromResourcePathFn,
            BiFunction<String, String, String> canonicalDashboardFilterFieldFn) {
        this.dashboardFieldStemFn = dashboardFieldStemFn;
        this.titleFromResourcePathFn = titleFromResourcePathFn;
        this.canonicalDashboardFilterFieldFn = canonicalDashboardFilterFieldFn;
    }

    boolean isTemporalDimension(DashboardDimension dimension) {
        if (dimension == null) {
            return false;
        }
        return "temporal".equalsIgnoreCase(safe(dimension.orientation()))
                || isTemporalFieldNameOrLabel(dimension.field(), dimension.label());
    }

    boolean isTemporalFieldNameOrLabel(String field, String label) {
        String searchable = searchableText(safe(field) + " " + safe(label));
        return containsAny(searchable,
                "data inicio",
                "data fim",
                "data",
                "date",
                "competencia",
                "periodo",
                "vigencia",
                "mes",
                "ano");
    }

    boolean isCategoricalPeriodField(String field) {
        String normalized = normalize(field).replaceAll("[^a-z0-9]", "");
        return "competencia".equals(normalized)
                || "periodo".equals(normalized)
                || "ano".equals(normalized)
                || "mes".equals(normalized);
    }

    boolean isTemporalEvolutionRequest(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringVisualizationDecision visualizationDecision) {
        String prompt = searchableText(String.join(" ",
                request == null ? "" : safe(request.userPrompt()),
                request == null || request.intentResolution() == null ? "" : safe(request.intentResolution().effectivePrompt()),
                visualizationDecision == null ? "" : safe(visualizationDecision.intent())));
        return containsAny(prompt,
                "evolucao",
                "progressao",
                "trajetoria",
                "ao longo do tempo",
                "serie temporal");
    }

    boolean hasGovernedTemporalMetricEvolutionContext(List<FieldCandidate> fieldCandidates) {
        if (fieldCandidates == null || fieldCandidates.isEmpty()) {
            return false;
        }
        boolean hasTemporalAxis = fieldCandidates.stream()
                .anyMatch(field -> field.timeSeriesHint()
                        || (field.groupByHint() && isTemporalFieldNameOrLabel(field.field(), field.label())));
        boolean hasMetric = defaultTemporalEvolutionMetricCandidate(fieldCandidates) != null;
        return hasTemporalAxis && hasMetric;
    }

    boolean isEntityScopeIdentityAxis(String normalizedAxisField, List<FieldCandidate> fieldCandidates) {
        if (normalizedAxisField == null || normalizedAxisField.isBlank() || fieldCandidates == null) {
            return false;
        }
        String axisStem = dashboardFieldStemFn.apply(normalizedAxisField);
        return fieldCandidates.stream()
                .filter(FieldCandidate::entityLookupHint)
                .anyMatch(lookupField -> {
                    String lookupStem = dashboardFieldStemFn.apply(lookupField.field());
                    String lookupLabelStem = dashboardFieldStemFn.apply(lookupField.label());
                    return normalize(lookupField.field()).equals(normalizedAxisField)
                            || (!axisStem.isBlank() && (axisStem.equals(lookupStem) || axisStem.equals(lookupLabelStem)));
                });
    }

    FieldCandidate defaultTemporalEvolutionMetricCandidate(List<FieldCandidate> fieldCandidates) {
        if (fieldCandidates == null || fieldCandidates.isEmpty()) {
            return null;
        }
        return fieldCandidates.stream()
                .filter(FieldCandidate::metricHint)
                .filter(field -> !field.timeSeriesHint() && !field.groupByHint() && !field.entityLookupHint())
                .filter(field -> !"id".equals(normalize(field.field())))
                .findFirst()
                .orElseGet(() -> fieldCandidates.stream()
                        .filter(FieldCandidate::metricHint)
                        .filter(field -> !"id".equals(normalize(field.field())))
                        .findFirst()
                        .orElse(null));
    }

    String defaultAggregatedMetricLabel(FieldCandidate metricCandidate, String aggregation) {
        if (metricCandidate == null) {
            return "Total";
        }
        String fallbackLabel = titleFromResourcePathFn.apply(metricCandidate.field());
        String baseLabel = safe(metricCandidate.label()).isBlank() ? fallbackLabel : metricCandidate.label().trim();
        if (!"avg".equalsIgnoreCase(safe(aggregation))) {
            return baseLabel;
        }
        if (containsAny(searchableText(baseLabel), "medio", "media", "average")) {
            return baseLabel;
        }
        boolean feminine = normalize(baseLabel).endsWith("a") || normalize(baseLabel).endsWith("ao");
        return baseLabel + (feminine ? " Média" : " Médio");
    }

    List<DashboardDimension> defaultTemporalEvolutionDimensions(List<FieldCandidate> fieldCandidates) {
        if (fieldCandidates == null || fieldCandidates.isEmpty()) {
            return List.of();
        }
        FieldCandidate metricCandidate = defaultTemporalEvolutionMetricCandidate(fieldCandidates);
        if (metricCandidate == null) {
            return List.of();
        }
        String aggregation = metricCandidate.avgEligible() ? "avg" : "sum";
        String metricField = metricCandidate.field();
        String metricLabel = defaultAggregatedMetricLabel(metricCandidate, aggregation);

        FieldCandidate primaryTemporal = fieldCandidates.stream()
                .filter(FieldCandidate::timeSeriesHint)
                .findFirst()
                .orElseGet(() -> fieldCandidates.stream()
                        .filter(field -> field.groupByHint() && isTemporalFieldNameOrLabel(field.field(), field.label()))
                        .findFirst()
                        .orElse(null));
        if (primaryTemporal == null) {
            return List.of();
        }
        List<DashboardDimension> result = new ArrayList<>();
        boolean primaryTimeSeries = primaryTemporal.timeSeriesHint() && !isCategoricalPeriodField(primaryTemporal.field());
        String primaryFilterField = preferredTemporalOrDashboardFilterField(
                primaryTemporal.field(),
                primaryTemporal.label(),
                fieldCandidates);
        result.add(new DashboardDimension(
                "evolucao temporal por " + searchableText(primaryTemporal.label()),
                primaryTemporal.field(),
                primaryFilterField,
                primaryTemporal.label(),
                metricLabel + (primaryTimeSeries ? " ao longo de " : " por ") + primaryTemporal.label(),
                "area",
                primaryTimeSeries ? "temporal" : "vertical",
                aggregation,
                metricField,
                metricLabel,
                "governed-stats-capability-inference"));

        FieldCandidate secondaryCategorical = fieldCandidates.stream()
                .filter(FieldCandidate::groupByHint)
                .filter(field -> !normalize(field.field()).equals(normalize(primaryTemporal.field())))
                .filter(field -> !field.timeSeriesHint() && !isTemporalFieldNameOrLabel(field.field(), field.label()))
                .filter(field -> !isEntityScopeIdentityAxis(normalize(field.field()), fieldCandidates))
                .findFirst()
                .orElse(null);
        if (secondaryCategorical != null) {
            String secondaryFilterField = canonicalDashboardFilterFieldFn.apply(
                    secondaryCategorical.field(),
                    secondaryCategorical.label());
            boolean hasDistinctEntityFilter = !normalize(secondaryFilterField).equals(normalize(secondaryCategorical.field()));
            boolean magnitudeComparison = hasDistinctEntityFilter || "avg".equals(normalize(aggregation));
            result.add(new DashboardDimension(
                    "composicao por " + searchableText(secondaryCategorical.label()),
                    secondaryCategorical.field(),
                    secondaryFilterField,
                    secondaryCategorical.label(),
                    metricLabel + " por " + secondaryCategorical.label(),
                    magnitudeComparison ? "bar" : "donut",
                    hasDistinctEntityFilter ? "horizontal" : "vertical",
                    aggregation,
                    metricField,
                    metricLabel,
                    "governed-stats-capability-inference"));
        }
        return List.copyOf(result);
    }

    String preferredTemporalOrDashboardFilterField(
            String field,
            String label,
            List<FieldCandidate> fieldCandidates) {
        String normalized = normalize(field);
        if (fieldCandidates != null) {
            for (FieldCandidate candidate : fieldCandidates) {
                if (normalize(candidate.field()).equals(normalized + "between")) {
                    return candidate.field();
                }
            }
        }
        return canonicalDashboardFilterFieldFn.apply(field, label);
    }

    String preferredEntityScopeFilterField(
            List<FieldCandidate> fieldCandidates,
            List<DashboardDimension> dimensions) {
        if (fieldCandidates == null || fieldCandidates.isEmpty()
                || dimensions == null
                || dimensions.stream().noneMatch(this::isTemporalDimension)) {
            return "";
        }
        Set<String> dimensionFilterFields = new LinkedHashSet<>();
        Set<String> dimensionStems = new LinkedHashSet<>();
        for (DashboardDimension dimension : dimensions) {
            if (dimension == null) {
                continue;
            }
            dimensionFilterFields.add(normalize(dimension.field()));
            dimensionFilterFields.add(normalize(dimension.filterField()));
            String stem = dashboardFieldStemFn.apply(dimension.field());
            if (!stem.isBlank()) {
                dimensionStems.add(stem);
            }
        }
        return fieldCandidates.stream()
                .filter(FieldCandidate::entityLookupHint)
                .filter(field -> !dimensionFilterFields.contains(normalize(field.field())))
                .filter(field -> {
                    String stem = dashboardFieldStemFn.apply(field.field());
                    return stem.isBlank() || !dimensionStems.contains(stem);
                })
                .map(FieldCandidate::field)
                .findFirst()
                .orElse("");
    }

    private String searchableText(String value) {
        return normalize(safe(value).replaceAll("([a-z])([A-Z])", "$1 $2"))
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || value.isBlank() || candidates == null) {
            return false;
        }
        String padded = " " + value + " ";
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (padded.contains(" " + candidate.trim() + " ") || value.contains(candidate.trim())) {
                return true;
            }
        }
        return false;
    }
}
