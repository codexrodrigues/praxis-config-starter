package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;

/**
 * Ferramenta que consolida a visao 360 de um registro especifico: dados principais governados,
 * subcolecoes relacionadas, alertas proativos e acoes permitidas.
 */
public class GetEntity360DossierTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "get_entity_360_dossier";

    private final AgentResourceExecutionBridge bridge;

    public GetEntity360DossierTool(AgentResourceExecutionBridge bridge) {
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
        properties.put("entityId", Map.of(
                "type", "string",
                "description", "Chave primaria ou composta do registro selecionado."));
        properties.put("includeRelatedSurfaces", Map.of(
                "type", "array",
                "description", "Opcional: lista de IDs de superficies relacionadas a carregar no dossie.",
                "items", Map.of("type", "string")));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Carrega o dossie 360 completo de um registro confirmado, incluindo subcolecoes relacionadas, alertas proativos e acoes autorizadas.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "entityId"));
    }

    @Override
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = piiVault != null && rawArguments != null
                ? piiVault.rehydrateArguments(rawArguments)
                : (rawArguments == null ? Map.of() : rawArguments);
        String entityKey = args.get("entityKey") == null ? null : args.get("entityKey").toString().trim();
        String entityId = args.get("entityId") == null ? null : args.get("entityId").toString().trim();
        List<String> relatedSurfaces = args.get("includeRelatedSurfaces") instanceof List<?> list
                ? list.stream().filter(Objects::nonNull).map(Object::toString).toList()
                : List.of();

        Optional<AgentEntityDossier> found = bridge.loadEntityDossier(context, entityKey, entityId, relatedSurfaces);
        if (found.isEmpty()) {
            Map<String, Object> missing = Map.of(
                    "found", false,
                    "entityKey", entityKey == null ? "" : entityKey,
                    "entityId", entityId == null ? "" : entityId);
            return OperationalAssistantBlockComposer.maskMod11InStructure(
                    piiVault != null ? piiVault.scrubOutboundForModel(missing) : missing);
        }
        AgentEntityDossier dossier = found.get();
        artifacts.recordDossier(dossier);

        Map<String, Object> output = toModelSafeMap(dossier);
        Map<String, Object> scrubbed = piiVault != null ? piiVault.scrubOutboundForModel(output) : output;
        return OperationalAssistantBlockComposer.maskMod11InStructure(scrubbed);
    }

    public static Map<String, Object> toModelSafeMap(AgentEntityDossier dossier) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("found", true);
        output.put("entityKey", dossier.entity().entityKey());
        output.put("resourceKey", dossier.entity().resourceKey());
        output.put("resourcePath", dossier.entity().resourcePath());
        output.put("entityId", dossier.entityId());
        output.put("displayTitle", dossier.displayTitle());
        output.put("displaySubtitle", dossier.displaySubtitle());
        output.put("record", dossier.modelSafeRecord());
        output.put("maskedForAiFields", dossier.maskedForAiFields());
        output.put("alerts", dossier.alerts().stream()
                .map(alert -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", nonNullText(alert.code()));
                    item.put("severity", nonNullText(alert.severity()));
                    item.put("title", nonNullText(alert.title()));
                    item.put("description", nonNullText(alert.description()));
                    item.put("suggestedActionId", nonNullText(alert.suggestedActionId()));
                    return item;
                })
                .toList());
        output.put("relatedDatasets", dossier.relatedDatasets().stream()
                .map(ds -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("surfaceId", nonNullText(ds.surfaceId()));
                    item.put("title", nonNullText(ds.title()));
                    item.put("totalCount", ds.totalCount());
                    item.put("rows", ds.modelSafeRows());
                    return item;
                })
                .toList());
        output.put("actions", dossier.actions().stream()
                .map(act -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("actionId", nonNullText(act.actionId()));
                    item.put("label", nonNullText(act.label()));
                    item.put("operationKind", nonNullText(act.operationKind()));
                    item.put("disabled", act.disabled());
                    return item;
                })
                .toList());
        output.put("surfaces", dossier.surfaces().stream()
                .map(surf -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("surfaceId", nonNullText(surf.surfaceId()));
                    item.put("title", nonNullText(surf.title()));
                    item.put("available", surf.available());
                    return item;
                })
                .toList());
        AgentEvaluatedCalculationMemory calc = dossier.calculationMemory();
        if (calc != null && !calc.modelSafeTrails().isEmpty()) {
            output.put("calculationMemoryAvailable", true);
            output.put("calculationTrails", ExplainCalculationMemoryTool.mapModelSafeTrails(calc.modelSafeTrails()));
        }
        return output;
    }

    private static String nonNullText(String value) {
        return value == null ? "" : value;
    }
}
