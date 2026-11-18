package org.praxisplatform.config.ai.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
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
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentProposalService;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.tools.AggregateResourceStatsTool;
import org.praxisplatform.config.ai.agent.tools.DiscoverTopicResourcesAndSchemasTool;
import org.praxisplatform.config.ai.agent.tools.ExplainCalculationMemoryTool;
import org.praxisplatform.config.ai.agent.tools.GetEntity360DossierTool;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool;
import org.praxisplatform.config.ai.agent.tools.PrepareActionOrMutationTool;
import org.praxisplatform.config.ai.agent.tools.PrepareBulkOperationTool;
import org.praxisplatform.config.ai.agent.tools.QueryResourceRecordsTool;
import org.praxisplatform.config.ai.agent.tools.SearchAndResolveEntitiesTool;
import org.praxisplatform.config.ai.agent.tools.SearchDomainKnowledgeAndRulesTool;
import org.praxisplatform.config.ai.agent.tools.SimulateDeterminationOrRulesTool;
import org.praxisplatform.config.service.AiCallConfig;
import static org.mockito.Mockito.mock;

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
        assertThat(capturedConfigs.get(0).systemInstruction().orElseThrow().role()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesAllTenToolDeclarationsWithParametersJsonSchemaAndSupportsToolCallingNone() {
        List<GenerateContentConfig> capturedConfigs = new ArrayList<>();
        List<List<Content>> capturedContents = new ArrayList<>();

        GoogleGenAiAgentToolGateway gateway = new GoogleGenAiAgentToolGateway(
                new ObjectMapper(),
                "test-gemini-key",
                "gemini-2.5-flash",
                (apiKey, model, contents, config) -> {
                    capturedContents.add(contents);
                    capturedConfigs.add(config);
                    return GenerateContentResponse.builder()
                            .candidates(List.of(Candidate.builder()
                                    .content(Content.builder()
                                            .role("model")
                                            .parts(List.of(Part.fromText("Sintese final.")))
                                            .build())
                                    .build()))
                            .build();
                });

        AgentResourceExecutionBridge bridge = mock(AgentResourceExecutionBridge.class);
        OperationalAgentProposalService proposalService = mock(OperationalAgentProposalService.class);
        List<OperationalAgentTool> tools = List.of(
                new SearchAndResolveEntitiesTool(bridge),
                new DiscoverTopicResourcesAndSchemasTool(bridge),
                new QueryResourceRecordsTool(bridge),
                new GetEntity360DossierTool(bridge),
                new AggregateResourceStatsTool(bridge),
                new ExplainCalculationMemoryTool(bridge),
                new SearchDomainKnowledgeAndRulesTool(List.of()),
                new SimulateDeterminationOrRulesTool(bridge),
                new PrepareActionOrMutationTool(proposalService),
                new PrepareBulkOperationTool(proposalService));
        List<AgentToolDeclaration> decls = tools.stream().map(OperationalAgentTool::declaration).toList();

        Part thoughtPart = Part.builder().thought(true).text("thinking step").build();
        Part callPart = Part.builder()
                .functionCall(FunctionCall.builder().id("call-t1").name("query_resource_records").args(Map.of()).build())
                .thoughtSignature(new byte[]{1, 2, 3})
                .build();

        gateway.generateTurn(
                new AgentModelTurnRequest(
                        "System",
                        List.of(
                                AgentConversationItem.userText("Consultar"),
                                AgentConversationItem.assistantToolCalls(
                                        "",
                                        List.of(),
                                        Map.of("geminiModelParts", List.of(thoughtPart, callPart))),
                                AgentConversationItem.toolResults(List.of(
                                        new AgentToolResult("call-t1", "query_resource_records", Map.of(), false)))),
                        decls,
                        AiCallConfig.builder().provider("gemini").model("gemini-2.5-flash").build(),
                        true),
                AgentModelStreamListener.NOOP);

        GenerateContentConfig config = capturedConfigs.get(0);
        List<FunctionDeclaration> fnDecls = config.tools().orElseThrow().get(0).functionDeclarations().orElseThrow();
        assertThat(fnDecls).hasSize(10);
        for (FunctionDeclaration fn : fnDecls) {
            assertThat(fn.parameters()).isEmpty();
            assertThat(fn.parametersJsonSchema()).isPresent();
            Map<String, Object> schemaMap = (Map<String, Object>) fn.parametersJsonSchema().orElseThrow();
            assertThat(schemaMap.get("type")).isEqualTo("object");
        }
        assertThat(config.toolConfig()).isPresent();
        assertThat(config.toolConfig().get().functionCallingConfig().orElseThrow().mode())
                .contains(new com.google.genai.types.FunctionCallingConfigMode(FunctionCallingConfigMode.Known.NONE));
        List<Part> replayedModelParts = capturedContents.get(0).get(1).parts().orElseThrow();
        assertThat(replayedModelParts).hasSize(2);
        assertThat(replayedModelParts.get(0).thought()).contains(true);
        assertThat(replayedModelParts.get(1).thoughtSignature()).contains(new byte[]{1, 2, 3});
    }
}
