package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.praxisplatform.config.service.AiProviderCallException;
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
                .contains("without keyword routing")
                .contains("vague, misspelled, colloquial, multilingual")
                .contains("use it as semantic context for retrievalQuery")
                .contains("primaryBusinessEntity is the canonical")
                .contains("collection-oriented dashboard")
                .contains("depends on multiple coordinated analytical")
                .contains("where analytics are not the dominant requested outcome")
                .contains("individual or single-record profile")
                .contains("payroll, is itself requested")
                .contains("domainDiscovery")
                .contains("human-resources.funcionarios");
        assertThat(promptCaptor.getValue()).doesNotContain("\n  \"");
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("shouldRetrieveGovernedResources")
                .contains("retrievalQuery")
                .contains("Canonical business subject explicitly requested by the user")
                .contains("Dimensions, fields, filters, groupings")
                .contains("collection dashboard with filters, charts, and a detail table");
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(7);
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(640);
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
    void enrichesRetrievalQueryWithLlmAuthoredResourceSearchFocus() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
        assertThat(promptCaptor.getValue().length()).isLessThan(prompt.length() + 3000);
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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
                  "schemaVersion": "praxis-agentic-authoring-pre-intent-tool-plan.v1",
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
