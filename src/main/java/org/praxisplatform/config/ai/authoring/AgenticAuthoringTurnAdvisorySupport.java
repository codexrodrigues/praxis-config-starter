package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine.AgenticAuthoringTurnState;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.springframework.util.StringUtils;

/**
 * Encapsulates turn route classification, consultative catalog/schema message synthesis, and
 * reviewable preview message guarantees for {@link AgenticAuthoringTurnEngine}.
 */
final class AgenticAuthoringTurnAdvisorySupport {

    record CatalogSchemaSummary(String label, boolean schemaConfirmed, List<String> fields) {
    }

    record AgenticAuthoringTurnRoute(String routeClass, boolean allowsPreview) {
    }

    private final SchemaRetrievalService schemaRetrievalService;

    AgenticAuthoringTurnAdvisorySupport(SchemaRetrievalService schemaRetrievalService) {
        this.schemaRetrievalService = schemaRetrievalService;
    }

    AgenticAuthoringTurnRoute classifyRoute(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnState state) {
        if (requiresSharedRuleAuthoring(intentResolution)) {
            return new AgenticAuthoringTurnRoute(
                    hasComponentAuthoringSignal(request, intentResolution, state) ? "mixed" : "shared_rule_authoring",
                    false);
        }
        if (needsClarification(intentResolution)) {
            return new AgenticAuthoringTurnRoute("needs_clarification", false);
        }
        if (!allowsMaterializedPreview(intentResolution)) {
            return new AgenticAuthoringTurnRoute("advisory_authoring", false);
        }
        return new AgenticAuthoringTurnRoute("component_authoring", true);
    }

    String previewAssistantMessage(
            String userPrompt,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            String schemaBaseUrl) {
        String catalogMessage = consultativeCatalogAssistantMessage(
                userPrompt,
                intentResolution,
                resourceDiscovery,
                businessCatalogDiscovery,
                schemaBaseUrl);
        if (!catalogMessage.isBlank()) {
            return catalogMessage;
        }
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        return !message.isBlank() ? message : safeText(intentResolution.assistantMessage());
    }

    String consultativeCatalogAssistantMessage(
            String userPrompt,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            String schemaBaseUrl) {
        if (isPlatformGuidancePrompt(intentResolution == null ? "" : intentResolution.effectivePrompt())) {
            return safeText(intentResolution == null ? "" : intentResolution.assistantMessage());
        }
        if (!isAdvisoryCatalogIntent(intentResolution)) {
            if (!isUnresolvedAdvisoryIntent(intentResolution)
                    || !hasDiscoveryCandidates(resourceDiscovery, businessCatalogDiscovery)) {
                return "";
            }
        }
        String projectionMessage = consultativeProjectionAssistantMessage(
                firstNonBlank(userPrompt, intentResolution == null ? "" : intentResolution.effectivePrompt()),
                businessCatalogDiscovery,
                resourceDiscovery);
        if (!projectionMessage.isBlank()) {
            return projectionMessage;
        }
        List<AgenticAuthoringCandidate> candidates = catalogCandidates(
                resourceDiscovery,
                businessCatalogDiscovery,
                intentResolution);
        if (candidates.isEmpty()) {
            return "Ainda nao encontrei fontes de dados governadas suficientes para responder com seguranca. "
                    + "Posso tentar de novo com um recorte de area, entidade ou processo mais especifico.";
        }
        List<CatalogSchemaSummary> schemaSummaries = candidates.stream()
                .limit(4)
                .map(candidate -> catalogSchemaSummary(candidate, schemaBaseUrl))
                .toList();
        List<CatalogSchemaSummary> confirmedSchemas = schemaSummaries.stream()
                .filter(summary -> summary.schemaConfirmed() && !summary.fields().isEmpty())
                .toList();
        String sources = humanJoin(candidates.stream()
                .limit(4)
                .map(this::businessResourceLabel)
                .filter(label -> !label.isBlank())
                .distinct()
                .toList());
        if (sources.isBlank()) {
            sources = "fontes governadas do catalogo";
        }
        if (confirmedSchemas.isEmpty()) {
            return "Encontrei dados em " + sources + ", mas ainda nao consegui confirmar os campos disponiveis. "
                    + "Por enquanto, consigo explicar as fontes disponiveis e recomendar telas em nivel de negocio; "
                    + "antes de materializar graficos, tabelas ou formularios, preciso validar os campos da fonte escolhida.";
        }
        StringBuilder message = new StringBuilder();
        message.append("Encontrei dados governados em ").append(sources).append(". ");
        message.append("Pelos campos confirmados, da para trabalhar com ");
        message.append(confirmedSchemas.stream()
                .map(summary -> schemaBusinessSummary(summary.label(), summary.fields()))
                .limit(3)
                .reduce((left, right) -> left + "; " + right)
                .orElse(""));
        message.append(". ");
        message.append("Eu recomendaria ").append(screenRecommendations(candidates, confirmedSchemas));
        message.append(". Quando voce pedir para criar, eu materializo usando apenas campos confirmados.");
        return message.toString();
    }

