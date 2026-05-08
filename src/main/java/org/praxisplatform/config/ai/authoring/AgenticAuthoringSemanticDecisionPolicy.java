package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class AgenticAuthoringSemanticDecisionPolicy {

    AgenticAuthoringSemanticDecision apply(Input input) {
        if (input == null) {
            return null;
        }
        String operationKind = valueOrDefault(input.operationKind(), "unknown");
        String artifactKind = valueOrDefault(input.artifactKind(), "unknown");
        String changeKind = valueOrDefault(input.changeKind(), "unknown");
        AgenticAuthoringCandidate selectedCandidate = input.selectedCandidate();
        List<AgenticAuthoringCandidate> candidates = input.candidates() == null ? List.of() : input.candidates();
        String prompt = normalize(input.prompt());
        String currentPrompt = normalize(input.currentPrompt());
        String rawPrompt = normalize(input.rawPrompt());
        boolean explicitDataSourceChoice = containsAny(rawPrompt, "data source", "fonte de dados", "fonte");
        boolean currentPromptExplicitDataSourceChoice = containsAny(currentPrompt, "data source", "fonte de dados", "fonte");
        boolean optionalDataSourceHint = !explicitDataSourceChoice
                && (input.optionalDataSourceHint() || isOptionalDataSourceHint(rawPrompt));
        boolean dataSourceChoiceAnswer = input.resourceChoiceClarificationAnswer()
                || (input.contextHintCandidate() != null && explicitDataSourceChoice);

        if (isGenericAnalyticalLlmExploration(input.llmIntent(), prompt) && candidates.size() > 1) {
            selectedCandidate = null;
        }
        if (optionalDataSourceHint) {
            selectedCandidate = null;
        }
        if (isBareDomainPrompt(prompt)) {
            selectedCandidate = null;
        }
        if ("api_catalog".equals(artifactKind)
                && "explore".equals(operationKind)
                && isBroadApiCatalogResourceDiscoveryPrompt(prompt)
                && input.contextHintCandidate() == null) {
            selectedCandidate = null;
        }
        if ("dashboard".equals(artifactKind)
                && selectedCandidate != null
                && candidates.size() > 1
                && containsAny(prompt, "visualizar informacoes", "visualizar informacao", "visualizar dados")
                && !isSpecificPayrollAnalyticsPrompt(prompt)) {
            selectedCandidate = null;
        }
        if ("dashboard".equals(artifactKind)
                && selectedCandidate != null
                && candidates.size() > 1
                && isAnalyticalComparisonPrompt(prompt)
                && analyticsCandidateCount(candidates) > 1
                && !isSpecificPayrollAnalyticsPrompt(prompt)) {
            selectedCandidate = null;
        }
        if ("form".equals(artifactKind)
                && "create".equals(operationKind)
                && selectedCandidate != null
                && candidates.size() > 1
                && isBroadArtifactDiscoveryOnly(candidates)) {
            selectedCandidate = null;
        }
        if (selectedCandidate != null
                && !isAnalyticsResource(selectedCandidate.resourcePath())
                && List.of("dashboard", "page", "unknown").contains(artifactKind)
                && isAnalyticalComparisonPrompt(String.join(" ", prompt, rawPrompt))
                && !explicitDataSourceChoice
                && !optionalDataSourceHint) {
            AgenticAuthoringCandidate analyticsCandidate = singleAnalyticsCandidate(candidates);
            if (analyticsCandidate != null) {
                selectedCandidate = analyticsCandidate;
            }
        }
        if (selectedCandidate != null
                && !isAnalyticsResource(selectedCandidate.resourcePath())
                && List.of("dashboard", "page", "unknown").contains(artifactKind)
                && isPayrollVisualizationPrompt(String.join(" ", prompt, rawPrompt))
                && !explicitDataSourceChoice
                && !optionalDataSourceHint) {
            AgenticAuthoringCandidate payrollAnalyticsCandidate = payrollAnalyticsCandidate(candidates);
            if (payrollAnalyticsCandidate != null) {
                selectedCandidate = payrollAnalyticsCandidate;
            }
        }
        if (selectedCandidate != null
                && List.of("dashboard", "page", "unknown").contains(artifactKind)
                && isSpecificPayrollAnalyticsPrompt(String.join(" ", prompt, rawPrompt))
                && !explicitDataSourceChoice
                && !optionalDataSourceHint) {
            AgenticAuthoringCandidate payrollAnalyticsCandidate = payrollAnalyticsCandidate(candidates);
            if (payrollAnalyticsCandidate != null) {
                selectedCandidate = payrollAnalyticsCandidate;
            }
        }
        if (selectedCandidate == null
                && List.of("dashboard", "page", "unknown").contains(artifactKind)
                && isSpecificPayrollAnalyticsPrompt(String.join(" ", prompt, rawPrompt))
                && !explicitDataSourceChoice
                && !optionalDataSourceHint) {
            AgenticAuthoringCandidate payrollAnalyticsCandidate = payrollAnalyticsCandidate(candidates);
            if (payrollAnalyticsCandidate != null) {
                selectedCandidate = payrollAnalyticsCandidate;
            }
        }
        if ("table".equals(artifactKind)
                && "explore".equals(operationKind)
                && isPayrollVisualizationPrompt(String.join(" ", prompt, rawPrompt))
                && containsAny(String.join(" ", prompt, rawPrompt), "por departamento", "por setor", "ranking", "comparar", "compare")) {
            artifactKind = "dashboard";
            changeKind = "recommend_dashboard_visualization";
        }
        if (!optionalDataSourceHint
                && input.governedResourceConfirmation()
                && !currentPromptExplicitDataSourceChoice
                && input.contextHintCandidate() != null) {
            selectedCandidate = input.contextHintCandidate();
            artifactKind = valueOrDefault(input.contextHintArtifactKind(), "dashboard");
            operationKind = valueOrDefault(input.contextHintOperationKind(), "create");
            changeKind = valueOrDefault(input.contextHintChangeKind(), "create_artifact");
        } else if (dataSourceChoiceAnswer && (!input.governedResourceConfirmation() || explicitDataSourceChoice)) {
            artifactKind = "dashboard";
            operationKind = "explore";
            changeKind = "recommend_dashboard_visualization";
        }
        return new AgenticAuthoringSemanticDecision(operationKind, artifactKind, changeKind, selectedCandidate);
    }

    private boolean isGenericAnalyticalLlmExploration(AgenticAuthoringLlmIntentResolution llmIntent, String prompt) {
        return llmIntent != null
                && llmIntent.resolved()
                && "explore".equals(valueOrDefault(llmIntent.operationKind(), "unknown"))
                && "unknown".equals(valueOrDefault(llmIntent.artifactKind(), "unknown"))
                && isAnalyticalComparisonPrompt(prompt);
    }

    private boolean isAnalyticalComparisonPrompt(String prompt) {
        return containsAny(prompt,
                "ranking", "rank", "top", "maior", "maiores", "menor", "menores",
                "comparar", "compare", "comparativo", "por setor", "por departamento",
                "por area", "por areas", "area", "areas",
                "recebe mais", "ganha mais", "salario", "salarios", "remuneracao");
    }

    private boolean isSpecificPayrollAnalyticsPrompt(String prompt) {
        boolean payrollSubject = containsAny(prompt,
                "folha", "pagamento", "pagamentos", "salario", "salarios", "remuneracao",
                "recebe mais", "ganha mais");
        boolean analyticalCut = containsAny(prompt,
                "ranking", "rank", "top", "maior", "maiores", "menor", "menores",
                "por setor", "por departamento", "por area", "por areas", "setor", "departamento", "area", "areas");
        return payrollSubject && analyticalCut;
    }

    private int analyticsCandidateCount(List<AgenticAuthoringCandidate> candidates) {
        return (int) (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates)
                .stream()
                .filter(candidate -> candidate != null && isAnalyticsResource(candidate.resourcePath()))
                .count();
    }

    private boolean isAnalyticsResource(String resourcePath) {
        String normalized = normalize(resourcePath);
        return normalized.contains("analytics") || normalized.contains("/vw-") || normalized.contains("/stats/");
    }

    private boolean isOptionalDataSourceHint(String prompt) {
        return containsAny(prompt,
                "se precisar usa",
                "se precisar, usa",
                "se precisar use",
                "se precisar, use",
                "se precisar usar",
                "se precisar, usar",
                "caso precise usa",
                "caso precise, usa",
                "caso precise use",
                "caso precise, use",
                "caso precise usar",
                "caso precise, usar");
    }

    private boolean isPayrollVisualizationPrompt(String prompt) {
        return containsAny(prompt, "folha", "pagamento", "pagamentos", "salario", "salarios", "remuneracao",
                "recebe mais", "ganha mais")
                && containsAny(prompt,
                "visualizar", "ver", "analisar", "acompanhar", "dashboard", "painel", "comparar", "compare",
                "grafico", "graficos", "melhor forma", "opcao", "opcoes", "recomenda");
    }

    private AgenticAuthoringCandidate payrollAnalyticsCandidate(List<AgenticAuthoringCandidate> candidates) {
        return (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates)
                .stream()
                .filter(candidate -> candidate != null && isAnalyticsResource(candidate.resourcePath()))
                .filter(candidate -> containsAny(String.join(" ",
                        valueOrDefault(candidate.resourcePath(), ""),
                        valueOrDefault(candidate.submitUrl(), ""),
                        valueOrDefault(candidate.reason(), "")),
                        "folha", "pagamento", "salario", "salarios", "remuneracao"))
                .findFirst()
                .orElse(null);
    }

    private AgenticAuthoringCandidate singleAnalyticsCandidate(List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> analyticsCandidates = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates)
                .stream()
                .filter(candidate -> candidate != null && isAnalyticsResource(candidate.resourcePath()))
                .toList();
        return analyticsCandidates.size() == 1 ? analyticsCandidates.get(0) : null;
    }

    private boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && !candidates.isEmpty()
                && candidates.stream()
                .allMatch(candidate -> candidate.evidence() != null
                        && candidate.evidence().contains("broad-artifact-discovery"));
    }

    private boolean isBareDomainPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String[] tokens = prompt.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        return tokens.length <= 2;
    }

    private boolean isBroadApiCatalogResourceDiscoveryPrompt(String prompt) {
        if (!containsAny(prompt, "api", "apis", "endpoint", "endpoints", "schema", "schemas", "filtro", "filtros", "filtrar")) {
            return false;
        }
        return containsAny(prompt, "quais", "listar", "liste", "mostrar", "mostre", "disponiveis", "candidatas", "catalogo");
    }

    private boolean containsAny(String value, String... tokens) {
        String normalizedValue = normalize(value);
        for (String token : tokens) {
            if (token != null && !token.isBlank() && normalizedValue.contains(normalize(token))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    record Input(
            String prompt,
            String currentPrompt,
            String rawPrompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate contextHintCandidate,
            AgenticAuthoringLlmIntentResolution llmIntent,
            boolean optionalDataSourceHint,
            boolean resourceChoiceClarificationAnswer,
            boolean governedResourceConfirmation,
            String contextHintOperationKind,
            String contextHintArtifactKind,
            String contextHintChangeKind) {
    }

    record AgenticAuthoringSemanticDecision(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
    }
}
