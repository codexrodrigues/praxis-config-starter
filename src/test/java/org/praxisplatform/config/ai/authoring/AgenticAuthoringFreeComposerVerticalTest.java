package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.*;

/** Real turn/resolver/materializer with synthetic retrieval and semantic provider boundaries. */
@Tag("unit")
class AgenticAuthoringFreeComposerVerticalTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void freeNeedTraversesTheTurnEngineAndCanonicalCrudMaterialization(String domain) throws Exception {
        String resource = "/api/synthetic/" + domain;
        String prompt = domain.equals("staff")
                ? "Preciso acompanhar as pessoas por equipe, ver pendências e abrir o cadastro para resolver o que falta."
                : "Preciso acompanhar entregas por base, ver pendências e abrir o registro para resolver o que falta.";
        var constraints = mapper.createObjectNode().put("appliesToDataSelection", true);
        constraints.putArray("filters").addObject().put("concept", "pending records")
                .put("field", "pending").put("operator", "eq").put("value", true);
        var candidate = new AgenticAuthoringCandidate(resource, "get",
                "/schemas/filtered?path=" + resource + "/filter&operation=post&schemaType=response",
                resource, "GET", 0.98, "Synthetic governed operational resource",
                List.of("semantic-retrieval", "tool-search-api-resources", "schema-grounding-verified",
                        "resource-capabilities-verified"));
        var catalog = mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        when(catalog.discover(anyString(), anyString(), any(), any(), any())).thenReturn(List.of(candidate));
        var llm = mock(AgenticAuthoringLlmIntentResolverService.class);
        var visual = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1", "review pending records",
                "resource-crud", "praxis-crud", List.of(), false, true,
                List.of("praxis-chart"), true, false, "llm-stub");
        when(llm.resolve(any(), anyString(), any(), any(), anyList(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(true, "create", "page",
                        "create_artifact", resource, null, "none", "Preparei uma prévia para revisão.",
                        List.of(), List.of(), List.of("llm-intent-resolution-used"), null, visual,
                        false, "component_authoring", constraints, List.of())));
        var resolver = new AgenticAuthoringIntentResolverService(mapper, catalog, llm, null);
        var schemas = mock(SchemaRetrievalService.class);
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("id").put("type", "integer").putObject("x-ui").put("label", "Identificador");
        properties.putObject("name").put("type", "string").putObject("x-ui").put("label", "Nome");
        properties.putObject("pending").put("type", "boolean").putObject("x-ui").put("label", "Pendente");
        properties.putObject("active").put("type", "boolean").putObject("x-ui").put("label", "Ativo");
        properties.putObject("groupId").put("type", "integer").putObject("x-ui").put("label", "Grupo");
        when(schemas.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost:8088/schemas/filtered"));
        when(schemas.fetchSchemaResult(any(AiSchemaContext.class), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost:8088/schemas/filtered"));
        var provider = mock(AiProviderManagementService.class);
        var artifacts = new AgenticAuthoringArtifactProperties();
        var preview = new AgenticAuthoringPreviewService(
                new AgenticAuthoringPlanService(provider, artifacts, mapper),
                new AgenticAuthoringPatchCompilerService(artifacts, mapper), mapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(mapper)), null, schemas);
        var registry = new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(catalog, mapper));
        AgenticAuthoringPreIntentToolPlanningService planner = (request, principal) ->
                AgenticAuthoringPreIntentToolPlanningResult.planned(new AgenticAuthoringPreIntentToolPlan(
                        "praxis-agentic-authoring-pre-intent-tool-plan.v2", "Discover governed operational records.",
                        List.of(new AgenticAuthoringToolCall(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES,
                                "pre_intent_resource_discovery", new AgenticAuthoringResourceCandidatesRequest(
                                        "governed operational records", request.userPrompt(), "page", 6))),
                        "authoring_or_other", "", true, constraints, "page", "praxis-crud"));
        var engine = new AgenticAuthoringTurnEngine(resolver, preview, mapper,
                new AgenticAuthoringCurrentPageAnalyzer(mapper), registry, null, null,
                null, null, null, planner);
        var page = mapper.createObjectNode();
        page.putArray("widgets");
        // Storage identity of the host, matching the real composer; no requested
        // resource, artifact, operation or materialized widget is preselected.
        var hints = mapper.createObjectNode();
        hints.putObject("agenticApplyTarget")
                .put("schemaVersion", "praxis-agentic-authoring-apply-target.v1")
                .put("componentType", "praxis-dynamic-page")
                .put("componentId", "landing:decision-playground")
                .put("scope", "user").put("mode", "create");
        var request = new AgenticAuthoringTurnStreamRequest(prompt, "praxis-ui-angular",
                "praxis-dynamic-page-builder", "/decision-playground", page, null,
                "openai", null, null, "free-" + domain, "turn-1", List.of(), null,
                List.of(), hints, null, null);
        List<ObjectNode> events = new ArrayList<>();
        var sink = new AgenticAuthoringTurnEventSink() {
            public AgenticAuthoringTurnEventAppendResult append(String type, Object payload) {
                var event = mapper.createObjectNode().put("type", type);
                event.set("payload", mapper.valueToTree(payload));
                events.add(event);
                return new AgenticAuthoringTurnEventAppendResult(type, true);
            }
            public boolean terminalReached() { return false; }
        };
        for (int turn = 1; turn <= 3; turn++) {
            if (turn == 2) constraints.withArray("filters").addObject()
                    .put("concept", "organizational group").put("field", "groupId").put("operator", "eq").put("value", 7);
            if (turn == 3) constraints.withArray("filters").addObject()
                    .put("concept", "active records").put("field", "active").put("operator", "eq").put("value", true);
            events.clear();
            engine.execute(request, new AiPrincipalContext("synthetic", "proof", "local", true), sink);
            JsonNode terminal = events.stream().filter(event -> "result".equals(event.path("type").asText()))
                    .reduce((first, second) -> second).orElseThrow().path("payload");
            Path output = Path.of("target/free-authoring", domain + (turn == 1 ? "" : "-turn" + turn) + ".json");
            Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), terminal);
            assertThat(terminal.path("canApply").asBoolean()).as(terminal.toPrettyString()).isTrue();
            assertThat(terminal.at("/intentResolution/semanticDecision/constraints")).isEqualTo(constraints);
            assertThat(terminal.at("/preview/uiCompositionPlan/widgets/0/componentId").asText()).isEqualTo("praxis-crud");
            JsonNode filters = terminal.at("/preview/uiCompositionPlan/widgets/0/inputs/metadata/queryContext/filters");
            assertThat(filters.path("pending").asBoolean()).isTrue();
            if (turn >= 2) assertThat(filters.path("groupId").asInt()).isEqualTo(7);
            if (turn == 3) assertThat(filters.path("active").asBoolean()).isTrue();
            var decision = mapper.treeToValue(terminal.at("/intentResolution/semanticDecision"), AgenticAuthoringSemanticDecision.class);
            if (turn > 1) {
                assertThat(decision.previousDecisionId()).isEqualTo(request.activeSemanticDecision().decisionId());
            }
            hints.withObject("agenticApplyTarget").put("mode", "update")
                    .put("baseEtag", "00000000-0000-4000-8000-%012d".formatted(turn));
            request = new AgenticAuthoringTurnStreamRequest(
                    turn == 1 ? "Mantenha as pendências e restrinja ao grupo 7." : "Mantenha o recorte e inclua somente os ativos.",
                    request.targetApp(), request.targetComponentId(), request.currentRoute(),
                    terminal.at("/preview/compiledFormPatch/patch/page"), null, "openai", null, null,
                    request.sessionId(), "turn-" + (turn + 1), List.of(), null, List.of(), hints, null, decision);
        }
        verify(llm, times(3)).resolve(any(), anyString(), any(), any(), anyList(), any(), any(), any(), any());
        verifyNoInteractions(provider);
    }
}
