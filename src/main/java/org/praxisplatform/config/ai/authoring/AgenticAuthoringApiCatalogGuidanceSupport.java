package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Package-private support encapsulating consultative API catalog guidance, platform capability
 * explanations, component capability scoring, and clarification/assistant message synthesis for
 * {@link AgenticAuthoringIntentResolverService}.
 */
final class AgenticAuthoringApiCatalogGuidanceSupport {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringIntentQuickReplySupport quickReplySupport;

    AgenticAuthoringApiCatalogGuidanceSupport(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentQuickReplySupport quickReplySupport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.quickReplySupport = Objects.requireNonNull(quickReplySupport, "quickReplySupport must not be null");
    }

    List<String> clarificationQuestions(
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> questions = new ArrayList<>();
        for (String message : gate.messages()) {
            if ("resource-candidate-required".equals(message)) {
                questions.add("Qual recurso de negócio deve alimentar esta tela?");
            } else if ("resource-candidate-ambiguous".equals(message)) {
                String options = quickReplySupport.formatCandidateOptions(candidates);
                if (options.isBlank()) {
                    questions.add("Qual recurso candidato deve ser usado?");
                } else {
                    questions.add("Encontrei recursos próximos: " + options + ". Qual deles você quer usar?");
                }
            } else if ("target-widget-required".equals(message)) {
                questions.add("Qual componente existente deve ser alterado?");
            } else if ("intent-operation-unknown".equals(message)) {
                questions.add("O que você quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?");
            } else if ("intent-artifact-unknown".equals(message)) {
                questions.add("Você quer criar ou alterar formulário, tabela, dashboard, stepper ou outro componente?");
            } else if ("analytics-breakdown-required".equals(message)) {
                questions.add("Qual recorte, métrica ou dimensão governada deve orientar este dashboard?");
            } else if ("intent-confirmation-required".equals(message)) {
                questions.add(confirmationQuestion(operationKind, artifactKind, selectedCandidate));
            }
        }
        return List.copyOf(questions);
    }

    String confirmationQuestion(String operationKind, String artifactKind, AgenticAuthoringCandidate selectedCandidate) {
        if ("table".equals(artifactKind)) {
            return "Posso criar uma tabela usando o recurso de negócio selecionado?";
        }
        if ("form".equals(artifactKind)) {
            return "Posso criar um formulário usando o recurso de negócio selecionado?";
        }
        if ("dashboard".equals(artifactKind)) {
            return "Posso criar um dashboard usando o recurso de negócio selecionado?";
        }
        return "Posso aplicar esta alteração usando o recurso de negócio selecionado?";
    }

