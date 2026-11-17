package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.model.AgentConversationItem;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.model.AgentToolResult;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool;
import org.praxisplatform.config.service.AiCallConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * Loop ReAct operacional com no maximo {@code maxIterations} rodadas explicitas de function calling.
 *
 * <p>Nunca mantem transacoes de banco abertas durante chamadas de I/O aos provedores LLM.
 */
@Slf4j
public class PraxisOperationalAgentLoop {

    public static final int DEFAULT_MAX_ITERATIONS = 6;

    public interface TurnProgressObserver {
        TurnProgressObserver NOOP = new TurnProgressObserver() {
        };

        default void onStatus(String code, String text) {
        }

        default void onToolStarted(String toolName, int round) {
        }

        default void onToolCompleted(String toolName, int round, long durationMs) {
        }

        default boolean isCancelled() {
            return false;
        }
    }

    public record ConversationHistoryEntry(String role, String text) {
    }

    public record AgentTurnOutcome(
            String provider,
            String model,
            String assistantMessage,
            com.fasterxml.jackson.databind.JsonNode assistantContent,
            List<Map<String, Object>> quickReplies,
            List<AgentTurnArtifactStore.ToolExecutionTrace> traces,
            AgentTokenUsage usage,
            int roundsExecuted
    ) {
    }

    private final AiToolCallingProvider toolCallingProvider;
    private final Map<String, OperationalAgentTool> toolsByName;
    private final OperationalAssistantBlockComposer blockComposer;
    private final int maxIterations;

    public PraxisOperationalAgentLoop(
            AiToolCallingProvider toolCallingProvider,
            List<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer) {
        this(toolCallingProvider, tools, blockComposer, DEFAULT_MAX_ITERATIONS);
    }

    public PraxisOperationalAgentLoop(
            AiToolCallingProvider toolCallingProvider,
            List<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer,
            int maxIterations) {
        this.toolCallingProvider = Objects.requireNonNull(toolCallingProvider, "toolCallingProvider is required");
        this.blockComposer = Objects.requireNonNull(blockComposer, "blockComposer is required");
        this.maxIterations = Math.max(1, maxIterations);
        Map<String, OperationalAgentTool> map = new LinkedHashMap<>();
        if (tools != null) {
            for (OperationalAgentTool tool : tools) {
                if (tool != null && StringUtils.hasText(tool.name())) {
                    map.put(tool.name(), tool);
                }
            }
        }
        this.toolsByName = Map.copyOf(map);
    }

    public AgentTurnOutcome execute(
            AgentExecutionContext context,
            String rawUserPrompt,
            TurnScopedPiiVault preTokenizedVault,
            List<ConversationHistoryEntry> history,
            AiCallConfig callConfig,
            TurnProgressObserver observer) {
        TurnProgressObserver progress = observer != null ? observer : TurnProgressObserver.NOOP;
        TurnScopedPiiVault vault = preTokenizedVault != null
                ? preTokenizedVault
                : TurnScopedPiiVault.tokenize(rawUserPrompt);

        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        List<AgentToolDeclaration> declarations = toolsByName.values().stream()
                .map(OperationalAgentTool::declaration)
                .toList();

        List<AgentConversationItem> conversation = buildInitialConversation(history, vault.sanitizedPrompt(), vault);
        String systemInstruction = buildSystemInstruction(context);
        AgentTokenUsage totalUsage = AgentTokenUsage.ZERO;
        String finalText = "";
        String provider = callConfig != null ? callConfig.getProvider() : null;
        String model = callConfig != null ? callConfig.getModel() : null;
        int rounds = 0;

        for (int round = 1; round <= maxIterations; round++) {
            if (progress.isCancelled()) {
                throw new IllegalStateException("Turn cancelled before LLM round " + round);
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Agent loop must never hold an active database transaction during LLM I/O.");
            }
            rounds = round;
            progress.onStatus("reasoning", "Analisando solicitacao operacional...");

            AgentModelTurnResponse response = toolCallingProvider.generateTurn(
                    new AgentModelTurnRequest(systemInstruction, conversation, declarations, callConfig),
                    AgentModelStreamListener.NOOP);

            if (StringUtils.hasText(response.provider())) {
                provider = response.provider();
            }
            if (StringUtils.hasText(response.model())) {
                model = response.model();
            }
            totalUsage = totalUsage.plus(response.usage());

            if (!response.hasToolCalls()) {
                finalText = StringUtils.hasText(response.text()) ? response.text().trim() : "";
                break;
            }

            conversation.add(AgentConversationItem.assistantToolCalls(
                    response.text(),
                    response.toolCalls(),
                    response.providerOpaqueState()));

            List<AgentToolResult> results = new ArrayList<>(response.toolCalls().size());
            for (AgentToolCall call : response.toolCalls()) {
                if (progress.isCancelled()) {
                    throw new IllegalStateException("Turn cancelled during tool execution: " + call.name());
                }
                progress.onToolStarted(call.name(), round);
                long startNanos = System.nanoTime();
                OperationalAgentTool tool = toolsByName.get(call.name());
                Map<String, Object> output;
                boolean error = false;
                if (tool == null) {
                    output = Map.of("error", "Unknown operational tool: " + call.name());
                    error = true;
                } else {
                    try {
                        output = tool.execute(context, vault, artifacts, call.arguments());
                    } catch (Exception ex) {
                        log.warn("[OperationalAgentLoop] Tool '{}' failed: {}", call.name(), ex.getMessage(), ex);
                        output = Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                        error = true;
                    }
                }
                long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                artifacts.recordTrace(call.name(), call.name(), durationMs, estimateResultCount(output));
                progress.onToolCompleted(call.name(), round, durationMs);
                results.add(new AgentToolResult(call.callId(), call.name(), output, error));
            }
            conversation.add(AgentConversationItem.toolResults(results));
            if (round == maxIterations) {
                finalText = "Consulta operacional concluida com as evidencias disponiveis.";
            }
        }

        if (!StringUtils.hasText(finalText)) {
            finalText = buildFallbackNarrative(artifacts);
        }
        OperationalAssistantBlockComposer.ComposedAssistantOutput composed =
                blockComposer.compose(finalText, artifacts);

        return new AgentTurnOutcome(
                provider,
                model,
                finalText,
                composed.assistantContent(),
                composed.quickReplies(),
                artifacts.traces(),
                totalUsage,
                rounds);
    }

