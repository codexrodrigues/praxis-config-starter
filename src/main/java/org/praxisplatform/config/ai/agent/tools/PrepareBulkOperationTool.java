package org.praxisplatform.config.ai.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentProposalService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentActionInputField;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentPersistedActionProposal;
import org.praxisplatform.config.ai.agent.spi.AgentPrepareBulkRequest;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedAction;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedBulkOperation;

/**
 * Ferramenta governada que prepara e avalia sem efeitos colaterais operacoes em lote
 * ({@code UNIFORM_UPDATE}, {@code PER_ITEM_UPDATE} ou {@code DOMAIN_COMMAND}) no motor
 * {@code praxis_bulk}, persistindo proposta vinculada para revisao no modal dedicado.
 */
public class PrepareBulkOperationTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "prepare_bulk_operation";

    private final OperationalAgentProposalService proposalService;

    public PrepareBulkOperationTool(OperationalAgentProposalService proposalService) {
        this.proposalService = Objects.requireNonNull(proposalService, "proposalService is required");
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolSafetyClass safetyClass() {
        return AgentToolSafetyClass.PROPOSAL_MUTATION;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entityKey", Map.of(
                "type", "string",
                "description", "Identificador do recurso (entityKey, resourceKey ou resourcePath)."));
        properties.put("bulkMode", Map.of(
                "type", "string",
                "enum", List.of("UNIFORM_UPDATE", "PER_ITEM_UPDATE", "DOMAIN_COMMAND"),
                "description", "Modo da operacao em lote: UNIFORM_UPDATE, PER_ITEM_UPDATE ou DOMAIN_COMMAND."));
        properties.put("actionId", Map.of(
                "type", "string",
                "description", "Obrigatorio para DOMAIN_COMMAND: identificador do comando @WorkflowAction coletivo em lote."));
        properties.put("selectionMode", Map.of(
                "type", "string",
                "enum", List.of("EXPLICIT", "QUERY"),
                "description", "Modo de selecao: EXPLICIT (por targetIds) ou QUERY (por filters)."));
        properties.put("filters", Map.of(
                "type", "object",
                "description", "Filtros do recurso (FilterDTO) para selecao em QUERY ou resolucao de lote no servidor."));
        properties.put("targetIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Lista de identificadores canonicos selecionados quando selectionMode=EXPLICIT."));
        properties.put("excludedIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Identificadores excluidos da selecao por filtro quando selectionMode=QUERY."));
        Map<String, Object> uniformChangeItemProps = new LinkedHashMap<>();
        uniformChangeItemProps.put("field", Map.of("type", "string", "description", "Campo @BulkEditable alvo."));
        uniformChangeItemProps.put("operator", Map.of("type", "string", "enum", List.of("SET", "CLEAR"), "description", "Operador de atualizacao: SET ou CLEAR."));
        uniformChangeItemProps.put("value", Map.of("type", "string", "description", "Novo valor serializado quando operator=SET."));
        uniformChangeItemProps.put("clearValue", Map.of("type", "boolean", "description", "Opcional: true para limpar o campo quando permitido."));
        properties.put("changes", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", uniformChangeItemProps),
                "description", "Alteracoes uniformes [{field, operator: SET|CLEAR, value}] para UNIFORM_UPDATE."));

        Map<String, Object> perItemProps = new LinkedHashMap<>();
        perItemProps.put("id", Map.of("type", "string", "description", "Identificador canonico do registro alvo."));
        perItemProps.put("expectedVersion", Map.of("type", "string", "description", "Opcional: versao/ETag esperada do item."));
        perItemProps.put("values", Map.of("type", "object", "description", "Mapa opcional campo->valor para alteracao do item."));
        perItemProps.put("clearFields", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Lista opcional de campos a limpar no item."));
        perItemProps.put("changes", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", uniformChangeItemProps),
                "description", "Lista opcional de mudancas [{field, operator, value}] do item."));
        properties.put("items", Map.of(
                "type", "array",
                "items", Map.of(
                        "type", "object",
                        "properties", perItemProps),
                "description", "Alteracoes distintas por item [{id, changes: [{field, operator, value}]}] para PER_ITEM_UPDATE."));
        properties.put("parameters", Map.of(
                "type", "object",
                "description", "Parametros do comando coletivo quando bulkMode=DOMAIN_COMMAND."));
        properties.put("rationale", Map.of(
                "type", "string",
                "description", "Justificativa curta e objetiva da solicitacao em lote."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Avalia e prepara em processo uma operacao em lote governada (edicao uniforme, edicao por item ou comando coletivo) sem efetuar escrita definitiva; gera proposta persistida para revisao humana no modal de lote.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "bulkMode"));
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

        AgentPrepareBulkRequest req = new AgentPrepareBulkRequest(
                text(args.get("entityKey")),
                text(args.get("bulkMode")),
                text(args.get("actionId")),
                text(args.get("selectionMode")),
                toStringObjectMap(args.get("filters")),
                toStringList(args.get("targetIds")),
                toStringList(args.get("excludedIds")),
                toMapList(args.get("changes")),
                toMapList(args.get("items")),
                toStringObjectMap(args.get("parameters")),
                text(args.get("rationale")));

        AgentPersistedActionProposal persisted = proposalService.prepareAndPersistBulk(context, req);
        artifacts.recordActionProposal(persisted);

        AgentPreparedAction base = persisted.prepared();
        AgentPreparedBulkOperation bulk = persisted.bulk();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("persistedWriteExecuted", false);
        out.put("requiresModalReview", true);
        out.put("proposalId", persisted.proposalId().toString());
        out.put("proposalStatus", persisted.proposalStatus());
        out.put("expiresAt", persisted.expiresAt() != null ? persisted.expiresAt().toString() : null);
        if (base != null) {
            out.put("entityKey", base.entityKey());
            out.put("resourcePath", base.resourcePath());
            out.put("actionId", base.actionId());
            out.put("actionTitle", base.actionTitle());
            out.put("operationKind", base.operationKind());
            out.put("formMode", base.formMode());
            out.put("organizationId", base.organizationId());
            out.put("organizationLabel", base.organizationLabel());
            out.put("allowed", base.allowed());
            out.put("blockedCode", base.blockedCode());
            out.put("impedimentNote", base.impedimentNote());
            out.put("missingRequiredFields", base.missingRequiredFields());
            out.put("inputFields", base.inputFields().stream().map(AgentActionInputField::name).toList());
        }
        if (bulk != null) {
            out.put("domainProposalId", bulk.domainProposalId());
            out.put("bulkOperationId", bulk.bulkOperationId());
            out.put("bulkMode", bulk.bulkMode());
            out.put("atomicity", bulk.atomicity());
            out.put("selectionMode", bulk.selectionMode());
            out.put("domainProposalStatus", bulk.domainProposalStatus());
            out.put("totals", Map.of(
                    "targetCount", bulk.targetCount(),
                    "evaluated", bulk.evaluatedCount(),
                    "executable", bulk.executableCount(),
                    "blocked", bulk.blockedCount()));
            out.put("filterLabel", bulk.filterLabel());
            out.put("modelSafePreviewItems", bulk.modelSafePreviewItems());
            out.put("blockedDiagnostics", bulk.blockedDiagnostics());
        }
        Map<String, Object> scrubbed = piiVault != null ? piiVault.scrubOutboundForModel(out) : out;
        return OperationalAssistantBlockComposer.maskMod11InStructure(scrubbed);
    }

    private static String text(Object val) {
        if (val == null) {
            return null;
        }
        String str = val.toString().trim();
        return str.isEmpty() ? null : str;
    }

    private static Map<String, Object> toStringObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null) {
                copy.put(k.toString(), v);
            }
        });
        return copy;
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String s = item.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> toMapList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
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
}
