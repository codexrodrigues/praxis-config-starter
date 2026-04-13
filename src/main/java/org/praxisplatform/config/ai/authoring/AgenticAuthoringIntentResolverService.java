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

    public AgenticAuthoringIntentResolverService(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.currentPageAnalyzer = new AgenticAuthoringCurrentPageAnalyzer(objectMapper);
        this.eligibilityGate = new AgenticAuthoringCandidateEligibilityGate();
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
        List<AgenticAuthoringCandidate> candidates = discoverCandidates(prompt, target);
        AgenticAuthoringCandidate selectedCandidate = selectCandidate(candidates, target);
        AgenticAuthoringGateResult gate = eligibilityGate.evaluate(
                operationKind,
                artifactKind,
                target,
                selectedCandidate,
                candidates);
        List<String> questions = clarificationQuestions(gate);
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
        if (containsAny(prompt, "criar", "crie", "gerar", "gere", "montar", "monte",
                "construir", "construa", "novo", "nova", "cadastrar", "abrir")) {
            return "create";
        }
        if (containsAny(prompt, "explicar", "explique", "porque", "por que")) {
            return "explain";
        }
        return "unknown";
    }

    private String resolveArtifactKind(String prompt, JsonNode currentPageSummary) {
        if (containsAny(prompt, "formulario", "form", "campo", "campos", "cadastrar", "cadastro", "abrir chamado")) {
            return "form";
        }
        if (containsAny(prompt, "dashboard", "painel", "grafico", "graficos", "indicador", "indicadores")) {
            return "dashboard";
        }
        if (containsAny(prompt, "tabela", "grid", "lista", "listagem")) {
            return "table";
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
        if ("create".equals(operationKind)) {
            return "create_artifact";
        }
        return "unknown";
    }

    private List<AgenticAuthoringCandidate> discoverCandidates(String prompt, AgenticAuthoringTarget target) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()) {
            candidates.add(candidate(target.resourcePath(), 0.95d, "resource resolved from current target widget", "current-page"));
        }
        if (containsAny(prompt, "funcionario", "funcionarios", "colaborador", "colaboradores", "rh", "human resources")) {
            candidates.add(candidate("/api/human-resources/funcionarios", 0.90d, "prompt mentions funcionarios/colaboradores", "known-quickstart-resource"));
        }
        if (containsAny(prompt, "chamado", "chamados", "helpdesk", "notebook", "tela quebrada", "incidente")) {
            candidates.add(candidate("/api/helpdesk/chamados", 0.92d, "prompt mentions chamado/helpdesk incident", "known-helpdesk-resource"));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
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
        String schemaPath = "/schemas/filtered?path=" + resourcePath + "&operation=post&schemaType=request";
        return new AgenticAuthoringCandidate(
                resourcePath,
                "post",
                schemaPath,
                resourcePath,
                "post",
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

    private List<String> clarificationQuestions(AgenticAuthoringGateResult gate) {
        List<String> questions = new ArrayList<>();
        for (String message : gate.messages()) {
            if ("resource-candidate-required".equals(message)) {
                questions.add("Qual recurso de negocio deve alimentar esta tela?");
            } else if ("resource-candidate-ambiguous".equals(message)) {
                questions.add("Qual recurso candidato deve ser usado?");
            } else if ("target-widget-required".equals(message)) {
                questions.add("Qual componente existente deve ser alterado?");
            } else if ("intent-artifact-unknown".equals(message)) {
                questions.add("Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?");
            }
        }
        return List.copyOf(questions);
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
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
