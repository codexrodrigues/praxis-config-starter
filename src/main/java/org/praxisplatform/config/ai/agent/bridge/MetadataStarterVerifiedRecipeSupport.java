package org.praxisplatform.config.ai.agent.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalProvenance;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalRecipeSummary;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalRecipeSummary.AgentAnalyticalRecipeParameterSummary;
import org.praxisplatform.config.ai.agent.spi.AgentVerifiedRecipeExecutionRequest;
import org.praxisplatform.uischema.analytics.ResolvedVerifiedAnalyticalRecipe;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver.CompiledRecipeExecution;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.dto.ComparisonBucket;
import org.praxisplatform.uischema.stats.dto.ComparisonMetricValue;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.DistributionBucket;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.stats.dto.VerifiedRecipeExecutionRequest;
import org.praxisplatform.uischema.stats.dto.VerifiedRecipeProvenance;
import org.springframework.util.StringUtils;

/**
 * Helper dedicated to extracting and executing {@code @VerifiedAnalyticalRecipe} definitions
 * inside {@link MetadataStarterInProcessAgentBridge} without bloating the bridge class.
 */
@Slf4j
final class MetadataStarterVerifiedRecipeSupport {

    private MetadataStarterVerifiedRecipeSupport() {
    }

    static List<AgentAnalyticalRecipeSummary> extractRecipes(
            AbstractResourceQueryController<?, ?, ?> controller,
            VerifiedAnalyticalRecipeResolver resolver,
            ObjectMapper objectMapper) {
        if (controller == null) {
            return List.of();
        }
        try {
            VerifiedAnalyticalRecipeResolver effective = resolver != null
                    ? resolver
                    : new VerifiedAnalyticalRecipeResolver(null, objectMapper, null, List.of());
            List<ResolvedVerifiedAnalyticalRecipe> resolved = effective.resolveByController(
                    controller.getClass(), controller);
            if (resolved == null || resolved.isEmpty()) {
                return List.of();
            }
            return resolved.stream().map(MetadataStarterVerifiedRecipeSupport::toSummary).toList();
        } catch (Exception ex) {
            log.warn("Failed extracting verified analytical recipes from controller {}: {}",
                    controller.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static RecipeExecutionOutcome executeRecipe(
            AbstractResourceQueryController<?, ?, ?> controller,
            BaseResourceQueryService<?, ?, ?> service,
            Class<?> filterDtoType,
            StatsFieldRegistry registry,
            Set<String> blockedFields,
            VerifiedAnalyticalRecipeResolver resolver,
            ObjectMapper objectMapper,
            AgentVerifiedRecipeExecutionRequest request) {
        VerifiedAnalyticalRecipeResolver effective = resolver != null
                ? resolver
                : new VerifiedAnalyticalRecipeResolver(null, objectMapper, null, List.of());
        List<ResolvedVerifiedAnalyticalRecipe> recipes = effective.resolveByController(
                controller.getClass(), controller);
        ResolvedVerifiedAnalyticalRecipe recipe = recipes.stream()
                .filter(candidate -> candidate.id().equals(request.recipeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Receita analitica verificada nao encontrada para este recurso: " + request.recipeId()));

        enforceGovernanceFields(recipe, blockedFields);

        Class<? extends GenericFilterDTO> typedFilterDto = filterDtoType != null
                && GenericFilterDTO.class.isAssignableFrom(filterDtoType)
                ? (Class<? extends GenericFilterDTO>) filterDtoType
                : null;
        CompiledRecipeExecution compiled = effective.compileExecution(
                recipe,
                typedFilterDto,
                registry,
                new VerifiedRecipeExecutionRequest(request.parameters(), request.timezone()));

        VerifiedRecipeProvenance provenance = compiled.provenance();
        List<RawRecipeBucket> rawBuckets = new ArrayList<>();
        String operationKind = toOperationKind(recipe.operation());
        String granularityUsed = recipe.operation() == AnalyticsOperation.TIMESERIES
                ? (compiled.timeSeriesRequest().granularity() != null
                        ? compiled.timeSeriesRequest().granularity().name()
                        : recipe.granularity().name())
                : null;

        StatsMetric primaryMetricOp = recipe.metrics().isEmpty()
                ? StatsMetric.COUNT
                : recipe.metrics().get(0).operation();
        String primaryMetricField = recipe.metrics().isEmpty()
                ? null
                : recipe.metrics().get(0).field();

        switch (recipe.operation()) {
            case GROUP_BY -> {
                GroupByStatsResponse resp = service.groupByStats(compiled.groupByRequest());
                if (resp != null && resp.buckets() != null) {
                    for (GroupByBucket b : resp.buckets()) {
                        String key = b.key() != null ? String.valueOf(b.key()) : firstNonBlank(b.label(), "N/D");
                        rawBuckets.add(new RawRecipeBucket(key, b.label(), b.value(), b.count(), null, null, null, null, null));
                    }
                }
            }
            case TIMESERIES -> {
                TimeSeriesStatsResponse resp = service.timeSeriesStats(compiled.timeSeriesRequest());
                if (resp != null && resp.points() != null) {
                    for (TimeSeriesPoint pt : resp.points()) {
                        String key = pt.start() != null ? pt.start().toString() : firstNonBlank(pt.label(), "");
                        String label = firstNonBlank(pt.label(), key);
                        rawBuckets.add(new RawRecipeBucket(
                                key,
                                label,
                                pt.value(),
                                pt.count(),
                                pt.start() != null ? pt.start().toString() : null,
                                pt.end() != null ? pt.end().toString() : null,
                                null,
                                null,
                                null));
                    }
                }
            }
            case DISTRIBUTION -> {
                DistributionStatsResponse resp = service.distributionStats(compiled.distributionRequest());
                if (resp != null && resp.buckets() != null) {
                    for (DistributionBucket b : resp.buckets()) {
                        String key = b.key() != null ? String.valueOf(b.key()) : firstNonBlank(b.label(), "");
                        String label = firstNonBlank(b.label(), key);
                        rawBuckets.add(new RawRecipeBucket(
                                key,
                                label,
                                b.value(),
                                b.count(),
                                b.from() != null ? String.valueOf(b.from()) : null,
                                b.to() != null ? String.valueOf(b.to()) : null,
                                null,
                                null,
                                null));
                    }
                }
            }
            case COMPARISON -> {
                ComparisonStatsResponse resp = service.comparisonStats(compiled.comparisonRequest());
                if (resp != null && resp.buckets() != null) {
                    for (ComparisonBucket b : resp.buckets()) {
                        String key = b.key() != null ? String.valueOf(b.key()) : firstNonBlank(b.label(), "N/D");
                        ComparisonMetricValue mv = extractComparisonMetric(b.values(), primaryMetricOp, primaryMetricField);
                        Number value = mv != null ? mv.current() : null;
                        BigDecimal previousValue = toNullableBigDecimal(mv != null ? mv.previous() : null);
                        BigDecimal delta = toNullableBigDecimal(mv != null ? mv.delta() : null);
                        Double deltaPercent = mv != null ? mv.deltaPercent() : null;
                        boolean isCountMetric = primaryMetricOp == StatsMetric.COUNT
                                || primaryMetricOp == StatsMetric.DISTINCT_COUNT;
                        long count = (isCountMetric && value != null) ? value.longValue() : 0L;
                        rawBuckets.add(new RawRecipeBucket(
                                key,
                                b.label(),
                                value,
                                count,
                                null,
                                null,
                                previousValue,
                                delta,
                                deltaPercent));
                    }
                }
            }
            case INFERENCE, ANOMALIES -> throw new UnsupportedOperationException(
                    "Operacao nao suportada em receita verificada: " + recipe.operation());
        }

        AgentAnalyticalProvenance agentProvenance = AgentAnalyticalProvenance.verifiedRecipe(
                provenance.recipeId(),
                provenance.title(),
                provenance.version(),
                provenance.governanceTier() != null ? provenance.governanceTier().wireValue() : "CERTIFIED",
                provenance.provenanceDigest(),
                provenance.lockedFilters(),
                provenance.appliedParameters());

        return new RecipeExecutionOutcome(
                recipe,
                agentProvenance,
                operationKind,
                primaryMetricOp,
                primaryMetricField,
                granularityUsed,
                List.copyOf(rawBuckets));
    }

    private static void enforceGovernanceFields(ResolvedVerifiedAnalyticalRecipe recipe, Set<String> blockedFields) {
        if (blockedFields == null || blockedFields.isEmpty()) {
            return;
        }
        checkBlockedField("Stats field", recipe.field(), blockedFields);
        checkBlockedField("Stats periodField", recipe.periodField(), blockedFields);
        for (VerifiedAnalyticalRecipeDescriptor.DimensionSpec dim : recipe.dimensions()) {
            checkBlockedField("Stats dimension", dim.field(), blockedFields);
        }
        for (VerifiedAnalyticalRecipeDescriptor.MetricSpec metric : recipe.metrics()) {
            checkBlockedField("Stats metricField", metric.field(), blockedFields);
        }
        for (String lockedKey : recipe.lockedFilters().keySet()) {
            checkBlockedField("Recipe lockedFilter", lockedKey, blockedFields);
        }
        for (VerifiedAnalyticalRecipeDescriptor.ParameterSpec param : recipe.adjustableParams()) {
            String target = StringUtils.hasText(param.filterField()) ? param.filterField() : param.name();
            checkBlockedField("Recipe adjustableParam", target, blockedFields);
        }
    }

    private static void checkBlockedField(String role, String candidate, Set<String> blockedFields) {
        if (StringUtils.hasText(candidate)
                && blockedFields.contains(candidate.trim().toLowerCase(Locale.ROOT))) {
            throw new SecurityException(role + " restricted by governance policy: " + candidate.trim());
        }
    }

    private static AgentAnalyticalRecipeSummary toSummary(ResolvedVerifiedAnalyticalRecipe recipe) {
        List<AgentAnalyticalRecipeParameterSummary> params = recipe.adjustableParams().stream()
                .map(p -> new AgentAnalyticalRecipeParameterSummary(
                        p.name(),
                        p.filterField(),
                        p.description(),
                        p.dataType(),
                        p.required(),
                        p.defaultValue(),
                        p.allowedValues(),
                        p.minValue(),
                        p.maxValue()))
                .toList();
        return new AgentAnalyticalRecipeSummary(
                recipe.id(),
                recipe.title(),
                recipe.description(),
                recipe.intent(),
                recipe.operation().wireValue(),
                recipe.field(),
                recipe.governanceTier().wireValue(),
                recipe.version(),
                recipe.provenanceDigest(),
                recipe.endpointPath(),
                recipe.lockedFilters(),
                params);
    }

    private static ComparisonMetricValue extractComparisonMetric(
            Map<String, ComparisonMetricValue> values,
            StatsMetric metricOp,
            String metricField) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(metricField) && values.containsKey(metricField)) {
            return values.get(metricField);
        }
        if (metricOp != null && values.containsKey(metricOp.wireValue())) {
            return values.get(metricOp.wireValue());
        }
        if (metricOp != null && values.containsKey(metricOp.name())) {
            return values.get(metricOp.name());
        }
        return values.values().iterator().next();
    }

    private static BigDecimal toNullableBigDecimal(Number value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return BigDecimal.valueOf(value.doubleValue());
        }
    }

    private static String toOperationKind(AnalyticsOperation operation) {
        return switch (operation) {
            case GROUP_BY -> "GROUP_BY";
            case TIMESERIES -> "TIMESERIES";
            case DISTRIBUTION -> "DISTRIBUTION";
            case COMPARISON -> "COMPARISON";
            case INFERENCE, ANOMALIES -> operation.name();
        };
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (StringUtils.hasText(second) ? second.trim() : null);
    }

    record RawRecipeBucket(
            String key,
            String serverLabel,
            Number value,
            long count,
            String rangeFrom,
            String rangeTo,
            BigDecimal previousValue,
            BigDecimal delta,
            Double deltaPercent
    ) {
    }

    record RecipeExecutionOutcome(
            ResolvedVerifiedAnalyticalRecipe recipe,
            AgentAnalyticalProvenance provenance,
            String operationKind,
            StatsMetric metricOp,
            String metricField,
            String granularityUsed,
            List<RawRecipeBucket> rawBuckets
    ) {
    }
}
