package org.praxisplatform.config.ai.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
class GoogleGenAiAgentToolGatewayTest {

    @Test
    void disablesAutomaticFunctionCallingAndPreservesThoughtSignatureAcrossRounds() {
        byte[] expectedSignature = new byte[]{9, 8, 7, 6};
        List<List<Content>> capturedContents = new ArrayList<>();
        List<GenerateContentConfig> capturedConfigs = new ArrayList<>();
        AtomicInteger callSeq = new AtomicInteger();

        GoogleGenAiAgentToolGateway gateway = new GoogleGenAiAgentToolGateway(
                new ObjectMapper(),
                "test-gemini-key",
                "gemini-2.5-flash",
                (apiKey, model, contents, config) -> {
                    capturedContents.add(contents);
                    capturedConfigs.add(config);
                    if (callSeq.getAndIncrement() == 0) {
                        Part callPart = Part.builder()
                                .functionCall(FunctionCall.builder()
                                        .id("call-gem-1")
                                        .name("search_and_resolve_entities")
                                        .args(Map.of("query", "[TAX_ID_1]"))
                                        .build())
                                .thoughtSignature(expectedSignature)
                                .build();
                        return GenerateContentResponse.builder()
                                .candidates(List.of(Candidate.builder()
                                        .content(Content.builder().role("model").parts(List.of(callPart)).build())
                                        .build()))
                                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                                        .promptTokenCount(100)
                                        .candidatesTokenCount(20)
                                        .totalTokenCount(120)
                                        .build())
                                .build();
                    }
                    return GenerateContentResponse.builder()
                            .candidates(List.of(Candidate.builder()
                                    .content(Content.builder()
                                            .role("model")
                                            .parts(List.of(Part.fromText("Cadastro localizado.")))
                                            .build())
                                    .build()))
                            .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                                    .promptTokenCount(150)
                                    .candidatesTokenCount(30)
                                    .totalTokenCount(180)
                                    .build())
                            .build();
                });

        AgentToolDeclaration decl = new AgentToolDeclaration(
                "search_and_resolve_entities",
                "Busca entidades",
                Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", "string", "description", "Termo"))),
                List.of("query"));

        AiCallConfig cfg = AiCallConfig.builder().provider("gemini").model("gemini-2.5-flash").build();
        AgentModelTurnResponse first = gateway.generateTurn(
                new AgentModelTurnRequest(
                        "Use ferramentas",
                        List.of(AgentConversationItem.userText("Buscar [TAX_ID_1]")),
                        List.of(decl),
                        cfg),
                AgentModelStreamListener.NOOP);

        assertThat(capturedConfigs.get(0).automaticFunctionCalling())
                .isPresent()
                .get()
                .satisfies(afc -> assertThat(afc.disable()).contains(true));
        assertThat(first.hasToolCalls()).isTrue();
        assertThat(first.toolCalls().get(0).callId()).isEqualTo("call-gem-1");
        assertThat(first.toolCalls().get(0).thoughtSignature())
                .isEqualTo(Base64.getEncoder().encodeToString(expectedSignature));

        AgentModelTurnResponse second = gateway.generateTurn(
                new AgentModelTurnRequest(
                        "Use ferramentas",
                        List.of(
                                AgentConversationItem.userText("Buscar [TAX_ID_1]"),
                                AgentConversationItem.assistantToolCalls(first.text(), first.toolCalls(), first.providerOpaqueState()),
                                AgentConversationItem.toolResults(List.of(
                                        new AgentToolResult("call-gem-1", "search_and_resolve_entities", Map.of("matchCount", 1), false)))),
                        List.of(decl),
                        cfg),
                AgentModelStreamListener.NOOP);

        assertThat(second.hasToolCalls()).isFalse();
        assertThat(second.text()).isEqualTo("Cadastro localizado.");
        assertThat(second.usage().totalTokens()).isEqualTo(180L);

        List<Content> round2Contents = capturedContents.get(1);
        assertThat(round2Contents).hasSize(3);
        Part modelCallPart = round2Contents.get(1).parts().orElseThrow().get(0);
        assertThat(modelCallPart.thoughtSignature()).contains(expectedSignature);
        Part toolRespPart = round2Contents.get(2).parts().orElseThrow().get(0);
        assertThat(toolRespPart.functionResponse()).isPresent();
        assertThat(toolRespPart.functionResponse().get().id()).contains("call-gem-1");
    }
}
