package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentDeterminationRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentSimulationPreview;

/**
 * Ferramenta de simulacao nao persistente que invoca endpoints {@code /determinations/*} em modo
 * somente leitura / rollback e registra um bloco visual {@code simulation-preview}.
 */
public class SimulateDeterminationOrRulesTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "simulate_determination_or_rules";

    private final AgentResourceExecutionBridge bridge;

    public SimulateDeterminationOrRulesTool(AgentResourceExecutionBridge bridge) {
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
                "description", "Identificador do recurso (entityKey, resourceKey ou resourcePath) que declara determinações em digest."));
        properties.put("determinationId", Map.of(
                "type", "string",
                "description", "Opcional: identificador da determinação (ex.: previa). Obrigatório apenas quando o recurso declarar múltiplas determinações."));
        properties.put("draftFields", Map.of(
                "type", "object",
                "description", "Mapa campo->valor com os parâmetros de rascunho a simular sem persistência."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Simula regras e endpoints /determinations/* sem persistir dados no banco, devolvendo projeção de campos e eventuais inconsistências.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "draftFields"));
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
        String determinationId = text(args.get("determinationId"));
        Map<String, Object> draftFields = args.get("draftFields") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        AgentSimulationPreview preview = bridge.simulateDetermination(
                context,
                new AgentDeterminationRequest(entityKey, determinationId, draftFields));
        artifacts.recordSimulationPreview(preview);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nonPersistent", true);
        result.put("entityKey", preview.entityKey());
        result.put("resourceKey", preview.resourceKey());
        result.put("resourcePath", preview.resourcePath());
        result.put("determinationId", preview.determinationId());
        result.put("title", OperationalAssistantBlockComposer.maskMod11TaxIds(preview.title()));
        result.put("preview", preview.modelSafePreview());
        result.put("validationIssues", preview.validationIssues());
        Map<String, Object> scrubbed = piiVault != null ? piiVault.scrubOutboundForModel(result) : result;
        return OperationalAssistantBlockComposer.maskMod11InStructure(scrubbed);
    }

    private static String text(Object val) {
        if (val == null) {
            return null;
        }
        String str = val.toString().trim();
        return str.isEmpty() ? null : str;
    }
}
