package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("unit")
class AiOrchestratorServiceClarificationUiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesExplicitClarificationUi() throws Exception {
        String payload = """
            {
              "message": "Confirma?",
              "options": ["Sim", "Não"],
              "clarification": {
                "responseType": "confirm",
                "selectionMode": "single",
                "presentation": "buttons",
                "allowCustom": false
              }
            }
            """;
        JsonNode node = mapper.readTree(payload);
        AiOrchestratorResponse response = invokeParseClarification(node);

        assertThat(response).isNotNull();
        assertThat(response.getClarification()).isNotNull();
        assertThat(response.getClarification().getResponseType()).isEqualTo("confirm");
        assertThat(response.getClarification().getSelectionMode()).isEqualTo("single");
        assertThat(response.getClarification().getPresentation()).isEqualTo("buttons");
        assertThat(response.getClarification().getAllowCustom()).isFalse();
        assertThat(response.getOptions()).containsExactly("Sim", "Não");
    }

    @Test
    void normalizesChoiceToDisallowCustom() throws Exception {
        String payload = """
            {
              "message": "Escolha a coluna",
              "options": ["status", "createdAt"],
              "clarification": {
                "responseType": "choice",
                "selectionMode": "single",
                "presentation": "chips"
              }
            }
            """;
        JsonNode node = mapper.readTree(payload);
        AiOrchestratorResponse response = invokeParseClarification(node);

        assertThat(response).isNotNull();
        assertThat(response.getClarification().getResponseType()).isEqualTo("choice");
        assertThat(response.getClarification().getAllowCustom()).isFalse();
    }

    @Test
    void fallbackToTextWhenNoOptions() throws Exception {
        String payload = """
            {
              "message": "Informe o valor desejado",
              "clarification": {
                "responseType": "choice"
              }
            }
            """;
        JsonNode node = mapper.readTree(payload);
        AiOrchestratorResponse response = invokeParseClarification(node);

        assertThat(response).isNotNull();
        assertThat(response.getClarification().getResponseType()).isEqualTo("text");
        assertThat(response.getClarification().getAllowCustom()).isTrue();
        assertThat(response.getOptions()).isNull();
    }

    private AiOrchestratorResponse invokeParseClarification(JsonNode node) throws Exception {
        AiOrchestratorService service = new AiOrchestratorService(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                mapper,
                null,
                mock(AiThreadService.class),
                mock(AiMessageService.class));
        return (AiOrchestratorResponse) ReflectionTestUtils.invokeMethod(
                service,
                "parseClarificationFromResult",
                node);
    }
}
