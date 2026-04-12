package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class AgenticAuthoringPatchCompilerServiceTest {

    @TempDir
    private Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compileBuildsPatchFromMinimalPlanAndCatalog() throws Exception {
        writeCatalog();

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(minimalPlan()));

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.compiledFormPatch().path("profileId").asText()).isEqualTo("create-minimal-form");
        assertThat(result.compiledFormPatch().path("catalogReleaseId").asText()).isEqualTo("catalog-release-test");
        assertThat(result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("inputs").path("schemaUrl").asText()).isEqualTo("/schemas/request");
        assertThat(result.compiledFormPatch().path("compatibility").path("aiHttpContract").asText()).isEqualTo("v1.1");
        assertThat(result.compiledFormPatch().path("compatibility").path("requiresV12").asBoolean()).isFalse();
    }

    @Test
    void compileRejectsInvalidMinimalPlanWithoutBuildingPatch() throws Exception {
        writeCatalog();
        ObjectNode plan = minimalPlan();
        ((ArrayNode) plan.path("fields")).removeAll();

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(plan));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("fields must not be empty");
        assertThat(result.compiledFormPatch().isEmpty()).isTrue();
    }

    @Test
    void compileUsesIntentCandidateAsRuntimeEndpoint() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(plan, funcionariosIntent()));

        assertThat(result.valid()).isTrue();
        ObjectNode inputs = (ObjectNode) result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("inputs");
        assertThat(inputs.path("schemaUrl").asText())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        assertThat(inputs.path("responseSchemaUrl").asText())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=response");
        assertThat(inputs.path("submitUrl").asText()).isEqualTo("/api/human-resources/funcionarios");
        assertThat(inputs.path("submitMethod").asText()).isEqualTo("post");
        assertThat(result.compiledFormPatch().path("catalogReleaseId").asText())
                .isEqualTo("praxis-ui-angular.create-minimal-form.intent-resolution.v0.1.0");
        assertThat(result.warnings()).contains("compiled-from-intent-resolution");
    }

    private AgenticAuthoringPatchCompilerService service() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(tempDir);
        properties.setPageCreateCatalog("page-create-catalog.v0.json");
        return new AgenticAuthoringPatchCompilerService(properties, objectMapper);
    }

    private void writeCatalog() throws Exception {
        ObjectNode catalog = objectMapper.createObjectNode();
        catalog.put("profileId", "create-minimal-form");
        catalog.put("targetComponent", "praxis-dynamic-page-builder");
        catalog.put("catalogReleaseId", "catalog-release-test");
        ArrayNode widgets = catalog.putArray("allowedWidgets");
        ObjectNode form = widgets.addObject();
        form.put("id", "praxis-dynamic-form");
        form.put("eligible", true);
        ObjectNode evidence = catalog.putObject("evidence");
        ObjectNode schemaRefs = evidence.putObject("schemaRefs");
        schemaRefs.put("request", "/schemas/request");
        schemaRefs.put("response", "/schemas/response");
        ObjectNode operation = evidence.putObject("operationRef");
        operation.put("method", "post");
        operation.put("path", "/api/helpdesk/chamados");
        Files.writeString(tempDir.resolve("page-create-catalog.v0.json"), objectMapper.writeValueAsString(catalog));
    }

    private ObjectNode minimalPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", "praxis-helpdesk-ui");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("apiUseCaseResolutionRef", "proofs/helpdesk-create-ticket-discovery.md#api-use-case");
        plan.put("fieldSelectionPlanRef", "proofs/helpdesk-create-ticket-discovery.md#field-selection");
        plan.put("submitActionRef", "POST /api/helpdesk/chamados");
        ArrayNode fields = plan.putArray("fields");
        ObjectNode title = fields.addObject();
        title.put("name", "titulo");
        title.put("label", "Titulo");
        title.put("controlType", "text");
        title.put("required", true);
        ObjectNode description = fields.addObject();
        description.put("name", "descricao");
        description.put("label", "Descricao");
        description.put("controlType", "textarea");
        description.put("required", false);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        plan.putArray("sourceRefs").add("docs/ai/agentic-authoring/proofs/helpdesk-create-ticket-discovery.md");
        return plan;
    }

    private ObjectNode funcionariosPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", "praxis-ui-angular");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("apiUseCaseResolutionRef", "intent-resolution:/api/human-resources/funcionarios");
        plan.put("fieldSelectionPlanRef", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        plan.put("submitActionRef", "POST /api/human-resources/funcionarios");
        ArrayNode fields = plan.putArray("fields");
        ObjectNode nome = fields.addObject();
        nome.put("name", "nome");
        nome.put("label", "Nome");
        nome.put("controlType", "text");
        nome.put("required", true);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        plan.putArray("sourceRefs").add("intent-resolution").add("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        return plan;
    }

    private AgenticAuthoringIntentResolutionResult funcionariosIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "form",
                "create_minimal_form",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.95,
                        "matched funcionarios",
                        java.util.List.of("funcionarios")),
                java.util.List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                objectMapper.createObjectNode());
    }
}
