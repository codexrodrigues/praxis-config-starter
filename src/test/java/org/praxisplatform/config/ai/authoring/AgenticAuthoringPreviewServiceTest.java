package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.ResourceSurfaceCatalogFetchResult;
import org.praxisplatform.config.service.ResourceSurfaceCatalogRetrievalService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringPreviewServiceTest {

    @Mock
    private AgenticAuthoringPlanService planService;

    @Mock
    private AgenticAuthoringPatchCompilerService patchCompilerService;

    @Mock
    private AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer;

    @Mock
    private SchemaRetrievalService schemaRetrievalService;

    @Mock
    private ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService;

    @Mock
    private ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void previewReturnsConsultativeAnswerWithoutMaterializingPlan() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "explain",
                "component",
                "answer_component_capability_question",
                "consultative",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("consultative", "eligible", List.of()),
                "Como habilito exportacao de selecionados?",
                "Use a capacidade governada da tabela para exportar apenas as linhas selecionadas.",
                objectMapper.createObjectNode(),
                List.of(),
                null,
                List.of(),
                List.of("llm-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Como habilito exportacao de selecionados?",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).contains(
                "llm-intent-resolution-used",
                "preview-materialization-skipped-consultative-answer");
        assertThat(result.assistantMessage())
                .isEqualTo("Use a capacidade governada da tabela para exportar apenas as linhas selecionadas.");
        assertThat(result.minimalFormPlan().isMissingNode()).isTrue();
        assertThat(result.compiledFormPatch().isMissingNode()).isTrue();
        verifyNoInteractions(planService, patchCompilerService);
    }

    @Test
    void previewReturnsApiCatalogConsultativeAnswerWithoutMaterializingPlan() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "explore",
                "api_catalog",
                "answer_api_catalog_question",
                "consultative",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                null,
                List.of(),
                new AgenticAuthoringGateResult("consultative", "eligible", List.of()),
                "Quais APIs e dados estao relacionados a folha de pagamento?",
                "Encontrei dados confirmados de folha de pagamento e posso sugerir telas sem criar preview agora.",
                objectMapper.createObjectNode(),
                List.of(),
                null,
                List.of(),
                List.of("llm-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quais APIs e dados estao relacionados a folha de pagamento?",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).contains("preview-materialization-skipped-consultative-answer");
        assertThat(result.assistantMessage())
                .contains("dados confirmados de folha de pagamento");
        verifyNoInteractions(planService, patchCompilerService);
    }

    @Test
    void previewReturnsPlanAndCompiledPatchWhenBothStagesPass() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest("Crie um formulario", "openai", "gpt-5.4-mini", "test-key");
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local"))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of("minimal-form-plan-only"), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of("compiled-from-minimal-form-plan"), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).contains("minimal-form-plan-only", "compiled-from-minimal-form-plan");
        assertThat(result.minimalFormPlan()).isSameAs(plan);
        assertThat(result.compiledFormPatch()).isSameAs(patch);
        assertThat(result.diagnostics().fieldScopeDecision()).isEqualTo("not-evaluated");
    }

    @Test
    void previewUsesCanonicalCreateRequestSchemaInsteadOfGeneratingAnotherLlmPlan() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = createEmployeeFormIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um formulario de funcionarios",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.putObject("properties").putObject("nomeCompleto").put("type", "string");
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));
        when(planService.materializeCreateFormPlanFromCanonicalSchema(any(), eq(schema)))
                .thenReturn(new AgenticAuthoringPlanResult(
                        true,
                        List.of(),
                        List.of("minimal-form-plan-materialized-from-schemas-filtered"),
                        plan));
        when(patchCompilerService.compile(any(AgenticAuthoringCompileRequest.class)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of(), patch));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.minimalFormPlan()).isSameAs(plan);
        assertThat(result.compiledFormPatch()).isSameAs(patch);
        assertThat(result.warnings()).contains("minimal-form-plan-materialized-from-schemas-filtered");
        verify(planService).materializeCreateFormPlanFromCanonicalSchema(any(), eq(schema));
        verify(planService, never()).generateMinimalFormPlan(any(), any(), any(), any());
    }

    @Test
    void minimalFormPlanEndpointUsesCanonicalCreateRequestSchemaWithoutLlmGeneration() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = createEmployeeFormIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um formulario de funcionarios",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.putArray("required").add("nomeCompleto");
        schema.putObject("properties").putObject("nomeCompleto").put("type", "string");
        ObjectNode plan = objectMapper.createObjectNode();
        plan.putArray("fields").addObject().put("name", "nomeCompleto");
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));
        when(planService.materializeCreateFormPlanFromCanonicalSchema(any(), eq(schema)))
                .thenReturn(new AgenticAuthoringPlanResult(
                        true,
                        List.of(),
                        List.of("minimal-form-plan-materialized-from-schemas-filtered"),
                        plan));

        AgenticAuthoringPlanResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(),
                null,
                schemaRetrievalService)
                .generateMinimalFormPlan(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.minimalFormPlan()).isSameAs(plan);
        assertThat(result.warnings()).contains("minimal-form-plan-materialized-from-schemas-filtered");
        verify(planService).materializeCreateFormPlanFromCanonicalSchema(any(), eq(schema));
        verify(planService, never()).generateMinimalFormPlan(any(), any(), any(), any());
        verifyNoInteractions(patchCompilerService);
    }

    @Test
    void previewFailsClosedWhenCanonicalCreateRequestSchemaIsUnavailable() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um formulario de funcionarios",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                createEmployeeFormIntent());
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                any(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(SchemaFetchResult.failure(
                        SchemaFetchResult.Status.UNAVAILABLE,
                        503,
                        "http://localhost/schemas/filtered",
                        "SCHEMA_UNAVAILABLE",
                        "temporarily unavailable"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("canonical-create-request-schema-unavailable");
        assertThat(result.warnings()).contains(
                "minimal-form-plan-schema-grounding-required",
                "minimal-form-plan-schema-fetch:SCHEMA_UNAVAILABLE",
                "compile-skipped-invalid-minimal-form-plan");
        verify(planService, never()).generateMinimalFormPlan(any(), any(), any(), any());
        verifyNoInteractions(patchCompilerService);
    }

    @Test
    void previewBuildsSafeProjectKnowledgeInfluenceAuditFromSourceRefs() throws Exception {
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode projectKnowledge = contextHints.putObject("projectKnowledge");
        projectKnowledge.put("schemaVersion", "praxis-agentic-authoring-project-knowledge.v1");
        projectKnowledge.put("source", "domain_knowledge_concept");
        projectKnowledge.put("influenceCount", 99);
        ArrayNode entries = projectKnowledge.putArray("entries");
        entries.add("malformed-entry-must-not-count");
        ObjectNode cited = entries.addObject();
        cited.put("knowledgeId", "knowledge-1");
        cited.put("conceptKey", "human-resources.funcionarios.preference.identity-card");
        cited.put("kind", "project_preference");
        cited.put("visibility", "allow");
        cited.put("sourceSummary", "accepted authoring turn");
        cited.put("influence", "layout_preference");
        cited.put("summary", "Prefer compact identity cards.");
        cited.put("rawPayload", "MUST_NOT_LEAK");
        ObjectNode uncited = entries.addObject();
        uncited.put("knowledgeId", "knowledge-2");
        uncited.put("conceptKey", "human-resources.funcionarios.constraint.hidden");
        uncited.put("kind", "governance_constraint");
        uncited.put("visibility", "mask");
        uncited.put("sourceSummary", "security review");
        uncited.put("influence", "masked_context");
        uncited.put("summary", "Masked summary must not be copied to audit.");

        AgenticAuthoringIntentResolutionResult intent = modifyAddFieldIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Adicione cartao de identificacao compacto",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent,
                "session-1",
                "turn-1",
                List.of(),
                null,
                List.of(),
                contextHints);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        plan.putArray("sourceRefs")
                .add("intent-resolution")
                .add("projectKnowledge:knowledge-1");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        patch.putArray("sourceRefs")
                .add("projectKnowledge:knowledge-1");
        when(planService.generateMinimalFormPlan(any(), eq("tenant"), eq("user"), eq("local")))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of(), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan, null, intent)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of(), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        JsonNode audit = result.diagnostics().projectKnowledgeAudit();
        assertThat(audit.path("schemaVersion").asText())
                .isEqualTo("praxis-agentic-authoring-project-knowledge-audit.v1");
        assertThat(audit.path("influenceCount").asInt()).isEqualTo(2);
        assertThat(audit.path("entries")).hasSize(2);
        assertThat(audit.path("citedCount").asInt()).isEqualTo(1);
        assertThat(audit.path("uncitedCount").asInt()).isEqualTo(1);
        assertThat(audit.path("entries").path(0).path("knowledgeId").asText()).isEqualTo("knowledge-1");
        assertThat(audit.path("entries").path(0).path("cited").asBoolean()).isTrue();
        assertThat(audit.path("entries").path(0).path("sourceRefs").toString())
                .contains("projectKnowledge:knowledge-1");
        assertThat(audit.path("entries").path(1).path("cited").asBoolean()).isFalse();
        assertThat(audit.toString())
                .doesNotContain("MUST_NOT_LEAK")
                .doesNotContain("Prefer compact identity cards")
                .doesNotContain("Masked summary");
    }

    @Test
    void previewSkipsCompileWhenPlanIsInvalid() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest("Crie um formulario", null, null, null);
        ObjectNode plan = objectMapper.createObjectNode();
        when(planService.generateMinimalFormPlan(request, null, null, null))
                .thenReturn(new AgenticAuthoringPlanResult(false, List.of("titulo is required"), List.of(), plan));

        AgenticAuthoringPreviewResult result = service().preview(request, null, null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("titulo is required");
        assertThat(result.warnings()).contains("compile-skipped-invalid-minimal-form-plan");
        assertThat(result.compiledFormPatch().isMissingNode()).isTrue();
        assertThat(result.diagnostics().fieldScopeDecision()).isEqualTo("not-evaluated");
    }

    @Test
    void previewAllowsModifyIntentAndPassesCurrentPageToCompiler() throws Exception {
        ObjectNode currentPage = objectMapper.createObjectNode();
        currentPage.putArray("widgets");
        AgenticAuthoringIntentResolutionResult intent = modifyAddFieldIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Adicione observacao interna",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                currentPage,
                intent);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("profileId", "modify-existing-form");
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local"))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of(), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan, currentPage, intent)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of("compiled-as-current-page-modification"), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch()).isSameAs(patch);
        assertThat(result.warnings()).contains("compiled-as-current-page-modification");
        assertThat(result.diagnostics().operationKind()).isEqualTo("modify");
        assertThat(result.diagnostics().changeKind()).isEqualTo("add_field");
        assertThat(result.diagnostics().targetWidgetKey()).isEqualTo("funcionarios-form");
        assertThat(result.diagnostics().fieldScopeDecision()).isEqualTo("accepted-add-local-field");
    }

    @Test
    void previewAllowsRemoveFieldIntentAndPassesCurrentPageToCompiler() throws Exception {
        ObjectNode currentPage = objectMapper.createObjectNode();
        currentPage.putArray("widgets");
        AgenticAuthoringIntentResolutionResult intent = removeFieldIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Remova observacao interna",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                currentPage,
                intent);
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("profileId", "create-minimal-form");
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("profileId", "modify-existing-form");
        when(planService.generateMinimalFormPlan(request, "tenant", "user", "local"))
                .thenReturn(new AgenticAuthoringPlanResult(true, List.of(), List.of(), plan));
        when(patchCompilerService.compile(new AgenticAuthoringCompileRequest(plan, currentPage, intent)))
                .thenReturn(new AgenticAuthoringCompileResult(true, List.of(), List.of("local-transient-fields-removed-only"), patch));

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.compiledFormPatch()).isSameAs(patch);
        assertThat(result.warnings()).contains("local-transient-fields-removed-only");
        assertThat(result.diagnostics().operationKind()).isEqualTo("remove");
        assertThat(result.diagnostics().changeKind()).isEqualTo("remove_field");
        assertThat(result.diagnostics().fieldScopeDecision()).isEqualTo("accepted-remove-local-field");
    }

    @Test
    void previewReturnsUiCompositionPlanFromHostProviderBeforeMinimalFormPipeline() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma tela master detail de departamentos",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                hostUiCompositionIntent());
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.putArray("widgets").addObject().put("key", "department-master").put("componentId", "praxis-list");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.put("version", "1.0.0");
        compiledFormPatch.putObject("patch");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:quickstart-human-resources"),
                        plan,
                        compiledFormPatch));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider)).preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.minimalFormPlan().isMissingNode()).isTrue();
        assertThat(result.uiCompositionPlan()).isSameAs(plan);
        assertThat(result.compiledFormPatch()).isSameAs(compiledFormPatch);
        assertThat(result.warnings()).contains(
                "ui-composition-plan-provider:quickstart-human-resources",
                "compiled-form-patch-materialized-by-page-builder");
    }

    @Test
    void previewBlocksTableOnlyMaterializationWhenSemanticDecisionRequiresChart() throws Exception {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.putArray("widgets").addObject().put("key", "incidents-table").put("componentId", "praxis-table");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        ObjectNode page = compiledFormPatch.putObject("patch").putObject("page");
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("id", "incidents-table");
        widget.putObject("definition").put("id", "praxis-table");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-table"),
                        plan,
                        compiledFormPatch));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider)).preview(new AgenticAuthoringPlanRequest(
                        "Prefiro graficos",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        operationalMonitoringDashboardIntent()), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).contains("semantic-preview-chart-required");
        assertThat(result.warnings()).contains("semantic-preview-materialization-mismatch");
        assertThat(result.assistantMessage()).contains("ainda nao consegui montar o grafico pedido");
    }

    @Test
    void previewBlocksMaterializationThatOmitsRequestedGovernedPrimaryComponent() throws Exception {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.putArray("widgets").addObject().put("key", "orders-table").put("componentId", "praxis-table");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        ObjectNode page = compiledFormPatch.putObject("patch").putObject("page");
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("id", "orders-table");
        widget.putObject("definition").put("id", "praxis-table");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-table"),
                        plan,
                        compiledFormPatch));
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/acme/orders",
                "post",
                "/schemas/filtered?path=/api/acme/orders/filter/cursor&operation=post&schemaType=response",
                "/api/acme/orders/filter/cursor",
                "POST",
                0.93d,
                "matched orders",
                List.of("semantic-retrieval"));
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "guided-step-workspace",
                "page",
                "praxis-stepper",
                List.of(),
                true,
                true,
                "llm-authored-semantic-decision");
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie uma pagina em etapas para pedidos",
                "Vou montar uma pagina em etapas.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                visualizationDecision);

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider))
                .preview(new AgenticAuthoringPlanRequest(
                        "Crie uma pagina em etapas para pedidos",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        intent), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes())
                .contains(AgenticAuthoringSemanticMaterializationPolicy.PRIMARY_COMPONENT_REQUIRED_FAILURE);
        assertThat(result.warnings()).contains("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).contains("praxis-table");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).doesNotContain("praxis-stepper");
        assertThat(result.assistantMessage())
                .contains("ainda nao montou esse componente")
                .doesNotContain("propriedades incompativeis com o componente de tabela");
    }

    @Test
    void previewAcceptsCrudPrimaryComponentWhenMaterializedAsMasterDetailComposition() throws Exception {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        ArrayNode planWidgets = plan.putArray("widgets");
        planWidgets.addObject().put("key", "employees-master").put("componentId", "praxis-table");
        planWidgets.addObject().put("key", "employees-detail").put("componentId", "praxis-dynamic-form");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        ObjectNode page = compiledFormPatch.putObject("patch").putObject("page");
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode table = widgets.addObject();
        table.put("id", "employees-master");
        table.putObject("definition").put("id", "praxis-table");
        ObjectNode form = widgets.addObject();
        form.put("id", "employees-detail");
        form.putObject("definition").put("id", "praxis-dynamic-form");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-master-detail"),
                        plan,
                        compiledFormPatch));
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter/cursor",
                "POST",
                0.93d,
                "matched employees",
                List.of("semantic-retrieval", "tool-search-api-resources"));
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "employee_tracking_with_profile_access",
                "single_column",
                "praxis-crud",
                List.of(),
                true,
                false,
                "llm-authored-semantic-decision");
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_page",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie uma tela para acompanhar colaboradores e abrir perfil",
                "Vou montar uma tela de colaboradores.",
                null,
                List.of(),
                null,
                List.of(),
                List.of("llm-fast-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                visualizationDecision);

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider))
                .preview(new AgenticAuthoringPlanRequest(
                        "Crie uma tela para acompanhar colaboradores e abrir perfil",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        intent), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes())
                .doesNotContain(AgenticAuthoringSemanticMaterializationPolicy.PRIMARY_COMPONENT_REQUIRED_FAILURE);
        assertThat(result.warnings()).doesNotContain("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).contains("praxis-table", "praxis-dynamic-form");
    }

    @Test
    void previewAcceptsPraxisListPrimaryComponentWhenGenericProviderMaterializesListPage() throws Exception {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-perfil-heroi",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-perfil-heroi/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-perfil-heroi/filter/cursor",
                "POST",
                0.93d,
                "matched employee profile",
                List.of("semantic-retrieval", "tool-search-api-resources", "semantic-role:profile-projection"));
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "Visao resumida de funcionario",
                "list-page",
                "praxis-list",
                List.of(),
                true,
                true,
                "llm-authored-semantic-decision");
        AgenticAuthoringSemanticDecision semanticDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "profile-list-decision",
                "create",
                "page",
                "author_component",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        candidate.resourcePath(),
                        candidate.operation(),
                        candidate.schemaUrl(),
                        candidate.submitUrl(),
                        candidate.submitMethod()),
                visualizationDecision,
                new AgenticAuthoringSemanticDecision.RetrievalEvidence(
                        "semantic_retrieval",
                        List.of("tool-search-api-resources"),
                        1),
                false,
                "",
                "",
                "");
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "author_component",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "visao resumida de funcionario",
                "Vou montar uma lista resumida de funcionario.",
                null,
                List.of(),
                null,
                List.of(),
                List.of("llm-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                visualizationDecision,
                semanticDecision);

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(new AgenticAuthoringPlanRequest(
                        "visao resumida de funcionario",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        intent), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).doesNotContain("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("resource-list-page");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-list");
        assertThat(result.uiCompositionPlan().path("widgets").get(0).path("inputs")
                .path("config").path("dataSource").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-perfil-heroi");
    }

    @Test
    void previewAcceptsPageBuilderPrimaryComponentWhenGenericProviderMaterializesProfilePage() throws Exception {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-perfil-heroi",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-perfil-heroi/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-perfil-heroi/filter/cursor",
                "POST",
                0.93d,
                "matched employee profile",
                List.of("semantic-retrieval", "tool-search-api-resources", "semantic-role:profile-projection"));
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "employee_profile_page",
                "single_column",
                "praxis-page-builder",
                List.of(),
                true,
                false,
                List.of("praxis-table", "praxis-list", "praxis-chart"),
                false,
                false,
                "llm-authored-semantic-decision");
        AgenticAuthoringSemanticDecision semanticDecision = new AgenticAuthoringSemanticDecision(
                AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                "profile-page-decision",
                "create",
                "page",
                "create_page_profile_screen",
                new AgenticAuthoringSemanticDecision.SelectedResource(
                        candidate.resourcePath(),
                        candidate.operation(),
                        candidate.schemaUrl(),
                        candidate.submitUrl(),
                        candidate.submitMethod()),
                visualizationDecision,
                new AgenticAuthoringSemanticDecision.RetrievalEvidence(
                        "semantic_retrieval",
                        List.of("tool-search-api-resources"),
                        1),
                false,
                "",
                "",
                "");
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_page_profile_screen",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "quero uma tela de perfil individual do funcionario",
                "Vou montar uma tela de perfil individual do funcionario.",
                null,
                List.of(),
                null,
                List.of(),
                List.of("llm-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                visualizationDecision,
                semanticDecision);

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(new AgenticAuthoringPlanRequest(
                        "quero uma tela de perfil individual do funcionario",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        intent), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.warnings()).doesNotContain("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("resource-profile-page");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-rich-content", "praxis-dynamic-form");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .doesNotContain("praxis-table", "praxis-list", "praxis-chart");
    }

    @Test
    void previewDescribesGovernanceReviewWithoutBlamingTableContract() throws Exception {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        ArrayNode widgets = plan.putArray("widgets");
        widgets.addObject().put("key", "payroll-chart").put("componentId", "praxis-chart");
        widgets.addObject().put("key", "payroll-table").put("componentId", "praxis-table")
                .putObject("inputs").put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.putObject("patch").putArray("widgets");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-governed-review"),
                        plan,
                        compiledFormPatch));
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Gostei, mas prefiro graficos",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                keywordFallbackReviewDashboardIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).contains("semantic-decision-review-required:keyword-fallback-fail-safe");
        assertThat(result.assistantMessage())
                .contains("Montei uma primeira pre-visualizacao de dashboard")
                .contains("Como prosseguir")
                .doesNotContain("propriedades incompativeis")
                .doesNotContain("componente de tabela");
    }

    @Test
    void previewAuditsProjectKnowledgeRefsFromUiCompositionPlanProvider() throws Exception {
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode projectKnowledge = contextHints.putObject("projectKnowledge");
        projectKnowledge.put("schemaVersion", "praxis-agentic-authoring-project-knowledge.v1");
        projectKnowledge.put("source", "domain_knowledge_concept");
        ObjectNode knowledgeEntry = projectKnowledge.putArray("entries").addObject();
        knowledgeEntry.put("knowledgeId", "knowledge-ui-composition");
        knowledgeEntry.put("conceptKey", "page-builder.employee-workspace");
        knowledgeEntry.put("kind", "project_preference");
        knowledgeEntry.put("visibility", "allow");
        knowledgeEntry.put("sourceSummary", "accepted authoring turn");
        knowledgeEntry.put("influence", "layout_preference");
        knowledgeEntry.put("summary", "Do not leak this summary into the audit.");

        ObjectNode uiCompositionPlan = objectMapper.createObjectNode();
        uiCompositionPlan.put("version", "1.0");
        uiCompositionPlan.put("kind", "praxis.ui-composition-plan");
        uiCompositionPlan.putArray("sourceRefs")
                .add("intent-resolution")
                .add("projectKnowledge:knowledge-ui-composition");
        uiCompositionPlan.putArray("widgets");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.put("version", "1.0.0");
        compiledFormPatch.putObject("patch");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test"),
                        uiCompositionPlan,
                        compiledFormPatch));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider)).preview(new AgenticAuthoringPlanRequest(
                        "Crie uma area de trabalho para empregados",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        selectedMasterDetailIntent(),
                        "session-1",
                        "turn-1",
                        List.of(),
                        null,
                        List.of(),
                        contextHints), "tenant", "user", "local");

        JsonNode audit = result.diagnostics().projectKnowledgeAudit();
        assertThat(audit.path("citedCount").asInt()).isEqualTo(1);
        assertThat(audit.path("uncitedCount").asInt()).isZero();
        assertThat(audit.path("entries").path(0).path("cited").asBoolean()).isTrue();
        assertThat(audit.path("entries").path(0).path("sourceRefs").toString())
                .contains("projectKnowledge:knowledge-ui-composition");
        assertThat(audit.toString()).doesNotContain("Do not leak this summary");
    }

    @Test
    void previewProviderReceivesIntentEffectivePromptInsteadOfRecomposedPendingClarification() throws Exception {
        String rawPrompt = "Com base nisso, agora crie uma tabela operacional de folhas de pagamento";
        AtomicReference<AgenticAuthoringPlanRequest> capturedRequest = new AtomicReference<>();
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("layoutPreset", "single-table-page");
        plan.putArray("widgets").addObject().put("key", "payroll-table").put("componentId", "praxis-table");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.putObject("patch");
        AgenticAuthoringUiCompositionPlanProvider provider = request -> {
            capturedRequest.set(request);
            return java.util.Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:single-table"),
                    plan,
                    compiledFormPatch));
        };

        new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider)).preview(new AgenticAuthoringPlanRequest(
                        rawPrompt,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        payrollTableIntent(rawPrompt),
                        "session-1",
                        "turn-2",
                        List.of(new AgenticAuthoringConversationMessage(
                                "m1",
                                "user",
                                "Crie um dashboard de folha de pagamento",
                                null)),
                        new AgenticAuthoringPendingClarification(
                                "Crie um dashboard de folha de pagamento",
                                List.of("Qual recorte do dashboard de folha de pagamento voce quer usar?"),
                                "Qual recorte do dashboard de folha de pagamento voce quer usar?",
                                "turn-1",
                                objectMapper.createObjectNode())), "tenant", "user", "local");

        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().userPrompt()).isEqualTo(rawPrompt);
    }

    @Test
    void previewProviderContextualizesShortIntentEffectivePromptWhenUserConfirmsPriorProposal() throws Exception {
        String sourcePrompt = "Crie uma pagina operacional com Praxis Tabs. A aba Cadastro deve conter formulario local. A aba Registros deve conter Praxis CRUD local. A aba Relacionamentos deve conter lista em cards. Use conteudo local editorial de demonstracao.";
        String confirmation = "Sim, siga e materialize a proposta agora.";
        AtomicReference<AgenticAuthoringPlanRequest> capturedRequest = new AtomicReference<>();
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("layoutPreset", "local-editorial-tabbed-workspace");
        plan.putArray("widgets").addObject().put("key", "local-solicitacoes-workspace").put("componentId", "praxis-tabs");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.putObject("patch");
        AgenticAuthoringUiCompositionPlanProvider provider = request -> {
            capturedRequest.set(request);
            return java.util.Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("ui-composition-plan-provider:local-editorial-tabbed-workspace"),
                    plan,
                    compiledFormPatch));
        };

        new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider)).preview(new AgenticAuthoringPlanRequest(
                        confirmation,
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        payrollTableIntent(confirmation),
                        "session-1",
                        "turn-2",
                        List.of(
                                new AgenticAuthoringConversationMessage("m1", "user", sourcePrompt, null),
                                new AgenticAuthoringConversationMessage(
                                        "m2",
                                        "assistant",
                                        "Posso seguir com Cadastro, Registros com CRUD local e Relacionamentos em cards.",
                                        null)),
                        null), "tenant", "user", "local");

        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().userPrompt())
                .contains(sourcePrompt)
                .contains(confirmation);
    }

    @Test
    void previewReturnsSelectedResourceDashboardPlanInsteadOfRejectingNonFormArtifact() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard Confirmed: usar /api/human-resources/vw-ranking-reputacao",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                selectedDashboardIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("intent-resolution-artifact-must-be-form");
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("resource-dashboard");
        assertThat(result.uiCompositionPlan().path("widgets").get(0).path("inputs").path("resourcePath").asText())
                .isEqualTo("/api/human-resources/vw-ranking-reputacao");
        assertThat(result.assistantMessage())
                .contains("Montei uma primeira versao")
                .contains("tabela conectada")
                .doesNotContain("ainda nao consegui montar o grafico pedido");
        assertThat(result.assistantMessage())
                .doesNotContain("- Tabela: conectada ao recurso para carregar schema e dados");
        assertThat(result.warnings()).contains(
                "ui-composition-plan-provider:selected-resource-dashboard",
                "compiled-form-patch-materialized-by-page-builder");
        assertThat(result.failureCodes()).doesNotContain(
                "semantic-preview-chart-required",
                "semantic-preview-dashboard-required");
        assertThat(result.diagnostics().fieldScopeDecision()).isEqualTo("accepted-create");
    }

    @Test
    void previewRejectsTableOnlyMaterializationWhenUserRequestedChart() throws Exception {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.putArray("widgets").addObject().put("key", "ranking-table").put("componentId", "praxis-table");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        ObjectNode page = compiledFormPatch.putObject("patch").putObject("page");
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("id", "ranking-table");
        widget.putObject("definition").put("id", "praxis-table");
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-table"),
                        plan,
                        compiledFormPatch));
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Gostei da visualizacao, mas prefiro que mostre usando graficos",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                selectedDashboardChartIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).contains("semantic-preview-chart-required");
        assertThat(result.warnings()).contains("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).contains("praxis-table");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).doesNotContain("praxis-chart");
        assertThat(result.assistantMessage())
                .contains("ainda nao consegui montar o grafico pedido")
                .doesNotContain("A tabela foi conectada ao recurso");
    }

    @Test
    void previewDoesNotOverrideLlmTableDecisionWithOperationalKeywordHeuristic() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringTableIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-operational-dashboard-required");
        assertThat(result.warnings()).doesNotContain("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).contains("praxis-table");
        assertThat(result.uiCompositionPlan().path("widgets").toString()).doesNotContain("praxis-chart");
        assertThat(result.assistantMessage())
                .doesNotContain("dashboard operacional completo");
    }

    @Test
    void previewWarnsWhenDashboardAxesAreInferredBeforeSchemaVerification() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("semantic-axis-schema-verification-pending");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes")).hasSize(3);
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"provenance\":\"llm-authored-semantic-axis\"")
                .contains("\"schemaVerified\":false");
    }

    @Test
    void previewBuildsStarterDashboardWhenVisualizationDecisionIsMissing() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel com a visao geral sobre funcionarios.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntentWithoutVisualizationDecision());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("resource-dashboard");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .contains("praxis-rich-content", "praxis-table")
                .doesNotContain("praxis-chart");
        assertThat(result.uiCompositionPlan().path("canvas").path("items").path("funcionarios-table").path("row").asInt())
                .isEqualTo(5);
        assertThat(result.uiCompositionPlan().path("diagnostics").path("visualizationDecisionIntent").asText())
                .isEqualTo("generic-dashboard");
    }

    @Test
    void previewFeedsSchemaFieldsIntoGenericDashboardPlanner() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel com a visao geral sobre funcionarios, com graficos e detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntentWithoutVisualizationDecision());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("id").put("type", "integer");
        properties.putObject("nomeCompleto").put("type", "string")
                .putObject("x-ui").put("label", "Nome completo");
        properties.putObject("departamentoNome").put("type", "string")
                .putObject("x-ui").put("label", "Departamento");
        properties.putObject("cargoNome").put("type", "string")
                .putObject("x-ui").put("label", "Cargo");
        properties.putObject("dataAdmissao").put("type", "string").put("format", "date");
        ObjectNode filterSchema = objectMapper.createObjectNode();
        ObjectNode filterProperties = filterSchema.putObject("properties");
        filterProperties.putObject("departamentoNome").put("type", "string")
                .putObject("x-ui").put("label", "Departamento");
        filterProperties.putObject("cargoNome").put("type", "string")
                .putObject("x-ui").put("label", "Cargo");
        ObjectNode departmentIds = filterProperties.putObject("departamentoIdsIn");
        departmentIds.put("type", "array");
        departmentIds.putObject("x-ui")
                .put("label", "Departamento")
                .put("controlType", "async-select")
                .put("multiple", true)
                .put("endpoint", "/api/human-resources/departamentos/options/filter");
        ObjectNode roleIds = filterProperties.putObject("cargoIdsIn");
        roleIds.put("type", "array");
        roleIds.putObject("x-ui")
                .put("label", "Cargo")
                .put("controlType", "async-select")
                .put("multiple", true)
                .put("endpoint", "/api/human-resources/cargos/options/filter");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    JsonNode resolvedSchema = "request".equals(context.getSchemaType()) ? filterSchema : schema;
                    return SchemaFetchResult.success(resolvedSchema, "http://localhost/schemas/filtered");
                });
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/funcionarios"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        employeeStatsCapabilities(),
                        "http://localhost/api/human-resources/funcionarios/capabilities"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("key"))
                .contains("funcionarios-chart-departamentoNome", "funcionarios-chart-cargoNome")
                .doesNotContain("funcionarios-chart-nomeCompleto", "funcionarios-chart-dataAdmissao");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .contains("funcionarios-chart-departamentoNome.crossFilter->funcionarios-table.queryContext")
                .contains("funcionarios-chart-cargoNome.crossFilter->funcionarios-table.queryContext");
        JsonNode departmentChart = result.uiCompositionPlan().path("widgets").findParents("key").stream()
                .filter(widget -> "funcionarios-chart-departamentoNome".equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode roleChart = result.uiCompositionPlan().path("widgets").findParents("key").stream()
                .filter(widget -> "funcionarios-chart-cargoNome".equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(departmentChart.path("inputs").path("config").path("semanticAxis").path("field").asText())
                .isEqualTo("departamentoNome");
        assertThat(departmentChart.path("inputs").path("config").path("semanticAxis")
                .path("statsExecutionField").asText()).isEqualTo("departamento");
        assertThat(departmentChart.path("inputs").path("config").path("dataSource").path("query")
                .path("statsRequest").path("field").asText()).isEqualTo("departamento");
        assertThat(roleChart.path("inputs").path("config").path("dataSource").path("query")
                .path("statsRequest").path("field").asText()).isEqualTo("cargoNome");
        assertThat(departmentChart.path("inputs").path("config").path("dataSource").path("query")
                .path("statsRequest").path("metric").has("field")).isFalse();
        assertThat(departmentChart.path("inputs").path("config").path("dataSource").path("query")
                .path("statsRequest").path("metric").path("alias").asText()).isEqualTo("total");
        assertThat(departmentChart.path("inputs").path("config").path("interactions")
                .path("eventActions").path("crossFilter").path("mapping").toString())
                .isEqualTo("{\"key\":\"departamentoIdsIn\"}");
        assertThat(roleChart.path("inputs").path("config").path("interactions")
                .path("eventActions").path("crossFilter").path("mapping").toString())
                .isEqualTo("{\"cargoNome\":\"cargoNome\"}");
        JsonNode departmentPointLink = findBinding(
                result.uiCompositionPlan().path("bindings"),
                "funcionarios-chart-departamentoNome.pointClick->funcionarios-table.queryContext");
        assertThat(departmentPointLink.path("policy").path("distinctBy").asText())
                .isEqualTo("payload.data.key");
        assertThat(departmentPointLink.path("transform").path("template").path("filters")
                .path("departamentoIdsIn").toString())
                .isEqualTo("[\"${payload.data.key}\"]");
        JsonNode departmentCrossFilterLink = findBinding(
                result.uiCompositionPlan().path("bindings"),
                "funcionarios-chart-departamentoNome.crossFilter->funcionarios-table.queryContext");
        assertThat(departmentCrossFilterLink.path("policy").path("distinctBy").asText())
                .isEqualTo("payload.filters.departamentoIdsIn");
        assertThat(departmentCrossFilterLink.path("transform").path("template").path("filters")
                .path("departamentoIdsIn").toString())
                .isEqualTo("[\"${payload.filters.departamentoIdsIn}\"]");
        JsonNode departmentSurfaceLink = findBinding(
                result.uiCompositionPlan().path("bindings"),
                "funcionarios-chart-departamentoNome.pointClick->surface.open");
        JsonNode departmentSurfaceBinding = departmentSurfaceLink.path("to").path("payload").path("bindings").path(0);
        assertThat(departmentSurfaceBinding.path("to").asText())
                .endsWith("queryContext.filters.departamentoIdsIn");
        assertThat(departmentSurfaceBinding.path("mode").asText()).isEqualTo("template");
        assertThat(departmentSurfaceBinding.path("value").toString())
                .isEqualTo("[\"${payload.data.key}\"]");
        JsonNode roleSurfaceLink = findBinding(
                result.uiCompositionPlan().path("bindings"),
                "funcionarios-chart-cargoNome.pointClick->surface.open");
        JsonNode roleSurfaceBinding = roleSurfaceLink.path("to").path("payload").path("bindings").path(0);
        assertThat(roleSurfaceLink.path("policy").path("distinctBy").asText())
                .isEqualTo("payload.data.cargoNome");
        assertThat(roleSurfaceBinding.path("from").asText()).isEqualTo("payload.data.cargoNome");
        assertThat(roleSurfaceBinding.path("to").asText()).endsWith("queryContext.filters.cargoNome");
        assertThat(result.uiCompositionPlan().toString())
                .contains("${item.departamentoNome}", "${item.cargoNome}")
                .doesNotContain("${item.departamento}", "${item.cargo}");
        assertThat(result.warnings()).contains("semantic-chart-interactions-grounded");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("dashboardBlueprint").path("domainSpecific").asBoolean())
                .isFalse();
    }

    @Test
    void previewReflowsInferredDashboardAfterUnsupportedSchemaAxisPrune() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel de funcionarios por departamento e cargo, com graficos e detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                inferredEmployeeDashboardIntentWithCargoIdEvidence());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("cargoNome").put("type", "string")
                .putObject("x-ui").put("label", "Cargo");
        properties.putObject("departamentoNome").put("type", "string")
                .putObject("x-ui").put("label", "Departamento");
        properties.putObject("cargoId").put("type", "integer")
                .putObject("x-ui").put("label", "Cargo");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/funcionarios"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        employeeStatsCapabilities(),
                        "http://localhost/api/human-resources/funcionarios/capabilities"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings())
                .as("widgets=%s axes=%s",
                        result.uiCompositionPlan().path("widgets").findValuesAsText("key"),
                        result.uiCompositionPlan().path("diagnostics").path("semanticAxes"))
                .contains(
                "semantic-chart-group-by-unsupported-field-type",
                "ui-composition-plan-layout-reflowed-after-widget-prune");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("key"))
                .contains("funcionarios-chart-cargoNome", "funcionarios-chart-departamentoNome")
                .doesNotContain("funcionarios-chart-cargoId");

        JsonNode desktopItems = result.uiCompositionPlan().path("canvas").path("items");
        assertThat(desktopItems.path("funcionarios-chart-cargoNome").path("colSpan").asInt()).isEqualTo(6);
        assertThat(desktopItems.path("funcionarios-chart-departamentoNome").path("colSpan").asInt()).isEqualTo(6);
        assertThat(List.of(
                desktopItems.path("funcionarios-chart-cargoNome").path("col").asInt(),
                desktopItems.path("funcionarios-chart-departamentoNome").path("col").asInt()))
                .containsExactlyInAnyOrder(1, 7);

        JsonNode mobileItems = result.uiCompositionPlan().path("deviceLayouts")
                .path("mobile").path("canvas").path("items");
        assertThat(mobileItems.path("funcionarios-list").path("row").asInt()).isEqualTo(17);
        assertThat(mobileItems.path("funcionarios-table").path("row").asInt()).isEqualTo(23);

        JsonNode tabletItems = result.uiCompositionPlan().path("deviceLayouts")
                .path("tablet").path("canvas").path("items");
        assertThat(tabletItems.path("funcionarios-list").path("row").asInt()).isEqualTo(11);
        assertThat(tabletItems.path("funcionarios-table").path("row").asInt()).isEqualTo(18);
    }

    @Test
    void previewFailsClosedWhenStatsDimensionIsNotInResourceCapabilities() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel de funcionarios por departamento.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntentWithoutVisualizationDecision());
        ObjectNode schema = objectMapper.createObjectNode();
        schema.putObject("properties").putObject("departamentoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));
        ObjectNode unsupported = objectMapper.createObjectNode();
        unsupported.putObject("stats").putArray("fields").addObject()
                .put("field", "ativo")
                .put("label", "Ativo")
                .put("groupByEligible", true)
                .putArray("metrics").add("COUNT");
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(anyString(), any(), any(), any(), any()))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        unsupported,
                        "http://localhost/api/human-resources/funcionarios/capabilities"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .contains("semantic-preview-axis-stats-capability-verification-required");
        assertThat(result.warnings())
                .contains("semantic-axis-stats-capability-verification-unsupported");
    }

    @Test
    void previewGroundsComparisonFromCanonicalCapabilitiesAndAnalyticsProjection() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        ObjectNode responseSchema = comparisonResourceSchema();
        ObjectNode filterSchema = comparisonFilterSchema();
        ObjectNode comparisonSchema = comparisonAnalyticsSchema();
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), eq("http://localhost")))
                .thenReturn(SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered"));
        List<AiSchemaContext> principalAwareSchemaContexts = new ArrayList<>();
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    principalAwareSchemaContexts.add(context);
                    assertThat(context.getOperation()).isEqualTo("post");
                    if (context.getPath().endsWith("/stats/comparison")) {
                        assertThat(context.getSchemaType()).isEqualTo("response");
                        return SchemaFetchResult.success(comparisonSchema, "http://localhost/schemas/filtered");
                    }
                    assertThat(context.getPath())
                            .isEqualTo("/api/human-resources/vw-analytics-afastamentos/filter");
                    return SchemaFetchResult.success(
                            "request".equals(context.getSchemaType()) ? filterSchema : responseSchema,
                            "http://localhost/schemas/filtered");
                });
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        comparisonStatsCapabilities(),
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));
        when(resourceSurfaceCatalogRetrievalService.fetchCatalogResult(
                eq("human-resources.funcionarios"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceSurfaceCatalogFetchResult.success(
                        employeeSurfaceCatalog("resource-context-required"),
                        "http://localhost/schemas/surfaces?resource=human-resources.funcionarios"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                resourceSurfaceCatalogRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        JsonNode chart = result.uiCompositionPlan().path("widgets").findParents("componentId").stream()
                .filter(widget -> "praxis-chart".equals(widget.path("componentId").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode config = chart.path("inputs").path("config");
        JsonNode query = config.path("dataSource").path("query");
        JsonNode statsRequest = query.path("statsRequest");
        assertThat(query.path("statsOperation").asText()).isEqualTo("comparison");
        assertThat(statsRequest.has("metric")).isFalse();
        assertThat(statsRequest.path("metrics")).hasSize(2);
        assertThat(statsRequest.path("periodField").asText()).isEqualTo("competencia");
        assertThat(config.path("series")).hasSize(4);
        assertThat(query.path("metrics").findValuesAsText("field")).containsExactly(
                "__praxisComparison_funcionarioId_current",
                "__praxisComparison_funcionarioId_previous",
                "__praxisComparison_diasAfastado_current",
                "__praxisComparison_diasAfastado_previous");
        assertThat(config.path("series").findValuesAsText("field")).containsExactly(
                "__praxisComparison_funcionarioId_current",
                "__praxisComparison_funcionarioId_previous",
                "__praxisComparison_diasAfastado_current",
                "__praxisComparison_diasAfastado_previous");
        assertThat(query.path("metrics").findValuesAsText("schemaProbeStatus"))
                .containsOnly("verified-derived-comparison-output");
        assertThat(statsRequest.path("metrics").findValuesAsText("field"))
                .containsExactly("funcionarioId", "diasAfastado");
        assertThat(config.path("analyticsProjection").path("governance").path("policyRefs").path(0)
                .path("policyId").asText()).isEqualTo("absence-criticality-policy");
        assertThat(config.path("analyticsProjection").path("governance").path("policyRefs").path(0)
                .path("policyVersion").asText()).isEqualTo("2026-07");
        assertThat(config.path("analyticsProjection").path("bindings").path("primaryDimension")
                .path("keyFilterField").asText()).isEqualTo("departamentoIdsIn");
        assertThat(config.path("semanticAxis").path("statsVerified").asBoolean()).isTrue();
        assertThat(config.path("interactions").path("eventActions").path("crossFilter")
                .path("mapping").toString()).isEqualTo("{\"key\":\"departamentoIdsIn\"}");
        assertThat(principalAwareSchemaContexts).extracting(AiSchemaContext::getPath)
                .containsExactlyInAnyOrder(
                        "/api/human-resources/vw-analytics-afastamentos/stats/comparison",
                        "/api/human-resources/vw-analytics-afastamentos/filter",
                        "/api/human-resources/vw-analytics-afastamentos/filter");
        String chartKey = chart.path("key").asText();
        JsonNode table = result.uiCompositionPlan().path("widgets").findParents("componentId").stream()
                .filter(widget -> "praxis-table".equals(widget.path("componentId").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode chartToTable = findBinding(
                result.uiCompositionPlan().path("bindings"),
                chartKey + ".crossFilter->" + table.path("key").asText() + ".queryContext");
        assertThat(chartToTable.path("policy").path("distinctBy").asText())
                .isEqualTo("payload.filters.departamentoIdsIn");
        assertThat(chartToTable.path("transform").path("template").path("filters")
                .path("departamentoIdsIn").toString())
                .isEqualTo("[\"${payload.filters.departamentoIdsIn}\"]");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .doesNotContain("pointClick->surface.open");
        JsonNode list = result.uiCompositionPlan().path("widgets").findParents("componentId").stream()
                .filter(widget -> "praxis-list".equals(widget.path("componentId").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode recordOpenAction = list.path("inputs").path("config").path("actions").path(0);
        assertThat(recordOpenAction.path("action").asText()).isEqualTo("surface.open");
        assertThat(recordOpenAction.path("recordOpen").path("sourceIdentityField").asText())
                .isEqualTo("funcionarioId");
        assertThat(recordOpenAction.path("recordOpen").path("target").path("resourceKey").asText())
                .isEqualTo("human-resources.funcionarios");
        assertThat(recordOpenAction.path("recordOpen").path("target").path("surfaceId").asText())
                .isEqualTo("hero-profile");
        assertThat(recordOpenAction.has("globalAction")).isFalse();
        assertThat(result.warnings()).contains(
                "semantic-axis-stats-capability-verified",
                "semantic-chart-interactions-grounded");
        assertThat(result.uiCompositionPlan().toString())
                .doesNotContain("sampleRows")
                .doesNotContain("rawRows")
                .doesNotContain("${item.id}")
                .doesNotContain("threshold");
    }

    @Test
    void previewRejectsComparisonWhenCurrentPrincipalCannotUseTheOperation() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        ObjectNode capabilities = comparisonStatsCapabilities();
        ObjectNode availability = (ObjectNode) capabilities.path("operations")
                .path("statsComparison").path("availability");
        availability.put("allowed", false);
        availability.put("reason", "missing-authority");
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        capabilities,
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .containsExactly("governed-analytics-comparison-operation-unavailable-missing-authority");
        assertThat(result.uiCompositionPlan().isEmpty()).isTrue();
        verifyNoInteractions(schemaRetrievalService);
    }

    @Test
    void previewMaterializesAggregateOnlyComparisonWhenCurrentPrincipalCannotReadNominalRows() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        ObjectNode capabilities = comparisonStatsCapabilities();
        ObjectNode nominalAvailability = (ObjectNode) capabilities.path("operations")
                .path("filter").path("availability");
        nominalAvailability.put("allowed", false);
        nominalAvailability.put("reason", "missing-authority");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), eq("http://localhost")))
                .thenReturn(SchemaFetchResult.success(
                        comparisonResourceSchema(),
                        "http://localhost/schemas/filtered"));
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        capabilities,
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(SchemaFetchResult.success(
                        comparisonAnalyticsSchema(),
                        "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                resourceSurfaceCatalogRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .contains("praxis-chart")
                .doesNotContain("praxis-list", "praxis-table");
        assertThat(result.uiCompositionPlan().path("layoutPresetOptions").path("detailStrategy").asText())
                .isEqualTo("aggregate-only");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .doesNotContain("surface.open", ".crossFilter->", "-list", "-table");
        assertThat(result.uiCompositionPlan().path("canvas").path("items").toString())
                .doesNotContain("-list", "-table");
        assertThat(result.uiCompositionPlan().path("grouping").toString())
                .doesNotContain("-list", "-table");
        assertThat(result.uiCompositionPlan().path("deviceLayouts").toString())
                .doesNotContain("-list", "-table");
        verifyNoInteractions(resourceSurfaceCatalogRetrievalService);
    }

    @Test
    void previewRejectsRecordOpenWhenNominalIdentityFieldIsNotPublished() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        ObjectNode nominalResponseSchema = comparisonResourceSchema();
        ((ObjectNode) nominalResponseSchema.path("properties")).remove("funcionarioId");
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        comparisonStatsCapabilities(),
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if (context.getPath().endsWith("/stats/comparison")) {
                        return SchemaFetchResult.success(
                                comparisonAnalyticsSchema(),
                                "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(
                            "request".equals(context.getSchemaType())
                                    ? comparisonFilterSchema()
                                    : nominalResponseSchema,
                            "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                resourceSurfaceCatalogRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .containsExactly("governed-analytics-comparison-record-open-source-field-missing");
        assertThat(result.uiCompositionPlan().isEmpty()).isTrue();
        verifyNoInteractions(resourceSurfaceCatalogRetrievalService);
    }

    @Test
    void previewRejectsRecordOpenWhenTargetSurfaceIsUnavailableToThePrincipal() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        comparisonStatsCapabilities(),
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if (context.getPath().endsWith("/stats/comparison")) {
                        return SchemaFetchResult.success(
                                comparisonAnalyticsSchema(),
                                "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(
                            "request".equals(context.getSchemaType())
                                    ? comparisonFilterSchema()
                                    : comparisonResourceSchema(),
                            "http://localhost/schemas/filtered");
                });
        when(resourceSurfaceCatalogRetrievalService.fetchCatalogResult(
                eq("human-resources.funcionarios"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceSurfaceCatalogFetchResult.success(
                        employeeSurfaceCatalog("missing-authority"),
                        "http://localhost/schemas/surfaces?resource=human-resources.funcionarios"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService,
                resourceSurfaceCatalogRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .containsExactly("governed-analytics-comparison-record-open-surface-unavailable-missing-authority");
        assertThat(result.uiCompositionPlan().isEmpty()).isTrue();
    }

    @Test
    void previewRejectsCrossFilterProjectionWithoutCanonicalBucketKeyBinding() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        ObjectNode comparisonSchema = comparisonAnalyticsSchema();
        ((ObjectNode) comparisonSchema.path("x-ui").path("analytics").path("projections").path(0)
                .path("bindings").path("primaryDimension")).remove("keyFilterField");
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenAnswer(invocation -> SchemaFetchResult.success(
                        comparisonSchema,
                        "http://localhost/schemas/filtered"));
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        comparisonStatsCapabilities(),
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .containsExactly("governed-analytics-comparison-key-filter-binding-required");
        assertThat(result.uiCompositionPlan().isEmpty()).isTrue();
    }

    @Test
    void previewRejectsBucketKeyBindingThatCannotRepresentTheCanonicalKey() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Materialize a leitura analitica autorizada para este recurso.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                comparisonDashboardIntent());
        ObjectNode filterSchema = comparisonFilterSchema();
        ((ObjectNode) filterSchema.path("properties").path("departamentoIdsIn").path("items"))
                .put("type", "object");
        when(schemaRetrievalService.fetchSchemaResult(
                any(AiSchemaContext.class),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    return SchemaFetchResult.success(
                            context.getPath().endsWith("/filter") ? filterSchema : comparisonAnalyticsSchema(),
                            "http://localhost/schemas/filtered");
                });
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/vw-analytics-afastamentos"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        comparisonStatsCapabilities(),
                        "http://localhost/api/human-resources/vw-analytics-afastamentos/capabilities"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes())
                .containsExactly("governed-analytics-comparison-key-filter-field-incompatible");
        assertThat(result.uiCompositionPlan().isEmpty()).isTrue();
    }

    @Test
    void previewReusesSchemaFetchesDuringSingleUiCompositionPreview() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel com indicadores e graficos sobre funcionarios.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntentWithoutVisualizationDecision());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        ObjectNode responseProperties = responseSchema.putObject("properties");
        responseProperties.putObject("id").put("type", "integer");
        responseProperties.putObject("departamentoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        responseProperties.putObject("cargoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Cargo");
        responseProperties.putObject("salario").put("type", "number");
        ObjectNode requestSchema = objectMapper.createObjectNode();
        ObjectNode requestProperties = requestSchema.putObject("properties");
        requestProperties.putObject("departamentoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        List<AiSchemaContext> capturedContexts = new ArrayList<>();
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    capturedContexts.add(context);
                    JsonNode schema = "request".equals(context.getSchemaType()) ? requestSchema : responseSchema;
                    return SchemaFetchResult.success(schema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().path("diagnostics").path("resourceSchemaGrounding").path("verified").asBoolean())
                .isTrue();
        assertThat(capturedContexts.stream()
                .filter(context -> "response".equals(context.getSchemaType()))
                .count())
                .isEqualTo(1);
        assertThat(capturedContexts.stream()
                .filter(context -> "request".equals(context.getSchemaType()))
                .count())
                .isLessThanOrEqualTo(1);
    }

    @Test
    void previewGroundsTableColumnAdditionInCanonicalSchema() throws Exception {
        ObjectNode page = objectMapper.createObjectNode();
        page.put("kind", "praxis.ui-composition-plan");
        page.put("version", "1.0");
        page.put("layoutPreset", "single-table-page");
        ObjectNode table = page.putArray("widgets").addObject();
        table.put("key", "funcionarios-table");
        table.put("componentId", "praxis-table");
        table.putObject("inputs").putObject("config").putArray("columns")
                .addObject()
                .put("field", "nomeCompleto")
                .put("header", "Nome Completo")
                .put("type", "string");
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("nomeCompleto")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Nome Completo");
        properties.putObject("email")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Email");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(new AgenticAuthoringPlanRequest(
                        "Adicione a coluna e-mail à tabela de funcionários e mantenha as demais colunas.",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        page,
                        tableColumnAdditionIntent()),
                        "tenant",
                        "user",
                        "local",
                        "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains("ui-composition-plan-provider:generic-table-column-addition");
        JsonNode columns = result.uiCompositionPlan().path("widgets").path(0)
                .path("inputs").path("config").path("columns");
        assertThat(columns).hasSize(2);
        assertThat(columns.path(0).path("field").asText()).isEqualTo("nomeCompleto");
        assertThat(columns.path(1).path("field").asText()).isEqualTo("email");
        assertThat(columns.path(1).path("header").asText()).isEqualTo("Email");
    }

    @Test
    void previewMaterializesDashboardQualityRepairActionsThroughGenericPlanner() throws Exception {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("source", "dashboard-quality-gate");
        contextHints.put("kind", "dashboard-repair-action");
        contextHints.put("artifactKind", "dashboard");
        contextHints.put("resourcePath", "/api/procurement/suppliers");
        contextHints.putArray("warnings")
                .add("dashboard-without-chart-widget")
                .add("dashboard-filter-not-connected")
                .add("dashboard-without-surface-actions");
        contextHints.putArray("schemaFields")
                .add(fieldHint("supplierStatus", "Status", "select"))
                .add(fieldHint("categoryName", "Categoria", "select"))
                .add(fieldHint("createdAt", "Criado em", "date"));
        contextHints.putObject("dashboardQuality")
                .put("schemaVersion", "praxis-dashboard-quality-repair-context.v1")
                .putObject("validation")
                .put("status", "degraded");
        ObjectNode materializedPage = contextHints.putObject("materializedPage");
        materializedPage.put("kind", "praxis.materialized-page");
        materializedPage.put("layoutPreset", "resource-dashboard");
        materializedPage.putArray("widgets")
                .addObject()
                .put("key", "suppliers-filter")
                .put("componentId", "praxis-filter")
                .put("role", "filters");
        materializedPage.putArray("bindings")
                .addObject()
                .put("from", "suppliers-filter.requestSearch")
                .put("to", "suppliers-table.queryContext");

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(new AgenticAuthoringPlanRequest(
                        "Connect widgets",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        dashboardQualityRepairIntent(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        contextHints), "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings())
                .contains("ui-composition-plan-provider:generic-dashboard-quality-repair");
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-chart-required");
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("resource-dashboard");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .contains("praxis-rich-content", "praxis-filter", "praxis-chart", "praxis-list", "praxis-table");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("key"))
                .contains(
                        "suppliers-filter",
                        "suppliers-chart-supplierStatus",
                        "suppliers-chart-categoryName",
                        "suppliers-list",
                        "suppliers-table")
                .doesNotContain("suppliers-chart-createdAt");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .contains("suppliers-filter.requestSearch->suppliers-chart-supplierStatus.queryContext")
                .contains("suppliers-chart-supplierStatus.crossFilter->suppliers-table.queryContext")
                .contains("suppliers-chart-supplierStatus.pointClick->surface.open");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("dashboardQualityRepair")
                .path("requestedWarnings").toString())
                .contains("dashboard-filter-not-connected")
                .contains("dashboard-without-surface-actions");
        JsonNode inputSnapshot = result.uiCompositionPlan().path("diagnostics").path("dashboardQualityRepair")
                .path("inputSnapshot");
        assertThat(inputSnapshot.path("widgetCount").asInt()).isEqualTo(1);
        assertThat(inputSnapshot.path("bindingCount").asInt()).isEqualTo(1);
        assertThat(inputSnapshot.path("widgets").path(0).path("key").asText()).isEqualTo("suppliers-filter");
        assertThat(inputSnapshot.path("widgets").path(0).path("componentId").asText()).isEqualTo("praxis-filter");
        assertThat(result.compiledFormPatch().path("compatibility").path("publicResponseKind").asText())
                .isEqualTo("ui-composition-plan");
    }

    @Test
    void previewEnrichesSchemaFieldsForDashboardQualityRepairEvenWhenPromptIsGeneric() throws Exception {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("source", "dashboard-quality-gate");
        contextHints.put("kind", "dashboard-repair-action");
        contextHints.put("artifactKind", "dashboard");
        contextHints.put("resourcePath", "/api/procurement/suppliers");
        contextHints.putArray("warnings")
                .add("dashboard-without-chart-widget")
                .add("dashboard-filter-not-connected");
        contextHints.putObject("dashboardQuality")
                .put("schemaVersion", "praxis-dashboard-quality-repair-context.v1")
                .putObject("validation")
                .put("status", "degraded");
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("id").put("type", "integer");
        properties.putObject("supplierName").put("type", "string")
                .putObject("x-ui").put("label", "Fornecedor");
        properties.putObject("supplierStatus").put("type", "string")
                .putObject("x-ui").put("label", "Status");
        properties.putObject("categoryName").put("type", "string")
                .putObject("x-ui").put("label", "Categoria");
        properties.putObject("createdAt").put("type", "string").put("format", "date");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(new AgenticAuthoringPlanRequest(
                        "Connect widgets",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        dashboardQualityRepairIntent(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        contextHints), "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("key"))
                .contains(
                        "suppliers-chart-supplierStatus",
                        "suppliers-chart-categoryName",
                        "suppliers-filter",
                        "suppliers-table")
                .doesNotContain("suppliers-chart-createdAt", "suppliers-chart-supplierName");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .contains("suppliers-filter.requestSearch->suppliers-chart-supplierStatus.queryContext")
                .contains("suppliers-chart-categoryName.crossFilter->suppliers-table.queryContext");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("dashboardQualityRepair")
                .path("requestedWarnings").toString())
                .contains("dashboard-filter-not-connected");
    }

    @Test
    void previewPromotesSemanticAxesWhenSchemaContainsTheFields() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("gravidade").put("type", "string");
        properties.putObject("andamento").put("type", "string");
        properties.putObject("responsavel").put("type", "string");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));
        assertThat(request.intentResolution().selectedCandidate()).isNotNull();
        assertThat(request.intentResolution().visualizationDecision()).isNotNull();
        assertThat(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper).plan(request)).isPresent();

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).doesNotContain("semantic-axis-schema-verification-pending");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"schemaVerified\":true")
                .contains("\"schemaProbeStatus\":\"verified\"")
                .contains("\"source\":\"schemas.filtered\"");
        assertThat(result.uiCompositionPlan().path("widgets").toString())
                .contains("\"schemaVerified\":true")
                .contains("\"schemaProbeStatus\":\"verified\"");
    }

    @Test
    void previewUsesCompiledPagePatchAsSemanticMaterializationForChartModification() throws Exception {
        ObjectNode page = objectMapper.createObjectNode();
        ObjectNode widget = page.putArray("widgets").addObject();
        widget.put("key", "incidentes-chart-severidade");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        config.putArray("series").addObject().put("type", "bar");

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)))
                .preview(new AgenticAuthoringPlanRequest(
                        "Altere o gráfico selecionado para linhas",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        page,
                        chartTypeModificationIntent()),
                        "tenant",
                        "user",
                        "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.compiledFormPatch().path("patch").path("page")
                .path("widgets").get(0).path("definition").path("inputs").path("config").path("type").asText())
                .isEqualTo("line");
        assertThat(result.assistantMessage())
                .contains("Atualizei o grafico selecionado para linhas")
                .doesNotContain("painel que voce quer montar");
    }

    @Test
    void previewRemovesGenericGroupByChartsForNonCategoricalFields() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("gravidade").put("type", "string");
        properties.putObject("andamento").put("type", "number");
        properties.putObject("responsavel").put("type", "string");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).contains(
                "semantic-chart-group-by-unsupported-field-type",
                "ui-composition-plan-layout-reflowed-after-widget-prune");
        assertThat(result.uiCompositionPlan().path("widgets").toString())
                .contains("incidentes-chart-gravidade")
                .contains("incidentes-chart-responsavel")
                .doesNotContain("incidentes-chart-andamento");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .doesNotContain("incidentes-chart-andamento");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"andamento\",\"label\":\"Andamento\",\"provenance\":\"llm-authored-semantic-axis\",\"schemaVerified\":false,\"schemaProbeStatus\":\"unsupported\"");
        JsonNode desktopItems = result.uiCompositionPlan().path("canvas").path("items");
        assertThat(desktopItems.path("incidentes-chart-gravidade").path("col").asInt()).isEqualTo(1);
        assertThat(desktopItems.path("incidentes-chart-gravidade").path("colSpan").asInt()).isEqualTo(6);
        assertThat(desktopItems.path("incidentes-chart-responsavel").path("col").asInt()).isEqualTo(7);
        assertThat(desktopItems.path("incidentes-chart-responsavel").path("colSpan").asInt()).isEqualTo(6);
        assertThat(desktopItems.path("incidentes-list").path("row").asInt()).isEqualTo(10);

        JsonNode mobileItems = result.uiCompositionPlan().path("deviceLayouts")
                .path("mobile").path("canvas").path("items");
        assertThat(mobileItems.path("incidentes-chart-gravidade").path("row").asInt()).isEqualTo(9);
        assertThat(mobileItems.path("incidentes-chart-responsavel").path("row").asInt()).isEqualTo(13);
        assertThat(mobileItems.path("incidentes-list").path("row").asInt()).isEqualTo(17);
        assertThat(mobileItems.path("incidentes-table").path("row").asInt()).isEqualTo(23);

        JsonNode tabletItems = result.uiCompositionPlan().path("deviceLayouts")
                .path("tablet").path("canvas").path("items");
        assertThat(tabletItems.path("incidentes-chart-gravidade").path("row").asInt()).isEqualTo(7);
        assertThat(tabletItems.path("incidentes-chart-gravidade").path("col").asInt()).isEqualTo(1);
        assertThat(tabletItems.path("incidentes-chart-responsavel").path("row").asInt()).isEqualTo(7);
        assertThat(tabletItems.path("incidentes-chart-responsavel").path("col").asInt()).isEqualTo(4);
        assertThat(tabletItems.path("incidentes-list").path("row").asInt()).isEqualTo(11);
        assertThat(tabletItems.path("incidentes-table").path("row").asInt()).isEqualTo(18);

        assertThat(result.uiCompositionPlan().path("grouping").toString())
                .contains("incidentes-chart-gravidade")
                .contains("incidentes-chart-responsavel")
                .doesNotContain("incidentes-chart-andamento");
        assertThat(result.uiCompositionPlan().path("slotAssignments").toString())
                .contains("\"incidentes-chart-gravidade\":\"primary-chart\"")
                .contains("\"incidentes-chart-responsavel\":\"secondary-chart-1\"")
                .doesNotContain("incidentes-chart-andamento");
    }

    @Test
    void previewAddsResourceSchemaGroundingForTablePlansWhenSchemaIsAvailable() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma tabela operacional de folhas de pagamento",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTableIntent("Crie uma tabela operacional de folhas de pagamento"));
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("ano").put("type", "integer");
        properties.putObject("mes").put("type", "integer");
        properties.putObject("salarioLiquido").put("type", "number");
        properties.putObject("cpf")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "CPF")
                .put("tableHidden", true);
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().path("diagnostics").path("resourceSchemaGrounding").path("verified").asBoolean())
                .isTrue();
        assertThat(result.uiCompositionPlan().path("diagnostics").path("resourceSchemaGrounding").path("fieldCount").asInt())
                .isEqualTo(4);
        JsonNode columns = result.uiCompositionPlan().path("widgets").path(0).path("inputs").path("config").path("columns");
        assertThat(columns).hasSize(3);
        assertThat(columns.path(0).path("field").asText()).isEqualTo("ano");
        assertThat(columns.path(0).path("type").asText()).isEqualTo("number");
        assertThat(columns.path(2).path("field").asText()).isEqualTo("salarioLiquido");
        assertThat(columns.path(2).path("type").asText()).isEqualTo("number");
        assertThat(columns.toString()).doesNotContain("cpf");
        assertThat(result.warnings()).contains("table-columns-materialized-from-schema");
        assertThat(result.uiCompositionPlan().path("widgets").toString())
                .doesNotContain("schemaVerification")
                .doesNotContain("schemaEvidenceSource")
                .doesNotContain("schemaEvidenceUrl");
    }

    @Test
    void previewReleasesWeakLexicalReviewOnlyAfterFilteredSchemaGrounding() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma tabela operacional de folhas de pagamento",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTableIntent(
                        "Crie uma tabela operacional de folhas de pagamento",
                        List.of("lexical-fallback")));
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("ano").put("type", "integer");
        properties.putObject("mes").put("type", "integer");
        properties.putObject("salarioLiquido").put("type", "number");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(request.intentResolution().semanticDecision().reviewRequired()).isTrue();
        assertThat(request.intentResolution().semanticDecision().reviewReason()).isEqualTo("weak-lexical-evidence");
        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-decision-review-required:weak-lexical-evidence");
        assertThat(result.warnings()).doesNotContain("semantic-decision-review-required");
        assertThat(result.assistantMessage()).contains("Montei uma primeira versao usando");
    }

    @Test
    void previewReleasesPromptAlignmentReviewWhenToolBackedResourceIsSchemaGrounded() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero uma tela longa para acompanhar o time e abrir detalhes depois",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                promptAlignedPayrollTableIntent("Quero uma tela longa para acompanhar o time e abrir detalhes depois"));
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("nomeCompleto").put("type", "string");
        properties.putObject("cargoNome").put("type", "string");
        properties.putObject("departamentoNome").put("type", "string");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(request.intentResolution().semanticDecision().reviewRequired()).isTrue();
        assertThat(request.intentResolution().semanticDecision().reviewReason()).isEqualTo("prompt-alignment-selection");
        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-decision-review-required:prompt-alignment-selection");
        assertThat(result.warnings()).doesNotContain("semantic-decision-review-required");
    }

    @Test
    void previewReleasesKeywordFallbackReviewWhenToolBackedResourceIsSchemaGrounded() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero uma tela longa para acompanhar o time e abrir detalhes depois",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                governedKeywordFallbackPayrollTableIntent("Quero uma tela longa para acompanhar o time e abrir detalhes depois"));
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("nomeCompleto").put("type", "string");
        properties.putObject("cargoNome").put("type", "string");
        properties.putObject("departamentoNome").put("type", "string");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(request.intentResolution().semanticDecision().reviewRequired()).isTrue();
        assertThat(request.intentResolution().semanticDecision().reviewReason()).isEqualTo("keyword-fallback-fail-safe");
        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-decision-review-required:keyword-fallback-fail-safe");
        assertThat(result.warnings()).doesNotContain("semantic-decision-review-required");
    }

    @Test
    void previewGroundsBareGetResourceThroughCanonicalCursorSchema() throws Exception {
        AtomicReference<AiSchemaContext> capturedContext = new AtomicReference<>();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma tabela operacional de folhas de pagamento",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                barePayrollGetTableIntent("Crie uma tabela operacional de folhas de pagamento"));
        ObjectNode schema = objectMapper.createObjectNode();
        schema.putObject("properties").putObject("salarioLiquido").put("type", "number");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    capturedContext.set(invocation.getArgument(0, AiSchemaContext.class));
                    return SchemaFetchResult.success(schema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(capturedContext.get().getPath())
                .isEqualTo("/api/human-resources/folhas-pagamento/filter/cursor");
        assertThat(capturedContext.get().getOperation()).isEqualTo("post");
        assertThat(capturedContext.get().getSchemaType()).isEqualTo("response");
    }

    @Test
    void previewNormalizesSemanticAxesAgainstSchemaAndDropsUnsupportedCharts() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        ObjectNode severidade = properties.putObject("severidade");
        severidade.put("type", "string");
        severidade.put("description", "Classe de gravidade do incidente.");
        severidade.putObject("x-ui").put("label", "Severidade");
        properties.putObject("missao").put("type", "string");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).doesNotContain("semantic-axis-schema-verification-unsupported-axis");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly(
                        "praxis-rich-content",
                        "praxis-rich-content",
                        "praxis-filter",
                        "praxis-chart",
                        "praxis-list",
                        "praxis-table");
        String widgets = result.uiCompositionPlan().path("widgets").toString();
        assertThat(widgets)
                .contains("\"field\":\"severidade\"")
                .contains("\"requestedField\":\"gravidade\"")
                .contains("\"statsPath\":\"/api/operations/incidentes/stats/group-by\"")
                .contains("\"selectedFieldIds\":[\"severidade\"]")
                .doesNotContain("\"field\":\"andamento\"")
                .doesNotContain("\"field\":\"responsavel\"")
                .doesNotContain("\"dimensionField\":\"andamento\"")
                .doesNotContain("\"dimensionField\":\"responsavel\"");
        String bindings = result.uiCompositionPlan().path("bindings").toString();
        assertThat(bindings)
                .contains("incidentes-filter.requestSearch->incidentes-chart-gravidade.queryContext")
                .contains("incidentes-filter.change->incidentes-table.queryContext")
                .contains("incidentes-filter.change->incidentes-list.queryContext")
                .contains("\"template\":{\"filters\":\"${payload}\"}")
                .contains("incidentes-chart-gravidade.pointClick->surface.open")
                .contains("incidentes-chart-gravidade.crossFilter->incidentes-list.queryContext")
                .contains("incidentes-chart-gravidade.crossFilter->incidentes-table.queryContext")
                .doesNotContain("incidentes-chart-andamento")
                .doesNotContain("incidentes-chart-responsavel")
                .doesNotContain("payload.filters.andamento")
                .doesNotContain("payload.filters.responsavel");
        assertThat(result.warnings()).contains("ui-composition-plan-filter-query-context-normalized");
        assertThat(result.warnings()).contains("ui-composition-plan-orphan-binding-removed");
        String canvasItems = result.uiCompositionPlan().path("canvas").path("items").toString();
        assertThat(canvasItems)
                .contains("incidentes-chart-gravidade")
                .doesNotContain("incidentes-chart-andamento")
                .doesNotContain("incidentes-chart-responsavel")
                .doesNotContain("andamento")
                .doesNotContain("responsavel");
        assertThat(result.warnings()).contains("ui-composition-plan-orphan-canvas-item-removed");
        String grouping = result.uiCompositionPlan().path("grouping").toString();
        assertThat(grouping)
                .contains("incidentes-chart-gravidade")
                .doesNotContain("incidentes-chart-andamento")
                .doesNotContain("incidentes-chart-responsavel");
        String deviceLayouts = result.uiCompositionPlan().path("deviceLayouts").toString();
        assertThat(deviceLayouts)
                .contains("incidentes-chart-gravidade")
                .doesNotContain("incidentes-chart-andamento")
                .doesNotContain("incidentes-chart-responsavel");
        String slotAssignments = result.uiCompositionPlan().path("slotAssignments").toString();
        assertThat(slotAssignments)
                .contains("incidentes-chart-gravidade")
                .doesNotContain("incidentes-chart-andamento")
                .doesNotContain("incidentes-chart-responsavel");
        assertThat(result.warnings()).contains("ui-composition-plan-orphan-grouping-item-removed");
        assertThat(result.warnings()).contains("ui-composition-plan-orphan-device-layout-item-removed");
        assertThat(result.warnings()).contains("ui-composition-plan-orphan-slot-assignment-removed");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"severidade\"")
                .contains("\"schemaProbeStatus\":\"verified\"");
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.assistantMessage())
                .contains("Montei uma primeira versao")
                .doesNotContain("propriedades incompativeis");
    }

    @Test
    void previewUsesSelectableFilterFieldWhenDisplayFieldHasMultiSelectDtoPartner() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel geral de funcionarios por cargo.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                funcionariosCargoDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        ObjectNode cargoNome = properties.putObject("cargoNome");
        cargoNome.put("type", "string");
        cargoNome.put("description", "Nome do cargo para recortes analiticos conectados.");
        cargoNome.putObject("x-ui")
                .put("label", "Cargo")
                .put("controlType", "input")
                .put("name", "cargoNome");
        ObjectNode cargoIdsIn = properties.putObject("cargoIdsIn");
        cargoIdsIn.put("type", "array");
        cargoIdsIn.put("description", "Conjunto de cargos aceitos para a busca.");
        cargoIdsIn.putObject("items").put("type", "integer");
        cargoIdsIn.putObject("x-ui")
                .put("label", "Cargos")
                .put("controlType", "async-select")
                .put("multiple", true)
                .put("endpoint", "/api/human-resources/cargos/options/filter")
                .put("name", "cargoIdsIn");
        properties.putObject("ativo")
                .put("type", "boolean")
                .putObject("x-ui")
                .put("label", "Status")
                .put("controlType", "checkbox");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        String widgets = result.uiCompositionPlan().path("widgets").toString();
        assertThat(widgets)
                .contains("\"selectedFieldIds\":[\"cargoIdsIn\"]")
                .doesNotContain("\"selectedFieldIds\":[\"cargoNome\"]");
        assertThat(result.warnings()).contains("semantic-filter-schema-field-replaced-with-selectable-field");
    }

    @Test
    void previewDeduplicatesFilterFieldsAfterSelectableDtoReplacement() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel geral de funcionarios por cargo e descricao do cargo.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                funcionariosDuplicateCargoDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("cargoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Cargo")
                .put("controlType", "input")
                .put("name", "cargoNome");
        properties.putObject("cargoDescricao")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Descricao do cargo")
                .put("controlType", "input")
                .put("name", "cargoDescricao");
        ObjectNode cargoIdsIn = properties.putObject("cargoIdsIn");
        cargoIdsIn.put("type", "array");
        cargoIdsIn.put("description", "Conjunto de cargos aceitos para a busca por nome e descricao.");
        cargoIdsIn.putObject("items").put("type", "integer");
        cargoIdsIn.putObject("x-ui")
                .put("label", "Cargos")
                .put("controlType", "async-select")
                .put("multiple", true)
                .put("endpoint", "/api/human-resources/cargos/options/filter")
                .put("name", "cargoIdsIn");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        JsonNode selectedFields = result.uiCompositionPlan().path("widgets")
                .findValues("inputs")
                .stream()
                .filter(inputs -> inputs.path("filterId").asText("").endsWith("-filter"))
                .findFirst()
                .orElseThrow()
                .path("selectedFieldIds");
        assertThat(selectedFields.toString()).isEqualTo("[\"cargoIdsIn\"]");
        assertThat(result.warnings()).contains(
                "semantic-filter-schema-field-replaced-with-selectable-field",
                "semantic-filter-schema-field-deduplicated");
    }

    @Test
    void previewRepairsUnsupportedChartAxesWhenUserAsksForSchemaSafeAxes() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "ajuste os graficos usando apenas eixos seguros confirmados pelo schema",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        ObjectNode severidade = properties.putObject("severidade");
        severidade.put("type", "string");
        severidade.put("description", "Classe de gravidade do incidente.");
        severidade.putObject("x-ui").put("label", "Severidade");
        properties.putObject("missao").put("type", "string").putObject("x-ui").put("label", "Missao");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.warnings())
                .contains("semantic-chart-axis-repaired-with-schema-field")
                .contains("semantic-chart-axis-dropped-without-safe-schema-field");
        String widgets = result.uiCompositionPlan().path("widgets").toString();
        assertThat(widgets)
                .contains("\"field\":\"severidade\"")
                .contains("\"field\":\"missao\"")
                .doesNotContain("\"field\":\"andamento\"");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"missao\"")
                .contains("\"materialized\":false")
                .contains("\"materializationReason\":\"schema-safe-axis-repair\"");
    }

    @Test
    void previewDoesNotKeepDroppedUnresolvedAxisPendingAfterChartRemoval() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "quero visualizar contratos de fornecedores",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                contractsDashboardIntentWithUnresolvedAxis());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("codigoInterno")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Codigo Interno");
        properties.putObject("descricao")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Descricao");
        properties.putObject("valorTotal")
                .put("type", "number")
                .putObject("x-ui")
                .put("label", "Valor Total");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(ignored -> java.util.Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-unresolved-axis"),
                        unresolvedAxisDashboardPlan(),
                        objectMapper.createObjectNode()))),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.warnings())
                .doesNotContain("semantic-axis-schema-verification-pending")
                .doesNotContain("semantic-preview-materialization-mismatch");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .doesNotContain("\"componentId\":\"praxis-chart\"")
                .contains("\"componentId\":\"praxis-list\"")
                .contains("\"componentId\":\"praxis-table\"");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"unresolved\"")
                .contains("\"schemaProbeStatus\":\"unsupported\"")
                .contains("\"materialized\":false")
                .contains("\"materializationReason\":\"unsupported-semantic-axis\"");
    }

    @Test
    void previewDropsOrphanDiagnosticsAxisWhenInferredChartIsRemoved() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "quero acompanhar contratos dos fornecedores",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                contractsDashboardIntentWithUnresolvedAxis());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("codigoInterno").put("type", "string");
        properties.putObject("descricao").put("type", "string");
        properties.putObject("valorTotal").put("type", "number");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(ignored -> java.util.Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test-orphan-axis"),
                        unresolvedAxisDashboardPlanWithOrphanDiagnosticsAxis(),
                        objectMapper.createObjectNode()))),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.warnings())
                .doesNotContain("semantic-axis-schema-verification-pending")
                .doesNotContain("semantic-preview-materialization-mismatch");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .contains("praxis-list", "praxis-table")
                .doesNotContain("praxis-chart");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"contratoStatus\"")
                .contains("\"materialized\":false")
                .contains("\"materializationReason\":\"chart-axis-not-materialized\"");
    }

    @Test
    void previewPreservesSingleChartConstraintWithoutAddingDashboardSupportWidgets() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de barras simples de incidentes por severidade. "
                        + "Use a fonte Indicadores Incidentes e o campo Severidade. "
                        + "Nao crie tabela, filtros nem KPIs.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                incidentSingleChartIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode severidade = schema.putObject("properties").putObject("severidade");
        severidade.put("type", "string");
        severidade.putObject("x-ui").put("label", "Severidade");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-chart-required");
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("single-chart-page");
        assertThat(result.uiCompositionPlan().path("widgets")).hasSize(1);
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-chart");
        assertThat(result.uiCompositionPlan().toString())
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/group-by\"")
                .doesNotContain("praxis-table")
                .doesNotContain("praxis-filter")
                .doesNotContain("kpi-band");
        assertThat(result.assistantMessage())
                .contains("Montei um grafico")
                .contains("Nao inclui tabela, filtros nem KPIs")
                .doesNotContain("dashboard")
                .doesNotContain("tabela de detalhe");
    }

    @Test
    void previewPreservesSelectedResourceForChartWhenCandidateIsTransactional() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de barras simples de incidentes por severidade. "
                        + "Nao crie tabela, filtros nem KPIs.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                incidentSingleChartIntentWithTransactionalSelectedCandidate());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode severidade = schema.putObject("properties").putObject("severidade");
        severidade.put("type", "string");
        severidade.putObject("x-ui").put("label", "Severidade");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("single-chart-page");
        assertThat(result.uiCompositionPlan().path("widgets")).hasSize(1);
        assertThat(result.uiCompositionPlan().toString())
                .contains("\"statsPath\":\"/api/operations/incidentes/stats/group-by\"")
                .doesNotContain("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/group-by\"")
                .doesNotContain("praxis-table")
                .doesNotContain("praxis-filter")
                .doesNotContain("kpi-band");
    }

    @Test
    void previewReplacesAggregateCountAxisWithSchemaBackedGroupingDimension() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Gostei, mas prefiro graficos mantendo os mesmos dados da folha.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollCountAxisDashboardIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("ano").put("type", "integer").putObject("x-ui").put("label", "Ano");
        properties.putObject("salarioBruto").put("type", "number").putObject("x-ui").put("label", "Salario Bruto");
        properties.putObject("funcionario").put("type", "string").putObject("x-ui").put("label", "Funcionario");
        properties.putObject("mes").put("type", "integer").putObject("x-ui").put("label", "Mes");
        properties.putObject("dataPagamento").put("type", "string").putObject("x-ui").put("label", "Data de Pagamento");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.warnings()).doesNotContain("semantic-axis-schema-verification-unsupported-axis");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"requestedField\":\"quantidadeRegistros\"")
                .contains("\"field\":\"mes\"")
                .contains("\"schemaVerified\":true")
                .contains("\"schemaProbeStatus\":\"verified\"")
                .contains("\"selectedFieldIds\":[\"mes\"]")
                .contains("\"dimensionField\":\"mes\"")
                .contains("\"statsPath\":\"/api/human-resources/folhas-pagamento/stats/group-by\"");
    }

    @Test
    void previewCanonicalizesChartMetricFieldAgainstFilteredSchema() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de barras horizontais de folha por departamento somando Salario Liquido.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollAnalyticsDashboardIntentWithMetric("salario_liquido"));
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("departamento")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        properties.putObject("salarioLiquido")
                .put("type", "number")
                .putObject("x-ui")
                .put("label", "Salário Líquido");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.warnings())
                .doesNotContain("semantic-chart-metric-schema-verification-unsupported-field")
                .contains("semantic-chart-count-metric-preserved-for-record-count");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"field\":\"salarioLiquido\"")
                .contains("\"alias\":\"salarioLiquido\"")
                .contains("\"aggregation\":\"sum\"")
                .contains("\"operation\":\"SUM\"")
                .contains("\"schemaProbeStatus\":\"verified\"");
        assertThat(plan).doesNotContain("\"field\":\"salario_liquido\"");
    }

    @Test
    void previewRepairsPromptAlignedChartAxisBeforeGenericSchemaFallback() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico horizontal de folha de pagamento por departamento somando salario liquido. Use Analytics Folha Pagamento.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollAnalyticsDashboardIntentWithUnresolvedAxis());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("ano").put("type", "integer").putObject("x-ui").put("label", "Ano");
        properties.putObject("departamento")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        properties.putObject("salarioLiquido")
                .put("type", "number")
                .putObject("x-ui")
                .put("label", "Salário Líquido");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings())
                .contains("semantic-chart-axis-repaired-with-prompt-aligned-schema-field")
                .contains("semantic-chart-metric-inferred-from-schema-context");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"requestedField\":\"unresolved\"")
                .contains("\"field\":\"departamento\"")
                .contains("\"title\":\"Registros por Departamento")
                .contains("\"statsPath\":\"/api/human-resources/vw-analytics-folha-pagamento/stats/group-by\"")
                .contains("\"field\":\"salarioLiquido\"")
                .contains("\"operation\":\"SUM\"")
                .contains("\"schemaProbeStatus\":\"verified\"");
        assertThat(plan).doesNotContain("\"dimensionField\":\"ano\"");
    }

    @Test
    void previewRepairsStatusLikeChartAxisToSingleBooleanSchemaField() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de pizza de funcionarios por status. Use a fonte Funcionarios. Nao crie tabela, filtros nem KPIs.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                employeeStatusPieChartIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("nomeCompleto")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Nome Completo");
        properties.putObject("ativo")
                .put("type", "boolean")
                .put("description", "Indica se o colaborador esta ativo no cadastro; inativos podem ser retidos por auditoria.")
                .putObject("x-ui")
                .put("label", "Ativo");
        properties.putObject("estadoCivil")
                .put("type", "string")
                .putArray("enum")
                .add("SOLTEIRO")
                .add("CASADO");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-chart-required");
        assertThat(result.warnings())
                .contains("semantic-chart-axis-repaired-with-prompt-aligned-schema-field")
                .doesNotContain("semantic-axis-schema-verification-unsupported-axis")
                .doesNotContain("semantic-preview-materialization-mismatch");
        String plan = result.uiCompositionPlan().toString();
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly("praxis-chart");
        assertThat(plan)
                .contains("\"type\":\"pie\"")
                .contains("\"requestedField\":\"status\"")
                .contains("\"field\":\"ativo\"")
                .contains("\"label\":\"Ativo\"")
                .contains("\"statsPath\":\"/api/human-resources/funcionarios/stats/group-by\"")
                .contains("\"schemaProbeStatus\":\"verified\"")
                .doesNotContain("praxis-table")
                .doesNotContain("praxis-filter")
                .doesNotContain("kpi-band");
    }

    @Test
    void previewAlignsDashboardFiltersWithRequestDtoSelectableFields() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = funcionariosCargoDashboardIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "quero um painel geral dos funcionarios",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);
        ObjectNode responseSchema = objectMapper.createObjectNode();
        ObjectNode responseProperties = responseSchema.putObject("properties");
        responseProperties.putObject("cargoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Cargo");
        responseProperties.putObject("nomeCompleto")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Nome completo");
        ObjectNode requestSchema = objectMapper.createObjectNode();
        ObjectNode requestProperties = requestSchema.putObject("properties");
        requestProperties.putObject("cargoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Cargo");
        ObjectNode cargoIdsIn = requestProperties.putObject("cargoIdsIn");
        cargoIdsIn.put("type", "array");
        ObjectNode cargoUi = cargoIdsIn.putObject("x-ui");
        cargoUi.put("label", "Cargo");
        cargoUi.put("controlType", "async-select");
        cargoUi.put("multiple", true);
        cargoUi.put("endpoint", "/api/human-resources/cargos/options/filter");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    JsonNode schema = "request".equals(context.getSchemaType()) ? requestSchema : responseSchema;
                    return SchemaFetchResult.success(schema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        JsonNode filterInputs = result.uiCompositionPlan()
                .path("widgets")
                .findValues("inputs")
                .stream()
                .filter(inputs -> inputs.path("filterId").asText("").endsWith("-filter"))
                .findFirst()
                .orElseThrow();
        assertThat(result.valid()).isTrue();
        assertThat(filterInputs.path("selectedFieldIds").toString())
                .contains("cargoIdsIn")
                .doesNotContain("cargoNome");
        assertThat(result.warnings())
                .contains("semantic-filter-schema-field-replaced-with-selectable-field");
        assertThat(result.uiCompositionPlan().toString()).contains("kpi-band");
    }

    @Test
    void previewBuildsGenericDashboardForOrdersWithoutFuncionarioAssumptions() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = ordersDashboardIntent();
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "quero um painel geral de pedidos",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);
        ObjectNode responseSchema = objectMapper.createObjectNode();
        ObjectNode responseProperties = responseSchema.putObject("properties");
        responseProperties.putObject("numeroPedido")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Pedido");
        responseProperties.putObject("clienteNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Cliente");
        responseProperties.putObject("statusNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Status");
        responseProperties.putObject("canalNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Canal");
        responseProperties.putObject("criadoEm")
                .put("type", "string")
                .put("format", "date-time")
                .putObject("x-ui")
                .put("label", "Criado em");
        ObjectNode requestSchema = objectMapper.createObjectNode();
        ObjectNode requestProperties = requestSchema.putObject("properties");
        requestProperties.putObject("statusNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Status");
        ObjectNode statusIdsIn = requestProperties.putObject("statusIdsIn");
        statusIdsIn.put("type", "array");
        ObjectNode statusUi = statusIdsIn.putObject("x-ui");
        statusUi.put("label", "Status");
        statusUi.put("controlType", "async-select");
        statusUi.put("multiple", true);
        statusUi.put("endpoint", "/api/sales/status/options/filter");
        ObjectNode canalIdsIn = requestProperties.putObject("canalIdsIn");
        canalIdsIn.put("type", "array");
        ObjectNode canalUi = canalIdsIn.putObject("x-ui");
        canalUi.put("label", "Canal");
        canalUi.put("controlType", "async-select");
        canalUi.put("multiple", true);
        canalUi.put("endpoint", "/api/sales/canais/options/filter");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    JsonNode schema = "request".equals(context.getSchemaType()) ? requestSchema : responseSchema;
                    return SchemaFetchResult.success(schema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        JsonNode widgets = result.uiCompositionPlan().path("widgets");
        JsonNode filterInputs = widgets
                .findValues("inputs")
                .stream()
                .filter(inputs -> inputs.path("filterId").asText("").endsWith("-filter"))
                .findFirst()
                .orElseThrow();
        String plan = result.uiCompositionPlan().toString();
        assertThat(result.valid()).isTrue();
        assertThat(widgets.findValuesAsText("componentId"))
                .contains("praxis-rich-content", "praxis-filter", "praxis-chart", "praxis-list", "praxis-table");
        assertThat(filterInputs.path("selectedFieldIds").toString())
                .contains("statusIdsIn", "canalIdsIn")
                .doesNotContain("statusNome", "canalNome");
        assertThat(plan)
                .contains("pedidos-chart-statusNome")
                .contains("pedidos-chart-canalNome")
                .contains("Destaques de Pedidos")
                .contains("surface.open")
                .doesNotContain("funcionarios")
                .doesNotContain("cargoIdsIn")
                .doesNotContain("departamentoIdsIn");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("dashboardBlueprint").path("domainSpecific").asBoolean())
                .isFalse();
        assertThat(result.warnings())
                .contains("semantic-filter-schema-field-replaced-with-selectable-field");
    }

    @Test
    void previewRepairsUnresolvedTimeseriesAxisWithSchemaTemporalField() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de linha da evolucao mensal de incidentes por data de ocorrido.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                unresolvedIncidentTimeseriesChartIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("severidade")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Severidade");
        properties.putObject("ocorridoEm")
                .put("type", "string")
                .put("format", "date-time")
                .put("description", "Marco temporal do fato.")
                .putObject("x-ui")
                .put("label", "Ocorrido em");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-axis-schema-verification-required");
        assertThat(result.warnings())
                .contains("semantic-chart-timeseries-axis-repaired-with-governed-temporal-field")
                .doesNotContain("semantic-axis-schema-verification-unsupported-axis");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"requestedField\":\"unresolved\"")
                .contains("\"field\":\"ocorridoEm\"")
                .contains("\"type\":\"time\"")
                .contains("\"statsOperation\":\"timeseries\"")
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries\"")
                .contains("\"granularity\":\"MONTH\"")
                .contains("\"schemaProbeStatus\":\"verified\"");
        assertThat(plan).doesNotContain("\"field\":\"unresolved\"");
    }

    @Test
    void previewGroundsTimeseriesInteractionsAsGuardedTemporalRangeFilters() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard da evolucao mensal da folha por competencia com detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTimeseriesDashboardIntent());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        ObjectNode responseProperties = responseSchema.putObject("properties");
        responseProperties.putObject("competencia")
                .put("type", "string")
                .put("format", "date")
                .put("description", "Competencia temporal da folha.")
                .putObject("x-ui")
                .put("label", "Competencia");
        responseProperties.putObject("salarioLiquido")
                .put("type", "number")
                .put("format", "decimal")
                .putObject("x-ui")
                .put("label", "Salario liquido");
        ObjectNode filterSchema = objectMapper.createObjectNode();
        ObjectNode competenceRange = filterSchema.putObject("properties")
                .putObject("competenciaBetween");
        competenceRange.put("type", "array");
        competenceRange.put("description", "Intervalo inclusivo da competencia selecionada.");
        competenceRange.putObject("items")
                .put("type", "string")
                .put("format", "date");
        competenceRange.putObject("x-ui")
                .put("label", "Competencia")
                .put("controlType", "dateRange");
        ((ObjectNode) filterSchema.path("properties"))
                .putObject("status")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Status");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if ("request".equals(context.getSchemaType())
                            && context.getPath().endsWith("/filter")) {
                        return SchemaFetchResult.success(filterSchema, "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringGenericUiCompositionPlanProvider genericProvider =
                new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper);
        AgenticAuthoringUiCompositionPlanProvider weakTemporalGuardProvider = authoredRequest -> {
            AgenticAuthoringUiCompositionPlanResult authored = genericProvider.plan(authoredRequest).orElseThrow();
            JsonNode authoredChart = authored.uiCompositionPlan().path("widgets").findParents("key").stream()
                    .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                            .equals(widget.path("key").asText()))
                    .findFirst()
                    .orElseThrow();
            ((ObjectNode) authoredChart.path("inputs").path("config").path("interactions")
                    .path("eventActions").path("crossFilter").path("mapping"))
                    .put("status", "status");
            ObjectNode pointBinding = (ObjectNode) findBinding(
                    authored.uiCompositionPlan().path("bindings"),
                    "vw-analytics-folha-pagamento-chart-competencia.pointClick->vw-analytics-folha-pagamento-table.queryContext");
            ArrayNode alternatives = pointBinding.putObject("condition").putArray("or");
            alternatives.addObject().putObject("!!").put("var", "payload.data.start");
            alternatives.addObject().putObject("!!").put("var", "payload.data.end");
            ObjectNode crossFilterBinding = (ObjectNode) findBinding(
                    authored.uiCompositionPlan().path("bindings"),
                    "vw-analytics-folha-pagamento-chart-competencia.crossFilter->vw-analytics-folha-pagamento-table.queryContext");
            crossFilterBinding.put("condition", false);
            return java.util.Optional.of(authored);
        };
        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(weakTemporalGuardProvider),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        JsonNode plan = result.uiCompositionPlan();
        JsonNode chart = plan.path("widgets").findParents("key").stream()
                .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                        .equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(chart.path("inputs").path("config").path("dataSource").path("query")
                .path("statsOperation").asText()).isEqualTo("timeseries");
        JsonNode crossFilterMapping = chart.path("inputs").path("config").path("interactions")
                .path("eventActions").path("crossFilter").path("mapping");
        assertThat(crossFilterMapping.path("start").asText()).isEqualTo("competenciaBetween");
        assertThat(crossFilterMapping.path("status").asText()).isEqualTo("status");

        JsonNode pointLink = findBinding(
                plan.path("bindings"),
                "vw-analytics-folha-pagamento-chart-competencia.pointClick->vw-analytics-folha-pagamento-table.queryContext");
        assertThat(pointLink.path("policy").path("distinctBy").asText())
                .isEqualTo("payload.data.start");
        assertThat(pointLink.path("condition").path("and")).hasSize(3);
        assertThat(pointLink.path("condition").path("and").path(0).path("or")).hasSize(2);
        assertThat(pointLink.path("condition").toString())
                .contains("payload.data.start")
                .contains("payload.data.end");
        assertThat(pointLink.path("transform").path("template").path("filters")
                .path("competenciaBetween"))
                .extracting(JsonNode::asText)
                .containsExactly("${payload.data.start}", "${payload.data.end}");

        JsonNode crossFilterLink = findBinding(
                plan.path("bindings"),
                "vw-analytics-folha-pagamento-chart-competencia.crossFilter->vw-analytics-folha-pagamento-table.queryContext");
        assertThat(crossFilterLink.path("policy").path("distinctBy").asText())
                .isEqualTo("payload.source.data.start");
        assertThat(crossFilterLink.path("condition").path("and")).hasSize(3);
        assertThat(crossFilterLink.path("condition").path("and").path(0).asBoolean()).isFalse();
        assertThat(crossFilterLink.path("condition").toString())
                .contains("payload.source.data.start")
                .contains("payload.source.data.end");
        assertThat(crossFilterLink.path("transform").path("template").path("filters")
                .path("competenciaBetween"))
                .extracting(JsonNode::asText)
                .containsExactly("${payload.source.data.start}", "${payload.source.data.end}");
        assertThat(crossFilterLink.path("transform").path("template").path("filters")
                .path("status").asText()).isEqualTo("${payload.filters.status}");

        JsonNode surfaceLink = findBinding(
                plan.path("bindings"),
                "vw-analytics-folha-pagamento-chart-competencia.pointClick->surface.open");
        JsonNode surfaceBinding = surfaceLink.path("to").path("payload").path("bindings").path(0);
        assertThat(surfaceBinding.path("to").asText())
                .endsWith("queryContext.filters.competenciaBetween");
        assertThat(surfaceBinding.path("mode").asText()).isEqualTo("template");
        assertThat(surfaceBinding.path("value"))
                .extracting(JsonNode::asText)
                .containsExactly("${payload.data.start}", "${payload.data.end}");
        assertThat(surfaceBinding.has("from")).isFalse();
        assertThat(result.warnings()).contains("semantic-chart-interactions-grounded");

        AgenticAuthoringUiCompositionPlanProvider replayProvider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of(),
                        result.uiCompositionPlan().deepCopy(),
                        result.compiledFormPatch().deepCopy()));
        AgenticAuthoringPreviewResult replayed = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(replayProvider),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");
        assertThat(findBinding(replayed.uiCompositionPlan().path("bindings"), pointLink.path("id").asText()))
                .isEqualTo(pointLink);
        assertThat(findBinding(replayed.uiCompositionPlan().path("bindings"), crossFilterLink.path("id").asText()))
                .isEqualTo(crossFilterLink);
        assertThat(findBinding(replayed.uiCompositionPlan().path("bindings"), surfaceLink.path("id").asText()))
                .isEqualTo(surfaceLink);
    }

    @Test
    void previewKeepsDateRangeTargetsNonTemporalForCategoricalCharts() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Quero um painel de funcionarios por departamento, com grafico e detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                operationalMonitoringDashboardIntentWithoutVisualizationDecision());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        responseSchema.putObject("properties")
                .putObject("departamentoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        ObjectNode filterSchema = objectMapper.createObjectNode();
        ObjectNode departmentRange = filterSchema.putObject("properties")
                .putObject("departamentoBetween");
        departmentRange.put("type", "array");
        departmentRange.putObject("items")
                .put("type", "string")
                .put("format", "date");
        departmentRange.putObject("x-ui")
                .put("label", "Departamento")
                .put("controlType", "date-range");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    return SchemaFetchResult.success(
                            "request".equals(context.getSchemaType()) ? filterSchema : responseSchema,
                            "http://localhost/schemas/filtered");
                });
        when(resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                eq("/api/human-resources/funcionarios"),
                eq("http://localhost"),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        employeeStatsCapabilities(),
                        "http://localhost/api/human-resources/funcionarios/capabilities"));

        AgenticAuthoringGenericUiCompositionPlanProvider genericProvider =
                new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper);
        AgenticAuthoringUiCompositionPlanProvider categoricalDateRangeProvider = authoredRequest -> {
            AgenticAuthoringUiCompositionPlanResult authored = genericProvider.plan(authoredRequest).orElseThrow();
            JsonNode authoredChart = authored.uiCompositionPlan().path("widgets").findParents("key").stream()
                    .filter(widget -> "funcionarios-chart-departamentoNome"
                            .equals(widget.path("key").asText()))
                    .findFirst()
                    .orElseThrow();
            ObjectNode mapping = (ObjectNode) authoredChart.path("inputs").path("config")
                    .path("interactions").path("eventActions").path("crossFilter").path("mapping");
            mapping.removeAll();
            mapping.put("departamentoNome", "departamentoBetween");
            return java.util.Optional.of(authored);
        };
        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(categoricalDateRangeProvider),
                null,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        JsonNode plan = result.uiCompositionPlan();
        JsonNode chart = plan.path("widgets").findParents("key").stream()
                .filter(widget -> "funcionarios-chart-departamentoNome".equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(chart.path("inputs").path("config").path("dataSource").path("query")
                .path("statsOperation").asText()).isEqualTo("group-by");
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("eventActions").path("crossFilter").path("mapping").toString())
                .isEqualTo("{\"departamentoNome\":\"departamentoBetween\"}");
        JsonNode pointLink = findBinding(
                plan.path("bindings"),
                "funcionarios-chart-departamentoNome.pointClick->funcionarios-table.queryContext");
        assertThat(pointLink.path("transform").path("template").path("filters")
                .has("departamentoBetween")).isFalse();
        assertThat(pointLink.path("condition").toString())
                .doesNotContain("payload.data.start", "payload.data.end");
    }

    @Test
    void previewDisablesTimeseriesFilteringWhenTheExplicitTargetIsScalar() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard da evolucao mensal da folha por competencia com detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTimeseriesDashboardIntent());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        responseSchema.putObject("properties")
                .putObject("competencia")
                .put("type", "string")
                .put("format", "date")
                .putObject("x-ui")
                .put("label", "Competencia");
        ObjectNode filterSchema = responseSchema.deepCopy();
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if ("request".equals(context.getSchemaType())
                            && context.getPath().endsWith("/filter")) {
                        return SchemaFetchResult.success(filterSchema, "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered");
                });
        AgenticAuthoringGenericUiCompositionPlanProvider genericProvider =
                new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper);
        AgenticAuthoringUiCompositionPlanProvider scalarTargetProvider = authoredRequest -> {
            AgenticAuthoringUiCompositionPlanResult authored = genericProvider.plan(authoredRequest).orElseThrow();
            JsonNode authoredChart = authored.uiCompositionPlan().path("widgets").findParents("key").stream()
                    .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                            .equals(widget.path("key").asText()))
                    .findFirst()
                    .orElseThrow();
            ObjectNode mapping = (ObjectNode) authoredChart.path("inputs").path("config")
                    .path("interactions").path("eventActions").path("crossFilter").path("mapping");
            mapping.removeAll();
            mapping.put("start", "competencia");
            return java.util.Optional.of(authored);
        };

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(scalarTargetProvider),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        JsonNode plan = result.uiCompositionPlan();
        JsonNode chart = plan.path("widgets").findParents("key").stream()
                .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                        .equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("crossFilter").asBoolean()).isFalse();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("eventActions").has("crossFilter")).isFalse();
        assertThat(plan.path("bindings").findValuesAsText("id"))
                .doesNotContain(
                        "vw-analytics-folha-pagamento-chart-competencia.pointClick->surface.open",
                        "vw-analytics-folha-pagamento-chart-competencia.pointClick->vw-analytics-folha-pagamento-table.queryContext",
                        "vw-analytics-folha-pagamento-chart-competencia.crossFilter->vw-analytics-folha-pagamento-table.queryContext");
        assertThat(result.warnings()).contains("semantic-chart-temporal-range-filter-target-unresolved");
    }

    @Test
    void previewDisablesTemporalFilteringWhenExplicitRangeTargetsConflict() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard da evolucao mensal da folha por competencia com detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTimeseriesDashboardIntent());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        responseSchema.putObject("properties")
                .putObject("competencia")
                .put("type", "string")
                .put("format", "date")
                .putObject("x-ui")
                .put("label", "Competencia");
        ObjectNode filterSchema = objectMapper.createObjectNode();
        ObjectNode filterProperties = filterSchema.putObject("properties");
        ObjectNode firstRange = filterProperties.putObject("competenciaBetween");
        firstRange.put("type", "array");
        firstRange.putObject("items").put("type", "string").put("format", "date");
        firstRange.putObject("x-ui").put("label", "Competencia").put("controlType", "dateRange");
        ObjectNode secondRange = filterProperties.putObject("competenciaBetweenInclusive");
        secondRange.put("type", "array");
        secondRange.putObject("items").put("type", "string").put("format", "date");
        secondRange.putObject("x-ui").put("label", "Competencia").put("controlType", "dateRange");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if ("request".equals(context.getSchemaType())
                            && context.getPath().endsWith("/filter")) {
                        return SchemaFetchResult.success(filterSchema, "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringGenericUiCompositionPlanProvider genericProvider =
                new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper);
        AgenticAuthoringUiCompositionPlanProvider conflictingTargetProvider = authoredRequest -> {
            AgenticAuthoringUiCompositionPlanResult authored = genericProvider.plan(authoredRequest).orElseThrow();
            ObjectNode plan = (ObjectNode) authored.uiCompositionPlan();
            JsonNode authoredChart = plan.path("widgets").findParents("key").stream()
                    .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                            .equals(widget.path("key").asText()))
                    .findFirst()
                    .orElseThrow();
            ObjectNode mapping = (ObjectNode) authoredChart.path("inputs").path("config")
                    .path("interactions").path("eventActions").path("crossFilter").path("mapping");
            mapping.removeAll();
            mapping.put("start", "competenciaBetween");
            mapping.put("end", "competenciaBetweenInclusive");
            ObjectNode selectionLink = plan.withArray("bindings").addObject();
            selectionLink.put("id", "vw-analytics-folha-pagamento-chart-competencia.selectionChange->vw-analytics-folha-pagamento-table.queryContext");
            selectionLink.put("intent", "data-projection");
            selectionLink.putObject("from")
                    .put("kind", "component-port")
                    .put("widget", "vw-analytics-folha-pagamento-chart-competencia")
                    .put("port", "selectionChange")
                    .put("direction", "output");
            selectionLink.putObject("to")
                    .put("kind", "component-port")
                    .put("widget", "vw-analytics-folha-pagamento-table")
                    .put("port", "queryContext")
                    .put("direction", "input");
            return java.util.Optional.of(authored);
        };
        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(conflictingTargetProvider),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        JsonNode plan = result.uiCompositionPlan();
        JsonNode chart = plan.path("widgets").findParents("key").stream()
                .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                        .equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("crossFilter").asBoolean()).isFalse();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("eventActions").has("crossFilter")).isFalse();
        assertThat(plan.path("bindings").findValuesAsText("id"))
                .doesNotContain(
                        "vw-analytics-folha-pagamento-chart-competencia.pointClick->surface.open",
                        "vw-analytics-folha-pagamento-chart-competencia.pointClick->vw-analytics-folha-pagamento-table.queryContext",
                        "vw-analytics-folha-pagamento-chart-competencia.crossFilter->vw-analytics-folha-pagamento-table.queryContext",
                        "vw-analytics-folha-pagamento-chart-competencia.selectionChange->vw-analytics-folha-pagamento-table.queryContext");
        assertThat(result.warnings())
                .contains("semantic-chart-temporal-range-filter-target-unresolved")
                .doesNotContain("semantic-chart-interactions-grounded");
    }

    @Test
    void previewDisablesTemporalFilteringWhenInferredRangeTargetsAreAmbiguous() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard da evolucao mensal da folha por competencia com detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTimeseriesDashboardIntent());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        responseSchema.putObject("properties")
                .putObject("competencia")
                .put("type", "string")
                .put("format", "date")
                .putObject("x-ui")
                .put("label", "Competencia");
        ObjectNode filterSchema = objectMapper.createObjectNode();
        ObjectNode filterProperties = filterSchema.putObject("properties");
        for (String field : List.of("competenciaBetween", "competenciaBetweenInclusive")) {
            ObjectNode range = filterProperties.putObject(field);
            range.put("type", "array");
            range.putObject("items").put("type", "string").put("format", "date");
            range.putObject("x-ui").put("label", "Competencia").put("controlType", "dateRange");
        }
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if ("request".equals(context.getSchemaType())
                            && context.getPath().endsWith("/filter")) {
                        return SchemaFetchResult.success(filterSchema, "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        JsonNode plan = result.uiCompositionPlan();
        JsonNode chart = plan.path("widgets").findParents("key").stream()
                .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                        .equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("crossFilter").asBoolean()).isFalse();
        assertThat(findBinding(
                plan.path("bindings"),
                "vw-analytics-folha-pagamento-chart-competencia.pointClick->vw-analytics-folha-pagamento-table.queryContext")
                .isMissingNode()).isTrue();
        assertThat(result.warnings())
                .contains("semantic-chart-temporal-range-filter-target-unresolved")
                .doesNotContain("semantic-chart-interactions-grounded");
    }

    @Test
    void previewDisablesTemporalFilteringWhenTheFilterSchemaIsUnavailable() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard da evolucao mensal da folha por competencia com detalhes.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollTimeseriesDashboardIntent());
        ObjectNode responseSchema = objectMapper.createObjectNode();
        responseSchema.putObject("properties")
                .putObject("competencia")
                .put("type", "string")
                .put("format", "date")
                .putObject("x-ui")
                .put("label", "Competencia");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if ("request".equals(context.getSchemaType())
                            && context.getPath().endsWith("/filter")) {
                        return SchemaFetchResult.failure(
                                SchemaFetchResult.Status.UNAVAILABLE,
                                503,
                                "http://localhost/schemas/filtered",
                                "SCHEMA_UNAVAILABLE",
                                "temporary test failure");
                    }
                    return SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        JsonNode plan = result.uiCompositionPlan();
        JsonNode chart = plan.path("widgets").findParents("key").stream()
                .filter(widget -> "vw-analytics-folha-pagamento-chart-competencia"
                        .equals(widget.path("key").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("crossFilter").asBoolean()).isFalse();
        assertThat(chart.path("inputs").path("config").path("interactions")
                .path("eventActions").has("crossFilter")).isFalse();
        assertThat(plan.path("bindings").findValuesAsText("id"))
                .doesNotContain(
                        "vw-analytics-folha-pagamento-chart-competencia.pointClick->surface.open",
                        "vw-analytics-folha-pagamento-chart-competencia.pointClick->vw-analytics-folha-pagamento-table.queryContext",
                        "vw-analytics-folha-pagamento-chart-competencia.crossFilter->vw-analytics-folha-pagamento-table.queryContext");
        assertThat(result.warnings())
                .contains("semantic-chart-temporal-range-filter-target-unresolved")
                .doesNotContain("semantic-chart-interactions-grounded");
    }

    @Test
    void previewRepairsStatsTimeseriesAxisAwayFromNumericMonthBucket() throws Exception {
        AgenticAuthoringIntentResolutionResult intent = new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries",
                        "POST",
                        0.95d,
                        "matched payroll analytics",
                        List.of("semantic-retrieval", "analytics-projection")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "calcular tendencia mensal de funcionarios",
                "Vou criar uma pre-visualizacao governada.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "calcular tendencia mensal de funcionarios",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "tempo",
                                "mes",
                                "Mes",
                                "line_chart",
                                "temporal",
                                "count",
                                null,
                                "Total",
                                "/api/human-resources/funcionarios")),
                        false,
                        false,
                        List.of("praxis-table", "praxis-filter", "praxis-rich-content", "praxis-kpi"),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "calcular tendencia mensal de funcionarios",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                intent);
        ObjectNode responseSchema = objectMapper.createObjectNode();
        ObjectNode responseProperties = responseSchema.putObject("properties");
        responseProperties.putObject("mes")
                .put("type", "integer")
                .putObject("x-ui")
                .put("label", "Mes");
        responseProperties.putObject("competencia")
                .put("type", "string")
                .put("format", "date")
                .putObject("x-ui")
                .put("label", "Competencia");
        ObjectNode statsRequestSchema = objectMapper.createObjectNode();
        ObjectNode statsRequestProperties = statsRequestSchema.putObject("properties");
        statsRequestProperties.putObject("field")
                .put("type", "string")
                .put("description", "Campo temporal da serie, como competencia.");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    AiSchemaContext context = invocation.getArgument(0);
                    if ("request".equals(context.getSchemaType())
                            && context.getPath().contains("/stats/timeseries")) {
                        return SchemaFetchResult.success(statsRequestSchema, "http://localhost/schemas/filtered");
                    }
                    return SchemaFetchResult.success(responseSchema, "http://localhost/schemas/filtered");
                });
        AgenticAuthoringUiCompositionPlanProvider provider = ignored -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:test"),
                        payrollTimeseriesPlanWithMonthAxis(),
                        objectMapper.createObjectNode()));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings())
                .contains("semantic-chart-timeseries-axis-repaired-with-governed-temporal-field");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"requestedField\":\"mes\"")
                .contains("\"field\":\"competencia\"")
                .contains("\"statsRequest\":{\"filter\":{},\"field\":\"competencia\"")
                .contains("\"dimensions\":[\"competencia\"]");
        assertThat(plan).doesNotContain("\"statsRequest\":{\"filter\":{},\"field\":\"mes\"");
    }

    @Test
    void previewPromotesLineChartWithDateTimeAxisToTimeseries() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico de linha da evolucao mensal de incidentes por Ocorrido Em.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                incidentLineChartWithDateAxisButNoTemporalOperationIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("severidade")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Severidade");
        properties.putObject("ocorridoEm")
                .put("type", "string")
                .put("format", "date-time")
                .putObject("x-ui")
                .put("label", "Ocorrido em");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-chart-required");
        assertThat(result.warnings())
                .contains("semantic-chart-temporal-operation-repaired-with-schema-field")
                .doesNotContain("semantic-chart-group-by-unsupported-field-type");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"componentId\":\"praxis-chart\"")
                .contains("\"field\":\"ocorridoEm\"")
                .contains("\"type\":\"time\"")
                .contains("\"statsOperation\":\"timeseries\"")
                .contains("\"statsPath\":\"/api/risk-intelligence/vw-indicadores-incidentes/stats/timeseries\"")
                .contains("\"granularity\":\"MONTH\"")
                .contains("\"schemaProbeStatus\":\"verified\"");
    }

    @Test
    void previewPromotesSemanticTemporalAxisToTimeseriesEvenWhenChartTypeIsGeneric() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie apenas um grafico da evolucao mensal de incidentes por data de ocorrido.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                incidentGenericChartWithTemporalConceptIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("ocorridoEm")
                .put("type", "string")
                .put("format", "date-time")
                .putObject("x-ui")
                .put("label", "Ocorrido em");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("semantic-preview-chart-required");
        assertThat(result.warnings())
                .contains("semantic-chart-temporal-operation-repaired-with-schema-field")
                .doesNotContain("semantic-chart-group-by-unsupported-field-type");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"field\":\"ocorridoEm\"")
                .contains("\"type\":\"time\"")
                .contains("\"statsOperation\":\"timeseries\"")
                .contains("\"schemaProbeStatus\":\"verified\"");
    }

    @Test
    void previewOmitsUngroundedChartsWhenLlmProvidesNoAxes() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Gostei, mas prefiro graficos com KPIs, filtros e tabela de detalhe preservando estes dados.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                payrollAnalyticsDashboardChartIntentWithoutAxes());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("departamento").put("type", "string").putObject("x-ui").put("label", "Departamento");
        properties.putObject("mes").put("type", "integer").putObject("x-ui").put("label", "Mes");
        properties.putObject("salarioBruto").put("type", "number").putObject("x-ui").put("label", "Salario Bruto");
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost/schemas/filtered"));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).contains("semantic-preview-axis-schema-verification-required");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("praxis-table")
                .doesNotContain("praxis-chart")
                .contains("\"field\":\"unresolved\"")
                .contains("\"schemaVerified\":false")
                .contains("\"provenance\":\"schema-grounding-required\"")
                .doesNotContain("Canvas item references unknown widget");
        assertThat(result.uiCompositionPlan().path("canvas").path("items").toString())
                .doesNotContain("vw-analytics-folha-pagamento-chart-unresolved");
        assertThat(result.assistantMessage())
                .contains("ainda nao consegui montar o grafico pedido");
    }

    @Test
    void previewVerifiesDashboardAxesAgainstReadSchemaWhenCandidateCameFromCreateSurface() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "A fonte deve ser a tabela de funcionarios, nao folha. Mantenha o dashboard com grafico por departamento.",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                employeeDashboardFromCreateSurfaceIntent());
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("departamentoId")
                .put("type", "integer")
                .put("format", "int32")
                .putObject("x-ui")
                .put("label", "Departamento");
        properties.putObject("departamentoNome")
                .put("type", "string")
                .putObject("x-ui")
                .put("label", "Departamento");
        properties.putObject("cargoNome").put("type", "string");
        properties.putObject("salario").put("type", "number");
        List<AiSchemaContext> capturedContexts = new ArrayList<>();
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    capturedContexts.add(invocation.getArgument(0));
                    return SchemaFetchResult.success(schema, "http://localhost/schemas/filtered");
                });

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper)),
                null,
                schemaRetrievalService)
                .preview(request, "tenant", "user", "local", "http://localhost");

        assertThat(result.valid()).isTrue();
        assertThat(capturedContexts)
                .anySatisfy(context -> {
                    assertThat(context.getPath()).isEqualTo("/api/human-resources/funcionarios/filter/cursor");
                    assertThat(context.getOperation()).isEqualTo("post");
                    assertThat(context.getSchemaType()).isEqualTo("response");
                });
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"requestedField\":\"departamento\"")
                .contains("\"field\":\"departamentoNome\"")
                .contains("\"schemaVerified\":true")
                .contains("\"statsPath\":\"/api/human-resources/funcionarios/stats/group-by\"")
                .contains("\"selectedFieldIds\":[\"departamentoNome\"]")
                .contains("\"dimensionField\":\"departamentoNome\"");
    }

    @Test
    void previewMessageDescribesChartDrilldownDetailAsRichList() throws Exception {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("layoutPreset", "chart-drilldown-dashboard");
        ArrayNode widgets = plan.putArray("widgets");
        widgets.addObject().put("key", "payroll-by-department-chart").put("componentId", "praxis-chart");
        widgets.addObject().put("key", "payroll-drilldown-list").put("componentId", "praxis-list");
        ObjectNode compiledFormPatch = objectMapper.createObjectNode();
        compiledFormPatch.putObject("patch");
        AgenticAuthoringUiCompositionPlanProvider provider = request -> java.util.Optional.of(
                new AgenticAuthoringUiCompositionPlanResult(
                        true,
                        List.of(),
                        List.of("ui-composition-plan-provider:chart-drilldown-dashboard"),
                        plan,
                        compiledFormPatch));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider))
                .preview(new AgenticAuthoringPlanRequest(
                        "Crie dashboard de folha com grafico e lista rica de detalhe",
                        "openai",
                        "gpt-5.4-mini",
                        "test-key",
                        null,
                        payrollAnalyticsDashboardIntent()),
                        "tenant",
                        "user",
                        "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.assistantMessage())
                .contains("lista de detalhe em cards ricos")
                .doesNotContain("tabela de detalhe");
    }

    @Test
    void previewReturnsSelectedResourceMasterDetailPlanInsteadOfRejectingNonFormArtifact() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma tela com lista de funcionarios e detalhe lateral",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                selectedMasterDetailIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)))
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).doesNotContain("intent-resolution-artifact-must-be-form");
        assertThat(result.uiCompositionPlan().path("layoutPreset").asText()).isEqualTo("resource-master-detail");
        assertThat(result.uiCompositionPlan().path("widgets")).hasSize(2);
        JsonNode bindings = result.uiCompositionPlan().path("bindings");
        assertThat(bindings).hasSize(2);
        assertThat(bindings.path(0).path("from").path("kind").asText()).isEqualTo("component-port");
        assertThat(bindings.path(0).path("from").path("widget").asText()).isEqualTo("human-resources-funcionarios-master");
        assertThat(bindings.path(0).path("from").path("port").asText()).isEqualTo("rowClick");
        assertThat(bindings.path(0).path("to").path("kind").asText()).isEqualTo("state");
        assertThat(bindings.path(0).path("to").path("path").asText()).isEqualTo("selectedItem");
        assertThat(bindings.path(0).path("transform").path("path").asText()).isEqualTo("payload.row");
        assertThat(bindings.path(1).path("from").path("kind").asText()).isEqualTo("state");
        assertThat(bindings.path(1).path("from").path("path").asText()).isEqualTo("selectedItem");
        assertThat(bindings.path(1).path("to").path("kind").asText()).isEqualTo("component-port");
        assertThat(bindings.path(1).path("to").path("widget").asText()).isEqualTo("human-resources-funcionarios-detail");
        assertThat(bindings.path(1).path("to").path("port").asText()).isEqualTo("resourceId");
        assertThat(bindings.path(1).path("transform").path("kind").asText()).isEqualTo("pick-path");
        assertThat(bindings.path(1).path("transform").path("path").asText()).isEqualTo("id");
        JsonNode detailWidget = result.uiCompositionPlan().path("widgets").path(1);
        assertThat(detailWidget.path("componentId").asText()).isEqualTo("praxis-dynamic-form");
        assertThat(detailWidget.path("inputs").path("mode").asText()).isEqualTo("view");
        assertThat(detailWidget.path("inputs").path("resourcePath").asText()).isEqualTo("/api/human-resources/funcionarios");
        JsonNode tableColumns = result.uiCompositionPlan().path("widgets").path(0)
                .path("inputs").path("config").path("columns");
        assertThat(tableColumns).isNotEmpty();
        assertThat(findColumn(tableColumns, "salario").path("format").asText()).isEqualTo("BRL|symbol|2");
        assertThat(findColumn(tableColumns, "ativo").path("renderer").path("type").asText()).isEqualTo("chip");
        assertThat(result.assistantMessage())
                .contains("valores formatados")
                .contains("acoes por linha");
        assertThat(bindings.path(1).has("source")).isFalse();
        assertThat(bindings.path(1).has("target")).isFalse();
        assertThat(result.warnings()).contains(
                "ui-composition-plan-provider:selected-resource-master-detail",
                "compiled-form-patch-materialized-by-page-builder");
    }

    @Test
    void previewUsesLlmSynthesizedAssistantMessageWhenAvailable() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard Confirmed: usar /api/human-resources/vw-ranking-reputacao",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                selectedDashboardIntent());
        when(messageSynthesizer.synthesizeWithTelemetry(
                any(AgenticAuthoringPlanRequest.class),
                any(AgenticAuthoringIntentResolutionResult.class),
                any(),
                anyBoolean(),
                anyList(),
                anyList(),
                anyString(),
                eq("tenant"),
                eq("user"),
                eq("local")))
                .thenReturn(AgenticAuthoringPreviewMessageResult.deterministic(
                        "Usei a fonte Ranking reputacao para montar a pre-visualizacao. A tabela esta conectada ao recurso e voce ja pode revisar, pedir um grafico ou salvar."));

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper)),
                messageSynthesizer)
                .preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.assistantMessage()).contains("Usei a fonte Ranking reputacao");
    }

    @Test
    void previewRejectsIntentThatMustRouteToSharedRuleAuthoring() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma regra LGPD para CPF",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                sharedRuleRouteIntent());

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("intent-resolution-shared-rule-route-required");
        assertThat(result.warnings()).contains("preview-skipped-invalid-intent-resolution");
        assertThat(result.compiledFormPatch().isMissingNode()).isTrue();
    }

    @Test
    void previewFailsClosedForUiCompositionIntentWhenNoPlanProviderIsAvailable() throws Exception {
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie um dashboard para reputacao",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                selectedDashboardIntent());

        AgenticAuthoringPreviewResult result = service().preview(request, "tenant", "user", "local");

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("intent-resolution-artifact-requires-ui-composition-plan");
        assertThat(result.warnings())
                .contains("ui-composition-plan-provider-unavailable", "preview-skipped-invalid-intent-resolution");
        assertThat(result.minimalFormPlan().isMissingNode()).isTrue();
        assertThat(result.compiledFormPatch().isMissingNode()).isTrue();
        verifyNoInteractions(planService, patchCompilerService);
    }

    @Test
    void previewRejectsSharedRuleRouteBeforeUiCompositionProviders() throws Exception {
        AtomicReference<Boolean> providerCalled = new AtomicReference<>(false);
        AgenticAuthoringUiCompositionPlanProvider provider = request -> {
            providerCalled.set(true);
            ObjectNode uiCompositionPlan = objectMapper.createObjectNode();
            uiCompositionPlan.put("kind", "praxis.ui-composition-plan");
            ObjectNode compiledPatch = objectMapper.createObjectNode();
            compiledPatch.put("kind", "compiled-form-patch");
            return java.util.Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                    true,
                    List.of(),
                    List.of("provider-should-not-run"),
                    uiCompositionPlan,
                    compiledPatch));
        };
        AgenticAuthoringPlanRequest request = new AgenticAuthoringPlanRequest(
                "Crie uma regra LGPD para CPF",
                "openai",
                "gpt-5.4-mini",
                "test-key",
                null,
                sharedRuleRouteIntent());

        AgenticAuthoringPreviewResult result = new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                List.of(provider))
                .preview(request, "tenant", "user", "local");

        assertThat(providerCalled.get()).isFalse();
        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).containsExactly("intent-resolution-shared-rule-route-required");
        assertThat(result.warnings()).contains("preview-skipped-invalid-intent-resolution");
        assertThat(result.uiCompositionPlan()).isNull();
        assertThat(result.compiledFormPatch().isMissingNode()).isTrue();
    }

    private AgenticAuthoringPreviewService service() {
        return new AgenticAuthoringPreviewService(planService, patchCompilerService);
    }

    private ObjectNode employeeStatsCapabilities() {
        ObjectNode capabilities = objectMapper.createObjectNode();
        ArrayNode fields = capabilities.putObject("stats").putArray("fields");
        fields.addObject()
                .put("field", "departamento")
                .put("label", "Departamento")
                .put("keyAndLabelDistinct", true)
                .put("groupByEligible", true)
                .putArray("metrics").add("COUNT");
        fields.addObject()
                .put("field", "cargoNome")
                .put("label", "Cargo Nome")
                .put("groupByEligible", true)
                .putArray("metrics").add("COUNT");
        fields.addObject()
                .put("field", "salario")
                .put("label", "Salario")
                .put("metricFieldEligible", true)
                .putArray("metrics").add("COUNT").add("SUM").add("AVG");
        return capabilities;
    }

    private ObjectNode comparisonStatsCapabilities() {
        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode comparisonOperation = capabilities.putObject("operations").putObject("statsComparison");
        comparisonOperation.put("id", "statsComparison");
        comparisonOperation.put("supported", true);
        comparisonOperation.put("scope", "COLLECTION");
        comparisonOperation.put("preferredMethod", "POST");
        comparisonOperation.put("preferredRel", "stats-comparison");
        ObjectNode availability = comparisonOperation.putObject("availability");
        availability.put("allowed", true);
        availability.putObject("metadata").put("accessClass", "aggregate");
        ObjectNode filterOperation = capabilities.path("operations") instanceof ObjectNode operations
                ? operations.putObject("filter")
                : capabilities.putObject("operations").putObject("filter");
        filterOperation.put("id", "filter");
        filterOperation.put("supported", true);
        filterOperation.putObject("availability").put("allowed", true);
        ArrayNode fields = capabilities.putObject("stats").putArray("fields");
        fields.addObject()
                .put("field", "departamento")
                .put("label", "Departamento")
                .put("keyAndLabelDistinct", true)
                .put("groupByEligible", true)
                .putArray("modes").add("GROUP_BY");
        fields.addObject()
                .put("field", "competencia")
                .put("label", "Competencia")
                .put("timeSeriesEligible", true)
                .putArray("modes").add("TIME_SERIES");
        fields.addObject()
                .put("field", "funcionarioId")
                .put("label", "Funcionario")
                .put("metricFieldEligible", true)
                .putArray("metrics").add("DISTINCT_COUNT");
        fields.addObject()
                .put("field", "diasAfastado")
                .put("label", "Dias afastado")
                .put("metricFieldEligible", true)
                .putArray("metrics").add("SUM");
        return capabilities;
    }

    private ObjectNode comparisonResourceSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("departamento").put("type", "string")
                .putObject("x-ui").put("label", "Departamento");
        properties.putObject("competencia").put("type", "string").put("format", "date");
        properties.putObject("funcionarioId").put("type", "integer");
        properties.putObject("diasAfastado").put("type", "number");
        return schema;
    }

    private ObjectNode comparisonFilterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = schema.putObject("properties");
        ObjectNode department = properties.putObject("departamentoIdsIn");
        department.put("type", "array");
        department.putObject("items").put("type", "integer");
        department.putObject("x-ui")
                .put("label", "Departamento")
                .put("controlType", "async-select")
                .put("multiple", true)
                .put("endpoint", "/api/human-resources/departamentos/options/filter");
        return schema;
    }

    private ObjectNode comparisonAnalyticsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode projection = schema.putObject("x-ui")
                .putObject("analytics")
                .putArray("projections")
                .addObject();
        projection.put("id", "absence-department-comparison");
        projection.put("intent", "comparison");
        projection.putObject("source")
                .put("kind", "praxis.stats")
                .put("resource", "/api/human-resources/vw-analytics-afastamentos")
                .put("operation", "comparison");
        ObjectNode bindings = projection.putObject("bindings");
        bindings.putObject("primaryDimension")
                .put("field", "departamento")
                .put("role", "category")
                .put("label", "Departamento")
                .put("keyFilterField", "departamentoIdsIn");
        bindings.putArray("primaryMetrics")
                .addObject()
                .put("field", "funcionarioId")
                .put("aggregation", "distinct-count")
                .put("label", "Colaboradores");
        bindings.withArray("primaryMetrics")
                .addObject()
                .put("field", "diasAfastado")
                .put("aggregation", "sum")
                .put("label", "Dias afastado");
        bindings.putObject("comparisonPeriod")
                .put("field", "competencia")
                .put("timezone", "America/Sao_Paulo")
                .put("preset", "LAST_30_DAYS")
                .put("mode", "PREVIOUS_ALIGNED");
        ObjectNode defaults = projection.putObject("defaults");
        defaults.put("limit", 12);
        defaults.putArray("sort").addObject().put("field", "diasAfastado").put("direction", "desc");
        projection.putObject("presentationHints").putArray("preferredFamilies").add("chart");
        ObjectNode interactions = projection.putObject("interactions");
        interactions.put("pointSelection", false);
        interactions.put("crossFilter", true);
        interactions.putObject("recordOpen")
                .put("sourceIdentityField", "funcionarioId")
                .putObject("target")
                .put("resourceKey", "human-resources.funcionarios")
                .put("surfaceId", "hero-profile");
        ObjectNode policyRef = projection.putObject("governance").putArray("policyRefs").addObject();
        policyRef.put("policyId", "absence-criticality-policy");
        policyRef.put("policyVersion", "2026-07");
        policyRef.put("role", "criticality");
        policyRef.put("resultField", "criticalityLevel");
        policyRef.putObject("attestation")
                .put("policyIdField", "criticalityPolicyId")
                .put("policyVersionField", "criticalityPolicyVersion");
        return schema;
    }

    private ObjectNode employeeSurfaceCatalog(String availabilityReason) {
        ObjectNode catalog = objectMapper.createObjectNode();
        catalog.put("resourceKey", "human-resources.funcionarios");
        catalog.put("resourcePath", "/api/human-resources/funcionarios");
        catalog.put("group", "human-resources");
        ObjectNode surface = catalog.putArray("surfaces").addObject();
        surface.put("id", "hero-profile");
        surface.put("resourceKey", "human-resources.funcionarios");
        surface.put("kind", "READ_PROJECTION");
        surface.put("scope", "ITEM");
        surface.put("operationId", "getFuncionarioHeroProfile");
        ObjectNode availability = surface.putObject("availability");
        availability.put("allowed", availabilityReason == null);
        if (availabilityReason != null) {
            availability.put("reason", availabilityReason);
        }
        return catalog;
    }

    private ObjectNode unresolvedAxisDashboardPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("version", "1.0");
        ArrayNode widgets = plan.putArray("widgets");
        ObjectNode chart = widgets.addObject();
        chart.put("key", "contracts-chart");
        chart.put("componentId", "praxis-chart");
        ObjectNode chartConfig = chart.putObject("inputs").putObject("config");
        chartConfig.put("id", "contracts-chart");
        chartConfig.put("type", "bar");
        chartConfig.putObject("semanticAxis")
                .put("concept", "unresolved")
                .put("field", "unresolved")
                .put("label", "Unresolved")
                .put("provenance", "schema-grounding-required")
                .put("schemaVerified", false)
                .put("schemaProbeStatus", "pending");
        chartConfig.putObject("dataSource")
                .put("kind", "remote")
                .put("resourcePath", "/api/procurement/contracts")
                .put("submitUrl", "/api/procurement/contracts/filter/cursor")
                .put("submitMethod", "post");
        ObjectNode list = widgets.addObject();
        list.put("key", "contracts-list");
        list.put("componentId", "praxis-list");
        list.putObject("inputs").putObject("config")
                .putObject("dataSource")
                .put("resourcePath", "/api/procurement/contracts");
        ObjectNode table = widgets.addObject();
        table.put("key", "contracts-table");
        table.put("componentId", "praxis-table");
        table.putObject("inputs")
                .put("resourcePath", "/api/procurement/contracts");
        ObjectNode diagnostics = plan.putObject("diagnostics");
        diagnostics.putArray("semanticAxes").addObject()
                .put("concept", "unresolved")
                .put("field", "unresolved")
                .put("label", "Unresolved")
                .put("provenance", "schema-grounding-required")
                .put("schemaVerified", false)
                .put("schemaProbeStatus", "pending");
        return plan;
    }

    private ObjectNode unresolvedAxisDashboardPlanWithOrphanDiagnosticsAxis() {
        ObjectNode plan = unresolvedAxisDashboardPlan();
        ObjectNode diagnosticsAxis = (ObjectNode) plan.path("diagnostics").path("semanticAxes").path(0);
        diagnosticsAxis.put("concept", "status do contrato");
        diagnosticsAxis.put("field", "contratoStatus");
        diagnosticsAxis.put("label", "Status do contrato");
        diagnosticsAxis.put("provenance", "llm-authored-semantic-axis");
        return plan;
    }

    private AgenticAuthoringIntentResolutionResult hostUiCompositionIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/departamentos",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/departamentos/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/departamentos/filter/cursor",
                        "POST",
                        0.91d,
                        "selected departamentos page resource",
                        List.of("semantic-retrieval")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
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
                        "funcionarios-form",
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
                        List.of("funcionarios")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult tableColumnAdditionIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "table",
                "column.add",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "funcionarios-table",
                        "praxis-table",
                        "/api/human-resources/funcionarios",
                        "",
                        "",
                        "get"),
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "get",
                        "",
                        "/api/human-resources/funcionarios",
                        "GET",
                        0.97d,
                        "resource preserved from existing component target",
                        List.of("current-page-target-resource")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringIntentResolutionResult selectedDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-ranking-reputacao",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-ranking-reputacao/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                        "POST",
                        0.94d,
                        "user selected a dashboard resource candidate",
                        List.of("quick-reply")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult selectedDashboardChartIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_chart",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-ranking-reputacao",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-ranking-reputacao/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                        "POST",
                        0.94d,
                        "user selected a dashboard resource candidate",
                        List.of("quick-reply")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Gostei da visualizacao, mas prefiro que mostre usando graficos",
                "Vou trocar a projecao visual para grafico.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "analytical-breakdown",
                        "dashboard",
                        "praxis-chart",
                        List.of(),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult keywordFallbackReviewDashboardIntent() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/vw-analytics-folha-pagamento",
                "post",
                "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                "POST",
                0.95d,
                "matched payroll analytics",
                List.of("semantic-retrieval"));
        AgenticAuthoringVisualizationDecision visualizationDecision = new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "analytical-breakdown",
                "dashboard",
                "praxis-chart",
                List.of(),
                true,
                true,
                "llm-authored-semantic-decision");
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Gostei, mas prefiro graficos",
                "Vou criar uma pre-visualizacao governada.",
                null,
                List.of(),
                null,
                List.of("keyword-fallback-fail-safe-applied"),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                visualizationDecision,
                new AgenticAuthoringSemanticDecision(
                        AgenticAuthoringSemanticDecision.SCHEMA_VERSION,
                        "keyword-fallback-review-decision",
                        "create",
                        "dashboard",
                        "create_artifact",
                        new AgenticAuthoringSemanticDecision.SelectedResource(
                                candidate.resourcePath(),
                                candidate.operation(),
                                candidate.schemaUrl(),
                                candidate.submitUrl(),
                                candidate.submitMethod()),
                        visualizationDecision,
                        new AgenticAuthoringSemanticDecision.RetrievalEvidence(
                                "keyword_fallback",
                                List.of("keyword-fallback-fail-safe-applied"),
                                1),
                        true,
                        "keyword-fallback-fail-safe",
                        "",
                        ""));
    }

    private AgenticAuthoringIntentResolutionResult payrollAnalyticsDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                        "POST",
                        0.95d,
                        "matched payroll analytics",
                        List.of("payroll", "analytics")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult payrollAnalyticsDashboardChartIntentWithoutAxes() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                        "POST",
                        0.95d,
                        "matched payroll analytics",
                        List.of("payroll", "analytics")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Gostei, mas prefiro graficos com KPIs, filtros e tabela de detalhe preservando estes dados.",
                "Vou criar uma pre-visualizacao governada.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "analytical-breakdown",
                        "dashboard",
                        "praxis-chart",
                        List.of(),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private ObjectNode payrollTimeseriesPlanWithMonthAxis() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schemaVersion", "praxis-ui-composition-plan.v1");
        plan.put("layoutPreset", "single-chart-page");
        ArrayNode widgets = plan.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "vw-analytics-folha-pagamento-chart-mes");
        widget.put("componentId", "praxis-chart");
        ObjectNode config = widget.putObject("inputs").putObject("config");
        config.put("type", "line");
        config.put("title", "Registros por Mes");
        ObjectNode semanticAxis = config.putObject("semanticAxis");
        semanticAxis.put("concept", "tempo");
        semanticAxis.put("field", "mes");
        semanticAxis.put("label", "Mes");
        ObjectNode axes = config.putObject("axes");
        ObjectNode x = axes.putObject("x");
        x.put("field", "mes");
        x.put("type", "time");
        axes.putObject("y").put("field", "total");
        ObjectNode seriesItem = config.putArray("series").addObject();
        seriesItem.put("type", "line");
        ObjectNode metric = seriesItem.putObject("metric");
        metric.put("aggregation", "count");
        metric.put("alias", "total");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        ObjectNode query = dataSource.putObject("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "timeseries");
        query.put("statsPath", "/api/human-resources/vw-analytics-folha-pagamento/stats/timeseries");
        query.put("granularity", "month");
        query.putArray("dimensions").add("mes");
        ObjectNode queryMetric = query.putArray("metrics").addObject();
        queryMetric.put("aggregation", "count");
        queryMetric.put("alias", "total");
        ObjectNode statsRequest = query.putObject("statsRequest");
        statsRequest.putObject("filter");
        statsRequest.put("field", "mes");
        statsRequest.put("granularity", "MONTH");
        ObjectNode statsMetric = statsRequest.putObject("metric");
        statsMetric.put("operation", "COUNT");
        statsMetric.put("alias", "total");
        ObjectNode diagnostics = plan.putObject("diagnostics");
        ArrayNode semanticAxes = diagnostics.putArray("semanticAxes");
        ObjectNode diagnosticAxis = semanticAxes.addObject();
        diagnosticAxis.put("concept", "tempo");
        diagnosticAxis.put("field", "mes");
        diagnosticAxis.put("label", "Mes");
        diagnosticAxis.put("provenance", "/api/human-resources/funcionarios");
        return plan;
    }

    private AgenticAuthoringIntentResolutionResult payrollAnalyticsDashboardIntentWithMetric(String metricField) {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                        "POST",
                        0.95d,
                        "matched payroll analytics",
                        List.of("payroll", "analytics")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico de barras horizontais de folha por departamento somando Salario Liquido.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "payroll-department-horizontal-chart",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "department",
                                "departamento",
                                "Departamento",
                                "horizontal-bar",
                                "horizontal",
                                "count",
                                metricField,
                                "Salário Líquido",
                                "llm-authored-semantic-axis")),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult payrollAnalyticsDashboardIntentWithUnresolvedAxis() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                        "POST",
                        0.95d,
                        "matched payroll analytics",
                        List.of("payroll", "analytics")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico horizontal de folha de pagamento por departamento somando salario liquido. Use Analytics Folha Pagamento.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "payroll-unresolved-horizontal-chart",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "unresolved",
                                "unresolved",
                                "Schema-grounded dimension required",
                                "horizontal-bar",
                                "horizontal",
                                "count",
                                null,
                                null,
                                "llm-authored-semantic-axis")),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult contractsDashboardIntentWithUnresolvedAxis() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/procurement/contracts",
                "post",
                "/schemas/filtered?path=/api/procurement/contracts/filter/cursor&operation=post&schemaType=response",
                "/api/procurement/contracts/filter/cursor",
                "POST",
                0.95d,
                "matched procurement contracts",
                List.of("semantic-retrieval", "tool-search-api-resources"));
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Visualizar contratos de fornecedores em tabela",
                "Vou criar uma visualizacao de contratos.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "contracts-supplier-overview",
                        "resource-dashboard",
                        "praxis-table",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "unresolved",
                                "unresolved",
                                "Unresolved",
                                "bar",
                                "vertical",
                                "count",
                                null,
                                "Total",
                                "schema-grounding-required")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult employeeStatusPieChartIntent() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response",
                "/api/human-resources/funcionarios/filter/cursor",
                "POST",
                0.95d,
                "matched employees",
                List.of("explicit-source-match", "domain-catalog-context"));
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico de pizza de funcionarios por status. Use a fonte Funcionarios. Nao crie tabela, filtros nem KPIs.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "single-pie-by-employee-status",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "grouping",
                                "status",
                                "Status do funcionário",
                                "pie",
                                "vertical",
                                "count",
                                null,
                                "Total",
                                "llm-authored-semantic-axis")),
                        false,
                        false,
                        List.of("praxis-table", "praxis-filter", "praxis-rich-content"),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult payrollTimeseriesDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-folha-pagamento/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                        "POST",
                        0.95d,
                        "matched payroll analytics",
                        List.of("semantic-retrieval", "analytics-projection")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie um dashboard da evolucao mensal da folha por competencia com detalhes.",
                "Vou criar uma pre-visualizacao governada.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "payroll-monthly-evolution-dashboard",
                        "dashboard",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "competence",
                                "competencia",
                                "Competencia",
                                "line",
                                "temporal",
                                "sum",
                                "salarioLiquido",
                                "Salario liquido",
                                "llm-authored-semantic-axis")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult unresolvedIncidentTimeseriesChartIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor",
                        "POST",
                        0.95d,
                        "matched incident indicators",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico de linha da evolucao mensal de incidentes por data de ocorrido.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "incident-monthly-evolution-line-chart",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "unresolved",
                                "unresolved",
                                "Unresolved",
                                "line",
                                "temporal",
                                "count",
                                null,
                                "Total",
                                "llm-authored-semantic-axis")),
                        false,
                        false,
                        List.of("praxis-table", "praxis-filter", "praxis-rich-content", "praxis-kpi"),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult incidentLineChartWithDateAxisButNoTemporalOperationIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor",
                        "POST",
                        0.95d,
                        "matched incident indicators",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico de linha da evolucao mensal de incidentes por Ocorrido Em.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "incident-monthly-evolution-line-chart",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "time_dimension",
                                "ocorridoEm",
                                "Ocorrido Em (mes)",
                                "line_chart",
                                "vertical",
                                "count",
                                null,
                                "Total",
                                "llm-authored-semantic-axis")),
                        false,
                        false,
                        List.of("praxis-table", "praxis-filter", "praxis-rich-content", "praxis-kpi"),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult incidentGenericChartWithTemporalConceptIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "chart",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor",
                        "POST",
                        0.95d,
                        "matched incident indicators",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico da evolucao mensal de incidentes por data de ocorrido.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "incident-monthly-evolution-chart",
                        "single-chart",
                        "praxis-chart",
                        List.of(new AgenticAuthoringVisualizationAxisDecision(
                                "tempo_mensal",
                                "ocorridoEm",
                                "Mês de ocorrência",
                                "bar",
                                "vertical",
                                "count",
                                null,
                                "Total",
                                "llm-authored-semantic-axis")),
                        false,
                        false,
                        List.of("praxis-table", "praxis-filter", "praxis-rich-content", "praxis-kpi"),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult payrollCountAxisDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
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
                        0.94d,
                        "matched payroll table",
                        List.of("payroll", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Gostei, mas prefiro graficos mantendo os mesmos dados da folha.",
                "Vou preservar a fonte e trocar a projecao visual para graficos.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "analytical-breakdown",
                        "dashboard",
                        "praxis-chart",
                        List.of(visualizationAxis(
                                "recordCount",
                                "quantidade_registros",
                                "Quantidade de registros",
                                "bar",
                                "vertical")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult incidentSingleChartIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor",
                        "POST",
                        0.96d,
                        "matched incident indicators",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico de barras simples de incidentes por severidade.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "single-chart",
                        "single_chart",
                        "praxis-chart",
                        List.of(visualizationAxis(
                                "severity",
                                "severidade",
                                "Severidade",
                                "bar",
                                "vertical")),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult incidentSingleChartIntentWithTransactionalSelectedCandidate() {
        AgenticAuthoringCandidate transactionalCandidate = new AgenticAuthoringCandidate(
                "/api/operations/incidentes",
                "post",
                "/schemas/filtered?path=/api/operations/incidentes/filter/cursor&operation=post&schemaType=response",
                "/api/operations/incidentes/filter/cursor",
                "POST",
                0.97d,
                "matched incident operations",
                List.of("semantic-retrieval", "schema-probe-pending"));
        AgenticAuthoringCandidate analyticalCandidate = new AgenticAuthoringCandidate(
                "/api/risk-intelligence/vw-indicadores-incidentes",
                "post",
                "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor&operation=post&schemaType=response",
                "/api/risk-intelligence/vw-indicadores-incidentes/filter/cursor",
                "POST",
                0.92d,
                "matched incident indicators",
                List.of("semantic-retrieval", "analytics-view", "schema-probe-pending"));
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                transactionalCandidate,
                List.of(transactionalCandidate, analyticalCandidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie apenas um grafico de barras simples de incidentes por severidade.",
                "Vou criar somente o grafico solicitado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "single-chart",
                        "single_chart",
                        "praxis-chart",
                        List.of(visualizationAxis(
                                "severity",
                                "severidade",
                                "Severidade",
                                "bar",
                                "vertical")),
                        false,
                        false,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult selectedMasterDetailIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "page",
                "create_master_detail",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        0.90d,
                        "user selected an employee read resource candidate",
                        List.of("quick-reply")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult employeeDashboardFromCreateSurfaceIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                        "/api/human-resources/funcionarios",
                        "POST",
                        0.49d,
                        "matched employee table",
                        List.of("lexical-fallback")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "A fonte deve ser a tabela de funcionarios, nao folha. Mantenha o dashboard com grafico por departamento.",
                "Vou trocar a fonte para funcionarios e manter a visualizacao analitica.",
                null,
                List.of(),
                null,
                List.of("keyword-fallback-fail-safe-applied"),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "analytical-breakdown",
                        "dashboard",
                        "praxis-chart",
                        List.of(visualizationAxis(
                                "department",
                                "departamento",
                                "Departamento",
                                "bar",
                                "vertical")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult payrollTableIntent(String effectivePrompt) {
        return payrollTableIntent(effectivePrompt, List.of("payroll"));
    }

    private AgenticAuthoringIntentResolutionResult payrollTableIntent(String effectivePrompt, List<String> evidence) {
        return payrollTableIntent(
                effectivePrompt,
                evidence,
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento/all&operation=get&schemaType=response",
                "/api/human-resources/folhas-pagamento/all");
    }

    private AgenticAuthoringIntentResolutionResult promptAlignedPayrollTableIntent(String effectivePrompt) {
        return new AgenticAuthoringIntentResolutionResult(
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
                        0.94d,
                        "matched payroll table",
                        List.of("semantic-retrieval", "tool-search-api-resources")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                effectivePrompt,
                "Vou criar uma tabela operacional.",
                List.of(),
                List.of(),
                List.of("llm-resource-selection-overridden-by-prompt-alignment"),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult governedKeywordFallbackPayrollTableIntent(String effectivePrompt) {
        ObjectNode llmDiagnostics = objectMapper.createObjectNode();
        ObjectNode telemetry = llmDiagnostics.putObject("resolutionTelemetry");
        telemetry.put("schemaVersion", "praxis-agentic-authoring-resolution-telemetry.v1");
        telemetry.put("llmResolutionAttempted", true);
        telemetry.put("llmResolved", false);
        telemetry.put("fallbackPolicy", "fail-safe");
        telemetry.put("keywordFallbackApplied", true);
        return new AgenticAuthoringIntentResolutionResult(
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
                        0.94d,
                        "matched payroll table",
                        List.of("semantic-retrieval", "tool-search-api-resources")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                effectivePrompt,
                "Vou criar uma tabela operacional.",
                null,
                List.of(),
                null,
                List.of(),
                List.of("keyword-fallback-applied", "keyword-fallback-fail-safe-applied"),
                List.of(),
                objectMapper.createObjectNode(),
                llmDiagnostics);
    }

    private AgenticAuthoringIntentResolutionResult barePayrollGetTableIntent(String effectivePrompt) {
        return payrollTableIntent(
                effectivePrompt,
                List.of("lexical-fallback"),
                "/schemas/filtered?path=/api/human-resources/folhas-pagamento&operation=get&schemaType=response",
                "/api/human-resources/folhas-pagamento");
    }

    private AgenticAuthoringIntentResolutionResult payrollTableIntent(
            String effectivePrompt,
            List<String> evidence,
            String schemaUrl,
            String submitUrl) {
        return new AgenticAuthoringIntentResolutionResult(
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
                        schemaUrl,
                        submitUrl,
                        "GET",
                        0.94d,
                        "matched payroll table",
                        evidence),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                effectivePrompt,
                "Vou criar uma tabela operacional.",
                List.of(),
                List.of(),
                List.of("llm-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult operationalMonitoringTableIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "table",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/operations/incidentes/filter/cursor",
                        "post",
                        "/schemas/filtered?path=/api/operations/incidentes/filter/cursor&operation=post&schemaType=response",
                        "/api/operations/incidentes/filter/cursor",
                        "POST",
                        0.94d,
                        "matched incidents",
                        List.of("semantic-retrieval")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "Vou criar uma tabela operacional.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult operationalMonitoringDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/operations/incidentes",
                        "post",
                        "/schemas/filtered?path=/api/operations/incidentes/filter/cursor&operation=post&schemaType=response",
                        "/api/operations/incidentes/filter/cursor",
                        "POST",
                        0.94d,
                        "matched incidents",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Preciso monitorar chamados e ocorrencias em atendimento, gravidade, andamento e responsavel.",
                "Vou criar um dashboard operacional.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                null,
                operationalMonitoringVisualizationDecision());
    }

    private AgenticAuthoringIntentResolutionResult operationalMonitoringDashboardIntentWithoutVisualizationDecision() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        0.94d,
                        "matched employees",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Quero um painel com a visao geral sobre funcionarios.",
                "Vou criar um dashboard inicial.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringIntentResolutionResult inferredEmployeeDashboardIntentWithCargoIdEvidence() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        0.94d,
                        "matched employees",
                        List.of("semantic-retrieval", "schema-probe-pending", "field: cargoId")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Quero um painel de funcionarios por departamento e cargo, com graficos e detalhes.",
                "Vou criar um dashboard inicial.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringIntentResolutionResult dashboardQualityRepairIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "dashboard",
                "connect_dashboard_widgets",
                "dashboard-quality-gate",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/procurement/suppliers",
                        "post",
                        "/schemas/filtered?path=/api/procurement/suppliers/filter/cursor&operation=post&schemaType=response",
                        "/api/procurement/suppliers/filter/cursor",
                        "POST",
                        0.94d,
                        "matched suppliers",
                        List.of("dashboard-quality-gate", "schema-fields")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Connect widgets",
                "Vou reparar o dashboard.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringIntentResolutionResult funcionariosCargoDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        0.94d,
                        "matched employees",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Quero um painel geral de funcionarios por cargo.",
                "Vou criar um dashboard inicial.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "employee-dashboard",
                        "dashboard",
                        "praxis-chart",
                        List.of(visualizationAxis("role", "cargoNome", "Cargo", "bar", "vertical")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult comparisonDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/vw-analytics-afastamentos",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/vw-analytics-afastamentos/filter&operation=post&schemaType=response",
                        "/api/human-resources/vw-analytics-afastamentos/filter",
                        "POST",
                        0.96d,
                        "semantic comparison resource",
                        List.of("semantic-retrieval", "resource-capabilities")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Materialize a leitura analitica autorizada para este recurso.",
                "Vou materializar a projection governada.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "comparison",
                        "dashboard",
                        "praxis-chart",
                        List.of(visualizationAxis(
                                "department",
                                "departamento",
                                "Departamento",
                                "bar",
                                "vertical")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult funcionariosDuplicateCargoDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/human-resources/funcionarios",
                        "post",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter&operation=post&schemaType=response",
                        "/api/human-resources/funcionarios/filter",
                        "POST",
                        0.94d,
                        "matched employees",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Quero um painel geral de funcionarios por cargo e descricao do cargo.",
                "Vou criar um dashboard inicial.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "employee-dashboard",
                        "dashboard",
                        "praxis-chart",
                        List.of(
                                visualizationAxis("role", "cargoNome", "Cargo", "bar", "vertical"),
                                visualizationAxis("role-description", "cargoDescricao", "Descricao do cargo", "bar", "vertical")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult ordersDashboardIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "dashboard",
                "create_artifact",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                new AgenticAuthoringCandidate(
                        "/api/sales/pedidos",
                        "post",
                        "/schemas/filtered?path=/api/sales/pedidos/filter&operation=post&schemaType=response",
                        "/api/sales/pedidos/filter",
                        "POST",
                        0.94d,
                        "matched orders",
                        List.of("semantic-retrieval", "schema-probe-pending")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Quero um painel geral de pedidos por status e canal.",
                "Vou criar um dashboard inicial.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                new AgenticAuthoringVisualizationDecision(
                        "praxis-agentic-authoring-visualization-decision.v1",
                        "orders-dashboard",
                        "dashboard",
                        "praxis-chart",
                        List.of(
                                visualizationAxis("status", "statusNome", "Status", "bar", "vertical"),
                                visualizationAxis("channel", "canalNome", "Canal", "doughnut", "vertical")),
                        true,
                        true,
                        "llm-authored-semantic-decision"));
    }

    private AgenticAuthoringIntentResolutionResult chartTypeModificationIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "modify",
                "dashboard",
                "set_chart_type",
                "generic-page-change",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                new AgenticAuthoringTarget(
                        "incidentes-chart-severidade",
                        "praxis-chart",
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "",
                        "",
                        ""),
                new AgenticAuthoringCandidate(
                        "/api/risk-intelligence/vw-indicadores-incidentes",
                        "post",
                        "/schemas/filtered?path=/api/risk-intelligence/vw-indicadores-incidentes/filter&operation=post&schemaType=response",
                        "/api/risk-intelligence/vw-indicadores-incidentes/filter",
                        "POST",
                        0.94d,
                        "matched incidents",
                        List.of("component-capability-catalog", "semantic-retrieval")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Altere o gráfico selecionado para linhas",
                "Vou ajustar o grafico selecionado.",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringVisualizationDecision operationalMonitoringVisualizationDecision() {
        return new AgenticAuthoringVisualizationDecision(
                "praxis-agentic-authoring-visualization-decision.v1",
                "operational-monitoring-dashboard",
                "dashboard",
                "praxis-chart",
                List.of(
                        visualizationAxis("severity", "gravidade", "Gravidade", "bar", "vertical"),
                        visualizationAxis("status", "andamento", "Andamento", "bar", "vertical"),
                        visualizationAxis("owner", "responsavel", "Responsavel", "horizontal-bar", "horizontal")),
                true,
                true,
                "llm-authored-semantic-decision");
    }

    private AgenticAuthoringVisualizationAxisDecision visualizationAxis(
            String concept,
            String field,
            String label,
            String chartType,
            String orientation) {
        return new AgenticAuthoringVisualizationAxisDecision(
                concept,
                field,
                label,
                chartType,
                orientation,
                "count",
                null,
                "Total",
                "llm-authored-semantic-axis");
    }

    private ObjectNode fieldHint(String field, String label, String type) {
        ObjectNode hint = objectMapper.createObjectNode();
        hint.put("field", field);
        hint.put("label", label);
        hint.put("type", type);
        return hint;
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
                        "funcionarios-form",
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
                        List.of("funcionarios")),
                List.of(),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                List.of(),
                List.of(),
                List.of(),
                objectMapper.createObjectNode());
    }

    private AgenticAuthoringIntentResolutionResult createEmployeeFormIntent() {
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "post",
                "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request",
                "/api/human-resources/funcionarios",
                "POST",
                0.95,
                "matched funcionarios",
                List.of("tool-search-api-resources", "schema-available"));
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "create",
                "form",
                "create_artifact",
                "create-minimal-form",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                null,
                candidate,
                List.of(candidate),
                new AgenticAuthoringGateResult("candidate-eligibility@0.1.0", "eligible", List.of()),
                "Crie um formulario de funcionarios",
                "Vou criar o formulario usando a fonte governada de funcionarios.",
                null,
                List.of(),
                null,
                List.of(),
                List.of("llm-fast-intent-resolution-used"),
                List.of(),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode(),
                null);
    }

    private AgenticAuthoringIntentResolutionResult sharedRuleRouteIntent() {
        return new AgenticAuthoringIntentResolutionResult(
                false,
                "create",
                "form",
                "create_artifact",
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
                        0.99,
                        "selected resource for shared rule grounding",
                        List.of("quick-reply-context")),
                List.of(),
                new AgenticAuthoringGateResult(
                        "candidate-eligibility@0.1.0",
                        "route_required",
                        List.of("shared-rule-authoring-required")),
                "Crie uma regra LGPD para CPF",
                "Esse pedido deve seguir pela trilha governada de regra compartilhada.",
                List.of(),
                List.of(),
                List.of("keyword-fallback-applied"),
                List.of("shared-rule-authoring-required"),
                objectMapper.createObjectNode());
    }

    private JsonNode findColumn(JsonNode columns, String field) {
        for (JsonNode column : columns) {
            if (field.equals(column.path("field").asText())) {
                return column;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private JsonNode findBinding(JsonNode bindings, String id) {
        if (bindings != null && bindings.isArray()) {
            for (JsonNode binding : bindings) {
                if (id.equals(binding.path("id").asText())) {
                    return binding;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
