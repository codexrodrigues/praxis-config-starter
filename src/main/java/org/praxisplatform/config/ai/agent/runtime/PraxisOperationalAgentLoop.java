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
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentTopicDescriptor;
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
    private final AgentResourceExecutionBridge bridge;

    public PraxisOperationalAgentLoop(
            AiToolCallingProvider toolCallingProvider,
            List<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer) {
        this(toolCallingProvider, tools, blockComposer, DEFAULT_MAX_ITERATIONS, null);
    }

    public PraxisOperationalAgentLoop(
            AiToolCallingProvider toolCallingProvider,
            List<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer,
            int maxIterations) {
        this(toolCallingProvider, tools, blockComposer, maxIterations, null);
    }

    public PraxisOperationalAgentLoop(
            AiToolCallingProvider toolCallingProvider,
            List<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer,
            int maxIterations,
            AgentResourceExecutionBridge bridge) {
        this.toolCallingProvider = Objects.requireNonNull(toolCallingProvider, "toolCallingProvider is required");
        this.blockComposer = Objects.requireNonNull(blockComposer, "blockComposer is required");
        this.maxIterations = Math.max(1, maxIterations);
        this.bridge = bridge;
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
        String lastFinishReason = null;
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
            progress.onStatus("reasoning", "Analisando solicitação operacional...");

            AgentModelTurnResponse response = toolCallingProvider.generateTurn(
                    new AgentModelTurnRequest(systemInstruction, conversation, declarations, callConfig),
                    AgentModelStreamListener.NOOP);

            if (StringUtils.hasText(response.provider())) {
                provider = response.provider();
            }
            if (StringUtils.hasText(response.model())) {
                model = response.model();
            }
            if (StringUtils.hasText(response.finishReason())) {
                lastFinishReason = response.finishReason();
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
                    output = Map.of("error", "Ferramenta operacional desconhecida: " + call.name());
                    error = true;
                } else {
                    try {
                        Map<String, Object> rawOutput = tool.execute(context, vault, artifacts, call.arguments());
                        output = rawOutput == null
                                ? Map.of()
                                : vault.scrubOutboundForModel(OperationalAssistantBlockComposer.maskMod11InStructure(rawOutput));
                    } catch (Exception ex) {
                        log.warn("[OperationalAgentLoop] Tool '{}' failed: {}", call.name(), ex.getMessage(), ex);
                        output = Map.of("error", "Falha ao executar ferramenta operacional.");
                        error = true;
                    }
                }
                long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                artifacts.recordTrace(call.name(), call.name(), durationMs, estimateResultCount(output));
                progress.onToolCompleted(call.name(), round, durationMs);
                results.add(new AgentToolResult(call.callId(), call.name(), output, error));
            }
            conversation.add(AgentConversationItem.toolResults(results));
            if (round == maxIterations && !progress.isCancelled()) {
                AgentModelTurnResponse synthesis = toolCallingProvider.generateTurn(
                        new AgentModelTurnRequest(systemInstruction, conversation, declarations, callConfig, true),
                        AgentModelStreamListener.NOOP);
                if (StringUtils.hasText(synthesis.provider())) {
                    provider = synthesis.provider();
                }
                if (StringUtils.hasText(synthesis.model())) {
                    model = synthesis.model();
                }
                totalUsage = totalUsage.plus(synthesis.usage());
                finalText = StringUtils.hasText(synthesis.text())
                        ? synthesis.text().trim()
                        : "Consulta operacional concluída com as evidências disponíveis.";
            }
        }

        if (!StringUtils.hasText(finalText)) {
            finalText = buildFallbackNarrative(artifacts, lastFinishReason);
        }
        finalText = OperationalAssistantBlockComposer.maskMod11TaxIds(finalText);
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
                String safeHistory = vault.tokenizeAdditional(entry.text());
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

    private String buildSystemInstruction(AgentExecutionContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é o assistente operacional da plataforma Praxis.\n");
        sb.append("Regras obrigatórias:\n");
        sb.append("1. Sempre comece por 'search_and_resolve_entities' quando o usuário informar um documento ([TAX_ID_n]), e-mail ([EMAIL_n]), código, matrícula, nome ou termo curto sem entidade inequívoca.\n");
        sb.append("2. Se 'search_and_resolve_entities' retornar exatamente 1 candidato (SINGLE_MATCH) e já incluir 'autoLoadedDossier', não chame 'get_entity_360_dossier' novamente; responda diretamente consolidando os dados, alertas e ações já retornados. Chame 'get_entity_360_dossier' apenas quando 'autoLoadedDossier' não estiver presente ou quando o usuário escolher um candidato específico.\n");
        sb.append("3. Se 'search_and_resolve_entities' retornar múltiplos candidatos (MULTIPLE_CANDIDATES), nunca escolha um deles por conta própria; apresente a síntese e peça ao usuário para escolher na lista.\n");
        sb.append("4. Para listar coleções ou aplicar filtros específicos, use 'discover_topic_resources_and_schemas' e 'query_resource_records'. Se não houver correspondência relevante na busca polimórfica global, responda de forma objetiva sem repetir múltiplas consultas vazias.\n");
        sb.append("5. Nunca invente fatos, números, alertas ou permissões; não gere HTML nem blocos JSON na resposta em texto pois a UI renderizará os blocos ricos automaticamente a partir das ferramentas.\n");
        sb.append("6. Seja executivo e conciso (1 a 3 frases curtas): não repita como lista em markdown os campos detalhados de 'autoLoadedDossier', 'get_entity_360_dossier', candidatos de 'search_and_resolve_entities' ou linhas de 'query_resource_records' quando os blocos ricos da UI já os exibirem; destaque apenas a identificação principal, o status e alertas críticos.\n");
        sb.append("7. Para análises quantitativas, gráficos, rankings ou séries temporais, inspecione 'statsCapability' via 'discover_topic_resources_and_schemas' (se necessário) e chame 'aggregate_resource_stats' usando apenas campos declarados.\n");
        sb.append("8. Para perguntas sobre fórmulas, contracheques, descontos, tetos ou composições de valores auditáveis, use 'explain_calculation_memory' (ou o campo 'calculationTrails' do dossiê) e explique apenas os passos retornados sem inventar parcelas.\n");
        sb.append("9. Para perguntas sobre leis, estatutos, regras declarativas ou motivos de bloqueio/validação, consulte 'search_domain_knowledge_and_rules' respeitando o tenant/ente ativo do contexto.\n");
        sb.append("10. Para simular cenários, prévias, saldos projetados ou elegibilidade sem gravar no banco, use 'simulate_determination_or_rules' informando 'draftFields' coerentes com o digest do recurso e deixe claro que é simulação não persistida.\n");
        sb.append("11. Nunca efetue ou declare uma mutação de banco diretamente durante o turno. Para ações ou edições individuais de 1 registro, chame 'prepare_action_or_mutation'. Para alterações em lote envolvendo múltiplos registros (UNIFORM_UPDATE ou PER_ITEM_UPDATE) ou comandos coletivos @WorkflowAction/@BulkOperation (DOMAIN_COMMAND), inspecione 'bulkCapability' no digest do recurso se necessário e chame 'prepare_bulk_operation' para avaliar sem escrita e gerar a proposta vinculada para revisão no modal dedicado.\n");
        sb.append("12. Contexto focado (Fast Grounding): se 'Registro/escopo ativo em foco na conversa' já identificar inequivocamente o recurso ('resourcePath'/'entityKey') e/ou registro ('recordId'/'entityId') e a pergunta do usuário se referir diretamente ao item ou tela aberta (ex.: 'este registro', 'este cálculo', 'estes indicadores', 'homologar este item'), use diretamente 'get_entity_360_dossier', 'explain_calculation_memory', 'prepare_action_or_mutation' ou 'prepare_bulk_operation' sem repetir 'discover_topic_resources_and_schemas' nem 'search_and_resolve_entities'. Caso o usuário nomeie pessoa, matrícula, CPF/CNPJ, lotação ou recurso diferente do foco atual, chame 'search_and_resolve_entities' obrigatoriamente.\n");
        if (bridge != null) {
            try {
                List<AgentTopicDescriptor> topics = bridge.listTopics(context);
                if (topics != null && !topics.isEmpty()) {
                    sb.append("Tópicos de domínio disponíveis neste host: ");
                    for (int i = 0; i < Math.min(topics.size(), 10); i++) {
                        AgentTopicDescriptor topic = topics.get(i);
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(topic.topicKey()).append(" (").append(topic.title()).append(')');
                    }
                    sb.append(".\n");
                }
            } catch (RuntimeException ex) {
                log.debug("[OperationalAgentLoop] Topics unavailable during instruction composition: {}", ex.getMessage());
            }
        }
        if (context != null && StringUtils.hasText(context.activeTopicKey())) {
            sb.append("Tópico em foco na tela ativa: ")
                    .append(sanitizePromptHint(context.activeTopicKey(), 120))
                    .append(".\n");
        }
        if (context != null && StringUtils.hasText(context.activeScreenResourcePath())) {
            sb.append("Contexto consultivo da tela aberta (apenas desempate, não bloqueia buscas em outros módulos): ")
                    .append(sanitizePromptHint(context.activeScreenResourcePath(), 240))
                    .append(".\n");
        }
        if (context != null && context.activeSelectionHints() != null && !context.activeSelectionHints().isEmpty()) {
            Object scrubbedHints = OperationalAssistantBlockComposer.maskMod11InStructure(context.activeSelectionHints());
            sb.append("Registro/escopo ativo em foco na conversa (somente leitura/referência; sanitizado): ")
                    .append(sanitizePromptHint(
                            TurnScopedPiiVault.maskRawEmails(
                                    OperationalAssistantBlockComposer.maskMod11TaxIds(String.valueOf(scrubbedHints))),
                            768))
                    .append(".\n");
        }
        return sb.toString();
    }

    private static String sanitizePromptHint(String raw, int maxLen) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String singleLine = raw.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return singleLine.length() > maxLen ? singleLine.substring(0, maxLen) : singleLine;
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

    private static String buildFallbackNarrative(AgentTurnArtifactStore artifacts, String lastFinishReason) {
        if (!artifacts.actionProposals().isEmpty()) {
            return "Proposta governada preparada abaixo. Revise os dados antes de confirmar a execução.";
        }
        if (!artifacts.simulationPreviews().isEmpty()) {
            return "Simulação não persistida calculada com os parâmetros informados abaixo.";
        }
        if (!artifacts.dossiers().isEmpty()) {
            return "Dados consolidados no dossiê operacional abaixo.";
        }
        if (!artifacts.calculationMemories().isEmpty()) {
            return "Memória de cálculo detalhada nas trilhas auditáveis abaixo.";
        }
        if (!artifacts.statsResults().isEmpty()) {
            return "Agregação analítica consolidada no gráfico abaixo.";
        }
        if (!artifacts.knowledgeCitations().isEmpty()) {
            return "Referências normativas e regras aplicáveis localizadas abaixo.";
        }
        if (artifacts.lastSearchMatches().size() > 1) {
            return "Encontrei múltiplos registros correspondentes. Escolha qual deseja abrir:";
        }
        if (!artifacts.queryResults().isEmpty()) {
            return "Consulta concluída com os registros retornados abaixo.";
        }
        if (artifacts.traces().isEmpty()) {
            String reason = StringUtils.hasText(lastFinishReason)
                    ? " (motivo: " + lastFinishReason + ")"
                    : "";
            return "O provedor de IA encerrou o turno sem texto nem consulta às ferramentas operacionais"
                    + reason + ". Tente reformular a solicitação ou iniciar uma nova consulta.";
        }
        return "Não foram encontrados registros para os critérios informados.";
    }
}
