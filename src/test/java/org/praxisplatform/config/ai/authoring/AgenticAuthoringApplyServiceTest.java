package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.UserConfigService;

@Tag("unit")
class AgenticAuthoringApplyServiceTest {

    private static final UUID STREAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID TURN_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID RESULT_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");
    private static final String BASE_ETAG = "00000000-0000-0000-0000-000000000205";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserConfigService userConfigService = org.mockito.Mockito.mock(UserConfigService.class);
    private final AiApiKeyProtectionService apiKeyProtectionService = org.mockito.Mockito.mock(AiApiKeyProtectionService.class);
    private final AiTurnEventService turnEventService = org.mockito.Mockito.mock(AiTurnEventService.class);

    @Test
    void applyPersistsCompiledPageThroughCanonicalUserConfigService() throws Exception {
        JsonNode compiledPatch = compiledPatch();
        JsonNode savedPayload = compiledPatch.path("patch").path("page");
        UiUserConfig saved = UiUserConfig.builder()
                .componentType("praxis-dynamic-page")
                .componentId("helpdesk:notebook-screen")
                .environment("local")
                .payload(objectMapper.writeValueAsString(savedPayload))
                .tags("{\"source\":\"agentic-authoring\"}")
                .version(2L)
                .etag(UUID.fromString("00000000-0000-0000-0000-000000000123"))
                .build();
        when(userConfigService.upsert(
                eq(UserConfigService.Scope.USER),
                eq("tenant"),
                eq("user"),
                eq("praxis-dynamic-page"),
                eq("helpdesk:notebook-screen"),
                eq("local"),
                org.mockito.ArgumentMatchers.any(JsonNode.class),
                org.mockito.ArgumentMatchers.any(JsonNode.class),
                eq("\"" + BASE_ETAG + "\""),
                eq("author"))).thenReturn(saved);
        when(apiKeyProtectionService.sanitizeForResponse(savedPayload)).thenReturn(savedPayload);

        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                null,
                "helpdesk:notebook-screen",
                null,
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext, "update", BASE_ETAG);

