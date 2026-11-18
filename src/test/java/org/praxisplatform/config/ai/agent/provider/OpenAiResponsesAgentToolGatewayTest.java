package org.praxisplatform.config.ai.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.model.AgentConversationItem;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.model.AgentToolResult;
import org.praxisplatform.config.service.AiCallConfig;

@Tag("unit")
class OpenAiResponsesAgentToolGatewayTest {

    @Test
    void mapsToolsFunctionCallsAndFunctionCallOutputsViaResponsesApi() {
        List<ResponseCreateParams> capturedParams = new ArrayList<>();
        AtomicInteger callSeq = new AtomicInteger();

        OpenAiResponsesAgentToolGateway gateway = new OpenAiResponsesAgentToolGateway(
                new ObjectMapper(),
                "sk-test-openai",
                "",
                "gpt-4o-mini",
                (apiKey, baseUrl, timeout, params) -> {
                    capturedParams.add(params);
                    if (callSeq.getAndIncrement() == 0) {
                        return Response.builder()
                                .id("resp-1")
                                .createdAt(1.0d)
                                .model(ResponsesModel.ofString("gpt-4o-mini"))
                                .parallelToolCalls(true)
                                .toolChoice(Response.ToolChoice.ofOptions(com.openai.models.responses.ToolChoiceOptions.AUTO))
                                .tools(List.of())
                                .error(java.util.Optional.empty())
                                .incompleteDetails(java.util.Optional.empty())
                                .instructions(java.util.Optional.empty())
                                .metadata(java.util.Optional.empty())
                                .temperature(0.2d)
                                .topP(1.0d)
                                .output(List.of(ResponseOutputItem.ofFunctionCall(
                                        ResponseFunctionToolCall.builder()
                                                .callId("fc_1")
                                                .name("search_and_resolve_entities")
                                                .arguments("{\"query\":\"Ana\"}")
                                                .build())))
                                .build();
                    }
                    ResponseOutputText textPart = ResponseOutputText.builder()
                            .text("Encontrei 2 candidatas chamadas Ana.")
                            .annotations(List.of())
                            .build();
                    ResponseOutputMessage msg = ResponseOutputMessage.builder()
                            .id("msg_1")
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .content(List.of(ResponseOutputMessage.Content.ofOutputText(textPart)))
                            .build();
                    return Response.builder()
                            .id("resp-2")
                            .createdAt(2.0d)
                            .model(ResponsesModel.ofString("gpt-4o-mini"))
                            .parallelToolCalls(true)
                            .toolChoice(Response.ToolChoice.ofOptions(com.openai.models.responses.ToolChoiceOptions.AUTO))
                            .tools(List.of())
                            .error(java.util.Optional.empty())
                            .incompleteDetails(java.util.Optional.empty())
                            .instructions(java.util.Optional.empty())
                            .metadata(java.util.Optional.empty())
                            .temperature(0.2d)
                            .topP(1.0d)
                            .output(List.of(ResponseOutputItem.ofMessage(msg)))
                            .usage(ResponseUsage.builder()
                                    .inputTokens(80L)
                                    .outputTokens(25L)
                                    .totalTokens(105L)
                                    .inputTokensDetails(ResponseUsage.InputTokensDetails.builder().cachedTokens(0L).cacheWriteTokens(0L).build())
                                    .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder().reasoningTokens(0L).build())
                                    .build())
                            .build();
                });

        AgentToolDeclaration decl = new AgentToolDeclaration(
                "search_and_resolve_entities",
                "Busca entidades",
                Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string"))),
                List.of("query"));

