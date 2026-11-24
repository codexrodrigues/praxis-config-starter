package org.praxisplatform.config.ai.agent.security;

import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool.AgentToolSafetyClass;

import java.util.Map;

/**
 * Pluggable 4-stage security guard protecting operational agent loops from prompt injection,
 * ungrounded mutations, and untrusted tool-payload hijacking.
 */
public interface AgentSecurityGuard {

    default InboundCheckResult guardInboundUserInput(
            String userMessage,
            AgentExecutionContext context,
            TurnSecurityState state
    ) {
        return new InboundCheckResult(AgentSecurityVerdict.allow(), userMessage, context);
    }

    default HistoryCheckResult guardHistoryEntry(
            String role,
            String text,
            TurnSecurityState state
    ) {
        if (state != null && text != null && !text.isBlank()) {
            state.groundFromTrustedHistoryText(text);
        }
        return new HistoryCheckResult(AgentSecurityVerdict.allow(), text);
    }

    default AgentSecurityVerdict guardBeforeToolExecution(
            String toolName,
            AgentToolSafetyClass safetyClass,
            Map<String, Object> arguments,
            TurnSecurityState state
    ) {
        return AgentSecurityVerdict.allow();
    }

    default ToolOutputCheckResult guardToolOutput(
            String toolName,
            AgentToolSafetyClass safetyClass,
            Map<String, Object> payload,
            TurnSecurityState state
    ) {
        return new ToolOutputCheckResult(AgentSecurityVerdict.allow(), payload);
    }

    default OutboundCheckResult guardOutboundResponse(String narrative, TurnSecurityState state) {
        return new OutboundCheckResult(AgentSecurityVerdict.allow(), narrative);
    }

    record InboundCheckResult(
            AgentSecurityVerdict verdict,
            String sanitizedUserMessage,
            AgentExecutionContext sanitizedContext
    ) { }

    record ToolOutputCheckResult(
            AgentSecurityVerdict verdict,
            Map<String, Object> sanitizedPayload
    ) { }

    record OutboundCheckResult(
            AgentSecurityVerdict verdict,
            String sanitizedNarrative
    ) { }

    record HistoryCheckResult(
            AgentSecurityVerdict verdict,
            String sanitizedText
    ) { }
}
