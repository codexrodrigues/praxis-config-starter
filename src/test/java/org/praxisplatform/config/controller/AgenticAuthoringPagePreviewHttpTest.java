package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCandidate;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCompileResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceCandidatesResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceDiscoveryService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReferenceUiCompositionPlanProvider;
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
    void componentCapabilitiesReturnsExecutableCatalogs() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                mock(AgenticAuthoringPlanService.class),
                mock(AgenticAuthoringPatchCompilerService.class),
                mock(AgenticAuthoringPreviewService.class),
                mock(AgenticAuthoringApplyService.class),
                new AgenticAuthoringComponentCapabilitiesService(),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        String response = mockMvc.perform(get("/api/praxis/config/ai/authoring/component-capabilities"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("version").asText()).isEqualTo("0.1.0");
        assertThat(body.path("catalogs")).extracting(node -> node.path("componentId").asText())
                .containsExactly("praxis-dynamic-form", "praxis-table", "praxis-chart");
        JsonNode formCatalog = body.path("catalogs").get(0);
        assertThat(formCatalog.path("capabilities")).extracting(node -> node.path("changeKind").asText())
                .containsExactly("add_field", "rename_or_relabel", "remove_field");
    }

    @Test
    void resourceCandidatesEndpointReturnsToolPayload() throws Exception {
        AgenticAuthoringResourceDiscoveryService resourceDiscoveryService =
                mock(AgenticAuthoringResourceDiscoveryService.class);
        when(resourceDiscoveryService.search(any()))
                .thenReturn(new AgenticAuthoringResourceCandidatesResult(
                        true,
                        "searchApiResources",
                        "Quais APIs analiticas podem alimentar graficos de folha?",
                        "dashboard",
                        List.of(new AgenticAuthoringCandidate(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "get",
                                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/group-by&operation=post&schemaType=response",
                                "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                                "post",
                                0.9d,
                                "api_metadata broad artifact discovery",
                                List.of("api-metadata"))),
                        List.of()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                mock(AgenticAuthoringPlanService.class),
                mock(AgenticAuthoringPatchCompilerService.class),
                mock(AgenticAuthoringPreviewService.class),
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                resourceDiscoveryService)).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("retrievalQuery", "Quais APIs analiticas podem alimentar graficos de folha?");
        request.put("artifactKind", "dashboard");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/resource-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("tool").asText()).isEqualTo("searchApiResources");
        assertThat(body.path("artifactKind").asText()).isEqualTo("dashboard");
        assertThat(body.path("candidates")).hasSize(1);
        assertThat(body.path("candidates").get(0).path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

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
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

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

    @Test
    void pagePreviewCanReturnChartDrillDownUiCompositionPlan() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "Use um chart para criar drill down da folha por departamento");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("uiCompositionPlan").path("layoutPreset").asText())
                .isEqualTo("chart-drilldown-dashboard");
        assertThat(body.path("uiCompositionPlan").path("widgets")).hasSize(3);
        assertThat(body.path("uiCompositionPlan").path("widgets").get(0).path("componentId").asText())
                .isEqualTo("praxis-chart");
        assertThat(body.path("uiCompositionPlan").path("bindings")).hasSize(3);
        assertThat(body.path("warnings")).extracting(JsonNode::asText)
                .contains("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
    }

    @Test
    void intentResolutionUsesPendingClarificationContextOverHttp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                new AgenticAuthoringIntentResolverService(objectMapper),
                mock(AgenticAuthoringPlanService.class),
                mock(AgenticAuthoringPatchCompilerService.class),
                mock(AgenticAuthoringPreviewService.class),
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "folha de pagamento");
        request.put("sessionId", "session-1");
        request.put("clientTurnId", "turn-2");
        request.putArray("conversationMessages")
                .addObject()
                .put("id", "message-1")
                .put("role", "user")
                .put("text", "Crie um dashboard")
                .put("createdAt", "2026-04-14T20:00:00Z");
        ObjectNode pendingClarification = request.putObject("pendingClarification");
        pendingClarification.put("sourcePrompt", "Crie um dashboard");
        pendingClarification.putArray("questions")
                .add("Qual recurso de negocio deve alimentar esta tela?");
        pendingClarification.put("assistantMessage", "Qual recurso de negocio deve alimentar esta tela?");
        pendingClarification.put("clientTurnId", "turn-1");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/intent-resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isFalse();
        assertThat(body.path("operationKind").asText()).isEqualTo("create");
        assertThat(body.path("artifactKind").asText()).isEqualTo("dashboard");
        assertThat(body.path("selectedCandidate").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(body.path("failureCodes")).extracting(JsonNode::asText)
                .contains("analytics-breakdown-required");
        assertThat(body.path("pendingClarification").path("sourcePrompt").asText())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(body.path("pendingClarification").path("questions")).extracting(JsonNode::asText)
                .containsExactly("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
        assertThat(body.path("pendingClarification").path("clientTurnId").asText()).isEqualTo("turn-2");
    }

    @Test
    void intentResolutionCarriesAttachmentSummariesInPendingClarificationDiagnosticsOverHttp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                new AgenticAuthoringIntentResolverService(objectMapper),
                mock(AgenticAuthoringPlanService.class),
                mock(AgenticAuthoringPatchCompilerService.class),
                mock(AgenticAuthoringPreviewService.class),
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "Crie um dashboard usando a imagem anexada");
        request.put("sessionId", "session-1");
        request.put("clientTurnId", "turn-1");
        ObjectNode attachment = request.putArray("attachmentSummaries").addObject();
        attachment.put("id", "attachment-1");
        attachment.put("name", "referencia.png");
        attachment.put("kind", "image");
        attachment.put("mimeType", "image/png");
        attachment.put("sizeBytes", 12345L);
        attachment.put("source", "paste");
        attachment.put("hasPreview", true);

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/intent-resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        JsonNode diagnostics = body.path("pendingClarification").path("diagnostics");
        assertThat(body.path("valid").asBoolean()).isFalse();
        assertThat(body.path("failureCodes")).extracting(JsonNode::asText)
                .contains("resource-candidate-required");
        assertThat(diagnostics.path("attachmentSummaries")).hasSize(1);
        assertThat(diagnostics.path("attachmentSummaries").get(0).path("name").asText())
                .isEqualTo("referencia.png");
        assertThat(diagnostics.path("attachmentSummaries").get(0).path("mimeType").asText())
                .isEqualTo("image/png");
        assertThat(diagnostics.toString()).doesNotContain("blob:");
    }

    @Test
    void intentResolutionUsesConversationHistoryWhenPendingClarificationIsMissingOverHttp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                new AgenticAuthoringIntentResolverService(objectMapper),
                mock(AgenticAuthoringPlanService.class),
                mock(AgenticAuthoringPatchCompilerService.class),
                mock(AgenticAuthoringPreviewService.class),
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "por departamento");
        request.put("sessionId", "session-1");
        request.put("clientTurnId", "turn-3");
        ArrayNode messages = request.putArray("conversationMessages");
        messages.addObject()
                .put("id", "message-1")
                .put("role", "user")
                .put("text", "Crie um dashboard")
                .put("createdAt", "2026-04-14T20:00:00Z");
        messages.addObject()
                .put("id", "message-2")
                .put("role", "assistant")
                .put("text", "Qual recurso de negocio deve alimentar esta tela?")
                .put("createdAt", "2026-04-14T20:00:01Z");
        messages.addObject()
                .put("id", "message-3")
                .put("role", "user")
                .put("text", "folha de pagamento")
                .put("createdAt", "2026-04-14T20:01:00Z");
        messages.addObject()
                .put("id", "message-4")
                .put("role", "assistant")
                .put("text", "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?")
                .put("createdAt", "2026-04-14T20:01:01Z");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/intent-resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("effectivePrompt").asText())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: por departamento");
        assertThat(body.path("selectedCandidate").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
    }

    @Test
    void intentResolutionReturnsConsultativeCatalogSuggestionsForLongPromptOverHttp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                new AgenticAuthoringIntentResolverService(objectMapper),
                mock(AgenticAuthoringPlanService.class),
                mock(AgenticAuthoringPatchCompilerService.class),
                mock(AgenticAuthoringPreviewService.class),
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put(
                "userPrompt",
                "Estou montando uma pagina executiva para RH e ainda nao sei se devo usar chart, tabela, cards ou resumo. Com base no catalogo de componentes, quais opcoes voce recomenda para analisar folha de pagamento por departamento, descontos e salario liquido antes de criar qualquer coisa?");
        request.put("sessionId", "session-consultative-catalog");
        request.put("clientTurnId", "turn-1");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/intent-resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isFalse();
        assertThat(body.path("operationKind").asText()).isEqualTo("explore");
        assertThat(body.path("artifactKind").asText()).isEqualTo("dashboard");
        assertThat(body.path("changeKind").asText()).isEqualTo("recommend_dashboard_visualization");
        assertThat(body.path("selectedCandidate").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(body.path("assistantMessage").asText()).contains("melhores opcoes");
        assertThat(body.path("quickReplies")).extracting(reply -> reply.path("id").asText())
                .containsExactly(
                        "payroll-executive-dashboard",
                        "payroll-department-drilldown",
                        "payroll-detail-table");
        assertThat(body.path("pendingClarification").path("questions")).extracting(JsonNode::asText)
                .containsExactly("Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?");
    }

    @Test
    void pagePreviewUsesAccumulatedPendingClarificationContextOverHttp() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "por departamento");
        request.put("sessionId", "session-1");
        request.put("clientTurnId", "turn-3");
        request.putArray("conversationMessages")
                .addObject()
                .put("id", "message-2")
                .put("role", "user")
                .put("text", "folha de pagamento")
                .put("createdAt", "2026-04-14T20:01:00Z");
        ObjectNode pendingClarification = request.putObject("pendingClarification");
        pendingClarification.put("sourcePrompt", "Crie um dashboard\n\nConfirmed: folha de pagamento");
        pendingClarification.putArray("questions")
                .add("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
        pendingClarification.put("assistantMessage",
                "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
        pendingClarification.put("clientTurnId", "turn-2");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("uiCompositionPlan").path("layoutPreset").asText())
                .isEqualTo("chart-drilldown-dashboard");
        assertThat(body.path("uiCompositionPlan").path("widgets")).hasSize(3);
        assertThat(body.path("warnings")).extracting(JsonNode::asText)
                .contains("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
    }

    @Test
    void pagePreviewPreservesAttachmentSummariesWhenConversationContextIsEnrichedOverHttp() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        ArgumentCaptor<AgenticAuthoringPlanRequest> requestCaptor =
                ArgumentCaptor.forClass(AgenticAuthoringPlanRequest.class);
        when(planService.generateMinimalFormPlan(requestCaptor.capture(), any(), any(), any()))
                .thenReturn(new AgenticAuthoringPlanResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode()));
        when(compilerService.compile(any()))
                .thenReturn(new AgenticAuthoringCompileResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode()));
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "folha de pagamento");
        request.put("sessionId", "session-1");
        request.put("clientTurnId", "turn-2");
        ObjectNode pendingClarification = request.putObject("pendingClarification");
        pendingClarification.put("sourcePrompt", "Crie um dashboard");
        pendingClarification.putArray("questions")
                .add("Qual recurso de negocio deve alimentar esta tela?");
        pendingClarification.put("assistantMessage", "Qual recurso de negocio deve alimentar esta tela?");
        pendingClarification.put("clientTurnId", "turn-1");
        ObjectNode attachment = request.putArray("attachmentSummaries").addObject();
        attachment.put("id", "attachment-1");
        attachment.put("name", "referencia.png");
        attachment.put("kind", "image");
        attachment.put("mimeType", "image/png");
        attachment.put("sizeBytes", 12345L);
        attachment.put("source", "paste");
        attachment.put("hasPreview", true);

        mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        AgenticAuthoringPlanRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.userPrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(capturedRequest.attachmentSummaries()).hasSize(1);
        assertThat(capturedRequest.attachmentSummaries().get(0).name()).isEqualTo("referencia.png");
        assertThat(capturedRequest.attachmentSummaries().get(0).mimeType()).isEqualTo("image/png");
    }

    @Test
    void pagePreviewRestoresAttachmentSummariesFromPendingClarificationDiagnosticsOverHttp() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        ArgumentCaptor<AgenticAuthoringPlanRequest> requestCaptor =
                ArgumentCaptor.forClass(AgenticAuthoringPlanRequest.class);
        when(planService.generateMinimalFormPlan(requestCaptor.capture(), any(), any(), any()))
                .thenReturn(new AgenticAuthoringPlanResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode()));
        when(compilerService.compile(any()))
                .thenReturn(new AgenticAuthoringCompileResult(
                        true,
                        List.of(),
                        List.of(),
                        objectMapper.createObjectNode()));
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "por departamento");
        ObjectNode pendingClarification = request.putObject("pendingClarification");
        pendingClarification.put("sourcePrompt", "Crie um dashboard usando a imagem anexada");
        pendingClarification.putArray("questions").add("Qual recorte?");
        pendingClarification.put("assistantMessage", "Qual recorte?");
        ObjectNode diagnostics = pendingClarification.putObject("diagnostics");
        ObjectNode attachment = diagnostics.putArray("attachmentSummaries").addObject();
        attachment.put("id", "attachment-1");
        attachment.put("name", "referencia.png");
        attachment.put("kind", "image");
        attachment.put("mimeType", "image/png");
        attachment.put("sizeBytes", 12345L);
        attachment.put("source", "paste");
        attachment.put("hasPreview", true);

        mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        AgenticAuthoringPlanRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.userPrompt())
                .isEqualTo("Crie um dashboard usando a imagem anexada\n\nConfirmed: por departamento");
        assertThat(capturedRequest.attachmentSummaries()).hasSize(1);
        assertThat(capturedRequest.attachmentSummaries().get(0).name()).isEqualTo("referencia.png");
    }

    @Test
    void pagePreviewUsesConversationHistoryWhenPendingClarificationIsMissingOverHttp() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "por departamento");
        request.put("sessionId", "session-1");
        request.put("clientTurnId", "turn-3");
        ArrayNode messages = request.putArray("conversationMessages");
        messages.addObject()
                .put("id", "message-1")
                .put("role", "user")
                .put("text", "Crie um dashboard")
                .put("createdAt", "2026-04-14T20:00:00Z");
        messages.addObject()
                .put("id", "message-2")
                .put("role", "assistant")
                .put("text", "Qual recurso de negocio deve alimentar esta tela?")
                .put("createdAt", "2026-04-14T20:00:01Z");
        messages.addObject()
                .put("id", "message-3")
                .put("role", "user")
                .put("text", "folha de pagamento")
                .put("createdAt", "2026-04-14T20:01:00Z");
        messages.addObject()
                .put("id", "message-4")
                .put("role", "assistant")
                .put("text", "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?")
                .put("createdAt", "2026-04-14T20:01:01Z");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("uiCompositionPlan").path("layoutPreset").asText())
                .isEqualTo("chart-drilldown-dashboard");
        assertThat(body.path("uiCompositionPlan").path("widgets")).hasSize(3);
        assertThat(body.path("warnings")).extracting(JsonNode::asText)
                .contains("ui-composition-plan-provider:quickstart-payroll-chart-drilldown");
    }

    @Test
    void pagePreviewCanReturnPayrollTableUiCompositionPlan() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "Sim, crie uma tabela operacional de folhas de pagamento");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("uiCompositionPlan").path("layoutPreset").asText())
                .isEqualTo("single-table-page");
        assertThat(body.path("uiCompositionPlan").path("widgets")).hasSize(1);
        JsonNode table = body.path("uiCompositionPlan").path("widgets").get(0);
        assertThat(table.path("componentId").asText()).isEqualTo("praxis-table");
        assertThat(table.path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/folhas-pagamento");
        assertThat(body.path("warnings")).extracting(JsonNode::asText)
                .contains("ui-composition-plan-provider:quickstart-payroll-table");
    }

    @Test
    void pagePreviewCanReturnUiCompositionPlanWithoutMinimalFormPipeline() throws Exception {
        AgenticAuthoringPlanService planService = mock(AgenticAuthoringPlanService.class);
        AgenticAuthoringPatchCompilerService compilerService = mock(AgenticAuthoringPatchCompilerService.class);
        AgenticAuthoringPreviewService previewService = new AgenticAuthoringPreviewService(
                planService,
                compilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringController(
                mock(AgenticAuthoringDryRunService.class),
                mock(AgenticAuthoringArtifactSource.class),
                mock(AgenticAuthoringIntentResolverService.class),
                planService,
                compilerService,
                previewService,
                mock(AgenticAuthoringApplyService.class),
                mock(AgenticAuthoringComponentCapabilitiesService.class),
                mock(AgenticAuthoringResourceDiscoveryService.class))).build();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("userPrompt", "Crie uma tela master detail de departamentos com funcionarios e folha");

        String response = mockMvc.perform(post("/api/praxis/config/ai/authoring/page-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("valid").asBoolean()).isTrue();
        assertThat(body.path("minimalFormPlan").isMissingNode() || body.path("minimalFormPlan").isNull()).isTrue();
        assertThat(body.path("uiCompositionPlan").path("kind").asText()).isEqualTo("praxis.ui-composition-plan");
        assertThat(body.path("uiCompositionPlan").path("widgets")).hasSize(4);
        assertThat(body.path("uiCompositionPlan").path("bindings")).hasSize(5);
        assertThat(body.path("compiledFormPatch").path("patch").isObject()).isTrue();
        assertThat(body.path("warnings")).extracting(JsonNode::asText)
                .contains("compiled-form-patch-materialized-by-page-builder");
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