    String assistantMessage(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringGateResult gate,
            boolean answeredBareDomainClarification,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (gate != null && gate.messages().contains("shared-rule-authoring-required")) {
            return sharedRuleAuthoringAssistantMessage(selectedCandidate);
        }
        if ("component".equals(artifactKind)
                && ("explain".equals(operationKind) || "explore".equals(operationKind))) {
            return platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("api_catalog".equals(artifactKind)
                && ("explore".equals(operationKind) || "explain".equals(operationKind))) {
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (gate != null && gate.messages().contains("resource-candidate-required")) {
            return quickReplySupport.missingResourceAssistantMessage(artifactKind);
        }
        if (gate != null
                && gate.messages().contains("resource-candidate-ambiguous")
                && selectedCandidate == null) {
            return quickReplySupport.ambiguousResourceAssistantMessage(artifactKind, candidates);
        }
        if ("unknown".equals(valueOrUnknown(operationKind))
                || "unknown".equals(valueOrUnknown(artifactKind))) {
            return unresolvedIntentAssistantMessage();
        }
        if (!"explore".equals(operationKind)) {
            return authoringAssistantMessage(operationKind, artifactKind, changeKind, selectedCandidate);
        }
        if ("api_catalog".equals(artifactKind)) {
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (answeredBareDomainClarification) {
            return null;
        }
        if ("dashboard".equals(artifactKind)) {
            return "Entendi que você quer um painel analítico. Posso preparar uma prévia com a fonte de negócio mais aderente, ou ajustar antes quais métricas e recortes devem aparecer.";
        }
        if ("table".equals(artifactKind)) {
            return "Consigo montar essa tabela. Antes de criar, preciso apenas confirmar a fonte de negócio e quais colunas devem aparecer como principais.";
        }
        return "Posso ajudar a transformar esse pedido em uma tela. Se você me disser se prefere dashboard, tabela, formulário ou uma página mais completa, eu sigo pelo caminho certo.";
    }

    boolean shouldUsePlatformGuidanceMessage(String prompt, String assistantMessage) {
        String normalizedMessage = normalize(assistantMessage);
        if (normalizedMessage.isBlank()) {
            return true;
        }
        return containsAny(normalizedMessage,
                "/api/",
                "/schemas/",
                "selection.enabled",
                "export.general",
                "toolbar.actions",
                ".enabled=",
                ".scope=",
                ".formats=");
    }

    boolean shouldUseApiCatalogGuidanceMessage(String assistantMessage) {
        String rawMessage = valueOrDefault(assistantMessage, "").toLowerCase(Locale.ROOT);
        if (rawMessage.isBlank()) {
            return true;
        }
        if (rawMessage.contains("/api") || rawMessage.contains("/schemas")) {
            return true;
        }
        if (rawMessage.indexOf("fonte confirmada") >= 0
                && rawMessage.indexOf("fonte confirmada") != rawMessage.lastIndexOf("fonte confirmada")) {
            return true;
        }
        String normalizedMessage = normalize(assistantMessage);
        return containsAny(normalizedMessage,
                "vou consultar",
                "vou investigar",
                "consultar o catalogo",
                "consultar as fontes",
                "investigar as capacidades",
                "nao temos dados",
                "não temos dados",
                "nao encontrei dados",
                "não encontrei dados",
                "nao temos informacoes",
                "não temos informações",
                "informacoes especificas",
                "informações específicas",
                "dados especificos disponiveis",
                "dados específicos disponíveis",
                "/api/",
                "/schemas/");
    }

    String sanitizeApiCatalogConsultativeLanguage(
            String assistantMessage,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        String sanitized = quickReplySupport.sanitizePresentationText(assistantMessage, selectedCandidate, candidates);
        if (sanitized == null || sanitized.isBlank()) {
            return sanitized;
        }
        return sanitized
                .replaceAll("(?i)\\bendpoints?\\b", "fontes")
                .replaceAll("(?i)\\bschemas?\\b", "campos confirmados")
                .replaceAll("(?i)\\bjson\\b", "estrutura")
                .replaceAll("(?i)\\burls? de submiss[aã]o\\b", "formas de uso")
                .replaceAll("(?i)\\burls?\\b", "referencias")
                .replaceAll("(?i)\\bPOST\\b", "consulta ou envio")
                .replaceAll("(?i)\\bGET\\b", "consulta")
                .replaceAll("(?i)\\bPUT\\b", "atualizacao")
                .replaceAll("(?i)\\bPATCH\\b", "ajuste")
                .replaceAll("(?i)\\bDELETE\\b", "remocao")
                .replaceAll("(?i)\\bactions?\\b", "acoes disponiveis")
                .replace("cada fontes", "cada fonte");
    }

    String platformCapabilityAssistantMessage(
            String prompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        String normalized = normalize(prompt);
        Optional<ComponentCapabilityMatch> capabilityMatch = bestComponentCapabilityMatch(normalized, componentCapabilities);
        if (capabilityMatch.isPresent() && capabilityMatch.get().score() >= 3) {
            return componentCapabilityAssistantMessage(capabilityMatch.get());
        }
        List<String> families = componentFamilyLabels(componentCapabilities);
        String componentText = families.isEmpty()
                ? "tabelas, formularios, dashboards, graficos, filtros, abas e fluxos em etapas"
                : humanJoin(families);
        return "Aqui voce pode conversar comigo em linguagem natural sobre a intencao da tela antes de criar qualquer coisa. "
                + "O Praxis usa componentes governados como " + componentText + ", escolhidos de acordo com o objetivo, o dominio disponivel e as regras de materializacao. "
                + "Voce pode pedir algo como um dashboard para acompanhar indicadores, uma tabela operacional, um formulario governado, uma pagina com abas ou um fluxo em etapas. "
                + "Quando o pedido ficar concreto, eu valido dados, campos e acoes antes de gerar a previa.";
    }

    private Optional<ComponentCapabilityMatch> bestComponentCapabilityMatch(
            String normalizedPrompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (normalizedPrompt == null
                || normalizedPrompt.isBlank()
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return Optional.empty();
        }
        return componentCapabilities.catalogs().stream()
                .filter(Objects::nonNull)
                .flatMap(catalog -> nullToEmpty(catalog.capabilities()).stream()
                        .filter(Objects::nonNull)
                        .map(capability -> new ComponentCapabilityMatch(
                                catalog.componentId(),
                                capability,
                                componentCapabilityScore(normalizedPrompt, catalog.componentId(), capability))))
                .filter(match -> match.score() > 0)
                .max(Comparator
                        .comparingInt(ComponentCapabilityMatch::score)
                        .thenComparing(match -> valueOrDefault(match.componentId(), "")));
    }

    private int componentCapabilityScore(
            String normalizedPrompt,
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability) {
        int score = 0;
        String normalizedComponent = normalize(componentId);
        if (!normalizedComponent.isBlank() && normalizedPrompt.contains(normalizedComponent.replace('-', ' '))) {
            score += 2;
        }
        String normalizedChangeKind = normalize(capability.changeKind()).replace('_', ' ');
        if (!normalizedChangeKind.isBlank() && normalizedPrompt.contains(normalizedChangeKind)) {
            score += 2;
        }
        for (String term : nullToEmpty(capability.triggerTerms())) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && normalizedPrompt.contains(normalizedTerm)) {
                score += normalizedTerm.contains(" ") ? 2 : 1;
            }
        }
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example : nullToEmpty(capability.examples())) {
            String exampleText = normalize(valueOrDefault(example.prompt(), "") + " " + valueOrDefault(example.intent(), ""));
            for (String token : normalizedPrompt.split("\\s+")) {
                if (token.length() >= 5 && exampleText.contains(token)) {
                    score++;
                }
            }
        }
        return score;
    }

