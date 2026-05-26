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
        boolean directMaterializationRequest = isDirectMaterializationRequest(prompt)
                || isDirectMaterializationRequest(currentPrompt)
                || isDirectMaterializationRequest(rawPrompt);
        boolean implicitAuthoringRequest = isImplicitAuthoringRequest(prompt)
                || isImplicitAuthoringRequest(currentPrompt)
                || isImplicitAuthoringRequest(rawPrompt);
        boolean llmResolved = input.llmIntent() != null && input.llmIntent().resolved();
        boolean implicitAuthoringHasGroundedSource = selectedCandidate != null
                || input.contextHintCandidate() != null
                || hasGroundedCandidate(candidates);
        boolean materializationRequest = directMaterializationRequest
                || (implicitAuthoringRequest
                && implicitAuthoringHasGroundedSource
                && !input.resourceChoiceClarificationAnswer()
                && (!llmResolved || !"unknown".equals(artifactKind)));

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
                && !explicitDataSourceChoice) {
            selectedCandidate = null;
        }
        if ("form".equals(artifactKind)
                && "create".equals(operationKind)
                && selectedCandidate != null
                && candidates.size() > 1
                && isBroadArtifactDiscoveryOnly(candidates)) {
            selectedCandidate = null;
        }
        if (materializationRequest
                && ("explore".equals(operationKind)
                || "explain".equals(operationKind)
                || "api_catalog".equals(artifactKind)
                || "unknown".equals(artifactKind))) {
            operationKind = "create";
            artifactKind = materializableArtifactKind(prompt, currentPrompt, rawPrompt, artifactKind);
            changeKind = materializableChangeKind(prompt, currentPrompt, rawPrompt, changeKind);
        }
        String requestedArtifactKind = materializableArtifactKind(prompt, currentPrompt, rawPrompt, artifactKind);
        if (materializationRequest
                && "table".equals(artifactKind)
                && "dashboard".equals(requestedArtifactKind)) {
            operationKind = "create";
            artifactKind = "dashboard";
            changeKind = tableCreationChangeKind(changeKind) ? "create_artifact" : materializableChangeKind(
                    prompt,
                    currentPrompt,
                    rawPrompt,
                    changeKind);
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
            if (materializationRequest) {
                selectedCandidate = selectedCandidate != null ? selectedCandidate : input.contextHintCandidate();
                artifactKind = materializableArtifactKind(prompt, currentPrompt, rawPrompt, artifactKind);
                operationKind = "create";
                changeKind = materializableChangeKind(prompt, currentPrompt, rawPrompt, changeKind);
            } else {
                artifactKind = "dashboard";
                operationKind = "explore";
                changeKind = "recommend_dashboard_visualization";
            }
        }
        return new AgenticAuthoringSemanticDecision(operationKind, artifactKind, changeKind, selectedCandidate);
    }

    private boolean isDirectMaterializationRequest(String prompt) {
        String normalized = normalize(prompt).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAny(normalized,
                "como criar", "como crio", "como montar", "como faco", "como fazer",
                "posso criar", "da para criar", "daria para criar", "o que posso criar",
                "quais dashboards", "quais paineis", "quais opcoes", "quais opções",
                "quero saber", "gostaria de saber")) {
            return false;
        }
        String padded = " " + normalized + " ";
        return padded.contains(" crie ")
                || padded.contains(" criar agora ")
                || padded.contains(" monte ")
                || padded.contains(" gere ")
                || padded.contains(" gerar agora ")
                || padded.contains(" materialize ")
                || padded.contains(" materializar ")
                || padded.contains(" preview ")
                || padded.contains(" pre visualizacao ")
                || padded.contains(" create a ")
                || padded.contains(" create an ")
                || padded.contains(" build a ")
                || padded.contains(" build an ")
                || padded.contains(" generate a ")
                || padded.contains(" generate an ");
    }

    private boolean isImplicitAuthoringRequest(String prompt) {
        String normalized = normalize(prompt).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (containsAny(normalized,
                "como criar", "como crio", "como montar", "como faco", "como fazer",
                "posso criar", "da para criar", "daria para criar", "o que posso criar",
                "quero saber", "gostaria de saber", "quais dashboards", "quais paineis",
                "quais opcoes", "quais opções", "quais dados", "quais informacoes",
                "compare alternativas", "comparar alternativas", "recomendacao antes",
                "recomendação antes", "quais opcoes de dashboard", "quais opções de dashboard",
                "voce recomenda", "você recomenda", "devo usar", "antes de criar", "nao sei", "não sei")) {
            return false;
        }
        boolean authoringVerb = containsAny(normalized,
                "quero", "preciso", "gostaria", "necessito", "acompanhar", "monitorar", "controlar");
        boolean pageOrDashboardSurface = containsAny(normalized,
                "dashboard", "painel", "visao geral", "visao 360", "overview", "kpi", "indicador");
        return authoringVerb && pageOrDashboardSurface;
    }

    private String materializableArtifactKind(
            String prompt,
            String currentPrompt,
            String rawPrompt,
            String fallbackArtifactKind) {
        String normalized = normalize(prompt + " " + currentPrompt + " " + rawPrompt);
        if (containsAny(normalized, "formulario", "formulário", " form ")) {
            return "form";
        }
        if (containsAny(normalized, "tabela", " table ", "grid")) {
            return "table";
        }
        if (containsAny(normalized, "pagina", "página", " page ")) {
            return "page";
        }
        if (containsAny(normalized,
                "grafico", "gráfico", "chart", "dashboard", "painel", "kpi", "indicador")) {
            return "dashboard";
        }
        return "unknown".equals(valueOrDefault(fallbackArtifactKind, "unknown"))
                || "api_catalog".equals(fallbackArtifactKind)
                || "component".equals(fallbackArtifactKind)
                ? "dashboard"
                : fallbackArtifactKind;
    }

    private String materializableChangeKind(
            String prompt,
            String currentPrompt,
            String rawPrompt,
            String fallbackChangeKind) {
        String normalized = normalize(prompt + " " + currentPrompt + " " + rawPrompt);
        if (containsAny(normalized, "grafico", "gráfico", "chart")
                && containsAny(normalized, "apenas", "somente", "only", "unico", "único")) {
            return "create_chart";
        }
        if (!"unknown".equals(valueOrDefault(fallbackChangeKind, "unknown"))
                && !"answer_api_catalog_question".equals(fallbackChangeKind)
                && !"answer_component_catalog_question".equals(fallbackChangeKind)
                && !"answer_component_capability_question".equals(fallbackChangeKind)
                && !"recommend_dashboard_visualization".equals(fallbackChangeKind)
                && !"recommend_table_visualization".equals(fallbackChangeKind)) {
            return fallbackChangeKind;
        }
        return "create_artifact";
    }

    private boolean tableCreationChangeKind(String changeKind) {
        String normalized = valueOrDefault(changeKind, "unknown");
        return "create_table".equals(normalized)
                || "recommend_table_visualization".equals(normalized)
                || "table.create".equals(normalized);
    }

    private boolean hasGroundedCandidate(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream()
                .anyMatch(candidate -> candidate != null
                        && candidate.resourcePath() != null
                        && !candidate.resourcePath().isBlank()
                        && !isBroadArtifactDiscoveryCandidate(candidate));
    }

    private boolean isBroadArtifactDiscoveryCandidate(AgenticAuthoringCandidate candidate) {
        return candidate.evidence() != null
                && candidate.evidence().contains("broad-artifact-discovery");
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
