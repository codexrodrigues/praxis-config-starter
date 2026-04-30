package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        projectKnowledge.put("influenceCount", 2);
        ArrayNode entries = projectKnowledge.putArray("entries");
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
                "test-key");
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
                .contains("Criei uma pre-visualizacao usando \"Ranking reputacao\" como fonte de dados");
        assertThat(result.assistantMessage())
                .contains("A tabela foi conectada ao recurso e ja pode carregar schema/dados");
        assertThat(result.warnings()).contains(
                "ui-composition-plan-provider:selected-resource-dashboard",
                "compiled-form-patch-materialized-by-page-builder");
        assertThat(result.diagnostics().fieldScopeDecision()).isEqualTo("accepted-create");
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
                eq(true),
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

    private AgenticAuthoringIntentResolutionResult payrollTableIntent(String effectivePrompt) {
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
                        List.of("payroll")),
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
}