        AiCallConfig cfg = AiCallConfig.builder().provider("openai").model("gpt-4o-mini").build();
        AgentModelTurnResponse first = gateway.generateTurn(
                new AgentModelTurnRequest(
                        "System rules",
                        List.of(AgentConversationItem.userText("Ana")),
                        List.of(decl),
                        cfg),
                AgentModelStreamListener.NOOP);

        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.toolCalls().get(0).callId()).isEqualTo("fc_1");
        assertThat(first.toolCalls().get(0).arguments()).containsEntry("query", "Ana");
        assertThat(first.providerOpaqueState()).containsEntry("previousResponseId", "resp-1");
        assertThat(capturedParams.get(0).tools()).isPresent();

        AgentModelTurnResponse second = gateway.generateTurn(
                new AgentModelTurnRequest(
                        "System rules",
                        List.of(
                                AgentConversationItem.userText("Ana"),
                                AgentConversationItem.assistantToolCalls(first.text(), first.toolCalls(), first.providerOpaqueState()),
                                AgentConversationItem.toolResults(List.of(
                                        new AgentToolResult("fc_1", "search_and_resolve_entities", Map.of("matchCount", 2), false)))),
                        List.of(decl),
                        cfg),
                AgentModelStreamListener.NOOP);

        assertThat(second.hasToolCalls()).isFalse();
        assertThat(second.text()).isEqualTo("Encontrei 2 candidatas chamadas Ana.");
        assertThat(second.usage().totalTokens()).isEqualTo(105L);
        assertThat(capturedParams.get(0).store()).contains(false);
        assertThat(capturedParams.get(1).store()).contains(false);
        assertThat(capturedParams.get(1).previousResponseId()).isEmpty();
        assertThat(capturedParams.get(1).input().orElseThrow().asResponse()).hasSize(3);

        gateway.generateTurn(
                new AgentModelTurnRequest(
                        "System rules",
                        List.of(
                                AgentConversationItem.userText("Ana"),
                                AgentConversationItem.assistantToolCalls(first.text(), first.toolCalls(), Map.of()),
                                AgentConversationItem.toolResults(List.of(
                                        new AgentToolResult("fc_1", "search_and_resolve_entities", Map.of("matchCount", 2), false)))),
                        List.of(decl),
                        cfg,
                        true),
                AgentModelStreamListener.NOOP);

        assertThat(capturedParams.get(2).previousResponseId()).isEmpty();
        assertThat(capturedParams.get(2).input().orElseThrow().asResponse()).hasSize(3);
        assertThat(capturedParams.get(2).toolChoice())
                .isPresent()
                .get()
                .satisfies(tc -> assertThat(tc.asOptions()).isEqualTo(ToolChoiceOptions.NONE));
    }

    @Test
    void normalizesBaseUrlAndOmitsTemperatureForReasoningModels() {
        AtomicReference<String> capturedBaseUrl = new AtomicReference<>();
        AtomicReference<ResponseCreateParams> capturedParams = new AtomicReference<>();

        OpenAiResponsesAgentToolGateway gateway = new OpenAiResponsesAgentToolGateway(
                new ObjectMapper(),
                "sk-test-openai",
                "https://api.openai.com",
                "o3-mini",
                (apiKey, baseUrl, timeout, params) -> {
                    capturedBaseUrl.set(baseUrl);
                    capturedParams.set(params);
                    ResponseOutputText textPart = ResponseOutputText.builder()
                            .text("OK")
                            .annotations(List.of())
                            .build();
                    ResponseOutputMessage msg = ResponseOutputMessage.builder()
                            .id("msg_reasoning")
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .content(List.of(ResponseOutputMessage.Content.ofOutputText(textPart)))
                            .build();
                    return Response.builder()
                            .id("resp-reasoning")
                            .createdAt(1.0d)
                            .model(ResponsesModel.ofString("o3-mini"))
                            .parallelToolCalls(false)
                            .toolChoice(Response.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                            .tools(List.of())
                            .error(java.util.Optional.empty())
                            .incompleteDetails(java.util.Optional.empty())
                            .instructions(java.util.Optional.empty())
                            .metadata(java.util.Optional.empty())
                            .temperature(1.0d)
                            .topP(1.0d)
                            .output(List.of(ResponseOutputItem.ofMessage(msg)))
                            .build();
                });

        gateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(AgentConversationItem.userText("Teste")),
                        List.of(),
                        AiCallConfig.builder().provider("openai").model("gpt-5").temperature(0.2d).build()),
                AgentModelStreamListener.NOOP);

        assertThat(capturedBaseUrl.get()).isEqualTo("https://api.openai.com/v1");
        assertThat(capturedParams.get().store()).contains(false);
        assertThat(capturedParams.get().temperature()).isEmpty();
    }
}
