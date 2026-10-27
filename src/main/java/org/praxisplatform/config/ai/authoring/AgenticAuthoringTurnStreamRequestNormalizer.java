package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Normalizes, grounds, and computes canonical idempotency SHA-256 fingerprints for
 * {@link AgenticAuthoringTurnStreamRequest} payloads.
 */
public final class AgenticAuthoringTurnStreamRequestNormalizer {

    public static final String REQUEST_FINGERPRINT_SCHEMA_VERSION =
            "praxis-agentic-authoring-turn-request-fingerprint.v2";

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringRuntimeComponentGroundingService runtimeComponentGroundingService;

    public AgenticAuthoringTurnStreamRequestNormalizer(
            ObjectMapper objectMapper,
            AgenticAuthoringRuntimeComponentGroundingService runtimeComponentGroundingService) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.runtimeComponentGroundingService = Objects.requireNonNull(
                runtimeComponentGroundingService,
                "runtimeComponentGroundingService must not be null");
    }

    public AgenticAuthoringTurnStreamRequest withActiveSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeSemanticDecision) {
        if (request == null || activeSemanticDecision == null) {
            return request;
        }
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints(),
                request.componentCapabilities(),
                activeSemanticDecision,
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public AgenticAuthoringTurnStreamRequest withPersistedResolvedCandidates(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode issuedCandidateApis) {
        if (request == null || issuedCandidateApis == null || !issuedCandidateApis.isArray() || issuedCandidateApis.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resolvedIntent = contextHints.path("resolvedIntent").isObject()
                ? (ObjectNode) contextHints.path("resolvedIntent")
                : contextHints.putObject("resolvedIntent");
        resolvedIntent.set("candidateApis", issuedCandidateApis.deepCopy());
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                contextHints,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public AgenticAuthoringTurnStreamRequest withCanonicalSessionId(
            AgenticAuthoringTurnStreamRequest request,
            UUID threadId) {
        if (request == null || threadId == null || threadId.toString().equals(request.sessionId())) {
            return request;
        }
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                threadId.toString(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints(),
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public AgenticAuthoringTurnStreamRequest withGroundedRuntimeComponentContext(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null) {
            return request;
        }
        ObjectNode groundedContext = runtimeComponentGroundingService.ground(
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
        ObjectNode contextHints = contextHintsWithoutClientGroundedRuntimeContext(request.contextHints());
        if (groundedContext != null && !groundedContext.isEmpty()) {
            contextHints.set("groundedRuntimeComponentContext", groundedContext);
        }
        if (contextHints.isEmpty() && request.contextHints() == null) {
            return request;
        }
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                contextHints,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public AgenticAuthoringTurnStreamRequest withoutClientVerifiedDomainOperations(
            AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.contextHints() == null || !request.contextHints().isObject()) {
            return request;
        }
        ObjectNode sanitized = ((ObjectNode) request.contextHints()).deepCopy();
        sanitized.remove("verifiedDomainOperations");
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                sanitized.isEmpty() ? null : sanitized,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public AgenticAuthoringTurnStreamRequest withRequestBaseUrl(
            AgenticAuthoringTurnStreamRequest request,
            String baseUrl) {
        if (request == null || baseUrl == null || baseUrl.isBlank()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        if (!contextHints.path("requestBaseUrl").asText("").isBlank()) {
            return request;
        }
        contextHints.put("requestBaseUrl", baseUrl.replaceAll("/+$", ""));
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                contextHints,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    public String requestHash(AiTurnEventEnvelope startEvent) {
        if (startEvent == null || startEvent.getPayload() == null) {
            return null;
        }
        JsonNode requestHash = startEvent.getPayload().get("requestHash");
        if (requestHash == null || requestHash.isNull()) {
            return null;
        }
        String value = requestHash.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String requestHash(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        try {
            ObjectNode fingerprint = objectMapper.createObjectNode();
            fingerprint.put("schemaVersion", REQUEST_FINGERPRINT_SCHEMA_VERSION);
            putIfPresent(
                    fingerprint,
                    "principalAuthorities",
                    principalContext == null
                            ? List.of()
                            : principalContext.authorities().stream().sorted().toList());
            putIfPresent(fingerprint, "userPrompt", request.userPrompt());
            putIfPresent(fingerprint, "targetApp", request.targetApp());
            putIfPresent(fingerprint, "targetComponentId", request.targetComponentId());
            putIfPresent(fingerprint, "currentRoute", request.currentRoute());
            putIfPresent(fingerprint, "currentPage", request.currentPage());
            putIfPresent(fingerprint, "selectedWidgetKey", request.selectedWidgetKey());
            putIfPresent(fingerprint, "provider", request.provider());
            putIfPresent(fingerprint, "model", request.model());
            putIfPresent(fingerprint, "sessionId", request.sessionId());
            putIfPresent(fingerprint, "conversationMessages", request.conversationMessages());
            putIfPresent(fingerprint, "pendingClarification", request.pendingClarification());
            putIfPresent(fingerprint, "attachmentSummaries", request.attachmentSummaries());
            putIfPresent(fingerprint, "contextHints", contextHintsForFingerprint(request.contextHints()));
            putIfPresent(fingerprint, "componentCapabilities", request.componentCapabilities());
            putIfPresent(fingerprint, "activeSemanticDecision", request.activeSemanticDecision());
            putIfPresent(fingerprint, "diagnostics", request.diagnostics());
            putIfPresent(
                    fingerprint,
                    "runtimeComponentObservationTrustBoundary",
                    request.runtimeComponentObservationTrustBoundary());
            byte[] raw = objectMapper.writeValueAsBytes(canonicalize(fingerprint));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(raw));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to hash authoring turn request.", ex);
        }
    }

    private ObjectNode contextHintsWithoutClientGroundedRuntimeContext(JsonNode contextHints) {
        ObjectNode sanitized = contextHints != null && contextHints.isObject()
                ? contextHints.deepCopy()
                : objectMapper.createObjectNode();
        sanitized.remove("groundedRuntimeComponentContext");
        return sanitized;
    }

    private JsonNode contextHintsForFingerprint(JsonNode contextHints) {
        if (contextHints == null || !contextHints.isObject()) {
            return contextHints;
        }
        ObjectNode copy = contextHints.deepCopy();
        copy.remove("requestBaseUrl");
        if (copy.isEmpty()) {
            return null;
        }
        return copy;
    }

    private void putIfPresent(ObjectNode target, String fieldName, Object value) {
        if (value == null) {
            return;
        }
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(value);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        target.set(fieldName, node);
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            Iterator<String> iterator = node.fieldNames();
            while (iterator.hasNext()) {
                fieldNames.add(iterator.next());
            }
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                canonical.set(fieldName, canonicalize(node.get(fieldName)));
            }
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                canonical.add(canonicalize(item));
            }
            return canonical;
        }
        return node;
    }
}
