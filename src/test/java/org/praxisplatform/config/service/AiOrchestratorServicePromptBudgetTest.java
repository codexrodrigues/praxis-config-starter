package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AiOrchestratorServicePromptBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void responseModeClassifierReceivesOnlyResponseModeContractSlice() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        AiOrchestratorService service = newService(provider);
        ObjectNode contract = largeAuthoringContract();
        when(provider.generateJson(anyString(), any(AiJsonSchema.class), nullable(AiCallConfig.class)))
                .thenReturn(objectMapper.readTree("""
                        { "kind": "consult", "preferredResponse": "info", "reason": "pergunta consultiva" }
                        """));

        String selected = ReflectionTestUtils.invokeMethod(
                service,
                "selectAuthoringResponseMode",
                "quais formatos de data voce recomenda?",
                contract,
                AiOrchestratorRequest.builder().build(),
                AiCallConfig.builder().provider("openai").build());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(provider).generateJson(promptCaptor.capture(), any(AiJsonSchema.class), nullable(AiCallConfig.class));
        String prompt = promptCaptor.getValue();
        assertThat(selected).isEqualTo("consult");
        assertThat(prompt).contains("responseModes");
        assertThat(prompt).contains("consult");
        assertThat(prompt).doesNotContain("VERY_LARGE_OPERATION_CATALOG");
        assertThat(prompt.length()).isLessThan(9000);
    }

    @Test
    void qaAnswerUsesBoundedConfigAndAuthoringContractContext() throws Exception {
        AiProvider provider = mock(AiProvider.class);
        AiOrchestratorService service = newService(provider);
        ObjectNode currentState = objectMapper.createObjectNode();
        currentState.put("columns", "x".repeat(50000));
        ObjectNode contract = largeAuthoringContract();
        when(provider.generateText(anyString(), nullable(AiCallConfig.class))).thenReturn("resposta");

        String answer = ReflectionTestUtils.invokeMethod(
                service,
                "answerQuestion",
                "como posso formatar datas?",
                "",
                currentState,
                AiOrchestratorRequest.builder().build(),
                AiCallConfig.builder().provider("openai").build(),
                contract);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(provider).generateText(promptCaptor.capture(), nullable(AiCallConfig.class));
        String prompt = promptCaptor.getValue();
        assertThat(answer).isEqualTo("resposta");
        assertThat(prompt).contains("formatPresets");
        assertThat(prompt).contains("... [truncated] ...");
        assertThat(prompt).doesNotContain("VERY_LARGE_OPERATION_CATALOG");
        assertThat(prompt.length()).isLessThan(33000);
    }

    private AiOrchestratorService newService(AiProvider provider) {
        return new AiOrchestratorService(
                null,
                provider,
                mock(AiInteractionLogger.class),
                null,
                null,
                null,
                null,
                null,
                objectMapper,
                null,
                null,
                null);
    }

    private ObjectNode largeAuthoringContract() throws Exception {
        JsonNode responseModes = objectMapper.readTree("""
                [
                  { "kind": "consult", "preferredResponse": "info" },
                  { "kind": "edit", "preferredResponse": "componentEditPlan" }
                ]
                """);
        ObjectNode contract = objectMapper.createObjectNode();
        contract.set("responseModes", responseModes);
        contract.putObject("formatPresets")
                .put("date-long", "Data por extenso")
                .put("date-month", "Mes e ano");
        contract.put("operations", "VERY_LARGE_OPERATION_CATALOG" + "x".repeat(70000));
        contract.put("examples", "y".repeat(70000));
        return contract;
    }
}
