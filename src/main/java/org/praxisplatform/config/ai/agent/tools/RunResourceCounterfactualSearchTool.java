package org.praxisplatform.config.ai.agent.tools;

import java.math.BigDecimal;
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
import org.praxisplatform.config.ai.agent.spi.AgentCounterfactualSearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalAnalysisResult;
import org.springframework.util.StringUtils;

/**
 * Neutral agent tool invoking constrained counterfactual recourse search ({@code /stats/counterfactual})
 * through {@link AgentResourceExecutionBridge}. Strictly advisory ({@code VALIDATED_BY_RULES_ENGINE}
 * candidates only; never mutates business state).
 */
public class RunResourceCounterfactualSearchTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "run_resource_counterfactual_search";

    private final AgentResourceExecutionBridge bridge;

    public RunResourceCounterfactualSearchTool(AgentResourceExecutionBridge bridge) {
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
        properties.put("targetMetricKey", Map.of(
                "type", "string",
                "description", "Target metric key to move toward the requested goal or compliance band."));
        properties.put("direction", Map.of(
                "type", "string",
                "enum", List.of("DECREASE_TO_TARGET", "INCREASE_TO_TARGET", "REACH_BAND"),
                "description", "Target direction: DECREASE_TO_TARGET, INCREASE_TO_TARGET, or REACH_BAND."));
        properties.put("baselineValue", Map.of(
                "type", "number",
                "description", "Current observed baseline value of targetMetricKey."));
        properties.put("targetValue", Map.of(
                "type", "number",
                "description", "Desired target value (or lower bound when direction is REACH_BAND)."));
        properties.put("targetUpperBound", Map.of(
                "type", "number",
                "description", "Optional upper bound when direction is REACH_BAND."));

        Map<String, Object> leverProps = new LinkedHashMap<>();
        leverProps.put("leverKey", Map.of("type", "string"));
        leverProps.put("leverType", Map.of(
                "type", "string",
                "enum", List.of("CONTINUOUS", "DISCRETE_INT", "CATEGORICAL")));
        leverProps.put("currentValue", Map.of("description", "Current numeric or categorical value."));
        leverProps.put("minValue", Map.of("type", "number"));
        leverProps.put("maxValue", Map.of("type", "number"));
        leverProps.put("stepSize", Map.of("type", "number"));
        leverProps.put("allowedCategories", Map.of("type", "array", "items", Map.of("type", "string")));
        leverProps.put("costWeight", Map.of("type", "number"));
        leverProps.put("linearSensitivity", Map.of("type", "number"));
        leverProps.put("categoryImpacts", Map.of("type", "object"));
        leverProps.put("monotoneSign", Map.of("type", "integer"));
        properties.put("mutableLevers", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", leverProps,
                        "required", List.of("leverKey", "leverType")),
                "description", "Mutable recourse levers validated deterministically by rules engine."));

        properties.put("maxCandidates", Map.of(
                "type", "integer",
                "description", "Maximum recourse candidates to evaluate and validate."));
        properties.put("maxChangedLevers", Map.of(
                "type", "integer",
                "description", "Sparsity cap: maximum number of levers changed per candidate."));
        properties.put("seed", Map.of(
                "type", "integer",
                "description", "Optional deterministic optimization seed."));
        properties.put("asOfDate", Map.of(
                "type", "string",
                "description", "Optional ISO-8601 reference date (YYYY-MM-DD)."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Optional FilterDTO property map scoping baseline records."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Search advisory-only counterfactual recourse options (OPTIMIZATION_RESULT) over mutable operational levers. Strictly fail-closed: emits only candidates approved by the JVM rules engine (VALIDATED_BY_RULES_ENGINE) and never mutates resource state.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "targetMetricKey", "direction", "baselineValue", "targetValue", "mutableLevers"));
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
        String targetMetricKey = text(args.get("targetMetricKey"));
        String direction = text(args.get("direction"));
        BigDecimal baselineValue = asBigDecimal("baselineValue", args.get("baselineValue"));
        BigDecimal targetValue = asBigDecimal("targetValue", args.get("targetValue"));
        BigDecimal targetUpperBound = asBigDecimal("targetUpperBound", args.get("targetUpperBound"));
        List<Map<String, Object>> mutableLevers = mapList(args.get("mutableLevers"));
        Integer maxCandidates = asInteger("maxCandidates", args.get("maxCandidates"));
        Integer maxChangedLevers = asInteger("maxChangedLevers", args.get("maxChangedLevers"));
        Long seed = asLong("seed", args.get("seed"));
        LocalDate asOfDate = asLocalDate("asOfDate", args.get("asOfDate"));
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        AgentStatisticalAnalysisResult result = bridge.runCounterfactualSearch(
                context,
                new AgentCounterfactualSearchRequest(
                        entityKey,
                        null,
                        targetMetricKey,
                        direction,
                        baselineValue,
                        targetValue,
                        targetUpperBound,
                        mutableLevers,
                        maxCandidates,
                        maxChangedLevers,
                        seed,
                        asOfDate,
                        filters));

        if (artifacts != null) {
            artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                    AgentExtensionArtifact.KIND_COUNTERFACTUAL_SEARCH,
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

    private static BigDecimal asBigDecimal(String fieldName, Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid value for " + fieldName + ": " + s.trim(), ex);
            }
        }
        return null;
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
