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
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.UserConfigService;

public class AgenticAuthoringApplyService {

    private static final String DEFAULT_COMPONENT_TYPE = "praxis-dynamic-page";
    private static final String AUTHORING_SOURCE_SCHEMA_VERSION = "praxis.ui-authoring-source/v1";
    private static final String UI_COMPOSITION_PLAN_KIND = "praxis.ui-composition-plan";
    private static final String UI_COMPOSITION_PLAN_VERSION = "1.0";

    private final UserConfigService userConfigService;
    private final AiApiKeyProtectionService apiKeyProtectionService;
    private final AiTurnEventService turnEventService;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonHashService canonicalJsonHashService;

    public AgenticAuthoringApplyService(
            UserConfigService userConfigService,
            AiApiKeyProtectionService apiKeyProtectionService,
            AiTurnEventService turnEventService,
            ObjectMapper objectMapper,
            CanonicalJsonHashService canonicalJsonHashService) {
        this.userConfigService = userConfigService;
        this.apiKeyProtectionService = apiKeyProtectionService;
        this.turnEventService = turnEventService;
        this.objectMapper = objectMapper;
        this.canonicalJsonHashService = canonicalJsonHashService;
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
        JsonNode authoringSource = buildAuthoringSource(
                request,
                terminalResult,
                payload,
                componentType,
                componentId);

        UiUserConfig saved = "create".equals(applyTarget.mode())
                ? createConfig(
                        scope,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        componentType,
                        componentId,
                        principalContext.environment(),
                        payload,
                        authoringSource,
                        tags,
                        updatedBy)
                : upsertConfig(
                        scope,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        componentType,
                        componentId,
                        principalContext.environment(),
                        payload,
                        authoringSource,
                        tags,
                        ifMatch,
                        updatedBy);

        String etag = saved.getEtag() != null ? saved.getEtag().toString() : null;
        JsonNode savedPayload = apiKeyProtectionService.sanitizeForResponse(readJson(saved.getPayload()));
        JsonNode savedTags = readJson(saved.getTags());
        JsonNode savedAuthoringSource = apiKeyProtectionService.sanitizeForResponse(
                readJson(saved.getAuthoringSource()));

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
                savedAuthoringSource,
                authoringSource == null
                        ? List.of(
                                "persisted-page-payload-from-compiled-form-patch",
                                "verified-agentic-turn-result-lineage",
                                "ui-composition-authoring-source-not-issued")
                        : List.of(
                                "persisted-page-payload-from-compiled-form-patch",
                                "persisted-server-attested-ui-composition-authoring-source",
                                "verified-agentic-turn-result-lineage"));
    }

    private UiUserConfig createConfig(
            UserConfigService.Scope scope,
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment,
            JsonNode payload,
            JsonNode authoringSource,
            JsonNode tags,
            String updatedBy) {
        if (authoringSource == null) {
            return userConfigService.create(
                    scope,
                    tenantId,
                    userId,
                    componentType,
                    componentId,
                    environment,
                    payload,
                    tags,
                    updatedBy);
        }
        return userConfigService.createAuthored(
                scope,
                tenantId,
                userId,
                componentType,
                componentId,
                environment,
                payload,
                authoringSource,
                tags,
                updatedBy);
    }

    private UiUserConfig upsertConfig(
            UserConfigService.Scope scope,
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment,
            JsonNode payload,
            JsonNode authoringSource,
            JsonNode tags,
            String ifMatch,
            String updatedBy) {
        if (authoringSource == null) {
            return userConfigService.upsert(
                    scope,
                    tenantId,
                    userId,
                    componentType,
                    componentId,
                    environment,
                    payload,
                    tags,
                    ifMatch,
                    updatedBy);
        }
        return userConfigService.upsertAuthored(
                scope,
                tenantId,
                userId,
                componentType,
                componentId,
                environment,
                payload,
                authoringSource,
                tags,
                ifMatch,
                updatedBy);
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

    /**
     * Builds the durable authoring document exclusively from the backend-issued terminal result.
     * Diagnostics remain turn evidence and are intentionally excluded from semantic identity.
     */
    private JsonNode buildAuthoringSource(
            AgenticAuthoringApplyRequest request,
            AiTurnEventEnvelope terminalResult,
            JsonNode pagePayload,
            String componentType,
            String componentId) {
        JsonNode issuedPlan = terminalResult.getPayload()
                .path("preview")
                .path("uiCompositionPlan");
        if (issuedPlan.isMissingNode() || issuedPlan.isNull()) {
            return null;
        }
        if (!issuedPlan.isObject()
                || !UI_COMPOSITION_PLAN_KIND.equals(issuedPlan.path("kind").asText())
                || !UI_COMPOSITION_PLAN_VERSION.equals(issuedPlan.path("version").asText())) {
            throw new IllegalStateException("agentic-turn-result-ui-composition-plan-invalid");
        }
        validateIssuedPlanMaterialization(issuedPlan, request.compiledFormPatch());

        ObjectNode semanticSource = ((ObjectNode) issuedPlan).deepCopy();
        JsonNode diagnostics = semanticSource.remove("diagnostics");
        String sourceSha256 = canonicalJsonHashService.sha256(semanticSource);
        String materializationSha256 = canonicalJsonHashService.sha256(pagePayload);

        ObjectNode authoringSource = objectMapper.createObjectNode();
        authoringSource.put("schemaVersion", AUTHORING_SOURCE_SCHEMA_VERSION);
        authoringSource.put("kind", "ui-composition-plan");
        authoringSource.set("source", semanticSource);
        authoringSource.put("sourceSha256", sourceSha256);

        ObjectNode materialization = authoringSource.putObject("materialization");
        materialization.put("kind", "widget-page-definition");
        materialization.put("componentType", componentType);
        materialization.put("componentId", componentId);
        materialization.put("sha256", materializationSha256);
        copyText(request.compiledFormPatch(), materialization, "profileId");
        copyText(request.compiledFormPatch(), materialization, "catalogReleaseId");
        copyText(request.compiledFormPatch(), materialization, "builderVersion");

        ObjectNode provenance = authoringSource.putObject("provenance");
        provenance.put("streamId", terminalResult.getStreamId().toString());
        provenance.put("threadId", terminalResult.getThreadId().toString());
        provenance.put("turnId", terminalResult.getTurnId().toString());
        provenance.put("resultEventId", terminalResult.getEventId().toString());
        if (request.semanticDecision() != null && request.semanticDecision().decisionId() != null) {
            provenance.put("semanticDecisionId", request.semanticDecision().decisionId());
        }
        copyTemplateProvenance(diagnostics, provenance);
        return authoringSource;
    }

    private void validateIssuedPlanMaterialization(
            JsonNode issuedPlan,
            JsonNode issuedCompiledFormPatch) {
        AgenticAuthoringUiCompositionPlanCompiler.CompileResult compiled =
                new AgenticAuthoringUiCompositionPlanCompiler(objectMapper)
                        .compile(issuedPlan, issuedCompiledFormPatch);
        if (!compiled.valid()) {
            throw new IllegalStateException("agentic-turn-result-ui-composition-plan-invalid");
        }
        if (!compiled.compiledFormPatch().equals(issuedCompiledFormPatch)) {
            throw new IllegalStateException(
                    "agentic-turn-result-ui-composition-materialization-mismatch");
        }
    }

    private void copyTemplateProvenance(JsonNode diagnostics, ObjectNode provenance) {
        JsonNode resolution = diagnostics == null
                ? null
                : diagnostics.path("templateResolution");
        if (resolution == null || !resolution.isObject()) {
            return;
        }
        String registryKey = resolution.path("registryKey").asText("").trim();
        String configSha256 = resolution.path("configSha256").asText("").trim();
        if (registryKey.isBlank() || configSha256.isBlank()) {
            return;
        }
        ObjectNode template = provenance.putObject("templateRef");
        template.put("registryKey", registryKey);
        template.put("configSha256", configSha256);
        if (resolution.has("version") && resolution.path("version").canConvertToLong()) {
            template.put("version", resolution.path("version").asLong());
        }
        String etag = resolution.path("etag").asText("").trim();
        if (!etag.isBlank()) {
            template.put("etag", etag);
        }
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
