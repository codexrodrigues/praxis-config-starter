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
        assertNoRootPatchPointer(ladderOk);

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

    @Test
    void doesNotEmitRootPointerForSetValueOnProgressionLadderOrganogramAndStepper() throws Exception {
        JsonNode ladderConfig = OBJECT_MAPPER.readTree("""
                { "document": { "variant": "staircase", "showFinancialDetails": false, "steps": [] } }
                """);
        AgenticAuthoringManifestCompileResult ladderResult = service.compilePatch(
                "praxis-progression-ladder",
                editRequest(ladderConfig, """
                        { "operationId": "progressionLadder.variant.set", "input": { "variant": "pillars" } }
                        """));
        assertThat(ladderResult.compiled()).as("ladderResult failures=%s", ladderResult.failures()).isTrue();
        assertNoRootPatchPointer(ladderResult);
        assertThat(ladderResult.patch().path("patchOperations").get(0).path("path").asText())
                .isEqualTo("/document/variant");

        JsonNode organogramConfig = OBJECT_MAPPER.readTree("""
                { "layout": { "topology": "orthogonal-compact" }, "nodes": [] }
                """);
        AgenticAuthoringManifestCompileResult organogramResult = service.compilePatch(
                "praxis-organogram",
                editRequest(organogramConfig, """
                        { "operationId": "organogram.topology.set", "input": { "topology": "radial-360" } }
                        """));
        assertThat(organogramResult.compiled()).as("organogramResult failures=%s", organogramResult.failures()).isTrue();
        assertNoRootPatchPointer(organogramResult);
        assertThat(organogramResult.patch().path("patchOperations").get(0).path("path").asText())
                .isEqualTo("/layout/topology");

        JsonNode stepperConfig = OBJECT_MAPPER.readTree("""
                { "orientation": "horizontal", "headerPosition": "top", "steps": [{ "id": "s1", "label": "S1" }] }
                """);
        AgenticAuthoringManifestCompileResult stepperResult = service.compilePatch(
                "praxis-stepper",
                editRequest(stepperConfig, """
                        { "operationId": "orientation.set", "input": { "orientation": "vertical", "headerPosition": "bottom" } }
                        """));
        assertThat(stepperResult.compiled()).as("stepperResult failures=%s", stepperResult.failures()).isTrue();
        assertNoRootPatchPointer(stepperResult);
        JsonNode stepperPatchOps = stepperResult.patch().path("patchOperations");
        assertThat(stepperPatchOps).hasSize(2);
        assertThat(stepperPatchOps)
                .extracting(op -> op.path("path").asText())
                .containsExactlyInAnyOrder("/orientation", "/headerPosition");
        assertThat(stepperPatchOps.get(0).path("value").asText()).isEqualTo("vertical");
        assertThat(stepperPatchOps.get(1).path("value").asText()).isEqualTo("bottom");
        JsonNode stepperProposed = stepperResult.patch().path("proposedConfig");
        assertThat(stepperProposed.path("orientation").asText()).isEqualTo("vertical");
        assertThat(stepperProposed.path("headerPosition").asText()).isEqualTo("bottom");
        assertThat(stepperProposed.has("labelPosition")).isFalse();
        assertThat(stepperProposed.has("density")).isFalse();

        AgenticAuthoringManifestCompileResult navResult = service.compilePatch(
                "praxis-stepper",
                editRequest(stepperProposed, """
                        { "operationId": "navigation.mode.set", "input": { "linear": true, "nextLabel": "Avançar" } }
                        """));
        assertThat(navResult.compiled()).as("navResult failures=%s", navResult.failures()).isTrue();
        JsonNode navProposed = navResult.patch().path("proposedConfig");
        assertThat(navProposed.path("linear").asBoolean()).isTrue();
        assertThat(navProposed.path("navigation").path("nextLabel").asText()).isEqualTo("Avançar");
        assertThat(navProposed.path("navigation").has("linear")).isFalse();
        assertThat(navProposed.path("navigation").has("prevLabel")).isFalse();
    }

    @Test
    void executesCrudGovernanceContextKpiBandFilterBarAndListSurfaceExtras() throws Exception {
        ObjectNode crudConfig = (ObjectNode) OBJECT_MAPPER.readTree("""
                {
                  "crudId": "employees-crud",
                  "resource": { "path": "/funcionarios", "resourceKey": "human-resources.funcionarios", "idField": "id" },
                  "table": { "columns": [] }
                }
                """);

        AgenticAuthoringManifestCompileResult attachGov = service.compilePatch(
                "praxis-crud",
                editRequest(crudConfig, """
                        {
                          "operationId": "domain.governanceContext.attach",
                          "target": "domainCatalog:human-resources.funcionarios:cpf",
                          "input": {
                            "resourceKey": "human-resources.funcionarios",
                            "releaseId": "2026.1",
                            "query": "cpf",
                            "itemTypes": ["governance"],
                            "intent": "authoring"
                          }
                        }
                        """));
        assertThat(attachGov.compiled()).as("attachGov failures=%s", attachGov.failures()).isTrue();
        assertThat(attachGov.patch().path("proposedConfig").path("queryContext").path("meta").path("domainCatalog")
                .path("resourceKey").asText()).isEqualTo("human-resources.funcionarios");
        assertThat(attachGov.patch().path("proposedConfig").path("queryContext").path("meta").path("domainCatalog")
                .path("query").asText()).isEqualTo("cpf");

        ObjectNode current = (ObjectNode) attachGov.patch().path("proposedConfig");
        AgenticAuthoringManifestCompileResult kpiBand = service.compilePatch(
                "praxis-crud",
                editRequest(current, """
                        {
                          "operationId": "list.kpiBand.configure",
                          "input": {
                            "enabled": true,
                            "columns": 2,
                            "cards": [
                              {
                                "id": "ativos",
                                "label": "Ativos",
                                "valueExpr": "${totalCount}",
                                "visualization": { "kind": "delta", "value": 12, "fallbackText": "+12" }
                              }
                            ]
                          }
                        }
                        """));
        assertThat(kpiBand.compiled()).as("kpiBand failures=%s", kpiBand.failures()).isTrue();
        assertThat(kpiBand.patch().path("proposedConfig").path("kpiBand").path("cards").get(0).path("label").asText())
                .isEqualTo("Ativos");

        current = (ObjectNode) kpiBand.patch().path("proposedConfig");
        AgenticAuthoringManifestCompileResult filterBar = service.compilePatch(
                "praxis-crud",
                editRequest(current, """
                        {
                          "operationId": "list.filterBar.configure",
                          "input": {
                            "inlineFields": ["nome"],
                            "quickFilters": [
                              { "id": "ativos", "label": "Ativos", "filter": { "ativo": true } }
                            ],
                            "showAdvanced": true
                          }
                        }
                        """));
        assertThat(filterBar.compiled()).as("filterBar failures=%s", filterBar.failures()).isTrue();
        assertThat(filterBar.patch().path("proposedConfig").path("filterBar").path("quickFilters")).hasSize(1);

        current = (ObjectNode) filterBar.patch().path("proposedConfig");
        AgenticAuthoringManifestCompileResult listSurface = service.compilePatch(
                "praxis-crud",
                editRequest(current, """
                        {
                          "operationId": "list.surface.configure",
                          "input": {
                            "pageHeader": { "title": "Funcionários", "domain": { "label": "RH", "tone": "primary" } },
                            "kpiBand": { "enabled": true, "cards": [{ "label": "Total", "value": 42 }] },
                            "filterBar": { "inlineFields": ["departamento"], "showAdvanced": false }
                          }
                        }
                        """));
        assertThat(listSurface.compiled()).as("listSurface failures=%s", listSurface.failures()).isTrue();
        assertThat(listSurface.patch().path("proposedConfig").path("pageHeader").path("title").asText())
                .isEqualTo("Funcionários");
        assertThat(listSurface.patch().path("proposedConfig").path("kpiBand").path("cards")).hasSize(1);
        assertThat(listSurface.patch().path("proposedConfig").path("filterBar").path("inlineFields").get(0).asText())
                .isEqualTo("departamento");

        AgenticAuthoringManifestValidationResult forbiddenGov = service.validateEditPlan(
                "praxis-crud",
                editRequest(crudConfig, """
                        {
                          "operationId": "domain.governanceContext.attach",
                          "target": "domainCatalog:human-resources.funcionarios:cpf",
                          "input": {
                            "resourceKey": "human-resources.funcionarios",
                            "query": "cpf",
                            "aiUsage": { "visibility": "forbidden" },
                            "rules": [{ "field": "cpf", "required": true }]
                          }
                        }
                        """));
        assertThat(forbiddenGov.valid()).isFalse();
        assertThat(forbiddenGov.failures()).anyMatch(msg -> msg.contains("ai-usage-visibility-respected"));
        assertThat(forbiddenGov.failures()).anyMatch(msg -> msg.contains("no-form-config-rule-materialization"));
    }

    private void assertNoRootPatchPointer(AgenticAuthoringManifestCompileResult result) {
        JsonNode patchOperations = result.patch().path("patchOperations");
        assertThat(patchOperations.isArray()).isTrue();
        assertThat(patchOperations).isNotEmpty();
        for (JsonNode patchOperation : patchOperations) {
            assertThat(patchOperation.path("path").asText())
                    .as("patchOperation must not overwrite root document: %s", patchOperation)
                    .isNotEqualTo("/");
        }
    }

    private AgenticAuthoringManifestEditPlanRequest editRequest(JsonNode config, String planJson) throws Exception {
        JsonNode plan = OBJECT_MAPPER.readTree(planJson);
        return new AgenticAuthoringManifestEditPlanRequest(config, plan);
    }
}
