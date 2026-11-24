package org.praxisplatform.config.ai.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.http.Headers;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
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

    @Test
    void appliesReasoningEffortCacheKeySelectiveStrictSchemaAndOpaqueReasoningReplay() {
        List<ResponseCreateParams> capturedParams = new ArrayList<>();
        AtomicInteger callSeq = new AtomicInteger();

        com.openai.models.responses.ResponseReasoningItem reasoningItem =
                com.openai.models.responses.ResponseReasoningItem.builder()
                        .id("rs_opaque_1")
                        .encryptedContent("enc_blob_xyz")
                        .summary(List.of())
                        .build();

        OpenAiResponsesAgentToolGateway nativeGateway = new OpenAiResponsesAgentToolGateway(
                new ObjectMapper(),
                "sk-test-openai",
                "",
                "gpt-5",
                (apiKey, baseUrl, timeout, params) -> {
                    capturedParams.add(params);
                    if (callSeq.getAndIncrement() == 0) {
                        return Response.builder()
                                .id("resp-r1")
                                .createdAt(1.0d)
                                .model(ResponsesModel.ofString("gpt-5"))
                                .parallelToolCalls(true)
                                .toolChoice(Response.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                                .tools(List.of())
                                .error(java.util.Optional.empty())
                                .incompleteDetails(java.util.Optional.empty())
                                .instructions(java.util.Optional.empty())
                                .metadata(java.util.Optional.empty())
                                .temperature(1.0d)
                                .topP(1.0d)
                                .output(List.of(
                                        ResponseOutputItem.ofReasoning(reasoningItem),
                                        ResponseOutputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                                                .callId("fc_r1")
                                                .name("closed_tool")
                                                .arguments("{\"query\":\"test\"}")
                                                .build())))
                                .usage(ResponseUsage.builder()
                                        .inputTokens(100L)
                                        .outputTokens(30L)
                                        .totalTokens(130L)
                                        .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                                                .cachedTokens(64L)
                                                .cacheWriteTokens(16L)
                                                .build())
                                        .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                                                .reasoningTokens(12L)
                                                .build())
                                        .build())
                                .build();
                    }
                    ResponseOutputMessage msg = ResponseOutputMessage.builder()
                            .id("msg_r2")
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                                    ResponseOutputText.builder().text("{\"answer\":\"ok\"}").annotations(List.of()).build())))
                            .build();
                    return Response.builder()
                            .id("resp-r2")
                            .createdAt(2.0d)
                            .model(ResponsesModel.ofString("gpt-5"))
                            .parallelToolCalls(true)
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

        AgentToolDeclaration closedTool = new AgentToolDeclaration(
                "closed_tool",
                "Closed schema tool",
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of("query", Map.of("type", "string")),
                        "required", List.of("query")),
                List.of("query"));
        AgentToolDeclaration openMapTool = new AgentToolDeclaration(
                "open_tool",
                "Open map schema tool",
                Map.of(
                        "type", "object",
                        "properties", Map.of("filters", Map.of("type", "object"))),
                List.of());

        Map<String, Object> responseSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", List.of("answer"));

        AiCallConfig cfg = AiCallConfig.builder()
                .provider("openai")
                .model("gpt-5")
                .thinkingProfile(org.praxisplatform.config.service.AiThinkingProfile.DEEP_ANALYSIS)
                .promptCacheKey("tenant-cache-key-1")
                .build();

        AgentModelTurnResponse first = nativeGateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(AgentConversationItem.userText("Executar")),
                        List.of(closedTool, openMapTool),
                        cfg,
                        false,
                        responseSchema),
                AgentModelStreamListener.NOOP);

        assertThat(first.usage().cacheReadInputTokens()).isEqualTo(64L);
        assertThat(first.usage().cacheWriteInputTokens()).isEqualTo(16L);
        assertThat(first.usage().thinkingTokens()).isEqualTo(12L);
        assertThat(first.usage().cacheHit()).isTrue();
        assertThat(first.providerOpaqueState()).containsKey("openaiReasoningItems");

        ResponseCreateParams params1 = capturedParams.get(0);
        assertThat(params1.promptCacheKey()).contains("tenant-cache-key-1");
        assertThat(params1.include()).contains(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
        assertThat(params1.reasoning().orElseThrow().effort())
                .contains(com.openai.models.ReasoningEffort.HIGH);
        assertThat(params1.text()).isPresent();
        var tools = params1.tools().orElseThrow();
        assertThat(tools.get(0).asFunction().strict()).contains(true);
        assertThat(tools.get(1).asFunction().strict()).contains(false);

        // Round 2 replays opaque ResponseReasoningItem alongside function call even with store(false)
        nativeGateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(
                                AgentConversationItem.userText("Executar"),
                                AgentConversationItem.assistantToolCalls(
                                        first.text(), first.toolCalls(), first.providerOpaqueState()),
                                AgentConversationItem.toolResults(List.of(
                                        new AgentToolResult("fc_r1", "closed_tool", Map.of("ok", true), false)))),
                        List.of(closedTool, openMapTool),
                        cfg),
                AgentModelStreamListener.NOOP);

        ResponseCreateParams params2 = capturedParams.get(1);
        var items2 = params2.input().orElseThrow().asResponse();
        assertThat(items2.stream().anyMatch(i -> i.isReasoning())).isTrue();

        // Non-native compatible endpoint must not receive OpenAI-only strict=true or promptCacheKey
        List<ResponseCreateParams> proxyParams = new ArrayList<>();
        OpenAiResponsesAgentToolGateway proxyGateway = new OpenAiResponsesAgentToolGateway(
                new ObjectMapper(),
                "sk-proxy",
                "https://proxy.example.internal/v1",
                "gpt-4o-mini",
                (apiKey, baseUrl, timeout, params) -> {
                    proxyParams.add(params);
                    return Response.builder()
                            .id("resp-proxy")
                            .createdAt(1.0d)
                            .model(ResponsesModel.ofString("gpt-4o-mini"))
                            .parallelToolCalls(false)
                            .toolChoice(Response.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                            .tools(List.of())
                            .error(java.util.Optional.empty())
                            .incompleteDetails(java.util.Optional.empty())
                            .instructions(java.util.Optional.empty())
                            .metadata(java.util.Optional.empty())
                            .temperature(0.2d)
                            .topP(1.0d)
                            .output(List.of())
                            .build();
                });

        proxyGateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(AgentConversationItem.userText("Hi")),
                        List.of(closedTool),
                        cfg),
                AgentModelStreamListener.NOOP);

        assertThat(proxyParams.get(0).promptCacheKey()).isEmpty();
        assertThat(proxyParams.get(0).tools().orElseThrow().get(0).asFunction().strict()).contains(false);
    }

    @Test
    void dropsUnencryptedReasoningReplayMapsOffEffortByFamilyRejectsUnsupportedStrictKeywordsAndParsesRetryAfter() {
        List<ResponseCreateParams> captured = new ArrayList<>();
        com.openai.models.responses.ResponseReasoningItem unencryptedReasoning =
                com.openai.models.responses.ResponseReasoningItem.builder()
                        .id("rs_no_enc")
                        .summary(List.of())
                        .build();

        OpenAiResponsesAgentToolGateway gateway = new OpenAiResponsesAgentToolGateway(
                new ObjectMapper(),
                "sk-test-openai",
                "",
                "gpt-5-mini",
                (apiKey, baseUrl, timeout, params) -> {
                    captured.add(params);
                    return Response.builder()
                            .id("resp-family")
                            .createdAt(1.0d)
                            .model(ResponsesModel.ofString("gpt-5-mini"))
                            .parallelToolCalls(false)
                            .toolChoice(Response.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                            .tools(List.of())
                            .error(java.util.Optional.empty())
                            .incompleteDetails(java.util.Optional.empty())
                            .instructions(java.util.Optional.empty())
                            .metadata(java.util.Optional.empty())
                            .temperature(1.0d)
                            .topP(1.0d)
                            .output(List.of())
                            .build();
                });

        AgentToolDeclaration toolWithMinimum = new AgentToolDeclaration(
                "bounded_tool",
                "Bounded schema",
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of("limit", Map.of("type", "integer", "minimum", 1)),
                        "required", List.of("limit")),
                List.of("limit"));

        gateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(
                                AgentConversationItem.userText("Test"),
                                AgentConversationItem.assistantToolCalls(
                                        "",
                                        List.of(),
                                        Map.of("openaiReasoningItems", List.of(unencryptedReasoning))),
                                AgentConversationItem.userText("Next")),
                        List.of(toolWithMinimum),
                        AiCallConfig.builder()
                                .provider("openai")
                                .model("gpt-5.1")
                                .thinkingProfile(org.praxisplatform.config.service.AiThinkingProfile.OFF)
                                .build()),
                AgentModelStreamListener.NOOP);

        gateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(AgentConversationItem.userText("Test2")),
                        List.of(),
                        AiCallConfig.builder()
                                .provider("openai")
                                .model("gpt-5-mini")
                                .thinkingProfile(org.praxisplatform.config.service.AiThinkingProfile.OFF)
                                .build()),
                AgentModelStreamListener.NOOP);

        gateway.generateTurn(
                new AgentModelTurnRequest(
                        "Rules",
                        List.of(AgentConversationItem.userText("Test3")),
                        List.of(),
                        AiCallConfig.builder()
                                .provider("openai")
                                .model("o3-mini")
                                .thinkingProfile(org.praxisplatform.config.service.AiThinkingProfile.OFF)
                                .build()),
                AgentModelStreamListener.NOOP);

        // Unencrypted reasoning item was dropped under store(false), and minimum caused strict(false)
        assertThat(captured.get(0).input().orElseThrow().asResponse().stream().noneMatch(i -> i.isReasoning())).isTrue();
        assertThat(captured.get(0).tools().orElseThrow().get(0).asFunction().strict()).contains(false);
        assertThat(captured.get(0).reasoning().orElseThrow().effort()).contains(ReasoningEffort.NONE);
        assertThat(captured.get(1).reasoning().orElseThrow().effort()).contains(ReasoningEffort.MINIMAL);
        assertThat(captured.get(2).reasoning().orElseThrow().effort()).contains(ReasoningEffort.LOW);

        Headers headers = Headers.builder().put("Retry-After", "15").build();
        assertThat(OpenAiResponsesAgentToolGateway.parseRetryAfter(headers)).isNotNull();
    }
}
