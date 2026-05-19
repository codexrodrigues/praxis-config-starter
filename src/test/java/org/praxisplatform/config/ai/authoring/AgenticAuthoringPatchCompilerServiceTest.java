package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
                .compile(new AgenticAuthoringCompileRequest(funcionariosPlan(), funcionariosIntent()));

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.compiledFormPatch().path("profileId").asText()).isEqualTo("create-minimal-form");
        assertThat(result.compiledFormPatch().path("catalogReleaseId").asText())
                .isEqualTo("praxis-ui-angular.create-minimal-form.intent-resolution.v0.1.0");
        assertThat(result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("id").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("inputs").path("schemaUrl").asText())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        assertThat(result.compiledFormPatch().path("compatibility").path("aiHttpContract").asText()).isEqualTo("v1.1");
        assertThat(result.compiledFormPatch().path("compatibility").path("requiresV12").asBoolean()).isFalse();
    }

    @Test
    void compileRejectsInvalidMinimalPlanWithoutBuildingPatch() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();
        ((ArrayNode) plan.path("fields")).removeAll();

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(plan, funcionariosIntent()));

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

    @Test
    void compileAddsLocalTransientFieldToExistingDynamicFormForModifyIntent() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();
        ((ObjectNode) plan.path("fields").get(0)).put("name", "observacaoInterna");
        ((ObjectNode) plan.path("fields").get(0)).put("label", "Observacao interna");
        ((ObjectNode) plan.path("fields").get(0)).put("controlType", "textarea");
        ((ObjectNode) plan.path("fields").get(0)).put("required", false);

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(plan, currentFuncionariosPage(), modifyAddFieldIntent()));

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("compiled-as-current-page-modification");
        assertThat(result.compiledFormPatch().path("profileId").asText()).isEqualTo("modify-existing-form");
        ObjectNode page = (ObjectNode) result.compiledFormPatch().path("patch").path("page");
        ObjectNode inputs = (ObjectNode) page.path("widgets").get(0).path("definition").path("inputs");
        assertThat(inputs.path("schemaUrl").asText())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        assertThat(inputs.path("submitUrl").asText()).isEqualTo("/api/human-resources/funcionarios");
        JsonNode fieldMetadata = inputs.path("config").path("fieldMetadata");
        assertThat(fieldMetadata).hasSize(1);
        assertThat(fieldMetadata.get(0).path("name").asText()).isEqualTo("observacaoInterna");
        assertThat(fieldMetadata.get(0).path("source").asText()).isEqualTo("local");
        assertThat(fieldMetadata.get(0).path("transient").asBoolean()).isTrue();
        assertThat(fieldMetadata.get(0).path("submitPolicy").asText()).isEqualTo("omit");
        assertThat(inputs.path("config").path("sections").get(0).path("rows").get(0)
                .path("columns").get(0).path("fields").get(0).asText()).isEqualTo("observacaoInterna");
    }

    @Test
    void compileRelabelsServerBackedFieldWithoutMakingItLocalForModifyIntent() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();
        ((ObjectNode) plan.path("fields").get(0)).put("name", "nome");
        ((ObjectNode) plan.path("fields").get(0)).put("label", "Nome completo do colaborador");
        ((ObjectNode) plan.path("fields").get(0)).put("controlType", "text");
        ((ObjectNode) plan.path("fields").get(0)).put("required", true);

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(plan, currentFuncionariosPageWithLocalNome(), modifyRenameIntent()));

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("compiled-as-current-page-modification");
        ObjectNode inputs = (ObjectNode) result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("inputs");
        JsonNode fieldMetadata = inputs.path("config").path("fieldMetadata");
        assertThat(fieldMetadata).hasSize(1);
        assertThat(fieldMetadata.get(0).path("name").asText()).isEqualTo("nome");
        assertThat(fieldMetadata.get(0).path("label").asText()).isEqualTo("Nome completo do colaborador");
        assertThat(fieldMetadata.get(0).has("source")).isFalse();
        assertThat(fieldMetadata.get(0).has("transient")).isFalse();
        assertThat(fieldMetadata.get(0).has("submitPolicy")).isFalse();
        assertThat(inputs.path("schemaUrl").asText())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
    }

    @Test
    void compileRelabelPreservesUnrelatedLocalTransientFieldsFromExistingDynamicForm() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();
        ((ObjectNode) plan.path("fields").get(0)).put("name", "nome");
        ((ObjectNode) plan.path("fields").get(0)).put("label", "Nome completo do colaborador");
        ArrayNode fields = (ArrayNode) plan.path("fields");
        ObjectNode observacao = fields.addObject();
        observacao.put("name", "observacaoInterna");
        observacao.put("label", "Observacao interna");
        observacao.put("controlType", "textarea");
        observacao.put("required", false);
        observacao.put("schemaPointer", "/properties/observacaoInterna");

        ObjectNode currentPage = currentFuncionariosPageWithLocalObservacao();
        ObjectNode inputs = (ObjectNode) currentPage.path("widgets").get(0).path("definition").path("inputs");
        ObjectNode config = (ObjectNode) inputs.path("config");
        ObjectNode nome = ((ArrayNode) config.path("fieldMetadata")).insertObject(0);
        nome.put("name", "nome");
        nome.put("label", "Nome");
        nome.put("controlType", "text");

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(plan, currentPage, modifyRenameIntent()));

        assertThat(result.valid()).isTrue();
        JsonNode fieldMetadata = result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("inputs").path("config").path("fieldMetadata");
        assertThat(fieldMetadata).hasSize(2);
        assertThat(fieldMetadata.get(0).path("name").asText()).isEqualTo("nome");
        assertThat(fieldMetadata.get(0).path("label").asText()).isEqualTo("Nome completo do colaborador");
        assertThat(fieldMetadata.get(0).has("source")).isFalse();
        assertThat(fieldMetadata.get(1).path("name").asText()).isEqualTo("observacaoInterna");
        assertThat(fieldMetadata.get(1).path("source").asText()).isEqualTo("local");
        assertThat(fieldMetadata.get(1).path("transient").asBoolean()).isTrue();
        assertThat(fieldMetadata.get(1).path("submitPolicy").asText()).isEqualTo("omit");
    }

    @Test
    void compileRemovesLocalTransientFieldFromExistingDynamicFormForRemoveIntent() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();
        ((ObjectNode) plan.path("fields").get(0)).put("name", "observacaoInterna");
        ((ObjectNode) plan.path("fields").get(0)).put("label", "Observacao interna");
        ((ObjectNode) plan.path("fields").get(0)).put("controlType", "textarea");
        ((ObjectNode) plan.path("fields").get(0)).put("required", false);

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(
                        plan,
                        currentFuncionariosPageWithLocalObservacao(),
                        removeFieldIntent()));

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch().path("warnings").toString())
                .contains("local-transient-fields-removed-only");
        ObjectNode inputs = (ObjectNode) result.compiledFormPatch().path("patch").path("page").path("widgets").get(0)
                .path("definition").path("inputs");
        JsonNode fieldMetadata = inputs.path("config").path("fieldMetadata");
        assertThat(fieldMetadata).isEmpty();
        JsonNode sections = inputs.path("config").path("sections");
        assertThat(sections).isEmpty();
        assertThat(inputs.path("schemaUrl").asText())
                .isEqualTo("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
    }

    @Test
    void compileRejectsRemovingServerBackedFieldWithoutLocalTransientSemantics() throws Exception {
        writeCatalog();
        ObjectNode plan = funcionariosPlan();
        ((ObjectNode) plan.path("fields").get(0)).put("name", "nome");
        ((ObjectNode) plan.path("fields").get(0)).put("label", "Nome");
        ((ObjectNode) plan.path("fields").get(0)).put("controlType", "text");

        AgenticAuthoringCompileResult result = service()
                .compile(new AgenticAuthoringCompileRequest(
                        plan,
                        currentFuncionariosPageWithServerBackedNome(),
                        removeFieldIntent()));

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("remove_field requires current local/transient field: nome");
        assertThat(result.compiledFormPatch().isEmpty()).isTrue();
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
        nome.put("schemaPointer", "/properties/nome");
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

    private AgenticAuthoringIntentResolutionResult modifyAddFieldIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "form",
                "add_field",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "api-human-resources-funcionarios-form",
                        "praxis-dynamic-form",
                        "/api/human-resources/funcionarios",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "post"),
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

    private AgenticAuthoringIntentResolutionResult modifyRenameIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "form",
                "rename_or_relabel",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "api-human-resources-funcionarios-form",
                        "praxis-dynamic-form",
                        "/api/human-resources/funcionarios",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "post"),
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

    private AgenticAuthoringIntentResolutionResult removeFieldIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "remove",
                "form",
                "remove_field",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "api-human-resources-funcionarios-form",
                        "praxis-dynamic-form",
                        "/api/human-resources/funcionarios",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "post"),
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

    private ObjectNode currentFuncionariosPage() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode canvas = page.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        canvas.putObject("items").putObject("api-human-resources-funcionarios-form")
                .put("col", 1)
                .put("row", 1)
                .put("colSpan", 12)
                .put("rowSpan", 4);
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "api-human-resources-funcionarios-form");
        ObjectNode inputs = widget.putObject("definition")
                .put("id", "praxis-dynamic-form")
                .putObject("inputs");
        inputs.put("mode", "create");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");
        inputs.put("responseSchemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=response");
        inputs.put("formId", "api-human-resources-funcionarios-minimal");
        inputs.put("componentInstanceId", "api-human-resources-funcionarios-minimal");
        page.putObject("composition").putArray("links");
        return page;
    }

    private ObjectNode currentFuncionariosPageWithLocalNome() {
        ObjectNode page = currentFuncionariosPage();
        ObjectNode inputs = (ObjectNode) page.path("widgets").get(0).path("definition").path("inputs");
        ObjectNode config = inputs.putObject("config");
        ArrayNode fieldMetadata = config.putArray("fieldMetadata");
        ObjectNode nome = fieldMetadata.addObject();
        nome.put("name", "nome");
        nome.put("label", "Nome");
        nome.put("controlType", "text");
        nome.put("source", "local");
        nome.put("transient", true);
        nome.put("submitPolicy", "omit");
        return page;
    }

    private ObjectNode currentFuncionariosPageWithLocalObservacao() {
        ObjectNode page = currentFuncionariosPage();
        ObjectNode inputs = (ObjectNode) page.path("widgets").get(0).path("definition").path("inputs");
        ObjectNode config = inputs.putObject("config");
        ArrayNode fieldMetadata = config.putArray("fieldMetadata");
        ObjectNode field = fieldMetadata.addObject();
        field.put("name", "observacaoInterna");
        field.put("label", "Observacao interna");
        field.put("controlType", "textarea");
        field.put("source", "local");
        field.put("transient", true);
        field.put("submitPolicy", "omit");
        ObjectNode section = config.putArray("sections").addObject();
        section.put("id", "agentic-local-fields");
        section.put("title", "Campos adicionais");
        ObjectNode row = section.putArray("rows").addObject();
        row.put("id", "agentic-local-fields-row");
        ObjectNode column = row.putArray("columns").addObject();
        column.put("id", "agentic-local-fields-column");
        column.put("span", 12);
        column.putArray("fields").add("observacaoInterna");
        return page;
    }

    private ObjectNode currentFuncionariosPageWithServerBackedNome() {
        ObjectNode page = currentFuncionariosPage();
        ObjectNode inputs = (ObjectNode) page.path("widgets").get(0).path("definition").path("inputs");
        ObjectNode config = inputs.putObject("config");
        ArrayNode fieldMetadata = config.putArray("fieldMetadata");
        ObjectNode field = fieldMetadata.addObject();
        field.put("name", "nome");
        field.put("label", "Nome");
        field.put("controlType", "text");
        return page;
    }
}
