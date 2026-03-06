package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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
}
