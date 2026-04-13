package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.UserConfigService;

@Tag("unit")
class AgenticAuthoringApplyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserConfigService userConfigService = org.mockito.Mockito.mock(UserConfigService.class);
    private final AiApiKeyProtectionService apiKeyProtectionService = org.mockito.Mockito.mock(AiApiKeyProtectionService.class);

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
                eq("stale-etag"),
                eq("author"))).thenReturn(saved);
        when(apiKeyProtectionService.sanitizeForResponse(savedPayload)).thenReturn(savedPayload);

        AgenticAuthoringApplyResult result = service().apply(
                new AgenticAuthoringApplyRequest(compiledPatch, null, "helpdesk:notebook-screen", null, null),
                "tenant",
                "user",
                "local",
                "author",
                "\"stale-etag\"");

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
                eq("stale-etag"),
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
        assertThat(result.applied()).isTrue();
        assertThat(result.version()).isEqualTo(2L);
        assertThat(result.scope()).isEqualTo("user");
        assertThat(result.etag()).isEqualTo("00000000-0000-0000-0000-000000000123");
        assertThat(result.payload()).isEqualTo(savedPayload);
    }

    @Test
    void applyRejectsPatchWithoutRenderablePagePayload() {
        ObjectNode invalid = objectMapper.createObjectNode();
        invalid.putObject("patch");

        assertThatThrownBy(() -> service().apply(
                new AgenticAuthoringApplyRequest(invalid, "praxis-dynamic-page", "page", "tenant", null),
                "tenant",
                null,
                null,
                "author",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compiledFormPatch.patch.page");
    }

    private AgenticAuthoringApplyService service() {
        return new AgenticAuthoringApplyService(userConfigService, apiKeyProtectionService, objectMapper);
    }

    private JsonNode compiledPatch() throws Exception {
        return objectMapper.readTree("""
                {
                  "profileId": "create-minimal-form",
                  "catalogReleaseId": "catalog-release-test",
                  "builderVersion": "0.1.0",
                  "patch": {
                    "page": {
                      "canvas": {"layout": "single-column"},
                      "widgets": [
                        {
                          "id": "ticket-form",
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
}
