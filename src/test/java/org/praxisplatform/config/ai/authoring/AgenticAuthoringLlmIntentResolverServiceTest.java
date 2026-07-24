package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringLlmIntentResolverServiceTest {

    @Mock
    private AiProviderManagementService providerManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void repairsIncompleteFastDashboardVisualizationBeforeUsingTheFullResolver() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(
                objectMapper.readTree("""
                        {
                          "resolved": true,
                          "semanticIntentClass": "component_authoring",
                          "operationKind": "create",
                          "artifactKind": "chart",
                          "changeKind": "create_chart",
                          "selectedResourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
                          "resourceSearchQuery": null,
                          "followUpKind": "none",
                          "requiresGovernedAuthoring": false,
                          "assistantMessage": "Vou preparar a visualização de pagamentos.",
                          "visualizationDecision": null,
                          "consultativeRetrievalPlan": null,
                          "quickReplies": [],
                          "clarificationQuestions": [],
                          "warnings": []
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "resolved": true,
                          "semanticIntentClass": "component_authoring",
                          "operationKind": "create",
                          "artifactKind": "dashboard",
                          "changeKind": "create_dashboard",
                          "selectedResourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
                          "resourceSearchQuery": null,
                          "followUpKind": "none",
                          "requiresGovernedAuthoring": false,
                          "assistantMessage": "Vou preparar o dashboard coordenado de pagamentos.",
                          "visualizationDecision": {
                            "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                            "intent": "payroll-dashboard",
                            "layoutKind": "dashboard_grid",
                            "primaryComponent": "praxis-chart",
                            "primaryComponentId": "praxis-chart",
                            "axes": [{
                              "concept": "competência de pagamento",
                              "field": "competencia",
                              "label": "Competência",
                              "chartType": "line",
                              "orientation": "temporal",
                              "metricField": "salarioLiquido",
                              "metricAggregation": "sum",
                              "metricLabel": "Salário líquido",
                              "provenance": "governed-candidate-evidence"
                            }],
                            "includeSummary": true,
                            "includeDetailTable": true,
                            "excludedComponentIds": [],
                            "includeFilters": true,
                            "includeKpis": true,
                            "provenance": "llm-fast-visualization-repair"
                          },
                          "consultativeRetrievalPlan": null,
                          "quickReplies": [],
                          "clarificationQuestions": [],
                          "warnings": []
                        }
                        """));
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode discovery = contextHints.putObject("resourceDiscovery");
        discovery.put("artifactKind", "dashboard");
        ObjectNode focus = discovery.putObject("resourceSearchFocus");
        focus.put("primaryBusinessEntity", "pagamentos de funcionários");
        focus.putArray("supportingConcepts").add("competência").add("departamento");
        focus.put("desiredSurface", "dashboard com indicadores, gráficos e tabela detalhada");

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "Monte um painel bonito para acompanhar os pagamentos dos funcionários.",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key",
                                "session-1",
                                "turn-1",
                                List.of(),
                                null,
                                List.of(),
                                contextHints),
                        "Monte um painel bonito para acompanhar os pagamentos dos funcionários.",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "post",
                                "/schemas/filtered/payroll",
                                "/api/human-resources/vw-analytics-folha-pagamento/filter",
                                "post",
                                0.91d,
                                "semantic payroll analytics",
                                List.of("semantic-retrieval", "tool-search-api-resources"))),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(promptCaptor.getAllValues()).hasSize(2);
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("The previous compact resolution selected an analytical artifact")
                .contains("resolved=false instead of inventing fields or components");
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().axes()).hasSize(1);
        assertThat(result.warnings())
                .contains("llm-fast-intent-resolution-used", "llm-fast-visualization-repair-used");
        assertThat(result.providerInvocations())
                .extracting(AiProviderInvocationTelemetry::phase)
                .containsExactly("intent_fast", "intent_fast_visualization_repair");
    }

    @Test
    void repairsIncompleteFullDashboardVisualizationOnce() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(), any(AiJsonSchema.class), any(AiCallConfig.class),
                eq("tenant"), eq("user"), eq("local"))).thenReturn(
                dashboardResolution("[]"),
                dashboardResolution("""
                        [{
                          "concept":"competência da folha",
                          "field":"competencia",
                          "label":"Competência",
                          "chartType":"line",
                          "orientation":"temporal",
                          "metricField":"salarioLiquido",
                          "metricAggregation":"sum",
                          "metricLabel":"Salário líquido",
                          "provenance":"governed payroll evidence"
                        }]
                        """));
        ObjectNode hints = objectMapper.createObjectNode();
        hints.putObject("preIntentSemanticOrientation").put("requiresFullIntentResolution", true);
        hints.putObject("semanticReconciliation").put("forceFullIntentResolution", true);
        hints.putObject("resourceDiscovery").put("artifactKind", "dashboard");

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService, objectMapper)
                .resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "Crie o dashboard salarial.", "page-builder", "praxis-dynamic-page-builder",
                                "/page-builder-ia", objectMapper.createObjectNode(), null, "openai", "gpt-5.6-terra",
                                "test-key", "session-full-repair", "turn-full-repair", List.of(), null, List.of(), hints),
                        "Crie o dashboard salarial.", objectMapper.createObjectNode(), null,
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/vw-analytics-folha-pagamento", "post", "/schemas/filtered/payroll",
                                "/api/human-resources/vw-analytics-folha-pagamento/filter", "post", 0.93d,
                                "semantic payroll analytics", List.of("semantic-retrieval", "stats-capabilities-verified"))),
                        componentCapabilities(), "tenant", "user", "local")
                .orElseThrow();

        assertThat(promptCaptor.getAllValues()).hasSize(2);
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("previous full resolution")
                .contains("resolved analytical artifact with empty axes");
        assertThat(result.visualizationDecision().axes()).hasSize(1);
        assertThat(result.warnings()).contains("llm-full-visualization-repair-used");
        assertThat(result.providerInvocations()).extracting(AiProviderInvocationTelemetry::phase)
                .containsExactly("intent_full", "intent_full_visualization_repair");
    }

    private JsonNode dashboardResolution(String axes) throws Exception {
        return objectMapper.readTree("""
                {
                  "resolved":true,
                  "semanticIntentClass":"component_authoring",
                  "operationKind":"create",
                  "artifactKind":"dashboard",
                  "changeKind":"create_artifact",
                  "selectedResourcePath":"/api/human-resources/vw-analytics-folha-pagamento",
                  "resourceSearchQuery":null,
                  "followUpKind":"none",
                  "requiresGovernedAuthoring":false,
                  "assistantMessage":"Vou preparar o dashboard.",
                  "visualizationDecision":{
                    "schemaVersion":"praxis-visualization-decision.v1",
                    "intent":"dashboard salarial",
                    "layoutKind":"dashboard",
                    "primaryComponent":"praxis-chart",
                    "axes":%s,
                    "includeSummary":true,
                    "includeDetailTable":true,
                    "excludedComponentIds":[],
                    "includeFilters":true,
                    "includeKpis":true,
                    "provenance":"governed evidence"
                  },
                  "consultativeRetrievalPlan":null,
                  "quickReplies":[],
                  "clarificationQuestions":[],
                  "warnings":[],
                  "queryConstraints":{"filters":[]}
                }
                """.formatted(axes));
    }

    @Test
    void resolveCanUseFastLlmIntentPassWhenCompactEvidenceIsSufficient() throws Exception {
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
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "chart",
                  "changeKind": "create_chart",
                  "selectedResourcePath": "/api/risk-intelligence/vw-indicadores-incidentes",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Criei uma pre-visualizacao com um grafico simples por Severidade.",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                    "intent": "incident-severity-chart",
                    "layoutKind": "single_chart",
                    "primaryComponent": "praxis-chart",
                    "axes": [
                      {
                        "concept": "severidade",
                        "field": "severidade",
                        "label": "Severidade",
                        "chartType": "bar",
                        "orientation": "vertical",
                        "metricAggregation": "count",
                        "metricField": null,
                        "metricLabel": "Total",
                        "provenance": "llm-authored-semantic-axis"
                      }
                    ],
                    "includeSummary": false,
                    "includeDetailTable": false,
                    "excludedComponentIds": ["praxis-table", "praxis-kpi"],
                    "includeFilters": false,
                    "includeKpis": false,
                    "provenance": "llm-authored-semantic-decision"
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "authoringScopePolicy": {
                    "kind": "praxis.authoring-scope-policy.v1",
                    "outOfScopeResponseType": "info",
                    "fallbackTone": "friendly-guided"
                  },
                  "resourceDiscovery": {
                    "artifactKind": "chart",
                    "resourceSearchFocus": {
                      "primaryBusinessEntity": "incidentes",
                      "supportingConcepts": ["severidade"],
                      "desiredSurface": "grafico simples",
                      "uncertainty": "",
                      "rationale": "Decisao semantica do planejamento pre-intent."
                    }
                  }
                }
                """);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie apenas um grafico de barras simples de incidentes por severidade. Use a fonte Indicadores Incidentes. Nao crie tabela, filtros nem KPIs.",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "Crie apenas um grafico de barras simples de incidentes por severidade. Use a fonte Indicadores Incidentes. Nao crie tabela, filtros nem KPIs.",
                objectMapper.createObjectNode(),
                null,
                List.of(
                        new AgenticAuthoringCandidate(
                                "/api/risk-intelligence/vw-indicadores-incidentes",
                                "GET",
                                "/schemas/filtered/risk-intelligence.vw-indicadores-incidentes",
                                "/api/risk-intelligence/vw-indicadores-incidentes",
                                "POST",
                                0.98d,
                                "Fonte indicada explicitamente pelo usuario.",
                                List.of("explicit-source-match"),
                                AgenticAuthoringEvidenceBundle.of(
                                        "explicit_source_match",
                                        List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                                "api_metadata",
                                                "retrieved_candidate",
                                                "/api/risk-intelligence/vw-indicadores-incidentes",
                                                "Indicadores de incidentes com campo severidade.",
                                                0.92d,
                                                List.of("indicadores", "incidentes", "severidade"),
                                                "",
                                                "",
                                                "")))),
                        weakCandidate("/api/risk-intelligence/ameacas"),
                        weakCandidate("/api/human-resources/funcionarios"),
                        weakCandidate("/api/assets/equipamentos")),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-fast-intent-context.v1")
                .contains("Decide from the user's meaning, not from backend keywords.")
                .contains("whose meaning depends on multiple coordinated analytical regions")
                .contains("do not downgrade a coordinated dashboard to page or accordion")
                .contains("where analytics are not the dominant requested outcome")
                .contains("which governed data can be used to create a table, form, chart, dashboard, page or other component")
                .contains("Do not select a weak resource or ask for a materialization confirmation")
                .contains("route_shared_rule_authoring")
                .contains("blocked suppliers cannot be selected in purchases")
                .contains("Show a blocked-supplier badge in this local table")
                .contains("Which governed supplier data can I use in a dashboard?")
                .contains("Never reinterpret a requested business rule as a dashboard or page")
                .contains("\"authoringScopePolicy\"")
                .contains("\"outOfScopeResponseType\" : \"info\"")
                .contains("\"semanticRetrievalIntent\"")
                .contains("\"artifactKind\" : \"chart\"")
                .contains("\"primaryBusinessEntity\" : \"incidentes\"")
                .contains("loose instruction, assistant meta request, greeting, or unrelated ask")
                .contains("\"candidateResources\"")
                .contains("/api/risk-intelligence/vw-indicadores-incidentes")
                .contains("Indicadores de incidentes com campo severidade.")
                .doesNotContain("/api/human-resources/funcionarios")
                .doesNotContain("contextBundle:");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(1800);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(12);
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("\"requiresGovernedAuthoring\"")
                .contains("\"required\"")
                .contains("requiresGovernedAuthoring");
        assertStrictSchemaCompatible(objectMapper.readTree(schemaCaptor.getValue().jsonSchema()), "$");
        assertThat(result.requiresGovernedAuthoring()).isFalse();
        assertThat(result.artifactKind()).isEqualTo("chart");
        assertThat(result.selectedResourcePath())
                .isEqualTo("/api/risk-intelligence/vw-indicadores-incidentes");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().layoutKind()).isEqualTo("single_chart");
        assertThat(result.visualizationDecision().includeDetailTable()).isFalse();
        assertThat(result.visualizationDecision().includeFilters()).isFalse();
        assertThat(result.visualizationDecision().includeKpis()).isFalse();
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void usesFocusedIntentPassForLlmPlannedFilteredTable() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "component_authoring",
                  "operationKind": "create",
                  "artifactKind": "table",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": "/api/human-resources/funcionarios",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou preparar a tabela de funcionários com o recorte solicitado.",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                    "intent": "employee-department-table",
                    "layoutKind": "single_table",
                    "primaryComponent": "praxis-table",
                    "primaryComponentId": "praxis-table",
                    "axes": [],
                    "includeSummary": false,
                    "includeDetailTable": true,
                    "excludedComponentIds": [],
                    "includeFilters": true,
                    "includeKpis": false,
                    "provenance": "llm-planned-focused-resource"
                  },
                  "queryConstraints": {
                    "appliesToDataSelection": true,
                    "filters": [{
                      "concept": "área de tecnologia",
                      "field": "departamento",
                      "operator": "in",
                      "value": "tecnologia"
                    }]
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode resourceDiscovery = contextHints.putObject("resourceDiscovery");
        resourceDiscovery.put("artifactKind", "table");
        ObjectNode discoveredCandidate = resourceDiscovery.putArray("candidates").addObject();
        discoveredCandidate.put("resourcePath", "/api/human-resources/funcionarios");
        discoveredCandidate.put("operation", "post");
        discoveredCandidate.put("submitUrl", "/api/human-resources/funcionarios/filter");
        discoveredCandidate.put("submitMethod", "post");
        discoveredCandidate.put("reason", "Fonte governada que exibe os registros de funcionários.");
        discoveredCandidate.putArray("evidence")
                .add("tool-search-api-resources")
                .add("semantic-retrieval");
        ObjectNode orientation = contextHints.putObject("preIntentSemanticOrientation");
        orientation.put("schemaVersion", "praxis-agentic-authoring-pre-intent-orientation-context.v1");
        orientation.put("semanticIntentClass", "authoring_or_other");
        orientation.put("artifactKind", "table");
        orientation.put("requiresFullIntentResolution", true);
        orientation.put("source", "llm_pre_intent_tool_plan");
        orientation.set("queryConstraints", objectMapper.readTree("""
                {"appliesToDataSelection":true,
                "filters":[{"concept":"área de tecnologia","field":"departamento",
                "operator":"in","value":"tecnologia"}]}
                """));

        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "monta pra mim uma tabela só com o pessoal da área de tecnologia",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-5-mini",
                "test-key",
                "session-focused-table",
                "turn-focused-table",
                List.of(),
                null,
                List.of(),
                contextHints);

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        request,
                        "Crie uma tabela de funcionários e preserve as colunas. "
                                + request.userPrompt(),
                        objectMapper.createObjectNode(),
                        null,
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/funcionarios",
                                "post",
                                "",
                                "/api/human-resources/funcionarios/filter",
                                "post",
                                0.98d,
                                "Fonte governada que exibe os registros de funcionários.",
                                List.of("tool-search-api-resources", "semantic-retrieval"))),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-focused-resource-context.v1")
                .contains("one focused, LLM-planned Praxis table-authoring request")
                .contains("\"queryConstraints\"")
                .contains("\"appliesToDataSelection\":true")
                .contains("área de tecnologia")
                .contains("/api/human-resources/funcionarios")
                .contains("header, label, renderer, formatting, composed-cell")
                .contains("later governed field and live option-value tools")
                .doesNotContain("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(1800);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(24);
        assertThat(result.resolved()).isTrue();
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.selectedResourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.queryConstraints().path("appliesToDataSelection").asBoolean()).isTrue();
        assertThat(result.queryConstraints().path("filters").get(0).path("value").asText())
                .isEqualTo("tecnologia");
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void usesCompactIntentPassForGovernedLiveOptionFieldRefinement() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "component_authoring",
                  "operationKind": "create",
                  "artifactKind": "table",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": "/api/human-resources/funcionarios",
                  "resourceSearchQuery": null,
                  "followUpKind": "refinement",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou preparar a tabela com o filtro governado.",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                    "intent": "employee-department-table",
                    "layoutKind": "single_table",
                    "primaryComponent": "praxis-table",
                    "axes": [],
                    "includeSummary": false,
                    "includeDetailTable": true,
                    "excludedComponentIds": [],
                    "includeFilters": true,
                    "includeKpis": false,
                    "provenance": "governed-live-option-field"
                  },
                  "queryConstraints": {
                    "appliesToDataSelection": true,
                    "filters": [{
                      "concept": "departamentos de atuação",
                      "field": "departamentoIdsIn",
                      "operator": "in",
                      "value": ["engenharia", "inteligência artificial"]
                    }]
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));
        AgenticAuthoringSemanticDecision activeDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-employee-table",
                "create",
                "table",
                "create_artifact",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        "/api/human-resources/funcionarios",
                        "post",
                        "",
                        "/api/human-resources/funcionarios/filter",
                        "post"),
                null,
                new AgenticAuthoringSemanticDecision.RetrievalEvidence(
                        "semantic_retrieval",
                        List.of("tool-search-api-resources"),
                        1),
                false,
                "",
                "",
                "");
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode grounding = contextHints.putObject("liveOptionFieldGrounding");
        grounding.put("schemaVersion", "praxis-live-option-field-grounding.v1");
        grounding.put("resourcePath", "/api/human-resources/funcionarios");
        grounding.set("originalPredicate", objectMapper.readTree("""
                {"concept":"departamentos de atuação","field":"departamento","operator":"in",
                 "value":["engenharia","inteligência artificial"]}
                """));
        grounding.set("candidates", objectMapper.readTree("""
                [{"canonicalFilterField":"departamentoIdsIn","label":"Departamentos",
                  "description":"Conjunto de departamentos organizacionais.","optionSourceKey":"department",
                  "optionResourcePath":"/api/human-resources/departamentos","multiple":true}]
                """));
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "preciso de uma tabela com os funcionários dos departamentos de engenharia e inteligência artificial",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-5-mini",
                "test-key",
                "session-live-option",
                "turn-live-option",
                List.of(),
                null,
                List.of(),
                contextHints,
                activeDecision);

        DomainCatalogPromptContextService liveOptionDomainContextService =
                Mockito.mock(DomainCatalogPromptContextService.class);
        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper,
                        liveOptionDomainContextService)
                .resolve(
                        request,
                        request.userPrompt(),
                        objectMapper.createObjectNode(),
                        null,
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/funcionarios",
                                "post",
                                "",
                                "/api/human-resources/funcionarios/filter",
                                "post",
                                0.98d,
                                "Fonte governada de funcionários.",
                                List.of("tool-search-api-resources"))),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-live-option-refinement-context.v1")
                .contains("focused semantic refinement")
                .contains("\"activeSemanticDecision\"")
                .contains("\"liveOptionFieldGrounding\"")
                .contains("\"canonicalFilterField\":\"departamentoIdsIn\"")
                .contains("queryConstraints.appliesToDataSelection=true");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(1800);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(24);
        assertThat(configCaptor.getValue().getModel()).isEqualTo("gpt-5.6-luna");
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        assertThat(result.queryConstraints().path("appliesToDataSelection").asBoolean()).isTrue();
        assertThat(result.queryConstraints().path("filters").get(0).path("field").asText())
                .isEqualTo("departamentoIdsIn");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
        Mockito.verifyNoInteractions(liveOptionDomainContextService);
    }

    @Test
    void exposesOnlyTheTerminalValueGroundingStageAfterTheCanonicalFieldIsKnown() {
        AgenticAuthoringSemanticDecision activeDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-employee-table",
                "create",
                "table",
                "create_artifact",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        "/api/human-resources/funcionarios",
                        "post",
                        "",
                        "/api/human-resources/funcionarios/filter",
                        "post"),
                null,
                null,
                false,
                "",
                "",
                "");
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.putObject("liveOptionFieldGrounding")
                .put("canonicalFilterField", "departamentoIdsIn");
        ObjectNode valueGrounding = contextHints.putObject("liveOptionValueGrounding");
        valueGrounding.put("canonicalFilterField", "departamentoIdsIn");
        valueGrounding.put("exhaustive", true);
        valueGrounding.putArray("candidates")
                .addObject()
                .put("id", 25)
                .put("label", "Aperture Science - Engenharia");
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "monte uma tabela do pessoal de engenharia",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-5-mini",
                "test-key",
                "session-live-values",
                "turn-live-values",
                List.of(),
                null,
                List.of(),
                contextHints,
                activeDecision);
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "",
                "/api/human-resources/funcionarios/filter",
                "post",
                0.98d,
                "Fonte governada de funcionários.",
                List.of("tool-search-api-resources"));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        String prompt = ReflectionTestUtils.invokeMethod(
                service,
                "liveOptionRefinementPrompt",
                request,
                request.userPrompt(),
                List.of(candidate));

        assertThat(prompt)
                .contains("\"liveOptionValueGrounding\"")
                .contains("Several matching values across")
                .contains("organizations are")
                .contains("expected and are not, by themselves, ambiguity")
                .contains("every requested business category and semantic text predicate")
                .contains("remove the duplicate predicate")
                .contains("already has governed field evidence")
                .doesNotContain("\"liveOptionFieldGrounding\"");
    }

    private void assertStrictSchemaCompatible(JsonNode schema, String path) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode type = schema.path("type");
        boolean objectType = "object".equals(type.asText());
        if (type.isArray()) {
            for (JsonNode value : type) {
                objectType = objectType || "object".equals(value.asText());
            }
        }
        if (objectType) {
            assertThat(schema.path("additionalProperties").asBoolean(true))
                    .as(path + " must be closed")
                    .isFalse();
            Set<String> properties = new HashSet<>();
            schema.path("properties").fieldNames().forEachRemaining(properties::add);
            Set<String> required = new HashSet<>();
            schema.path("required").forEach(value -> required.add(value.asText()));
            assertThat(required)
                    .as(path + " must require every property")
                    .containsExactlyInAnyOrderElementsOf(properties);
        }
        schema.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                assertStrictSchemaCompatible(value, path + "." + entry.getKey());
            } else if (value.isArray()) {
                for (int index = 0; index < value.size(); index++) {
                    assertStrictSchemaCompatible(value.get(index), path + "." + entry.getKey() + "[" + index + "]");
                }
            }
        });
    }

    @Test
    void resolveUsesRankedCompactCapabilitiesForAGovernedSelectedComponentRefinement() throws Exception {
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
                  "matchesSelectedComponentScope": true,
                  "semanticIntentClass": "component_authoring",
                  "operationKind": "modify",
                  "artifactKind": "table",
                  "changeKind": "column.add",
                  "followUpKind": "refinement",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Adicionei a coluna salário mantendo as colunas atuais.",
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringSemanticDecision activeDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-add-email",
                "modify",
                "table",
                "column.add",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        "/api/human-resources/funcionarios",
                        "get",
                        "",
                        "/api/human-resources/funcionarios",
                        "get"),
                null,
                new AgenticAuthoringSemanticDecision.RetrievalEvidence(
                        "current_page",
                        List.of("current-page-target-resource"),
                        1),
                false,
                "",
                "",
                "");
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "Agora adicione também a coluna salário sem remover nenhuma das anteriores.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                "funcionarios-table",
                "openai",
                "gpt-4.1-mini",
                "test-key",
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "user-1",
                                "user",
                                "Adicione a coluna e-mail à tabela de funcionários.",
                                "2026-07-15T20:00:00Z"),
                        new AgenticAuthoringConversationMessage(
                                "assistant-1",
                                "assistant",
                                "A coluna e-mail foi adicionada.",
                                "2026-07-15T20:00:01Z")),
                null,
                List.of(),
                objectMapper.createObjectNode(),
                activeDecision);
        AgenticAuthoringComponentCapabilitiesResult capabilities =
                new AgenticAuthoringComponentCapabilitiesService().listCapabilities();

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        request,
                        request.userPrompt(),
                        objectMapper.createObjectNode(),
                        new AgenticAuthoringTarget(
                                "funcionarios-table",
                                "praxis-table",
                                "/api/human-resources/funcionarios",
                                "",
                                "/api/human-resources/funcionarios",
                                "get"),
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/funcionarios",
                                "get",
                                "",
                                "/api/human-resources/funcionarios",
                                "get",
                                0.97d,
                                "resource preserved from existing component target",
                                List.of("current-page-target-resource"))),
                        capabilities,
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-targeted-component-intent-context.v1")
                .contains("\"activeSemanticDecision\"")
                .contains("\"recentConversation\"")
                .contains("\"governedCapabilities\"")
                .contains("\"changeKind\" : \"column.add\"")
                .contains("semanticEffect\" : \"Adicionar coluna")
                .contains("Adding a new schema-backed item is different from formatting, moving")
                .contains("never from keywords, regexes or capability order")
                .contains("current userPrompt is authoritative")
                .contains("row-dependent visual outcome governed by a condition")
                .contains("followUpKind=\"new_instruction\"")
                .doesNotContain("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(promptCaptor.getValue().length()).isLessThan(12_000);
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("matchesSelectedComponentScope")
                .contains("column.add")
                .contains("unknown")
                .doesNotContain("visualizationDecision");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(900);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(8);
        assertThat(result.changeKind()).isEqualTo("column.add");
        assertThat(result.warnings()).contains("llm-compact-targeted-component-intent-used");
        assertThat(result.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("targeted_component_intent", "success"));
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void resolvesNaturalLocalUndoThroughTheSemanticSchemaAndDeclaredClientActionCatalog() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiJsonSchema> schemaCaptor = ArgumentCaptor.forClass(AiJsonSchema.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                schemaCaptor.capture(),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "matchesDeclaredAction": true,
                  "actionKind": "local-undo",
                  "assistantMessage": "Vou desfazer somente a última alteração local."
                }
                """));
        AgenticAuthoringIntentResolutionRequest request = targetedTableUndoRequest(true);

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        request,
                        request.userPrompt(),
                        objectMapper.createObjectNode(),
                        targetedTableTarget(),
                        List.of(),
                        new AgenticAuthoringComponentCapabilitiesService().listCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("\"declaredClientActions\"")
                .contains("\"kind\" : \"local-undo\"")
                .contains("\"userPrompt\" : \"Desfaz só a última mudança e mantém todas as anteriores.\"")
                .doesNotContain("Crie uma tabela de funcionários")
                .contains("primary meaning of the current userPrompt")
                .contains("Availability governs execution only");
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("\"local-undo\"")
                .contains("matchesDeclaredAction");
        assertThat(result.resolved()).isTrue();
        assertThat(result.operationKind()).isEqualTo("undo");
        assertThat(result.artifactKind()).isEqualTo("component");
        assertThat(result.changeKind()).isEqualTo("undo_last_local_change");
        assertThat(result.warnings()).contains("llm-declared-client-action-intent-used");
    }

    @Test
    void targetedComponentIntentProviderFailureDoesNotCascadeIntoTheLargeGenericResolver() {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenThrow(AiProviderCallException.timeout(
                "openai",
                new RuntimeException("targeted component timeout")));
        AgenticAuthoringIntentResolutionRequest request = targetedTableRefinementRequest();
        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        request,
                        request.userPrompt(),
                        objectMapper.createObjectNode(),
                        targetedTableTarget(),
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/funcionarios",
                                "get",
                                "",
                                "/api/human-resources/funcionarios",
                                "get",
                                0.97d,
                                "resource preserved from existing component target",
                                List.of("current-page-target-resource"))),
                        new AgenticAuthoringComponentCapabilitiesService().listCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(result.resolved()).isFalse();
        assertThat(result.warnings())
                .contains("llm-provider-timeout")
                .doesNotContain("llm-fast-intent-resolution-used");
        assertThat(result.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("targeted_component_intent", "failure"),
                        org.assertj.core.groups.Tuple.tuple("targeted_component_intent", "failure"));
        Mockito.verify(providerManagementService, Mockito.times(2)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void targetedComponentIntentRetriesATransientFailureWithinTheCompactSemanticContract() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenThrow(AiProviderCallException.transport(
                        "openai",
                        new RuntimeException("connection reset")))
                .thenReturn(objectMapper.readTree("""
                        {
                          "matchesSelectedComponentScope": true,
                          "semanticIntentClass": "component_authoring",
                          "operationKind": "modify",
                          "artifactKind": "table",
                          "changeKind": "column.add",
                          "followUpKind": "refinement",
                          "requiresGovernedAuthoring": false,
                          "assistantMessage": "Vou formatar a coluna salário.",
                          "clarificationQuestions": [],
                          "warnings": []
                        }
                        """));
        AgenticAuthoringIntentResolutionRequest request = targetedTableRefinementRequest();

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        request,
                        request.userPrompt(),
                        objectMapper.createObjectNode(),
                        targetedTableTarget(),
                        List.of(),
                        new AgenticAuthoringComponentCapabilitiesService().listCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(result.resolved()).isTrue();
        assertThat(result.changeKind()).isEqualTo("column.add");
        assertThat(result.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::attempt,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("targeted_component_intent", 1, "failure"),
                        org.assertj.core.groups.Tuple.tuple("targeted_component_intent", 2, "success"));
    }

    @Test
    void targetedComponentIntentFallsThroughWhenTheRequestSemanticallyTargetsANewArtifact() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(
                        objectMapper.readTree("""
                                {
                                  "matchesSelectedComponentScope": false,
                                  "semanticIntentClass": "out_of_scope",
                                  "operationKind": "unknown",
                                  "artifactKind": "unknown",
                                  "changeKind": "unknown",
                                  "followUpKind": "new_instruction",
                                  "requiresGovernedAuthoring": false,
                                  "assistantMessage": "",
                                  "clarificationQuestions": [],
                                  "warnings": []
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "resolved": true,
                                  "semanticIntentClass": "component_authoring",
                                  "operationKind": "create",
                                  "artifactKind": "form",
                                  "changeKind": "create_artifact",
                                  "selectedResourcePath": "/api/human-resources/funcionarios",
                                  "resourceSearchQuery": null,
                                  "followUpKind": "new_instruction",
                                  "requiresGovernedAuthoring": false,
                                  "assistantMessage": "Vou preparar um novo formulário de funcionários para revisão.",
                                  "visualizationDecision": null,
                                  "consultativeRetrievalPlan": null,
                                  "quickReplies": [],
                                  "clarificationQuestions": [],
                                  "warnings": []
                                }
                                """));
        AgenticAuthoringIntentResolutionRequest refinement = targetedTableRefinementRequest();
        AgenticAuthoringIntentResolutionRequest request = new AgenticAuthoringIntentResolutionRequest(
                "Crie também um novo formulário de funcionários.",
                refinement.targetApp(),
                refinement.targetComponentId(),
                refinement.currentRoute(),
                refinement.currentPage(),
                refinement.selectedWidgetKey(),
                refinement.provider(),
                refinement.model(),
                refinement.apiKey(),
                refinement.sessionId(),
                refinement.clientTurnId(),
                refinement.conversationMessages(),
                refinement.pendingClarification(),
                refinement.attachmentSummaries(),
                refinement.contextHints(),
                refinement.activeSemanticDecision());

        AgenticAuthoringLlmIntentResolution result = new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper)
                .resolve(
                        request,
                        request.userPrompt(),
                        objectMapper.createObjectNode(),
                        targetedTableTarget(),
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/funcionarios",
                                "get",
                                "",
                                "/api/human-resources/funcionarios",
                                "get",
                                0.97d,
                                "resource preserved from existing component target",
                                List.of("current-page-target-resource"))),
                        new AgenticAuthoringComponentCapabilitiesService().listCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(promptCaptor.getAllValues()).hasSize(3);
        assertThat(promptCaptor.getAllValues().get(0))
                .contains("praxis-targeted-component-intent-context.v1");
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(promptCaptor.getAllValues().get(2))
                .contains("Praxis");
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("form");
        assertThat(result.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("targeted_component_intent", "success"),
                        org.assertj.core.groups.Tuple.tuple("intent_fast", "success"),
                        org.assertj.core.groups.Tuple.tuple("intent_full", "success"));
    }

    @Test
    void platformGuidanceSemanticClassNormalizesAnInconsistentTechnicalTuple() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiJsonSchema> schemaCaptor = ArgumentCaptor.forClass(AiJsonSchema.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                schemaCaptor.capture(),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "platform_guidance",
                  "operationKind": "explain",
                  "artifactKind": "unknown",
                  "changeKind": "unknown",
                  "selectedResourcePath": "/api/payroll/salary-history",
                  "resourceSearchQuery": "recursos disponiveis",
                  "followUpKind": "unknown",
                  "requiresGovernedAuthoring": true,
                  "assistantMessage": "Aqui você pode criar formulários, tabelas, gráficos, filtros e páginas descrevendo sua intenção em linguagem natural.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [
                    {"id":"form","kind":"suggestion","label":"Criar formulário","prompt":"Crie um formulário"},
                    {"id":"table","kind":"suggestion","label":"Criar tabela","prompt":"Crie uma tabela"},
                    {"id":"chart","kind":"suggestion","label":"Criar gráfico","prompt":"Crie um gráfico"}
                  ],
                  "clarificationQuestions": ["Qual recurso?"],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "O que posso fazer aqui?",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-4.1-mini",
                        "test-key",
                        "session-platform-guidance",
                        "turn-platform-guidance",
                        List.of(),
                        null,
                        List.of(),
                        objectMapper.createObjectNode()),
                "O que posso fazer aqui?",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("semanticIntentClass")
                .contains("platform_guidance")
                .contains("in-scope platform guidance")
                .contains("Do not start resource discovery");
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("semanticIntentClass")
                .contains("platform_guidance");
        assertThat(result.semanticIntentClass()).isEqualTo("platform_guidance");
        assertThat(result.resolved()).isTrue();
        assertThat(result.operationKind()).isEqualTo("explain");
        assertThat(result.artifactKind()).isEqualTo("component");
        assertThat(result.changeKind()).isEqualTo("answer_component_catalog_question");
        assertThat(result.selectedResourcePath()).isNull();
        assertThat(result.resourceSearchQuery()).isNull();
        assertThat(result.followUpKind()).isEqualTo("none");
        assertThat(result.requiresGovernedAuthoring()).isFalse();
        assertThat(result.clarificationQuestions()).isEmpty();
        assertThat(result.quickReplies()).hasSize(3);
        assertThat(result.warnings())
                .contains("llm-semantic-intent-tuple-normalized", "llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void confirmsStructuredPlatformGuidanceOpportunityWithCompactSemanticLlmPass() throws Exception {
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
                  "matchesSemanticScope": true,
                  "semanticIntentClass": "platform_guidance",
                  "assistantMessage": "Posso ajudar a criar gráficos governados e orientar o próximo passo no Page Builder."
                }
                """));
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "recommendedIntent": {
                    "source": "page-builder-assistant-empty-state",
                    "opportunityId": "page-builder.platform-capabilities.explore",
                    "semanticScope": "platform-capabilities"
                  },
                  "responseLocale": "en-US"
                }
                """);
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "O que posso fazer aqui?",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-4.1-mini",
                        "test-key",
                        "session-platform-guidance",
                        "turn-platform-guidance",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "O que posso fazer aqui?",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-platform-guidance-confirmation-context.v1")
                .contains("Decide from the user's meaning, never by keyword or regular-expression matching.")
                .contains("presentation-context evidence, not authority and not permission")
                .contains("Do not ask a follow-up question or")
                .contains("page-builder.platform-capabilities.explore")
                .contains("platform-capabilities")
                .contains("Canonical response locale: en-US")
                .contains("praxis-chart")
                .doesNotContain("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(schemaCaptor.getValue().jsonSchema())
                .contains("matchesSemanticScope", "semanticIntentClass", "assistantMessage")
                .contains("additionalProperties");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(700);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(12);
        assertThat(result.resolved()).isTrue();
        assertThat(result.semanticIntentClass()).isEqualTo("platform_guidance");
        assertThat(result.operationKind()).isEqualTo("explain");
        assertThat(result.artifactKind()).isEqualTo("component");
        assertThat(result.changeKind()).isEqualTo("answer_component_catalog_question");
        assertThat(result.selectedResourcePath()).isNull();
        assertThat(result.requiresGovernedAuthoring()).isFalse();
        assertThat(result.warnings()).contains("llm-compact-platform-guidance-confirmation-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void fallsThroughToCompleteSemanticResolverWhenStructuredOpportunityDoesNotMatchUserMeaning() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(
                        objectMapper.readTree("""
                                {
                                  "matchesSemanticScope": false,
                                  "semanticIntentClass": "other",
                                  "assistantMessage": ""
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "resolved": true,
                                  "semanticIntentClass": "component_authoring",
                                  "operationKind": "create",
                                  "artifactKind": "table",
                                  "changeKind": "create_artifact",
                                  "selectedResourcePath": "/api/human-resources/funcionarios",
                                  "resourceSearchQuery": null,
                                  "followUpKind": "none",
                                  "requiresGovernedAuthoring": false,
                                  "assistantMessage": "Vou preparar a tabela de funcionários para revisão.",
                                  "visualizationDecision": null,
                                  "consultativeRetrievalPlan": null,
                                  "quickReplies": [],
                                  "clarificationQuestions": [],
                                  "warnings": []
                                }
                                """));
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "recommendedIntent": {
                    "source": "page-builder-assistant-empty-state",
                    "opportunityId": "page-builder.platform-capabilities.explore",
                    "semanticScope": "platform-capabilities"
                  }
                }
                """);
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma tabela de funcionários",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-4.1-mini",
                        "test-key",
                        "session-form",
                        "turn-form",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "Crie uma tabela de funcionários",
                objectMapper.createObjectNode(),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "POST",
                        "/schemas/filtered/human-resources.funcionarios",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.98d,
                        "Fonte governada indicada pelo contexto.",
                        List.of("context-hint"))),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getAllValues()).hasSize(2);
        assertThat(promptCaptor.getAllValues().get(0))
                .contains("praxis-platform-guidance-confirmation-context.v1");
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(result.semanticIntentClass()).isEqualTo("component_authoring");
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.selectedResourcePath()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(result.warnings())
                .contains("llm-fast-intent-resolution-used")
                .doesNotContain("llm-compact-platform-guidance-confirmation-used");
        assertThat(result.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("platform_guidance_confirmation", "success"),
                        org.assertj.core.groups.Tuple.tuple("intent_fast", "success"));
        Mockito.verify(providerManagementService, Mockito.times(2)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void componentAuthoringSemanticClassNormalizesMaterializeComponentTuple() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "component_authoring",
                  "operationKind": "create",
                  "artifactKind": "table",
                  "changeKind": "materialize_component",
                  "selectedResourcePath": "/api/human-resources/employees",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": true,
                  "assistantMessage": "Vou criar a tabela de funcionários.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma tabela de funcionários",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-4.1-mini",
                        "test-key"),
                "Crie uma tabela de funcionários",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(result.semanticIntentClass()).isEqualTo("component_authoring");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.warnings())
                .contains("llm-semantic-intent-tuple-normalized", "llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void componentAuthoringSemanticClassNormalizesAuthorComponentCreationTuple() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "component_authoring",
                  "operationKind": "create",
                  "artifactKind": "table",
                  "changeKind": "author_component",
                  "selectedResourcePath": "/api/human-resources/employees",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": true,
                  "assistantMessage": "Vou criar a tabela de funcionários.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma tabela de funcionários",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key"),
                "Crie uma tabela de funcionários",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(result.semanticIntentClass()).isEqualTo("component_authoring");
        assertThat(result.changeKind()).isEqualTo("create_artifact");
        assertThat(result.warnings())
                .contains("llm-semantic-intent-tuple-normalized", "llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void dashboardAxesNormalizeCrudPrimaryComponentToChart() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "component_authoring",
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": "/api/human-resources/funcionarios",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou montar um painel de acompanhamento de funcionários.",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-visualization-decision.v1",
                    "intent": "dashboard",
                    "layoutKind": "dashboard_layout",
                    "primaryComponent": "praxis-crud",
                    "axes": [
                      {
                        "concept": "funcionários",
                        "field": "dataAdmissao",
                        "label": "Data de Admissão",
                        "chartType": "line",
                        "orientation": "temporal",
                        "metricAggregation": "count",
                        "metricField": "id",
                        "metricLabel": "Admissões",
                        "provenance": "semantic_retrieval"
                      }
                    ],
                    "includeSummary": true,
                    "includeDetailTable": true,
                    "excludedComponentIds": ["praxis-tabs"],
                    "includeFilters": true,
                    "includeKpis": true,
                    "provenance": "semantic_retrieval + user_prompt"
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma tela bonita para acompanhar funcionários.",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key"),
                "Crie uma tela bonita para acompanhar funcionários.",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(result.resolved()).isTrue();
        assertThat(result.artifactKind()).isEqualTo("dashboard");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().primaryComponent()).isEqualTo("praxis-chart");
        assertThat(result.visualizationDecision().axes()).hasSize(1);
        assertThat(result.visualizationDecision().provenance())
                .contains("canonical-component-alignment");
        assertThat(result.warnings())
                .contains(
                        "llm-visualization-primary-component-normalized",
                        "llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void sharedRuleSemanticClassNormalizesVisualTupleWithoutDiscardingResourceDiscoveryIntent() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "semanticIntentClass": "shared_rule_authoring",
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "route_shared_rule_authoring",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": "fornecedor bloqueado compras",
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": true,
                  "assistantMessage": "Vou preparar a regra governada de elegibilidade de fornecedores.",
                  "visualizationDecision": {
                    "artifactKind": "dashboard",
                    "primaryComponent": "praxis-chart",
                    "layoutKind": "dashboard",
                    "axes": []
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key"),
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(result.semanticIntentClass()).isEqualTo("shared_rule_authoring");
        assertThat(result.operationKind()).isEqualTo("create");
        assertThat(result.artifactKind()).isEqualTo("unknown");
        assertThat(result.changeKind()).isEqualTo("route_shared_rule_authoring");
        assertThat(result.requiresGovernedAuthoring()).isTrue();
        assertThat(result.resourceSearchQuery()).isEqualTo("fornecedor bloqueado compras");
        assertThat(result.visualizationDecision()).isNull();
        assertThat(result.warnings())
                .contains("llm-semantic-intent-tuple-normalized");
    }

    @Test
    void semanticReconciliationForcesTheFullIntentPass() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "table",
                  "changeKind": "create_table",
                  "selectedResourcePath": "/api/human-resources/funcionarios",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou criar a tabela governada.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode reconciliation = contextHints.putObject("semanticReconciliation");
        reconciliation.put("forceFullIntentResolution", true);
        reconciliation.put("plannedArtifactKind", "table");
        reconciliation.put("observedArtifactKind", "page");
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma tabela de funcionários",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "Crie uma tabela de funcionários",
                objectMapper.createObjectNode(),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "GET",
                        "/schemas/filtered/human-resources.funcionarios",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.98d,
                        "Fonte governada selecionada.",
                        List.of("explicit-source-match"))),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("contextBundle:")
                .contains("semanticReconciliation")
                .doesNotContain("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(4096);
        assertThat(result.artifactKind()).isEqualTo("table");
        assertThat(result.warnings()).doesNotContain("llm-fast-intent-resolution-used");
    }

    @Test
    void resolveRejectsIncompleteIntentPayloadMissingGovernedAuthoringFlag() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": "/api/procurement/suppliers",
                  "resourceSearchQuery": "fornecedor bloqueado compras",
                  "followUpKind": "none",
                  "assistantMessage": "Entendi: voce quer uma regra governada de elegibilidade para fornecedores.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        assertThat(service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        objectMapper.createObjectNode()),
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                objectMapper.createObjectNode(),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/procurement/suppliers",
                        "POST",
                        "/schemas/filtered?path=/api/procurement/suppliers&operation=post&schemaType=request",
                        "/api/procurement/suppliers",
                        "POST",
                        0.49d,
                        "Fornecedor candidato para compras.",
                        List.of("api-metadata", "lexical-fallback", "weak-evidence"),
                        null)),
                componentCapabilities(),
                "tenant",
                "user",
                "local")).isEmpty();
    }

    @Test
    void resolveCanUseFastLlmIntentPassForOpenApiCatalogQuestionsWithoutSelectedResource() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "explore",
                  "artifactKind": "api_catalog",
                  "changeKind": "answer_api_catalog_question",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": "fontes governadas para visualizações analíticas",
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou consultar o catálogo governado antes de sugerir gráficos.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": {
                    "schemaVersion": "praxis-agentic-authoring-consultative-retrieval-plan.v1",
                    "requiredContext": ["domain_catalog"],
                    "semanticQueries": ["fontes governadas para gráficos e painéis"],
                    "answerStrategy": "answer_with_confirmed_resources",
                    "expectedEvidence": ["api_metadata", "domain_catalog"]
                  },
                  "quickReplies": [
                    {
                      "schemaVersion": "praxis-agentic-authoring-quick-reply.v1",
                      "id": "show-confirmed-chart-sources",
                      "kind": "api_catalog_followup",
                      "label": "Ver fontes disponíveis",
                      "prompt": "Mostre as fontes confirmadas para gráficos.",
                      "intent": "api_catalog_followup"
                    }
                  ],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Entre os dados existentes, quais eu posso usar para gerar gráficos?",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        objectMapper.createObjectNode()),
                "Entre os dados existentes, quais eu posso usar para gerar gráficos?",
                objectMapper.createObjectNode(),
                null,
                List.of(weakCandidate("/api/analytics/folha-pagamento")),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-fast-intent-context.v1")
                .contains("which governed data can be used to create a table, form, chart, dashboard, page or other component");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(1800);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(12);
        assertThat(result.resolved()).isTrue();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.changeKind()).isEqualTo("answer_api_catalog_question");
        assertThat(result.selectedResourcePath()).isNull();
        assertThat(result.consultativeRetrievalPlan()).isNotNull();
        assertThat(result.quickReplies()).extracting(AgenticAuthoringQuickReply::label)
                .containsExactly("Ver fontes disponíveis");
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void resolveCanUseFastLlmIntentPassForOpenApiCatalogQuestionsWithoutCandidatesOrCapabilities() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "explore",
                  "artifactKind": "api_catalog",
                  "changeKind": "answer_api_catalog_question",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": "fontes governadas para tabelas",
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou consultar as fontes confirmadas para responder.",
                  "visualizationDecision": null,
                  "consultativeRetrievalPlan": {
                    "schemaVersion": "praxis-agentic-authoring-consultative-retrieval-plan.v1",
                    "requiredContext": ["api_metadata"],
                    "semanticQueries": ["fontes governadas para tabelas"],
                    "answerStrategy": "answer_with_confirmed_resources",
                    "expectedEvidence": ["api_metadata"]
                  },
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Eu posso criar tabelas com quais dados aqui?",
                        "page-builder",
                        "praxis-table",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        objectMapper.createObjectNode()),
                "Eu posso criar tabelas com quais dados aqui?",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                null,
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-fast-intent-context.v1")
                .contains("\"candidateResources\" : [ ]");
        assertThat(result.resolved()).isTrue();
        assertThat(result.operationKind()).isEqualTo("explore");
        assertThat(result.artifactKind()).isEqualTo("api_catalog");
        assertThat(result.changeKind()).isEqualTo("answer_api_catalog_question");
        assertThat(result.selectedResourcePath()).isNull();
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void resolveCanUseFastLlmIntentPassWhenConversationContainsOnlyCurrentPrompt() throws Exception {
        String prompt = "Crie uma pagina com accordion: dados gerais, detalhes e acoes de funcionarios.";
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "page",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": "/api/human-resources/funcionarios",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Criei uma pagina com paineis expansíveis para funcionários.",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                    "intent": "funcionarios-accordion-page",
                    "layoutKind": "accordion_layout",
                    "primaryComponent": "praxis-expansion",
                    "axes": [],
                    "includeSummary": false,
                    "includeDetailTable": false,
                    "excludedComponentIds": ["praxis-chart"],
                    "includeFilters": false,
                    "includeKpis": false,
                    "provenance": "llm-fast-intent"
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        prompt,
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(new AgenticAuthoringConversationMessage("m1", "user", prompt, "2026-05-17T10:00:00Z")),
                        null,
                        List.of(),
                        objectMapper.createObjectNode()),
                prompt,
                objectMapper.createObjectNode(),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "GET",
                        "/schemas/filtered/human-resources.funcionarios",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.98d,
                        "Fonte indicada explicitamente pelo usuario.",
                        List.of("explicit-source-match"))),
                componentCapabilitiesWithExpansion(),
                "tenant",
                "user",
                "local").orElseThrow();

        assertThat(promptCaptor.getValue())
                .contains("praxis-agentic-authoring-fast-intent-context.v1")
                .contains("For a requested page organized as accordion/acordeon/expansion panels")
                .doesNotContain("contextBundle:");
        assertThat(result.artifactKind()).isEqualTo("page");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().primaryComponent()).isEqualTo("praxis-expansion");
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void resolveCompletesFastIntentResourceWhenDistinctExplicitCandidateIsUnambiguous() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "chart",
                  "changeKind": "create_chart",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Criei uma pre-visualizacao com um grafico simples.",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                    "intent": "payroll-chart",
                    "layoutKind": "single_chart",
                    "primaryComponent": "praxis-chart",
                    "axes": [
                      {
                        "concept": "departamento",
                        "field": "departamento",
                        "label": "Departamento",
                        "chartType": "horizontal-bar",
                        "orientation": "horizontal",
                        "metricAggregation": "sum",
                        "metricField": "salarioLiquido",
                        "metricLabel": "Salario liquido",
                        "provenance": "llm-authored-semantic-axis"
                      }
                    ],
                    "includeSummary": false,
                    "includeDetailTable": false,
                    "excludedComponentIds": ["praxis-table", "praxis-kpi", "praxis-filter"],
                    "includeFilters": false,
                    "includeKpis": false,
                    "provenance": "llm-authored-semantic-decision"
                  },
                  "consultativeRetrievalPlan": null,
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "Crie apenas um grafico horizontal de folha de pagamento por departamento somando salario liquido. Use a fonte Analytics Folha Pagamento.",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        analyticsFieldContext()),
                "Crie apenas um grafico horizontal de folha de pagamento por departamento somando salario liquido. Use a fonte Analytics Folha Pagamento.",
                objectMapper.createObjectNode(),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "/schemas/filtered/human-resources.vw-analytics-folha-pagamento",
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        0.98d,
                        "Fonte indicada explicitamente pelo usuario e confirmada no catalogo.",
                        List.of("explicit-source-match", "domain-catalog-context"),
                        AgenticAuthoringEvidenceBundle.of(
                                "explicit_source_match",
                                List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                        "api_metadata",
                                        "retrieved_candidate",
                                        "/api/human-resources/vw-analytics-folha-pagamento",
                                        "Analytics de folha com departamento e salarioLiquido.",
                                        0.92d,
                                        List.of("analytics", "folha", "departamento", "salarioLiquido"),
                                        "",
                                        "",
                                        ""))))),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        String prompt = promptCaptor.getValue();
        JsonNode compactContext = objectMapper.readTree(prompt.substring(prompt.indexOf("Compact context:")
                + "Compact context:".length()).trim());
        assertThat(prompt).contains("praxis-agentic-authoring-fast-intent-context.v1");
        assertThat(compactContext.path("candidateResources")).hasSize(1);
        assertThat(compactContext.path("candidateResources").get(0).path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(compactContext.path("candidateResources").get(0).path("analyticsFields").toString())
                .contains("departamento", "salarioLiquido", "allowedAggregations")
                .doesNotContain("internalQuery");
        assertThat(result.selectedResourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.warnings()).contains("llm-fast-intent-resolution-used");
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    private ObjectNode analyticsFieldContext() {
        ObjectNode context = objectMapper.createObjectNode();
        ObjectNode candidate = context.putObject("resourceDiscovery")
                .putArray("candidates")
                .addObject();
        candidate.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        candidate.putArray("analyticsFields")
                .addObject()
                .put("field", "departamento")
                .put("groupByEligible", true);
        candidate.withArray("analyticsFields")
                .addObject()
                .put("field", "salarioLiquido")
                .put("metricFieldEligible", true)
                .putArray("allowedAggregations")
                .add("sum")
                .add("avg");
        return context;
    }

    @Test
    void resolveSendsStructuredContextBundleAndToolCatalogToProvider() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "create_chart",
                  "selectedResourcePath": "/api/vendas/pedidos",
                  "resourceSearchQuery": "pedidos de vendas para graficos",
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Encontrei pedidos como base para o grafico. Quer usar esse recurso?",
                  "visualizationDecision": {
                    "schemaVersion": "praxis-agentic-authoring-visualization-decision.v1",
                    "intent": "sales-dashboard",
                    "layoutKind": "dashboard",
                    "primaryComponent": "praxis-chart",
                    "axes": [
                      {
                        "concept": "status",
                        "field": "status",
                        "label": "Status",
                        "chartType": "bar",
                        "orientation": "vertical",
                        "metricAggregation": "count",
                        "metricField": null,
                        "metricLabel": "Total",
                        "provenance": "llm-authored-semantic-axis"
                      }
                    ],
                    "includeSummary": true,
                    "includeDetailTable": true,
                    "provenance": "llm-authored-semantic-decision"
                  },
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie painel de visualizacao de graficos",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode().put("title", "Vendas"),
                        "chart-1",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(new AgenticAuthoringConversationMessage("m1", "user", "quero um grafico", "2026-04-15T10:00:00Z")),
                        null,
                        List.of(),
                        objectMapper.readTree("""
                                {
                                  "source": "page-builder",
                                  "authoringScopePolicy": {
                                    "kind": "praxis.authoring-scope-policy.v1",
                                    "outOfScopeResponseType": "info",
                                    "fallbackTone": "friendly-guided"
                                  }
                                }
                                """)),
                "crie painel de visualizacao de graficos",
                objectMapper.createObjectNode().put("widgetCount", 1),
                null,
                List.of(new AgenticAuthoringCandidate(
                        "/api/vendas/pedidos",
                        "GET",
                        "/schemas/filtered/vendas.pedidos",
                        "/api/vendas/pedidos",
                        "POST",
                        0.93d,
                        "Pedidos parece relevante para graficos de vendas.",
                        List.of("resourceKey:vendas.pedidos"))),
                componentCapabilities(),
                "tenant",
                "user",
                "local").orElseThrow();

        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("contextBundle:");
        assertThat(prompt).contains("\"schemaVersion\" : \"praxis-agentic-authoring-context-bundle.v1\"");
        assertThat(prompt).contains("\"runtimeContext\"");
        assertThat(prompt).contains("\"hostApplication\" : \"Angular Praxis Page Builder assistant\"");
        assertThat(prompt).contains("\"retrievalContext\"");
        assertThat(prompt).contains("\"candidateResources\"");
        assertThat(prompt).contains("/api/vendas/pedidos");
        assertThat(prompt).contains("\"componentContext\"");
        assertThat(prompt).contains("\"authorableComponents\"");
        assertThat(prompt).contains("\"componentId\" : \"praxis-chart\"");
        assertThat(prompt).contains("\"platformGuide\"");
        assertThat(prompt).contains("\"authoringScopePolicy\"");
        assertThat(prompt).contains("\"outOfScopeResponseType\" : \"info\"");
        assertThat(prompt).contains("loose instruction, assistant meta request, greeting, or unrelated ask");
        assertThat(prompt).contains("\"formAuthoringPolicy\"");
        assertThat(prompt).contains("consultativeRetrievalPlan");
        assertThat(prompt).contains("depends on multiple coordinated analytical regions");
        assertThat(prompt).contains("semanticReconciliation");
        assertThat(prompt).contains("consultative platform guidance");
        assertThat(prompt).contains("which governed data can be used to create a table, form, chart, dashboard, page or other component");
        assertThat(prompt).contains("Do not treat it as immediate component creation");
        assertThat(prompt).contains("Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras");
        assertThat(prompt).contains("route_shared_rule_authoring");
        assertThat(prompt).contains("Mostre um badge de fornecedor bloqueado nesta tabela");
        assertThat(prompt).contains("Never reinterpret a requested reusable business rule as a dashboard");
        assertThat(prompt).contains("Praxis is a governed AI authoring platform");
        assertThat(prompt).contains("Visualizar metricas");
        assertThat(prompt).contains("Select visualizationDecision.primaryComponent from authorableComponents");
        assertThat(prompt).contains("\"examples\"");
        assertThat(prompt).contains("Use categoryField para o eixo X");
        assertThat(prompt).contains("\"toolCatalog\"");
        assertThat(prompt).contains("\"searchApiResources\"");
        assertThat(prompt).contains("/api/praxis/config/ai/authoring/resource-candidates");
        assertThat(prompt).contains("Avoid terse labels such as \"alimentar tela\"");
        assertThat(prompt).contains("Use assistantMessage as the natural chat reply");
        assertThat(prompt).contains("author 2 to 4 quickReplies for this exact conversation");
        assertThat(prompt).contains("contextHints.presentation.bestFor");
        assertThat(prompt).contains("resourceSearchQuery");
        assertThat(prompt).contains("visualizationDecision");
        assertThat(prompt).contains("layoutKind` to `single_chart`");
        assertThat(result.visualizationDecision()).isNotNull();
        assertThat(result.visualizationDecision().primaryComponent()).isEqualTo("praxis-chart");
        assertThat(result.visualizationDecision().axes()).hasSize(1);
        assertThat(result.visualizationDecision().axes().get(0).field()).isEqualTo("status");
        assertThat(configCaptor.getValue().getMaxTokens()).isEqualTo(4096);
        assertThat(configCaptor.getValue().getTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void resolveAddsGovernedDomainContextToContextBundleBeforeCallingProvider() throws Exception {
        DomainCatalogPromptContextService domainCatalogPromptContextService =
                Mockito.mock(DomainCatalogPromptContextService.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> contextHintsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        when(domainCatalogPromptContextService.buildPromptContext(
                eq("crie uma regra de aprovação para reembolso"),
                contextHintsCaptor.capture(),
                eq("tenant"),
                eq("local"))).thenReturn("""
                DOMAIN_CATALOG_CONTEXT
                schemaVersion: praxis.domain-catalog.context.v1
                serviceKey: praxis-service
                items:
                - [governance/policy] Reembolso exige aprovação do gestor (finance.reimbursement.approval) | visibility=mask | trainingUse=deny | ruleAuthoring=allow
                """);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "unknown",
                  "changeKind": "create_artifact",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou tratar isso como decisão governada.",
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper,
                        domainCatalogPromptContextService);
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "domainCatalog": {
                    "schemaVersion": "praxis.ai.context-hints.domain-catalog/v0.2",
                    "serviceKey": "praxis-service",
                    "resourceKey": "finance.reimbursements",
                    "intent": "authoring",
                    "policyProfile": "authoring",
                    "query": "reembolso aprovação gestor"
                  }
                }
                """);

        service.resolve(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie uma regra de aprovação para reembolso",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "crie uma regra de aprovação para reembolso",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "user",
                "local");

        String prompt = promptCaptor.getValue();
        assertThat(contextHintsCaptor.getValue().path("domainCatalog").path("policyProfile").asText())
                .isEqualTo("authoring");
        assertThat(prompt).contains("\"governedDomainContext\"");
        assertThat(prompt).contains("\"schemaVersion\" : \"praxis-agentic-authoring-governed-domain-context.v1\"");
        assertThat(prompt).contains("\"policyProfile\" : \"authoring\"");
        assertThat(prompt).contains("\"available\" : true");
        assertThat(prompt).contains("\"resolutionStatus\" : \"resolved\"");
        assertThat(prompt).contains("\"requested\"");
        assertThat(prompt).contains("\"resourceKey\" : \"finance.reimbursements\"");
        assertThat(prompt).contains("\"intent\" : \"authoring\"");
        assertThat(prompt).contains("DOMAIN_CATALOG_CONTEXT");
        assertThat(prompt).contains("visibility=mask");
        assertThat(prompt).contains("trainingUse=deny");
        assertThat(prompt).contains("Treat this block as governed semantic grounding");
    }

    @Test
    void resolveCapsLongAssistantMessageReturnedByProvider() throws Exception {
        String longMessage = "Area de dados recomendada. ".repeat(80);
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "explore",
                  "artifactKind": "api_catalog",
                  "changeKind": "resource_discovery_for_indicators",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": "indicadores para dashboard",
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "%s",
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """.formatted(longMessage)));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "quais dados posso usar para graficos?",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "quais dados posso usar para graficos?",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(result.assistantMessage()).hasSizeLessThanOrEqualTo(700);
        assertThat(result.assistantMessage()).endsWith("...");
    }

    @Test
    void resolveParsesConsultativeRetrievalPlanReturnedByProvider() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(AiCallConfig.class),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "explain",
                  "artifactKind": "api_catalog",
                  "changeKind": "answer_api_catalog_question",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Vou investigar as capacidades e o dominio antes de responder.",
                  "consultativeRetrievalPlan": {
                    "schemaVersion": "praxis-agentic-authoring-consultative-retrieval-plan.v1",
                    "requiredContext": ["platform_capabilities", "component_registry", "domain_catalog", "api_resources"],
                    "semanticQueries": ["componentes governados para painel administrativo", "formularios governados do dominio atual"],
                    "answerStrategy": "Explicar possibilidades sem criar preview.",
                    "expectedEvidence": ["componentes authoraveis", "fontes de negocio", "politica de formulario"]
                  },
                  "quickReplies": [],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));

        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution result = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "O que posso fazer aqui?",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "O que posso fazer aqui?",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(result.consultativeRetrievalPlan()).isNotNull();
        assertThat(result.consultativeRetrievalPlan().requiredContext())
                .contains("platform_capabilities", "component_registry", "domain_catalog", "api_resources");
        assertThat(result.consultativeRetrievalPlan().semanticQueries())
                .contains("componentes governados para painel administrativo");
    }

    @Test
    void diagnosticSnapshotExposesExactPromptAndContextBundle() {
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        JsonNode diagnostics = service.diagnosticSnapshot(
                new AgenticAuthoringIntentResolutionRequest(
                        "crie grafico",
                        "page-builder",
                        "praxis-chart",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        "chart-1",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key"),
                "crie grafico",
                objectMapper.createObjectNode().put("widgetCount", 1),
                null,
                List.of(),
                componentCapabilities(),
                "tenant",
                "local");

        assertThat(diagnostics.path("schemaVersion").asText())
                .isEqualTo("praxis-agentic-authoring-llm-diagnostics.v1");
        assertThat(diagnostics.path("promptTemplateId").asText())
                .isEqualTo("ai-authoring/page-builder-system-prompt.v1.md");
        assertThat(diagnostics.path("prompt").asText()).contains("contextBundle:");
        assertThat(diagnostics.path("contextBundle").path("runtimeContext").path("hostApplication").asText())
                .isEqualTo("Angular Praxis Page Builder assistant");
        assertThat(diagnostics.path("contextBundle").path("governedDomainContext").path("available").asBoolean())
                .isFalse();
        assertThat(diagnostics.path("contextBundle").path("governedDomainContext").path("resolutionStatus").asText())
                .isEqualTo("not_requested");
        assertThat(diagnostics.path("contextBundle").path("governedDomainContext").path("requested").path("present").asBoolean())
                .isFalse();
        assertThat(diagnostics.path("contextBundle").path("toolCatalog").path("searchApiResources").path("endpoint").asText())
                .isEqualTo("/api/praxis/config/ai/authoring/resource-candidates");
    }

    @Test
    void diagnosticProjectionNeverRepeatsGovernedDomainRetrieval() {
        DomainCatalogPromptContextService domainContextService =
                Mockito.mock(DomainCatalogPromptContextService.class);
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(
                        providerManagementService,
                        objectMapper,
                        domainContextService);
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.putObject("domainCatalog").put("enabled", true);

        JsonNode diagnostics = service.diagnosticProjection(
                new AgenticAuthoringIntentResolutionRequest(
                        "O que posso fazer aqui?",
                        "page-builder",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        objectMapper.createObjectNode(),
                        null,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        contextHints),
                "O que posso fazer aqui?",
                objectMapper.createObjectNode(),
                null,
                List.of(),
                componentCapabilities());

        assertThat(diagnostics.path("captureKind").asText())
                .isEqualTo("non_retrieving_projection");
        assertThat(diagnostics.path("exactProviderPromptIncluded").asBoolean()).isFalse();
        assertThat(diagnostics.path("contextBundle")
                .path("governedDomainContext")
                .path("resolutionStatus")
                .asText()).isEqualTo("not_recaptured_for_diagnostics");
        Mockito.verifyNoInteractions(domainContextService);
    }

    @Test
    void replacesRedactedQuickReplyPromptWithHumanLabel() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": true,
                  "operationKind": "create",
                  "artifactKind": "dashboard",
                  "changeKind": "create_dashboard",
                  "selectedResourcePath": "/api/human-resources/vw-analytics-folha-pagamento",
                  "resourceSearchQuery": null,
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Escolha a fonte de dados.",
                  "quickReplies": [
                    {
                      "id": "use-analytics-view",
                      "kind": "resource",
                      "label": "Usar visão analítica de folha",
                      "prompt": "[REDACTED]",
                      "contextHints": {
                        "resourcePath": "/api/human-resources/vw-analytics-folha-pagamento"
                      }
                    }
                  ],
                  "clarificationQuestions": [],
                  "warnings": []
                }
                """));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "quero um painel de pagamentos",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "quero um painel de pagamentos",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.quickReplies()).hasSize(1);
        assertThat(resolution.quickReplies().get(0).prompt()).isEqualTo("Usar visão analítica de folha");
        assertThat(resolution.quickReplies().get(0).contextHints().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void keepsUnresolvedLlmGuidanceWhenItContainsActionableContext() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(objectMapper.readTree("""
                {
                  "resolved": false,
                  "operationKind": "create",
                  "artifactKind": "form",
                  "changeKind": "create_minimal_form",
                  "selectedResourcePath": null,
                  "resourceSearchQuery": "api de cadastro de funcionario para formulario de RH",
                  "followUpKind": "none",
                  "requiresGovernedAuthoring": false,
                  "assistantMessage": "Entendi que você quer uma ficha de cadastro. Vou buscar recursos de criação para funcionário.",
                  "quickReplies": [
                    {
                      "id": "search-form-resources",
                      "kind": "suggestion",
                      "label": "Buscar APIs de cadastro",
                      "prompt": "Buscar APIs de cadastro de funcionário",
                      "contextHints": {
                        "tool": "searchApiResources",
                        "artifactKind": "form"
                      }
                    }
                  ],
                  "clarificationQuestions": [],
                  "warnings": ["resource-selection-required"]
                }
                """));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "preciso monta uma ficha pra cadastra funsionario",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "preciso monta uma ficha pra cadastra funsionario",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.operationKind()).isEqualTo("create");
        assertThat(resolution.artifactKind()).isEqualTo("form");
        assertThat(resolution.resourceSearchQuery())
                .isEqualTo("api de cadastro de funcionario para formulario de RH");
        assertThat(resolution.quickReplies()).hasSize(1);
        assertThat(resolution.warnings()).contains("resource-selection-required");
    }

    @Test
    void returnsFailHonestResolutionWhenProviderFails() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenThrow(new RuntimeException("provider quota exhausted"));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "crie um dashboard",
                                "page-builder",
                                "praxis-chart",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "crie um dashboard",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.followUpKind()).isEqualTo("provider_error");
        assertThat(resolution.warnings())
                .contains("llm-intent-resolution-failed", "llm-provider-error")
                .doesNotContain("provider quota exhausted");
        assertThat(resolution.assistantMessage())
                .contains("Não consegui confirmar")
                .contains("tabela")
                .doesNotContain("provider quota exhausted");
        assertThat(resolution.clarificationQuestions())
                .contains("Você quer consultar dados disponíveis ou já quer criar uma tabela, formulário, gráfico ou painel?");
        assertThat(resolution.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::failureKind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("intent_fast", "failure", "quota-exhausted"),
                        org.assertj.core.groups.Tuple.tuple("intent_full", "failure", "quota-exhausted"));
    }

    @Test
    void recoversPlatformGuidanceFromPriorStructuredSemanticScopeWhenProviderFails() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenThrow(new RuntimeException("provider timeout"));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);
        JsonNode contextHints = objectMapper.readTree("""
                {
                  "recommendedIntent": {
                    "source": "page-builder-assistant-empty-state",
                    "opportunityId": "page-builder.platform-capabilities.explore",
                    "semanticScope": "platform-capabilities"
                  }
                }
                """);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "O que posso fazer aqui?",
                                "page-builder",
                                "praxis-dynamic-page-builder",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-4.1-mini",
                                "test-key",
                                "session-platform-guidance",
                                "turn-platform-guidance",
                                List.of(),
                                null,
                                List.of(),
                                contextHints),
                        "O que posso fazer aqui?",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.resolved()).isTrue();
        assertThat(resolution.semanticIntentClass()).isEqualTo("platform_guidance");
        assertThat(resolution.operationKind()).isEqualTo("explain");
        assertThat(resolution.artifactKind()).isEqualTo("component");
        assertThat(resolution.changeKind()).isEqualTo("answer_component_catalog_question");
        assertThat(resolution.followUpKind()).isEqualTo("none");
        assertThat(resolution.selectedResourcePath()).isNull();
        assertThat(resolution.requiresGovernedAuthoring()).isFalse();
        assertThat(resolution.assistantMessage())
                .contains("formulários")
                .contains("tabelas")
                .contains("gráficos")
                .contains("filtros");
        assertThat(resolution.warnings())
                .contains(
                        "llm-intent-resolution-failed",
                        "llm-provider-error",
                        "platform-guidance-prior-semantic-scope-recovery-used");
        assertThat(resolution.providerInvocations())
                .extracting(
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::phase,
                        org.praxisplatform.config.service.AiProviderInvocationTelemetry::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "platform_guidance_confirmation",
                        "failure"));
        Mockito.verify(providerManagementService, Mockito.times(1)).generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"));
    }

    @Test
    void classifiesNormalizedProviderQuotaFailureWithoutLeakingProviderBody() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenThrow(AiProviderCallException.fromHttpStatus(
                "openai",
                429,
                "quota exceeded for request id req_secret_123"));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "crie um dashboard",
                                "page-builder",
                                "praxis-chart",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "crie um dashboard",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.warnings())
                .contains("llm-intent-resolution-failed", "llm-provider-error", "llm-provider-quota-exhausted")
                .doesNotContain("quota exceeded for request id req_secret_123");
        assertThat(resolution.assistantMessage())
                .doesNotContain("quota")
                .doesNotContain("req_secret_123");
    }

    @Test
    void classifiesLegacyProviderFailureMessagesWhenProviderDoesNotExposeStructuredKind() throws Exception {
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenThrow(new RuntimeException("OpenAI Error 401: invalid api key sk-test-secret"));
        AgenticAuthoringLlmIntentResolverService service =
                new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);

        AgenticAuthoringLlmIntentResolution resolution = service.resolve(
                        new AgenticAuthoringIntentResolutionRequest(
                                "crie um dashboard",
                                "page-builder",
                                "praxis-chart",
                                "/page-builder-ia",
                                objectMapper.createObjectNode(),
                                null,
                                "openai",
                                "gpt-5.4-mini",
                                "test-key"),
                        "crie um dashboard",
                        objectMapper.createObjectNode(),
                        null,
                        List.of(),
                        componentCapabilities(),
                        "tenant",
                        "user",
                        "local")
                .orElseThrow();

        assertThat(resolution.warnings())
                .contains("llm-provider-auth-error")
                .doesNotContain("OpenAI Error 401: invalid api key sk-test-secret");
        assertThat(resolution.assistantMessage()).doesNotContain("sk-test-secret");
    }

    private AgenticAuthoringIntentResolutionRequest targetedTableRefinementRequest() {
        AgenticAuthoringSemanticDecision activeDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "decision-add-email",
                "modify",
                "table",
                "column.add",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        "/api/human-resources/funcionarios",
                        "get",
                        "",
                        "/api/human-resources/funcionarios",
                        "get"),
                null,
                new AgenticAuthoringSemanticDecision.RetrievalEvidence(
                        "current_page",
                        List.of("current-page-target-resource"),
                        1),
                false,
                "",
                "",
                "");
        return new AgenticAuthoringIntentResolutionRequest(
                "Agora adicione também a coluna salário sem remover nenhuma das anteriores.",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                "funcionarios-table",
                "openai",
                "gpt-4.1-mini",
                "test-key",
                "session-1",
                "turn-2",
                List.of(
                        new AgenticAuthoringConversationMessage(
                                "user-1",
                                "user",
                                "Adicione a coluna e-mail à tabela de funcionários.",
                                "2026-07-15T20:00:00Z"),
                        new AgenticAuthoringConversationMessage(
                                "assistant-1",
                                "assistant",
                                "A coluna e-mail foi adicionada.",
                                "2026-07-15T20:00:01Z")),
                null,
                List.of(),
                objectMapper.createObjectNode(),
                activeDecision);
    }

    private AgenticAuthoringIntentResolutionRequest targetedTableUndoRequest(boolean available) {
        AgenticAuthoringIntentResolutionRequest prior = targetedTableRefinementRequest();
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode action = contextHints.putArray("clientActions").addObject();
        action.put("schemaVersion", "praxis-agentic-authoring-client-action.v1");
        action.put("id", "page-builder.local-preview.undo");
        action.put("kind", "local-undo");
        action.put("capabilityRef", "page-builder.local-preview-history");
        action.put("available", available);
        action.put("targetComponentId", "praxis-dynamic-page-builder");
        return new AgenticAuthoringIntentResolutionRequest(
                "Desfaz só a última mudança e mantém todas as anteriores.",
                prior.targetApp(),
                prior.targetComponentId(),
                prior.currentRoute(),
                prior.currentPage(),
                prior.selectedWidgetKey(),
                prior.provider(),
                prior.model(),
                prior.apiKey(),
                prior.sessionId(),
                "turn-undo",
                prior.conversationMessages(),
                prior.pendingClarification(),
                prior.attachmentSummaries(),
                contextHints,
                prior.activeSemanticDecision());
    }

    private AgenticAuthoringTarget targetedTableTarget() {
        return new AgenticAuthoringTarget(
                "funcionarios-table",
                "praxis-table",
                "/api/human-resources/funcionarios",
                "",
                "/api/human-resources/funcionarios",
                "get");
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilities() {
        return new AgenticAuthoringComponentCapabilitiesResult(
                "0",
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                        "praxis-chart",
                        "0",
                        List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                                "chart.create",
                                "create_chart",
                                List.of("grafico", "chart"),
                                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias(
                                        "categoryField",
                                        List.of("eixo x", "categoria"))),
                                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample(
                                        "crie um grafico por status",
                                        "Agrupar pedidos por status",
                                        List.of("Use categoryField para o eixo X", "Use valueField para a metrica"))))))));
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilitiesWithExpansion() {
        return new AgenticAuthoringComponentCapabilitiesResult(
                "0",
                List.of(
                        new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                                "praxis-expansion",
                                "0",
                                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                                        "panel.add",
                                        "layout_expansion",
                                        List.of("accordion", "acordeon", "painel expansivel"),
                                        List.of(),
                                        List.of()))),
                        new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                                "praxis-table",
                                "0",
                                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                                        "table.create",
                                        "create_table",
                                        List.of("tabela", "detalhes"),
                                        List.of(),
                                        List.of())))));
    }

    private AgenticAuthoringCandidate weakCandidate(String resourcePath) {
        return new AgenticAuthoringCandidate(
                resourcePath,
                "post",
                "/schemas/filtered?path=" + resourcePath + "/filter/cursor&operation=post&schemaType=response",
                resourcePath + "/filter/cursor",
                "post",
                0.42d,
                "weak lexical candidate",
                List.of("api-metadata", "lexical-fallback", "weak-evidence"));
    }
}
