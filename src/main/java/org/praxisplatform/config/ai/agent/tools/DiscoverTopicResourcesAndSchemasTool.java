package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentTopicDescriptor;
import org.springframework.util.StringUtils;

/**
 * Ferramenta sob demanda para descobrir topicos de negocio, entidades disponiveis e um digest
 * compacto de schema/filtros/acoes sem inflar o prompt do sistema.
 */
public class DiscoverTopicResourcesAndSchemasTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "discover_topic_resources_and_schemas";

    private final AgentResourceExecutionBridge bridge;

    public DiscoverTopicResourcesAndSchemasTool(AgentResourceExecutionBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge is required");
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("topicOrQuery", Map.of(
                "type", "string",
                "description", "Opcional: assunto, modulo ou palavra-chave de negocio (ex.: ferias, departamentos, cargos, folha)."));
        properties.put("entityKey", Map.of(
                "type", "string",
                "description", "Opcional: entityKey, resourceKey ou resourcePath especifico para inspecionar seus campos de filtro, resposta e acoes."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Descobre topicos de negocio, recursos @ApiResource acessiveis e um resumo compacto dos filtros e acoes de uma entidade.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of());
    }

    @Override
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = rawArguments == null ? Map.of() : rawArguments;
        String topicOrQuery = args.get("topicOrQuery") == null ? null : args.get("topicOrQuery").toString().trim();
        String entityKey = args.get("entityKey") == null ? null : args.get("entityKey").toString().trim();

        List<AgentTopicDescriptor> topics = bridge.listTopics(context);
        List<AgentEntityDescriptor> entities = bridge.listEntities(context, topicOrQuery);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topics", topics);
        result.put("entities", entities);
        if (StringUtils.hasText(entityKey)) {
            bridge.describeEntitySchema(context, entityKey)
                    .ifPresent(digest -> result.put("schemaDigest", digest));
        }
        return result;
    }
}
