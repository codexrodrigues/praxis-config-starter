package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiInteractionLogger {

    private static final Logger LOG = LoggerFactory.getLogger("ai-interactions");

    private final ObjectMapper objectMapper;

    @Value("${praxis.ai.logging.enabled:true}")
    private boolean enabled;

    @Value("${praxis.ai.logging.include-prompt:true}")
    private boolean includePrompt;

    @Value("${praxis.ai.logging.include-response:true}")
    private boolean includeResponse;

    @Value("${praxis.ai.logging.include-front-response:true}")
    private boolean includeFrontResponse;

    @Value("${praxis.ai.logging.pretty-json:true}")
    private boolean prettyJson;

    @Value("${praxis.ai.logging.max-chars:20000}")
    private int maxChars;

    public void logLlmInteraction(
            AiOrchestratorRequest request,
            String callType,
            String provider,
            Integer attempt,
            String prompt,
            JsonNode responseJson,
            String responseText,
            long durationMs,
            Throwable error) {
        if (!enabled || !LOG.isInfoEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[LLM]");
        appendRequestContext(sb, request);
        appendValue(sb, "callType", callType);
        appendValue(sb, "provider", provider);
        appendValue(sb, "attempt", attempt != null ? attempt.toString() : null);
        if (durationMs >= 0) {
            appendValue(sb, "latencyMs", Long.toString(durationMs));
        }

        if (includePrompt) {
            sb.append("\nPROMPT:\n").append(truncate(prompt));
        }
        if (includeResponse) {
            sb.append("\nRESPONSE (formatted):\n")
                    .append(truncate(formatResponse(responseJson, responseText)));
        }
        if (error != null) {
            sb.append("\nERROR:\n")
                    .append(error.getClass().getSimpleName())
                    .append(": ")
                    .append(error.getMessage());
        }
        LOG.info(sb.toString());
    }

    public void logFrontendResponse(AiOrchestratorRequest request, AiOrchestratorResponse response) {
        if (!enabled || !includeFrontResponse || !LOG.isInfoEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[FRONT_RESPONSE]");
        appendRequestContext(sb, request);
        sb.append("\nRESPONSE (formatted):\n")
                .append(truncate(formatJsonSafe(objectMapper.valueToTree(response))));
        LOG.info(sb.toString());
    }

    public void logDiagnostic(AiOrchestratorRequest request, String label, JsonNode payload) {
        if (!enabled || !LOG.isInfoEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[DIAGNOSTIC]");
        appendRequestContext(sb, request);
        appendValue(sb, "label", label);
        sb.append("\nPAYLOAD (formatted):\n")
                .append(truncate(formatJsonSafe(payload)));
        LOG.info(sb.toString());
    }

    private void appendRequestContext(StringBuilder sb, AiOrchestratorRequest request) {
        appendValue(sb, "requestId", resolveRequestId());
        if (request == null) {
            return;
        }
        appendValue(sb, "componentId", request.getComponentId());
        appendValue(sb, "componentType", request.getComponentType());
        appendValue(sb, "aiMode", request.getAiMode());
        appendValue(sb, "variantId", request.getVariantId());
        appendValue(sb, "contractVersion", request.getContractVersion());
        appendValue(sb, "schemaHash", request.getSchemaHash());
        if (request.getSessionId() != null) {
            appendValue(sb, "sessionId", request.getSessionId().toString());
        }
        if (request.getClientTurnId() != null) {
            appendValue(sb, "clientTurnId", request.getClientTurnId().toString());
        }
        appendValue(sb, "mode", request.getMode());
    }

    private void appendValue(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(' ').append(key).append('=').append(value);
    }

    private String resolveRequestId() {
        String requestId = MDC.get("requestId");
        return requestId != null && !requestId.isBlank() ? requestId : "n/a";
    }

    private String formatResponse(JsonNode responseJson, String responseText) {
        if (responseJson != null) {
            return formatJsonSafe(responseJson);
        }
        if (responseText == null) {
            return "<null>";
        }
        try {
            JsonNode parsed = objectMapper.readTree(responseText);
            return formatJsonSafe(parsed);
        } catch (Exception ignore) {
            return responseText;
        }
    }

    private String formatJsonSafe(JsonNode node) {
        if (node == null) {
            return "<null>";
        }
        try {
            if (prettyJson) {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            }
            return node.toString();
        } catch (Exception e) {
            return node.toString();
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "<null>";
        }
        int limit = Math.max(0, maxChars);
        if (limit == 0 || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n... [truncated]";
    }
}
