package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class AgenticAuthoringPagePreviewHttpTest {

    @TempDir
    private Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pagePreviewDerivesCurrentPageSummaryForIncompleteIntent() throws Exception {
        writeArtifacts();
        AiProviderManagementService providerManagementService = mock(AiProviderManagementService.class);
        when(providerManagementService.generateJson(
                any(String.class),
                any(AiJsonSchema.class),
                any(),
                isNull(),
                isNull(),
                isNull()))
                .thenReturn(removeObservacaoInternaPlan());

        AgenticAuthoringArtifactProperties properties = properties();
        AgenticAuthoringPlanService planService =
                new AgenticAuthoringPlanService(providerManagementService, properties, objectMapper);
        AgenticAuthoringPatchCompilerService compilerService =
                new AgenticAuthoringPatchCompilerService(properties, objectMapper);
        AgenticAuthoringPreviewService previewService =
                new AgenticAuthoringPreviewService(planService, compilerService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class))).build();

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(previewRequest())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("warnings")).extracting(JsonNode::asText)
                .contains("current-page-summary-derived");
        assertThat(body.path("diagnostics").path("derivedCurrentPageSummary").asBoolean()).isTrue();
        assertThat(body.path("diagnostics").path("targetWidgetKey").asText())
                .isEqualTo("api-human-resources-funcionarios-form");
        assertThat(body.path("diagnostics").path("operationKind").asText()).isEqualTo("remove");
        assertThat(body.path("diagnostics").path("changeKind").asText()).isEqualTo("remove_field");
        assertThat(body.path("diagnostics").path("fieldScopeDecision").asText())
                .isEqualTo("accepted-remove-local-field");
        assertThat(body.path("compiledFormPatch").path("warnings")).extracting(JsonNode::asText)
                .contains("local-transient-fields-removed-only");
        JsonNode inputs = body.path("compiledFormPatch").path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs");
        assertThat(inputs.path("config").path("fieldMetadata")).isEmpty();
        assertThat(inputs.path("config").path("sections").toString()).doesNotContain("observacaoInterna");
        assertThat(inputs.path("submitUrl").asText()).isEqualTo("/api/human-resources/funcionarios");
    }

    private AgenticAuthoringArtifactProperties properties() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(tempDir);
        properties.setContractsDir(tempDir);
        return properties;
    }

    private void writeArtifacts() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        ObjectNode catalog = objectMapper.createObjectNode();
        catalog.put("profileId", "create-minimal-form");
        catalog.put("targetComponent", "praxis-dynamic-page-builder");
        catalog.put("catalogReleaseId", "catalog-release-test");
        ObjectNode form = catalog.putArray("allowedWidgets").addObject();
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

    private ObjectNode previewRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "Remova o campo observacaoInterna");
        request.put("provider", "mock");
        request.put("model", "mock-model");
        request.put("apiKey", "test-key");
        request.set("currentPage", currentPageWithLocalObservacao());
        request.set("intentResolution", incompleteRemoveIntent());
        return request;
    }

    private ObjectNode removeObservacaoInternaPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", "praxis-ui-angular");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("apiUseCaseResolutionRef", "intent-resolution:/api/human-resources/funcionarios");
        plan.put("fieldSelectionPlanRef", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        plan.put("submitActionRef", "POST /api/human-resources/funcionarios");
        ObjectNode field = plan.putArray("fields").addObject();
        field.put("name", "observacaoInterna");
        field.put("label", "Observacao interna");
        field.put("controlType", "textarea");
        field.put("required", false);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        plan.putArray("sourceRefs").add("intent-resolution");
        return plan;
    }

    private ObjectNode currentPageWithLocalObservacao() {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode canvas = page.putObject("canvas");
        canvas.put("mode", "grid");
        canvas.put("columns", 12);
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "api-human-resources-funcionarios-form");
        ObjectNode inputs = widget.putObject("definition")
                .put("id", "praxis-dynamic-form")
                .putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");
        ObjectNode config = inputs.putObject("config");
        ObjectNode field = config.putArray("fieldMetadata").addObject();
        field.put("name", "observacaoInterna");
        field.put("label", "Observacao interna");
        field.put("controlType", "textarea");
        field.put("source", "local");
        field.put("transient", true);
        field.put("submitPolicy", "omit");
        ObjectNode section = config.putArray("sections").addObject();
        ObjectNode row = section.putArray("rows").addObject();
        ObjectNode column = row.putArray("columns").addObject();
        column.putArray("fields").add("observacaoInterna");
        return page;
    }

    private ObjectNode incompleteRemoveIntent() {
        ObjectNode intent = objectMapper.createObjectNode();
        intent.put("valid", true);
        intent.put("operationKind", "remove");
        intent.put("artifactKind", "form");
        intent.put("changeKind", "remove_field");
        intent.put("authoringProfile", "create-minimal-form");
        intent.put("targetApp", "praxis-ui-angular");
        intent.put("targetComponentId", "praxis-dynamic-page-builder");
        ObjectNode target = intent.putObject("target");
        target.put("widgetKey", "api-human-resources-funcionarios-form");
        target.put("componentId", "praxis-dynamic-form");
        target.put("resourcePath", "/api/human-resources/funcionarios");
        target.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        target.put("submitUrl", "/api/human-resources/funcionarios");
        target.put("submitMethod", "post");
        ObjectNode candidate = intent.putObject("selectedCandidate");
        candidate.put("resourcePath", "/api/human-resources/funcionarios");
        candidate.put("operation", "post");
        candidate.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        candidate.put("submitUrl", "/api/human-resources/funcionarios");
        candidate.put("submitMethod", "POST");
        candidate.put("score", 0.95d);
        candidate.put("reason", "current target");
        candidate.putArray("evidence").add("current-page");
        intent.putArray("candidates").add(candidate.deepCopy());
        ObjectNode gate = intent.putObject("gate");
        gate.put("version", "candidate-eligibility@0.1.0");
        gate.put("status", "eligible");
        gate.putArray("messages");
        intent.putArray("clarificationQuestions");
        intent.putArray("warnings");
        intent.putArray("failureCodes");
        intent.putObject("currentPageSummary");
        return intent;
    }
}