    private String componentCapabilityAssistantMessage(ComponentCapabilityMatch match) {
        String componentLabel = componentLabel(match.componentId());
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability = match.capability();
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example =
                nullToEmpty(capability.examples()).stream().findFirst().orElse(null);
        String intent = example == null ? "" : valueOrDefault(example.intent(), "");
        List<String> hints = example == null ? List.of() : nullToEmpty(example.configHints());
        StringBuilder message = new StringBuilder();
        message.append("Sim. No Praxis, isso e uma capacidade governada do componente ")
                .append(componentLabel)
                .append(", nao um atalho solto da tela.");
        if (!intent.isBlank()) {
            message.append(" A intencao e ").append(stripTrailingSentencePunctuation(lowercaseFirst(intent))).append(".");
        }
        if (!hints.isEmpty()) {
            message.append(" Para funcionar bem, a decisao precisa cobrir ")
                    .append(humanJoin(hints.stream()
                            .map(this::humanizeConfigHint)
                            .filter(hint -> !hint.isBlank())
                            .distinct()
                            .limit(5)
                            .toList()))
                    .append(".");
        }
        message.append(" Se ja houver uma tabela selecionada na pagina, eu posso preparar essa alteracao para revisao usando a configuracao governada do componente.");
        return message.toString();
    }

