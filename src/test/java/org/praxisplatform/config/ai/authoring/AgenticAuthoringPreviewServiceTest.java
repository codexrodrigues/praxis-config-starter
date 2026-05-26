package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.praxisplatform.config.dto.AiSchemaContext;
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
                .isEqualTo(3);
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
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("key"))
                .contains("funcionarios-chart-departamentoNome", "funcionarios-chart-cargoNome")
                .doesNotContain("funcionarios-chart-nomeCompleto", "funcionarios-chart-dataAdmissao");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .contains("funcionarios-chart-departamentoNome.pointClick->funcionarios-table.queryContext")
                .contains("funcionarios-chart-cargoNome.pointClick->funcionarios-table.queryContext");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("dashboardBlueprint").path("domainSpecific").asBoolean())
                .isFalse();
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
        assertThat(result.warnings()).contains("semantic-chart-group-by-unsupported-field-type");
        assertThat(result.uiCompositionPlan().path("widgets").toString())
                .contains("incidentes-chart-gravidade")
                .contains("incidentes-chart-responsavel")
                .doesNotContain("incidentes-chart-andamento");
        assertThat(result.uiCompositionPlan().path("bindings").toString())
                .doesNotContain("incidentes-chart-andamento");
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"andamento\",\"label\":\"Andamento\",\"provenance\":\"llm-authored-semantic-axis\",\"schemaVerified\":false,\"schemaProbeStatus\":\"unsupported\"");
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
                .isEqualTo(3);
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
        assertThat(result.warnings()).contains("semantic-axis-schema-verification-unsupported-axis");
        assertThat(result.uiCompositionPlan().path("widgets").findValuesAsText("componentId"))
                .containsExactly(
                        "praxis-rich-content",
                        "praxis-filter",
                        "praxis-chart",
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
                .contains("\"template\":{\"filters\":\"${payload}\"}")
                .contains("incidentes-chart-gravidade.pointClick->incidentes-table.queryContext")
                .contains("incidentes-chart-gravidade.crossFilter->incidentes-table.queryContext")
                .doesNotContain("incidentes-chart-andamento")
                .doesNotContain("incidentes-chart-responsavel")
                .doesNotContain("andamento")
                .doesNotContain("responsavel");
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
        assertThat(result.uiCompositionPlan().path("diagnostics").path("semanticAxes").toString())
                .contains("\"field\":\"severidade\"")
                .contains("\"schemaProbeStatus\":\"unsupported\"");
        assertThat(result.failureCodes()).contains("semantic-preview-axis-schema-verification-required");
        assertThat(result.assistantMessage())
                .contains("campos seguros para alguns graficos")
                .contains("manter a proposta em revisao")
                .doesNotContain("propriedades incompativeis")
                .doesNotContain("cards ricos");
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
                .doesNotContain("\"dimensionField\":\"mes\"")
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
                .contains("semantic-chart-metric-aggregation-repaired-with-schema-field");
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
                .contains("semantic-chart-axis-repaired-with-prompt-aligned-schema-field")
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
        AtomicReference<AiSchemaContext> capturedContext = new AtomicReference<>();
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenAnswer(invocation -> {
                    capturedContext.set(invocation.getArgument(0));
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
        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().getPath()).isEqualTo("/api/human-resources/funcionarios/filter/cursor");
        assertThat(capturedContext.get().getOperation()).isEqualTo("post");
        assertThat(capturedContext.get().getSchemaType()).isEqualTo("response");
        String plan = result.uiCompositionPlan().toString();
        assertThat(plan)
                .contains("\"requestedField\":\"departamento\"")
                .contains("\"field\":\"departamentoNome\"")
                .contains("\"schemaVerified\":true")
                .contains("\"statsPath\":\"/api/human-resources/funcionarios/stats/group-by\"")
                .contains("\"selectedFieldIds\":[\"departamentoNome\"]")
                .doesNotContain("\"dimensionField\":\"departamentoNome\"");
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
        when(messageSynthesizer.synthesize(
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
                .thenReturn("Usei a fonte Ranking reputacao para montar a pre-visualizacao. A tabela esta conectada ao recurso e voce ja pode revisar, pedir um grafico ou salvar.");

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
}
