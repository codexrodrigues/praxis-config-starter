package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatsAggregationRequest;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;

/**
 * Ferramenta de agregacao estatistica governada em processo ({@code /stats/group-by},
 * {@code /stats/timeseries}, {@code /stats/distribution}) que materializa grafico {@code <praxis-chart>}
 * na UI e devolve series numericamente consolidadas ao modelo.
 */
public class AggregateResourceStatsTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "aggregate_resource_stats";

    private final AgentResourceExecutionBridge bridge;

    public AggregateResourceStatsTool(AgentResourceExecutionBridge bridge) {
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
                "description", "Identificador do recurso (entityKey, resourceKey ou resourcePath) com statsCapability no digest."));
        properties.put("operationKind", Map.of(
                "type", "string",
                "description", "Opcional: GROUP_BY (padrao), TIMESERIES ou DISTRIBUTION."));
        properties.put("field", Map.of(
                "type", "string",
                "description", "Opcional: campo elegivel declarado em statsCapability.fields."));
        properties.put("metricOperation", Map.of(
                "type", "string",
                "description", "Opcional: COUNT (padrao), DISTINCT_COUNT, SUM, AVG, MIN ou MAX."));
        properties.put("metricField", Map.of(
                "type", "string",
                "description", "Opcional: campo numerico elegivel como metricField quando metricOperation != COUNT."));
        properties.put("granularity", Map.of(
                "type", "string",
                "description", "Opcional para TIMESERIES: DAY, WEEK ou MONTH."));
        properties.put("distributionMode", Map.of(
                "type", "string",
                "description", "Opcional para DISTRIBUTION: TERMS ou HISTOGRAM."));
        properties.put("chartKind", Map.of(
                "type", "string",
                "description", "Opcional: bar, horizontal-bar, line, pie ou donut."));
        properties.put("title", Map.of(
                "type", "string",
                "description", "Opcional: titulo executivo curto do grafico."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Opcional: mapa campo->valor com propriedades do FilterDTO."));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Opcional: maximo de buckets (1 a 50, padrao 12)."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Executa agregacao analitica in-process (group-by, timeseries ou distribution) sobre campos declarados em StatsFieldRegistry e gera visualizacao em grafico.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey"));
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
        String operationKind = text(args.get("operationKind"));
        String field = text(args.get("field"));
        String metricOperation = text(args.get("metricOperation"));
        String metricField = text(args.get("metricField"));
        String granularity = text(args.get("granularity"));
        String distributionMode = text(args.get("distributionMode"));
        String chartKind = text(args.get("chartKind"));
        String title = text(args.get("title"));
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        int limit = asInt(args.get("limit"), 12);

        AgentStatsAggregationRequest request = new AgentStatsAggregationRequest(
                entityKey,
                operationKind,
                field,
                metricOperation,
                metricField,
                granularity,
                distributionMode,
                chartKind,
                title,
                filters,
                limit);

        AgentStatsResult result = bridge.aggregateStats(context, request);
        artifacts.recordStatsResult(result);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("entityKey", nonNull(result.entityKey()));
        output.put("resourceKey", nonNull(result.resourceKey()));
        output.put("resourcePath", nonNull(result.resourcePath()));
        output.put("title", nonNull(result.title()));
        output.put("subtitle", nonNull(result.subtitle()));
        output.put("operationKind", nonNull(result.operationKind()));
        output.put("chartKind", nonNull(result.chartKind()));
        output.put("field", nonNull(result.field()));
        output.put("metricOperation", nonNull(result.metricOperation()));
        if (result.metricField() != null) {
            output.put("metricField", result.metricField());
        }
        output.put("totalElements", result.totalElements());
        output.put("buckets", result.buckets().stream()
                .map(b -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", nonNull(b.key()));
                    row.put("label", nonNull(b.label()));
                    row.put("value", b.value());
                    row.put("count", b.count());
                    return row;
                })
                .toList());
        if (result.emptyReason() != null) {
            output.put("emptyReason", result.emptyReason());
        }
        return piiVault != null ? piiVault.scrubOutboundForModel(output) : output;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
