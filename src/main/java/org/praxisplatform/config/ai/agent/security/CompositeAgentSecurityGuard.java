package org.praxisplatform.config.ai.agent.security;

import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool.AgentToolSafetyClass;

import java.util.List;
import java.util.Map;

/**
 * Evaluates multiple {@link AgentSecurityGuard} implementations sequentially, stopping on the
 * first {@link AgentSecurityVerdict.Decision#BLOCK} verdict while propagating sanitization changes.
 */
public final class CompositeAgentSecurityGuard implements AgentSecurityGuard {

    private final List<AgentSecurityGuard> guards;

    public CompositeAgentSecurityGuard(List<AgentSecurityGuard> guards) {
        this.guards = guards == null ? List.of() : List.copyOf(guards);
    }

    @Override
    public InboundCheckResult guardInboundUserInput(
            String userMessage,
            AgentExecutionContext context,
            TurnSecurityState state
    ) {
        String currentMsg = userMessage;
        AgentExecutionContext currentCtx = context;
        AgentSecurityVerdict strongest = AgentSecurityVerdict.allow();
        for (AgentSecurityGuard guard : guards) {
            InboundCheckResult result = guard.guardInboundUserInput(currentMsg, currentCtx, state);
            currentMsg = result.sanitizedUserMessage();
            currentCtx = result.sanitizedContext();
            strongest = mergeVerdict(strongest, result.verdict());
            if (result.verdict().isBlocked()) {
                return new InboundCheckResult(result.verdict(), currentMsg, currentCtx);
            }
        }
        return new InboundCheckResult(strongest, currentMsg, currentCtx);
    }

    @Override
    public HistoryCheckResult guardHistoryEntry(String role, String text, TurnSecurityState state) {
        String current = text;
        AgentSecurityVerdict strongest = AgentSecurityVerdict.allow();
        for (AgentSecurityGuard guard : guards) {
            HistoryCheckResult result = guard.guardHistoryEntry(role, current, state);
            current = result.sanitizedText();
            strongest = mergeVerdict(strongest, result.verdict());
            if (result.verdict().isBlocked()) {
                return new HistoryCheckResult(result.verdict(), current);
            }
        }
        return new HistoryCheckResult(strongest, current);
    }

    @Override
    public AgentSecurityVerdict guardBeforeToolExecution(
            String toolName,
            AgentToolSafetyClass safetyClass,
            Map<String, Object> arguments,
            TurnSecurityState state
    ) {
        AgentSecurityVerdict strongest = AgentSecurityVerdict.allow();
        for (AgentSecurityGuard guard : guards) {
            AgentSecurityVerdict verdict = guard.guardBeforeToolExecution(toolName, safetyClass, arguments, state);
            strongest = mergeVerdict(strongest, verdict);
            if (verdict.isBlocked()) {
                return verdict;
            }
        }
        return strongest;
    }

    @Override
    public ToolOutputCheckResult guardToolOutput(
            String toolName,
            AgentToolSafetyClass safetyClass,
            Map<String, Object> payload,
            TurnSecurityState state
    ) {
        Map<String, Object> currentPayload = payload;
        AgentSecurityVerdict strongest = AgentSecurityVerdict.allow();
        for (AgentSecurityGuard guard : guards) {
            ToolOutputCheckResult result = guard.guardToolOutput(toolName, safetyClass, currentPayload, state);
            currentPayload = result.sanitizedPayload();
            strongest = mergeVerdict(strongest, result.verdict());
            if (result.verdict().isBlocked()) {
                return new ToolOutputCheckResult(result.verdict(), currentPayload);
            }
        }
        return new ToolOutputCheckResult(strongest, currentPayload);
    }

    @Override
    public OutboundCheckResult guardOutboundResponse(String narrative, TurnSecurityState state) {
        String current = narrative;
        AgentSecurityVerdict strongest = AgentSecurityVerdict.allow();
        for (AgentSecurityGuard guard : guards) {
            OutboundCheckResult result = guard.guardOutboundResponse(current, state);
            current = result.sanitizedNarrative();
            strongest = mergeVerdict(strongest, result.verdict());
            if (result.verdict().isBlocked()) {
                return new OutboundCheckResult(result.verdict(), current);
            }
        }
        return new OutboundCheckResult(strongest, current);
    }

    private static AgentSecurityVerdict mergeVerdict(AgentSecurityVerdict a, AgentSecurityVerdict b) {
        if (b == null) {
            return a;
        }
        return b.decision().ordinal() > a.decision().ordinal() ? b : a;
    }
}
