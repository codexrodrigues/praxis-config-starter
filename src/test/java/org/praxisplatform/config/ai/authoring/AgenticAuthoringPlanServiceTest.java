package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringPlanServiceTest {

    @TempDir
    private Path tempDir;

    @Mock
    private AiProviderManagementService providerManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateMinimalFormPlanReturnsValidResultForAllowedFields() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest("Crie um formulario", "openai", "gpt-5.4-mini", "test-key"),
                        "tenant",
                        "user",
                        "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.minimalFormPlan()).isSameAs(plan);
        assertThat(configCaptor.getValue().getProvider()).isEqualTo("openai");
        assertThat(configCaptor.getValue().getModel()).isEqualTo("gpt-5.4-mini");
        assertThat(configCaptor.getValue().getApiKey()).isEqualTo("test-key");
    }

    @Test
    void generateMinimalFormPlanFlagsBlockedFields() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ObjectNode blocked = objectMapper.createObjectNode();
        blocked.put("name", "prioridadeId");
        blocked.put("label", "Prioridade");
        blocked.put("controlType", "select");
        blocked.put("required", false);
        ((ArrayNode) plan.path("fields")).add(blocked);
        when(providerManagementService.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any()))
                .thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(new AgenticAuthoringPlanRequest("Crie um formulario", null, null, null), null, null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("field not allowed: prioridadeId", "blocked field present: prioridadeId");
    }

    @Test
    void generateMinimalFormPlanUsesIntentResolutionAsPromptAndValidationContext() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Crie um formulario didatico para cadastrar funcionarios",
                                "openai",
                                "gpt-5.4-mini",
                                "test-key",
                                funcionariosIntent()),
                        "tenant",
                        "user",
                        "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).contains("intent-resolution-applied");
        assertThat(promptCaptor.getValue()).contains("/api/human-resources/funcionarios");
        assertThat(promptCaptor.getValue()).contains("submitActionRef: POST /api/human-resources/funcionarios");
        assertThat(promptCaptor.getValue()).contains("Current page summary:");
        assertThat(promptCaptor.getValue()).contains("\"fieldNames\":[\"observacaoInterna\"]");
        assertThat(promptCaptor.getValue()).contains("\"localFieldNames\":[\"observacaoInterna\"]");
    }

    @Test
    void generateMinimalFormPlanDerivesCurrentPageSummaryWhenIntentIsIncomplete() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan("observacaoInterna", "Observacao interna", "textarea");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                any(),
                any(),
                any())).thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Adicione o campo observacaoInterna",
                                null,
                                null,
                                null,
                                currentPageWithLocalObservacao(),
                                funcionariosIntent("remove", "remove_field", objectMapper.createObjectNode())),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("intent-resolution-applied", "current-page-summary-derived");
        assertThat(promptCaptor.getValue()).contains("\"localFieldNames\":[\"observacaoInterna\"]");
    }

    @Test
    void generateMinimalFormPlanCompletesRemoveFieldFromLocalCurrentPageSummaryWhenProviderReturnsEmptyFields() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan("observacaoInterna", "Observacao interna", "textarea");
        plan.putArray("fields");
        when(providerManagementService.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any()))
                .thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Remova o campo observacaoInterna do formulario",
                                null,
                                null,
                                null,
                                currentPageWithLocalObservacao(),
                                funcionariosIntent("remove", "remove_field", objectMapper.createObjectNode())),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.minimalFormPlan().path("fields")).hasSize(1);
        assertThat(result.minimalFormPlan().path("fields").get(0).path("name").asText())
                .isEqualTo("observacaoInterna");
        assertThat(result.minimalFormPlan().path("fields").get(0).path("controlType").asText())
                .isEqualTo("textarea");
    }

    @Test
    void generateMinimalFormPlanRejectsDuplicateAddFieldFromCurrentPageSummary() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan("observacaoInterna", "Observacao interna", "textarea");
        when(providerManagementService.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any()))
                .thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Adicione o campo observacaoInterna",
                                null,
                                null,
                                null,
                                funcionariosIntent("modify", "add_field")),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("add_field duplicates existing field: observacaoInterna");
    }

    @Test
    void generateMinimalFormPlanRejectsRemoveFieldOutsideLocalFieldNames() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan("nome", "Nome", "text");
        when(providerManagementService.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any()))
                .thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Remova o campo nome",
                                null,
                                null,
                                null,
                                funcionariosIntent("remove", "remove_field")),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("remove_field requires current local/transient field: nome");
    }

    @Test
    void generateMinimalFormPlanRequiresConfiguredContractsDir() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();

        assertThatThrownBy(() -> service(properties)
                .generateMinimalFormPlan(new AgenticAuthoringPlanRequest("Crie um formulario", null, null, null), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contracts-dir");
    }

    private AgenticAuthoringPlanService service(AgenticAuthoringArtifactProperties properties) {
        return new AgenticAuthoringPlanService(providerManagementService, properties);
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
        plan.putArray("sourceRefs").add("proofs/helpdesk-create-ticket-discovery.md");
        return plan;
    }

    private ObjectNode funcionariosPlan() {
        return funcionariosPlan("nome", "Nome", "text");
    }

    private ObjectNode funcionariosPlan(String fieldName, String fieldLabel, String controlType) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", "praxis-ui-angular");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("apiUseCaseResolutionRef", "intent-resolution:/api/human-resources/funcionarios");
        plan.put("fieldSelectionPlanRef", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        plan.put("submitActionRef", "POST /api/human-resources/funcionarios");
        ArrayNode fields = plan.putArray("fields");
        ObjectNode field = fields.addObject();
        field.put("name", fieldName);
        field.put("label", fieldLabel);
        field.put("controlType", controlType);
        field.put("required", true);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        plan.putArray("sourceRefs").add("intent-resolution").add("/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        return plan;
    }

    private AgenticAuthoringIntentResolutionResult funcionariosIntent() {
        return funcionariosIntent("create", "create_minimal_form");
    }

    private AgenticAuthoringIntentResolutionResult funcionariosIntent(String operationKind, String changeKind) {
        return funcionariosIntent(operationKind, changeKind, currentPageSummary());
    }

    private AgenticAuthoringIntentResolutionResult funcionariosIntent(
            String operationKind,
            String changeKind,
            ObjectNode currentPageSummary) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                operationKind,
                "form",
                changeKind,
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
                currentPageSummary);
    }

    private ObjectNode currentPageSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        var formWidgets = summary.putArray("formWidgets");
        ObjectNode formWidget = formWidgets.addObject();
        formWidget.put("widgetKey", "funcionarios-form");
        formWidget.putArray("fieldNames").add("observacaoInterna");
        formWidget.putArray("localFieldNames").add("observacaoInterna");
        formWidget.putArray("serverBackedOverrideNames");
        return summary;
    }

    private ObjectNode currentPageWithLocalObservacao() {
        ObjectNode page = objectMapper.createObjectNode();
        var widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
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
        return page;
    }
}
