package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.UUID;
import org.praxisplatform.config.service.AiPrincipalContext;

/** Backend-attested persistence identity carried by an applicable terminal result. */
public record AgenticAuthoringApplyTarget(
        String schemaVersion,
        String componentType,
        String componentId,
        String scope,
        String environment,
        String mode,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String baseEtag) {

    public static final String SCHEMA_VERSION = "praxis-agentic-authoring-apply-target.v1";
    private static final int MAX_COMPONENT_TYPE_LENGTH = 64;
    private static final int MAX_COMPONENT_ID_LENGTH = 255;

    static Resolution resolve(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext) {
        JsonNode candidate = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("agenticApplyTarget");
        return resolveCandidate(
                candidate,
                principalContext == null ? null : blankToNull(principalContext.environment()));
    }

    private static Resolution resolveCandidate(JsonNode candidate, String environment) {
        if (candidate == null || !candidate.isObject()) {
            return Resolution.blocked("apply-target-missing");
        }
        if (!SCHEMA_VERSION.equals(text(candidate, "schemaVersion"))) {
            return Resolution.blocked("apply-target-schema-version-invalid");
        }
        String componentType = text(candidate, "componentType");
        if (componentType.isBlank()) {
            return Resolution.blocked("apply-target-component-type-missing");
        }
        String componentId = text(candidate, "componentId");
        if (componentId.isBlank()) {
            return Resolution.blocked("apply-target-component-id-missing");
        }
        if (componentType.length() > MAX_COMPONENT_TYPE_LENGTH || componentId.length() > MAX_COMPONENT_ID_LENGTH) {
            return Resolution.blocked("apply-target-component-identity-too-long");
        }
        String scope = text(candidate, "scope").toLowerCase(Locale.ROOT);
        if (!"user".equals(scope) && !"tenant".equals(scope)) {
            return Resolution.blocked("apply-target-scope-invalid");
        }
        String mode = text(candidate, "mode").toLowerCase(Locale.ROOT);
        if (!"create".equals(mode) && !"update".equals(mode)) {
            return Resolution.blocked("apply-target-mode-invalid");
        }
        if ("create".equals(mode) && candidate.has("baseEtag")) {
            return Resolution.blocked("apply-target-create-base-etag-forbidden");
        }
        String baseEtag = normalizeEtag(text(candidate, "baseEtag"));
        if ("update".equals(mode) && !validUuid(baseEtag)) {
            return Resolution.blocked("apply-target-base-etag-required");
        }
        return Resolution.resolved(new AgenticAuthoringApplyTarget(
                SCHEMA_VERSION,
                componentType,
                componentId,
                scope,
                environment,
                mode,
                baseEtag.isBlank() ? null : baseEtag));
    }

    static AgenticAuthoringApplyTarget fromTerminal(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            throw new IllegalStateException("agentic-turn-result-apply-target-missing");
        }
        Resolution resolution = resolveCandidate(candidate, blankToNull(text(candidate, "environment")));
        if (!resolution.valid()) {
            throw new IllegalStateException("agentic-turn-result-" + resolution.failureCode());
        }
        return resolution.target();
    }

    static String normalizeEtag(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.regionMatches(true, 0, "W/", 0, 2)) {
            normalized = normalized.substring(2).trim();
        }
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.trim();
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static boolean validUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record Resolution(AgenticAuthoringApplyTarget target, String failureCode) {
        static Resolution resolved(AgenticAuthoringApplyTarget target) {
            return new Resolution(target, "");
        }

        static Resolution blocked(String failureCode) {
            return new Resolution(null, failureCode);
        }

        boolean valid() {
            return target != null && (failureCode == null || failureCode.isBlank());
        }
    }
}