    static JsonNode assistantContent(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery) {
        if (intentResolution != null && intentResolution.assistantContent() != null) {
            return intentResolution.assistantContent();
        }
        JsonNode content = businessCatalogDiscovery == null ? null : businessCatalogDiscovery.assistantContent();
        if (content != null) {
            return content;
        }
        return resourceDiscovery == null ? null : resourceDiscovery.assistantContent();
    }

    static String groundedPreviewAssistantMessage(
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String message = preview == null ? "" : safeText(preview.assistantMessage());
        if (!message.isBlank()
                && !message.contains("revisao de governanca")
                && !isGenericPreviewReadyMessage(message)) {
            return message;
        }
        String artifactKind = intentResolution == null ? "" : safeText(intentResolution.artifactKind());
        if ("dashboard".equals(artifactKind)) {
            return "Montei uma primeira versao de dashboard com a fonte confirmada. "
                    + "Ela ja inclui grafico, filtros, KPIs e tabela de detalhe conectada; revise a pre-visualizacao "
                    + "e salve quando estiver de acordo.";
        }
        return "Montei uma primeira versao com a fonte confirmada. "
                + "Revise o resultado e salve quando estiver de acordo.";
    }

    static boolean isAdvisoryCatalogIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        String operationKind = safeText(intentResolution.operationKind());
        String artifactKind = safeText(intentResolution.artifactKind());
        String changeKind = safeText(intentResolution.changeKind());
        return ("explore".equals(operationKind) || "explain".equals(operationKind))
                && "api_catalog".equals(artifactKind)
                && ("answer_api_catalog_question".equals(changeKind)
                || "answer_catalog_question".equals(changeKind)
                || "api_catalog_followup".equals(changeKind));
    }

    static boolean isPlatformGuidancePrompt(String prompt) {
        String normalized = normalizeText(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean question = containsAny(normalized,
                "o que posso", "o que consigo", "o que da para", "o que dá para",
                "o que voce pode", "o que você pode", "como faco", "como faço",
                "como criar", "como montar", "quais componentes", "que componentes",
                "quais widgets", "que widgets", "quais telas", "que telas",
                "quais paginas", "que paginas", "posso criar", "daria para criar",
                "da para criar", "dá para criar", "posso montar", "posso fazer");
        boolean platformSubject = containsAny(normalized,
                "aqui", "praxis", "page builder", "builder", "assistente",
                "componente", "componentes", "widget", "widgets",
                "tela", "telas", "pagina", "paginas", "dashboard", "painel",
                "formulario", "formulário", "tabela", "grafico", "gráfico",
                "aba", "abas", "tabs", "stepper", "administrativo", "admin",
                "livremente", "predefinido", "predefinidos", "pre definido", "pre definidos");
        boolean dataCatalogSubject = containsAny(normalized,
                "dados", "fonte", "fontes", "api", "apis", "schema", "schemas",
                "campo", "campos", "recurso", "recursos", "entidade", "entidades");
        boolean formPolicySubject = containsAny(normalized,
                "formulario", "formulário", "livre", "livremente",
                "predefinido", "predefinidos", "pre definido", "pre definidos",
                "governado", "governada");
        return question && platformSubject && (!dataCatalogSubject || formPolicySubject);
    }

    static boolean isUnresolvedAdvisoryIntent(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        String operationKind = safeText(intentResolution.operationKind());
        String artifactKind = safeText(intentResolution.artifactKind());
        return !intentResolution.valid()
                || "unknown".equals(operationKind)
                || "unknown".equals(artifactKind);
    }

    static boolean hasDiscoveryCandidates(
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        return (resourceDiscovery != null
                && resourceDiscovery.candidates() != null
                && !resourceDiscovery.candidates().isEmpty())
                || (businessCatalogDiscovery != null
                && businessCatalogDiscovery.candidates() != null
                && !businessCatalogDiscovery.candidates().isEmpty());
    }

    static String ensureReviewablePreviewMessage(
            String value,
            AgenticAuthoringTurnStreamRequest request,
            boolean canApply) {
        return ensureReviewablePreviewMessage(value, request, canApply, "");
    }

    static String ensureReviewablePreviewMessage(
            String value,
            AgenticAuthoringTurnStreamRequest request,
            boolean canApply,
            String applyBlockReason) {
        String message = value == null ? "" : value.trim();
        if (!canApply) {
            if ("apply-target-missing".equals(applyBlockReason)) {
                String responseLocale = request == null || request.contextHints() == null
                        ? ""
                        : request.contextHints().path("responseLocale").asText("");
                String clarification = !responseLocale.isBlank()
                                && !responseLocale.toLowerCase(Locale.ROOT).startsWith("pt")
                        ? "The preview is ready for review, but it cannot be saved in this turn until the application target is identified."
                        : "A prévia está pronta para revisão, mas ainda não pode ser salva neste turno porque o destino de aplicação não foi identificado.";
                return message.isBlank() ? clarification : message + "\n\n" + clarification;
            }
            return message;
        }
        String normalized = Normalizer.normalize(message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        boolean reviewMeaningPresent = normalized.contains("previa")
                || normalized.contains("pre visualizacao")
                || normalized.contains("preview")
                || normalized.contains("revis");
        if (reviewMeaningPresent) {
            return message;
        }
        String responseLocale = request == null || request.contextHints() == null
                ? ""
                : request.contextHints().path("responseLocale").asText("");
        String guarantee = !responseLocale.isBlank()
                        && !responseLocale.toLowerCase(Locale.ROOT).startsWith("pt")
                ? "The preview is ready for review before saving."
                : "A prévia está pronta para revisão antes de salvar.";
        return message.isBlank() ? guarantee : message + "\n\n" + guarantee;
    }

    private String consultativeProjectionAssistantMessage(
            String userPrompt,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringResourceCandidatesResult resourceDiscovery) {
        AgenticAuthoringConsultativeApiCatalogProjection projection =
                businessCatalogDiscovery == null ? null : businessCatalogDiscovery.consultativeProjection();
        if (projection == null || !projection.hasResources()) {
            projection = resourceDiscovery == null ? null : resourceDiscovery.consultativeProjection();
        }
        if (projection == null || !projection.hasResources()) {
            return "";
        }
        String unsupportedDomainMessage = AgenticAuthoringConsultativeGroundingAlignment.unsupportedDomainMessage(
                userPrompt,
                projection.resources());
        if (StringUtils.hasText(unsupportedDomainMessage)) {
            return unsupportedDomainMessage;
        }
        return safeText(projection.assistantMessage());
    }

    private List<AgenticAuthoringCandidate> catalogCandidates(
            AgenticAuthoringResourceCandidatesResult resourceDiscovery,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        addCandidates(candidates, businessCatalogDiscovery == null ? null : businessCatalogDiscovery.candidates());
        addCandidates(candidates, resourceDiscovery == null ? null : resourceDiscovery.candidates());
        if (intentResolution != null) {
            addCandidates(candidates, intentResolution.candidates());
            if (intentResolution.selectedCandidate() != null) {
                addCandidates(candidates, List.of(intentResolution.selectedCandidate()));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        return candidates.stream()
                .filter(candidate -> candidate != null && StringUtils.hasText(candidate.resourcePath()))
                .filter(candidate -> seen.add(normalizeText(candidate.resourcePath())))
                .toList();
    }

    private void addCandidates(List<AgenticAuthoringCandidate> target, List<AgenticAuthoringCandidate> candidates) {
        if (target == null || candidates == null) {
            return;
        }
        candidates.stream()
                .filter(Objects::nonNull)
                .forEach(target::add);
    }

    private CatalogSchemaSummary catalogSchemaSummary(AgenticAuthoringCandidate candidate, String schemaBaseUrl) {
        String label = businessResourceLabel(candidate);
        if (schemaRetrievalService == null) {
            return new CatalogSchemaSummary(label, false, List.of());
        }
        AiSchemaContext context = schemaContext(candidate);
        if (context == null) {
            return new CatalogSchemaSummary(label, false, List.of());
        }
        SchemaFetchResult schemaResult = schemaRetrievalService.fetchSchemaResult(context, schemaBaseUrl);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            return new CatalogSchemaSummary(label, false, List.of());
        }
        return new CatalogSchemaSummary(label, true, schemaFieldLabels(schemaResult.getSchema()));
    }

    private AiSchemaContext schemaContext(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        Map<String, String> query = queryParameters(candidate.schemaUrl());
        String path = firstNonBlank(query.get("path"), candidate.submitUrl(), candidate.resourcePath());
        String operation = firstNonBlank(query.get("operation"), candidate.submitMethod(), candidate.operation(), "get");
        String schemaType = firstNonBlank(query.get("schemaType"), "response");
        String businessPath = businessResourcePath(firstNonBlank(path, candidate.submitUrl(), candidate.resourcePath()));
        if (isStatsPath(path) || isStatsPath(candidate.submitUrl()) || isStatsPath(candidate.resourcePath())) {
            path = businessPath + "/filter/cursor";
            operation = "post";
            schemaType = "response";
        }
        if (!businessPath.isBlank() && normalizeText(path).equals(normalizeText(businessPath))
                && "get".equalsIgnoreCase(operation)) {
            path = businessPath + "/filter/cursor";
            operation = "post";
            schemaType = "response";
        }
        if (path.isBlank() || operation.isBlank() || schemaType.isBlank()) {
            return null;
        }
        return AiSchemaContext.builder()
                .path(path)
                .operation(operation)
                .schemaType(schemaType)
                .build();
    }

    private Map<String, String> queryParameters(String url) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String value = safeText(url);
        int queryIndex = value.indexOf('?');
        if (queryIndex < 0 || queryIndex == value.length() - 1) {
            return parameters;
        }
        for (String pair : value.substring(queryIndex + 1).split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String parameterValue = equals >= 0 ? pair.substring(equals + 1) : "";
            parameters.put(urlDecode(key), urlDecode(parameterValue));
        }
        return parameters;
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(safeText(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return safeText(value);
        }
    }

    private String businessResourcePath(String path) {
        String value = safeText(path).trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/filter/cursor")) {
            return value.substring(0, value.length() - "/filter/cursor".length());
        }
        if (value.endsWith("/stats")) {
            return value.substring(0, value.length() - "/stats".length());
        }
        return value;
    }

    private boolean isStatsPath(String path) {
        String normalized = safeText(path).toLowerCase(Locale.ROOT);
        return normalized.contains("/stats");
    }

    private List<String> schemaFieldLabels(JsonNode schema) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        collectSchemaFieldLabels(schema, labels);
        return labels.stream().limit(6).toList();
    }

    private void collectSchemaFieldLabels(JsonNode node, Set<String> labels) {
        if (node == null || node.isMissingNode() || node.isNull() || labels.size() >= 8) {
            return;
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                if (labels.size() >= 8 || shouldHideField(entry.getKey(), entry.getValue())) {
                    return;
                }
                String label = firstNonBlank(
                        entry.getValue().path("x-ui").path("label").asText(""),
                        humanizeToken(entry.getKey()));
                if (!label.isBlank()) {
                    labels.add(label);
                }
            });
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSchemaFieldLabels(entry.getValue(), labels));
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectSchemaFieldLabels(item, labels);
            }
        }
    }

    private boolean shouldHideField(String fieldName, JsonNode field) {
        JsonNode ui = field == null ? MissingNode.getInstance() : field.path("x-ui");
        String label = normalizeText(firstNonBlank(
                ui.path("label").asText(""),
                field == null ? "" : field.path("title").asText("")));
        String normalizedFieldName = normalizeText(humanizeToken(fieldName));
        return ui.path("tableHidden").asBoolean(false)
                || ui.path("formHidden").asBoolean(false)
                || "password".equalsIgnoreCase(ui.path("controlType").asText(""))
                || isTechnicalIdentifier(normalizedFieldName)
                || Set.of(
                        "id",
                        "uuid",
                        "created at",
                        "updated at",
                        "created by",
                        "updated by",
                        "field",
                        "granularity",
                        "metric",
                        "metrics",
                        "points",
                        "operation",
                        "alias",
                        "start",
                        "end")
                .contains(label);
    }

    private boolean isTechnicalIdentifier(String value) {
        String normalized = safeText(value);
        return "id".equals(normalized)
                || "uuid".equals(normalized)
                || normalized.endsWith(" id")
                || normalized.endsWith(" uuid");
    }

    private String schemaBusinessSummary(String label, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return safeText(label);
        }
        return safeText(label) + " cobrindo " + humanJoin(fields);
    }

    private String screenRecommendations(
            List<AgenticAuthoringCandidate> candidates,
            List<CatalogSchemaSummary> confirmedSchemas) {
        List<String> recommendations = new ArrayList<>();
        recommendations.add("uma lista com filtros e detalhe para consulta operacional");
        if (hasCandidate(candidates, "analytics")
                || hasCandidate(candidates, "analit")
                || hasCandidate(candidates, "indicador")
                || hasCandidate(candidates, "folha")) {
            recommendations.add("um dashboard para acompanhar indicadores e distribuicoes confirmadas pelo schema");
        }
        if (hasCandidate(candidates, "historico")
                || hasCandidate(candidates, "eventos")
                || hasCandidate(candidates, "history")
                || hasField(confirmedSchemas, "data inicio")
                || hasField(confirmedSchemas, "data fim")) {
            recommendations.add("uma aba de historico ou linha do tempo para mudancas e eventos");
        }
        if (hasCandidate(candidates, "cargo")
                || hasCandidate(candidates, "departamento")
                || hasField(confirmedSchemas, "cargo")
                || hasField(confirmedSchemas, "departamento")) {
            recommendations.add("visoes de apoio para cargo, departamento e segmentacao");
        }
        return humanJoin(recommendations);
    }

    private boolean hasField(List<CatalogSchemaSummary> summaries, String token) {
        String normalizedToken = normalizeText(token);
        return summaries != null && summaries.stream()
                .filter(Objects::nonNull)
                .flatMap(summary -> summary.fields() == null ? Stream.empty() : summary.fields().stream())
                .anyMatch(field -> normalizeText(field).contains(normalizedToken));
    }

    private String businessResourceLabel(AgenticAuthoringCandidate candidate) {
        String path = businessResourcePath(candidate == null ? "" : candidate.resourcePath());
        if (path.isBlank()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return humanizeToken(last.replace("vw-", ""));
    }

    private String humanizeToken(String value) {
        String[] parts = safeText(value)
                .replace('_', '-')
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .split("-+");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT)
                    + part.substring(1).toLowerCase(Locale.ROOT));
        }
        return String.join(" ", words);
    }

    private String humanJoin(List<String> values) {
        List<String> clean = values == null
                ? List.of()
                : values.stream().filter(StringUtils::hasText).distinct().toList();
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.size() == 1) {
            return clean.get(0);
        }
        if (clean.size() == 2) {
            return clean.get(0) + " e " + clean.get(1);
        }
        return String.join(", ", clean.subList(0, clean.size() - 1)) + " e " + clean.get(clean.size() - 1);
    }

    private boolean hasCandidate(List<AgenticAuthoringCandidate> candidates, String token) {
        String normalizedToken = normalizeText(token);
        return candidates != null && candidates.stream()
                .anyMatch(candidate -> normalizeText(candidate.resourcePath()).contains(normalizedToken));
    }

    private static boolean isGenericPreviewReadyMessage(String message) {
        String normalized = normalizeText(message).replaceAll("[^a-z0-9]+", " ").trim();
        return "preview ready".equals(normalized)
                || "preview applied to the page".equals(normalized)
                || "pre visualizacao pronta".equals(normalized);
    }

    private static boolean requiresSharedRuleAuthoring(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        AgenticAuthoringGateResult gate = intentResolution.gate();
        return (gate != null
                && "route_required".equals(gate.status())
                && contains(gate.messages(), "shared-rule-authoring-required"))
                || contains(intentResolution.failureCodes(), "shared-rule-authoring-required");
    }

    private static boolean needsClarification(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.gate() == null) {
            return false;
        }
        return "clarification_required".equals(intentResolution.gate().status());
    }

    private static boolean allowsMaterializedPreview(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        String operationKind = safeLower(intentResolution.operationKind());
        return "create".equals(operationKind)
                || "modify".equals(operationKind)
                || "remove".equals(operationKind);
    }

    private static boolean hasComponentAuthoringSignal(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnState state) {
        if (state != null && state.structuralTarget() != null) {
            return true;
        }
        String artifactKind = safeLower(intentResolution.artifactKind());
        if ("form".equals(artifactKind)
                || "table".equals(artifactKind)
                || "dashboard".equals(artifactKind)
                || "page".equals(artifactKind)
                || "chart".equals(artifactKind)) {
            return true;
        }
        String prompt = safeLower(request.userPrompt());
        return prompt.contains("formulario")
                || prompt.contains("formulário")
                || prompt.contains("pagina")
                || prompt.contains("página")
                || prompt.contains("tabela")
                || prompt.contains("dashboard")
                || prompt.contains("painel")
                || prompt.contains("campo")
                || prompt.contains("widget");
    }

    private static boolean contains(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(expected::equals);
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(normalizeText(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safeText(String value) {
        return value != null ? value : "";
    }
}
