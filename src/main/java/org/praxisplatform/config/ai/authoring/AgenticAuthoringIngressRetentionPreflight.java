package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.regex.Pattern;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enforces privacy, ingress, and retention policies after resolving {@link AiPrincipalContext}
 * and strictly BEFORE {@code AiThreadService.resolveThread} ({@code buildTitle}),
 * {@code AiAssistantObservationService.captureStream}, {@code requestHash}, and {@code reserveTurn}.
 */
public final class AgenticAuthoringIngressRetentionPreflight {

    public static final String INGRESS_BLOCKED_REASON =
            "agentic-authoring-ingress-blocked-by-policy";
    public static final String ZERO_RETENTION_STORAGE_BLOCKED_REASON =
            "agentic-authoring-zero-retention-storage-blocked";
    public static final String PRINCIPAL_CONTEXT_REQUIRED_REASON =
            "agentic-authoring-principal-context-required";
    public static final String NEUTRAL_ZERO_RETENTION_TITLE =
            "Assistência operacional de preenchimento";

    private static final Pattern REDACTED_MARKER_PATTERN = Pattern.compile(
            "^\\s*(?:\\[\\s*(?:REDACTED|MASKED|OCULTO|OMITIDO|SENSITIVE|CONFIDENCIAL)\\s*\\]|<\\s*(?:REDACTED|MASKED|OCULTO|OMITIDO)\\s*>|\\*{2,}|•{2,}|#{3,}|X{4,})\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PARTIAL_MASK_PATTERN = Pattern.compile(
            "(?:\\*{2,}|•{2,})(?:[-./\\s]?(?:\\d|[A-Za-z]|\\*|•))+|(?:(?:\\d|[A-Za-z])+[-./\\s]?)+(?:\\*{2,}|•{2,})");

    public record PreflightDecision(
            boolean processingAllowed,
            boolean zeroRetention,
            boolean skipObservationCapture,
            String retentionPolicy,
            String threadTitleSeed) {
    }

    public PreflightDecision evaluateOrThrow(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        if (principalContext == null
                || !StringUtils.hasText(principalContext.tenantId())
                || !StringUtils.hasText(principalContext.userId())
                || !StringUtils.hasText(principalContext.environment())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    PRINCIPAL_CONTEXT_REQUIRED_REASON);
        }
        JsonNode hints = request == null ? null : request.contextHints();
        JsonNode ingressPolicy = hints == null ? null : hints.path("ingressPolicy");
        JsonNode privacyPolicy = hints == null ? null : hints.path("privacyPolicy");
        JsonNode runtimeValueAssistance = hints == null ? null : hints.path("runtimeValueAssistance");
        JsonNode runtimeAuthoringPolicy = hints == null ? null : hints.path("runtimeAuthoringPolicy");

        if (isProcessingBlocked(ingressPolicy)
                || isProcessingBlocked(privacyPolicy)
                || isProcessingBlocked(runtimeValueAssistance)
                || isProcessingBlocked(runtimeAuthoringPolicy)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    INGRESS_BLOCKED_REASON);
        }

        boolean zeroRetention = isZeroRetentionRequested(ingressPolicy)
                || isZeroRetentionRequested(privacyPolicy)
                || isZeroRetentionRequested(runtimeValueAssistance);

        if (zeroRetention && isDurableStorageDisallowed(ingressPolicy, privacyPolicy, runtimeValueAssistance)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ZERO_RETENTION_STORAGE_BLOCKED_REASON);
        }

        String rawPrompt = request == null ? "" : String.valueOf(request.userPrompt() == null ? "" : request.userPrompt());
        return new PreflightDecision(
                true,
                zeroRetention,
                zeroRetention,
                zeroRetention ? "zero" : "standard",
                zeroRetention ? NEUTRAL_ZERO_RETENTION_TITLE : rawPrompt);
    }

    public static boolean isMaskedOrRedactedValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isTextual()) {
            return isMaskedOrRedactedText(node.asText(""));
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (isMaskedOrRedactedValue(item)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            var elements = node.elements();
            while (elements.hasNext()) {
                if (isMaskedOrRedactedValue(elements.next())) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    public static boolean isMaskedOrRedactedText(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String trimmed = value.trim();
        return REDACTED_MARKER_PATTERN.matcher(trimmed).matches()
                || PARTIAL_MASK_PATTERN.matcher(trimmed).find();
    }

    private boolean isProcessingBlocked(JsonNode policyNode) {
        if (policyNode == null || !policyNode.isObject()) {
            return false;
        }
        if (policyNode.path("processingAllowed").isBoolean()
                && !policyNode.path("processingAllowed").asBoolean()) {
            return true;
        }
        if (policyNode.path("allowProcessing").isBoolean()
                && !policyNode.path("allowProcessing").asBoolean()) {
            return true;
        }
        String processingPolicy = normalize(policyNode.path("processingPolicy").asText(""));
        if ("deny".equals(processingPolicy)
                || "denied".equals(processingPolicy)
                || "blocked".equals(processingPolicy)
                || "forbid".equals(processingPolicy)) {
            return true;
        }
        String aiUsagePolicy = normalize(policyNode.path("aiUsagePolicy").asText(""));
        if ("denied".equals(aiUsagePolicy)
                || "deny".equals(aiUsagePolicy)
                || "never".equals(aiUsagePolicy)
                || "blocked".equals(aiUsagePolicy)) {
            return true;
        }
        String visibility = normalize(policyNode.path("aiUsage").path("visibility").asText(""));
        return "deny".equals(visibility) || "never".equals(visibility);
    }

    private boolean isZeroRetentionRequested(JsonNode policyNode) {
        if (policyNode == null || !policyNode.isObject()) {
            return false;
        }
        String retention = normalize(policyNode.path("retentionPolicy").asText(""));
        if ("zero".equals(retention)
                || "none".equals(retention)
                || "ephemeral".equals(retention)
                || "no-store".equals(retention)) {
            return true;
        }
        if (policyNode.path("zeroRetention").isBoolean() && policyNode.path("zeroRetention").asBoolean()) {
            return true;
        }
        if (policyNode.path("ephemeral").isBoolean() && policyNode.path("ephemeral").asBoolean()) {
            return true;
        }
        return policyNode.path("persistPrompt").isBoolean() && !policyNode.path("persistPrompt").asBoolean();
    }

    private boolean isDurableStorageDisallowed(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || !node.isObject()) {
                continue;
            }
            if (node.path("allowDurableThreadStorage").isBoolean()
                    && !node.path("allowDurableThreadStorage").asBoolean()) {
                return true;
            }
            if (node.path("blockDurableStorageOnZeroRetention").isBoolean()
                    && node.path("blockDurableStorageOnZeroRetention").asBoolean()) {
                return true;
            }
            String mode = normalize(node.path("zeroRetentionMode").asText(""));
            if ("deny-durable-stream".equals(mode) || "strict-block".equals(mode)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