        AgenticAuthoringApplyResult result = service().apply(
                request,
                principalContext,
                "author",
                "\"" + BASE_ETAG + "\"");

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> tagsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(userConfigService).upsert(
                eq(UserConfigService.Scope.USER),
                eq("tenant"),
                eq("user"),
                eq("praxis-dynamic-page"),
                eq("helpdesk:notebook-screen"),
                eq("local"),
                payloadCaptor.capture(),
                tagsCaptor.capture(),
                eq("\"" + BASE_ETAG + "\""),
                eq("author"));
        assertThat(payloadCaptor.getValue()).isEqualTo(savedPayload);
        JsonNode persistedInputs = payloadCaptor.getValue()
                .path("widgets")
                .get(0)
                .path("definition")
                .path("inputs");
        assertThat(persistedInputs.path("mode").asText()).isEqualTo("create");
        assertThat(persistedInputs.path("schemaUrl").asText()).isEqualTo(
                "/schemas/filtered?path=/api/helpdesk/chamados&operation=post&schemaType=request");
        assertThat(persistedInputs.path("submitUrl").asText()).isEqualTo("/api/helpdesk/chamados");
        assertThat(persistedInputs.path("submitMethod").asText()).isEqualTo("post");
        assertThat(persistedInputs.path("responseSchemaUrl").asText()).isEqualTo(
                "/schemas/filtered?path=/api/helpdesk/chamados&operation=post&schemaType=response");
        assertThat(persistedInputs.path("formId").asText()).isEqualTo("ticket-form-minimal");
        assertThat(persistedInputs.path("componentInstanceId").asText()).isEqualTo("ticket-form-minimal");
        assertThat(tagsCaptor.getValue().path("source").asText()).isEqualTo("agentic-authoring");
        assertThat(tagsCaptor.getValue().path("profileId").asText()).isEqualTo("create-minimal-form");
        assertThat(tagsCaptor.getValue().path("authoringStreamId").asText()).isEqualTo(STREAM_ID.toString());
        assertThat(tagsCaptor.getValue().path("authoringThreadId").asText()).isEqualTo(THREAD_ID.toString());
        assertThat(tagsCaptor.getValue().path("authoringTurnId").asText()).isEqualTo(TURN_ID.toString());
        assertThat(tagsCaptor.getValue().path("authoringResultEventId").asText()).isEqualTo(RESULT_EVENT_ID.toString());
        assertThat(tagsCaptor.getValue().path("semanticDecisionId").asText()).isEqualTo("decision-form");
        assertThat(result.applied()).isTrue();
        assertThat(result.version()).isEqualTo(2L);
        assertThat(result.scope()).isEqualTo("user");
        assertThat(result.etag()).isEqualTo("00000000-0000-0000-0000-000000000123");
        assertThat(result.payload()).isEqualTo(savedPayload);
    }

    @Test
    void semanticPolicyAcceptsCanonicalCompiledPageForPageBuilderDecision() throws Exception {
        ObjectNode compiledPatch = (ObjectNode) compiledPatch();
        ObjectNode page = (ObjectNode) compiledPatch.path("patch").path("page");
        page.put("layoutPreset", "resource-dashboard");
        page.putObject("canvas").put("mode", "grid");

        AgenticAuthoringSemanticMaterializationPolicy.ValidationResult result =
                AgenticAuthoringSemanticMaterializationPolicy.validate(pageBuilderSemanticDecision(), compiledPatch);

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
    }

    @Test
    void applyRejectsMissingTerminalResultReference() throws Exception {
        AgenticAuthoringApplyRequest request = new AgenticAuthoringApplyRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                null,
                validSemanticDecision());

        assertThatThrownBy(() -> service().apply(request, principal("user"), "author", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamId is required");
    }

    @Test
    void applyRejectsResultEventOutsideRequestedTerminalResult() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);
        AiTurnEventEnvelope mismatched = terminalResult(request, true);
        mismatched.setEventId(UUID.fromString("00000000-0000-0000-0000-000000000299"));
        when(turnEventService.findLastEvent(STREAM_ID)).thenReturn(Optional.of(mismatched));

        assertThatThrownBy(() -> service().apply(request, principalContext, "author", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-event-mismatch");
    }

    @Test
    void applyRejectsPatchDifferentFromTerminalPreview() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);
        AiTurnEventEnvelope terminal = terminalResult(request, true);
        ((ObjectNode) terminal.getPayload().path("preview"))
                .set("compiledFormPatch", objectMapper.createObjectNode().put("forged", true));
        when(turnEventService.findLastEvent(STREAM_ID)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service().apply(request, principalContext, "author", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-patch-mismatch");
    }

    @Test
    void applyRejectsTerminalResultWithoutApplyAuthorization() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);
        when(turnEventService.findLastEvent(STREAM_ID))
                .thenReturn(Optional.of(terminalResult(request, false)));

        assertThatThrownBy(() -> service().apply(request, principalContext, "author", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-is-not-applicable");
    }

    @Test
    void applyRejectsTerminalResultBoundToAnotherComponent() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);
        AiTurnEventEnvelope terminal = terminalResult(request, true);
        ((ObjectNode) terminal.getPayload().path("applyTarget")).put("componentId", "another-page");
        when(turnEventService.findLastEvent(STREAM_ID)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service().apply(request, principalContext, "author", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-apply-target-mismatch");
    }

    @Test
    void applyRejectsTerminalResultBoundToAnotherScope() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);
        AiTurnEventEnvelope terminal = terminalResult(request, true);
        ((ObjectNode) terminal.getPayload().path("applyTarget")).put("scope", "tenant");
        when(turnEventService.findLastEvent(STREAM_ID)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service().apply(request, principalContext, "author", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-apply-target-mismatch");
    }

    @Test
    void applyRejectsIfMatchDifferentFromTerminalBaseEtag() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext, "update", BASE_ETAG);

        assertThatThrownBy(() -> service().apply(
                request,
                principalContext,
                "author",
                "\"00000000-0000-0000-0000-000000000299\""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-base-etag-mismatch");
    }

    @Test
    void applyRejectsSemanticDecisionDifferentFromTerminalResult() throws Exception {
        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch(),
                "praxis-dynamic-page",
                "page",
                "user",
                validSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);
        AiTurnEventEnvelope terminal = terminalResult(request, true);
        ((ObjectNode) terminal.getPayload().path("intentResolution"))
                .set("semanticDecision", objectMapper.valueToTree(chartSemanticDecision()));
        when(turnEventService.findLastEvent(STREAM_ID)).thenReturn(Optional.of(terminal));

        assertThatThrownBy(() -> service().apply(request, principalContext, "author", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentic-turn-result-semantic-decision-mismatch");
    }

    @Test
    void applyRejectsPatchWithoutRenderablePagePayload() {
        ObjectNode invalid = objectMapper.createObjectNode();
        invalid.putObject("patch");

        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(
                        invalid,
                        "praxis-dynamic-page",
                        "page",
                        "tenant",
                        null,
                        validSemanticDecision()),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiledFormPatch.patch.page");
    }

    @Test
    void applyRejectsIncompleteCanonicalCanvas() throws Exception {
        ObjectNode invalid = (ObjectNode) compiledPatch();
        ((ObjectNode) invalid.path("patch").path("page")).putObject("canvas");

        assertThatThrownBy(() -> service().apply(
                applicableRequest(
                        invalid,
                        "praxis-dynamic-page",
                        "page",
                        "user",
                        validSemanticDecision()),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiled-page-canvas-mode-invalid");
    }

    @Test
    void applyRejectsMissingSemanticDecision() throws Exception {
        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(
                        compiledPatch(),
                        "praxis-dynamic-page",
                        "page",
                        "tenant",
                        null,
                        null),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic-materialization-mismatch")
                .hasMessageContaining("semantic-decision-required");
    }

    @Test
    void applyRejectsMaterializationThatDoesNotSatisfySemanticDecision() throws Exception {
        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(
                        compiledPatch(),
                        "praxis-dynamic-page",
                        "page",
                        "tenant",
                        null,
                        chartSemanticDecision()),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic-materialization-mismatch")
                .hasMessageContaining("semantic-preview-chart-required");
    }

    @Test
    void applyRejectsMaterializationBoundToDifferentResourceThanSemanticDecision() throws Exception {
        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(
                        compiledPatch(),
                        "praxis-dynamic-page",
                        "page",
                        "tenant",
                        null,
                        semanticDecisionForResource("/api/helpdesk/clients")),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic-materialization-mismatch")
                .hasMessageContaining("semantic-preview-resource-binding-mismatch");
    }

    @Test
    void applyRejectsSemanticDecisionThatRequiresReview() throws Exception {
        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(
                        compiledPatch(),
                        "praxis-dynamic-page",
                        "page",
                        "tenant",
                        null,
                        reviewRequiredSemanticDecision()),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic-materialization-mismatch")
                .hasMessageContaining("semantic-decision-review-required:resource-selection-domain-anchor");
    }

    @Test
    void applyAllowsWeakLexicalReviewOnlyWhenMaterializationIsSchemaGrounded() throws Exception {
        JsonNode compiledPatch = compiledPatchWithSchemaGrounding();
        JsonNode savedPayload = compiledPatch.path("patch").path("page");
        UiUserConfig saved = UiUserConfig.builder()
                .componentType("praxis-dynamic-page")
                .componentId("page")
                .environment("local")
                .payload(objectMapper.writeValueAsString(savedPayload))
                .tags("{\"source\":\"agentic-authoring\"}")
                .version(3L)
                .etag(UUID.fromString("00000000-0000-0000-0000-000000000456"))
                .build();
        when(userConfigService.create(
                eq(UserConfigService.Scope.TENANT),
                eq("tenant"),
                eq("user"),
                eq("praxis-dynamic-page"),
                eq("page"),
                eq("local"),
                org.mockito.ArgumentMatchers.any(JsonNode.class),
                org.mockito.ArgumentMatchers.any(JsonNode.class),
                eq("author"))).thenReturn(saved);
        when(apiKeyProtectionService.sanitizeForResponse(savedPayload)).thenReturn(savedPayload);

        AgenticAuthoringApplyRequest request = applicableRequest(
                compiledPatch,
                "praxis-dynamic-page",
                "page",
                "tenant",
                weakLexicalReviewSemanticDecision());
        AiPrincipalContext principalContext = principal("user");
        authorize(request, principalContext);

        AgenticAuthoringApplyResult result = service().apply(
                request,
                principalContext,
                "author",
                null);

        assertThat(result.applied()).isTrue();
        assertThat(result.version()).isEqualTo(3L);
    }

    @Test
    void applyRejectsWeakLexicalReviewWhenMaterializationIsNotSchemaGrounded() throws Exception {
        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(
                        compiledPatch(),
                        "praxis-dynamic-page",
                        "page",
                        "tenant",
                        null,
                        weakLexicalReviewSemanticDecision()),
                principal("user"),
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic-materialization-mismatch")
                .hasMessageContaining("semantic-decision-review-required:weak-lexical-evidence");
    }

    private AgenticAuthoringApplyService service() {
        return new AgenticAuthoringApplyService(
                userConfigService,
                apiKeyProtectionService,
                turnEventService,
                objectMapper);
    }

    private AgenticAuthoringApplyRequest applicableRequest(
            JsonNode compiledFormPatch,
            String componentType,
            String componentId,
            String scope,
            AgenticAuthoringSemanticDecision semanticDecision) {
        return new AgenticAuthoringApplyRequest(
                compiledFormPatch,
                componentType,
                componentId,
                scope,
                null,
                semanticDecision,
                STREAM_ID,
                RESULT_EVENT_ID);
    }

    private AiPrincipalContext principal(String userId) {
        return new AiPrincipalContext("tenant", userId, "local", true);
    }

    private void authorize(
            AgenticAuthoringApplyRequest request,
            AiPrincipalContext principalContext) {
        authorize(request, principalContext, "create", null);
    }

    private void authorize(
            AgenticAuthoringApplyRequest request,
            AiPrincipalContext principalContext,
            String mode,
            String baseEtag) {
        when(turnEventService.requireOwnership(STREAM_ID, principalContext))
                .thenReturn(new AiTurnEventService.StreamOwnership(
                        STREAM_ID,
                        THREAD_ID,
                        TURN_ID,
                        principalContext.tenantId(),
                        principalContext.userId(),
                        principalContext.environment(),
                        java.time.Instant.now().plusSeconds(900)));
        when(turnEventService.findLastEvent(STREAM_ID))
                .thenReturn(Optional.of(terminalResult(request, true, mode, baseEtag)));
    }

    private AiTurnEventEnvelope terminalResult(
            AgenticAuthoringApplyRequest request,
            boolean canApply) {
        return terminalResult(request, canApply, "create", null);
    }

    private AiTurnEventEnvelope terminalResult(
            AgenticAuthoringApplyRequest request,
            boolean canApply,
            String mode,
            String baseEtag) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canApply", canApply);
        payload.putObject("preview").set("compiledFormPatch", request.compiledFormPatch());
        payload.putObject("intentResolution")
                .set("semanticDecision", objectMapper.valueToTree(request.semanticDecision()));
        ObjectNode applyTarget = payload.putObject("applyTarget");
        applyTarget.put("schemaVersion", AgenticAuthoringApplyTarget.SCHEMA_VERSION);
        applyTarget.put("componentType", request.componentType() == null
                ? "praxis-dynamic-page"
                : request.componentType());
        applyTarget.put("componentId", request.componentId());
        applyTarget.put("scope", request.scope() == null ? "user" : request.scope());
        applyTarget.put("environment", "local");
        applyTarget.put("mode", mode);
        if (baseEtag != null) {
            applyTarget.put("baseEtag", baseEtag);
        }
        return AiTurnEventEnvelope.builder()
                .eventId(RESULT_EVENT_ID)
                .streamId(STREAM_ID)
                .threadId(THREAD_ID)
                .turnId(TURN_ID)
                .seq(7L)
                .type("result")
                .payload(payload)
                .build();
    }

    private JsonNode compiledPatch() throws Exception {
        return objectMapper.readTree("""
                {
                  "profileId": "create-minimal-form",
                  "catalogReleaseId": "catalog-release-test",
                    "builderVersion": "0.1.0",
                    "patch": {
                      "page": {
                      "widgets": [
                        {
                          "key": "ticket-form",
                          "definition": {
                            "id": "praxis-dynamic-form",
                            "inputs": {
                              "mode": "create",
                              "schemaUrl": "/schemas/filtered?path=/api/helpdesk/chamados&operation=post&schemaType=request",
                              "submitUrl": "/api/helpdesk/chamados",
                              "submitMethod": "post",
                              "responseSchemaUrl": "/schemas/filtered?path=/api/helpdesk/chamados&operation=post&schemaType=response",
                              "formId": "ticket-form-minimal",
                              "componentInstanceId": "ticket-form-minimal"
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """);
    }

    private JsonNode compiledPatchWithSchemaGrounding() throws Exception {
        ObjectNode patch = (ObjectNode) compiledPatch();
        ObjectNode diagnostics = patch.putObject("diagnostics");
        ObjectNode grounding = diagnostics.putObject("resourceSchemaGrounding");
        grounding.put("verified", true);
        grounding.put("source", "schemas.filtered");
        grounding.put("endpointUrl", "/schemas/filtered?path=/api/helpdesk/chamados&operation=post&schemaType=response");
        grounding.put("fieldCount", 4);
        return patch;
    }

    private AgenticAuthoringSemanticDecision chartSemanticDecision() {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-chart",
                "create",
                "dashboard",
                "create_chart",
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "analytical-breakdown",
                        "dashboard",
                        "praxis-chart",
                        List.of(),
                        true,
                        true,
                        "test"),
                null,
                false,
                "",
                "",
                "previous-conversation-decision");
    }

    private AgenticAuthoringSemanticDecision validSemanticDecision() {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-form",
                "create",
                "form",
                "create_artifact",
                null,
                null,
                null,
                false,
                "",
                "",
                "");
    }

    private AgenticAuthoringSemanticDecision pageBuilderSemanticDecision() {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-page-builder",
                "create",
                "dashboard",
                "create_artifact",
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "resource-dashboard",
                        "dashboard",
                        "praxis-page-builder",
                        List.of(),
                        true,
                        true,
                        "test"),
                null,
                false,
                "",
                "",
                "");
    }

    private AgenticAuthoringSemanticDecision weakLexicalReviewSemanticDecision() {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-weak-lexical",
                "create",
                "form",
                "create_artifact",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        "/api/helpdesk/chamados",
                        "post",
                        "/schemas/filtered?path=/api/helpdesk/chamados&operation=post&schemaType=request",
                        "/api/helpdesk/chamados",
                        "POST"),
                null,
                null,
                true,
                "weak-lexical-evidence",
                "",
                "");
    }

    private AgenticAuthoringSemanticDecision semanticDecisionForResource(String resourcePath) {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-form-selected-resource",
                "create",
                "form",
                "create_artifact",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        resourcePath,
                        "post",
                        "/schemas/filtered?path=" + resourcePath + "&operation=post&schemaType=request",
                        resourcePath,
                        "POST"),
                null,
                null,
                false,
                "",
                "",
                "");
    }

    private AgenticAuthoringSemanticDecision reviewRequiredSemanticDecision() {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-review",
                "create",
                "table",
                "create_artifact",
                null,
                null,
                null,
                true,
                "resource-selection-domain-anchor",
                "",
                "");
    }
}
