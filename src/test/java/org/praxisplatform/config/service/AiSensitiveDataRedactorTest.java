package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AiSensitiveDataRedactorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiSensitiveDataRedactor redactor = new AiSensitiveDataRedactor();

    @Test
    void shouldRedactPiiInFreeTextEventFields() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", "Contato: alice@example.com token=abc123");
        payload.putObject("error").put("details", "cpf 123456789012");

        var sanitized = redactor.sanitizeEventPayload(payload);

        assertThat(sanitized.path("message").asText()).contains("[REDACTED]");
        assertThat(sanitized.path("error").path("details").asText()).contains("[REDACTED]");
    }

    @Test
    void shouldKeepFunctionalPatchFieldsUntouched() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode response = payload.putObject("response");
        ObjectNode patch = response.putObject("patch");
        patch.put("title", "cliente@example.com");

        var sanitized = redactor.sanitizeEventPayload(payload);

        assertThat(sanitized.path("response").path("patch").path("title").asText()).isEqualTo("cliente@example.com");
    }

    @Test
    void shouldPreserveAllowlistedQuickReplyContextHintsForClientActions() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode quickReply = payload.putArray("quickReplies").addObject();
        quickReply.put("id", "resource-api-human-resources-vw-ranking-reputacao");
        quickReply.put("label", "ranking reputacao");
        quickReply.put("prompt", "Usar ranking reputacao como fonte de dados.");
        ObjectNode contextHints = quickReply.putObject("contextHints");
        contextHints.put("resourcePath", "/api/human-resources/vw-ranking-reputacao");
        contextHints.put("submitUrl", "/api/human-resources/vw-ranking-reputacao/filter/cursor");
        contextHints.put("operation", "post");
        contextHints.put("source", "component-capability-catalog");
        contextHints.put("kind", "contextual-preview-action");
        contextHints.put("operationKind", "modify");
        contextHints.put("artifactKind", "chart");
        contextHints.put("changeKind", "enable_chart_drilldown");
        contextHints.put("capabilityId", "praxis-chart.drilldown.enable@0.1.0");
        contextHints.put("targetComponentId", "praxis-chart");
        contextHints.put("selectedComponentId", "praxis-chart");
        contextHints.put("targetWidgetKey", "department-chart");
        contextHints.put("selectedWidgetKey", "department-chart");
        contextHints.put("surfacePresentation", "modal");
        contextHints.put("surfaceActionId", "surface.open");
        contextHints.put("surfaceWidgetId", "praxis-table");
        contextHints.put("token", "secret-token");
        contextHints.putObject("previewPage").putArray("widgets").addObject().put("key", "secret-widget");
        contextHints.putObject("targetWidgetSnapshot").put("key", "department-chart");

        var sanitized = redactor.sanitizeEventPayload(payload);

        assertThat(sanitized.path("quickReplies").path(0).path("prompt").asText())
                .isEqualTo("Usar ranking reputacao como fonte de dados.");
        JsonNode sanitizedHints = sanitized.path("quickReplies").path(0).path("contextHints");
        assertThat(sanitizedHints.path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao");
        assertThat(sanitizedHints.path("submitUrl").asText())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao/filter/cursor");
        assertThat(sanitizedHints.path("operation").asText()).isEqualTo("post");
        assertThat(sanitizedHints.path("source").asText()).isEqualTo("component-capability-catalog");
        assertThat(sanitizedHints.path("kind").asText()).isEqualTo("contextual-preview-action");
        assertThat(sanitizedHints.path("operationKind").asText()).isEqualTo("modify");
        assertThat(sanitizedHints.path("artifactKind").asText()).isEqualTo("chart");
        assertThat(sanitizedHints.path("changeKind").asText()).isEqualTo("enable_chart_drilldown");
        assertThat(sanitizedHints.path("capabilityId").asText())
                .isEqualTo("praxis-chart.drilldown.enable@0.1.0");
        assertThat(sanitizedHints.path("targetComponentId").asText()).isEqualTo("praxis-chart");
        assertThat(sanitizedHints.path("selectedComponentId").asText()).isEqualTo("praxis-chart");
        assertThat(sanitizedHints.path("targetWidgetKey").asText()).isEqualTo("department-chart");
        assertThat(sanitizedHints.path("selectedWidgetKey").asText()).isEqualTo("department-chart");
        assertThat(sanitizedHints.path("surfacePresentation").asText()).isEqualTo("modal");
        assertThat(sanitizedHints.path("surfaceActionId").asText()).isEqualTo("surface.open");
        assertThat(sanitizedHints.path("surfaceWidgetId").asText()).isEqualTo("praxis-table");
        assertThat(sanitizedHints.path("token").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitizedHints.path("previewPage").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitizedHints.path("targetWidgetSnapshot").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void shouldEnforceTypeLengthGrammarAndSecretRedactionForQuickReplyHints() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode contextHints = payload.putArray("quickReplies").addObject().putObject("contextHints");
        contextHints.put("submitUrl", "/api/people?token=do-not-persist");
        contextHints.put("retrievalQuery", "Encontrar alice@example.com no catálogo");
        contextHints.put("capabilityId", "Bearer abc.def.ghi");
        contextHints.put("source", "a".repeat(1025));
        contextHints.putObject("targetComponentId").put("id", "praxis-chart");
        contextHints.put("requiresActiveSemanticDecision", true);
        contextHints.putArray("surfaceRef").add("runtime-surface:people");

        JsonNode sanitizedHints = redactor.sanitizeEventPayload(payload)
                .path("quickReplies")
                .path(0)
                .path("contextHints");

        assertThat(sanitizedHints.path("submitUrl").asText())
                .isEqualTo("/api/people?token=[REDACTED]");
        assertThat(sanitizedHints.path("retrievalQuery").asText())
                .isEqualTo("Encontrar [REDACTED] no catálogo");
        assertThat(sanitizedHints.path("capabilityId").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitizedHints.path("source").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitizedHints.path("targetComponentId").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitizedHints.path("requiresActiveSemanticDecision").asBoolean()).isTrue();
        assertThat(sanitizedHints.path("surfaceRef").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void shouldRedactNonQuickReplyContextHintsInStreamEvents() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("contextHints")
                .put("resourcePath", "/api/human-resources/vw-ranking-reputacao");

        var sanitized = redactor.sanitizeEventPayload(payload);

        assertThat(sanitized.path("contextHints").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void shouldPreservePromptFieldsWhileRedactingSecretsInStreamEvents() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("userPrompt", "Criar dashboard token=abc123");
        payload.put("effectivePrompt", "Criar dashboard para folha");

        var sanitized = redactor.sanitizeEventPayload(payload);

        assertThat(sanitized.path("userPrompt").asText()).isEqualTo("Criar dashboard token=[REDACTED]");
        assertThat(sanitized.path("effectivePrompt").asText()).isEqualTo("Criar dashboard para folha");
    }

    @Test
    void shouldPreserveOnlyCanonicalNumericTokenCountersInStreamEvents() {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode telemetry = payload.putObject("providerTelemetry");
        telemetry.put("inputTokens", 120);
        telemetry.put("outputTokens", 18);
        telemetry.put("cacheReadInputTokens", 80);
        telemetry.putNull("cacheWriteInputTokens");
        telemetry.put("totalTokens", 138);
        telemetry.put("accessToken", "secret-access-token");
        telemetry.put("token", 123);
        telemetry.put("inputTokenCount", 120);
        telemetry.put("outputTokensText", "18");
        telemetry.put("totalTokensInvalid", -1);

        JsonNode sanitized = redactor.sanitizeEventPayload(payload).path("providerTelemetry");

        assertThat(sanitized.path("inputTokens").asInt()).isEqualTo(120);
        assertThat(sanitized.path("outputTokens").asInt()).isEqualTo(18);
        assertThat(sanitized.path("cacheReadInputTokens").asInt()).isEqualTo(80);
        assertThat(sanitized.path("cacheWriteInputTokens").isNull()).isTrue();
        assertThat(sanitized.path("totalTokens").asInt()).isEqualTo(138);
        assertThat(sanitized.path("accessToken").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.path("token").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.path("inputTokenCount").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.path("outputTokensText").asText()).isEqualTo("[REDACTED]");
        assertThat(sanitized.path("totalTokensInvalid").asText()).isEqualTo("[REDACTED]");
    }
}
