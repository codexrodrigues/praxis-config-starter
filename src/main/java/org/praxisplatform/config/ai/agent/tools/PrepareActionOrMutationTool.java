package org.praxisplatform.config.ai.agent.tools;

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
import org.praxisplatform.config.ai.agent.spi.AgentPrepareActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedAction;

/**
 * Ferramenta governada que avalia estado, ETag forte, autoridades, escopo organizacional e DTO de
 * comando para persistir uma proposta ({@code ai_agent_action_proposal}) aguardando confirmacao humana.
 */
public class PrepareActionOrMutationTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "prepare_action_or_mutation";

    private final OperationalAgentProposalService proposalService;

    public PrepareActionOrMutationTool(OperationalAgentProposalService proposalService) {
        this.proposalService = Objects.requireNonNull(proposalService, "proposalService is required");
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
                "description", "Identificador canonico do registro alvo (obrigatorio para acoes ou edicoes de item)."));
        properties.put("actionId", Map.of(
                "type", "string",
                "description", "Identificador da acao @WorkflowAction, intencao @ResourceIntent ou 'edit'."));
        properties.put("proposedInputs", Map.of(
                "type", "object",
                "description", "Opcional: valores de entrada propostos pelo usuario para pre-preenchimento."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Prepara e persiste uma proposta governada de acao ou edicao (sem efetuar escrita imediata), capturando ETag forte, escopo organizacional, campos obrigatorios e eventuais bloqueios para confirmacao humana.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("entityKey", "actionId"));
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
        String entityId = text(args.get("entityId"));
        String actionId = text(args.get("actionId"));
        Map<String, Object> proposedInputs = args.get("proposedInputs") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        AgentPersistedActionProposal persisted = proposalService.prepareAndPersist(
                context,
                new AgentPrepareActionRequest(entityKey, entityId, actionId, proposedInputs));
        artifacts.recordActionProposal(persisted);

        AgentPreparedAction prep = persisted.prepared();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("persistedWriteExecuted", false);
        out.put("proposalId", persisted.proposalId().toString());
        out.put("proposalStatus", persisted.proposalStatus());
        out.put("expiresAt", persisted.expiresAt() != null ? persisted.expiresAt().toString() : null);
        out.put("entityKey", prep.entityKey());
        out.put("resourcePath", prep.resourcePath());
        out.put("entityId", prep.entityId());
        out.put("recordTitle", OperationalAssistantBlockComposer.maskMod11TaxIds(prep.recordTitle()));
        out.put("actionId", prep.actionId());
        out.put("actionTitle", prep.actionTitle());
        out.put("operationKind", prep.operationKind());
        out.put("formMode", prep.formMode());
        out.put("riskLevel", prep.riskLevel());
        out.put("organizationId", prep.organizationId());
        out.put("organizationLabel", prep.organizationLabel());
        out.put("recordLifecycleState", prep.recordLifecycleState());
        out.put("expectedVersionTag", prep.expectedVersionTag());
        out.put("allowed", prep.allowed());
        out.put("blockedCode", prep.blockedCode());
        out.put("impedimentNote", prep.impedimentNote());
        out.put("hasInputForm", prep.hasInputForm());
        out.put("missingRequiredFields", prep.missingRequiredFields());
        out.put("inputFields", prep.inputFields().stream()
                .map(AgentActionInputField::name)
                .toList());
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
}
