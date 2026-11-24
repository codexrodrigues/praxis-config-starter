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
import org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalAnalysisResult;
import org.springframework.util.StringUtils;

/**
 * Neutral agent tool invoking robust anomaly scanning ({@code /stats/anomalies}) through
 * {@link AgentResourceExecutionBridge} and recording epistemic extension artifacts.
 */
public class ScanResourceAnomaliesTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "scan_resource_anomalies";

    private final AgentResourceExecutionBridge bridge;

    public ScanResourceAnomaliesTool(AgentResourceExecutionBridge bridge) {
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
        properties.put("methods", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Non-empty list of detection methods: PAIRED_RESIDUAL_SHIFT, ROBUST_MAD_ZSCORE, GRUBBS_ESD, NIGRINI_BENFORD, EMPIRICAL_BAYES_SHRINKAGE."));
        properties.put("columnMapping", Map.of(
                "type", "object",
                "description", "Optional mapping overriding semantic column slots (cohortColumn, groupColumn, expectedColumn, observedColumn, residualColumn, variableFlagColumn, unitColumn)."));
        properties.put("alpha", Map.of(
                "type", "number",
                "description", "BH FDR significance level in (0, 1); defaults to 0.05."));
        properties.put("materialityThreshold", Map.of(
                "type", "number",
                "description", "Optional minimum financial materiality threshold."));
        properties.put("madThreshold", Map.of(
                "type", "number",
                "description", "Optional positive MAD z-score threshold; defaults to 3.5."));
        properties.put("minCohortSize", Map.of(
                "type", "integer",
                "description", "Optional minimum cohort size; defaults to 15."));
        properties.put("minGroupSize", Map.of(
                "type", "integer",
                "description", "Optional minimum collective group size; defaults to 20."));
        properties.put("enableBenford", Map.of(
                "type", "boolean",
                "description", "Enable Nigrini-Benford forensic scan (requires enableBenford=true)."));
        properties.put("minBenfordSamples", Map.of(
                "type", "integer",
                "description", "Minimum eligible sample size for Benford test; defaults to 500."));
        properties.put("minBucketCount", Map.of(
                "type", "integer",
                "description", "Optional k-anonymity floor (never lower than server policy)."));
        properties.put("maxFindings", Map.of(
                "type", "integer",
                "description", "Maximum returned findings; defaults to 200."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Optional FilterDTO property map applied before cohort extraction."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Execute robust MAD/ESD, Empirical Bayes, and Nigrini-Benford anomaly scanning with BH FDR control, k-anonymity protection, and fail-closed epistemic provenance.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "methods"));
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
        List<String> methods = stringList(args.get("methods"));
        Map<String, String> columnMapping = stringMap(args.get("columnMapping"));
        Double alpha = asDouble(args.get("alpha"));
        BigDecimal materiality = asBigDecimal(args.get("materialityThreshold"));
        Double madThreshold = asDouble(args.get("madThreshold"));
        Integer minCohortSize = asInteger(args.get("minCohortSize"));
        Integer minGroupSize = asInteger(args.get("minGroupSize"));
        Boolean enableBenford = args.get("enableBenford") instanceof Boolean b ? b : null;
        Integer minBenfordSamples = asInteger(args.get("minBenfordSamples"));
        Integer minBucketCount = asInteger(args.get("minBucketCount"));
        Integer maxFindings = asInteger(args.get("maxFindings"));
        Long seed = asLong(args.get("seed"));
        LocalDate asOfDate = asLocalDate(args.get("asOfDate"));
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        AgentStatisticalAnalysisResult result = bridge.scanResourceAnomalies(
                context,
                new AgentAnomalyScanRequest(
                        entityKey,
                        methods,
                        columnMapping,
                        alpha,
                        materiality,
                        madThreshold,
                        minCohortSize,
                        minGroupSize,
                        enableBenford,
                        minBenfordSamples,
                        minBucketCount,
                        maxFindings,
                        seed,
                        asOfDate,
                        filters));

        if (artifacts != null) {
            artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                    AgentExtensionArtifact.KIND_ANOMALY_SCAN,
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

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null && StringUtils.hasText(v.toString())) {
                out.put(k.toString().trim(), v.toString().trim());
            }
        });
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
