package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.UserConfigService;

public class AgenticAuthoringApplyService {

    private static final String DEFAULT_COMPONENT_TYPE = "praxis-dynamic-page";

    private final UserConfigService userConfigService;
    private final AiApiKeyProtectionService apiKeyProtectionService;
    private final AiTurnEventService turnEventService;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringApplyService(
            UserConfigService userConfigService,
            AiApiKeyProtectionService apiKeyProtectionService,
            AiTurnEventService turnEventService,
            ObjectMapper objectMapper) {
        this.userConfigService = userConfigService;
        this.apiKeyProtectionService = apiKeyProtectionService;
        this.turnEventService = turnEventService;
        this.objectMapper = objectMapper;
    }

    public AgenticAuthoringApplyResult apply(
            AgenticAuthoringApplyRequest request,
            AiPrincipalContext principalContext,
            String updatedBy,
            String ifMatch) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (principalContext == null
                || principalContext.tenantId() == null
                || principalContext.userId() == null) {
            throw new IllegalArgumentException("AI principal context is required");
        }

        String componentType = defaultIfBlank(request.componentType(), DEFAULT_COMPONENT_TYPE);
        String componentId = requireText(request.componentId(), "componentId is required");
        UserConfigService.Scope scope = resolveScope(request.scope(), principalContext.userId());
        JsonNode payload = extractPagePayload(request.compiledFormPatch());
        if (request.semanticDecision() == null) {
            validateSemanticMaterialization(null, request.compiledFormPatch(), request.compiledFormPatch());
        }
        AiTurnEventService.StreamOwnership ownership = turnEventService.requireOwnership(
                requireUuid(request.streamId(), "streamId is required"),
                principalContext);
        AiTurnEventEnvelope terminalResult = requireTerminalResult(request, ownership);
        AgenticAuthoringApplyTarget applyTarget = validateTerminalMaterialization(
                request,
                terminalResult,
                principalContext,
                componentType,
                componentId,
                scope,
                ifMatch);
        validateSemanticMaterialization(
                request.semanticDecision(),
                request.compiledFormPatch(),
                terminalSemanticEvidence(terminalResult, request.compiledFormPatch()));
        JsonNode tags = buildTags(request, terminalResult);

        UiUserConfig saved = "create".equals(applyTarget.mode())
                ? userConfigService.create(
                        scope,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        componentType,
                        componentId,
                        principalContext.environment(),
                        payload,
                        tags,
                        updatedBy)
                : userConfigService.upsert(
                        scope,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        componentType,
                        componentId,
                        principalContext.environment(),
                        payload,
                        tags,
                        ifMatch,
                        updatedBy);

        String etag = saved.getEtag() != null ? saved.getEtag().toString() : null;
        JsonNode savedPayload = apiKeyProtectionService.sanitizeForResponse(readJson(saved.getPayload()));
        JsonNode savedTags = readJson(saved.getTags());

