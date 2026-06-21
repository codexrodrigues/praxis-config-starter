package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.service.SchemaRetrievalService;

@Tag("unit")
class AgenticAuthoringToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesSearchApiResourcesAsInternalRouteScopedTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        assertThat(registry.definitions())
                .hasSize(9)
                .anySatisfy(definition -> {
                    assertThat(definition.name()).isEqualTo("searchApiResources");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "shared_rule_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring",
                                    "pre_intent_resource_discovery");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:/api/praxis/config/ai/authoring/resource-candidates");
                    assertThat(definition.allowedPhases())
                            .containsExactlyInAnyOrder("retrieveEvidence", "repairOrAsk");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("safe_grounding");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                })
                .anySatisfy(definition -> {
                    assertThat(definition.name()).isEqualTo("resolveRuntimeRelatedSurface");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:runtime-related-surface-read");
                    assertThat(definition.allowedPhases())
                            .containsExactly("retrieveEvidence");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("governed_runtime_context_reconciliation");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                })
                .extracting(AgenticAuthoringToolDefinition::name)
                .containsExactlyInAnyOrder(
                        "searchApiResources",
                        "searchComponentCorpus",
                        "getComponentAuthoringContext",
                        "getManifestSlice",
                        "searchConfigPathDocs",
                        "searchExamples",
                        "searchSchemaFields",
                        "presentationAffordanceDiscovery",
                        "resolveRuntimeRelatedSurface");
    }

    @Test
    void exposesPresentationAffordanceDiscoveryAsReadOnlyGroundingTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        assertThat(registry.definitions())
                .anySatisfy(definition -> {
                    assertThat(definition.name()).isEqualTo("presentationAffordanceDiscovery");
                    assertThat(definition.allowedRoutes())
                            .containsExactlyInAnyOrder(
                                    "component_authoring",
                                    "mixed",
                                    "needs_clarification",
                                    "advisory_authoring");
                    assertThat(definition.ownerSurface())
                            .isEqualTo("praxis-config-starter:ai-authoring/presentation-affordances");
                    assertThat(definition.allowedPhases())
                            .containsExactlyInAnyOrder("retrieveEvidence", "repairOrAsk");
                    assertThat(definition.sideEffectClass()).isEqualTo("read_only");
                    assertThat(definition.governanceLevel()).isEqualTo("safe_grounding");
                    assertThat(definition.auditRedactionPolicy()).isEqualTo("safe_event_projection_only");
                });
    }

    @Test
    void discoversStringTableColumnAffordancesWithoutDateFormats() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-table",
                                "column",
                                "statusPriority",
                                null,
                                "string",
                                null,
                                null,
                                "opcoes de formatacao para coluna calculada textual",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("componentId", "praxis-table")
                .containsEntry("targetKind", "column")
                .containsEntry("dataType", "string")
                .containsEntry("sourceRef", "@praxisui/core:ColumnDefinition");
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("targetField").asText()).isEqualTo("statusPriority");
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains(
                        "column.align",
                        "column.renderer.badge",
                        "column.renderer.chip",
                        "column.renderer.icon",
                        "column.renderer.compose",
                        "column.conditionalRenderers")
                .doesNotContain("column.format.date");
    }

    @Test
    void discoversGenericTableColumnAffordancesWhenTypeIsUnknown() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-table",
                                "column",
                                "",
                                null,
                                null,
                                null,
                                null,
                                "quais recursos de apresentacao existem para colunas",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("dataType", "unknown")
                .containsEntry("requiresTypeConfirmation", true);
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("requiresTypeConfirmation").asBoolean()).isTrue();
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains(
                        "column.align",
                        "column.renderer.badge",
                        "column.renderer.chip",
                        "column.renderer.compose")
                .doesNotContain("column.format.date", "column.format.numeric");
    }

    @Test
    void rejectsPresentationAffordanceDiscoveryWhenTargetHasNoProvider() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-unknown",
                                "field",
                                "statusPriority",
                                null,
                                "string",
                                null,
                                null,
                                "opcoes de formatacao para campo",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("presentation-affordance-target-unsupported");
    }

    @Test
    void discoversDynamicFormAffordancesThroughSameTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "presentationAffordanceDiscovery",
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                "praxis-dynamic-form",
                                null,
                                "observacaoInterna",
                                null,
                                null,
                                null,
                                null,
                                "quais opcoes visuais existem para campo",
                                20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("componentId").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(payload.path("targetKind").asText()).isEqualTo("field");
        assertThat(payload.path("affordances"))
                .extracting(affordance -> affordance.path("id").asText())
                .contains("field.label", "field.helperText", "field.layout");
    }

    @Test
    void getManifestSliceCanReturnPresentationAffordances() throws Exception {
        AgenticAuthoringManifestService manifestService = Mockito.mock(AgenticAuthoringManifestService.class);
        when(manifestService.getManifest("praxis-table")).thenReturn(objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "1.0.0",
                  "editableTargets": [],
                  "operations": [],
                  "validators": []
                }
                """));
        when(manifestService.listPresentationAffordances("praxis-table")).thenReturn(objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "defaultTargetKind": "column",
                  "affordances": [
                    { "id": "column.renderer.badge" }
                  ]
                }
                """));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                manifestService,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "getManifestSlice",
                        "component_authoring",
                        new ManifestSliceToolRequest("praxis-table", null, "presentationAffordances", 20)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        JsonNode payload = (JsonNode) result.payload();
        assertThat(payload.path("sliceKind").asText()).isEqualTo("presentationAffordances");
        assertThat(payload.path("evidence").path("affordances").get(0).path("id").asText())
                .isEqualTo("column.renderer.badge");
    }

    @Test
    void executesSearchComponentCorpusThroughContextRetrievalService() {
        ContextRetrievalService contextRetrievalService = Mockito.mock(ContextRetrievalService.class);
        when(contextRetrievalService.searchComponentCorpus(
                eq("toolbar"),
                eq("praxis-table"),
                eq("capabilities"),
                eq(3),
                eq("tenant-a"),
                eq("prod"),
                eq("release-1")))
                .thenReturn(List.of(new ContextRetrievalService.ComponentCorpusEvidence(
                        "doc-1",
                        "praxis-table",
                        "component_definition",
                        "capabilities",
                        "praxis-ui-angular/projects/praxis-table/table.metadata.ts",
                        "release-1",
                        "tenant-a",
                        "prod",
                        "allow",
                        "hash-1",
                        "1.0.0",
                        "Toolbar capability docs",
                        0.91d)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                contextRetrievalService,
                null,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchComponentCorpus",
                        "component_authoring",
                        new CorpusToolRequest(
                                "toolbar",
                                "praxis-table",
                                "capabilities",
                                null,
                                "tenant-a",
                                "prod",
                                "release-1",
                                3)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics())
                .containsEntry("evidenceCount", 1)
                .containsEntry("componentId", "praxis-table")
                .containsEntry("chunkKind", "capabilities")
                .containsEntry("releaseId", "release-1");
        assertThat((List<?>) result.payload()).hasSize(1);
    }

    @Test
    void executesSearchExamplesAsRecipeOnlyCorpusSearch() {
        ContextRetrievalService contextRetrievalService = Mockito.mock(ContextRetrievalService.class);
        when(contextRetrievalService.searchComponentCorpus(
                eq("component examples recipes"),
                eq("praxis-table"),
                eq("recipe"),
                eq(5),
                eq(null),
                eq(null),
                eq("release-1")))
                .thenReturn(List.of());
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                contextRetrievalService,
                null,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchExamples",
                        "component_authoring",
                        new CorpusToolRequest(
                                null,
                                "praxis-table",
                                null,
                                null,
                                null,
                                null,
                                "release-1",
                                5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics()).containsEntry("chunkKind", "recipe");
    }

    @Test
    void executesSearchSchemaFieldsWithoutApplyingPatch() throws Exception {
        SchemaRetrievalService schemaRetrievalService = Mockito.mock(SchemaRetrievalService.class);
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        when(schemaRetrievalService.fetchSchema(
                Mockito.any(org.praxisplatform.config.dto.AiSchemaContext.class),
                anyString()))
                .thenReturn(schema);
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                null,
                null,
                schemaRetrievalService,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchSchemaFields",
                        "component_authoring",
                        new SchemaFieldsToolRequest(
                                "/employees",
                                "POST",
                                "request",
                                "name",
                                "http://localhost:8080",
                                5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.safeDiagnostics()).containsEntry("schemaFound", true);
        assertThat(((JsonNode) result.payload()).path("schema").path("properties").has("name")).isTrue();
    }

    @Test
    void rejectsReadOnlyCorpusToolWhenServiceIsUnavailable() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchComponentCorpus",
                        "component_authoring",
                        new CorpusToolRequest("toolbar", "praxis-table", null, null, null, null, "release-1", 5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-service-unavailable");
    }

    @Test
    void readOnlyCorpusToolsAreBlockedOutsideRetrievalPhases() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper),
                Mockito.mock(ContextRetrievalService.class),
                null,
                null,
                objectMapper);

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchComponentCorpus",
                        "component_authoring",
                        new CorpusToolRequest("toolbar", "praxis-table", null, null, null, null, "release-1", 5)),
                null,
                "applyPatch");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-phase-not-allowed");
    }
    @Test
    void executesSearchApiResourcesThroughRegistry() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new ApiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "GET",
                        "analytics,folha,pagamento",
                        "Analytics de folha de pagamento",
                        "Visao analitica de folha de pagamento por departamento",
                        "listVwAnalyticsFolhaPagamento",
                        null,
                        "{\"type\":\"object\"}",
                        "[]",
                        "{}",
                        null)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "component_authoring",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "graficos de folha de pagamento",
                                null,
                                "dashboard",
                                5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.payload()).isInstanceOf(AgenticAuthoringResourceCandidatesResult.class);
        AgenticAuthoringResourceCandidatesResult payload =
                (AgenticAuthoringResourceCandidatesResult) result.payload();
        assertThat(payload.candidates())
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .containsExactly("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(result.safeDiagnostics())
                .containsEntry("candidateCount", 1)
                .containsEntry("artifactKind", "dashboard")
                .containsEntry("retrievalQuery", "graficos de folha de pagamento")
                .containsEntry("retrievalSource", "lexical_fallback");
        assertThat(result.safeDiagnostics().get("resourceDiscoveryDiagnostics"))
                .isInstanceOfSatisfying(Map.class, diagnostics -> assertThat(diagnostics)
                        .containsKeys(
                                "catalogDiscoveryElapsedMs",
                                "groundingElapsedMs",
                                "consultativeProjectionElapsedMs",
                                "quickReplyElapsedMs",
                                "totalElapsedMs")
                        .containsEntry("catalogCandidateCount", 1)
                        .containsEntry("groundedCandidateCount", 1)
                        .containsEntry("limitedCandidateCount", 1));
    }

    @Test
    void rejectsToolExecutionOutsideDeclaredRouteScope() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "unsupported_route",
                        new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-route-not-allowed");
        assertThat(result.errorMessage()).contains("unsupported_route");
    }

    @Test
    void allowsPreIntentResourceDiscoveryRouteUsedByTurnEngine() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        when(repository.findAll()).thenReturn(List.of(new ApiMetadata(
                "/api/human-resources/funcionarios",
                "GET",
                "funcionarios,rh,pessoas",
                "Funcionários",
                "Cadastro e perfil de funcionarios",
                "listFuncionarios",
                null,
                "{\"type\":\"object\"}",
                "[]",
                "{}",
                null)));
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(
                        new AgenticAuthoringApiMetadataCandidateCatalog(repository),
                        objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "pre_intent_resource_discovery",
                        new AgenticAuthoringResourceCandidatesRequest(
                                "quero criar algo que mostre informacoes dos empregados",
                                "quero criar algo que mostre informacoes dos empregados",
                                "page",
                                6)),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isTrue();
        assertThat(result.tool()).isEqualTo("searchApiResources");
        assertThat(result.safeDiagnostics())
                .containsEntry("artifactKind", "page")
                .containsEntry("retrievalQuery", "quero criar algo que mostre informacoes dos empregados");
    }

    @Test
    void rejectsToolExecutionWithoutExplicitPhase() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(new AgenticAuthoringToolCall(
                "searchApiResources",
                "component_authoring",
                new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)));

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-phase-required");
    }

    @Test
    void rejectsToolExecutionOutsideDeclaredPhaseScope() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "component_authoring",
                        new AgenticAuthoringResourceCandidatesRequest("funcionarios", null, "form", 5)),
                null,
                "proposeDecision");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-phase-not-allowed");
        assertThat(result.errorMessage()).contains("proposeDecision");
    }

    @Test
    void resolvesRuntimeRelatedSurfaceResourceFromTargetWidgetComponent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/api/operations/missao-participantes/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": 10,
                            "funcionarioNome": "Ana Torres",
                            "papel": "LIDER",
                            "principal": true,
                            "missaoTitulo": "Operacao Aurora",
                            "cpf": "123.456.789-00",
                            "email": "ana@example.test",
                            "salario": 999999
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                    new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
            JsonNode runtimeContext = objectMapper.readTree("""
                    {
                      "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                      "availableSurfaces": ["missionTeam"],
                      "components": [
                        {
                          "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                          "refs": {"pageId": "page-builder-ia"},
                          "snapshot": {
                            "selectionDigest": {},
                            "relationSurfaceRefs": [
                              {
                                "id": "missionTeam",
                                "sourceWidget": "missionSummary",
                                "targetWidget": "missionTeam",
                                "targetResourcePath": "operations/missao-participantes",
                                "statePath": "selection.missionId",
                                "queryMapping": {
                                  "sourceField": "missaoId",
                                  "targetFilterField": "missaoId",
                                  "targetPath": "filters.missaoId",
                                  "valueSource": "selectionDigest.selectedIds[0]"
                                },
                                "operationId": "dynamicPage.surface.open"
                              }
                            ]
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["missionTeam"],
                            "activeActionRefs": ["dynamicPage.surface.open"]
                          }
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                          "refs": {"resourcePath": "operations/vw-resumo-missoes"},
                          "snapshot": {
                            "selectionDigest": {
                              "selectedCount": 1,
                              "selectedIds": ["1"],
                              "idField": "missaoId"
                            }
                          },
                          "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "missionTeam"},
                          "refs": {"resourcePath": "operations/missao-participantes"},
                          "snapshot": {"schemaFieldRefs": ["funcionarioNome", "papel", "resultado"]}
                        }
                      ]
                    }
                    """);

            AgenticAuthoringToolResult result = registry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    "missionTeam",
                                    null,
                                    null,
                                    "http://localhost:" + server.getAddress().getPort(),
                                    8)),
                    new AiPrincipalContext("tenant", "user", "local", true),
                    "retrieveEvidence");

            assertThat(result.valid())
                    .as("errorCode=%s errorMessage=%s", result.errorCode(), result.errorMessage())
                    .isTrue();
            assertThat(requestBody.get()).contains("\"missaoId\":1");
            JsonNode payload = (JsonNode) result.payload();
            assertThat(payload.path("resourcePath").asText()).isEqualTo("operations/missao-participantes");
            assertThat(payload.path("recordCount").asInt()).isEqualTo(1);
            assertThat(payload.path("queryMapping").path("targetPath").asText()).isEqualTo("filters.missaoId");
            assertThat(payload.path("projectionFields").toString()).contains("funcionarioNome", "papel");
            assertThat(payload.path("redactionApplied").asBoolean()).isTrue();
            assertThat(payload.path("records").toString())
                    .contains("Ana Torres")
                    .doesNotContain("Operacao Aurora")
                    .doesNotContain("123.456.789-00")
                    .doesNotContain("ana@example.test")
                    .doesNotContain("salario");
            assertThat(payload.path("rawRuntimeValuesCopied").asBoolean()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsGenericRuntimeRelatedSurfaceThroughDeclaredQueryMapping() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/api/sales/order-items/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {
                            "id": 301,
                            "orderId": 42,
                            "sku": "SKU-42-A",
                            "quantity": 3,
                            "email": "buyer@example.test",
                            "token": "hidden"
                          }
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                    new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
            JsonNode runtimeContext = objectMapper.readTree("""
                    {
                      "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                      "availableSurfaces": ["orderItems"],
                      "components": [
                        {
                          "identity": {"componentId": "praxis-dynamic-page", "instanceId": "orders-page"},
                          "snapshot": {
                            "relationSurfaceRefs": [
                              {
                                "id": "orderItems",
                                "sourceWidget": "ordersTable",
                                "targetWidget": "orderItems",
                                "targetResourcePath": "sales/order-items",
                                "queryMapping": {
                                  "sourceField": "orderId",
                                  "targetFilterField": "orderId",
                                  "targetPath": "filters.orderId",
                                  "valueSource": "selectionDigest.selectedIds[0]"
                                },
                                "operationId": "dynamicPage.surface.open"
                              }
                            ]
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["orderItems"],
                            "activeActionRefs": ["dynamicPage.surface.open"]
                          }
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "ordersTable"},
                          "refs": {"resourcePath": "sales/orders"},
                          "snapshot": {
                            "selectionDigest": {
                              "selectedCount": 1,
                              "selectedIds": ["42"],
                              "idField": "orderId"
                            }
                          },
                          "affordances": {"activeSurfaceRefs": ["orderItems"]}
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "orderItems"},
                          "refs": {"resourcePath": "sales/order-items"},
                          "snapshot": {"schemaFieldRefs": ["orderId", "sku", "quantity"]}
                        }
                      ]
                    }
                    """);

            AgenticAuthoringToolResult result = registry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    "orderItems",
                                    null,
                                    null,
                                    "http://localhost:" + server.getAddress().getPort(),
                                    8)),
                    new AiPrincipalContext("tenant", "user", "local", true),
                    "retrieveEvidence");

            assertThat(result.valid()).isTrue();
            assertThat(requestBody.get()).contains("\"orderId\":42");
            JsonNode payload = (JsonNode) result.payload();
            assertThat(payload.path("resourcePath").asText()).isEqualTo("sales/order-items");
            assertThat(payload.path("records").toString())
                    .contains("SKU-42-A")
                    .contains("quantity")
                    .doesNotContain("buyer@example.test")
                    .doesNotContain("hidden");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenRelationIsForged() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["payroll"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId",
                              "valueSource": "selectionDigest.selectedIds[0]"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["payroll"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    }
                  ]
                }
                """, "payroll", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-relation-not-declared");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWithoutSelection() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId",
                              "valueSource": "selectionDigest.selectedIds[0]"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 0, "selectedIds": [], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-selection-required");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWithoutDeclaredQueryMapping() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, "missaoId");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-query-mapping-required");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenSelectionIdFieldIsMissing() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"]}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, "missaoId");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-selection-id-required");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenSelectionHasMultipleIds() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 2, "selectedIds": ["1", "2"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-multiple-selection-unsupported");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenQueryMappingSourceFieldDiffersFromSelectionIdField() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missionId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-source-field-mismatch");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWithoutGovernedTargetResourcePathEvenWhenRequestSuggestsOne() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", "operations/missao-participantes", null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-resource-not-declared");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenDeclaredTargetWidgetIsMissing() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetWidget": "missingMissionTeam",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-widget-not-found");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetFilterFieldIsNotDeclaredByTargetSurface() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetWidget": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "cpf"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionTeam"},
                      "refs": {"resourcePath": "operations/missao-participantes"},
                      "snapshot": {"schemaFieldRefs": ["missaoId", "funcionarioNome", "papel"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-filter-field-not-declared");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetPathIsNotSafeFilterIdentifier() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missao-id"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-path-invalid");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetPathDiffersFromTargetFilterField() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.outraCoisa"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-target-path-filter-mismatch");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenTargetWidgetIsMissingEvenWithResourcePathAndQueryMappingDeclared() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/api/operations/missao-participantes/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "success": true,
                      "data": {
                        "content": [
                          {"id": 10, "missaoId": 1, "funcionarioNome": "Ana Torres", "papel": "LIDER"}
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                    new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
            JsonNode runtimeContext = objectMapper.readTree("""
                    {
                      "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                      "availableSurfaces": ["missionTeam"],
                      "components": [
                        {
                          "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                          "snapshot": {
                            "relationSurfaceRefs": [
                              {
                                "id": "missionTeam",
                                "sourceWidget": "missionSummary",
                                "targetWidget": "missingMissionTeam",
                                "targetResourcePath": "operations/missao-participantes",
                                "queryMapping": {
                                  "sourceField": "missaoId",
                                  "targetFilterField": "missaoId",
                                  "targetPath": "filters.missaoId"
                                },
                                "operationId": "dynamicPage.surface.open"
                              }
                            ]
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["missionTeam"],
                            "activeActionRefs": ["dynamicPage.surface.open"]
                          }
                        },
                        {
                          "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                          "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                          "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                        }
                      ]
                    }
                    """);

            AgenticAuthoringToolResult result = registry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    "missionTeam",
                                    null,
                                    null,
                                    "http://localhost:" + server.getAddress().getPort(),
                                    8)),
                    new AiPrincipalContext("tenant", "user", "local", true),
                    "retrieveEvidence");

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("runtime-surface-target-widget-not-found");
            assertThat(requestBody.get()).isBlank();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenSurfaceCapabilityIsNotActive() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {"activeActionRefs": ["dynamicPage.surface.open"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-not-active");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenOperationCapabilityIsNotActive() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "targetResourcePath": "operations/missao-participantes",
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-operation-not-active");
    }

    @Test
    void rejectsRuntimeRelatedSurfaceWhenResourcePathMatchesMultipleRuntimeSurfacesWithoutCanonicalIdentity() throws Exception {
        AgenticAuthoringToolResult result = executeRuntimeRelatedSurfaceRead("""
                {
                  "schemaVersion": "praxis-agentic-authoring-runtime-consultable-context.v1",
                  "availableSurfaces": ["missionTeam"],
                  "components": [
                    {
                      "identity": {"componentId": "praxis-dynamic-page", "instanceId": "page-builder-ia"},
                      "snapshot": {
                        "relationSurfaceRefs": [
                          {
                            "id": "missionTeam",
                            "sourceWidget": "missionSummary",
                            "targetResourcePath": "operations/missao-participantes",
                            "queryMapping": {
                              "sourceField": "missaoId",
                              "targetFilterField": "missaoId",
                              "targetPath": "filters.missaoId"
                            },
                            "operationId": "dynamicPage.surface.open"
                          }
                        ]
                      },
                      "affordances": {
                        "activeSurfaceRefs": ["missionTeam"],
                        "activeActionRefs": ["dynamicPage.surface.open"]
                      }
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionSummary"},
                      "snapshot": {"selectionDigest": {"selectedCount": 1, "selectedIds": ["1"], "idField": "missaoId"}},
                      "affordances": {"activeSurfaceRefs": ["missionTeam"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionTeamA"},
                      "refs": {"resourcePath": "operations/missao-participantes"},
                      "snapshot": {"schemaFieldRefs": ["funcionarioNome", "papel"]}
                    },
                    {
                      "identity": {"componentId": "praxis-table", "widgetKey": "missionTeamB"},
                      "refs": {"resourcePath": "operations/missao-participantes"},
                      "snapshot": {"schemaFieldRefs": ["funcionarioNome", "papel"]}
                    }
                  ]
                }
                """, "missionTeam", null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("runtime-surface-resource-path-ambiguous");
    }

    private AgenticAuthoringToolResult executeRuntimeRelatedSurfaceRead(
            String runtimeContextJson,
            String surfaceRef,
            String requestResourcePath,
            String filterField) throws Exception {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));
        return registry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                        "advisory_authoring",
                        new RuntimeRelatedSurfaceReadToolRequest(
                                objectMapper.readTree(runtimeContextJson),
                                surfaceRef,
                                requestResourcePath,
                                filterField,
                                "http://localhost:1",
                                8)),
                new AiPrincipalContext("tenant", "user", "local", true),
                "retrieveEvidence");
    }

    @Test
    void returnsStructuredFailureForInvalidPayload() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "searchApiResources",
                        "component_authoring",
                        "funcionarios"),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-payload-invalid");
        assertThat(result.errorMessage()).contains("AgenticAuthoringResourceCandidatesRequest");
    }

    @Test
    void returnsStructuredFailureForUnknownTool() {
        AgenticAuthoringToolRegistry registry = new AgenticAuthoringToolRegistry(
                new AgenticAuthoringResourceDiscoveryService(null, objectMapper));

        AgenticAuthoringToolResult result = registry.execute(
                new AgenticAuthoringToolCall(
                        "unknownTool",
                        "component_authoring",
                        null),
                null,
                "retrieveEvidence");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("tool-not-found");
    }
}
