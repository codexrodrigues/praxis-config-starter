package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.springframework.util.StringUtils;

/**
 * Ferramenta de consulta filtrada e paginada sobre qualquer recurso {@code @ApiResource} do host.
 */
public class QueryResourceRecordsTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "query_resource_records";

    private final AgentResourceExecutionBridge bridge;

    public QueryResourceRecordsTool(AgentResourceExecutionBridge bridge) {
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
                "description", "Identificador do recurso (entityKey, resourceKey ou resourcePath)."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Opcional: mapa campo->valor com nomes de propriedades do FilterDTO."));
        properties.put("page", Map.of(
                "type", "integer",
                "description", "Opcional: pagina base zero (padrao 0)."));
        properties.put("size", Map.of(
                "type", "integer",
                "description", "Opcional: tamanho da pagina (1 a 20, padrao 10)."));
        properties.put("sort", Map.of(
                "type", "string",
                "description", "Opcional: ordenacao no formato campo,asc|desc."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Consulta registros paginados em um recurso usando filtros governados do FilterDTO e retorna resumo seguro ao modelo.",
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
        String entityKey = args.get("entityKey") == null ? null : args.get("entityKey").toString().trim();
        Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        int page = asInt(args.get("page"), 0);
        int size = asInt(args.get("size"), 10);
        String sort = args.get("sort") == null ? null : args.get("sort").toString().trim();

        AgentQueryResult queryResult = bridge.queryRecords(context, entityKey, filters, page, size, sort);
        artifacts.recordQueryResult(queryResult);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("entityKey", queryResult.entityKey());
        output.put("resourceKey", queryResult.resourceKey());
        output.put("resourcePath", queryResult.resourcePath());
        output.put("title", queryResult.title());
        output.put("totalElements", queryResult.totalElements());
        output.put("page", queryResult.page());
        output.put("size", queryResult.size());
        output.put("records", queryResult.modelSafeRecords());
        output.put("collectionActions", queryResult.collectionActions().stream()
                .map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("actionId", a.actionId() == null ? "" : a.actionId());
                    item.put("label", a.label() == null ? "" : a.label());
                    item.put("disabled", a.disabled());
                    return item;
                })
                .toList());
        return piiVault != null ? piiVault.scrubOutboundForModel(output) : output;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
