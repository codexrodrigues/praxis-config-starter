package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void generateMinimalFormPlanUsesReferencePlanForResolvedQuickstartFuncionariosCreate() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);

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
        assertThat(result.minimalFormPlan().path("targetApp").asText()).isEqualTo("praxis-ui-angular");
        assertThat(result.minimalFormPlan().path("submitActionRef").asText())
                .isEqualTo("POST /api/human-resources/funcionarios");
        assertThat(result.minimalFormPlan().path("fields"))
                .extracting(field -> field.path("name").asText())
                .contains("nomeCompleto", "cpf", "email", "telefone", "salario", "cargoId", "departamentoId");
        verifyNoInteractions(providerManagementService);
    }

    @Test
    void generateMinimalFormPlanIncludesPendingClarificationContextInPrompt() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                any(),
                any(),
                any())).thenReturn(plan);

        service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "folha de pagamento",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "session-1",
                                "turn-2",
                                java.util.List.of(new AgenticAuthoringConversationMessage(
                                        "m1",
                                        "user",
                                        "Crie um dashboard",
                                        null)),
                                new AgenticAuthoringPendingClarification(
                                        "Crie um dashboard",
                                        java.util.List.of("Qual tema do dashboard?"),
                                        "Qual tema do dashboard?",
                                        "turn-1",
                                        objectMapper.createObjectNode())),
                        null,
                        null,
                        null);

        assertThat(promptCaptor.getValue()).contains("User request:");
        assertThat(promptCaptor.getValue()).contains("Crie um dashboard");
        assertThat(promptCaptor.getValue()).contains("Confirmed: folha de pagamento");
    }

    @Test
    void generateMinimalFormPlanPrefersIntentEffectivePromptOverPendingClarificationRecomposition() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                any(),
                any(),
                any())).thenReturn(plan);
        String rawPrompt = "Com base nisso, agora crie uma tabela operacional de folhas de pagamento";

        service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                rawPrompt,
                                null,
                                null,
                                null,
                                null,
                                new AgenticAuthoringIntentResolutionResult(
                                        true,
                                        "create",
                                        "table",
                                        "create_artifact",
                                        "generic-page-change",
                                        "praxis-ui-angular",
                                        "praxis-dynamic-page-builder",
                                        null,
                                        new AgenticAuthoringCandidate(
                                                "/api/human-resources/folhas-pagamento",
                                                "get",
                                                "/schemas/filtered?path=/api/human-resources/folhas-pagamento/all&operation=get&schemaType=response",
                                                "/api/human-resources/folhas-pagamento/all",
                                                "GET",
                                                0.94,
                                                "matched payroll table",
                                                java.util.List.of("payroll")),
                                        java.util.List.of(),
                                        new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", java.util.List.of()),
                                        rawPrompt,
                                        "Vou criar uma tabela operacional.",
                                        java.util.List.of(),
                                        java.util.List.of(),
                                        java.util.List.of("llm-intent-resolution-used"),
                                        java.util.List.of(),
                                        objectMapper.createObjectNode()),
                                "session-1",
                                "turn-2",
                                java.util.List.of(new AgenticAuthoringConversationMessage(
                                        "m1",
                                        "user",
                                        "Crie um dashboard de folha de pagamento",
                                        null)),
                                new AgenticAuthoringPendingClarification(
                                        "Crie um dashboard de folha de pagamento",
                                        java.util.List.of("Qual recorte do dashboard de folha de pagamento voce quer usar?"),
                                        "Qual recorte do dashboard de folha de pagamento voce quer usar?",
                                        "turn-1",
                                        objectMapper.createObjectNode())),
                        null,
                        null,
                        null);

        assertThat(promptCaptor.getValue()).contains("User request:");
        assertThat(promptCaptor.getValue()).contains(rawPrompt);
        assertThat(promptCaptor.getValue()).doesNotContain("Confirmed:");
    }

    @Test
    void generateMinimalFormPlanIncludesOnlyAttachmentSummariesInPrompt() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                any(),
                any(),
                any())).thenReturn(plan);

        service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Use a imagem anexada como referencia",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "session-1",
                                "turn-1",
                                java.util.List.of(),
                                null,
                                java.util.List.of(new AgenticAuthoringAttachmentSummary(
                                        "attachment-1",
                                        "referencia.png",
                                        "image",
                                        "image/png",
                                        12345L,
                                        "paste",
                                        true))),
                        null,
                        null,
                        null);

        assertThat(promptCaptor.getValue()).contains("Attachment summaries:");
        assertThat(promptCaptor.getValue()).contains("\"name\":\"referencia.png\"");
        assertThat(promptCaptor.getValue()).contains("\"mimeType\":\"image/png\"");
        assertThat(promptCaptor.getValue()).contains("Do not assume access to file bytes, base64 content, blob URLs, or image pixels");
        assertThat(promptCaptor.getValue()).doesNotContain("blob:");
    }

    @Test
    void generateMinimalFormPlanIncludesGovernedProjectKnowledgeProjectionInPrompt() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan("apelido", "Apelido", "text");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                any(),
                any(),
                any())).thenReturn(plan);
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode projectKnowledge = contextHints.putObject("projectKnowledge");
        projectKnowledge.put("schemaVersion", "praxis-agentic-authoring-project-knowledge.v1");
        projectKnowledge.put("source", "domain_knowledge_concept");
        projectKnowledge.put("influenceCount", 1);
        projectKnowledge.put("rawPayload", "MUST_NOT_LEAK");
        ObjectNode entry = projectKnowledge.putArray("entries").addObject();
        entry.put("knowledgeId", "knowledge-1");
        entry.put("conceptKey", "human-resources.funcionarios.preference.identity-card");
        entry.put("kind", "project_preference");
        entry.put("visibility", "allow");
        entry.put("sourceSummary", "accepted authoring turn");
        entry.put("influence", "layout_preference");
        entry.put("summary", "Prefer compact identity cards.");
        entry.put("rawSourceData", "SECRET_SOURCE_DATA");
        entry.putObject("scope")
                .put("tenantId", "tenant")
                .put("environment", "local")
                .put("contextKey", "human-resources")
                .put("resourceKey", "human-resources.funcionarios");
        entry.putObject("status")
                .put("lifecycle", "active")
                .put("curationStatus", "approved");
        entry.putArray("evidence")
                .add("domain-knowledge:concept:human-resources.funcionarios.preference.identity-card");

        service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Adicione apelido ao formulario",
                                null,
                                null,
                                null,
                                null,
                                funcionariosIntent("modify", "add_field"),
                                "session-1",
                                "turn-1",
                                java.util.List.of(),
                                null,
                                java.util.List.of(),
                                contextHints),
                        "tenant",
                        "user",
                        "local");

        assertThat(promptCaptor.getValue()).contains("Governed project knowledge:");
        assertThat(promptCaptor.getValue()).contains("\"kind\":\"project_preference\"");
        assertThat(promptCaptor.getValue()).contains("\"summary\":\"Prefer compact identity cards.\"");
        assertThat(promptCaptor.getValue()).contains("sourceRefs must cite intent-resolution, the resolved schema URL, and projectKnowledge entries");
        assertThat(promptCaptor.getValue()).doesNotContain("MUST_NOT_LEAK");
        assertThat(promptCaptor.getValue()).doesNotContain("SECRET_SOURCE_DATA");
    }

    @Test
    void generateMinimalFormPlanRestoresAttachmentSummariesFromPendingClarificationDiagnostics() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.set("attachmentSummaries", objectMapper.valueToTree(java.util.List.of(
                new AgenticAuthoringAttachmentSummary(
                        "attachment-1",
                        "referencia.png",
                        "image",
                        "image/png",
                        12345L,
                        "paste",
                        true))));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(providerManagementService.generateJson(
                promptCaptor.capture(),
                any(AiJsonSchema.class),
                any(),
                any(),
                any(),
                any())).thenReturn(plan);

        service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "por departamento",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "session-1",
                                "turn-2",
                                java.util.List.of(),
                                new AgenticAuthoringPendingClarification(
                                        "Crie um dashboard usando a imagem anexada",
                                        java.util.List.of("Qual recorte?"),
                                        "Qual recorte?",
                                        "turn-1",
                                        diagnostics)),
                        null,
                        null,
                        null);

        assertThat(promptCaptor.getValue()).contains("\"name\":\"referencia.png\"");
        assertThat(promptCaptor.getValue()).contains("\"source\":\"paste\"");
        assertThat(promptCaptor.getValue()).doesNotContain("blob:");
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
    void generateMinimalFormPlanDoesNotDeriveLegacySummaryWhenStructuralInspectionIsPresent() throws Exception {
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
                                "Remova o campo observacaoInterna",
                                null,
                                null,
                                null,
                                currentPageWithLocalObservacao(),
                                funcionariosIntent("remove", "remove_field", currentPageStructuralSummary())),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).doesNotContain("current-page-summary-derived");
        assertThat(promptCaptor.getValue()).contains("\"structuralInspection\"");
        assertThat(promptCaptor.getValue()).doesNotContain("\"formWidgets\"");
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
    void generateMinimalFormPlanCompletesRemoveFieldFromStructuralInspectionWhenLegacySummaryIsAbsent() throws Exception {
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
                                funcionariosIntent("remove", "remove_field", currentPageStructuralSummary())),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.minimalFormPlan().path("fields")).hasSize(1);
        assertThat(result.minimalFormPlan().path("fields").get(0).path("name").asText())
                .isEqualTo("observacaoInterna");
    }

    @Test
    void generateMinimalFormPlanCompletesRelabelFieldFromPromptWhenProviderMissesServerBackedField() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = funcionariosPlan("observacaoInterna", "Observacao interna", "textarea");
        when(providerManagementService.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any()))
                .thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest(
                                "Renomeie o campo nome para Nome completo do colaborador",
                                null,
                                null,
                                null,
                                currentPageWithLocalObservacao(),
                                funcionariosIntent("modify", "rename_or_relabel", objectMapper.createObjectNode())),
                        null,
                        null,
                        null);

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.minimalFormPlan().path("fields"))
                .anySatisfy(field -> {
                    assertThat(field.path("name").asText()).isEqualTo("nome");
                    assertThat(field.path("label").asText()).isEqualTo("Nome completo do colaborador");
                });
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

    private ObjectNode currentPageStructuralSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        ObjectNode inspection = summary.putObject("structuralInspection");
        var widgets = inspection.putArray("widgets");
        widgets.addObject()
                .put("widgetKey", "funcionarios-form")
                .put("componentType", "praxis-dynamic-form")
                .put("artifactKind", "form")
                .put("boundResource", "/api/human-resources/funcionarios");
        var fields = inspection.putArray("fields");
        fields.addObject()
                .put("widgetKey", "funcionarios-form")
                .put("name", "observacaoInterna")
                .put("label", "Observacao interna")
                .put("controlType", "textarea")
                .put("binding", "transient")
                .put("source", "local");
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
