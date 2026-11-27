package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import org.praxisplatform.config.ai.agent.security.AgentSecurityFinding;
import org.praxisplatform.config.ai.agent.security.AgentSecurityGuard;
import org.praxisplatform.config.ai.agent.security.AgentSecurityMode;
import org.praxisplatform.config.ai.agent.security.AgentSecurityVerdict;
import org.praxisplatform.config.ai.agent.security.DefaultDeterministicAgentSecurityGuard;
import org.praxisplatform.config.ai.agent.security.TurnSecurityState;
import org.praxisplatform.config.ai.agent.spi.AgentEpistemicEnvelope;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.ai.agent.spi.AgentPersistedActionProposal;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentTopicDescriptor;
import org.praxisplatform.config.ai.agent.telemetry.AgentTurnTelemetryRecorder;
import org.praxisplatform.config.ai.agent.telemetry.AgentTurnTelemetrySummary;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
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
            int roundsExecuted,
            AgentTurnTelemetrySummary telemetrySummary
    ) {
        public AgentTurnOutcome(
                String provider,
                String model,
                String assistantMessage,
                com.fasterxml.jackson.databind.JsonNode assistantContent,
                List<Map<String, Object>> quickReplies,
                List<AgentTurnArtifactStore.ToolExecutionTrace> traces,
                AgentTokenUsage usage,
                int roundsExecuted) {
            this(provider, model, assistantMessage, assistantContent, quickReplies, traces, usage, roundsExecuted, null);
        }
    }

    private final AiToolCallingProvider toolCallingProvider;
    private final Map<String, OperationalAgentTool> toolsByName;
    private final OperationalAssistantBlockComposer blockComposer;
    private final int maxIterations;
    private final AgentResourceExecutionBridge bridge;
    private final AgentSystemInstructionComposer instructionComposer;
    private final AgentSecurityGuard securityGuard;
    private final AgentSecurityMode securityMode;
    private final AgentTurnTelemetryRecorder telemetryRecorder;

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
        this(
                toolCallingProvider,
                tools,
                blockComposer,
                maxIterations,
                bridge,
                null,
                null,
                null,
                null);
    }

    public PraxisOperationalAgentLoop(
            AiToolCallingProvider toolCallingProvider,
            List<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer,
            int maxIterations,
            AgentResourceExecutionBridge bridge,
            AgentSystemInstructionComposer instructionComposer,
            AgentSecurityGuard securityGuard,
            AgentSecurityMode securityMode,
            AgentTurnTelemetryRecorder telemetryRecorder) {
        this.toolCallingProvider = Objects.requireNonNull(toolCallingProvider, "toolCallingProvider is required");
        this.blockComposer = Objects.requireNonNull(blockComposer, "blockComposer is required");
        this.maxIterations = Math.max(1, maxIterations);
        this.bridge = bridge;
        this.instructionComposer = instructionComposer != null
                ? instructionComposer
                : AgentSystemInstructionComposer.defaultComposer();
        this.securityGuard = securityGuard != null
                ? securityGuard
                : new DefaultDeterministicAgentSecurityGuard();
        this.securityMode = securityMode != null ? securityMode : AgentSecurityMode.ENFORCE;
        this.telemetryRecorder = telemetryRecorder != null
                ? telemetryRecorder
                : AgentTurnTelemetryRecorder.NOOP;
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
                .sorted(Comparator.comparing(AgentToolDeclaration::name))
                .toList();

        TurnSecurityState securityState = new TurnSecurityState(securityMode);
        AgentTurnTelemetryRecorder.TurnSpan turnSpan = telemetryRecorder.startTurn(
                context,
                callConfig != null ? callConfig.getProvider() : null,
                callConfig != null ? callConfig.getModel() : null);

        AgentTokenUsage totalUsage = AgentTokenUsage.ZERO;
        String finalText = "";
        String lastFinishReason = null;
        String provider = callConfig != null ? callConfig.getProvider() : null;
        String model = callConfig != null ? callConfig.getModel() : null;
        int rounds = 0;
        String finishStatus = "completed";

        try {
            int priorFindingsCount = securityState.findings().size();
            AgentSecurityGuard.InboundCheckResult inbound = securityGuard.guardInboundUserInput(
                    vault.sanitizedPrompt(), context, securityState);
            recordNewFindings(turnSpan, securityState, priorFindingsCount);
            if (inbound.verdict().isBlocked()) {
                finishStatus = "blocked_inbound";
                String denialText = StringUtils.hasText(inbound.verdict().reason())
                        ? inbound.verdict().reason()
                        : "Solicitacao bloqueada pela politica de seguranca.";
                OperationalAssistantBlockComposer.ComposedAssistantOutput composedBlocked =
                        blockComposer.compose(denialText, artifacts);
                AgentTurnTelemetrySummary summary = turnSpan.complete(
                        provider, model, 0, totalUsage, securityState.isTainted(), finishStatus);
                return new AgentTurnOutcome(
                        provider,
                        model,
                        denialText,
                        composedBlocked.assistantContent(),
                        composedBlocked.quickReplies(),
                        artifacts.traces(),
                        totalUsage,
                        0,
                        summary);
            }

            AgentExecutionContext effectiveContext = inbound.sanitizedContext();
            AgentExecutionContext modelSafeContext = sanitizeExecutionContextForModel(effectiveContext);
            String safePrompt = inbound.sanitizedUserMessage();
            List<AgentConversationItem> conversation = buildInitialConversation(
                    history, safePrompt, vault, securityGuard, securityState, turnSpan);
            String systemInstruction = buildSystemInstruction(modelSafeContext, effectiveContext);

            for (int round = 1; round <= maxIterations; round++) {
                if (progress.isCancelled()) {
                    finishStatus = "cancelled";
                    throw new IllegalStateException("Turn cancelled before LLM round " + round);
                }
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    finishStatus = "error";
                    throw new IllegalStateException(
                            "Agent loop must never hold an active database transaction during LLM I/O.");
                }
                rounds = round;
                progress.onStatus("reasoning", "Analisando solicitação operacional...");

                AgentExecutionContext roundModelContext = enrichModelContextForTurn(modelSafeContext, securityState);
                AgentModelTurnResponse response;
                try (AgentTurnTelemetryRecorder.ModelRoundSpan roundSpan =
                        turnSpan.startModelRound(round, provider, model)) {
                    try {
                        response = toolCallingProvider.generateTurn(
                                new AgentModelTurnRequest(
                                        systemInstruction,
                                        conversation,
                                        declarations,
                                        callConfig,
                                        false,
                                        Map.of(),
                                        roundModelContext),
                                AgentModelStreamListener.NOOP);
                    } catch (RuntimeException ex) {
                        roundSpan.fail(ex);
                        throw ex;
                    }
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
                    roundSpan.finish(
                            provider,
                            model,
                            response.usage(),
                            response.toolCalls().size(),
                            response.finishReason());
                }

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
                        finishStatus = "cancelled";
                        throw new IllegalStateException("Turn cancelled during tool execution: " + call.name());
                    }
                    progress.onToolStarted(call.name(), round);
                    long startNanos = System.nanoTime();
                    OperationalAgentTool tool = toolsByName.get(call.name());
                    OperationalAgentTool.AgentToolSafetyClass safetyClass = tool != null
                            ? tool.safetyClass()
                            : OperationalAgentTool.AgentToolSafetyClass.READ_ONLY;
                    String recipeId = call.arguments() != null && call.arguments().get("recipeId") != null
                            ? String.valueOf(call.arguments().get("recipeId"))
                            : null;

                    Map<String, Object> output;
                    boolean error = false;
                    boolean blockedBySecurity = false;

                    try (AgentTurnTelemetryRecorder.ToolExecutionSpan toolSpan =
                            turnSpan.startToolExecution(call.name(), safetyClass.name(), recipeId)) {
                        int beforeFindings = securityState.findings().size();
                        AgentSecurityVerdict preVerdict = securityGuard.guardBeforeToolExecution(
                                call.name(), safetyClass, call.arguments(), securityState);
                        recordNewFindings(turnSpan, securityState, beforeFindings);

                        if (preVerdict.isBlocked()) {
                            blockedBySecurity = true;
                            error = true;
                            output = Map.of(
                                    "error", StringUtils.hasText(preVerdict.reason())
                                            ? preVerdict.reason()
                                            : "Operacao bloqueada pela politica de seguranca.",
                                    "blockedBySecurity", true,
                                    "ruleId", preVerdict.ruleId());
                        } else if (tool == null) {
                            output = Map.of("error", "Ferramenta operacional desconhecida: " + call.name());
                            error = true;
                        } else {
                            try {
                                Map<String, Object> rawOutput = tool.execute(effectiveContext, vault, artifacts, call.arguments());
                                recordToolAccessAndSovereignty(call.arguments(), rawOutput, securityState);
                                Map<String, Object> scrubbed = rawOutput == null
                                        ? Map.of()
                                        : vault.scrubOutboundForModel(
                                                OperationalAssistantBlockComposer.maskMod11InStructure(rawOutput));
                                int outputFindingsStart = securityState.findings().size();
                                AgentSecurityGuard.ToolOutputCheckResult outputCheck =
                                        securityGuard.guardToolOutput(call.name(), safetyClass, scrubbed, securityState);
                                recordNewFindings(turnSpan, securityState, outputFindingsStart);
                                output = outputCheck.sanitizedPayload() != null ? outputCheck.sanitizedPayload() : scrubbed;
                            } catch (Exception ex) {
                                log.warn("[OperationalAgentLoop] Tool '{}' failed: {}", call.name(), ex.getMessage(), ex);
                                toolSpan.fail(ex);
                                output = Map.of("error", "Falha ao executar ferramenta operacional.");
                                error = true;
                            }
                        }
                        toolSpan.finish(error, blockedBySecurity);
                    }
                    long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                    artifacts.recordTrace(call.name(), call.name(), durationMs, estimateResultCount(output));
                    progress.onToolCompleted(call.name(), round, durationMs);
                    results.add(new AgentToolResult(call.callId(), call.name(), output, error));
                }
                conversation.add(AgentConversationItem.toolResults(results));
                if (round == maxIterations && !progress.isCancelled()) {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        finishStatus = "error";
                        throw new IllegalStateException(
                                "Agent loop must never hold an active database transaction during LLM I/O.");
                    }
                    AgentExecutionContext synthModelContext = enrichModelContextForTurn(modelSafeContext, securityState);
                    try (AgentTurnTelemetryRecorder.ModelRoundSpan synthSpan =
                            turnSpan.startModelRound(round + 1, provider, model)) {
                        AgentModelTurnResponse synthesis;
                        try {
                            synthesis = toolCallingProvider.generateTurn(
                                    new AgentModelTurnRequest(
                                            systemInstruction,
                                            conversation,
                                            declarations,
                                            callConfig,
                                            true,
                                            Map.of(),
                                            synthModelContext),
                                    AgentModelStreamListener.NOOP);
                        } catch (RuntimeException ex) {
                            synthSpan.fail(ex);
                            throw ex;
                        }
                        if (StringUtils.hasText(synthesis.provider())) {
                            provider = synthesis.provider();
                        }
                        if (StringUtils.hasText(synthesis.model())) {
                            model = synthesis.model();
                        }
                        totalUsage = totalUsage.plus(synthesis.usage());
                        synthSpan.finish(
                                provider,
                                model,
                                synthesis.usage(),
                                0,
                                synthesis.finishReason());
                        finalText = StringUtils.hasText(synthesis.text())
                                ? synthesis.text().trim()
                                : "Consulta operacional concluída com as evidências disponíveis.";
                    }
                }
            }

            if (!StringUtils.hasText(finalText)) {
                finalText = buildFallbackNarrative(artifacts, lastFinishReason);
            }
            int outFindingsStart = securityState.findings().size();
            AgentSecurityGuard.OutboundCheckResult outboundCheck =
                    securityGuard.guardOutboundResponse(finalText, securityState);
            recordNewFindings(turnSpan, securityState, outFindingsStart);
            finalText = OperationalAssistantBlockComposer.maskMod11TaxIds(
                    outboundCheck.sanitizedNarrative() != null ? outboundCheck.sanitizedNarrative() : finalText);
            OperationalAssistantBlockComposer.ComposedAssistantOutput composed =
                    blockComposer.compose(finalText, artifacts);

            recordTurnArtifactCorrelation(turnSpan, artifacts);
            AgentTurnTelemetrySummary summary = turnSpan.complete(
                    provider, model, rounds, totalUsage, securityState.isTainted(), finishStatus);
            return new AgentTurnOutcome(
                    provider,
                    model,
                    finalText,
                    composed.assistantContent(),
                    composed.quickReplies(),
                    artifacts.traces(),
                    totalUsage,
                    rounds,
                    summary);
        } catch (RuntimeException ex) {
            if ("completed".equals(finishStatus)) {
                finishStatus = "error";
            }
            turnSpan.fail(ex);
            turnSpan.complete(provider, model, rounds, totalUsage, securityState.isTainted(), finishStatus);
            throw ex;
        } finally {
            turnSpan.close();
        }
    }

    private static void recordNewFindings(
            AgentTurnTelemetryRecorder.TurnSpan turnSpan,
            TurnSecurityState state,
            int fromIndex) {
        List<AgentSecurityFinding> all = state.findings();
        for (int i = fromIndex; i < all.size(); i++) {
            turnSpan.recordSecurityFinding(all.get(i));
        }
    }

    private static List<AgentConversationItem> buildInitialConversation(
            List<ConversationHistoryEntry> history,
            String sanitizedPrompt,
            TurnScopedPiiVault vault,
            AgentSecurityGuard securityGuard,
            TurnSecurityState securityState,
            AgentTurnTelemetryRecorder.TurnSpan turnSpan) {
        List<AgentConversationItem> items = new ArrayList<>();
        if (history != null) {
            for (ConversationHistoryEntry entry : history) {
                if (entry == null || !StringUtils.hasText(entry.text())) {
                    continue;
                }
                String safeHistory = vault.tokenizeAdditional(entry.text());
                int before = securityState.findings().size();
                AgentSecurityGuard.HistoryCheckResult historyCheck =
                        securityGuard.guardHistoryEntry(entry.role(), safeHistory, securityState);
                recordNewFindings(turnSpan, securityState, before);
                String finalHistoryText = historyCheck != null && StringUtils.hasText(historyCheck.sanitizedText())
                        ? historyCheck.sanitizedText()
                        : safeHistory;
                if ("assistant".equalsIgnoreCase(entry.role())) {
                    items.add(AgentConversationItem.assistantText(finalHistoryText));
                } else {
                    items.add(AgentConversationItem.userText(finalHistoryText));
                }
            }
        }
        items.add(AgentConversationItem.userText(sanitizedPrompt));
        return items;
    }

    private String buildSystemInstruction(AgentExecutionContext modelSafeContext, AgentExecutionContext effectiveContext) {
        return instructionComposer.compose(modelSafeContext, buildTopicDiscoverySummary(effectiveContext));
    }

    private static void recordTurnArtifactCorrelation(
            AgentTurnTelemetryRecorder.TurnSpan turnSpan,
            AgentTurnArtifactStore artifacts) {
        if (turnSpan == null || artifacts == null) {
            return;
        }
        String epistemicKind = null;
        String provenanceHash = null;
        List<AgentExtensionArtifact> extensions = artifacts.extensionArtifacts();
        if (!extensions.isEmpty()) {
            AgentExtensionArtifact latest = extensions.get(extensions.size() - 1);
            if (latest != null && latest.epistemicEnvelope() != null) {
                epistemicKind = latest.epistemicEnvelope().epistemicKind();
                provenanceHash = latest.epistemicEnvelope().provenanceHash();
            }
        }
        String proposalId = null;
        List<AgentPersistedActionProposal> proposals = artifacts.actionProposals();
        if (!proposals.isEmpty()) {
            AgentPersistedActionProposal latestProposal = proposals.get(proposals.size() - 1);
            if (latestProposal != null && latestProposal.proposalId() != null) {
                proposalId = latestProposal.proposalId().toString();
            }
        }
        if (StringUtils.hasText(epistemicKind) || StringUtils.hasText(provenanceHash) || StringUtils.hasText(proposalId)) {
            turnSpan.recordArtifactCorrelation(epistemicKind, provenanceHash, proposalId);
        }
    }

    private static void recordToolAccessAndSovereignty(
            Map<String, Object> arguments,
            Map<String, Object> rawOutput,
            TurnSecurityState state) {
        if (state == null) {
            return;
        }
        collectAccessMetadata(arguments, state, 0);
        collectAccessMetadata(rawOutput, state, 0);
    }

    private static void collectAccessMetadata(Object node, TurnSecurityState state, int depth) {
        if (node == null || depth > 4) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? null : entry.getKey().toString();
                Object val = entry.getValue();
                if (val == null || "fields".equals(key) || "columns".equals(key)) {
                    continue;
                }
                if ("resourcePath".equals(key) && val instanceof String s && StringUtils.hasText(s)) {
                    state.recordAccessedResourcePath(s);
                } else if (("topicKey".equals(key) || "activeTopicKey".equals(key))
                        && val instanceof String s && StringUtils.hasText(s)) {
                    state.recordAccessedTopicKey(s);
                } else if ("sovereignOnly".equals(key)
                        && (Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val).trim()))) {
                    state.escalateToSovereign();
                } else if (val instanceof Map<?, ?> || val instanceof Collection<?>) {
                    collectAccessMetadata(val, state, depth + 1);
                }
            }
        } else if (node instanceof Collection<?> col) {
            for (Object item : col) {
                collectAccessMetadata(item, state, depth + 1);
            }
        }
    }

    private static AgentExecutionContext enrichModelContextForTurn(
            AgentExecutionContext base,
            TurnSecurityState state) {
        if (base == null || state == null) {
            return base;
        }
        Set<String> accessedPaths = state.accessedResourcePaths();
        Set<String> accessedTopics = state.accessedTopicKeys();
        boolean escalated = state.isSovereignEscalated();
        if (accessedPaths.isEmpty() && accessedTopics.isEmpty() && !escalated) {
            return base;
        }
        Map<String, Object> claims = base.propagatedClaims() != null
                ? new LinkedHashMap<>(base.propagatedClaims())
                : new LinkedHashMap<>();
        if (!accessedPaths.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>();
            addStringsFromClaim(claims.get("accessedResourcePaths"), merged);
            merged.addAll(accessedPaths);
            claims.put("accessedResourcePaths", List.copyOf(merged));
        }
        if (!accessedTopics.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>();
            addStringsFromClaim(claims.get("accessedTopicKeys"), merged);
            merged.addAll(accessedTopics);
            claims.put("accessedTopicKeys", List.copyOf(merged));
        }
        if (escalated) {
            claims.put("sovereignOnly", true);
        }
        return new AgentExecutionContext(
                base.threadId(),
                base.turnId(),
                base.streamId(),
                base.tenantId(),
                base.userId(),
                base.environment(),
                base.principalName(),
                base.authorities(),
                base.activeScreenResourcePath(),
                base.activeTopicKey(),
                base.activeSelectionHints(),
                Collections.unmodifiableMap(claims));
    }

    private static void addStringsFromClaim(Object rawClaim, Set<String> sink) {
        if (rawClaim instanceof String s && StringUtils.hasText(s)) {
            sink.add(s.trim());
        } else if (rawClaim instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null && StringUtils.hasText(item.toString())) {
                    sink.add(item.toString().trim());
                }
            }
        }
    }

    private String buildTopicDiscoverySummary(AgentExecutionContext context) {
        if (bridge == null) {
            return "";
        }
        try {
            List<AgentTopicDescriptor> topics = bridge.listTopics(context);
            if (topics == null || topics.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Tópicos de domínio disponíveis neste host: ");
            for (int i = 0; i < Math.min(topics.size(), 10); i++) {
                AgentTopicDescriptor topic = topics.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(topic.topicKey()).append(" (").append(topic.title()).append(')');
            }
            sb.append('.');
            return sb.toString();
        } catch (RuntimeException ex) {
            log.debug("[OperationalAgentLoop] Topics unavailable during instruction composition: {}", ex.getMessage());
            return "";
        }
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
        if (output.get("groups") instanceof List<?> groups) {
            return groups.size();
        }
        if (output.get("findings") instanceof List<?> findings) {
            return findings.size();
        }
        if (output.get("candidates") instanceof List<?> candidates) {
            return candidates.size();
        }
        if (output.get("fanChart") instanceof List<?> fanChart) {
            return fanChart.size();
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
        if (!artifacts.extensionArtifacts().isEmpty()) {
            AgentExtensionArtifact latest = artifacts.extensionArtifacts().get(artifacts.extensionArtifacts().size() - 1);
            if (latest != null && latest.epistemicEnvelope() != null
                    && !AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE.equals(
                            latest.epistemicEnvelope().availabilityState())) {
                String note = latest.epistemicEnvelope().methodologyNote();
                return StringUtils.hasText(note)
                        ? "Execução analítica indisponível ou restrita em modo fail-closed: "
                                + OperationalAssistantBlockComposer.maskPiiText(note)
                        : "Execução analítica indisponível ou restrita em modo fail-closed ("
                                + latest.epistemicEnvelope().availabilityState() + ").";
            }
            if (latest != null && AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION.equals(latest.kind())) {
                return "Projeção estocástica consolidada com selo epistêmico e revalidação de âncoras abaixo.";
            }
            if (latest != null && AgentExtensionArtifact.KIND_COUNTERFACTUAL_SEARCH.equals(latest.kind())) {
                return "Alternativas contrafactuais validadas pelo motor de regras disponíveis abaixo (caráter consultivo, sujeitas à aprovação humana).";
            }
            return "Análise estatística consolidada com selo epistêmico abaixo.";
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

    private static AgentExecutionContext sanitizeExecutionContextForModel(AgentExecutionContext context) {
        if (context == null) {
            return null;
        }
        return new AgentExecutionContext(
                context.threadId(),
                context.turnId(),
                context.streamId(),
                context.tenantId(),
                OperationalAssistantBlockComposer.maskPiiText(context.userId()),
                context.environment(),
                OperationalAssistantBlockComposer.maskPiiText(context.principalName()),
                context.authorities(),
                OperationalAssistantBlockComposer.maskPiiText(context.activeScreenResourcePath()),
                OperationalAssistantBlockComposer.maskPiiText(context.activeTopicKey()),
                OperationalAssistantBlockComposer.maskPiiInStructure(context.activeSelectionHints()),
                OperationalAssistantBlockComposer.maskPiiInStructure(context.propagatedClaims()));
    }
}
