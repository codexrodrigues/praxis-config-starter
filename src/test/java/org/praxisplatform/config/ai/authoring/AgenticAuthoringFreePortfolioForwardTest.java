package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.*;

/** Owner-level forward tests. Semantic choices and metadata are synthetic;
 * compilation is real. These tests do not certify live provider or browser behavior. */
@Tag("unit")
class AgenticAuthoringFreePortfolioForwardTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void createFormUsesRequestSchemaRequiredFieldsAndCanonicalOptionSource(String domain) throws Exception {
        String resource = "/api/synthetic/" + domain;
        var provider = mock(AiProviderManagementService.class);
        var properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(java.nio.file.Path.of("docs/ai/agentic-authoring/proofs"));
        var schema = schema(domain);
        var intent = intent(resource, "form", null);
        var planService = new AgenticAuthoringPlanService(provider, properties, mapper);
        var request = new AgenticAuthoringPlanRequest("Quero somente nome e equipe.", "openai", "gpt-5-mini", null, intent);
        ObjectNode semanticPlan = (ObjectNode) planService.buildCanonicalCreateFormFieldCatalog(request, schema).minimalFormPlan();
        var selectedFields = mapper.createArrayNode();
        for (JsonNode field : semanticPlan.path("fields")) {
            if (List.of("name", "groupId").contains(field.path("name").asText())) selectedFields.add(field);
        }
        semanticPlan.set("fields", selectedFields);
        when(provider.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any())).thenReturn(semanticPlan);
        var plan = planService.generateCreateFormPlanFromCanonicalSchema(request, schema, "synthetic", "proof", "local");
        assertThat(plan.valid()).as(plan.failureCodes().toString()).isTrue();
        assertThat(plan.minimalFormPlan().path("fields").findValuesAsText("name"))
                .containsExactly("name", "groupId");
        JsonNode group = plan.minimalFormPlan().path("fields").findParents("name").stream()
                .filter(field -> "groupId".equals(field.path("name").asText())).findFirst().orElseThrow();
        assertThat(group.path("required").asBoolean()).isTrue();
        assertThat(group.path("optionSource").asText()).isEqualTo(domain + ".groups");
        var compiled = new AgenticAuthoringPatchCompilerService(properties, mapper)
                .compile(new AgenticAuthoringCompileRequest(plan.minimalFormPlan(), null, intent));
        assertThat(compiled.valid()).as(compiled.failureCodes().toString()).isTrue();
        assertThat(compiled.compiledFormPatch().toString()).contains(resource, "praxis-dynamic-form");
        assertThat(plan.minimalFormPlan().path("sourceRefs").toString())
                .contains("operation=post&schemaType=request");
        writeFixture(domain, "form", intent, mapper.valueToTree(compiled));
        var config = compiled.compiledFormPatch().at("/patch/page/widgets/0/definition/inputs/config");
        assertThat(config.path("sections").findValuesAsText("fieldName")).containsExactly("name", "groupId");
        assertThat(config.path("fieldMetadata").findValuesAsText("name")).containsExactly("name", "groupId");
        verify(provider).generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "shipments"})
    void dashboardGroundsCountDimensionDetailAndCrossFilter(String domain) throws Exception {
        String resource = "/api/synthetic/" + domain;
        var provider = mock(AiProviderManagementService.class);
        var schemas = mock(SchemaRetrievalService.class);
        var capabilities = mock(ResourceCapabilitiesRetrievalService.class);
        var schema = schema(domain);
        when(schemas.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost:8088/schemas/filtered"));
        ObjectNode stats = mapper.createObjectNode();
        stats.putObject("stats").putArray("fields").addObject()
                .put("field", "group").put("groupByEligible", true).putArray("metrics").add("COUNT");
        when(capabilities.fetchCapabilitiesResult(eq(resource), any(), any(), any(), any()))
                .thenReturn(ResourceCapabilitiesFetchResult.success(stats, resource + "/capabilities"));
        var visual = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1", "grouped monitoring", "dashboard", "praxis-chart",
                List.of(new AgenticAuthoringVisualizationAxisDecision("group", "group", "Grupo", "bar", "vertical",
                        "count", null, "Total", "llm-stub")), true, true, "llm-stub");
        var properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(java.nio.file.Path.of("docs/ai/agentic-authoring/proofs"));
        var preview = new AgenticAuthoringPreviewService(new AgenticAuthoringPlanService(provider, properties, mapper),
                new AgenticAuthoringPatchCompilerService(properties, mapper), mapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(mapper)), null, schemas, capabilities)
                .preview(new AgenticAuthoringPlanRequest("Mostre a quantidade por grupo e os registros relacionados.",
                        "openai", null, null, intent(resource, "dashboard", visual)), "synthetic", "proof", "local", "http://localhost:8088");
        assertThat(preview.valid()).as(preview.failureCodes().toString()).isTrue();
        assertThat(preview.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .contains("praxis-chart", "praxis-table");
        assertThat(preview.uiCompositionPlan().path("bindings").toString()).contains("crossFilter", "queryContext", "group");
        assertThat(preview.uiCompositionPlan().toString()).contains("count", "group");
        assertThat(preview.compiledFormPatch().at("/patch/page/widgets").size()).isGreaterThan(1);
        var chart = java.util.stream.StreamSupport.stream(preview.uiCompositionPlan().path("widgets").spliterator(), false)
                .filter(widget -> "praxis-chart".equals(widget.path("componentId").asText())).findFirst().orElseThrow();
        assertThat(chart.at("/inputs/chartDocument/metrics/0/field").asText()).isEqualTo("total");
        assertThat(chart.at("/inputs/config/dataSource/query/statsRequest/metric").has("field")).isFalse();
        writeFixture(domain, "dashboard", intent(resource, "dashboard", visual), mapper.valueToTree(preview));
        verify(capabilities).fetchCapabilitiesResult(eq(resource), any(), any(), any(), any());
        verifyNoInteractions(provider);
    }

    private void writeFixture(String domain, String artifact, AgenticAuthoringIntentResolutionResult intent,
            JsonNode preview) throws Exception {
        var directory = java.nio.file.Path.of("target/free-authoring");
        java.nio.file.Files.createDirectories(directory);
        var fixture = mapper.createObjectNode();
        fixture.set("intentResolution", mapper.valueToTree(intent));
        fixture.set("preview", preview);
        fixture.set("schema", schema(domain));
        java.nio.file.Files.writeString(directory.resolve(domain + "-" + artifact + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixture));
    }

    private ObjectNode schema(String domain) {
        var schema = mapper.createObjectNode().put("type", "object");
        schema.putArray("required").add("name").add("groupId");
        var fields = schema.putObject("properties");
        fields.putObject("id").put("type", "integer").put("readOnly", true);
        fields.putObject("name").put("type", "string").putObject("x-ui").put("label", "Nome").put("controlType", "input");
        fields.putObject("notes").put("type", "string").putObject("x-ui").put("label", "Observações opcionais");
        fields.putObject("group").put("type", "string").putArray("enum").add("A").add("B");
        fields.putObject("groupId").put("type", "integer").putObject("x-ui").put("label", "Grupo")
                .put("controlType", "select").putObject("optionSource").put("key", domain + ".groups")
                .put("resourcePath", "/api/synthetic/" + domain).put("type", "RESOURCE_ENTITY")
                .put("valuePropertyPath", "id").put("labelPropertyPath", "label");
        return schema;
    }

    private AgenticAuthoringIntentResolutionResult intent(String resource, String artifact,
            AgenticAuthoringVisualizationDecision visual) throws Exception {
        var node = mapper.createObjectNode().put("valid", true).put("operationKind", "create")
                .put("artifactKind", artifact).put("changeKind", "create_artifact")
                .put("authoringProfile", "form".equals(artifact) ? "create-minimal-form" : "generic-page-change")
                .put("targetApp", "praxis-ui-angular").put("targetComponentId", "praxis-dynamic-page-builder");
        String operationPath = resource + ("form".equals(artifact) ? "" : "/filter");
        node.set("selectedCandidate", mapper.valueToTree(new AgenticAuthoringCandidate(resource, "post",
                "/schemas/filtered?path=" + operationPath + "&operation=post&schemaType=" + ("form".equals(artifact) ? "request" : "response"),
                operationPath, "POST", 0.98, "Synthetic canonical metadata", List.of("api-metadata", "semantic-retrieval"))));
        node.set("gate", mapper.valueToTree(new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of())));
        node.set("visualizationDecision", mapper.valueToTree(visual));
        for (String field : List.of("candidates", "quickReplies", "clarificationQuestions", "warnings", "failureCodes")) node.putArray(field);
        return mapper.treeToValue(node, AgenticAuthoringIntentResolutionResult.class);
    }
}
