package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiProviderManagementService;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AgenticAuthoringProviderSchemaCompilerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringProviderSchemaCompiler compiler = new AgenticAuthoringProviderSchemaCompiler(objectMapper);

    @Mock
    private AiProviderManagementService providerManagementService;

    @Mock
    private AgenticAuthoringManifestService manifestService;

    @Test
    void compilesEveryTableOperationIntoAProviderSafeStructuredOutputSchemaWithoutMutatingTheManifest() throws Exception {
        JsonNode manifest = tableManifest();
        JsonNode original = manifest.deepCopy();
        List<JsonNode> operations = new ArrayList<>();
        manifest.path("operations").forEach(operations::add);

        JsonNode schema = compiler.compileEditPlanSchema("praxis-component-edit-plan.v1", "praxis-table", operations);

        assertThat(operations).hasSize(68);
        assertProviderSafe(schema);
        assertThat(manifest).isEqualTo(original);
    }

    @Test
    void compilesComplexRendererOperationsWithoutUnsupportedConditionalSchemas() throws Exception {
        JsonNode manifest = tableManifest();
        for (String operationId : List.of("column.renderer.set", "column.conditionalRenderer.add")) {
            JsonNode operation = operation(manifest, operationId);
            JsonNode schema = compiler.compileEditPlanSchema(
                    "praxis-component-edit-plan.v1", "praxis-table", List.of(operation));
            assertProviderSafe(schema);
            assertThat(schema.toString()).doesNotContain("\"if\"", "\"then\"", "\"else\"", "\"allOf\"");
        }
    }

    @Test
    void encodesUnconstrainedValuesAndArraysWithoutItemsForProviderTransport() throws Exception {
        JsonNode manifest = tableManifest();

        JsonNode type = compiler.compileEditPlanSchema(
                "praxis-component-edit-plan.v1", "praxis-table", List.of(operation(manifest, "column.type.set")));
        assertTypeIncludes(type.at("/properties/operations/items/properties/input/properties/type"), "string");

        JsonNode styleRule = compiler.compileEditPlanSchema(
                "praxis-component-edit-plan.v1", "praxis-table", List.of(operation(manifest, "row.styleRule.add")));
        assertTypeIncludes(styleRule.at("/properties/operations/items/properties/input/properties/style"), "string");
        assertTypeIncludes(styleRule.at("/properties/operations/items/properties/input/properties/effects"), "array");
    }

    @Test
    void decodesOnlyProviderTransportValuesAndLeavesInvalidCanonicalParametersForManifestValidation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"operations":[{"operationId":"column.renderer.set","inputSchema":{
                  "type":"object","required":["renderer"],"properties":{
                    "renderer":{"type":"string"},"compose":{"type":"object"}}}}]}
                """);
        JsonNode providerPlan = objectMapper.readTree("""
                {"operations":[{"operationId":"column.renderer.set","target":null,"confirmed":null,
                  "input":{"renderer":"unknown-renderer","compose":"{\\"imageField\\":\\"photo\\"}"}}]}
                """);

        JsonNode decoded = compiler.decodeCompatibilityValues(providerPlan, manifest);

        assertThat(decoded.at("/operations/0/target").isMissingNode()).isTrue();
        assertThat(decoded.at("/operations/0/confirmed").isMissingNode()).isTrue();
        assertThat(decoded.at("/operations/0/input/compose/imageField").asText()).isEqualTo("photo");
        // The compiler transports only provider incompatibilities. It never normalizes an invalid
        // canonical enum/value, so the manifest validator still receives and rejects it.
        assertThat(decoded.at("/operations/0/input/renderer").asText()).isEqualTo("unknown-renderer");
    }

    @Test
    void decodesStructuredRendererTransportBeforeCanonicalManifestValidation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"operations":[{"operationId":"column.conditionalRenderer.add","inputSchema":{
                  "type":"object","required":["renderer"],"properties":{
                    "renderer":{"type":"object","required":["type"],"properties":{
                      "type":{"enum":["chip","badge"]},
                      "chip":{"type":"object","properties":{"color":{"type":"string"}}}
                    }}}}}]}
                """);
        JsonNode providerPlan = objectMapper.readTree("""
                {"operations":[{"operationId":"column.conditionalRenderer.add","input":{
                  "renderer":"{\\\"type\\\":\\\"chip\\\",\\\"chip\\\":{\\\"color\\\":\\\"red\\\"}}"
                }}]}
                """);

        JsonNode decoded = compiler.decodeCompatibilityValues(providerPlan, manifest);

        assertThat(decoded.at("/operations/0/input/renderer/type").asText()).isEqualTo("chip");
        assertThat(decoded.at("/operations/0/input/renderer/chip/color").asText()).isEqualTo("red");
    }

    @Test
    void removesEmptyCompatibilityObjectsForRendererVariantsThatWereNotSelected() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"operations":[{"operationId":"column.renderer.set","inputSchema":{
                  "type":"object","required":["type"],"properties":{
                    "type":{"enum":["chip","compose"]},
                    "chip":{"type":"object","anyOf":[{"required":["text"]},{"required":["textField"]}],
                      "properties":{"text":{"type":"string"},"textField":{"type":"string"},"color":{"type":"string"}}},
                    "compose":{"type":"object","required":["items"],"properties":{
                      "items":{"type":"array","items":{"type":"object"}}
                    }}
                  }}}]}
                """);
        JsonNode providerPlan = objectMapper.readTree("""
                {"operations":[{"operationId":"column.renderer.set","input":{
                  "type":"compose",
                  "chip":{"text":null,"textField":null,"color":null},
                  "compose":{"items":[{"type":"image"}]}
                }}]}
                """);

        JsonNode decoded = compiler.decodeCompatibilityValues(providerPlan, manifest);

        assertThat(decoded.at("/operations/0/input/chip").isMissingNode()).isTrue();
        assertThat(decoded.at("/operations/0/input/compose/items/0/type").asText()).isEqualTo("image");
    }

    @Test
    void keepsAnInvalidProviderParameterForTheCanonicalManifestToRejectAfterDecoding() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"componentId":"praxis-table","operations":[{"operationId":"column.renderer.set","inputSchema":{
                  "type":"object","required":["renderer"],"properties":{"renderer":{"enum":["image","compose"]},"compose":{"type":"object"}}}}]}
                """);
        JsonNode selection = objectMapper.readTree("""
                {"schemaVersion":"praxis-semantic-operation-selection.v2","componentId":"praxis-table",
                 "goals":[{"description":"Set renderer","targetConcept":"column","operationIds":["column.renderer.set"]}],
                 "selectedOperationIds":["column.renderer.set"],"requiresClarification":false,"clarificationReason":""}
                """);
        JsonNode providerPlan = objectMapper.readTree("""
                {"schemaVersion":"praxis-component-edit-plan.v1","componentId":"praxis-table","operations":[{
                  "operationId":"column.renderer.set","target":null,"confirmed":null,
                  "input":{"renderer":"not-a-renderer","compose":"{\\"imageField\\":\\"photo\\"}"}}]}
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(providerManagementService.generateJson(any(), any(), any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(selection, providerPlan);
        when(manifestService.compilePatch(eq("praxis-table"), any())).thenReturn(
                new AgenticAuthoringManifestCompileResult(false, List.of("RENDERER_TYPE_UNKNOWN"), List.of(), null));

        AgenticAuthoringComponentEditPlanResult result = new AgenticAuthoringComponentEditPlanService(
                providerManagementService, manifestService, objectMapper).generateAndCompile(
                        new AgenticAuthoringPlanRequest("configured", "openai", "gpt", "key"),
                        "praxis-table", objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                        "tenant", "user", "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("component-edit-plan-manifest-validation-failed", "RENDERER_TYPE_UNKNOWN");
        ArgumentCaptor<AgenticAuthoringManifestEditPlanRequest> request = ArgumentCaptor.forClass(AgenticAuthoringManifestEditPlanRequest.class);
        verify(manifestService, org.mockito.Mockito.atLeastOnce()).compilePatch(eq("praxis-table"), request.capture());
        assertThat(request.getAllValues().get(0).plan().at("/operations/0/input/renderer").asText()).isEqualTo("not-a-renderer");
        assertThat(request.getAllValues().get(0).plan().at("/operations/0/input/compose/imageField").asText()).isEqualTo("photo");
    }

    private JsonNode tableManifest() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/ai-registry/registry-snapshot.json")) {
            assertThat(input).isNotNull();
            JsonNode manifest = objectMapper.readTree(input).path("components").path("praxis-table").path("authoringManifest");
            assertThat(manifest.isObject()).isTrue();
            return manifest;
        }
    }

    private JsonNode operation(JsonNode manifest, String id) {
        for (JsonNode operation : manifest.path("operations")) {
            if (id.equals(operation.path("operationId").asText())) return operation;
        }
        throw new AssertionError("Missing operation " + id);
    }

    private void assertProviderSafe(JsonNode schema) {
        assertThat(schema.isObject()).as(schema.toString()).isTrue();
        assertThat(hasProviderValueShape(schema)).as(schema.toString()).isTrue();
        if (schema.isObject()) {
            if (declaresObject(schema)) {
                assertThat(schema.path("additionalProperties").isBoolean()).as(schema.toString()).isTrue();
                assertThat(schema.path("additionalProperties").asBoolean()).as(schema.toString()).isFalse();
                Set<String> properties = new HashSet<>();
                schema.path("properties").fieldNames().forEachRemaining(properties::add);
                Set<String> required = new HashSet<>();
                schema.path("required").forEach(value -> required.add(value.asText()));
                assertThat(required).as(schema.toString()).containsExactlyInAnyOrderElementsOf(properties);
            }
            if (schema.path("enum").isArray()) assertEnumHasCompatibleType(schema);
            if (schema.path("anyOf").isArray()) {
                assertThat(schema.path("anyOf")).isNotEmpty();
                schema.path("anyOf").forEach(branch -> assertProviderSafe(branch));
            }
            if (declaresArray(schema)) {
                assertThat(schema.path("items").isObject()).as(schema.toString()).isTrue();
                assertProviderSafe(schema.path("items"));
            }
            if (schema.path("properties").isObject()) schema.path("properties").forEach(this::assertProviderSafe);
        }
    }

    private boolean hasProviderValueShape(JsonNode schema) {
        return schema.has("type") || schema.has("enum") || schema.has("const") || schema.path("anyOf").isArray();
    }

    private boolean declaresObject(JsonNode schema) {
        if ("object".equals(schema.path("type").asText())) return true;
        for (JsonNode type : schema.path("type")) if ("object".equals(type.asText())) return true;
        return false;
    }

    private boolean declaresArray(JsonNode schema) {
        if ("array".equals(schema.path("type").asText())) return true;
        for (JsonNode type : schema.path("type")) if ("array".equals(type.asText())) return true;
        return false;
    }

    private void assertTypeIncludes(JsonNode schema, String expected) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            assertThat(type.asText()).isEqualTo(expected);
        } else {
            assertThat(type).extracting(JsonNode::asText).contains(expected);
        }
    }

    private void assertEnumHasCompatibleType(JsonNode schema) {
        Set<String> types = new HashSet<>();
        JsonNode declared = schema.path("type");
        if (declared.isTextual()) types.add(declared.asText());
        else declared.forEach(value -> types.add(value.asText()));
        assertThat(types).as(schema.toString()).isNotEmpty();
        for (JsonNode value : schema.path("enum")) {
            String expected = value.isTextual() ? "string" : value.isBoolean() ? "boolean"
                    : value.isIntegralNumber() ? "integer" : value.isNumber() ? "number" : "null";
            assertThat(types.contains(expected) || ("integer".equals(expected) && types.contains("number")))
                    .as(schema.toString()).isTrue();
        }
    }
}
