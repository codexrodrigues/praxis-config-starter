package org.praxisplatform.config.ai.agent.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalAnalysisResult;
import org.praxisplatform.config.ai.agent.spi.AgentStochasticSimulationRequest;
import org.springframework.util.StringUtils;

/**
 * Neutral agent tool invoking stochastic Monte Carlo / QMC simulation ({@code /stats/simulation})
 * through {@link AgentResourceExecutionBridge} with deterministic BigDecimal anchor revalidation
 * and fail-closed epistemic provenance.
 */
public class RunResourceStochasticSimulationTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "run_resource_stochastic_simulation";

    private final AgentResourceExecutionBridge bridge;

    public RunResourceStochasticSimulationTool(AgentResourceExecutionBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge is required");
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolSafetyClass safetyClass() {
        return AgentToolSafetyClass.SIMULATION;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entityKey", Map.of(
                "type", "string",
                "description", "Canonical entity key or resourcePath from discover_topic_resources_and_schemas."));
        properties.put("scenarioCount", Map.of(
                "type", "integer",
                "description", "Number of Monte Carlo / Quasi-Monte Carlo trajectories (defaults to server policy)."));
        properties.put("horizonPeriods", Map.of(
                "type", "integer",
                "description", "Forward projection horizon in discrete periods."));
        properties.put("seed", Map.of(
                "type", "integer",
                "description", "Optional deterministic PRNG / Sobol seed for reproducible simulation."));
        properties.put("asOfDate", Map.of(
                "type", "string",
                "description", "Optional ISO-8601 reference date (YYYY-MM-DD)."));
        properties.put("samplingStrategy", Map.of(
                "type", "string",
                "enum", List.of("QUASI_MONTE_CARLO_SOBOL", "PSEUDO_RANDOM_PCG64"),
                "description", "QUASI_MONTE_CARLO_SOBOL (default) or PSEUDO_RANDOM_PCG64."));
        properties.put("dependenceMethod", Map.of(
                "type", "string",
                "enum", List.of("IMAN_CONOVER", "INDEPENDENT"),
                "description", "IMAN_CONOVER (default) or INDEPENDENT."));

        Map<String, Object> modelPointProps = new LinkedHashMap<>();
        modelPointProps.put("pointKey", Map.of("type", "string"));
        modelPointProps.put("segmentLabel", Map.of("type", "string"));
        modelPointProps.put("fundingSourceKey", Map.of("type", "string"));
        modelPointProps.put("weightCount", Map.of("type", "integer"));
        modelPointProps.put("baseAmount", Map.of("type", "number"));
        modelPointProps.put("periodGrowthFactors", Map.of("type", "array", "items", Map.of("type", "number")));
        modelPointProps.put("factorSensitivities", Map.of("type", "object"));
        modelPointProps.put("fixedComponent", Map.of("type", "number"));
        properties.put("modelPoints", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", modelPointProps),
                "description", "Optional baseline model points when not derived automatically by the server."));

        Map<String, Object> factorProps = new LinkedHashMap<>();
        factorProps.put("factorKey", Map.of("type", "string"));
        factorProps.put("distribution", Map.of(
                "type", "string",
                "enum", List.of("NORMAL", "LOGNORMAL", "TRIANGULAR", "PERT", "UNIFORM", "EMPIRICAL_QUANTILES")));
        factorProps.put("parameters", Map.of("type", "object"));
        factorProps.put("empiricalQuantiles", Map.of("type", "array", "items", Map.of("type", "number")));
        factorProps.put("empiricalValues", Map.of("type", "array", "items", Map.of("type", "number")));
        factorProps.put("lowerBound", Map.of("type", "number"));
        factorProps.put("upperBound", Map.of("type", "number"));
        factorProps.put("applyPerPeriod", Map.of("type", "boolean"));
        properties.put("stochasticFactors", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", factorProps,
                        "required", List.of("factorKey", "distribution")),
                "description", "Stochastic factor marginal specifications (required)."));

        properties.put("correlations", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "factorA", Map.of("type", "string"),
                                "factorB", Map.of("type", "string"),
                                "rankCorrelation", Map.of("type", "number")),
                        "required", List.of("factorA", "factorB", "rankCorrelation")),
                "description", "Optional Spearman rank correlations between distinct factors."));

        Map<String, Object> thresholdProps = new LinkedHashMap<>();
        thresholdProps.put("thresholdId", Map.of("type", "string"));
        thresholdProps.put("label", Map.of("type", "string"));
        thresholdProps.put("direction", Map.of(
                "type", "string",
                "enum", List.of("UPPER_CAP", "LOWER_FLOOR", "CUTOFF_WINDOW")));
        thresholdProps.put("limitValue", Map.of("type", "number"));
        thresholdProps.put("fundingSourceKey", Map.of("type", "string"));
        thresholdProps.put("targetPeriodIndex", Map.of("type", "integer"));
        properties.put("thresholds", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", thresholdProps,
                        "required", List.of("thresholdId", "direction", "limitValue")),
                "description", "Threshold specifications evaluated for breach probabilities and tail exposure."));

        properties.put("quantiles", Map.of(
                "type", "array",
                "items", Map.of("type", "number"),
                "description", "Requested quantiles in (0, 1), e.g. [0.05, 0.10, 0.25, 0.50, 0.75, 0.90, 0.95]."));
        properties.put("anchorQuantiles", Map.of(
                "type", "array",
                "items", Map.of("type", "number"),
                "description", "Anchor scenario quantiles revalidated inline against exact BigDecimal business rules."));
        properties.put("minBucketCount", Map.of(
                "type", "integer",
                "description", "Optional k-anonymity floor (never lower than server policy)."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Optional FilterDTO property map scoping baseline records."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Execute stochastic Monte Carlo / Sobol trajectory projection (STOCHASTIC_SIMULATION) with Iman-Conover rank correlation, Tornado sensitivity drivers, threshold breach probabilities, and deterministic BigDecimal anchor revalidation (fail-closed DEGRADED gating).",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "stochasticFactors"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = piiVault != null && rawArguments != null
                ? piiVault.rehydrateArguments(rawArguments)
                : (rawArguments == null ? Map.of() : rawArguments);

        String entityKey = text(args.get("entityKey"));
        Integer scenarioCount = asInteger("scenarioCount", args.get("scenarioCount"));
        Integer horizonPeriods = asInteger("horizonPeriods", args.get("horizonPeriods"));
        Long seed = asLong("seed", args.get("seed"));
        LocalDate asOfDate = asLocalDate("asOfDate", args.get("asOfDate"));
        String samplingStrategy = text(args.get("samplingStrategy"));
        String dependenceMethod = text(args.get("dependenceMethod"));
        List<Map<String, Object>> modelPoints = mapList(args.get("modelPoints"));
        List<Map<String, Object>> stochasticFactors = mapList(args.get("stochasticFactors"));
        List<Map<String, Object>> correlations = mapList(args.get("correlations"));
        List<Map<String, Object>> thresholds = mapList(args.get("thresholds"));
        List<Double> quantiles = doubleList("quantiles", args.get("quantiles"));
        List<Double> anchorQuantiles = doubleList("anchorQuantiles", args.get("anchorQuantiles"));
        Integer minBucketCount = asInteger("minBucketCount", args.get("minBucketCount"));
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        AgentStatisticalAnalysisResult result = bridge.runStochasticSimulation(
                context,
                new AgentStochasticSimulationRequest(
                        entityKey,
                        null,
                        scenarioCount,
                        horizonPeriods,
                        seed,
                        asOfDate,
                        samplingStrategy,
                        dependenceMethod,
                        modelPoints,
                        stochasticFactors,
                        correlations,
                        thresholds,
                        quantiles,
                        anchorQuantiles,
                        minBucketCount,
                        filters));

        if (artifacts != null) {
            artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                    AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION,
                    TOOL_NAME,
                    result.uiPayload(),
                    result.epistemicEnvelope()));
        }

        return piiVault != null
                ? piiVault.scrubOutboundForModel(result.modelSafePayload())
                : result.modelSafePayload();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> {
                    if (k != null) {
                        copy.put(k.toString(), v);
                    }
                });
                out.add(copy);
            }
        }
        return out;
    }

    private static List<Double> doubleList(String fieldName, Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Number n) {
                out.add(n.doubleValue());
            } else if (item instanceof String s && StringUtils.hasText(s)) {
                try {
                    out.add(Double.parseDouble(s.trim()));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid value for " + fieldName + ": " + s.trim(), ex);
                }
            } else if (item != null) {
                throw new IllegalArgumentException("Invalid numeric element for " + fieldName + ": " + item);
            }
        }
        return out;
    }

    private static Integer asInteger(String fieldName, Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid value for " + fieldName + ": " + s.trim(), ex);
            }
        }
        return null;
    }

    private static Long asLong(String fieldName, Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid value for " + fieldName + ": " + s.trim(), ex);
            }
        }
        return null;
    }

    private static LocalDate asLocalDate(String fieldName, Object value) {
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return LocalDate.parse(s.trim());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid value for " + fieldName + ": " + s.trim(), ex);
            }
        }
        return null;
    }
}
