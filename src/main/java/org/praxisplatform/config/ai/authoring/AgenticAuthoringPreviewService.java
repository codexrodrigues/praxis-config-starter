package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AgenticAuthoringPreviewService {

    private final AgenticAuthoringPlanService planService;
    private final AgenticAuthoringPatchCompilerService patchCompilerService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringIntentResolutionContext intentResolutionContext;
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator;
    private final List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders;
    private final AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer;

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService) {
        this(planService, patchCompilerService, new ObjectMapper(), List.of());
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper) {
        this(planService, patchCompilerService, objectMapper, List.of());
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders) {
        this(planService, patchCompilerService, objectMapper, uiCompositionPlanProviders, null);
    }

    public AgenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            List<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            AgenticAuthoringPreviewMessageSynthesizerService messageSynthesizer) {
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.patchCompilerService = Objects.requireNonNull(patchCompilerService, "patchCompilerService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.intentResolutionContext = new AgenticAuthoringIntentResolutionContext(this.objectMapper);
        this.conversationTurnOrchestrator = new AgenticAuthoringConversationTurnOrchestrator();
        this.uiCompositionPlanProviders = List.copyOf(
                uiCompositionPlanProviders == null ? List.of() : uiCompositionPlanProviders);
        this.messageSynthesizer = messageSynthesizer;
    }

    public AgenticAuthoringPreviewResult preview(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) throws IOException {
        AgenticAuthoringPlanRequest effectiveRequest = enrichRequest(request);
        Optional<AgenticAuthoringPreviewResult> uiCompositionPreview =
                previewUiCompositionPlan(effectiveRequest, tenantId, userId, environment);
        if (uiCompositionPreview.isPresent()) {
            return uiCompositionPreview.get();
        }
        AgenticAuthoringIntentResolutionResult intentResolution =
                effectiveRequest == null ? null : effectiveRequest.intentResolution();
        List<String> intentFailures = validateIntentResolution(intentResolution);
        if (!intentFailures.isEmpty()) {
            List<String> warnings = new ArrayList<>();
            if (intentResolution != null) {
                warnings.addAll(intentResolution.warnings());
            }
            warnings.add("preview-skipped-invalid-intent-resolution");
            return new AgenticAuthoringPreviewResult(
                    false,
                    List.copyOf(intentFailures),
                    List.copyOf(warnings),
                    MissingNode.getInstance(),
                    MissingNode.getInstance(),
                    diagnostics(intentResolution, List.copyOf(intentFailures), List.copyOf(warnings))
            );
        }
        AgenticAuthoringPlanResult planResult =
                planService.generateMinimalFormPlan(effectiveRequest, tenantId, userId, environment);
        List<String> failureCodes = new ArrayList<>(planResult.failureCodes());
        List<String> warnings = new ArrayList<>(planResult.warnings());
        if (!planResult.valid()) {
            warnings.add("compile-skipped-invalid-minimal-form-plan");
            return new AgenticAuthoringPreviewResult(
                    false,
                    List.copyOf(failureCodes),
                    List.copyOf(warnings),
                    planResult.minimalFormPlan(),
                    MissingNode.getInstance(),
                    diagnostics(intentResolution, List.copyOf(failureCodes), List.copyOf(warnings))
            );
        }

        AgenticAuthoringCompileResult compileResult =
                patchCompilerService.compile(new AgenticAuthoringCompileRequest(
                        planResult.minimalFormPlan(),
                        effectiveRequest == null ? null : effectiveRequest.currentPage(),
                        intentResolution));
        failureCodes.addAll(compileResult.failureCodes());
        warnings.addAll(compileResult.warnings());
        boolean valid = planResult.valid() && compileResult.valid();
        String fallbackMessage = deterministicPreviewAssistantMessage(intentResolution, null, valid);
        return new AgenticAuthoringPreviewResult(
                valid,
                List.copyOf(failureCodes),
                List.copyOf(warnings),
                planResult.minimalFormPlan(),
                compileResult.compiledFormPatch(),
                diagnostics(intentResolution, List.copyOf(failureCodes), List.copyOf(warnings)),
                null,
                previewAssistantMessage(
                        effectiveRequest,
                        intentResolution,
                        null,
                        valid,
                        List.copyOf(failureCodes),
                        List.copyOf(warnings),
                        fallbackMessage,
                        tenantId,
                        userId,
                        environment)
        );
    }

    private Optional<AgenticAuthoringPreviewResult> previewUiCompositionPlan(
            AgenticAuthoringPlanRequest request,
            String tenantId,
            String userId,
            String environment) {
        if (request == null) {
            return Optional.empty();
        }
        for (AgenticAuthoringUiCompositionPlanProvider provider : uiCompositionPlanProviders) {
            Optional<AgenticAuthoringUiCompositionPlanResult> result = provider.plan(request);
            if (result.isEmpty()) {
                continue;
            }
            AgenticAuthoringUiCompositionPlanResult planResult = result.get();
            List<String> failureCodes = planResult.failureCodes() == null ? List.of() : List.copyOf(planResult.failureCodes());
            List<String> warnings = new ArrayList<>(
                    planResult.warnings() == null ? List.of() : planResult.warnings());
            warnings.add("compiled-form-patch-materialized-by-page-builder");
            String fallbackMessage = deterministicPreviewAssistantMessage(
                    request.intentResolution(),
                    planResult.uiCompositionPlan(),
                    planResult.valid());
            return Optional.of(new AgenticAuthoringPreviewResult(
                    planResult.valid(),
                    failureCodes,
                    List.copyOf(warnings),
                    MissingNode.getInstance(),
                    planResult.compiledFormPatch() == null ? MissingNode.getInstance() : planResult.compiledFormPatch(),
                    diagnostics(request.intentResolution(), failureCodes, List.copyOf(warnings)),
                    planResult.uiCompositionPlan(),
                    previewAssistantMessage(
                            request,
                            request.intentResolution(),
                            planResult.uiCompositionPlan(),
                            planResult.valid(),
                            failureCodes,
                            List.copyOf(warnings),
                            fallbackMessage,
                            tenantId,
                            userId,
                            environment)
            ));
        }
        return Optional.empty();
    }

    private String previewAssistantMessage(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid,
            List<String> failureCodes,
            List<String> warnings,
            String fallbackMessage,
            String tenantId,
            String userId,
            String environment) {
        if (messageSynthesizer == null) {
            return fallbackMessage;
        }
        return messageSynthesizer.synthesize(
                request,
                intentResolution,
                uiCompositionPlan,
                valid,
                failureCodes,
                warnings,
                fallbackMessage,
                tenantId,
                userId,
                environment);
    }

    private String deterministicPreviewAssistantMessage(
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode uiCompositionPlan,
            boolean valid) {
        if (!valid) {
            return "Encontrei a fonte de dados, mas o plano gerado usou propriedades incompativeis com o componente de tabela. Vou ajustar para usar apenas os campos suportados.";
        }
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        if (candidate == null || value(candidate.resourcePath()).isBlank()) {
            return "";
        }
        String resourceLabel = titleFromResourcePath(candidate.resourcePath());
        if (containsComponent(uiCompositionPlan, "praxis-table")) {
            return "Criei uma pre-visualizacao usando \"" + resourceLabel
                    + "\" como fonte de dados. A tabela foi conectada ao recurso e ja pode carregar schema/dados. "
                    + "Voce pode revisar as colunas, pedir um grafico ou salvar a pagina.";
        }
        return "Criei uma pre-visualizacao usando \"" + resourceLabel
                + "\" como fonte de dados. Revise o resultado e salve a pagina quando estiver de acordo.";
    }

    private boolean containsComponent(JsonNode uiCompositionPlan, String componentId) {
        JsonNode widgets = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return false;
        }
        for (JsonNode widget : widgets) {
            if (componentId.equals(widget.path("componentId").asText())) {
                return true;
            }
        }
        return false;
    }

    private String titleFromResourcePath(String resourcePath) {
        String value = value(resourcePath);
        if (value.isBlank()) {
            return "o recurso selecionado";
        }
        String lastSegment = value.substring(value.lastIndexOf('/') + 1)
                .replace("vw-", "")
                .replace('-', ' ')
                .trim();
        if (lastSegment.isBlank()) {
            return "o recurso selecionado";
        }
        return Character.toUpperCase(lastSegment.charAt(0)) + lastSegment.substring(1);
    }

    private AgenticAuthoringPlanRequest enrichRequest(AgenticAuthoringPlanRequest request) {
        if (request == null) {
            return null;
        }
        request = withEffectivePrompt(request);
        AgenticAuthoringIntentResolutionResult enrichedIntent =
                intentResolutionContext.enrich(request.intentResolution(), request.currentPage());
        if (enrichedIntent == request.intentResolution()) {
            return request;
        }
        return new AgenticAuthoringPlanRequest(
                request.userPrompt(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                enrichedIntent,
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                attachmentSummaries(request),
                request.contextHints());
    }

    private AgenticAuthoringPlanRequest withEffectivePrompt(AgenticAuthoringPlanRequest request) {
        String intentEffectivePrompt = request.intentResolution() == null
                ? ""
                : value(request.intentResolution().effectivePrompt()).trim();
        if (!intentEffectivePrompt.isBlank()) {
            if (Objects.equals(intentEffectivePrompt, request.userPrompt())) {
                return request;
            }
            return withUserPrompt(request, intentEffectivePrompt);
        }
        AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                request.userPrompt(),
                request.conversationMessages(),
                request.pendingClarification());
        String effectivePrompt = turn.effectivePrompt();
        if (Objects.equals(effectivePrompt, request.userPrompt())) {
            return request;
        }
        return withUserPrompt(request, effectivePrompt);
    }

    private AgenticAuthoringPlanRequest withUserPrompt(AgenticAuthoringPlanRequest request, String userPrompt) {
        return new AgenticAuthoringPlanRequest(
                userPrompt,
                request.provider(),
                request.model(),
                request.apiKey(),
                request.currentPage(),
                request.intentResolution(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                attachmentSummaries(request),
                request.contextHints());
    }

    private List<AgenticAuthoringAttachmentSummary> attachmentSummaries(AgenticAuthoringPlanRequest request) {
        if (request.attachmentSummaries() != null && !request.attachmentSummaries().isEmpty()) {
            return request.attachmentSummaries();
        }
        JsonNode diagnostics = request.pendingClarification() == null
                ? null
                : request.pendingClarification().diagnostics();
        JsonNode summaries = diagnostics == null ? null : diagnostics.path("attachmentSummaries");
        if (summaries == null || !summaries.isArray() || summaries.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(
                    summaries,
                    new TypeReference<List<AgenticAuthoringAttachmentSummary>>() {
                    });
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private AgenticAuthoringPreviewDiagnostics diagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            List<String> failureCodes,
            List<String> warnings) {
        if (intentResolution == null) {
            return new AgenticAuthoringPreviewDiagnostics(false, "", "", "", "not-evaluated");
        }
        String targetWidgetKey = intentResolution.target() == null ? "" : value(intentResolution.target().widgetKey());
        boolean derived = warnings.contains("current-page-summary-derived")
                || (intentResolution.warnings() != null && intentResolution.warnings().contains("current-page-summary-derived"));
        return new AgenticAuthoringPreviewDiagnostics(
                derived,
                targetWidgetKey,
                value(intentResolution.operationKind()),
                value(intentResolution.changeKind()),
                fieldScopeDecision(intentResolution, failureCodes));
    }

    private String fieldScopeDecision(AgenticAuthoringIntentResolutionResult intentResolution, List<String> failureCodes) {
        if (failureCodes.stream().anyMatch(code -> code.startsWith("add_field duplicates existing field: "))) {
            return "rejected-duplicate-field";
        }
        if (failureCodes.stream().anyMatch(code -> code.startsWith("remove_field requires current local/transient field: ")
                || code.startsWith("remove_field requires local/transient field: "))) {
            return "rejected-non-local-field-removal";
        }
        if ("modify".equals(intentResolution.operationKind()) && "add_field".equals(intentResolution.changeKind())) {
            return "accepted-add-local-field";
        }
        if ("remove".equals(intentResolution.operationKind()) && "remove_field".equals(intentResolution.changeKind())) {
            return "accepted-remove-local-field";
        }
        if ("modify".equals(intentResolution.operationKind()) && "rename_or_relabel".equals(intentResolution.changeKind())) {
            return "accepted-relabel-server-backed-field";
        }
        if ("create".equals(intentResolution.operationKind())) {
            return "accepted-create";
        }
        return "not-evaluated";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private List<String> validateIntentResolution(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        if (!intentResolution.valid()) {
            failures.add("intent-resolution-invalid");
        }
        if (intentResolution.gate() == null || !"eligible".equals(intentResolution.gate().status())) {
            failures.add("intent-resolution-not-eligible");
        }
        if (intentResolution.selectedCandidate() == null) {
            failures.add("intent-resolution-selected-candidate-required");
        }
        if (!"create".equals(intentResolution.operationKind())
                && !"modify".equals(intentResolution.operationKind())
                && !"remove".equals(intentResolution.operationKind())) {
            failures.add("intent-resolution-operation-must-be-create-modify-or-remove");
        }
        if (!"form".equals(intentResolution.artifactKind())) {
            failures.add("intent-resolution-artifact-must-be-form");
        }
        return List.copyOf(failures);
    }
}
