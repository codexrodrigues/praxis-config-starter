package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class AgenticAuthoringIntentResolverService {

    private static final String DEFAULT_TARGET_COMPONENT = "praxis-dynamic-page-builder";

    private final AgenticAuthoringCurrentPageAnalyzer currentPageAnalyzer;
    private final AgenticAuthoringCandidateEligibilityGate eligibilityGate;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;

    public AgenticAuthoringIntentResolverService(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public AgenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
    }

    public AgenticAuthoringIntentResolutionResult resolve(AgenticAuthoringIntentResolutionRequest request) {
        if (request == null || request.userPrompt() == null || request.userPrompt().isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank.");
        }
        String prompt = normalize(request.userPrompt());
        JsonNode currentPageSummary = currentPageAnalyzer.summarize(request.currentPage());
        String operationKind = resolveOperationKind(prompt);
        String artifactKind = resolveArtifactKind(prompt, currentPageSummary);
        String changeKind = resolveChangeKind(prompt, operationKind, artifactKind);
        AgenticAuthoringTarget target = currentPageAnalyzer.resolveTarget(request.currentPage(), request.selectedWidgetKey());
        List<AgenticAuthoringCandidate> candidates = discoverCandidates(prompt, artifactKind, target);
        AgenticAuthoringCandidate selectedCandidate = selectCandidate(candidates, target);
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                target,
                selectedCandidate,
                candidates);
        gate = withPromptSpecificGateMessages(gate, prompt, operationKind, artifactKind, selectedCandidate);
        List<String> questions = clarificationQuestions(gate, operationKind, artifactKind, selectedCandidate, candidates);
        List<String> warnings = List.of("metadata-probe-not-run", "llm-intent-classification-not-run");
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
                questions,
                warnings,
                gate.messages(),
                currentPageSummary
        );
    }

    private String resolveOperationKind(String prompt) {
        if (containsAny(prompt, "conectar", "ligar", "vincular", "relacionar")) {
            return "connect";
        }
        if (containsAny(prompt, "remover", "remova", "remove", "excluir", "exclua", "apagar", "apague", "retirar", "retire")) {
            return "remove";
        }
        if (containsAny(prompt, "alterar", "altere", "mudar", "mude", "trocar", "troque",
                "adicionar", "adicione", "incluir", "inclua", "acrescentar", "acrescente",
                "dividir", "divida", "renomear", "renomeie")) {
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
                "ver", "visao", "mostrar", "mostre");
    }

    private boolean isPayrollAnalyticsPrompt(String prompt) {
        return containsAny(prompt, "folha", "pagamento", "pagamentos", "salario", "salarios",
                "departamento", "departamentos");
    }

    private String resolveArtifactKind(String prompt, JsonNode currentPageSummary) {
        if (containsAny(prompt, "formulario", "form", "campo", "campos", "cadastrar", "cadastro", "abrir chamado")) {
            return "form";
        }
        if (isTablePrompt(prompt)) {
            return "table";
        }
        if (containsAny(prompt, "dashboard", "painel", "grafico", "graficos", "chart", "charts",
                "indicador", "indicadores", "drill down", "drill-down", "drilldown",
                "visualizar informacoes", "visualizar informacao", "folha de pagamento")) {
            return "dashboard";
        }
        if (isConsultativePrompt(prompt) && isPayrollAnalyticsPrompt(prompt)) {
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
            return "remove_field";
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
        return "unknown";
    }

    private List<AgenticAuthoringCandidate> discoverCandidates(
            String prompt,
            String artifactKind,
            AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
            candidates.add(candidate(target.resourcePath(), 0.95d, "resource resolved from current target widget", "current-page"));
        }
        List<AgenticAuthoringCandidate> metadataCandidates = apiMetadataCandidateCatalog == null
                ? List.of()
                : apiMetadataCandidateCatalog.discover(prompt, artifactKind);
        if (!metadataCandidates.isEmpty()) {
            candidates.addAll(metadataCandidates);
        }
        candidates.addAll(discoverKnownCandidates(prompt));
        return deduplicateCandidates(candidates);
    }

    private List<AgenticAuthoringCandidate> discoverKnownCandidates(String prompt) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (containsAny(prompt, "funcionario", "funcionarios", "colaborador", "colaboradores", "rh", "human resources")) {
            candidates.add(candidate("/api/human-resources/funcionarios", 0.90d, "prompt mentions funcionarios/colaboradores", "known-quickstart-resource"));
        }
        if (isPayrollAnalyticsPrompt(prompt) && isTablePrompt(prompt)) {
            candidates.add(candidate(
                    "/api/human-resources/folhas-pagamento",
                    "get",
                    0.94d,
                    "prompt asks for operational payroll table/listing",
                    "known-quickstart-resource"));
        } else if (isPayrollAnalyticsPrompt(prompt)
                && (isConsultativePrompt(prompt)
                || containsAny(prompt, "chart", "grafico", "dashboard", "painel", "drill down", "drill-down",
                "drilldown", "visualizar", "mostrar", "mostre", "ver"))) {
            candidates.add(candidate(
                    "/api/human-resources/vw-analytics-folha-pagamento",
                    "get",
                    0.94d,
                    "prompt mentions payroll analytics chart drill-down",
                    "known-quickstart-analytics-view"));
        } else if (isPayrollAnalyticsPrompt(prompt)) {
            candidates.add(candidate(
                    "/api/human-resources/vw-analytics-folha-pagamento",
                    "get",
                    0.78d,
                    "approximate payroll analytics endpoint match",
                    "known-quickstart-analytics-view"));
            candidates.add(candidate(
                    "/api/human-resources/folhas-pagamento",
                    "get",
                    0.72d,
                    "approximate payroll collection endpoint match",
                    "known-quickstart-resource"));
        }
        if (containsAny(prompt, "chamado", "chamados", "helpdesk", "notebook", "tela quebrada", "incidente")) {
            candidates.add(candidate("/api/helpdesk/chamados", 0.92d, "prompt mentions chamado/helpdesk incident", "known-helpdesk-resource"));
        }
        return candidates;
    }

    private List<AgenticAuthoringCandidate> deduplicateCandidates(List<AgenticAuthoringCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                .collect(
                        java.util.stream.Collectors.toMap(
                                candidate -> candidate.resourcePath() + "::" + candidate.operation(),
                                candidate -> candidate,
                                (left, right) -> left.score() >= right.score() ? left : right,
                                java.util.LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    private AgenticAuthoringCandidate selectCandidate(List<AgenticAuthoringCandidate> candidates, AgenticAuthoringTarget target) {
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
            return candidates.stream()
                    .filter(candidate -> target.resourcePath().equals(candidate.resourcePath()))
                    .findFirst()
                    .orElse(null);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1 && candidates.get(0).score() - candidates.get(1).score() >= 0.08d) {
            return candidates.get(0);
        }
        return null;
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, double score, String reason, String evidence) {
        return candidate(resourcePath, "post", score, reason, evidence);
    }

    private AgenticAuthoringCandidate candidate(String resourcePath, String operation, double score, String reason, String evidence) {
        String schemaPath = "/schemas/filtered?path=" + resourcePath + "&operation=post&schemaType=request";
        if ("get".equalsIgnoreCase(operation)) {
            schemaPath = "/schemas/filtered?path=" + resourcePath + "&operation=get&schemaType=response";
        }
        return new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaPath,
                resourcePath,
                operation,
                score,
                reason,
                List.of(evidence, "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending")
        );
    }

    private String authoringProfile(String operationKind, String artifactKind) {
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
        if (requiresPayrollDashboardBreakdown(prompt, operationKind, artifactKind, selectedCandidate)
                && !messages.contains("analytics-breakdown-required")) {
            messages.add("analytics-breakdown-required");
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
                && "/api/human-resources/vw-analytics-folha-pagamento".equals(selectedCandidate.resourcePath())
                && !isExplicitCreateConfirmation(prompt)
                && !hasPayrollDashboardBreakdown(prompt);
    }

    private boolean hasPayrollDashboardBreakdown(String prompt) {
        return containsAny(prompt,
                "departamento", "departamentos", "competencia", "competencias", "mes", "mensal",
                "status", "perfil", "perfis", "cargo", "cargos", "funcionario", "funcionarios",
                "drill down", "drill-down", "drilldown");
    }

    private boolean isTablePrompt(String prompt) {
        return containsAny(prompt, "tabela", "grid", "lista", "listagem", "listar", "liste", "relacao");
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
            }
        }
        return List.copyOf(questions);
    }

    private String confirmationQuestion(String operationKind, String artifactKind, AgenticAuthoringCandidate selectedCandidate) {
        if ("explore".equals(operationKind) && "table".equals(artifactKind)
                && selectedCandidate != null
                && "/api/human-resources/folhas-pagamento".equals(selectedCandidate.resourcePath())) {
            return "Posso criar uma tabela operacional de folhas de pagamento usando /api/human-resources/folhas-pagamento?";
        }
        return "Posso criar um dashboard de folha de pagamento com grafico por departamento, indicadores e detalhamento?";
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

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
