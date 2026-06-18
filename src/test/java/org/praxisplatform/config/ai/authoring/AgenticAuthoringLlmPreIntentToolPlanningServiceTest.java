package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringLlmPreIntentToolPlanningServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void plansSearchApiResourcesWithLlmAuthoredSemanticQuery() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiJsonSchema> schemaCaptor = ArgumentCaptor.forClass(AiJsonSchema.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                schemaCaptor.capture(),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "page",
                  "retrievalQuery": "funcionarios colaboradores recursos humanos pessoas da empresa",
                  "reason": "O pedido precisa descobrir uma fonte governada de pessoas antes de criar a tela."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper, 7);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().reason()).contains("fonte governada");
        assertThat(result.plan().toolCalls()).hasSize(1);
        AgenticAuthoringToolCall call = result.plan().toolCalls().get(0);
        assertThat(call.name()).isEqualTo("searchApiResources");
        assertThat(call.routeClass()).isEqualTo("pre_intent_resource_discovery");
        assertThat(call.payload()).isInstanceOf(AgenticAuthoringResourceCandidatesRequest.class);
        AgenticAuthoringResourceCandidatesRequest payload =
                (AgenticAuthoringResourceCandidatesRequest) call.payload();
        assertThat(payload.retrievalQuery())
                .isEqualTo("funcionarios colaboradores recursos humanos pessoas da empresa");
        assertThat(payload.userPrompt())
                .isEqualTo("quero criar algo que mostre informacoes dos empregados");
        assertThat(payload.artifactKind()).isEqualTo("page");
        assertThat(promptCaptor.getValue())
                .contains("Use reasoning, not keyword matching")
                .contains("vague, misspelled, colloquial, multilingual")
                .contains("treat domainDiscovery as semantic context for the retrievalQuery")
                .contains("domainDiscovery")
                .contains("human-resources.funcionarios");
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("shouldRetrieveGovernedResources")
                .contains("retrievalQuery");
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(7);
    }

    @Test
    void returnsEmptyWhenLlmDoesNotRequestGovernedResourceRetrieval() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
                  "shouldRetrieveGovernedResources": false,
                  "artifactKind": "unknown",
                  "retrievalQuery": null,
                  "reason": "Pedido visual sem necessidade de fonte governada."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("deixe o card mais compacto"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isFalse();
        assertThat(result.skipReason()).isEqualTo("llm-no-tool-requested");
    }

    @Test
    void returnsProviderErrorSkipReasonWhenLlmPlanningFails() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenThrow(new IllegalStateException("Provider not available: openai"));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isFalse();
        assertThat(result.skipReason()).isEqualTo("provider-error");
        assertThat(result.errorCode()).isEqualTo("IllegalStateException");
    }

    private AgenticAuthoringTurnStreamRequest request(String prompt) throws Exception {
        return new AgenticAuthoringTurnStreamRequest(
                prompt,
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/decision-playground",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-test",
                "test-key",
                "session-1",
                "turn-client-1",
                List.of(),
                null,
                List.of(),
                objectMapper.readTree("""
                        {
                          "domainDiscovery": [
                            {
                              "resourceKey": "human-resources.funcionarios",
                              "title": "Funcionários"
                            }
                          ]
                        }
                        """),
                null);
    }
}
