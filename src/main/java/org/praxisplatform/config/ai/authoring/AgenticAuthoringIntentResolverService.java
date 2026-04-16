package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class AgenticAuthoringIntentResolverService {

    private static final String DEFAULT_TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private static final String PAYROLL_ANALYTICS = "/api/human-resources/vw-analytics-folha-pagamento";
    private static final String PAYROLL = "/api/human-resources/folhas-pagamento";

    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringCandidateEligibilityGate eligibilityGate;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringApiCatalogConversationService apiCatalogConversationService;
    private final AgenticAuthoringLlmIntentResolverService llmIntentResolverService;
    private final AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringFormCapabilityCatalog formCapabilityCatalog = AgenticAuthoringFormCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringTableCapabilityCatalog tableCapabilityCatalog = AgenticAuthoringTableCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringChartCapabilityCatalog chartCapabilityCatalog = AgenticAuthoringChartCapabilityCatalog.INSTANCE;
    private final AgenticAuthoringConversationTurnOrchestrator conversationTurnOrchestrator =
            new AgenticAuthoringConversationTurnOrchestrator();

    public AgenticAuthoringIntentResolverService(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog) {
        this(objectMapper, apiMetadataCandidateCatalog, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService) {
        this(objectMapper, apiMetadataCandidateCatalog, apiCatalogConversationService, null, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService,
            AgenticAuthoringLlmIntentResolverService llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.apiCatalogConversationService = apiCatalogConversationService;
        this.llmIntentResolverService = llmIntentResolverService;
        this.componentCapabilitiesService = componentCapabilitiesService;
    }

    public AgenticAuthoringIntentResolutionResult resolve(AgenticAuthoringIntentResolutionRequest request) {
        return resolve(request, null, null, null);
    }

    public AgenticAuthoringIntentResolutionResult resolve(
            AgenticAuthoringIntentResolutionRequest request,
            String tenantId,
            String userId,
            String environment) {
        if (request == null || request.userPrompt() == null || request.userPrompt().isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank.");
        }
        AgenticAuthoringConversationTurn turn = conversationTurnOrchestrator.resolve(
                request.userPrompt(),
                request.conversationMessages(),
                request.pendingClarification());
        String rawPrompt = request.userPrompt().trim();
        String effectivePrompt = turn.effectivePrompt();
        String prompt = normalize(effectivePrompt);
        JsonNode currentPageSummary = currentPageAnalyzer.summarize(request.currentPage());
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(request.currentPage(), request.selectedWidgetKey());
        String operationKind = resolveOperationKind(prompt);
        String artifactKind = resolveArtifactKind(prompt, currentPageSummary, target);
        String changeKind = resolveChangeKind(prompt, operationKind, artifactKind);
        List<AgenticAuthoringCandidate> candidates = discoverCandidates(prompt, artifactKind, target);
        AgenticAuthoringComponentCapabilitiesResult componentCapabilities = componentCapabilities();
        AgenticAuthoringLlmIntentResolution llmIntent = resolveLlmIntent(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities,
                tenantId,
                userId,
                environment);
        JsonNode llmDiagnostics = llmDiagnostics(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities);
        boolean llmTreatsPendingAsNewInstruction = turn.answeredPendingClarification()
                && isLlmFollowUpKind(llmIntent, "new_instruction");
        if (llmTreatsPendingAsNewInstruction) {
            effectivePrompt = rawPrompt;
            prompt = normalize(effectivePrompt);
            operationKind = resolveOperationKind(prompt);
            artifactKind = resolveArtifactKind(prompt, currentPageSummary, target);
            changeKind = resolveChangeKind(prompt, operationKind, artifactKind);
            candidates = discoverCandidates(prompt, artifactKind, target);
        }
        if (llmIntent != null) {
            operationKind = valueOrDefault(llmIntent.operationKind(), operationKind);
            artifactKind = valueOrDefault(llmIntent.artifactKind(), artifactKind);
            changeKind = valueOrDefault(llmIntent.changeKind(), changeKind);
            if (candidates.isEmpty()) {
                candidates = discoverCandidates(prompt, artifactKind, target);
            }
        }
        AgenticAuthoringCandidate selectedCandidate = selectCandidate(candidates, target, artifactKind);
        selectedCandidate = selectLlmCandidate(llmIntent, candidates, selectedCandidate);
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                changeKind,
                target,
                selectedCandidate,
                candidates);
        gate = withPromptSpecificGateMessages(gate, prompt, operationKind, artifactKind, selectedCandidate);
        List<String> questions = clarificationQuestions(gate, operationKind, artifactKind, selectedCandidate, candidates);
        boolean answeredBareDomainClarification = turn.answeredPendingClarification()
                && !llmTreatsPendingAsNewInstruction
                && isBareDomainPrompt(turn.sourcePrompt());
        String assistantMessage = assistantMessage(
                prompt,
                operationKind,
                artifactKind,
                selectedCandidate,
                candidates,
                gate,
                answeredBareDomainClarification);
        if (llmIntent != null && llmIntent.assistantMessage() != null && !llmIntent.assistantMessage().isBlank()) {
            assistantMessage = llmIntent.assistantMessage();
        }
        JsonNode apiCatalogAnswer = apiCatalogAnswer(prompt, operationKind, artifactKind, selectedCandidate, candidates);
        AgenticAuthoringPendingClarification pendingClarification =
                pendingClarification(
                        effectivePrompt,
                        gate,
                        assistantMessage,
                        questions,
                        request.clientTurnId(),
                        request.attachmentSummaries());
        List<AgenticAuthoringQuickReply> quickReplies = quickReplies(
                effectivePrompt,
                prompt,
                operationKind,
                artifactKind,
                selectedCandidate,
                gate,
                questions,
                candidates,
                answeredBareDomainClarification);
        if (llmIntent != null && llmIntent.quickReplies() != null && !llmIntent.quickReplies().isEmpty()) {
            quickReplies = llmIntent.quickReplies();
        }
        List<String> warnings = warnings(llmIntent);
        if (llmTreatsPendingAsNewInstruction) {
            warnings = withWarning(warnings, "llm-follow-up-kind-new-instruction");
        }
        return new AgenticAuthoringIntentResolutionResult(
                "eligible".equals(gate.status()),
                operationKind,
                artifactKind,
                changeKind,
                authoringProfile(operationKind, artifactKind),
                valueOrDefault(request.targetApp(), ""),
                valueOrDefault(request.targetComponentId(), DEFAULT_TARGET_COMPONENT),
                target,
                selectedCandidate,
                candidates,
                gate,
                effectivePrompt,
                assistantMessage,
                apiCatalogAnswer,
                quickReplies,
                pendingClarification,
                questions,
                warnings,
                gate.messages(),
                currentPageSummary,
                llmDiagnostics
        );
    }

    private AgenticAuthoringLlmIntentResolution resolveLlmIntent(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (llmIntentResolverService == null) {
            return null;
        }
        return llmIntentResolverService.resolve(
                        request,
                        effectivePrompt,
                        currentPageSummary,
                        target,
                        candidates,
                        componentCapabilities,
                        tenantId,
                        userId,
                        environment)
                .orElse(null);
    }

    private AgenticAuthoringComponentCapabilitiesResult componentCapabilities() {
        return componentCapabilitiesService == null
                ? new AgenticAuthoringComponentCapabilitiesService().listCapabilities()
                : componentCapabilitiesService.listCapabilities();
    }

    private JsonNode llmDiagnostics(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (!includeLlmDiagnostics(request)) {
            return null;
        }
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-intent-llm-diagnostics.v1");
        diagnostics.put("enabled", llmIntentResolverService != null);
        if (llmIntentResolverService == null) {
            diagnostics.put("reason", "llm-intent-resolver-unavailable");
            return diagnostics;
        }
        diagnostics.set("request", llmIntentResolverService.diagnosticSnapshot(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidates,
                componentCapabilities));
        return diagnostics;
    }

    private boolean includeLlmDiagnostics(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        return contextHints != null && contextHints.path("includeLlmDiagnostics").asBoolean(false);
    }

    private AgenticAuthoringCandidate selectLlmCandidate(
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return fallback;
        }
        return candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(fallback);
    }

    private List<String> warnings(AgenticAuthoringLlmIntentResolution llmIntent) {
        List<String> warnings = new ArrayList<>();
        warnings.add("metadata-probe-not-run");
        if (llmIntent == null) {
            warnings.add("llm-intent-resolution-fallback-deterministic");
        } else {
            warnings.add("llm-intent-resolution-used");
            if (llmIntent.warnings() != null) {
                warnings.addAll(llmIntent.warnings());
            }
        }
        return List.copyOf(warnings);
    }

    private List<String> withWarning(List<String> warnings, String warning) {
        List<String> next = new ArrayList<>(warnings == null ? List.of() : warnings);
        next.add(warning);
        return List.copyOf(next);
    }

    private boolean isLlmFollowUpKind(AgenticAuthoringLlmIntentResolution llmIntent, String expected) {
        if (llmIntent == null || llmIntent.followUpKind() == null || expected == null) {
            return false;
        }
        return expected.equals(normalize(llmIntent.followUpKind()));
    }

    private AgenticAuthoringPendingClarification pendingClarification(
            String effectivePrompt,
            AgenticAuthoringGateResult gate,
            String assistantMessage,
            List<String> questions,
            String clientTurnId,
            List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        if (gate == null || !"clarification_required".equals(gate.status()) || questions == null || questions.isEmpty()) {
            return null;
        }
        String prompt = valueOrDefault(effectivePrompt, "").trim();
        if (prompt.isBlank()) {
            return null;
        }
        String message = valueOrDefault(assistantMessage, "").trim();
        if (message.isBlank()) {
            message = questions.get(0);
        }
        return new AgenticAuthoringPendingClarification(
                prompt,
                List.copyOf(questions),
                message,
                clientTurnId,
                pendingClarificationDiagnostics(attachmentSummaries));
    }

    private JsonNode pendingClarificationDiagnostics(List<AgenticAuthoringAttachmentSummary> attachmentSummaries) {
        if (attachmentSummaries == null || attachmentSummaries.isEmpty()) {
            return null;
        }
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.set("attachmentSummaries", objectMapper.valueToTree(attachmentSummaries));
        return diagnostics;
    }

    private String resolveOperationKind(String prompt) {
        if (isApiCatalogQuestion(prompt)) {
            return "explore";
        }
        if (containsAny(prompt, "conectar", "ligar", "vincular", "relacionar")) {
            return "connect";
        }
        if (containsAny(prompt, "remover", "remova", "remove", "excluir", "exclua", "apagar", "apague", "retirar", "retire")) {
            return "remove";
        }
        if (containsAny(prompt, "alterar", "altere", "mudar", "mude", "trocar", "troque",
                "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente",
                "dividir", "divida", "renomear", "renomeie", "formatar", "formate", "formatacao")) {
            return "modify";
        }
        if (isExplicitCreateConfirmation(prompt)) {
            return "create";
        }
        if (isConsultativePrompt(prompt)) {
            return "explore";
        }
        if (containsAny(prompt, "criar", "crie", "gerar", "gere", "montar", "monte",
                "construir", "construa", "novo", "nova", "cadastrar", "abrir")) {
            return "create";
        }
        if (tableCapabilityCatalog.matchesAnyModificationPrompt(prompt)) {
            return "modify";
        }
        if (chartCapabilityCatalog.matchesAnyModificationPrompt(prompt)) {
            return "modify";
        }
        if (formCapabilityCatalog.matchesAnyModificationPrompt(prompt)) {
            return "modify";
        }
        if (containsAny(prompt, "explicar", "explique", "porque", "por que")) {
            return "explain";
        }
        return "unknown";
    }

    private boolean isExplicitCreateConfirmation(String prompt) {
        return containsAny(prompt, "sim, crie", "sim crie", "pode criar", "confirmo criar", "confirmo, crie");
    }

    private boolean isConsultativePrompt(String prompt) {
        return containsAny(prompt, "melhor forma", "como visualizar", "visualizar informacoes",
                "visualizar informacao", "visualizar", "analisar", "analise", "explorar", "explore",
                "me ajude", "ajude", "escolher", "sugerir", "sugira", "como ver",
                "opcao", "opcoes", "alternativa", "alternativas", "indicar", "indique", "voce indica", "me indica",
                "recomendar", "recomende", "recomendacao", "recomendacoes", "comparar", "compare",
                "comparativo", "orientar", "oriente", "me oriente", "faz mais sentido", "devo usar",
                "ver", "visao", "mostrar", "mostre");
    }

    private boolean isApiCatalogQuestion(String prompt) {
        if (!containsApiCatalogSubject(prompt)) {
            return false;
        }
        return containsAny(prompt,
                "qual", "quais", "que api", "que apis", "que endpoint", "que endpoints",
                "que schema", "que schemas", "o que", "essa", "esse", "esta", "este", "existe", "existem",
                "listar", "liste", "mostrar", "mostre", "consultar", "consulta", "devo usar",
                "usar para", "campos existem", "suporta", "permite", "relacionad", "complement",
                "combinar", "combine", "vincul");
    }

    private boolean containsApiCatalogSubject(String prompt) {
        String wordPadded = " " + prompt.replaceAll("[^a-z0-9]+", " ") + " ";
        if (wordPadded.contains(" api ") || wordPadded.contains(" apis ")
                || wordPadded.contains(" acao ") || wordPadded.contains(" acoes ")
                || wordPadded.contains(" action ") || wordPadded.contains(" actions ")) {
            return true;
        }
        return containsAny(prompt, "endpoint", "endpoints", "schema", "schemas", "filtro", "filtros", "filtrar");
    }

    private boolean isPayrollAnalyticsPrompt(String prompt) {
        return containsAny(prompt, "folha", "pagamento", "pagamentos", "salario", "salarios",
                "departamento", "departamentos");
    }

    private boolean isBareDomainPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (!isPayrollAnalyticsPrompt(normalized)) {
            return false;
        }
        String[] tokens = normalized.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        return tokens.length <= 2;
    }

    private String resolveArtifactKind(String prompt, JsonNode currentPageSummary, AgenticAuthoringTarget target) {
        if (isApiCatalogQuestion(prompt)) {
            return "api_catalog";
        }
        if (prefersPayrollDashboardRecommendation(prompt)) {
            return "dashboard";
        }
        if (containsAny(prompt, "formulario", "form", "campo", "campos", "cadastrar", "cadastro", "abrir chamado")) {
            return "form";
        }
        if (target != null && "praxis-dynamic-form".equals(target.componentId())
                && ("modify".equals(resolveOperationKind(prompt))
                || "remove".equals(resolveOperationKind(prompt))
                || formCapabilityCatalog.matchesAnyModificationPrompt(prompt))) {
            return "form";
        }
        if (target != null && "praxis-table".equals(target.componentId())
                && ("modify".equals(resolveOperationKind(prompt)) || tableCapabilityCatalog.matchesAnyModificationPrompt(prompt))) {
            return "table";
        }
        if (target != null && "praxis-chart".equals(target.componentId())
                && ("modify".equals(resolveOperationKind(prompt)) || chartCapabilityCatalog.matchesAnyModificationPrompt(prompt))) {
            return "dashboard";
        }
        if (isTablePrompt(prompt)) {
            return "table";
        }
        if (containsAny(prompt, "dashboard", "painel", "grafico", "graficos", "chart", "charts",
                "indicador", "indicadores", "drill down", "drill-down", "drilldown",
                "visualizar informacoes", "visualizar informacao", "folha de pagamento")) {
            return "dashboard";
        }
        if (isDashboardWidgetAdditionPrompt(prompt) && isPayrollAnalyticsPrompt(prompt)) {
            return "dashboard";
        }
        if (prefersPayrollDashboardRecommendation(prompt)) {
            return "dashboard";
        }
        if (containsAny(prompt, "stepper", "etapa", "etapas", "passo", "passos")) {
            return "stepper";
        }
        if (currentPageSummary.path("formWidgets").isArray() && !currentPageSummary.path("formWidgets").isEmpty()) {
            return "form";
        }
        return "unknown";
    }

    private String resolveChangeKind(String prompt, String operationKind, String artifactKind) {
        if ("connect".equals(operationKind)) {
            return "connect_widgets";
        }
        if ("remove".equals(operationKind) && containsAny(prompt, "campo", "campos")) {
            return formCapabilityCatalog.resolveChangeKind(prompt).orElse("remove_field");
        }
        if ("modify".equals(operationKind) && "form".equals(artifactKind)) {
            Optional<String> formChangeKind = formCapabilityCatalog.resolveChangeKind(prompt);
            if (formChangeKind.isPresent()) {
                return formChangeKind.orElseThrow();
            }
        }
        if ("modify".equals(operationKind) && "table".equals(artifactKind)) {
            Optional<String> tableChangeKind = tableCapabilityCatalog.resolveChangeKind(prompt);
            if (tableChangeKind.isPresent()) {
                return tableChangeKind.orElseThrow();
            }
        }
        if ("modify".equals(operationKind) && "dashboard".equals(artifactKind)) {
            if (isDashboardWidgetAdditionPrompt(prompt)) {
                return "add_dashboard_widget";
            }
            Optional<String> chartChangeKind = chartCapabilityCatalog.resolveChangeKind(prompt);
            if (chartChangeKind.isPresent()) {
                return chartChangeKind.orElseThrow();
            }
        }
        if ("modify".equals(operationKind) && containsAny(prompt,
                "renomear", "renomeie", "label", "rotulo", "titulo")) {
            return "rename_or_relabel";
        }
        if ("modify".equals(operationKind) && containsAny(prompt, "adicionar", "adicione",
                "incluir", "inclua", "acrescentar", "acrescente", "campo", "campos")) {
            return "add_field";
        }
        if ("modify".equals(operationKind) && containsAny(prompt, "etapa", "etapas", "passo", "passos", "dividir")) {
            return "split_into_steps";
        }
        if ("create".equals(operationKind) && "form".equals(artifactKind)) {
            return "create_minimal_form";
        }
        if ("create".equals(operationKind) && "dashboard".equals(artifactKind)
                && containsAny(prompt, "drill down", "drill-down", "drilldown", "detalhar", "detalhe", "aprofundar")) {
            return "create_chart_drilldown";
        }
        if ("create".equals(operationKind)) {
            return "create_artifact";
        }
        if ("explore".equals(operationKind) && "dashboard".equals(artifactKind)) {
            return "recommend_dashboard_visualization";
        }
        if ("explore".equals(operationKind) && "table".equals(artifactKind)) {
            return "recommend_table_visualization";
        }
        if ("explore".equals(operationKind) && "api_catalog".equals(artifactKind)) {
            return "answer_api_catalog_question";
        }
        return "unknown";
    }

    private boolean isDashboardWidgetAdditionPrompt(String prompt) {
        return containsAny(prompt, "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente")
                && containsAny(prompt, "widget", "componente", "resumo", "executivo", "kpi", "indicador", "indicadores");
    }

    private List<AgenticAuthoringCandidate> discoverCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
            String operation = target.submitMethod() == null || target.submitMethod().isBlank()
                    ? "post"
                    : target.submitMethod();
            candidates.add(candidate(
                    target.resourcePath(),
                    operation,
                    0.95d,
                    "resource resolved from current target widget",
                    "current-page"));
        }
        List<AgenticAuthoringCandidate> metadataCandidates = apiMetadataCandidateCatalog == null
                ? List.of()
                : apiMetadataCandidateCatalog.discover(prompt, artifactKind);
        if (metadataCandidates.isEmpty() && apiMetadataCandidateCatalog != null) {
            metadataCandidates = apiMetadataCandidateCatalog.discover("", artifactKind);
        }
        if (!metadataCandidates.isEmpty()) {
            candidates.addAll(metadataCandidates);
        }
        candidates.addAll(discoverKnownCandidates(prompt));
        return deduplicateCandidates(candidates);
    }

    private List<AgenticAuthoringCandidate> discoverKnownCandidates(String prompt) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (containsAny(prompt, "funcionario", "funcionarios", "colaborador", "colaboradores")
                || (containsAny(prompt, "rh", "human resources") && !isPayrollAnalyticsPrompt(prompt))) {
            candidates.add(candidate("/api/human-resources/funcionarios", 0.90d, "prompt mentions funcionarios/colaboradores", "known-quickstart-resource"));
        }
        if (isPayrollAnalyticsPrompt(prompt) && isDashboardWidgetAdditionPrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt asks to add a payroll analytics dashboard widget",
                    "known-quickstart-analytics-view"));
        } else if (prefersPayrollDashboardRecommendation(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt asks for payroll dashboard recommendations",
                    "known-quickstart-analytics-view"));
        } else if (isPayrollAnalyticsPrompt(prompt) && isTablePrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL,
                    PAYROLL + "/all",
                    "get",
                    0.94d,
                    "prompt asks for operational payroll table/listing",
                    "known-quickstart-resource"));
        } else if (isPayrollAnalyticsPrompt(prompt)
                && (isConsultativePrompt(prompt)
                || containsAny(prompt, "chart", "grafico", "dashboard", "painel", "drill down", "drill-down",
                "drilldown", "visualizar", "mostrar", "mostre", "ver"))) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.94d,
                    "prompt mentions payroll analytics chart drill-down",
                    "known-quickstart-analytics-view"));
        } else if (isPayrollAnalyticsPrompt(prompt)) {
            candidates.add(candidate(
                    PAYROLL_ANALYTICS,
                    PAYROLL_ANALYTICS + "/stats/group-by",
                    "post",
                    0.78d,
                    "approximate payroll analytics endpoint match",
                    "known-quickstart-analytics-view"));
            candidates.add(candidate(
                    PAYROLL,
                    PAYROLL + "/all",
                    "get",
                    0.72d,
                    "approximate payroll collection endpoint match",
                    "known-quickstart-resource"));
        }
        return candidates;
    }

    private List<AgenticAuthoringCandidate> deduplicateCandidates(List<AgenticAuthoringCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                .collect(
                        java.util.stream.Collectors.toMap(
                                AgenticAuthoringCandidate::resourcePath,
                                candidate -> candidate,
                                (left, right) -> left.score() >= right.score() ? left : right,
                                java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private AgenticAuthoringCandidate selectCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringTarget target,
            String artifactKind) {
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
            return candidates.stream()
                    .filter(candidate -> target.resourcePath().equals(candidate.resourcePath()))
                    .findFirst()
                    .orElse(null);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (isBroadArtifactDiscoveryOnly(candidates)) {
            return null;
        }
        if (candidates.size() > 1 && candidates.get(0).score() - candidates.get(1).score() >= 0.08d) {
            return candidates.get(0);
        }
        if ("dashboard".equals(artifactKind) && !candidates.isEmpty()) {
            double bestScore = candidates.get(0).score();
            return candidates.stream()
                    .filter(candidate -> isAnalyticsResource(candidate.resourcePath()))
                    .filter(candidate -> bestScore - candidate.score() < 0.08d)
                    .findFirst()
                    .orElse(null);
        }
        if ("api_catalog".equals(artifactKind) && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        return null;
    }

    private boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && !candidates.isEmpty()
                && candidates.stream()
                .allMatch(candidate -> candidate.evidence() != null
                        && candidate.evidence().contains("broad-artifact-discovery"));
    }

    private boolean isAnalyticsResource(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.toLowerCase(Locale.ROOT);
        return normalized.contains("analytics") || normalized.contains("/vw-");
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, double score, String reason, String evidence) {
        return candidate(resourcePath, "post", score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, String operation, double score, String reason, String evidence) {
        return candidate(resourcePath, resourcePath, operation, score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(
            String resourcePath,
            String submitUrl,
            String operation,
            double score,
            String reason,
            String evidence) {
        String normalizedOperation = operation.toLowerCase(Locale.ROOT);
        String schemaType = "get".equalsIgnoreCase(operation) || isReadProjectionOperation(submitUrl, operation)
                ? "response"
                : "request";
        String schemaPath = "/schemas/filtered?path=" + submitUrl + "&operation=" + normalizedOperation
                + "&schemaType=" + schemaType;
        return new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaPath,
                submitUrl,
                operation,
                score,
                reason,
                List.of(evidence, "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending")
        );
    }

    private String authoringProfile(String operationKind, String artifactKind) {
        if ("api_catalog".equals(artifactKind)) {
            return "api-catalog-qa";
        }
        if ("form".equals(artifactKind) && ("create".equals(operationKind)
                || "modify".equals(operationKind)
                || "remove".equals(operationKind))) {
            return "create-minimal-form";
        }
        return "generic-page-change";
    }

    private AgenticAuthoringGateResult withPromptSpecificGateMessages(
            AgenticAuthoringGateResult gate,
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        List<String> messages = new ArrayList<>(gate.messages());
        if (requiresPayrollDashboardBreakdown(prompt, operationKind, artifactKind, selectedCandidate)) {
            String breakdownMessage = isPayrollDashboardAlternativeBreakdownAnswer(prompt)
                    ? "analytics-custom-breakdown-required"
                    : "analytics-breakdown-required";
            if (!messages.contains(breakdownMessage)) {
                messages.add(breakdownMessage);
            }
        }
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult(gate.gateId(), status, List.copyOf(messages));
    }

    private boolean requiresPayrollDashboardBreakdown(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return "create".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate != null
                && PAYROLL_ANALYTICS.equals(selectedCandidate.resourcePath())
                && !isExplicitCreateConfirmation(prompt)
                && !hasPayrollDashboardBreakdown(prompt);
    }

    private boolean hasPayrollDashboardBreakdown(String prompt) {
        return containsAny(prompt,
                "departamento", "departamentos", "competencia", "competencias", "mes", "mensal",
                "status", "perfil", "perfis", "cargo", "cargos", "equipe", "equipes", "base", "bases",
                "funcionario", "funcionarios",
                "drill down", "drill-down", "drilldown");
    }

    private boolean isPayrollDashboardAlternativeBreakdownAnswer(String prompt) {
        return containsAny(prompt, "outro", "outra", "outros", "outras");
    }

    private boolean isTablePrompt(String prompt) {
        return containsAny(prompt, "tabela", "grid", "lista", "listagem", "listar", "liste", "relacao");
    }

    private boolean prefersPayrollDashboardRecommendation(String prompt) {
        if (!isConsultativePrompt(prompt) || !isPayrollAnalyticsPrompt(prompt)) {
            return false;
        }
        if (!isTablePrompt(prompt)) {
            return true;
        }
        return containsAny(prompt,
                "dashboard", "dashboards", "painel", "grafico", "graficos", "chart", "charts",
                "indicador", "indicadores", "drill down", "drill-down", "drilldown", "cross filter",
                "analise", "analisar", "analitica", "opcao", "opcoes", "alternativa", "alternativas",
                "recomendacao", "recomendacoes", "compare", "comparar");
    }

    private List<String> clarificationQuestions(
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> questions = new ArrayList<>();
        for (String message : gate.messages()) {
            if ("resource-candidate-required".equals(message)) {
                questions.add("Qual recurso de negocio deve alimentar esta tela?");
            } else if ("resource-candidate-ambiguous".equals(message)) {
                String options = formatCandidateOptions(candidates);
                if (options.isBlank()) {
                    questions.add("Qual recurso candidato deve ser usado?");
                } else {
                    questions.add("Encontrei recursos proximos: " + options + ". Qual deles voce quer usar?");
                }
            } else if ("target-widget-required".equals(message)) {
                questions.add("Qual componente existente deve ser alterado?");
            } else if ("intent-operation-unknown".equals(message)) {
                questions.add("O que voce quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?");
            } else if ("intent-artifact-unknown".equals(message)) {
                questions.add("Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?");
            } else if ("intent-confirmation-required".equals(message)) {
                questions.add(confirmationQuestion(operationKind, artifactKind, selectedCandidate));
            } else if ("analytics-breakdown-required".equals(message)) {
                questions.add("Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?");
            } else if ("analytics-custom-breakdown-required".equals(message)) {
                questions.add("Qual outro recorte voce quer usar para o dashboard de folha de pagamento: cargo, equipe, base ou perfil?");
            }
        }
        return List.copyOf(questions);
    }

    private String confirmationQuestion(String operationKind, String artifactKind, AgenticAuthoringCandidate selectedCandidate) {
        if ("explore".equals(operationKind) && "table".equals(artifactKind)
                && selectedCandidate != null
                && PAYROLL.equals(selectedCandidate.resourcePath())) {
            return "Posso criar uma tabela operacional de folhas de pagamento usando /api/human-resources/folhas-pagamento?";
        }
        return "Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?";
    }

    private String assistantMessage(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringGateResult gate,
            boolean answeredBareDomainClarification) {
        if (gate != null && gate.messages().contains("resource-candidate-required")) {
            return missingResourceAssistantMessage(artifactKind);
        }
        if (!"explore".equals(operationKind)) {
            return null;
        }
        if ("api_catalog".equals(artifactKind)) {
            if (apiCatalogConversationService != null) {
                return apiCatalogConversationService.answer(prompt, selectedCandidate, candidates).assistantMessage();
            }
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (answeredBareDomainClarification) {
            return null;
        }
        if (isPayrollAnalyticsPrompt(prompt)) {
            return "Para folha de pagamento, as melhores opcoes sao: 1. dashboard executivo com KPIs e total da folha; "
                    + "2. drill-down por departamento com grafico filtrando uma tabela de detalhes; "
                    + "3. evolucao mensal para identificar tendencias de custo; "
                    + "4. tabela detalhada com filtros e valores monetarios formatados. Escolha uma opcao ou descreva o que quer criar.";
        }
        if ("table".equals(artifactKind)) {
            return "Posso ajudar a escolher antes de criar. Para uma tabela, normalmente faz sentido definir recurso, colunas principais, filtros, ordenacao e formato dos campos.";
        }
        return "Posso ajudar a escolher antes de criar. Opcoes comuns sao dashboards para analise, formularios para entrada de dados, paginas master-detail para navegacao e tabelas para detalhe operacional.";
    }

    private JsonNode apiCatalogAnswer(
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!"explore".equals(operationKind) || !"api_catalog".equals(artifactKind)
                || apiCatalogConversationService == null) {
            return null;
        }
        return apiCatalogConversationService.answer(prompt, selectedCandidate, candidates).apiCatalogAnswer();
    }

    private String apiCatalogAssistantMessage(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (usableCandidates.isEmpty()) {
            return "Nao encontrei APIs candidatas no catalogo para esse tema. Posso listar endpoints, schemas, actions, filtros ou ajudar a escolher uma API quando houver metadados disponiveis.";
        }
        if (containsAny(prompt, "schema", "schemas", "campo", "campos")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            return "Para consultar campos e schema, use " + candidate.schemaUrl()
                    + ". A API candidata e " + candidate.resourcePath()
                    + " (" + candidate.operation().toUpperCase(Locale.ROOT) + ").";
        }
        if (containsAny(prompt, "action", "actions", "acao", "acoes", "permite", "criar", "editar", "alterar", "excluir")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            return "Para actions e operacoes permitidas, consulte /schemas/actions para "
                    + candidate.resourcePath()
                    + ". O candidato atual usa " + candidate.operation().toUpperCase(Locale.ROOT)
                    + " em " + candidate.submitUrl() + ".";
        }
        if (containsAny(prompt, "filtro", "filtros", "filtrar", "filter")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            return "Para filtros, priorize endpoints de colecao ou consulta como "
                    + candidate.submitUrl()
                    + " e valide o contrato em " + candidate.schemaUrl()
                    + ". Se o catalogo expuser surfaces/actions, use /schemas/surfaces e /schemas/actions para confirmar filtros e operacoes.";
        }

        String endpoints = usableCandidates.stream()
                .limit(4)
                .map(candidate -> candidate.resourcePath()
                        + " (" + candidate.operation().toUpperCase(Locale.ROOT)
                        + ", schema: " + candidate.schemaUrl() + ")")
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        String message = "APIs candidatas encontradas: " + endpoints;
        if (containsAny(prompt, "devo usar", "melhor", "recomenda", "recomende", "dashboard", "tabela")) {
            AgenticAuthoringCandidate candidate = selectedCandidate == null ? usableCandidates.get(0) : selectedCandidate;
            message += ". Recomendacao: use " + candidate.resourcePath()
                    + " com " + candidate.submitUrl()
                    + " para esse objetivo antes de gerar a pagina.";
        }
        return message;
    }

    private String missingResourceAssistantMessage(String artifactKind) {
        if ("dashboard".equals(artifactKind) || "page".equals(artifactKind)) {
            return "Consigo montar esse painel, mas ainda preciso escolher a fonte de dados. "
                    + "Para graficos, normalmente faz sentido usar uma API analitica ou uma colecao com campos numericos. "
                    + "Posso buscar opcoes no catalogo de APIs ou voce pode informar o dominio que quer visualizar.";
        }
        if ("table".equals(artifactKind)) {
            return "Consigo criar a tabela, mas preciso saber qual recurso de negocio deve alimentar as linhas. "
                    + "Posso buscar colecoes disponiveis no catalogo de APIs ou voce pode informar o dominio desejado.";
        }
        if ("form".equals(artifactKind)) {
            return "Consigo criar o formulario, mas preciso escolher qual operacao de negocio ele deve executar. "
                    + "Posso buscar APIs de criacao no catalogo ou voce pode informar o recurso que quer cadastrar.";
        }
        return "Consigo ajudar, mas ainda falta escolher o recurso de negocio. "
                + "Posso buscar opcoes reais no catalogo de APIs ou voce pode informar o dominio que quer usar.";
    }

    private List<AgenticAuthoringQuickReply> quickReplies(
            String effectivePrompt,
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate,
            List<String> questions,
            List<AgenticAuthoringCandidate> candidates,
            boolean answeredBareDomainClarification) {
        if (!"explore".equals(operationKind) && !"clarification_required".equals(gate.status())) {
            return List.of();
        }
        if (gate.messages().contains("analytics-breakdown-required")) {
            return payrollBreakdownQuickReplies(effectivePrompt);
        }
        if (gate.messages().contains("analytics-custom-breakdown-required")) {
            return payrollCustomBreakdownQuickReplies(effectivePrompt);
        }
        if (gate.messages().contains("resource-candidate-required")) {
            return resourceDiscoveryQuickReplies(effectivePrompt, artifactKind);
        }
        if ("api_catalog".equals(artifactKind)) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "api-create-dashboard",
                            "suggestion",
                            "Criar dashboard",
                            "Crie um dashboard usando a API recomendada."),
                    new AgenticAuthoringQuickReply(
                            "api-show-schema",
                            "suggestion",
                            "Ver schema",
                            "Quais campos existem no schema da API recomendada?"),
                    new AgenticAuthoringQuickReply(
                            "api-show-actions",
                            "suggestion",
                            "Ver actions",
                            "Quais actions e filtros essa API suporta?"));
        }
        if (!"explore".equals(operationKind)
                && gate.messages().contains("intent-confirmation-required")
                && !questions.isEmpty()) {
            return confirmationQuickReplies(effectivePrompt, questions.get(0));
        }
        if (answeredBareDomainClarification
                && gate.messages().contains("intent-confirmation-required")
                && !questions.isEmpty()) {
            return confirmationQuickReplies(effectivePrompt, questions.get(0));
        }
        if (gate.messages().contains("resource-candidate-ambiguous") && selectedCandidate == null) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
        }
        if (isPayrollAnalyticsPrompt(prompt)) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "payroll-executive-dashboard",
                            "suggestion",
                            "Dashboard executivo",
                            "Crie um dashboard de folha de pagamento com KPIs, folha por departamento, evolucao mensal e tabela de detalhes."),
                    new AgenticAuthoringQuickReply(
                            "payroll-department-drilldown",
                            "suggestion",
                            "Drill-down por departamento",
                            "Crie um dashboard de folha de pagamento com grafico por departamento, indicadores e drill-down para painel de detalhamento ao selecionar uma barra."),
                    new AgenticAuthoringQuickReply(
                            "payroll-detail-table",
                            "suggestion",
                            "Tabela detalhada",
                            "Crie uma tabela detalhada de folha de pagamento com filtros, valores monetarios formatados e colunas por funcionario."));
        }
        if ("clarification_required".equals(gate.status())) {
            return revisionQuickReplies(effectivePrompt);
        }
        return List.of(
                new AgenticAuthoringQuickReply(
                        "dashboard-suggestion",
                        "suggestion",
                        "Dashboard",
                        "Crie um dashboard com KPIs, grafico e tabela de detalhes."),
                new AgenticAuthoringQuickReply(
                        "form-suggestion",
                        "suggestion",
                        "Formulario",
                        "Crie um formulario com apenas os campos necessarios para o processo de negocio."),
                new AgenticAuthoringQuickReply(
                        "master-detail-suggestion",
                        "suggestion",
                        "Master detail",
                        "Crie uma pagina master-detail com uma lista de resumo e uma area de detalhe vinculada."));
    }

    private List<AgenticAuthoringQuickReply> candidateResourceQuickReplies(
            String effectivePrompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .limit(6)
                .map(candidate -> {
                    ObjectNode contextHints = objectMapper.createObjectNode();
                    contextHints.put("resourcePath", candidate.resourcePath());
                    contextHints.put("submitUrl", candidate.submitUrl());
                    contextHints.put("operation", candidate.operation());
                    contextHints.put("schemaUrl", candidate.schemaUrl());
                    return new AgenticAuthoringQuickReply(
                            quickReplyId(candidate),
                            "suggestion",
                            candidateLabel(candidate),
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "usar " + candidate.resourcePath()),
                            candidateDescription(candidate),
                            candidateIcon(candidate),
                            candidateTone(candidate),
                            contextHints);
                })
                .toList();
    }

    private List<AgenticAuthoringQuickReply> resourceDiscoveryQuickReplies(
            String effectivePrompt,
            String artifactKind) {
        String resolvedArtifactKind = artifactKind == null ? "unknown" : artifactKind;
        String query = switch (resolvedArtifactKind) {
            case "dashboard", "page" -> "Quais APIs analiticas ou colecoes com campos numericos podem alimentar este painel de graficos?";
            case "table" -> "Quais APIs de colecao podem alimentar esta tabela?";
            case "form" -> "Quais APIs de criacao podem alimentar este formulario?";
            default -> "Quais APIs disponiveis podem alimentar esta tela?";
        };
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("tool", "searchApiResources");
        contextHints.put("artifactKind", resolvedArtifactKind);
        contextHints.put("retrievalQuery", query);

        return List.of(
                new AgenticAuthoringQuickReply(
                        "search-api-resources",
                        "suggestion",
                        "Buscar APIs",
                        query,
                        "Consulta o catalogo para encontrar recursos reais antes de gerar a tela.",
                        "manage_search",
                        "resource",
                        contextHints),
                new AgenticAuthoringQuickReply(
                        "describe-business-domain",
                        "revise",
                        "Informar dominio",
                        effectivePrompt,
                        "Explique qual area de negocio, entidade ou indicador deve alimentar a tela.",
                        "edit_note",
                        "neutral",
                        null),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        "",
                        null,
                        null,
                        null,
                        null));
    }

    private String quickReplyId(AgenticAuthoringCandidate candidate) {
        return "resource-" + normalize(candidate.resourcePath())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private String candidateLabel(AgenticAuthoringCandidate candidate) {
        String path = candidate.resourcePath();
        if (path == null || path.isBlank()) {
            return "Recurso";
        }
        String lastSegment = path.replaceAll("/+$", "");
        int slash = lastSegment.lastIndexOf('/');
        lastSegment = slash >= 0 ? lastSegment.substring(slash + 1) : lastSegment;
        return lastSegment
                .replace("vw-", "")
                .replace("-", " ");
    }

    private String candidateDescription(AgenticAuthoringCandidate candidate) {
        String method = valueOrDefault(candidate.submitMethod(), candidate.operation()).toUpperCase(Locale.ROOT);
        return method + " " + candidate.submitUrl();
    }

    private String candidateIcon(AgenticAuthoringCandidate candidate) {
        String normalized = normalize(candidate.resourcePath() + " " + candidate.submitUrl());
        if (normalized.contains("analytics") || normalized.contains("stats") || normalized.contains("vw-")) {
            return "query_stats";
        }
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "edit_note";
        }
        return "dataset";
    }

    private String candidateTone(AgenticAuthoringCandidate candidate) {
        String normalized = normalize(candidate.resourcePath() + " " + candidate.submitUrl());
        if (normalized.contains("analytics") || normalized.contains("stats") || normalized.contains("vw-")) {
            return "analytics";
        }
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "primary";
        }
        return "resource";
    }

    private List<AgenticAuthoringQuickReply> payrollBreakdownQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-department",
                        "suggestion",
                        "Por departamento",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por departamento")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-competence",
                        "suggestion",
                        "Por competencia",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por competencia")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-status",
                        "suggestion",
                        "Por status",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por status")));
    }

    private List<AgenticAuthoringQuickReply> payrollCustomBreakdownQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-role",
                        "suggestion",
                        "Por cargo",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por cargo")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-team",
                        "suggestion",
                        "Por equipe",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por equipe")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-base",
                        "suggestion",
                        "Por base",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por base")),
                new AgenticAuthoringQuickReply(
                        "payroll-breakdown-profile",
                        "suggestion",
                        "Por perfil",
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, "por perfil")));
    }

    private List<AgenticAuthoringQuickReply> confirmationQuickReplies(String effectivePrompt, String question) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "confirm",
                        "confirm",
                        "Sim, criar",
                        confirmationPromptFromQuestion(effectivePrompt, question)),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Quero ajustar",
                        effectivePrompt),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        ""));
    }

    private List<AgenticAuthoringQuickReply> revisionQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Quero ajustar",
                        effectivePrompt),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        ""));
    }

    private String confirmationPromptFromQuestion(String effectivePrompt, String question) {
        String normalizedQuestion = question == null ? "" : question.trim().replaceAll("\\?+$", "");
        String directive = normalizedQuestion
                .replaceFirst("(?i)^posso\\s+criar\\s+", "Crie ")
                .replaceFirst("(?i)^can\\s+i\\s+create\\s+", "Create ");
        if (directive.isBlank() || directive.equals(normalizedQuestion)) {
            return AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, question);
        }
        return directive;
    }

    private String formatCandidateOptions(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        return candidates.stream()
                .limit(3)
                .map(candidate -> candidate.resourcePath() + " (" + candidate.operation().toUpperCase(Locale.ROOT) + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private boolean containsAny(String value, String... tokens) {
        String wordPaddedValue = null;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = normalize(token);
            if (requiresWholeWordMatch(normalizedToken)) {
                if (wordPaddedValue == null) {
                    wordPaddedValue = " " + value.replaceAll("[^a-z0-9]+", " ") + " ";
                }
                if (wordPaddedValue.contains(" " + normalizedToken + " ")) {
                    return true;
                }
                continue;
            }
            if (value.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresWholeWordMatch(String token) {
        return token.length() <= 4 && token.chars().allMatch(Character::isLetterOrDigit);
    }

    private boolean isReadProjectionOperation(String submitUrl, String operation) {
        String normalized = submitUrl == null ? "" : submitUrl.toLowerCase(Locale.ROOT);
        return "post".equalsIgnoreCase(operation)
                && (normalized.endsWith("/stats/group-by")
                || normalized.endsWith("/stats/timeseries")
                || normalized.endsWith("/stats/distribution")
                || normalized.endsWith("/filter")
                || normalized.endsWith("/filter/cursor"));
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
