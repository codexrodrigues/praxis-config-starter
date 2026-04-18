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
        contextHints.put("token", "secret-token");

        var sanitized = redactor.sanitizeEventPayload(payload);

        assertThat(sanitized.path("quickReplies").path(0).path("prompt").asText())
                .isEqualTo("Usar ranking reputacao como fonte de dados.");
        JsonNode sanitizedHints = sanitized.path("quickReplies").path(0).path("contextHints");
        assertThat(sanitizedHints.path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao");
        assertThat(sanitizedHints.path("submitUrl").asText())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao/filter/cursor");
        assertThat(sanitizedHints.path("operation").asText()).isEqualTo("post");
        assertThat(sanitizedHints.path("token").asText()).isEqualTo("[REDACTED]");
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
}
