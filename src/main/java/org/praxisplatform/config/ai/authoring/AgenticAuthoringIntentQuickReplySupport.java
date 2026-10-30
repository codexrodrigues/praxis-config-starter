package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Quick-reply generation, alignment affordance canonicalization, candidate presentation formatting,
 * and technical address sanitization for {@link AgenticAuthoringIntentResolverService}.
 */
final class AgenticAuthoringIntentQuickReplySupport {

    private static final int MAX_ASSISTANT_MESSAGE_CHARS = 700;

    private final ObjectMapper objectMapper;
    private final String domainCatalogServiceKey;
    private final AgenticAuthoringPresentationAffordanceDiscoveryService presentationAffordanceDiscoveryService;

    AgenticAuthoringIntentQuickReplySupport(
            ObjectMapper objectMapper,
            String domainCatalogServiceKey,
            AgenticAuthoringPresentationAffordanceDiscoveryService presentationAffordanceDiscoveryService) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.domainCatalogServiceKey = domainCatalogServiceKey;
        this.presentationAffordanceDiscoveryService = presentationAffordanceDiscoveryService;
    }

    String ambiguousResourceAssistantMessage(String artifactKind, List<AgenticAuthoringCandidate> candidates) {
        String artifactLabel = switch (artifactKind) {
            case "dashboard" -> "dashboard";
            case "table" -> "tabela";
            case "form" -> "formulario";
            case "page" -> "pagina";
            default -> "tela";
        };
        String options = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .limit(4)
                .map(candidate -> candidateLabel(candidate) + " - " + candidateDescription(candidate, artifactKind))
                .reduce((left, right) -> left + "; " + right)
                .orElse("opcoes do catalogo de APIs");
        return "Encontrei mais de uma fonte de dados possivel para este " + artifactLabel + ". "
                + "Escolha a fonte que melhor representa o recorte de negocio antes de gerar a pre-visualizacao. "
                + "Opcoes encontradas: " + options + ".";
    }

    String missingResourceAssistantMessage(String artifactKind) {
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

    List<AgenticAuthoringQuickReply> quickReplies(
            String effectivePrompt,
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate,
            List<String> questions,
            List<AgenticAuthoringCandidate> candidates,
            boolean answeredBareDomainClarification,
            Predicate<String> consultativePlatformCapabilityQuestionPredicate,
            Predicate<String> apiCatalogCandidateChoicePromptPredicate) {
        if (gate.messages().contains("assistant-choice-confirmation-required")) {
            return assistantChoiceQuickReplies(effectivePrompt, questions.isEmpty() ? "" : questions.get(0));
        }
        if (isDashboardFilterConnectionRequest(operationKind, artifactKind, changeKind, selectedCandidate)
                && !"eligible".equals(gate.status())) {
            return dashboardFilterConnectionQuickReplies(effectivePrompt, prompt, selectedCandidate);
        }
        if (selectedCandidate != null
                && "clarification_required".equals(gate.status())
                && gate.messages().contains("resource-candidate-ambiguous")) {
            return revisionQuickReplies(effectivePrompt);
        }
        if (isConsultativeDashboardWithSelectedCandidate(operationKind, artifactKind, selectedCandidate)) {
            return dashboardExplorationQuickReplies(effectivePrompt, selectedCandidate);
        }
        if ("component".equals(artifactKind)
                && consultativePlatformCapabilityQuestionPredicate != null
                && consultativePlatformCapabilityQuestionPredicate.test(prompt)) {
            return platformCapabilityQuickReplies(effectivePrompt, null);
        }
        if (!"explore".equals(operationKind) && !"clarification_required".equals(gate.status())) {
            return List.of();
        }
        if (gate.messages().contains("resource-candidate-required")) {
            return resourceDiscoveryQuickReplies(effectivePrompt, artifactKind);
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
        if (gate.messages().contains("resource-candidate-ambiguous")
                && selectedCandidate == null
                && !"api_catalog".equals(artifactKind)) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
        }
        if ("explore".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate == null
                && candidates != null
                && !candidates.isEmpty()) {
            return candidateResourceQuickReplies(effectivePrompt, candidates);
        }
        if ("api_catalog".equals(artifactKind)) {
            if (consultativePlatformCapabilityQuestionPredicate != null
                    && consultativePlatformCapabilityQuestionPredicate.test(prompt)) {
                return platformCapabilityQuickReplies(effectivePrompt, null);
            }
            if (isLiteralApiCatalogResourceListPrompt(prompt) && candidates != null && !candidates.isEmpty()) {
                return candidateResourceQuickReplies(effectivePrompt, candidates);
            }
            if (selectedCandidate == null && candidates != null && !candidates.isEmpty()) {
                if ((apiCatalogCandidateChoicePromptPredicate != null
                        && apiCatalogCandidateChoicePromptPredicate.test(prompt))
                        || isLiteralApiCatalogResourceListPrompt(prompt)) {
                    return candidateResourceQuickReplies(effectivePrompt, candidates);
                }
                return apiCatalogQuickReplies(effectivePrompt, prompt, candidates.get(0));
            }
            return apiCatalogQuickReplies(effectivePrompt, prompt, selectedCandidate);
        }
        if ("explore".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && selectedCandidate != null) {
            return dashboardExplorationQuickReplies(effectivePrompt, selectedCandidate);
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

    List<AgenticAuthoringQuickReply> ensureClarificationQuickReplies(
            String effectivePrompt,
            boolean clarificationRequired,
            List<AgenticAuthoringQuickReply> quickReplies) {
        if (!clarificationRequired || quickReplies != null && !quickReplies.isEmpty()) {
            return quickReplies == null ? List.of() : quickReplies;
        }
        return revisionQuickReplies(effectivePrompt);
    }

    List<AgenticAuthoringQuickReply> governedLlmQuickReplies(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate) {
        return governedLlmQuickReplies(llmIntent, selectedCandidate, null);
    }

    List<AgenticAuthoringQuickReply> governedLlmQuickReplies(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate) {
        if (llmIntent == null || llmIntent.quickReplies() == null || llmIntent.quickReplies().isEmpty()) {
            return List.of();
        }
        boolean blockedGate = gate != null && "blocked".equals(gate.status());
        ObjectNode selectedCandidateHints = selectedCandidate == null || blockedGate
                ? null
                : dashboardContextHints("", selectedCandidate);
        return llmIntent.quickReplies().stream()
                .filter(Objects::nonNull)
                .filter(this::isUsableLlmQuickReply)
                .map(reply -> enrichLlmQuickReply(reply, selectedCandidateHints))
                .limit(4)
                .toList();
    }

    List<AgenticAuthoringQuickReply> withSemanticResourceDiscoveryToolContext(
            List<AgenticAuthoringQuickReply> quickReplies,
            AgenticAuthoringGateResult gate,
            AgenticAuthoringLlmIntentResolution llmIntent,
            String artifactKind) {
        if (quickReplies == null
                || quickReplies.isEmpty()
                || gate == null
                || !gate.messages().contains("resource-candidate-required")) {
            return quickReplies == null ? List.of() : quickReplies;
        }
        boolean alreadyBound = quickReplies.stream()
                .filter(Objects::nonNull)
                .map(AgenticAuthoringQuickReply::contextHints)
                .filter(Objects::nonNull)
                .anyMatch(hints -> AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES.equals(
                        hints.path("tool").asText("")));
        if (alreadyBound) {
            return quickReplies;
        }
        String semanticQuery = llmIntent == null
                ? ""
                : valueOrDefault(llmIntent.resourceSearchQuery(), "").trim();
        if (semanticQuery.isBlank()) {
            semanticQuery = quickReplies.stream()
                    .filter(Objects::nonNull)
                    .filter(reply -> "suggestion".equals(valueOrDefault(reply.kind(), "")))
                    .map(AgenticAuthoringQuickReply::prompt)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(prompt -> !prompt.isBlank())
                    .findFirst()
                    .orElse("");
        }
        if (semanticQuery.isBlank()) {
            return quickReplies;
        }

        List<AgenticAuthoringQuickReply> bound = new ArrayList<>(quickReplies.size());
        boolean toolBound = false;
        for (AgenticAuthoringQuickReply reply : quickReplies) {
            if (!toolBound
                    && reply != null
                    && "suggestion".equals(valueOrDefault(reply.kind(), ""))) {
                ObjectNode contextHints = reply.contextHints() != null && reply.contextHints().isObject()
                        ? reply.contextHints().deepCopy()
                        : objectMapper.createObjectNode();
                contextHints.put("tool", AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
                contextHints.put("artifactKind", valueOrDefault(artifactKind, "unknown"));
                contextHints.put("retrievalQuery", semanticQuery);
                bound.add(new AgenticAuthoringQuickReply(
                        reply.id(),
                        reply.kind(),
                        reply.label(),
                        reply.prompt(),
                        reply.description(),
                        reply.icon(),
                        reply.tone(),
                        contextHints));
                toolBound = true;
            } else {
                bound.add(reply);
            }
        }
        return List.copyOf(bound);
    }

    private boolean isUsableLlmQuickReply(AgenticAuthoringQuickReply reply) {
        String label = valueOrDefault(reply.label(), "").trim();
        String prompt = valueOrDefault(reply.prompt(), "").trim();
        return !valueOrDefault(reply.id(), "").trim().isBlank()
                && !valueOrDefault(reply.kind(), "").trim().isBlank()
                && !label.isBlank()
                && !prompt.isBlank()
                && !containsTechnicalAddress(label)
                && !containsTechnicalAddress(valueOrDefault(reply.description(), ""));
    }

    private AgenticAuthoringQuickReply enrichLlmQuickReply(
            AgenticAuthoringQuickReply reply,
            ObjectNode selectedCandidateHints) {
        if (selectedCandidateHints == null || !shouldAttachSelectedCandidateHints(reply)) {
            return reply;
        }
        ObjectNode mergedHints = selectedCandidateHints.deepCopy();
        JsonNode replyHints = reply.contextHints();
        if (replyHints != null && replyHints.isObject()) {
            replyHints.fields().forEachRemaining(entry -> {
                if (!isTechnicalContextHintKey(entry.getKey())) {
                    mergedHints.set(entry.getKey(), entry.getValue());
                }
            });
        }
        return new AgenticAuthoringQuickReply(
                reply.id(),
                reply.kind(),
                reply.label(),
                reply.prompt(),
                reply.description(),
                reply.icon(),
                reply.tone(),
                mergedHints);
    }

    /**
     * Only continuations whose declared semantic role may consume the selected resource receive its
     * binding. The decision relies on the quick-reply {@code kind} contract authored with the reply;
     * identifiers are opaque and are never interpreted as intent.
     */
    private boolean shouldAttachSelectedCandidateHints(AgenticAuthoringQuickReply reply) {
        if (reply == null) {
            return false;
        }
        String kind = valueOrDefault(reply.kind(), "").trim().toLowerCase(Locale.ROOT);
        return !Set.of("cancel", "revise", "action", "info").contains(kind);
    }

    List<AgenticAuthoringQuickReply> canonicalizePresentationAffordanceQuickReplies(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringQuickReply> quickReplies) {
        if (request == null || quickReplies == null || quickReplies.isEmpty()) {
            return quickReplies == null ? List.of() : quickReplies;
        }
        Set<String> alignmentOptions = discoveredAlignmentOptions(request);
        if (alignmentOptions.isEmpty()) {
            return quickReplies;
        }
        List<String> values = quickReplies.stream()
                .map(this::alignmentOptionValue)
                .toList();
        if (values.stream().anyMatch(String::isBlank)
                || values.stream().distinct().count() < Math.min(2, values.size())) {
            return quickReplies;
        }
        for (String value : values) {
            if (!alignmentOptions.contains(value)) {
                return quickReplies;
            }
        }
        return quickReplies.stream()
                .map(reply -> canonicalAlignmentQuickReply(reply, alignmentOptionValue(reply), request))
                .toList();
    }

    private Set<String> discoveredAlignmentOptions(AgenticAuthoringIntentResolutionRequest request) {
        if (presentationAffordanceDiscoveryService == null) {
            return Set.of();
        }
        List<String> componentIds = Stream.of(
                        request.targetComponentId(),
                        jsonText(request.contextHints(), "targetComponentId"),
                        jsonText(request.contextHints(), "componentId"),
                        jsonText(request.contextHints() == null ? null : request.contextHints().path("authoringManifestRef"), "componentId"),
                        jsonText(request.contextHints() == null ? null : request.contextHints().path("identity"), "componentId"))
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        for (String componentId : componentIds) {
            Optional<JsonNode> discovery = presentationAffordanceDiscoveryService.discover(
                    new PresentationAffordanceDiscoveryToolRequest(
                            componentId,
                            componentId,
                            "column",
                            firstNonBlank(
                                    jsonText(request.contextHints(), "targetField"),
                                    jsonText(request.contextHints(), "columnField")),
                            null,
                            null,
                            null,
                            "unknown",
                            request.userPrompt(),
                            12));
            if (discovery.isEmpty()) {
                continue;
            }
            Set<String> options = alignmentOptionsFromDiscovery(discovery.get());
            if (!options.isEmpty()) {
                return options;
            }
        }
        return Set.of();
    }

    private Set<String> alignmentOptionsFromDiscovery(JsonNode discovery) {
        JsonNode affordances = discovery == null ? null : discovery.path("affordances");
        if (affordances == null || !affordances.isArray()) {
            return Set.of();
        }
        for (JsonNode affordance : affordances) {
            if ("column.align".equals(affordance.path("id").asText(""))
                    || "alignment".equals(affordance.path("category").asText(""))) {
                Set<String> options = StreamSupport.stream(affordance.path("options").spliterator(), false)
                        .filter(JsonNode::isTextual)
                        .map(JsonNode::asText)
                        .map(this::normalize)
                        .filter(option -> !option.isBlank())
                        .collect(Collectors.toSet());
                return options.containsAll(Set.of("left", "center", "right")) ? options : Set.of();
            }
        }
        return Set.of();
    }

    private AgenticAuthoringQuickReply canonicalAlignmentQuickReply(
            AgenticAuthoringQuickReply reply,
            String value,
            AgenticAuthoringIntentResolutionRequest request) {
        return new AgenticAuthoringQuickReply(
                firstNonBlank(reply.id(), "column-align-" + value),
                firstNonBlank(reply.kind(), "guided-option"),
                alignmentOptionLabel(value),
                value,
                firstNonBlank(reply.description(), alignmentOptionDescription(value)),
                alignmentOptionIcon(value),
                firstNonBlank(reply.tone(), "neutral"),
                canonicalAlignmentContextHints(reply.contextHints(), value, request),
                reply.semanticDecision(),
                reply.value());
    }

    private ObjectNode canonicalAlignmentContextHints(
            JsonNode existingHints,
            String value,
            AgenticAuthoringIntentResolutionRequest request) {
        ObjectNode hints = existingHints != null && existingHints.isObject()
                ? existingHints.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode selected = hints.putObject("optionSelected");
        selected.put("targetField", firstNonBlank(
                jsonText(existingHints, "targetField"),
                jsonText(request.contextHints(), "targetField"),
                jsonText(request.contextHints(), "columnField")));
        ObjectNode selection = selected.putObject("selection");
        selection.put("mode", "renderer");
        selection.put("value", "alignment:" + value);
        selection.put("affordanceId", "column.align");
        ObjectNode presentation = hints.putObject("presentation");
        presentation.put("kind", "guided-option");
        presentation.put("ctaLabel", "Aplicar alinhamento");
        presentation.put("icon", alignmentOptionIcon(value));
        presentation.put("tone", "neutral");
        ObjectNode affordance = hints.putObject("presentationAffordance");
        affordance.put("id", "column.align");
        affordance.put("category", "alignment");
        affordance.put("option", value);
        affordance.put("source", "presentationAffordanceDiscovery");
        return hints;
    }

    private String alignmentOptionValue(AgenticAuthoringQuickReply reply) {
        String prompt = normalize(valueOrDefault(reply == null ? null : reply.prompt(), ""));
        if (Set.of("left", "center", "right").contains(prompt)) {
            return prompt;
        }
        String label = normalize(valueOrDefault(reply == null ? null : reply.label(), ""));
        if (Set.of("left", "center", "right").contains(label)) {
            return label;
        }
        return "";
    }

    private String alignmentOptionLabel(String value) {
        return switch (value) {
            case "left" -> "Alinhar à esquerda";
            case "center" -> "Alinhar ao centro";
            case "right" -> "Alinhar à direita";
            default -> value;
        };
    }

    private String alignmentOptionDescription(String value) {
        return switch (value) {
            case "left" -> "Mantém o conteúdo encostado no início da célula.";
            case "center" -> "Centraliza o conteúdo da célula.";
            case "right" -> "Alinha o conteúdo ao fim da célula.";
            default -> "Aplica uma opção de alinhamento da coluna.";
        };
    }

    private String alignmentOptionIcon(String value) {
        return switch (value) {
            case "left" -> "format_align_left";
            case "center" -> "format_align_center";
            case "right" -> "format_align_right";
            default -> "format_align_left";
        };
    }

    boolean shouldUseLlmAuthoredQuickReplies(
            List<AgenticAuthoringQuickReply> llmAuthoredQuickReplies,
            AgenticAuthoringGateResult gate,
            boolean contextHintSelectionApplied,
            boolean promotedAssistantChoiceToClarification,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            boolean selectedCandidateHasTrustedSelectionEvidence) {
        if (isApiCatalogQuestion(operationKind, artifactKind, changeKind)) {
            return selectedCandidate != null
                    && selectedCandidateHasTrustedSelectionEvidence
                    && llmAuthoredQuickReplies != null
                    && !llmAuthoredQuickReplies.isEmpty();
        }
        if (isConsultativeDashboardWithSelectedCandidate(operationKind, artifactKind, selectedCandidate)) {
            return llmAuthoredQuickReplies != null
                    && !llmAuthoredQuickReplies.isEmpty()
                    && llmAuthoredQuickReplies.stream()
                    .noneMatch(reply -> valueOrDefault(reply.id(), "").startsWith("llm-"));
        }
        return llmAuthoredQuickReplies != null
                && !llmAuthoredQuickReplies.isEmpty()
                && gate != null
                && !"eligible".equals(gate.status())
                && !contextHintSelectionApplied
                && !promotedAssistantChoiceToClarification;
    }

    boolean isApiCatalogQuestion(String operationKind, String artifactKind, String changeKind) {
        return ("explore".equals(operationKind) || "explain".equals(operationKind))
                && "api_catalog".equals(artifactKind)
                && ("answer_api_catalog_question".equals(changeKind)
                || "answer_catalog_question".equals(changeKind)
                || "api_catalog_followup".equals(changeKind));
    }

    boolean contextHintSelectionApplied(
            AgenticAuthoringCandidate contextHintCandidate,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringGateResult gate) {
        return contextHintCandidate != null
                && selectedCandidate != null
                && gate != null
                && "eligible".equals(gate.status());
    }

    private boolean isTechnicalContextHintKey(String key) {
        return "technicalDetails".equals(key)
                || "resourcePath".equals(key)
                || "submitUrl".equals(key)
                || "schemaUrl".equals(key)
                || "submitMethod".equals(key);
    }

    private boolean containsTechnicalAddress(String value) {
        String normalized = valueOrDefault(value, "").toLowerCase(Locale.ROOT);
        return normalized.contains("/api/")
                || normalized.contains("/schemas/")
                || normalized.contains("http://")
                || normalized.contains("https://");
    }

    private boolean isConsultativeDashboardWithSelectedCandidate(
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate) {
        return ("explore".equals(operationKind) || "compose".equals(operationKind))
                && "dashboard".equals(artifactKind)
                && selectedCandidate != null;
    }

    private boolean isDashboardFilterConnectionRequest(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        if (!"dashboard".equals(artifactKind) || selectedCandidate == null) {
            return false;
        }
        String normalizedOperation = normalize(valueOrDefault(operationKind, ""));
        String normalizedChange = normalize(valueOrDefault(changeKind, ""));
        return "connect".equals(normalizedOperation)
                || normalizedChange.contains("filter")
                || normalizedChange.contains("filtro")
                || normalizedChange.contains("recorte")
                || normalizedChange.contains("dimension");
    }

    private List<AgenticAuthoringQuickReply> dashboardFilterConnectionQuickReplies(
            String effectivePrompt,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = dashboardContextHints(effectivePrompt, selectedCandidate);
        String normalizedPrompt = normalize(valueOrDefault(prompt, ""));
        boolean askedTemporalCut = containsAny(normalizedPrompt, "period", "tempo", "data", "mes", "ano", "competencia");
        boolean askedBusinessCut = containsAny(normalizedPrompt, "grupo", "categoria", "dimensao");
        String combinedLabel = askedTemporalCut && askedBusinessCut ? "Usar periodo e dimensao" : "Usar filtros e recortes";
        String combinedDecision = askedTemporalCut && askedBusinessCut
                ? "adicionar filtros de periodo e dimensao ao dashboard"
                : "adicionar filtros e recortes ao dashboard";
        String temporalLabel = askedTemporalCut ? "So periodo" : "Filtro temporal";
        String businessLabel = askedBusinessCut ? "So dimensao" : "Dimensao de negocio";
        return List.of(
                new AgenticAuthoringQuickReply(
                        "confirm-dashboard-filters",
                        "confirm",
                        combinedLabel,
                        AgenticAuthoringConversationPrompt.appendConfirmation(effectivePrompt, combinedDecision),
                        "Conecta controles antes da visualizacao usando a fonte semantica selecionada.",
                        "filter_alt",
                        "analytics",
                        contextHints.deepCopy()),
                new AgenticAuthoringQuickReply(
                        "dashboard-filter-period",
                        "confirm",
                        temporalLabel,
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "adicionar filtro temporal ao dashboard"),
                        "Permite recortar a analise por datas, ciclos ou periodos do dominio.",
                        "calendar_month",
                        "neutral",
                        contextHints.deepCopy()),
                new AgenticAuthoringQuickReply(
                        "dashboard-filter-dimension",
                        "confirm",
                        businessLabel,
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "adicionar filtro por dimensao de negocio ao dashboard"),
                        "Permite recortar a analise por uma dimensao semantica do dominio.",
                        "category",
                        "neutral",
                        contextHints.deepCopy()),
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

    List<AgenticAuthoringQuickReply> platformCapabilityQuickReplies(
            String effectivePrompt,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        String normalized = normalize(effectivePrompt);
        if (containsAny(normalized,
                "exportar", "selecionada", "selecionadas", "linhas selecionadas", "botao", "botão", "tabela")) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "table-export-selected-explain",
                            "suggestion",
                            "Ver configuração",
                            "Explique os passos para habilitar seleção, ação em massa e exportação das linhas marcadas em uma tabela Praxis.",
                            "Mostra como a tabela deve combinar seleção, toolbar e ação governada.",
                            "download",
                            "neutral",
                            quickReplyPresentation(
                                    "Boa para entender o comportamento antes de alterar a tela.",
                                    "Retorna orientação específica de tabela, sem buscar APIs de domínio por palavras soltas.",
                                    "Clique para detalhar a configuração.")),
                    new AgenticAuthoringQuickReply(
                            "table-export-selected-apply",
                            "suggestion",
                            "Aplicar na tabela",
                            "Na tabela selecionada, prepare a alteração para exportar apenas as linhas marcadas.",
                            "Usa o componente selecionado quando houver uma tabela ativa na página.",
                            "table_chart",
                            "resource",
                            quickReplyPresentation(
                                    "Boa quando você já está com a tabela correta selecionada.",
                                    "Retorna uma proposta de alteração governada para revisão.",
                                    "Clique depois de selecionar a tabela no canvas.")),
                    new AgenticAuthoringQuickReply(
                            "table-export-selected-backend",
                            "suggestion",
                            "Ver dados necessários",
                            "Quais dados ou ação de backend preciso confirmar para exportar as linhas selecionadas dessa tabela?",
                            "Separa a capacidade do componente da integração real com dados.",
                            "rule",
                            "analytics",
                            quickReplyPresentation(
                                    "Boa para validar o que falta antes de materializar.",
                                    "Retorna os pontos de dados e ação que precisam ser governados.",
                                    "Clique para revisar os pré-requisitos.")));
        }
        if (semanticOrientation != null && "dashboard".equals(semanticOrientation.artifactKind())) {
            return platformDashboardContinuationQuickReplies(effectivePrompt);
        }
        return List.of(
                new AgenticAuthoringQuickReply(
                        "platform-create-admin-dashboard",
                        "suggestion",
                        "Painel administrativo",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "continuar este objetivo criando um painel administrativo governado com os dados e componentes adequados"),
                        "Explora fontes, metricas, graficos, filtros e detalhes antes de materializar.",
                        "dashboard_customize",
                        "analytics",
                        conversationQuickReplyPresentation(
                                "Boa para administrar e acompanhar processos com indicadores e detalhes.",
                                "Retorna uma recomendacao de composicao governada antes da criacao.",
                                "Clique para pedir uma recomendacao de painel.",
                                "Continuar")),
                new AgenticAuthoringQuickReply(
                        "platform-create-form",
                        "suggestion",
                        "Formulario governado",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "continuar este objetivo com um formulario; escolha o processo, os campos e a acao governada antes de salvar"),
                        "Explica como transformar uma intencao em formulario sem inventar regra de negocio.",
                        "dynamic_form",
                        "resource",
                        conversationQuickReplyPresentation(
                                "Boa para cadastros, solicitacoes e edicoes guiadas.",
                                "Retorna perguntas e opcoes para groundar campos e acao de salvar.",
                                "Clique para iniciar a conversa de formulario.",
                                "Continuar")),
                new AgenticAuthoringQuickReply(
                        "platform-explore-components",
                        "suggestion",
                        "Ver possibilidades",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "mostrar quais componentes governados fazem sentido para este objetivo e para que serve cada um"),
                        "Mostra opcoes como tabela, grafico, formulario, abas e fluxos quando estiverem disponiveis.",
                        "widgets",
                        "neutral",
                        conversationQuickReplyPresentation(
                                "Boa para entender o que pode ser criado antes de escolher uma tela.",
                                "Retorna uma explicacao em linguagem simples sobre componentes e limites.",
                                "Clique para pedir o guia de componentes.",
                                "Explorar")));
    }

    private List<AgenticAuthoringQuickReply> platformDashboardContinuationQuickReplies(String effectivePrompt) {
        return List.of(
                new AgenticAuthoringQuickReply(
                        "platform-create-requested-dashboard",
                        "suggestion",
                        "Criar este dashboard",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "criar uma primeira versao governada deste dashboard usando os dados confirmados do dominio"),
                        "Monta uma primeira versao para revisar antes de salvar.",
                        "auto_awesome",
                        "analytics",
                        conversationQuickReplyPresentation(
                                "Quando o objetivo do dashboard ja esta claro.",
                                "Retorna uma previa governada para revisao.",
                                "Clique para materializar a primeira versao.",
                                "Criar")),
                new AgenticAuthoringQuickReply(
                        "platform-refine-dashboard-metrics",
                        "suggestion",
                        "Escolher indicadores",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "antes de criar, recomendar indicadores, recortes e visualizacoes aderentes a este dashboard"),
                        "Ajuda a definir o que acompanhar antes da prévia.",
                        "query_stats",
                        "neutral",
                        conversationQuickReplyPresentation(
                                "Quando voce quer revisar metricas e recortes primeiro.",
                                "Retorna sugestoes aderentes aos dados confirmados.",
                                "Clique para refinar o dashboard.",
                                "Refinar")),
                new AgenticAuthoringQuickReply(
                        "platform-review-dashboard-data",
                        "suggestion",
                        "Revisar dados disponíveis",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "mostrar quais dados governados estao disponiveis para este dashboard antes de materializar"),
                        "Confirma fontes e campos disponíveis para o objetivo.",
                        "dataset",
                        "resource",
                        conversationQuickReplyPresentation(
                                "Quando voce quer confirmar a cobertura dos dados.",
                                "Retorna fontes e campos governados relevantes.",
                                "Clique para revisar os dados disponiveis.",
                                "Revisar")));
    }

    List<AgenticAuthoringQuickReply> withPlatformQuickReplySemanticDecisions(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringQuickReply> quickReplies) {
        if (quickReplies == null || quickReplies.isEmpty()) {
            return List.of();
        }
        return quickReplies.stream()
                .map(reply -> withPlatformQuickReplySemanticDecision(request, reply))
                .toList();
    }

    private AgenticAuthoringQuickReply withPlatformQuickReplySemanticDecision(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringQuickReply reply) {
        if (reply == null || reply.semanticDecision() != null) {
            return reply;
        }
        PlatformQuickReplyDecision declaredDecision = switch (valueOrDefault(reply.id(), "")) {
            case "platform-create-admin-dashboard" ->
                    new PlatformQuickReplyDecision("create", "page", "create_artifact");
            case "platform-create-requested-dashboard" ->
                    new PlatformQuickReplyDecision("create", "dashboard", "create_artifact");
            case "platform-refine-dashboard-metrics" ->
                    new PlatformQuickReplyDecision("explore", "dashboard", "recommend_dashboard_visualization");
            case "platform-review-dashboard-data" ->
                    new PlatformQuickReplyDecision("explore", "api_catalog", "answer_api_catalog_question");
            case "platform-create-form" ->
                    new PlatformQuickReplyDecision("create", "form", "create_artifact");
            case "platform-explore-components" ->
                    new PlatformQuickReplyDecision("explain", "component", "answer_component_catalog_question");
            default -> null;
        };
        if (declaredDecision == null) {
            return reply;
        }
        ObjectNode contextHints = reply.contextHints() != null && reply.contextHints().isObject()
                ? reply.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.put("schemaVersion", "praxis-agentic-authoring-platform-action.v1");
        contextHints.put("source", "platform-capability-catalog");
        contextHints.put("operationKind", declaredDecision.operationKind());
        contextHints.put("artifactKind", declaredDecision.artifactKind());
        contextHints.put("changeKind", declaredDecision.changeKind());

        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", reply.id());
        constraints.put("continuationOf", "platform_capability_catalog");
        constraints.putArray("conceptKeys");
        AgenticAuthoringSemanticDecision semanticDecision = AgenticAuthoringSemanticDecision.from(
                        declaredDecision.operationKind(),
                        declaredDecision.artifactKind(),
                        declaredDecision.changeKind(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        request == null ? null : request.activeSemanticDecision(),
                        request == null ? "" : valueOrDefault(request.sessionId(), ""),
                        (request == null ? "" : valueOrDefault(request.clientTurnId(), "")) + ":" + reply.id(),
                        valueOrDefault(reply.prompt(), ""),
                        valueOrDefault(reply.prompt(), ""),
                        "The user may select this governed platform action after capability discovery.",
                        AgenticAuthoringSemanticRefinement.none())
                .withConstraints(constraints);
        return new AgenticAuthoringQuickReply(
                reply.id(),
                reply.kind(),
                reply.label(),
                reply.prompt(),
                reply.description(),
                reply.icon(),
                reply.tone(),
                contextHints,
                objectMapper.valueToTree(semanticDecision),
                reply.value());
    }

    private record PlatformQuickReplyDecision(
            String operationKind,
            String artifactKind,
            String changeKind) {
    }

    ObjectNode dashboardContextHints(
            String effectivePrompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("resourcePath", selectedCandidate.resourcePath());
        contextHints.put("submitUrl", selectedCandidate.submitUrl());
        contextHints.put("operation", selectedCandidate.operation());
        contextHints.put("schemaUrl", selectedCandidate.schemaUrl());
        contextHints.put("submitMethod", selectedCandidate.submitMethod());
        contextHints.set("technicalDetails", technicalDetails(selectedCandidate));
        AgenticAuthoringDomainCatalogHints.enrich(
                contextHints,
                selectedCandidate,
                null,
                effectivePrompt,
                domainCatalogServiceKey);
        return contextHints;
    }

    private List<AgenticAuthoringQuickReply> dashboardExplorationQuickReplies(
            String effectivePrompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = dashboardContextHints(effectivePrompt, selectedCandidate);
        return List.of(
                new AgenticAuthoringQuickReply(
                        "confirm-dashboard",
                        "confirm",
                        "Gerar previa governada",
                        AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "criar dashboard com " + candidateLabel(selectedCandidate)),
                        "Cria uma pre-visualizacao governada antes de salvar ou materializar.",
                        "dashboard_customize",
                        "analytics",
                        withQuickReplyPresentation(
                                contextHints,
                                "Indicada quando a fonte de dados ja parece correta e voce quer ver a primeira composicao.",
                                "Retorna uma previa com KPIs, graficos e componentes conectados ao recurso escolhido.",
                                "Clique para materializar uma proposta governada antes de salvar.")),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Refinar pedido",
                        effectivePrompt,
                        "Ajustar objetivo, dados, metricas ou escopo antes de gerar a pre-visualizacao.",
                        "tune",
                        "neutral",
                        quickReplyPresentation(
                                "Indicada quando ainda falta definir indicadores, filtros, granularidade ou publico do painel.",
                                "Retorna para a conversa para melhorar o pedido sem perder o contexto descoberto.",
                                "Clique para reformular o pedido com mais detalhes.")),
                new AgenticAuthoringQuickReply(
                        "cancel",
                        "cancel",
                        "Cancelar",
                        "",
                        "Encerra esta sugestao sem gerar previa ou alterar a pagina atual.",
                        "close",
                        "neutral",
                        quickReplyPresentation(
                                "Indicada quando a recomendacao nao combina com o objetivo atual.",
                                "Retorna ao estado atual da pagina sem aplicar mudancas.",
                                "Clique para descartar esta trilha de criacao.")));
    }

    private List<AgenticAuthoringQuickReply> candidateResourceQuickReplies(
            String effectivePrompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> visibleCandidates = candidates.stream()
                .limit(6)
                .toList();
        Map<String, Long> resourcePathCounts = visibleCandidates.stream()
                .collect(Collectors.groupingBy(
                        candidate -> valueOrDefault(candidate.resourcePath(), ""),
                        Collectors.counting()));
        return visibleCandidates.stream()
                .map(candidate -> {
                    ObjectNode contextHints = objectMapper.createObjectNode();
                    contextHints.put("resourcePath", candidate.resourcePath());
                    contextHints.put("submitUrl", candidate.submitUrl());
                    contextHints.put("operation", candidate.operation());
                    contextHints.put("schemaUrl", candidate.schemaUrl());
                    contextHints.put("submitMethod", candidate.submitMethod());
                    contextHints.set("technicalDetails", technicalDetails(candidate));
                    contextHints.set("presentation", candidatePresentation(candidate, null));
                    AgenticAuthoringDomainCatalogHints.enrich(
                            contextHints,
                            candidate,
                            null,
                            effectivePrompt,
                            domainCatalogServiceKey);
                    boolean duplicatedResourcePath = resourcePathCounts.getOrDefault(
                            valueOrDefault(candidate.resourcePath(), ""),
                            0L) > 1L;
                    return new AgenticAuthoringQuickReply(
                            quickReplyId(candidate, duplicatedResourcePath),
                            "suggestion",
                            candidateLabel(candidate),
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "usar " + candidateLabel(candidate)),
                            candidateFriendlyDescription(candidate, null),
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

    private List<AgenticAuthoringQuickReply> apiCatalogQuickReplies(
            String effectivePrompt,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode contextHints = selectedCandidate == null
                ? null
                : dashboardContextHints(effectivePrompt, selectedCandidate);
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        if (!containsAny(prompt, "schema", "schemas", "campo", "campos")) {
            replies.add(new AgenticAuthoringQuickReply(
                        "api-create-dashboard",
                        contextHints == null ? "suggestion" : "confirm",
                        "Criar dashboard",
                        contextHints == null
                                ? "Crie um dashboard usando a API recomendada."
                                : AgenticAuthoringConversationPrompt.appendConfirmation(
                                effectivePrompt,
                                "criar dashboard com " + candidateLabel(selectedCandidate)),
                        "Use quando voce ja quer transformar a fonte recomendada em uma primeira versao de painel governado.",
                        "dashboard_customize",
                        "analytics",
                        withQuickReplyPresentation(
                                contextHints,
                                "Boa para sair da descoberta e materializar um painel inicial.",
                                "Retorna uma pre-visualizacao com KPIs, graficos e componentes conectados ao recorte escolhido.",
                                "Clique para pedir a primeira composicao do dashboard.")));
        }
        replies.add(new AgenticAuthoringQuickReply(
                        "api-show-schema",
                        "suggestion",
                        "Ver campos",
                        "Quais campos existem na fonte recomendada?",
                        "Use quando voce quer entender quais campos, dimensoes e medidas podem virar filtros, colunas ou eixos.",
                        "view_list",
                        "resource",
                        withQuickReplyPresentation(
                                contextHints == null ? null : contextHints.deepCopy(),
                                "Boa para avaliar se a fonte tem os dados que voce precisa antes de criar a tela.",
                                "Retorna os campos principais, tipos e pistas de uso para formularios, tabelas e graficos.",
                                "Clique para pedir uma explicacao dos campos disponiveis.")));
        replies.add(new AgenticAuthoringQuickReply(
                        "api-show-actions",
                        "suggestion",
                        "Ver ações",
                        "Quais acoes e filtros essa fonte permite?",
                        "Use quando voce quer saber o que a fonte permite fazer: filtrar, consultar, criar, atualizar ou detalhar.",
                        "rule",
                        "resource",
                        withQuickReplyPresentation(
                                contextHints == null ? null : contextHints.deepCopy(),
                                "Boa para decidir a interacao certa da pagina, como filtros, drill-downs ou acoes de linha.",
                                "Retorna acoes, filtros e capacidades expostas pelo catalogo governado.",
                                "Clique para explorar as acoes suportadas pela fonte.")));
        return List.copyOf(replies);
    }

    private boolean isLiteralApiCatalogResourceListPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais apis",
                "que apis",
                "apis relacionadas",
                "apis disponiveis",
                "fontes de dados",
                "fontes disponiveis",
                "fontes relacionadas");
    }

    private ObjectNode quickReplyPresentation(String bestFor, String returns, String nextStep) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        ObjectNode presentation = contextHints.putObject("presentation");
        presentation.put("bestFor", bestFor);
        presentation.put("returns", returns);
        presentation.put("nextStep", nextStep);
        return contextHints;
    }

    private ObjectNode conversationQuickReplyPresentation(
            String bestFor,
            String returns,
            String nextStep,
            String ctaLabel) {
        ObjectNode contextHints = quickReplyPresentation(bestFor, returns, nextStep);
        ObjectNode presentation = (ObjectNode) contextHints.path("presentation");
        presentation.put("kind", "quick-action");
        presentation.put("ctaLabel", ctaLabel);
        return contextHints;
    }

    private ObjectNode withQuickReplyPresentation(
            ObjectNode contextHints,
            String bestFor,
            String returns,
            String nextStep) {
        ObjectNode enriched = contextHints == null ? objectMapper.createObjectNode() : contextHints;
        ObjectNode presentation = enriched.putObject("presentation");
        presentation.put("bestFor", bestFor);
        presentation.put("returns", returns);
        presentation.put("nextStep", nextStep);
        return enriched;
    }

    String conciseAssistantMessage(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.length() <= MAX_ASSISTANT_MESSAGE_CHARS) {
            return assistantMessage;
        }
        int cutoff = assistantMessage.lastIndexOf('.', MAX_ASSISTANT_MESSAGE_CHARS - 1);
        if (cutoff < 240) {
            cutoff = assistantMessage.lastIndexOf(' ', MAX_ASSISTANT_MESSAGE_CHARS - 1);
        }
        if (cutoff < 240) {
            cutoff = MAX_ASSISTANT_MESSAGE_CHARS - 1;
        }
        return assistantMessage.substring(0, cutoff).trim() + "...";
    }

    private String quickReplyId(AgenticAuthoringCandidate candidate, boolean includeOperation) {
        String base = "resource-" + normalize(candidate.resourcePath())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (!includeOperation) {
            return base;
        }
        String operation = valueOrDefault(candidate.operation(), "operation");
        String submitUrl = valueOrDefault(candidate.submitUrl(), candidate.resourcePath());
        String suffix = normalize(operation + "-" + submitUrl)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return suffix.isBlank() ? base : base + "-" + suffix;
    }

    String candidateLabel(AgenticAuthoringCandidate candidate) {
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

    String candidateDescription(AgenticAuthoringCandidate candidate, String artifactKind) {
        return switch (valueOrDefault(artifactKind, "")) {
            case "dashboard", "page" -> "Fonte candidata para alimentar o painel.";
            case "table" -> "Fonte candidata para alimentar a tabela.";
            case "form" -> "Operação candidata para o formulário.";
            default -> "Fonte candidata encontrada no catálogo.";
        };
    }

    private String candidateFriendlyDescription(AgenticAuthoringCandidate candidate, String artifactKind) {
        String resolvedKind = valueOrDefault(artifactKind, "");
        if ("dashboard".equals(resolvedKind) || "page".equals(resolvedKind)) {
            return "Indicada para montar um painel. Retorna dados que podem virar listas, indicadores e gráficos quando o schema confirmar os recortes.";
        }
        if ("table".equals(resolvedKind)) {
            return "Indicada quando você quer uma lista navegável. Retorna registros para tabela, filtros e detalhes.";
        }
        if ("form".equals(resolvedKind)) {
            return "Indicada quando você precisa cadastrar ou atualizar dados. Retorna uma operação governada para o formulário.";
        }
        return "Opção encontrada no catálogo semântico. Use para explorar quais dados ela oferece antes de criar a tela.";
    }

    private ObjectNode candidatePresentation(AgenticAuthoringCandidate candidate, String artifactKind) {
        ObjectNode presentation = objectMapper.createObjectNode();
        String resolvedKind = valueOrDefault(artifactKind, "");
        if ("dashboard".equals(resolvedKind) || "page".equals(resolvedKind)) {
            presentation.put("bestFor", "Boa para painéis com acompanhamento de registros, gráficos e drill-down quando o schema confirmar os recortes.");
            presentation.put("returns", "Retorna dados de negócio que podem alimentar cards, listas e gráficos materializados por schema.");
            presentation.put("nextStep", "Clique para usar esta fonte como recorte inicial da pré-visualização.");
            return presentation;
        }
        if ("table".equals(resolvedKind)) {
            presentation.put("bestFor", "Boa para consultar, filtrar e comparar registros em uma lista.");
            presentation.put("returns", "Retorna coleções navegáveis com campos para colunas, busca e detalhes.");
            presentation.put("nextStep", "Clique para criar a tabela usando esta fonte.");
            return presentation;
        }
        if ("form".equals(resolvedKind)) {
            presentation.put("bestFor", "Boa para capturar ou atualizar informações com governança.");
            presentation.put("returns", "Retorna a operação que o formulário deve executar ao salvar.");
            presentation.put("nextStep", "Clique para usar esta operação no formulário.");
            return presentation;
        }
        presentation.put("bestFor", "Boa para explorar uma fonte semântica disponível no catálogo.");
        presentation.put("returns", "Retorna dados ou operações que podem ser materializados em componentes.");
        presentation.put("nextStep", "Clique para investigar esta opção no próximo passo.");
        return presentation;
    }

    boolean shouldHideTechnicalAddresses(
            AgenticAuthoringIntentResolutionRequest request,
            String operationKind,
            String artifactKind,
            boolean llmRequiresGovernedAuthoring) {
        return !"api_catalog".equals(artifactKind)
                && !"explore".equals(operationKind)
                && !llmRequiresGovernedAuthoring
                && !"shared_rule_authoring".equals(requestedAuthoringFlow(request));
    }

    List<AgenticAuthoringQuickReply> sanitizeQuickReplies(
            List<AgenticAuthoringQuickReply> quickReplies,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (quickReplies == null || quickReplies.isEmpty()) {
            return List.of();
        }
        return quickReplies.stream()
                .map(reply -> new AgenticAuthoringQuickReply(
                        reply.id(),
                        reply.kind(),
                        sanitizePresentationText(reply.label(), selectedCandidate, candidates),
                        sanitizePresentationText(reply.prompt(), selectedCandidate, candidates),
                        sanitizePresentationText(reply.description(), selectedCandidate, candidates),
                        reply.icon(),
                        reply.tone(),
                        reply.contextHints(),
                        reply.semanticDecision(),
                        reply.value()))
                .toList();
    }

    String sanitizePresentationText(
            String text,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String sanitized = text;
        List<AgenticAuthoringCandidate> allCandidates = new ArrayList<>();
        if (selectedCandidate != null) {
            allCandidates.add(selectedCandidate);
        }
        if (candidates != null) {
            allCandidates.addAll(candidates);
        }
        for (AgenticAuthoringCandidate candidate : allCandidates.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                candidate -> valueOrDefault(candidate.resourcePath(), "") + "|" + valueOrDefault(candidate.submitUrl(), ""),
                                candidate -> candidate,
                                (left, right) -> left),
                        map -> map.values().stream()
                                .sorted(Comparator.comparingInt((AgenticAuthoringCandidate candidate) ->
                                        technicalAddressLength(candidate)).reversed())
                                .toList()))) {
            String label = candidateLabel(candidate);
            sanitized = replaceTechnicalAddress(sanitized, candidate.schemaUrl(), "Detalhes técnicos");
            sanitized = replaceTechnicalAddress(sanitized, candidate.submitUrl(), label);
            sanitized = replaceTechnicalAddress(sanitized, candidate.resourcePath(), label);
        }
        return sanitized;
    }

    private int technicalAddressLength(AgenticAuthoringCandidate candidate) {
        return Math.max(
                valueOrDefault(candidate.submitUrl(), "").length(),
                Math.max(
                        valueOrDefault(candidate.schemaUrl(), "").length(),
                        valueOrDefault(candidate.resourcePath(), "").length()));
    }

    private String replaceTechnicalAddress(String text, String address, String replacement) {
        String normalizedAddress = valueOrDefault(address, "").trim();
        String normalizedReplacement = valueOrDefault(replacement, "").trim();
        if (text == null
                || normalizedAddress.isBlank()
                || normalizedReplacement.isBlank()
                || !normalizedAddress.contains("/")) {
            return text;
        }
        String next = text.replaceAll(
                "(?i)\\b(GET|POST|PUT|PATCH|DELETE)\\s+" + Pattern.quote(normalizedAddress),
                normalizedReplacement);
        return next.replace(normalizedAddress, normalizedReplacement);
    }

    private ObjectNode technicalDetails(AgenticAuthoringCandidate candidate) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("resourcePath", valueOrDefault(candidate.resourcePath(), ""));
        details.put("submitUrl", valueOrDefault(candidate.submitUrl(), candidate.resourcePath()));
        details.put("submitMethod", valueOrDefault(candidate.submitMethod(), candidate.operation()).toUpperCase(Locale.ROOT));
        details.put("operation", valueOrDefault(candidate.operation(), ""));
        details.put("schemaUrl", valueOrDefault(candidate.schemaUrl(), ""));
        return details;
    }

    private String candidateIcon(AgenticAuthoringCandidate candidate) {
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "edit_note";
        }
        return "dataset";
    }

    private String candidateTone(AgenticAuthoringCandidate candidate) {
        if ("post".equalsIgnoreCase(candidate.operation())) {
            return "primary";
        }
        return "resource";
    }

    List<AgenticAuthoringQuickReply> confirmationQuickReplies(String effectivePrompt, String question) {
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

    List<AgenticAuthoringQuickReply> assistantChoiceQuickReplies(String effectivePrompt, String question) {
        if (isDashboardTableChoiceQuestion(question)) {
            return List.of(
                    new AgenticAuthoringQuickReply(
                            "dashboard-completo",
                            "confirm",
                            "Dashboard completo",
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "criar um dashboard completo com resumo, graficos, filtros e tabela detalhada"),
                            "Inclui indicadores, graficos de status/valor, filtros e uma tabela para investigar os pedidos.",
                            "dashboard_customize",
                            "primary",
                            quickReplyPresentation(
                                    "Indicada quando voce quer acompanhar um processo em nivel executivo e operacional.",
                                    "Retorna uma pagina com resumo, graficos, filtros e detalhes conectados ao recurso escolhido.",
                                    "Clique para gerar uma previa governada mais completa.")),
                    new AgenticAuthoringQuickReply(
                            "tabela-filtravel",
                            "confirm",
                            "Apenas tabela filtravel",
                            AgenticAuthoringConversationPrompt.appendConfirmation(
                                    effectivePrompt,
                                    "criar apenas uma tabela filtravel com valores formatados, chips de status e acoes por linha"),
                            "Foca na listagem dos pedidos com colunas ricas, formatos, chips e acoes de investigacao.",
                            "table_chart",
                            "neutral",
                            quickReplyPresentation(
                                    "Indicada quando a prioridade e localizar, comparar e abrir detalhes dos pedidos rapidamente.",
                                    "Retorna uma tabela com filtros, valores formatados, status visual e acoes por item.",
                                    "Clique para gerar uma previa focada em listagem.")),
                    new AgenticAuthoringQuickReply(
                            "ajustar-pedido",
                            "revise",
                            "Ajustar pedido",
                            effectivePrompt,
                            "Permite acrescentar colunas, filtros, agrupamentos ou regras antes de gerar a previa.",
                            "tune",
                            "neutral",
                            quickReplyPresentation(
                                    "Indicada quando ainda falta algum detalhe de negocio ou formato.",
                                    "Mantem o contexto e permite refinar a solicitacao.",
                                    "Clique para continuar conversando antes da criacao.")));
        }
        return confirmationQuickReplies(effectivePrompt, question);
    }

    List<AgenticAuthoringQuickReply> revisionQuickReplies(String effectivePrompt) {
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

    String requestedAuthoringFlow(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        JsonNode domainCatalog = contextHints == null ? null : contextHints.path("domainCatalog");
        if (domainCatalog == null || domainCatalog.isMissingNode()) {
            return "";
        }
        return normalize(domainCatalog.path("recommendedAuthoringFlow").asText(""));
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

    boolean shouldPromoteAssistantChoiceToClarification(
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringGateResult gate,
            String assistantMessage) {
        if (llmIntent == null
                || "platform_guidance".equals(llmIntent.semanticIntentClass())
                || "domain_decision_guidance".equals(llmIntent.semanticIntentClass())
                || llmIntent.quickReplies() == null
                || !llmIntent.quickReplies().isEmpty()
                || gate == null
                || !"eligible".equals(gate.status())
                || assistantMessage == null
                || assistantMessage.isBlank()) {
            return false;
        }
        String normalized = normalize(assistantMessage);
        return normalized.contains("?")
                && (containsAny(normalized, "deseja", "prefere", "voce prefere", "você prefere", "quer que eu", "posso")
                || normalized.contains(" ou "))
                && containsAny(normalized, "dashboard", "tabela", "grafico", "filtro", "painel");
    }

    private boolean isDashboardTableChoiceQuestion(String question) {
        String normalized = normalize(question);
        return (normalized.contains("dashboard") || normalized.contains("painel"))
                && normalized.contains("tabela")
                && (normalized.contains(" ou ") || normalized.contains("apenas"));
    }

    String formatCandidateOptions(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        return candidates.stream()
                .limit(3)
                .map(this::candidateLabel)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalizedValue = normalize(value);
        String wordPaddedValue = null;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = normalize(token);
            if (requiresWholeWordMatch(normalizedToken)) {
                if (wordPaddedValue == null) {
                    wordPaddedValue = " " + normalizedValue.replaceAll("[^a-z0-9]+", " ") + " ";
                }
                if (wordPaddedValue.contains(" " + normalizedToken + " ")) {
                    return true;
                }
                continue;
            }
            if (normalizedValue.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresWholeWordMatch(String token) {
        return token.length() <= 4 && token.chars().allMatch(Character::isLetterOrDigit);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String jsonText(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