    private static List<AgentConversationItem> buildInitialConversation(
            List<ConversationHistoryEntry> history,
            String sanitizedPrompt,
            TurnScopedPiiVault vault) {
        List<AgentConversationItem> items = new ArrayList<>();
        if (history != null) {
            for (ConversationHistoryEntry entry : history) {
                if (entry == null || !StringUtils.hasText(entry.text())) {
                    continue;
                }
                String safeHistory = TurnScopedPiiVault.tokenize(entry.text()).sanitizedPrompt();
                if ("assistant".equalsIgnoreCase(entry.role())) {
                    items.add(AgentConversationItem.assistantText(safeHistory));
                } else {
                    items.add(AgentConversationItem.userText(safeHistory));
                }
            }
        }
        items.add(AgentConversationItem.userText(sanitizedPrompt));
        return items;
    }

    private static String buildSystemInstruction(AgentExecutionContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Voce e o assistente operacional da plataforma Praxis.\n");
        sb.append("Regras obrigatorias:\n");
        sb.append("1. Sempre comece por 'search_and_resolve_entities' quando o usuario informar um documento ([TAX_ID_n]), codigo, matricula, nome ou termo curto sem entidade inequivoca.\n");
        sb.append("2. Se 'search_and_resolve_entities' retornar exactamente 1 candidato (SINGLE_MATCH) e ja incluir 'autoLoadedDossier', nao chame 'get_entity_360_dossier' novamente; responda diretamente consolidando os dados, alertas e acoes ja retornados. Chame 'get_entity_360_dossier' apenas quando 'autoLoadedDossier' nao estiver presente ou quando o usuario escolher um candidato especifico.\n");
        sb.append("3. Se 'search_and_resolve_entities' retornar multiplos candidatos (MULTIPLE_CANDIDATES), nunca escolha um deles por conta propria; apresente os candidatos encontrados e peca ao usuario para escolher.\n");
        sb.append("4. Para listar colecoes ou aplicar filtros especificos, use 'discover_topic_resources_and_schemas' e 'query_resource_records'.\n");
        sb.append("5. Nunca invente fatos, numeros, alertas ou permissoes; nao gere HTML nem blocos JSON na resposta em texto pois a UI renderizara os blocos ricos automaticamente a partir das ferramentas.\n");
        if (context != null && StringUtils.hasText(context.activeScreenResourcePath())) {
            sb.append("Contexto consultivo da tela aberta (apenas desempate, nao bloqueia buscas em outros modulos): ")
                    .append(context.activeScreenResourcePath())
                    .append(".\n");
        }
        return sb.toString();
    }

    private static int estimateResultCount(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return 0;
        }
        if (output.get("matchCount") instanceof Number n) {
            return n.intValue();
        }
        if (output.get("totalElements") instanceof Number n) {
            return n.intValue();
        }
        if (Boolean.TRUE.equals(output.get("found"))) {
            return 1;
        }
        return output.size();
    }

    private static String buildFallbackNarrative(AgentTurnArtifactStore artifacts) {
        if (!artifacts.dossiers().isEmpty()) {
            return "Dados consolidados no dossie operacional abaixo.";
        }
        if (artifacts.lastSearchMatches().size() > 1) {
            return "Encontrei multiplos registros correspondentes. Escolha qual deseja abrir:";
        }
        if (!artifacts.queryResults().isEmpty()) {
            return "Consulta concluida com os registros retornados abaixo.";
        }
        return "Nao foram encontrados registros para os criterios informados.";
    }
}
