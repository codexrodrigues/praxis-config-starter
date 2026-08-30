package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringLlmPreIntentToolPlanningServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    @Mock
    private DomainCatalogPromptContextService domainCatalogPromptContextService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesSelectedDomainDecisionToSemanticPlanningWithoutApiResourceDiscovery() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "authoring_or_other",
                  "assistantMessage": "",
                  "shouldRetrieveGovernedResources": true,
                  "requiresFullIntentResolution": true,
                  "queryConstraints": {"appliesToDataSelection": false, "filters": []},
                  "groundingProfile": "api_resource",
                  "artifactKind": "page",
                  "primaryComponent": "praxis-table",
                  "retrievalQuery": "folhas de pagamento",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "human-resources.folhas-pagamento",
                    "supportingConcepts": [],
                    "desiredSurface": "table",
                    "uncertainty": null,
                    "rationale": "Candidate deliberately conflicts with the selected governed decision."
                  },
                  "reason": "Generic resource retrieval must be suppressed by the canonical selection guard."
                }
                """));
        ObjectNode hints = objectMapper.createObjectNode();
        hints.putObject("selectedDomainDecisionRef")
                .put("schemaVersion", "praxis.ai.context-hints.domain-decision/v1")
                .put("definitionId", "758db752-19f0-4ab6-afd8-33f34eacb447")
                .put("ruleKey", "human-resources.example")
                .put("version", 3)
                .put("source", "policy-studio-selection");
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService, objectMapper, 7, 1, 0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("Explique esta decisao", objectMapper.createObjectNode(), hints),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().requiresFullIntentResolution()).isTrue();
        assertThat(result.plan().toolCalls()).isEmpty();
        assertThat(result.plan().artifactKind()).isEqualTo("unknown");
        assertThat(result.plan().primaryComponent()).isEmpty();
        assertThat(result.plan().reason())
                .isEqualTo("selected-domain-decision-deferred-to-full-semantic-resolution");
        assertThat(promptCaptor.getValue())
                .contains("planningHints.selectedDomainDecisionRef")
                .contains("758db752-19f0-4ab6-afd8-33f34eacb447")
                .contains("human-resources.example")
                .contains("policy-studio-selection")
                .contains("never generic-search");
    }

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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "authoring_or_other",
                  "assistantMessage": "",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "page",
                  "retrievalQuery": "funcionarios colaboradores recursos humanos pessoas da empresa",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "human-resources.funcionarios",
                    "supportingConcepts": [],
                    "desiredSurface": "table",
                    "uncertainty": "",
                    "rationale": "Canonical employee resource is the requested business subject."
                  },
                  "reason": "O pedido precisa descobrir uma fonte governada de pessoas antes de criar a tela."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        null,
                        7,
                        2,
                        250L,
                        "gpt-5.6-luna");

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.providerInvocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.phase()).isEqualTo("pre_intent_tool_plan");
            assertThat(invocation.model()).isEqualTo("gpt-5.6-luna");
            assertThat(invocation.status()).isEqualTo("success");
        });
        assertThat(result.plan().reason()).contains("fonte governada");
        assertThat(result.plan().semanticIntentClass()).isEqualTo("authoring_or_other");
        assertThat(result.plan().resolvesPlatformGuidance()).isFalse();
        assertThat(result.plan().toolCalls()).hasSize(1);
        AgenticAuthoringToolCall call = result.plan().toolCalls().get(0);
        assertThat(call.name()).isEqualTo("searchApiResources");
        assertThat(call.routeClass()).isEqualTo("pre_intent_resource_discovery");
        assertThat(call.payload()).isInstanceOf(AgenticAuthoringResourceCandidatesRequest.class);
        AgenticAuthoringResourceCandidatesRequest payload =
                (AgenticAuthoringResourceCandidatesRequest) call.payload();
        assertThat(payload.retrievalQuery())
                .isEqualTo("primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: none. desired surface: table. semantic query: "
                        + "funcionarios colaboradores recursos humanos pessoas da empresa");
        assertThat(payload.userPrompt())
                .isEqualTo("quero criar algo que mostre informacoes dos empregados");
        assertThat(payload.artifactKind()).isEqualTo("page");
        assertThat(promptCaptor.getValue())
                .contains("without keyword routing")
                .contains("vague, misspelled, colloquial, multilingual")
                .contains("use it as semantic context for retrievalQuery")
                .contains("primaryBusinessEntity is the canonical")
                .contains("collection-oriented dashboard")
                .contains("depends on multiple coordinated analytical")
                .contains("where analytics are not the dominant requested outcome")
                .contains("individual or single-record profile")
                .contains("payroll, is itself requested")
                .contains("what is being measured or explained")
                .contains("payroll is primaryBusinessEntity")
                .contains("employee headcount, status, role")
                .contains("canonical payroll analytical resource")
                .contains("semanticIntentClass=governed_domain_discovery")
                .contains("groundingProfile=domain_context")
                .contains("sobre quais assuntos posso criar")
                .contains("not yet commanding creation")
                .contains("domainDiscovery")
                .contains("human-resources.funcionarios")
                .contains("Resource discovery and defaults are not constraints")
                .contains("queryConstraints.appliesToDataSelection=true")
                .contains("headers, labels, renderers, formatting, composed cells")
                .contains("Author layoutKind independently")
                .contains("resource-master-detail")
                .contains("resource-crud + praxis-crud")
                .contains("Feasibility questions stay platform_guidance")
                .contains("they do not")
                .contains("informações salariais")
                .contains("dos funcionários?");
        assertThat(promptCaptor.getValue()).doesNotContain("\n  \"");
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("semanticIntentClass")
                .contains("governed_domain_discovery")
                .contains("feasibility or capability questions")
                .contains("without an explicit creation or modification request")
                .contains("assistantMessage")
                .contains("shouldRetrieveGovernedResources")
                .contains("retrievalQuery")
                .contains("appliesToDataSelection")
                .contains("displayed-value edits")
                .contains("primaryComponent")
                .contains("praxis-crud")
                .contains("layoutKind")
                .contains("AI-authored semantic composition archetype")
                .contains("Canonical business subject explicitly requested by the user")
                .contains("Dimensions, fields, filters, groupings")
                .contains("collection dashboard with filters, charts, and a detail table");
        JsonNode structuredOutputSchema = objectMapper.readTree(schemaCaptor.getValue().jsonSchema());
        assertThat(structuredOutputSchema.path("properties").path("schemaVersion").path("enum"))
                .containsExactly(objectMapper.getNodeFactory()
                        .textNode("praxis-agentic-authoring-pre-intent-tool-plan.v3"));
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(7);
        assertThat(configCaptor.getValue().getModel()).isEqualTo("gpt-5.6-luna");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(640);
        assertThat(configCaptor.getValue().getInvocationTrace()).isNotNull();
    }

    @Test
    void plansGovernedDomainDecisionSearchWithoutKeywordRouting() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "authoring_or_other",
                  "assistantMessage": "",
                  "shouldRetrieveGovernedResources": true,
                  "requiresFullIntentResolution": false,
                  "queryConstraints": {"appliesToDataSelection": false, "filters": []},
                  "groundingProfile": "domain_decision",
                  "artifactKind": "unknown",
                  "primaryComponent": null,
                  "retrievalQuery": "decisões de concessão extraordinária",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": null,
                    "supportingConcepts": [],
                    "desiredSurface": null,
                    "uncertainty": null,
                    "rationale": "O alvo exato deve ser escolhido somente após a busca governada."
                  },
                  "reason": "A solicitação precisa descobrir decisões existentes antes da explicação."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        null,
                        7,
                        2,
                        250L,
                        "gpt-5.6-luna");

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("Quais decisões governam a concessão extraordinária?"),
                new AiPrincipalContext("tenant", "user", "local", false));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("searchDomainRules");
            assertThat(call.routeClass()).isEqualTo("pre_intent_resource_discovery");
            assertThat(call.payload()).isInstanceOf(JsonNode.class);
            JsonNode payload = (JsonNode) call.payload();
            assertThat(payload.path("query").asText())
                    .isEqualTo("decisões de concessão extraordinária");
            assertThat(payload.path("page").asInt()).isZero();
            assertThat(payload.path("limit").asInt()).isEqualTo(6);
        });
        assertThat(promptCaptor.getValue())
                .contains("groundingProfile=domain_decision")
                .contains("LLM semantic discovery")
                .contains("never conditions or authority");
    }

    @Test
    void preservesCrudAsPrimaryComponentForGovernedWorkflowActions() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "requiresFullIntentResolution": true,
                          "queryConstraints": {
                            "appliesToDataSelection": true,
                            "filters": [{
                              "concept": "eventos pendentes",
                              "field": "status",
                              "operator": "eq",
                              "value": "pendentes"
                            }]
                          },
                          "groundingProfile": "api_resource",
                          "artifactKind": "page",
                          "primaryComponent": "praxis-crud",
                          "layoutKind": "resource-crud",
                          "retrievalQuery": "eventos da folha com ação de aprovação em lote",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "human-resources.eventos-folha",
                            "supportingConcepts": ["status pendente", "aprovação em lote"],
                            "desiredSurface": "lista operacional com ação governada",
                            "uncertainty": null,
                            "rationale": "A interação exige preservar ações do recurso existente."
                          },
                          "reason": "Ground the resource and preserve its governed workflow actions."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("monte uma lista dos eventos pendentes e deixe a aprovação em lote disponível"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().primaryComponent()).isEqualTo("praxis-crud");
        assertThat(result.plan().layoutKind()).isEqualTo("resource-crud");
        assertThat(result.plan().requiresFullIntentResolution()).isTrue();
        assertThat(result.plan().queryConstraints().path("filters")).hasSize(1);
    }

    @Test
    void preservesMasterDetailCompositionIndependentlyFromGovernedItemActions() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "requiresFullIntentResolution": false,
                          "queryConstraints": {
                            "appliesToDataSelection": false,
                            "filters": []
                          },
                          "groundingProfile": "api_resource",
                          "artifactKind": "page",
                          "primaryComponent": "praxis-table",
                          "layoutKind": "resource-master-detail",
                          "retrievalQuery": "missões operacionais",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "operations.missoes",
                            "supportingConcepts": ["seleção", "detalhe", "ações de item"],
                            "desiredSurface": "workspace operacional master-detail",
                            "uncertainty": null,
                            "rationale": "A coleção seleciona uma missão para detalhe e ações governadas."
                          },
                          "reason": "Preserve the requested resource workspace composition."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("crie uma página master-detail de missões com ações de item descobertas"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().artifactKind()).isEqualTo("page");
        assertThat(result.plan().primaryComponent()).isEqualTo("praxis-table");
        assertThat(result.plan().layoutKind()).isEqualTo("resource-master-detail");
        assertThat(result.plan().requiresFullIntentResolution()).isFalse();
    }

    @Test
    void rejectsIncompatibleCompactLayoutAndPrimaryComponentPair() throws Exception {
        ObjectNode result = (ObjectNode) objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "authoring_or_other",
                  "shouldRetrieveGovernedResources": true,
                  "requiresFullIntentResolution": false,
                  "artifactKind": "page",
                  "primaryComponent": "praxis-crud",
                  "layoutKind": "resource-master-detail"
                }
                """);
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        Boolean valid = ReflectionTestUtils.invokeMethod(service, "isValidStructuredPlan", result);

        assertThat(valid).isFalse();

        result.put("primaryComponent", "praxis-table");
        result.put("schemaVersion", "praxis-agentic-authoring-pre-intent-tool-plan.v2");
        Boolean staleVersion = ReflectionTestUtils.invokeMethod(service, "isValidStructuredPlan", result);
        assertThat(staleVersion).isFalse();
    }

    @Test
    void rejectsRetrievalAuthoringWithoutCanonicalBusinessEntityEvenWhenFullResolutionIsNotRequired()
            throws Exception {
        ObjectNode result = (ObjectNode) objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "authoring_or_other",
                  "shouldRetrieveGovernedResources": true,
                  "requiresFullIntentResolution": false,
                  "artifactKind": "page",
                  "primaryComponent": "praxis-table",
                  "layoutKind": "resource-master-detail",
                  "groundingProfile": "api_resource"
                }
                """);
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        Boolean valid = ReflectionTestUtils.invokeMethod(service, "isValidStructuredPlan", result);

        assertThat(valid).isFalse();
        result.putObject("resourceSearchFocus")
                .put("primaryBusinessEntity", "operations.missoes");
        Boolean grounded = ReflectionTestUtils.invokeMethod(service, "isValidStructuredPlan", result);
        assertThat(grounded).isTrue();
    }

    @Test
    void promotesResolvedExecutableDomainConceptToOperationalResourceSearch() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "groundingProfile": "domain_concept",
                          "artifactKind": "table",
                          "retrievalQuery": "funcionarios da area de tecnologia",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "human-resources.funcionarios",
                            "supportingConcepts": ["area tecnologia"],
                            "desiredSurface": "table",
                            "excludedConcepts": [],
                            "rationale": "Canonical employee resource resolved by the LLM."
                          },
                          "requiresFullIntentResolution": true,
                          "reason": "Resolve employees and the requested business filter."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("mostre as informacoes dos funcionarios que sao da area de tecnologia"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
            assertThat(call.routeClass()).isEqualTo("pre_intent_resource_discovery");
            assertThat(call.payload()).isInstanceOfSatisfying(
                    AgenticAuthoringResourceCandidatesRequest.class,
                    payload -> assertThat(payload.resourceSearchFocus().primaryBusinessEntity())
                            .isEqualTo("human-resources.funcionarios"));
        });
    }

    @Test
    void supportingConceptsDoNotForceFullResolutionWithoutExecutableConstraints() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "groundingProfile": "api_resource",
                          "artifactKind": "dashboard",
                          "retrievalQuery": "painel administrativo de funcionarios",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "human-resources.funcionarios",
                            "supportingConcepts": ["departamento", "cargo", "status"],
                            "desiredSurface": "painel administrativo",
                            "excludedConcepts": [],
                            "rationale": "Use concepts only to enrich retrieval of the employee resource."
                          },
                          "queryConstraints": {"appliesToDataSelection": false, "filters": []},
                          "requiresFullIntentResolution": false,
                          "reason": "The semantic intent is complete after governed resource discovery."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("crie um painel administrativo para acompanhar funcionarios"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().requiresFullIntentResolution()).isFalse();
        assertThat(result.plan().queryConstraints().path("appliesToDataSelection").asBoolean()).isFalse();
        assertThat(result.plan().queryConstraints().path("filters")).isEmpty();
    }

    @Test
    void retriesConcreteFullIntentWhenLlmDropsThePrimaryBusinessEntity() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(
                        objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "groundingProfile": "domain_context",
                          "artifactKind": "table",
                          "retrievalQuery": "registros de funcionários por departamentos de engenharia e inteligência artificial",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": null,
                            "supportingConcepts": ["engenharia", "inteligência artificial"],
                            "desiredSurface": "tabela",
                            "uncertainty": "a entidade canônica ainda precisa ser descoberta",
                            "rationale": "O pedido concreto requer recurso, campo e valores governados."
                          },
                          "requiresFullIntentResolution": true,
                          "reason": "Criar uma tabela filtrada exige descoberta operacional."
                        }
                        """),
                        objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "groundingProfile": "api_resource",
                          "artifactKind": "table",
                          "retrievalQuery": "registros de funcionários por departamentos de engenharia e inteligência artificial",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "human-resources.funcionarios",
                            "supportingConcepts": ["engenharia", "inteligência artificial"],
                            "desiredSurface": "tabela",
                            "uncertainty": null,
                            "rationale": "O catálogo identifica funcionários como o sujeito exibido."
                          },
                          "requiresFullIntentResolution": true,
                          "reason": "Criar uma tabela filtrada exige descoberta operacional."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("preciso de uma tabela com funcionários de engenharia e inteligência artificial"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
            assertThat(call.routeClass()).isEqualTo("pre_intent_resource_discovery");
            assertThat(call.payload()).isInstanceOfSatisfying(
                    AgenticAuthoringResourceCandidatesRequest.class,
                    payload -> {
                        assertThat(payload.resourceSearchFocus().primaryBusinessEntity())
                                .isEqualTo("human-resources.funcionarios");
                        assertThat(payload.retrievalQuery())
                                .contains("primary business entity: human-resources.funcionarios")
                                .contains("semantic query: registros de funcionários");
                    });
        });
        assertThat(result.providerInvocations()).hasSize(2);
    }

    @Test
    void plansCanonicalContextEnumerationForGovernedDomainDiscovery() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "governed_domain_discovery",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "groundingProfile": "domain_context",
                          "artifactKind": "dashboard",
                          "retrievalQuery": null,
                          "resourceSearchFocus": null,
                          "reason": "Enumerate governed business contexts before choosing the dashboard subject."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("Quais temas administrativos estão disponíveis para eu criar um dashboard interativo?"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().semanticIntentClass()).isEqualTo("governed_domain_discovery");
        assertThat(result.plan().resolvesPlatformGuidance()).isFalse();
        assertThat(result.plan().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo(AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CONTEXTS);
            assertThat(call.payload()).isEqualTo(new DomainKnowledgeToolRequest("", "", 0));
        });
    }

    @Test
    void preservesRequestedModelForNonOpenAiProviders() throws Exception {
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                any(),
                any(),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "platform_guidance",
                  "assistantMessage": "Posso ajudar a criar formularios, tabelas, graficos e paineis.",
                  "shouldRetrieveGovernedResources": false,
                  "artifactKind": "unknown",
                  "retrievalQuery": null,
                  "reason": "A pergunta pede orientacao sobre as capacidades da plataforma."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        null,
                        7,
                        1,
                        0L,
                        "gpt-5.6-luna");
        AgenticAuthoringTurnStreamRequest request = new AgenticAuthoringTurnStreamRequest(
                "o que posso fazer aqui?",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/decision-playground",
                objectMapper.createObjectNode(),
                null,
                "gemini",
                "gemini-2.5-flash",
                "test-key",
                "session-1",
                "turn-client-1",
                List.of(),
                null,
                List.of(),
                objectMapper.createObjectNode(),
                null,
                null);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request,
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(configCaptor.getValue().getProvider()).isEqualTo("gemini");
        assertThat(configCaptor.getValue().getModel()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void plansCapabilityGroundingBeforeOperationalApiDiscovery() throws Exception {
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "groundingProfile": "domain_capability",
                          "artifactKind": "form",
                          "retrievalQuery": null,
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": null,
                            "supportingConcepts": [],
                            "desiredSurface": "form",
                            "uncertainty": null,
                            "rationale": "The business context is known; capability must be grounded first."
                          },
                          "reason": "Resolve the governed capability before bindings or endpoints."
                        }
                        """));
        AgenticAuthoringTurnStreamRequest request = request(
                "crie uma experiência para o domínio atual",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode().put("contextKey", "human-resources"));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request,
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo(AgenticAuthoringToolRegistry.DISCOVER_DOMAIN_CAPABILITIES);
            assertThat(call.routeClass()).isEqualTo("advisory_authoring");
            assertThat(call.payload()).isEqualTo(new DomainKnowledgeToolRequest("human-resources", "", 0));
        });
    }

    @Test
    void suppliesOnlyCompactCanonicalResourceIdentitiesBeforeSemanticPlanning() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        AgenticAuthoringTurnStreamRequest request = request(
                "crie um painel de afastamentos",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode().put("source", "page-builder"));
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "shouldRetrieveGovernedResources": true,
                          "artifactKind": "dashboard",
                          "retrievalQuery": "afastamentos por departamento",
                          "reason": "O painel depende de dados governados de afastamentos."
                        }
                        """));
        when(domainCatalogPromptContextService.buildResourceIdentityContext("tenant", "local", 30))
                .thenReturn("""
                        DOMAIN_RESOURCE_IDENTITY_CATALOG
                        items:
                        - resourceKey=human-resources.ferias-afastamentos | label=Férias e afastamentos
                        """);
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request,
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-semantic-orientation-context.v1")
                .contains("DOMAIN_RESOURCE_IDENTITY_CATALOG")
                .contains("human-resources.ferias-afastamentos")
                .doesNotContain("DOMAIN_CATALOG_CONTEXT");
        verify(domainCatalogPromptContextService)
                .buildResourceIdentityContext("tenant", "local", 30);
        assertThat(request.contextHints().has("domainCatalog")).isFalse();
    }

    @Test
    void broadDomainCatalogAvailabilityDoesNotTriggerDetailedRetrievalBeforeSemanticOrientation() throws Exception {
        AgenticAuthoringTurnStreamRequest request = request(
                "monte uma tabela com pessoas da empresa",
                objectMapper.createObjectNode(),
                objectMapper.readTree("""
                        {
                          "source": "page-builder",
                          "domainCatalog": {
                            "mode": "domain-360",
                            "status": "ready",
                            "serviceKey": "praxis-service"
                          }
                        }
                        """));
        when(domainCatalogPromptContextService.buildResourceIdentityContext("tenant", "local", 30))
                .thenReturn("DOMAIN_RESOURCE_IDENTITY_CATALOG\nitems:\n- resourceKey=human-resources.funcionarios | label=Funcionários");
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "artifactKind": "table",
                          "retrievalQuery": "funcionários",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "human-resources.funcionarios",
                            "supportingConcepts": [],
                            "desiredSurface": "table",
                            "uncertainty": "",
                            "rationale": "Recurso canônico identificado."
                          },
                          "requiresFullIntentResolution": false,
                          "groundingProfile": "api_resource",
                          "reason": "A tabela depende do recurso governado."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request,
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        verify(domainCatalogPromptContextService)
                .buildResourceIdentityContext("tenant", "local", 30);
        verify(domainCatalogPromptContextService, times(0))
                .buildPromptContext(any(), any(), any(), any());
    }

    @Test
    void explicitBusinessScopeStillAddsDetailedGovernedContext() throws Exception {
        AgenticAuthoringTurnStreamRequest request = request(
                "monte uma tabela de funcionários",
                objectMapper.createObjectNode(),
                objectMapper.readTree("""
                        {
                          "domainCatalog": {
                            "serviceKey": "praxis-service",
                            "resourceKey": "human-resources.funcionarios"
                          }
                        }
                        """));
        when(domainCatalogPromptContextService.buildResourceIdentityContext("tenant", "local", 30))
                .thenReturn("DOMAIN_RESOURCE_IDENTITY_CATALOG\nitems:\n- resourceKey=human-resources.funcionarios | label=Funcionários");
        when(domainCatalogPromptContextService.buildPromptContext(
                eq("monte uma tabela de funcionários"),
                any(),
                eq("tenant"),
                eq("local")))
                .thenReturn("DOMAIN_CATALOG_CONTEXT\nresourceKey: human-resources.funcionarios");
        when(providerManagementService.generateJson(
                any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "semanticIntentClass": "authoring_or_other",
                          "assistantMessage": "",
                          "shouldRetrieveGovernedResources": true,
                          "artifactKind": "table",
                          "retrievalQuery": "funcionários",
                          "resourceSearchFocus": {
                            "primaryBusinessEntity": "human-resources.funcionarios",
                            "supportingConcepts": [],
                            "desiredSurface": "table",
                            "uncertainty": "",
                            "rationale": "Recurso canônico identificado."
                          },
                          "requiresFullIntentResolution": false,
                          "groundingProfile": "api_resource",
                          "reason": "A tabela depende do recurso governado."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request,
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        verify(domainCatalogPromptContextService).buildPromptContext(
                eq("monte uma tabela de funcionários"),
                any(),
                eq("tenant"),
                eq("local"));
    }

    @Test
    void preservesExplicitDomainCatalogOptOutBeforePreIntentPlanning() throws Exception {
        AgenticAuthoringTurnStreamRequest request = request(
                "ajuste apenas o espaçamento visual",
                objectMapper.createObjectNode(),
                objectMapper.readTree("""
                        {
                          "domainCatalog": {
                            "enabled": false
                          }
                        }
                        """));
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                          "shouldRetrieveGovernedResources": false,
                          "artifactKind": "page",
                          "reason": "A mudança é apenas visual."
                        }
                        """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);

        service.plan(request, new AiPrincipalContext("tenant", "user", "local", true));

        verifyNoInteractions(domainCatalogPromptContextService);
    }

    @Test
    void preservesSemanticOrientationWhenLlmDoesNotRequestGovernedResourceRetrieval() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
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

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().toolCalls()).isEmpty();
        assertThat(result.plan().semanticIntentClass()).isEqualTo("authoring_or_other");
        assertThat(result.plan().assistantMessage()).isEmpty();
    }

    @Test
    void resolvesPlatformGuidanceWithoutRecommendedIntentFromPraxisDomainSurfaceAndComponents() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "semanticIntentClass": "platform_guidance",
                  "assistantMessage": "Aqui no Praxis posso ajudar a criar formulários, tabelas e gráficos usando os recursos governados do seu domínio.",
                  "shouldRetrieveGovernedResources": false,
                  "artifactKind": "unknown",
                  "retrievalQuery": null,
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": null,
                    "supportingConcepts": [],
                    "desiredSurface": null,
                    "uncertainty": null,
                    "rationale": null
                  },
                  "reason": "A pergunta solicita orientação geral sobre as capacidades disponíveis."
                }
                """));
        AgenticAuthoringComponentCapabilitiesResult capabilities =
                new AgenticAuthoringComponentCapabilitiesResult(
                        "test",
                        List.of(
                                new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                                        "praxis-dynamic-form", "test", List.of()),
                                new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                                        "praxis-table", "test", List.of()),
                                new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                                        "praxis-chart", "test", List.of())));
        AgenticAuthoringTurnStreamRequest base = request(
                "O que posso fazer aqui?",
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode()
                        .put("source", "page-builder")
                        .put("responseLocale", "en-US"));
        AgenticAuthoringTurnStreamRequest requestWithoutRecommendation = new AgenticAuthoringTurnStreamRequest(
                base.userPrompt(), base.targetApp(), base.targetComponentId(), base.currentRoute(),
                base.currentPage(), base.selectedWidgetKey(), base.provider(), base.model(), base.apiKey(),
                base.sessionId(), base.clientTurnId(), base.conversationMessages(), base.pendingClarification(),
                base.attachmentSummaries(), base.contextHints(), capabilities);
        when(domainCatalogPromptContextService.buildResourceIdentityContext("tenant", "local", 30))
                .thenReturn("""
                        DOMAIN_RESOURCE_IDENTITY_CATALOG
                        items:
                        - resourceKey=human-resources.funcionarios | label=Funcionários
                        """);
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                requestWithoutRecommendation,
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.plan().resolvesPlatformGuidance()).isTrue();
        assertThat(result.plan().toolCalls()).isEmpty();
        assertThat(result.providerInvocations()).hasSize(1);
        assertThat(promptCaptor.getValue())
                .contains("Praxis is a governed AI authoring platform")
                .contains("praxis-dynamic-form")
                .contains("praxis-table")
                .contains("praxis-chart")
                .contains("DOMAIN_RESOURCE_IDENTITY_CATALOG")
                .contains("human-resources.funcionarios")
                .contains("Canonical response locale: en-US")
                .contains("recommendedIntent is optional evidence")
                .doesNotContain("\"changeKinds\"")
                .doesNotContain("\"semanticTerms\"");
        assertThat(promptCaptor.getValue()).doesNotContain("DOMAIN_CATALOG_CONTEXT");
        verify(domainCatalogPromptContextService)
                .buildResourceIdentityContext("tenant", "local", 30);
        assertThat(promptCaptor.getValue()).doesNotContain("\"recommendedIntent\"");
    }

    @Test
    void enrichesRetrievalQueryWithLlmAuthoredResourceSearchFocus() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "page",
                  "retrievalQuery": "acompanhar pessoas da empresa com detalhes por area",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "pessoas da empresa",
                    "supportingConcepts": ["area", "departamento", "detalhes"],
                    "desiredSurface": "pagina operacional de acompanhamento",
                    "uncertainty": "usuario ainda nao sabe se quer tabela ou painel",
                    "rationale": "Separar entidade principal de dimensoes auxiliares evita ranquear departamento como fonte principal."
                  },
                  "reason": "O pedido precisa descobrir a fonte governada principal antes da tela."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper, 7);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("preciso ver como esta meu pessoal por area"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        AgenticAuthoringResourceCandidatesRequest payload =
                (AgenticAuthoringResourceCandidatesRequest) result.plan().toolCalls().get(0).payload();
        assertThat(payload.retrievalQuery())
                .contains("primary business entity: pessoas da empresa")
                .contains("supporting concepts: area, departamento, detalhes")
                .contains("desired surface: pagina operacional de acompanhamento")
                .contains("semantic query: acompanhar pessoas da empresa com detalhes por area");
    }

    @Test
    void reconcilesLlmAuthoredBusinessEntityWithCanonicalDomainDiscoveryResourceKey() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "table",
                  "retrievalQuery": "consultar nome cargo e departamento",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "funcionarios",
                    "supportingConcepts": ["nome", "cargo", "departamento"],
                    "desiredSurface": "tabela para consulta",
                    "uncertainty": "",
                    "rationale": "A entidade governada são os funcionários."
                  },
                  "reason": "O pedido depende do recurso operacional governado."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper, 7);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request(
                        "monte a superfície de consulta solicitada",
                        objectMapper.createObjectNode(),
                        objectMapper.readTree("""
                        {
                          "domainDiscovery": [
                            {
                              "resourceKey": "operations.missoes",
                              "title": "Missões"
                            },
                            {
                              "resourceKey": "human-resources.funcionarios",
                              "title": "Funcionários",
                              "aliases": ["colaboradores", "staff"]
                            }
                          ]
                        }
                        """)),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        AgenticAuthoringResourceCandidatesRequest payload =
                (AgenticAuthoringResourceCandidatesRequest) result.plan().toolCalls().get(0).payload();
        assertThat(payload.resourceSearchFocus().primaryBusinessEntity())
                .isEqualTo("human-resources.funcionarios");
        assertThat(payload.retrievalQuery())
                .contains("primary business entity: human-resources.funcionarios")
                .contains("semantic query: consultar nome cargo e departamento");
        assertThat(promptCaptor.getValue())
                .contains("use its canonical resourceKey")
                .contains("human-resources.funcionarios");
    }

    @Test
    void keepsLlmAuthoredBusinessEntityWhenDomainDiscoveryIdentityIsAmbiguous() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "table",
                  "retrievalQuery": "consultar funcionarios",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "funcionarios",
                    "supportingConcepts": [],
                    "desiredSurface": "tabela",
                    "uncertainty": "duas fontes usam o mesmo nome",
                    "rationale": "A entidade ainda precisa ser desambiguada."
                  },
                  "reason": "O pedido depende de busca governada."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper, 7);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request(
                        "monte uma tabela",
                        objectMapper.createObjectNode(),
                        objectMapper.readTree("""
                        {
                          "domainDiscovery": [
                            {"resourceKey": "human-resources.funcionarios", "title": "Funcionários"},
                            {"resourceKey": "suppliers.funcionarios", "title": "Funcionários"}
                          ]
                        }
                        """)),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        AgenticAuthoringResourceCandidatesRequest payload =
                (AgenticAuthoringResourceCandidatesRequest) result.plan().toolCalls().get(0).payload();
        assertThat(payload.resourceSearchFocus().primaryBusinessEntity()).isEqualTo("funcionarios");
        assertThat(payload.retrievalQuery())
                .contains("primary business entity: funcionarios")
                .contains("supporting concepts: none")
                .contains("desired surface: tabela")
                .doesNotContain("primary business entity: human-resources.funcionarios");
    }

    @Test
    void sendsCompactPlannerContextInsteadOfRawPageAndHints() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "page",
                  "retrievalQuery": "contratos fornecedores compras vigencia status",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "contratos de fornecedores",
                    "supportingConcepts": ["compras", "vigencia", "status"],
                    "desiredSurface": "pagina de acompanhamento",
                    "uncertainty": "",
                    "rationale": "O usuario quer uma visao operacional de contratos."
                  },
                  "reason": "O pedido precisa de descoberta governada antes da tela."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper, 7);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request(
                        "quero acompanhar os contratos dos fornecedores",
                        objectMapper.readTree("""
                        {
                          "widgets": [
                            {
                              "key": "contracts-table",
                              "componentId": "praxis-table",
                              "resourcePath": "/api/procurement/contracts",
                              "largeLocalConfig": "raw-page-config-that-should-not-be-sent-to-planner"
                            }
                          ],
                          "largePageDraft": "raw-page-draft-that-should-not-be-sent-to-planner"
                        }
                        """),
                        objectMapper.readTree("""
                        {
                          "domainDiscovery": [
                            {
                              "resourceKey": "procurement.contracts",
                              "title": "Contratos",
                              "description": "Contratos firmados com fornecedores",
                              "largeGovernancePayload": "raw-domain-discovery-payload-that-should-not-be-sent-to-planner"
                            }
                          ],
                          "projectKnowledge": {
                            "schemaVersion": "praxis-agentic-authoring-project-knowledge.v1",
                            "source": "domain_knowledge_concept",
                            "entries": [
                              {
                                "knowledgeId": "contracts-policy",
                                "summary": "Priorizar contratos ativos e vencimento",
                                "rawEvidence": "raw-project-knowledge-evidence-that-should-not-be-sent-to-planner"
                              }
                            ]
                          },
                          "largeContext": "raw-context-hint-that-should-not-be-sent-to-planner"
                        }
                        """)),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-pre-intent-current-page-projection.v1")
                .contains("praxis-agentic-authoring-pre-intent-context-hints-projection.v1")
                .contains("procurement.contracts")
                .contains("/api/procurement/contracts")
                .contains("Contratos firmados com fornecedores")
                .contains("Priorizar contratos ativos e vencimento")
                .doesNotContain("raw-page-config-that-should-not-be-sent-to-planner")
                .doesNotContain("raw-page-draft-that-should-not-be-sent-to-planner")
                .doesNotContain("raw-domain-discovery-payload-that-should-not-be-sent-to-planner")
                .doesNotContain("raw-project-knowledge-evidence-that-should-not-be-sent-to-planner")
                .doesNotContain("raw-context-hint-that-should-not-be-sent-to-planner");
    }

    @Test
    void compactsLongSpokenPromptForPlannerWhilePreservingHeadAndTail() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "page",
                  "retrievalQuery": "funcionarios ficha resumo pessoas empresa",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "funcionarios",
                    "supportingConcepts": ["ficha", "resumo"],
                    "desiredSurface": "perfil individual",
                    "uncertainty": "transcricao longa com contexto irrelevante",
                    "rationale": "A intencao aparece no final depois de uma narracao longa."
                  },
                  "reason": "O pedido depende de fonte governada de funcionarios."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(providerManagementService, objectMapper, 7);
        String longMiddle = "detalhe irrelevante de transcricao ".repeat(120);
        String prompt = "olha eu estava pensando no fluxo do RH e preciso melhorar a consulta "
                + longMiddle
                + "no fim quero uma tela de perfil individual do funcionario com ficha de resumo";

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request(prompt),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(promptCaptor.getValue())
                .contains("olha eu estava pensando no fluxo do RH")
                .contains("perfil individual do funcionario com ficha de resumo")
                .contains("middle omitted for planner performance")
                .contains("userPromptOriginalLength")
                .contains("head_tail_compacted");
        assertThat(promptCaptor.getValue().length()).isLessThan(12_500);
        assertThat(promptCaptor.getValue()).doesNotContain(longMiddle);
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

    @Test
    void doesNotRetryTimeoutBecausePreIntentPlanningIsOptionalAndBounded() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenThrow(AiProviderCallException.timeout("openai", new RuntimeException("request timed out")));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        7,
                        2,
                        0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isFalse();
        assertThat(result.skipReason()).isEqualTo("provider-error");
        assertThat(result.errorCode()).isEqualTo("AiProviderCallException");
        verify(providerManagementService, times(1)).generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void retriesFastRateLimitFailureWhenAnotherAttemptFitsPlanningBudget() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenThrow(AiProviderCallException.fromHttpStatus("openai", 429, "rate limit exceeded"))
                .thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "page",
                  "retrievalQuery": "funcionarios colaboradores recursos humanos pessoas da empresa",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "pessoas da empresa",
                    "supportingConcepts": ["cargo", "departamento"],
                    "desiredSurface": "pagina de acompanhamento",
                    "uncertainty": "",
                    "rationale": "O usuario quer mostrar informacoes de empregados."
                  },
                  "reason": "O planejamento governado foi recuperado dentro do mesmo budget da fase."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        7,
                        2,
                        0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.skipReason()).isBlank();
        verify(providerManagementService, times(2)).generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void doesNotRetryTransientFailureWhenAnotherAttemptCannotFitPlanningBudget() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenThrow(AiProviderCallException.fromHttpStatus("openai", 429, "rate limit exceeded"));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        1,
                        2,
                        0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isFalse();
        assertThat(result.skipReason()).isEqualTo("provider-error");
        verify(providerManagementService, times(1)).generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void retriesInvalidStructuredOutputInsteadOfTreatingItAsNoToolDecision() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(null)
                .thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v3",
                  "shouldRetrieveGovernedResources": true,
                  "artifactKind": "dashboard",
                  "retrievalQuery": "funcionarios por cargo e departamento",
                  "resourceSearchFocus": {
                    "primaryBusinessEntity": "funcionarios",
                    "supportingConcepts": ["cargo", "departamento"],
                    "desiredSurface": "dashboard de colecao",
                    "uncertainty": "",
                    "rationale": "O pedido governa funcionarios, não uma projecao analitica relacionada."
                  },
                  "reason": "O dashboard depende da fonte governada de funcionarios."
                }
                """));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        7,
                        2,
                        0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero um painel 360 dos funcionarios por cargo e departamento"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isTrue();
        assertThat(result.skipReason()).isBlank();
        verify(providerManagementService, times(2)).generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void failsClosedWhenStructuredPlanningOutputRemainsInvalid() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(null);
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        7,
                        2,
                        0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero um painel 360 dos funcionarios"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isFalse();
        assertThat(result.skipReason()).isEqualTo("provider-error");
        assertThat(result.errorCode()).isEqualTo("IllegalStateException");
        verify(providerManagementService, times(2)).generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void doesNotRetryNonTransientProviderFailures() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenThrow(AiProviderCallException.fromHttpStatus(
                        "openai",
                        429,
                        "insufficient_quota"));
        AgenticAuthoringLlmPreIntentToolPlanningService service =
                new AgenticAuthoringLlmPreIntentToolPlanningService(
                        providerManagementService,
                        objectMapper,
                        7,
                        2,
                        0L);

        AgenticAuthoringPreIntentToolPlanningResult result = service.plan(
                request("quero criar algo que mostre informacoes dos empregados"),
                new AiPrincipalContext("tenant", "user", "local", true));

        assertThat(result.planned()).isFalse();
        assertThat(result.skipReason()).isEqualTo("provider-error");
        assertThat(result.errorCode()).isEqualTo("AiProviderCallException");
        verify(providerManagementService, times(1)).generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    private AgenticAuthoringTurnStreamRequest request(String prompt) throws Exception {
        return request(
                prompt,
                objectMapper.createObjectNode(),
                objectMapper.readTree("""
                        {
                          "domainDiscovery": [
                            {
                              "resourceKey": "human-resources.funcionarios",
                              "title": "Funcionários"
                            }
                          ]
                        }
                        """));
    }

    private AgenticAuthoringTurnStreamRequest request(
            String prompt,
            JsonNode currentPage,
            JsonNode contextHints) throws Exception {
        return new AgenticAuthoringTurnStreamRequest(
                prompt,
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/decision-playground",
                currentPage,
                null,
                "openai",
                "gpt-test",
                "test-key",
                "session-1",
                "turn-client-1",
                List.of(),
                null,
                List.of(),
                contextHints,
                null);
    }
}