    private String humanizeConfigHint(String hint) {
        String normalized = normalize(hint);
        if (normalized.contains("selection.enabled")) {
            return "selecao de linhas habilitada";
        }
        if (normalized.contains("toolbar") || normalized.contains("acao em massa") || normalized.contains("action")) {
            return "uma acao de exportar na barra da tabela ou nas acoes em massa";
        }
        if (normalized.contains("export.enabled")) {
            return "exportacao habilitada";
        }
        if (normalized.contains("export.formats")) {
            return "formatos permitidos, como CSV ou Excel";
        }
        if (normalized.contains("scope=selected") || normalized.contains("scope selected")) {
            return "escopo limitado aos registros selecionados";
        }
        if (normalized.contains("includeheaders")) {
            return "cabecalhos no arquivo exportado quando fizer sentido";
        }
        return hint
                .replace("=", " ")
                .replace(".", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String componentLabel(String componentId) {
        String normalized = valueOrDefault(componentId, "").trim();
        if (normalized.isBlank()) {
            return "governado";
        }
        return Stream.of(normalized.split("[^A-Za-z0-9]+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.equalsIgnoreCase("praxis")
                        ? "Praxis"
                        : part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String lowercaseFirst(String value) {
        String text = valueOrDefault(value, "").trim();
        if (text.isBlank()) {
            return text;
        }
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private String stripTrailingSentencePunctuation(String value) {
        return valueOrDefault(value, "").replaceAll("[.!?]+$", "").trim();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ComponentCapabilityMatch(
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability,
            int score) {
    }

    private List<String> componentFamilyLabels(AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (componentCapabilities == null || componentCapabilities.catalogs() == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            String componentId = valueOrDefault(catalog == null ? null : catalog.componentId(), "");
            String normalized = normalize(componentId);
            if (normalized.contains("chart")) {
                labels.add("graficos");
            } else if (normalized.contains("table") || normalized.contains("grid")) {
                labels.add("tabelas");
            } else if (normalized.contains("form")) {
                labels.add("formularios");
            } else if (normalized.contains("tabs")) {
                labels.add("abas");
            } else if (normalized.contains("stepper") || normalized.contains("wizard")) {
                labels.add("fluxos em etapas");
            } else if (normalized.contains("filter")) {
                labels.add("filtros");
            } else if (normalized.contains("crud")) {
                labels.add("CRUDs governados");
            }
        }
        return labels.stream().distinct().limit(8).toList();
    }

    String unresolvedIntentAssistantMessage() {
        return "Ainda não consegui entender isso com segurança. Não vou criar nem alterar nada agora. "
                + "Você pode me pedir para explorar os dados disponíveis, comparar fontes de negócio ou gerar uma tela específica.";
    }

    String authoringAssistantMessage(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        String resourceSummary = selectedCandidate == null || valueOrDefault(selectedCandidate.reason(), "").isBlank()
                ? "a fonte de negócio mais aderente"
                : "a fonte de negócio selecionada";
        if ("set_chart_type".equals(changeKind)) {
            return "Atualizei o gráfico selecionado mantendo os dados, a dimensão e a métrica atuais.";
        }
        if ("chart".equals(artifactKind) && "modify".equals(operationKind)) {
            return "Atualizei o gráfico mantendo os dados confirmados e o contexto atual.";
        }
        if ("chart".equals(artifactKind)) {
            return "Entendi. Vou preparar o gráfico usando dados confirmados e manter a decisão pronta para revisão.";
        }
        if ("dashboard".equals(artifactKind) && "create_chart_drilldown".equals(changeKind)) {
            return "Atualizei a composição para mostrar detalhes a partir da seleção do gráfico, mantendo a fonte e os campos confirmados.";
        }
        if ("dashboard".equals(artifactKind) && "create_chart".equals(changeKind)) {
            return "Entendi. Vou preparar somente o gráfico pedido, usando " + resourceSummary
                    + ", sem adicionar tabela, filtros ou KPIs.";
        }
        if ("dashboard".equals(artifactKind)) {
            return "Entendi o painel que você quer montar. Vou usar " + resourceSummary
                    + " para preparar uma prévia com métricas e recortes coerentes.";
        }
        if ("table".equals(artifactKind)) {
            return "Entendi a tabela que você quer montar. Vou usar " + resourceSummary
                    + " para preparar as colunas principais e manter a decisão pronta para revisão.";
        }
        if ("form".equals(artifactKind)) {
            return "Entendi o formulário que você quer criar. Vou usar " + resourceSummary
                    + " para sugerir os campos certos e manter a decisão pronta para revisão.";
        }
        return "Entendi o que você quer criar. Vou usar " + resourceSummary
                + " para preparar uma prévia e manter a decisão pronta para revisão.";
    }

    String sharedRuleAuthoringAssistantMessage(AgenticAuthoringCandidate selectedCandidate) {
        String resourcePath = selectedCandidate == null ? "" : valueOrDefault(selectedCandidate.resourcePath(), "").trim();
        if (!resourcePath.isBlank()) {
            return "Esse pedido parece ser uma regra compartilhada do domínio, não apenas uma mudança visual da tela. Use /api/praxis/config/domain-rules/intake para registrar a intenção, /api/praxis/config/domain-rules/simulations para simulação de impacto e cobertura, e siga por revisão e publicação antes de aplicar qualquer materialização.";
        }
        return "Esse pedido parece ser uma regra compartilhada do domínio, não apenas uma mudança visual da tela. Primeiro confirme o recurso de negócio e então use /api/praxis/config/domain-rules/intake e /api/praxis/config/domain-rules/simulations para seguir por simulação, revisão e publicação com governança.";
    }

    JsonNode apiCatalogAnswer(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!quickReplySupport.isApiCatalogQuestion(operationKind, artifactKind, changeKind)
                || candidates == null || candidates.isEmpty()) {
            return null;
        }
        return objectMapper.valueToTree(Map.of(
                "schemaVersion", "praxis-agentic-authoring-api-catalog-grounding.v1",
                "questionType", "api_catalog_grounding",
                "candidateApis", candidates));
    }

    String apiCatalogAssistantMessage(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (usableCandidates.isEmpty()) {
            return "Ainda nao encontrei uma fonte de negocio confiavel para esse tema. Posso procurar melhor no catalogo ou voce pode me dizer qual dominio devo usar.";
        }
        return apiCatalogResourceListAssistantMessage(usableCandidates);
    }

    String apiCatalogResourceListAssistantMessage(List<AgenticAuthoringCandidate> candidates) {
        List<String> labels = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .limit(4)
                .map(candidate -> {
                    String reason = valueOrDefault(candidate.reason(), "");
                    return isHumanCatalogReason(reason) ? reason : quickReplySupport.candidateLabel(candidate);
                })
                .map(this::humanizeCatalogCandidate)
                .filter(label -> !label.isBlank())
                .distinct()
                .toList();
        if (labels.isEmpty()) {
            return "Encontrei fontes de negocio candidatas para esse tema, mas ainda preciso abrir os detalhes governados antes de recomendar uma delas.";
        }
        return "Encontrei " + labels.size() + " fonte" + (labels.size() == 1 ? "" : "s")
                + " de dados relacionada" + (labels.size() == 1 ? "" : "s")
                + " no catalogo de fontes de negocio: " + humanJoin(labels)
                + ". Posso comparar para que serve cada uma, explicar os campos disponiveis ou usar a mais aderente para montar uma tela. Vou manter isso como consulta ate voce pedir para criar algo.";
    }

    private boolean isHumanCatalogReason(String reason) {
        String normalized = normalize(reason);
        return !normalized.isBlank()
                && !reason.contains("/")
                && !containsAny(normalized,
                "fonte confirmada",
                "fonte governada",
                "fonte de negocio",
                "fonte de negócio",
                "evidence",
                "fallback",
                "weak lexical",
                "api metadata",
                "api_metadata",
                "broad artifact",
                "artifact discovery",
                "discovery",
                "semantic retrieval",
                "semantic_retrieval",
                "retrieval",
                "domain catalog",
                "domain_catalog",
                "grounded",
                "resource selection",
                "provenance",
                "listar",
                "filter",
                "operation");
    }

    private String humanizeCatalogCandidate(String value) {
        String text = valueOrDefault(value, "")
                .replace("/api/", "")
                .replace("vw-", "")
                .replace("-", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private String humanJoin(List<String> values) {
        return AgenticAuthoringTextNormalizationSupport.humanJoin(values);
    }

    private boolean containsAny(String value, String... tokens) {
        return AgenticAuthoringTextNormalizationSupport.containsAny(value, tokens);
    }

    private String normalize(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalize(value);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }

    private String valueOrUnknown(String value) {
        return AgenticAuthoringTextNormalizationSupport.valueOrUnknown(value);
    }
}
