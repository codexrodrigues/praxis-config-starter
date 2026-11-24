package org.praxisplatform.config.ai.agent.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest;
import org.springframework.util.StringUtils;

/**
 * Neutral agent tool invoking cohort statistical inference ({@code /stats/inference}) through
 * {@link AgentResourceExecutionBridge} and recording epistemic extension artifacts.
 */
public class RunResourceStatisticalInferenceTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "run_resource_statistical_inference";

    private final AgentResourceExecutionBridge bridge;

    public RunResourceStatisticalInferenceTool(AgentResourceExecutionBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge is required");
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entityKey", Map.of(
                "type", "string",
                "description", "Canonical entity key or resourcePath from discover_topic_resources_and_schemas."));
        properties.put("tests", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Non-empty list of inference tests (e.g. WILCOXON_SIGNED_RANK, MANN_WHITNEY_U, KOLMOGOROV_SMIRNOV_2SAMP, WELCH_T, ONE_WAY_ANOVA, CHI_SQUARE_INDEPENDENCE, BENFORD_NIGRINI_MAD)."));
        properties.put("groupByField", Map.of(
                "type", "string",
                "description", "Optional categorical field used to group inference cohorts."));
        properties.put("expectedField", Map.of(
                "type", "string",
                "description", "Optional numeric field holding the baseline or expected value."));
        properties.put("observedField", Map.of(
                "type", "string",
                "description", "Optional numeric field holding the observed value."));
        properties.put("residualField", Map.of(
                "type", "string",
                "description", "Optional precomputed residual field (observed minus expected)."));
        properties.put("variableOrRetroactiveEligible", Map.of(
                "type", "boolean",
                "description", "Required true when invoking BENFORD_NIGRINI_MAD forensic test."));
        properties.put("alpha", Map.of(
                "type", "number",
                "description", "Significance threshold in (0, 1); defaults to 0.05."));
        properties.put("materialityThreshold", Map.of(
                "type", "number",
                "description", "Optional minimum financial materiality threshold."));
        properties.put("minBucketCount", Map.of(
                "type", "integer",
                "description", "Optional k-anonymity floor (never lower than server policy)."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Optional FilterDTO property map applied before cohort extraction."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Execute nonparametric or parametric cohort statistical inference with BH FDR control, k-anonymity suppression, and fail-closed epistemic provenance.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "tests"));
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
        List<String> tests = stringList(args.get("tests"));
        String groupByField = text(args.get("groupByField"));
        String expectedField = text(args.get("expectedField"));
        String observedField = text(args.get("observedField"));
        String residualField = text(args.get("residualField"));
        Boolean variableEligible = args.get("variableOrRetroactiveEligible") instanceof Boolean b ? b : null;
        Double alpha = asDouble(args.get("alpha"));
        BigDecimal materiality = asBigDecimal(args.get("materialityThreshold"));
        Integer minBucketCount = asInteger(args.get("minBucketCount"));
        Long seed = asLong(args.get("seed"));
        LocalDate asOfDate = asLocalDate(args.get("asOfDate"));
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        AgentStatisticalAnalysisResult result = bridge.runStatisticalInference(
                context,
                new AgentStatisticalInferenceRequest(
                        entityKey,
                        tests,
                        groupByField,
                        expectedField,
                        observedField,
                        residualField,
                        variableEligible,
                        alpha,
                        materiality,
                        minBucketCount,
                        seed,
                        asOfDate,
                        filters));

        if (artifacts != null) {
            artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                    AgentExtensionArtifact.KIND_STATISTICAL_INFERENCE,
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

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (item != null && StringUtils.hasText(item.toString())) {
                out.add(item.toString().trim());
            }
        }
        return out;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Double.parseDouble(s.trim());
        }
        return null;
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return new BigDecimal(s.trim());
        }
        return null;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Integer.parseInt(s.trim());
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Long.parseLong(s.trim());
        }
        return null;
    }

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return LocalDate.parse(s.trim());
        }
        return null;
    }
}