        return new AgenticAuthoringApplyResult(
                true,
                componentType,
                componentId,
                principalContext.environment(),
                scope.name().toLowerCase(),
                saved.getVersion(),
                etag,
                savedPayload,
                savedTags,
                List.of(
                        "persisted-page-payload-from-compiled-form-patch",
                        "verified-agentic-turn-result-lineage"));
    }

    private AiTurnEventEnvelope requireTerminalResult(
            AgenticAuthoringApplyRequest request,
            AiTurnEventService.StreamOwnership ownership) {
        UUID resultEventId = requireUuid(request.resultEventId(), "resultEventId is required");
        AiTurnEventEnvelope terminal = turnEventService.findLastEvent(ownership.streamId())
                .orElseThrow(() -> new IllegalStateException("agentic-turn-terminal-result-not-found"));
        if (!"result".equalsIgnoreCase(terminal.getType())) {
            throw new IllegalStateException("agentic-turn-terminal-event-is-not-applicable-result");
        }
        if (!resultEventId.equals(terminal.getEventId())) {
            throw new IllegalStateException("agentic-turn-result-event-mismatch");
        }
        if (!ownership.threadId().equals(terminal.getThreadId())
                || !ownership.turnId().equals(terminal.getTurnId())) {
            throw new IllegalStateException("agentic-turn-result-lineage-mismatch");
        }
        return terminal;
    }

    private AgenticAuthoringApplyTarget validateTerminalMaterialization(
            AgenticAuthoringApplyRequest request,
            AiTurnEventEnvelope terminalResult,
            AiPrincipalContext principalContext,
            String componentType,
            String componentId,
            UserConfigService.Scope scope,
            String ifMatch) {
        JsonNode payload = terminalResult.getPayload();
        if (payload == null || !payload.isObject() || !payload.path("canApply").asBoolean(false)) {
            throw new IllegalStateException("agentic-turn-result-is-not-applicable");
        }
        JsonNode issuedPatch = payload.path("preview").path("compiledFormPatch");
        if (!issuedPatch.isObject() || !issuedPatch.equals(request.compiledFormPatch())) {
            throw new IllegalStateException("agentic-turn-result-patch-mismatch");
        }
        JsonNode issuedDecision = payload.path("intentResolution").path("semanticDecision");
        JsonNode requestedDecision = request.semanticDecision() == null
                ? null
                : objectMapper.valueToTree(request.semanticDecision());
        if (!issuedDecision.isObject() || requestedDecision == null || !issuedDecision.equals(requestedDecision)) {
            throw new IllegalStateException("agentic-turn-result-semantic-decision-mismatch");
        }
        AgenticAuthoringApplyTarget applyTarget =
                AgenticAuthoringApplyTarget.fromTerminal(payload.path("applyTarget"));
        validateApplyTarget(applyTarget, principalContext, componentType, componentId, scope, ifMatch);
        return applyTarget;
    }

    private void validateApplyTarget(
            AgenticAuthoringApplyTarget applyTarget,
            AiPrincipalContext principalContext,
            String componentType,
            String componentId,
            UserConfigService.Scope scope,
            String ifMatch) {
        if (!componentType.equals(applyTarget.componentType())
                || !componentId.equals(applyTarget.componentId())
                || !scope.name().equalsIgnoreCase(applyTarget.scope())
                || !Objects.equals(
                        blankToNull(principalContext.environment()),
                        blankToNull(applyTarget.environment()))) {
            throw new IllegalStateException("agentic-turn-result-apply-target-mismatch");
        }
        String requestedEtag = AgenticAuthoringApplyTarget.normalizeEtag(ifMatch);
        if ("create".equals(applyTarget.mode())) {
            if (!requestedEtag.isBlank()) {
                throw new IllegalStateException("agentic-turn-result-create-precondition-mismatch");
            }
            return;
        }
        if (!Objects.equals(applyTarget.baseEtag(), requestedEtag)) {
            throw new IllegalStateException("agentic-turn-result-base-etag-mismatch");
        }
    }

    private JsonNode extractPagePayload(JsonNode compiledFormPatch) {
        return AgenticAuthoringCompiledPagePatchValidator.requireApplicablePage(compiledFormPatch);
    }

    private void validateSemanticMaterialization(
            AgenticAuthoringSemanticDecision semanticDecision,
            JsonNode compiledFormPatch,
            JsonNode semanticEvidence) {
        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult result =
                AgenticAuthoringSemanticMaterializationPolicy.validate(
                        semanticDecision,
                        compiledFormPatch,
                        semanticEvidence);
        if (!result.valid()) {
            throw new IllegalArgumentException("semantic-materialization-mismatch: "
                    + String.join(",", result.failureCodes()));
        }
    }

    private JsonNode terminalSemanticEvidence(
            AiTurnEventEnvelope terminalResult,
            JsonNode compiledFormPatch) {
        JsonNode uiCompositionPlan = terminalResult.getPayload()
                .path("preview")
                .path("uiCompositionPlan");
        return uiCompositionPlan.isObject() ? uiCompositionPlan : compiledFormPatch;
    }

    private JsonNode buildTags(
            AgenticAuthoringApplyRequest request,
            AiTurnEventEnvelope terminalResult) {
        ObjectNode tags = request.tags() != null && request.tags().isObject()
                ? request.tags().deepCopy()
                : objectMapper.createObjectNode();
        tags.put("source", "agentic-authoring");
        copyText(request.compiledFormPatch(), tags, "profileId");
        copyText(request.compiledFormPatch(), tags, "catalogReleaseId");
        copyText(request.compiledFormPatch(), tags, "builderVersion");
        tags.put("authoringStreamId", terminalResult.getStreamId().toString());
        tags.put("authoringThreadId", terminalResult.getThreadId().toString());
        tags.put("authoringTurnId", terminalResult.getTurnId().toString());
        tags.put("authoringResultEventId", terminalResult.getEventId().toString());
        if (request.semanticDecision() != null && request.semanticDecision().decisionId() != null) {
            tags.put("semanticDecisionId", request.semanticDecision().decisionId());
        }
        return tags;
    }

    private void copyText(JsonNode source, ObjectNode target, String fieldName) {
        String value = source != null ? source.path(fieldName).asText(null) : null;
        if (value != null && !value.isBlank()) {
            target.put(fieldName, value);
        }
    }

    private UserConfigService.Scope resolveScope(String scopeParam, String userId) {
        if (scopeParam == null || scopeParam.isBlank()) {
            return userId != null && !userId.isBlank()
                    ? UserConfigService.Scope.USER
                    : UserConfigService.Scope.TENANT;
        }
        return switch (scopeParam.trim().toLowerCase()) {
            case "user" -> UserConfigService.Scope.USER;
            case "tenant" -> UserConfigService.Scope.TENANT;
            default -> throw new IllegalArgumentException("Invalid scope. Use user or tenant.");
        };
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private UUID requireUuid(UUID value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
