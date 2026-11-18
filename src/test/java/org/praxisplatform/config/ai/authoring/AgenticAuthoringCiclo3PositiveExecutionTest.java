package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

@Tag("unit")
class AgenticAuthoringCiclo3PositiveExecutionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static JsonNode componentsSnapshot;
    private static AgenticAuthoringManifestService service;

    @BeforeAll
    static void loadSnapshotManifests() throws Exception {
        try (InputStream stream = AgenticAuthoringCiclo3PositiveExecutionTest.class
                .getResourceAsStream("/ai-registry/registry-snapshot.json")) {
            assertThat(stream).isNotNull();
            JsonNode snapshot = OBJECT_MAPPER.readTree(stream);
            componentsSnapshot = snapshot.path("components");
        }

        AiRegistryRepository repository = mock(AiRegistryRepository.class);
        when(repository.findAuthoringManifestPayload(
                eq("component_definition"),
                anyString(),
                eq("component-definition"),
                eq(Scope.SYSTEM.name()),
                eq("GLOBAL")))
                .thenAnswer(invocation -> {
                    String componentId = invocation.getArgument(1, String.class);
                    JsonNode manifest = componentsSnapshot.path(componentId).path("authoringManifest");
                    return manifest.isObject() ? Optional.of(manifest.toString()) : Optional.empty();
                });

        AgenticAuthoringTargetResolverRegistry resolverRegistry = new AgenticAuthoringTargetResolverRegistry();
        service = new AgenticAuthoringManifestService(
                repository,
                OBJECT_MAPPER,
                resolverRegistry,
                new AgenticAuthoringValidatorRegistry(resolverRegistry),
                new AgenticAuthoringEffectCompilerRegistry(OBJECT_MAPPER, resolverRegistry),
                new AgenticAuthoringManifestContractValidator());
    }

    @Test
    void executesBoxplotSharedFieldQuartileSeriesCalendarSemesterAndPartialAnnotationsScalability() throws Exception {
        ObjectNode config = (ObjectNode) OBJECT_MAPPER.readTree("""
                {
                  "chartDocument": {
                    "version": "0.1.0",
                    "kind": "boxplot",
                    "source": { "kind": "praxis.stats", "resource": "/api/stats/absences", "operation": "group-by" },
                    "dimensions": [{ "field": "departamento", "role": "category" }],
                    "metrics": [],
                    "referenceBands": [{ "min": 0, "max": 60, "axis": "y", "tone": "success" }],
                    "dataZoom": { "enabled": true, "mode": "inside" }
                  },
                  "availableFields": [
                    { "field": "departamento", "roles": ["dimension"], "operations": ["group-by"] },
                    { "field": "diasAfastado", "roles": ["metric"], "aggregable": true, "aggregations": ["sum", "avg", "min", "max", "count"] }
                  ]
                }
                """);

        for (String aggregation : List.of("min", "percentile-25", "median", "percentile-75", "max", "count")) {
            AgenticAuthoringManifestCompileResult step = service.compilePatch(
                    "praxis-chart",
                    editRequest(config, """
                            {
                              "operationId": "series.add",
                              "input": { "field": "diasAfastado", "aggregation": "%s", "label": "%s" }
                            }
                            """.formatted(aggregation, aggregation)));
            assertThat(step.compiled()).as("series.add boxplot %s failures=%s", aggregation, step.failures()).isTrue();
            config = (ObjectNode) step.patch().path("proposedConfig");
        }
        assertThat(config.path("chartDocument").path("metrics")).hasSize(6);

        // F-06: annotations.configure & scalability.configure resolve without missing sibling overwrites
        AgenticAuthoringManifestCompileResult annotations = service.compilePatch(
                "praxis-chart",
                editRequest(config, """
                        {
                          "operationId": "annotations.configure",
                          "input": { "referenceLines": [{ "value": 80, "axis": "y", "tone": "warning", "label": "SLA" }] }
                        }
                        """));
        assertThat(annotations.compiled()).as("annotations failures=%s", annotations.failures()).isTrue();
        assertThat(annotations.patch().path("patchOperations")).hasSize(1);
        assertThat(annotations.patch().path("proposedConfig").path("chartDocument").path("referenceBands")).hasSize(1);

        AgenticAuthoringManifestCompileResult scalability = service.compilePatch(
                "praxis-chart",
                editRequest(annotations.patch().path("proposedConfig"), """
                        {
                          "operationId": "scalability.configure",
                          "input": { "renderer": "svg" }
                        }
                        """));
        assertThat(scalability.compiled()).as("scalability failures=%s", scalability.failures()).isTrue();
        assertThat(scalability.patch().path("patchOperations")).hasSize(1);
        assertThat(scalability.patch().path("proposedConfig").path("chartDocument").path("dataZoom").path("enabled").asBoolean()).isTrue();
        assertThat(scalability.patch().path("proposedConfig").path("chartDocument").path("renderer").asText()).isEqualTo("svg");

        // F-15 + F-13: Calendar accepts semester range, rejects cartesian annotations/dataZoom
        JsonNode calendarConfig = OBJECT_MAPPER.readTree("""
                {
                  "chartDocument": {
                    "version": "0.1.0",
                    "kind": "calendar",
                    "source": { "kind": "derived" },
                    "dimensions": [{ "field": "data", "role": "time" }],
                    "metrics": [{ "field": "ocorrencias", "aggregation": "sum" }],
                    "calendar": { "range": "2026-S1" }
                  }
                }
                """);
        AgenticAuthoringManifestCompileResult calendarOk = service.compilePatch(
                "praxis-chart",
                editRequest(calendarConfig, """
                        { "operationId": "chart.type.set", "input": { "kind": "calendar" } }
                        """));
        assertThat(calendarOk.compiled()).as("calendar S1 failures=%s", calendarOk.failures()).isTrue();

        AgenticAuthoringManifestValidationResult calendarAnnotationsRejected = service.validateEditPlan(
                "praxis-chart",
                editRequest(calendarConfig, """
                        { "operationId": "annotations.configure", "input": { "referenceLines": [{ "value": 5, "axis": "y" }] } }
                        """));
        assertThat(calendarAnnotationsRejected.valid()).isFalse();

        // F-02: Cyclic sankey rejected deterministically
        AgenticAuthoringManifestValidationResult cyclicSankey = service.validateEditPlan(
                "praxis-chart",
                editRequest(OBJECT_MAPPER.readTree("{}"), """
                        {
                          "operationId": "chart.document.set",
                          "input": {
                            "version": "0.1.0",
                            "kind": "sankey",
                            "source": { "kind": "derived" },
                            "sankey": {
                              "links": [
                                { "source": "A", "target": "B", "value": 10 },
                                { "source": "B", "target": "A", "value": 5 }
                              ]
                            }
                          }
                        }
                        """));
        assertThat(cyclicSankey.valid()).isFalse();
        assertThat(cyclicSankey.failures()).anyMatch(msg -> msg.contains("praxis-relationship-graph"));
    }

    @Test
    void executesRelationshipGraphRootMetaAndNodeStyleRuleUpsertWithoutDollarKey() throws Exception {
        AgenticAuthoringManifestCompileResult metaResult = service.compilePatch(
                "praxis-relationship-graph",
                editRequest(OBJECT_MAPPER.readTree("{}"), """
                        {
                          "operationId": "update-graph-meta",
                          "input": { "title": "Mapa de Vínculos", "subtitle": "Escopo ativo" }
                        }
                        """));
        assertThat(metaResult.compiled()).as("meta failures=%s", metaResult.failures()).isTrue();
        JsonNode metaConfig = metaResult.patch().path("proposedConfig");
        assertThat(metaConfig.has("$")).isFalse();
        assertThat(metaConfig.path("title").asText()).isEqualTo("Mapa de Vínculos");
        assertThat(metaResult.patch().path("patchOperations")).allSatisfy(op ->
                assertThat(op.path("path").asText()).doesNotContain("$"));

        AgenticAuthoringManifestCompileResult insertRule = service.compilePatch(
                "praxis-relationship-graph",
                editRequest(metaConfig, """
                        {
                          "operationId": "upsert-node-style-rule",
                          "input": { "id": "risk-node", "label": "Crítico", "tone": "danger" }
                        }
                        """));
        assertThat(insertRule.compiled()).as("insert rule failures=%s", insertRule.failures()).isTrue();
        JsonNode afterInsert = insertRule.patch().path("proposedConfig");
        assertThat(afterInsert.path("rules").path("nodeStyles")).hasSize(1);
        assertThat(insertRule.patch().path("patchOperations").get(0).path("op").asText()).isEqualTo("add");
        assertThat(insertRule.patch().path("patchOperations").get(0).path("path").asText()).isEqualTo("/rules/nodeStyles/-");

        AgenticAuthoringManifestCompileResult updateRule = service.compilePatch(
                "praxis-relationship-graph",
                editRequest(afterInsert, """
                        {
                          "operationId": "upsert-node-style-rule",
                          "target": "risk-node",
                          "input": { "id": "risk-node", "badge": "ALERTA" }
                        }
                        """));
        assertThat(updateRule.compiled()).as("update rule failures=%s", updateRule.failures()).isTrue();
        assertThat(updateRule.patch().path("proposedConfig").path("rules").path("nodeStyles").get(0).path("badge").asText())
                .isEqualTo("ALERTA");
    }

    @Test
    void executesTimelineLanesRootOperationsAndLaneUpsertWithoutDollarKey() throws Exception {
        AgenticAuthoringManifestCompileResult identity = service.compilePatch(
                "praxis-timeline-lanes",
                editRequest(OBJECT_MAPPER.readTree("{}"), """
                        {
                          "operationId": "timelineLanes.updateIdentity",
                          "input": { "title": "Cronograma Funcional", "subtitle": "2026" }
                        }
                        """));
        assertThat(identity.compiled()).as("timeline identity failures=%s", identity.failures()).isTrue();
        assertThat(identity.patch().path("proposedConfig").has("$")).isFalse();
        assertThat(identity.patch().path("proposedConfig").path("title").asText()).isEqualTo("Cronograma Funcional");

        AgenticAuthoringManifestCompileResult policy = service.compilePatch(
                "praxis-timeline-lanes",
                editRequest(identity.patch().path("proposedConfig"), """
                        {
                          "operationId": "timelineLanes.configureTemporalPolicy",
                          "input": { "openEndedPolicy": "project-to-reference", "referenceDate": "2026-11-18" }
                        }
                        """));
        assertThat(policy.compiled()).as("timeline policy failures=%s", policy.failures()).isTrue();
        assertThat(policy.patch().path("proposedConfig").has("$")).isFalse();
        assertThat(policy.patch().path("proposedConfig").path("referenceDate").asText()).isEqualTo("2026-11-18");

        AgenticAuthoringManifestCompileResult upsertLane = service.compilePatch(
                "praxis-timeline-lanes",
                editRequest(policy.patch().path("proposedConfig"), """
                        {
                          "operationId": "timelineLanes.upsertLane",
                          "input": { "key": "ferias", "label": "Férias", "tone": "info" }
                        }
                        """));
        assertThat(upsertLane.compiled()).as("upsertLane failures=%s", upsertLane.failures()).isTrue();
        assertThat(upsertLane.patch().path("proposedConfig").path("lanes")).hasSize(1);
    }

    @Test
    void executesProgressionLadderAndAnalyticsPresentationRuntimeResolves() throws Exception {
        JsonNode ladderConfig = OBJECT_MAPPER.readTree("""
                {
                  "document": {
                    "variant": "staircase",
                    "showFinancialDetails": true,
                    "steps": []
                  }
                }
                """);
        AgenticAuthoringManifestCompileResult ladderOk = service.compilePatch(
                "praxis-progression-ladder",
                editRequest(ladderConfig, """
                        { "operationId": "progressionLadder.variant.set", "input": { "variant": "matrix-2d" } }
                        """));
        assertThat(ladderOk.compiled()).as("ladder failures=%s", ladderOk.failures()).isTrue();
        assertThat(ladderOk.patch().path("proposedConfig").path("document").path("variant").asText()).isEqualTo("matrix-2d");

        AgenticAuthoringManifestValidationResult ladderBad = service.validateEditPlan(
                "praxis-progression-ladder",
                editRequest(ladderConfig, """
                        { "operationId": "progressionLadder.variant.set", "input": { "variant": "radar-3d" } }
                        """));
        assertThat(ladderBad.valid()).isFalse();

        JsonNode analyticsConfig = OBJECT_MAPPER.readTree("""
                {
                  "projectionId": "supplier-procurement-funnel",
                  "preferredFamily": "chart"
                }
                """);
        AgenticAuthoringManifestCompileResult analyticsOk = service.compilePatch(
                "praxis-analytics-presentation",
                editRequest(analyticsConfig, """
                        { "operationId": "analytics.presentation.prefer-family", "input": { "family": "kpi" } }
                        """));
        assertThat(analyticsOk.compiled()).as("analytics failures=%s", analyticsOk.failures()).isTrue();
        assertThat(analyticsOk.patch().path("proposedConfig").path("preferredFamily").asText()).isEqualTo("kpi");

        AgenticAuthoringManifestValidationResult analyticsBad = service.validateEditPlan(
                "praxis-analytics-presentation",
                editRequest(analyticsConfig, """
                        { "operationId": "analytics.presentation.prefer-family", "input": { "family": "carousel" } }
                        """));
        assertThat(analyticsBad.valid()).isFalse();
    }

    private AgenticAuthoringManifestEditPlanRequest editRequest(JsonNode config, String planJson) throws Exception {
        JsonNode plan = OBJECT_MAPPER.readTree(planJson);
        return new AgenticAuthoringManifestEditPlanRequest(config, plan);
    }
}
