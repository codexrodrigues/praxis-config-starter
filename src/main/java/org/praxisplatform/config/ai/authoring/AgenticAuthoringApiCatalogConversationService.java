package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

/**
 * Composes natural-language answers over the canonical API catalog metadata.
 */
public class AgenticAuthoringApiCatalogConversationService {

    private final ObjectMapper objectMapper;
    private final ApiMetadataRepository repository;

    public AgenticAuthoringApiCatalogConversationService(
            ObjectMapper objectMapper,
            ApiMetadataRepository repository) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.repository = repository;
    }

    public ApiCatalogConversationAnswer answer(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        AgenticAuthoringCandidate candidate = selectedCandidate == null && !usableCandidates.isEmpty()
                ? usableCandidates.get(0)
                : selectedCandidate;
        ApiMetadata selectedMetadata = findMetadata(candidate).orElse(null);
        String normalizedPrompt = normalize(prompt);

        if (usableCandidates.isEmpty() && selectedMetadata == null) {
            String message = "Nao encontrei APIs candidatas no catalogo para esse tema. Posso listar endpoints, schemas, actions, filtros ou ajudar a escolher uma API quando houver metadados disponiveis.";
            return new ApiCatalogConversationAnswer(
                    message,
                    baseAnswer("endpoints", null, null, usableCandidates));
        }
        if (containsAny(normalizedPrompt, "relacionad", "complement", "combinar", "combine", "drill", "detalhe", "detalhamento", "vincul")) {
            return relatedApisAnswer(normalizedPrompt, selectedMetadata, candidate, usableCandidates);
        }
        if (containsAny(normalizedPrompt, "schema", "schemas", "campo", "campos")) {
            return schemaAnswer(selectedMetadata, candidate, usableCandidates);
        }
        if (containsAny(normalizedPrompt, "action", "actions", "acao", "acoes", "permite", "criar", "editar", "alterar", "excluir")) {
            return actionsAnswer(selectedMetadata, candidate, usableCandidates);
        }
        if (containsAny(normalizedPrompt, "filtro", "filtros", "filtrar", "filter")) {
            return filtersAnswer(selectedMetadata, candidate, usableCandidates);
        }
        return endpointChoiceAnswer(normalizedPrompt, selectedMetadata, candidate, usableCandidates);
    }

    private ApiCatalogConversationAnswer schemaAnswer(
            ApiMetadata metadata,
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> candidates) {
        String fields = summarizeFields(metadata != null ? metadata.getResponseSchema() : null);
        if (fields.isBlank()) {
            fields = summarizeFields(metadata != null ? metadata.getRequestSchema() : null);
        }
        String schemaUrl = candidate != null ? candidate.schemaUrl() : "/schemas/filtered";
        String api = candidate != null ? candidate.resourcePath() : metadataPath(metadata);
        String method = candidate != null ? candidate.operation().toUpperCase(Locale.ROOT) : metadataMethod(metadata);
        ObjectNode answer = baseAnswer("schema", metadata, candidate, candidates);
        answer.set("schemaFields", schemaFields(metadata));
        addEvidence(answer, "schema", schemaUrl);
        if (fields.isBlank()) {
            String message = "Para consultar campos e schema, use " + schemaUrl
                    + ". A API candidata e " + api + " (" + method + ").";
            return new ApiCatalogConversationAnswer(message, answer);
        }
        String message = "Campos principais de " + api + " (" + method + "): " + fields
                + ". Para o contrato completo, use " + schemaUrl + ".";
        return new ApiCatalogConversationAnswer(message, answer);
    }

    private ApiCatalogConversationAnswer actionsAnswer(
            ApiMetadata metadata,
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> candidates) {
        String resourcePath = candidate != null ? candidate.resourcePath() : metadataPath(metadata);
        List<ApiMetadata> related = relatedByBasePath(resourcePath);
        String operations = related.stream()
                .sorted(Comparator.comparing(ApiMetadata::getMethod, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ApiMetadata::getPath, String.CASE_INSENSITIVE_ORDER))
                .map(item -> item.getMethod().toUpperCase(Locale.ROOT) + " " + item.getPath())
                .limit(6)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        if (operations.isBlank() && candidate != null) {
            operations = candidate.operation().toUpperCase(Locale.ROOT) + " " + candidate.submitUrl();
        }
        String actionHint = writeOperationsHint(related);
        ObjectNode answer = baseAnswer("actions", metadata, candidate, candidates);
        answer.set("actions", actions(related, candidate));
        addEvidence(answer, "actions", "/schemas/actions");
        String message = "Actions e operacoes relacionadas a " + resourcePath + ": "
                + valueOrDefault(operations, "nenhuma operacao materializada no catalogo local")
                + ". " + actionHint
                + " Consulte /schemas/actions para a lista canonica de operacoes permitidas.";
        return new ApiCatalogConversationAnswer(message, answer);
    }

    private ApiCatalogConversationAnswer filtersAnswer(
            ApiMetadata metadata,
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> candidates) {
        String api = candidate != null ? candidate.resourcePath() : metadataPath(metadata);
        String parameters = summarizeParameters(metadata != null ? metadata.getParameters() : null);
        String fieldFilters = summarizeFields(metadata != null ? metadata.getResponseSchema() : null);
        String schemaUrl = candidate != null ? candidate.schemaUrl() : "/schemas/filtered";
        ObjectNode answer = baseAnswer("filters", metadata, candidate, candidates);
        answer.set("filterParameters", filterParameters(metadata != null ? metadata.getParameters() : null));
        answer.set("schemaFields", schemaFields(metadata));
        addEvidence(answer, "surfaces", "/schemas/surfaces");
        addEvidence(answer, "actions", "/schemas/actions");
        if (parameters.isBlank() && fieldFilters.isBlank()) {
            String message = "Para filtros, priorize endpoints de colecao ou consulta como " + submitUrl(candidate, metadata)
                    + " e valide o contrato em " + schemaUrl
                    + ". Use /schemas/surfaces e /schemas/actions para confirmar filtros e operacoes.";
            return new ApiCatalogConversationAnswer(message, answer);
        }
        StringJoiner details = new StringJoiner("; ");
        if (!parameters.isBlank()) {
            details.add("parametros: " + parameters);
        }
        if (!fieldFilters.isBlank()) {
            details.add("campos candidatos a filtro: " + fieldFilters);
        }
        String message = "Filtros para " + api + ": " + details
                + ". Confirme a exposicao UI em /schemas/surfaces e as operacoes em /schemas/actions.";
        return new ApiCatalogConversationAnswer(message, answer);
    }

    private ApiCatalogConversationAnswer relatedApisAnswer(
            String normalizedPrompt,
            ApiMetadata selectedMetadata,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        String selectedPath = selectedCandidate != null ? selectedCandidate.resourcePath() : metadataPath(selectedMetadata);
        List<ApiMetadata> related = relatedApis(normalizedPrompt, selectedMetadata, selectedPath);
        if (related.isEmpty() && !candidates.isEmpty()) {
            String endpoints = formatCandidates(candidates);
            ObjectNode answer = baseAnswer("related_apis", selectedMetadata, selectedCandidate, candidates);
            answer.set("relatedApis", objectMapper.createArrayNode());
            addRecommendation(answer, "combine_analytics_with_operational_detail", "Combine uma API analitica para indicadores com uma API operacional para detalhe.");
            String message = "APIs relacionadas encontradas por proximidade no catalogo: " + endpoints
                    + ". Para drill-down, combine uma API analitica para indicadores com uma API operacional para detalhe.";
            return new ApiCatalogConversationAnswer(message, answer);
        }
        String items = related.stream()
                .limit(5)
                .map(item -> item.getMethod().toUpperCase(Locale.ROOT) + " " + item.getPath()
                        + summarySuffix(item)
                        + relationReason(selectedMetadata, item))
                .reduce((left, right) -> left + "; " + right)
                .orElse("nenhuma API relacionada encontrada");
        ObjectNode answer = baseAnswer("related_apis", selectedMetadata, selectedCandidate, candidates);
        answer.set("relatedApis", relatedApisArray(related, selectedMetadata));
        addRecommendation(answer, "compose_drilldown_page", "Use a API analitica para KPIs/graficos e a operacional para tabela ou detalhe.");
        String message = "APIs relacionadas: " + items
                + ". Para geracao de pagina, use a API analitica para KPIs/graficos e a operacional para tabela ou detalhe.";
        return new ApiCatalogConversationAnswer(message, answer);
    }

    private ApiCatalogConversationAnswer endpointChoiceAnswer(
            String normalizedPrompt,
            ApiMetadata selectedMetadata,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        String endpoints = formatCandidates(candidates);
        if (endpoints.isBlank() && selectedMetadata != null) {
            endpoints = selectedMetadata.getPath() + " (" + selectedMetadata.getMethod().toUpperCase(Locale.ROOT) + ")";
        }
        String message = "APIs candidatas encontradas: " + endpoints;
        ObjectNode answer = baseAnswer("endpoints", selectedMetadata, selectedCandidate, candidates);
        if (containsAny(normalizedPrompt, "devo usar", "melhor", "recomenda", "recomende", "dashboard", "tabela")) {
            String recommendation = selectedCandidate != null ? selectedCandidate.resourcePath() : metadataPath(selectedMetadata);
            String submitUrl = selectedCandidate != null ? selectedCandidate.submitUrl() : metadataPath(selectedMetadata);
            String reason = recommendationReason(normalizedPrompt, selectedMetadata, selectedCandidate);
            message += ". Recomendacao: use " + recommendation + " com " + submitUrl
                    + " para esse objetivo antes de gerar a pagina. " + reason;
            answer.put("questionType", "api_choice");
            addRecommendation(answer, "use_selected_api", "Use " + recommendation + " antes de gerar a pagina. " + reason);
        }
        return new ApiCatalogConversationAnswer(message, answer);
    }

    private ObjectNode baseAnswer(
            String questionType,
            ApiMetadata metadata,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        ObjectNode answer = objectMapper.createObjectNode();
        answer.put("questionType", questionType);
        if (selectedCandidate != null || metadata != null) {
            answer.set("selectedApi", apiNode(metadata, selectedCandidate));
        }
        ArrayNode candidateApis = objectMapper.createArrayNode();
        for (AgenticAuthoringCandidate candidate : candidates) {
            candidateApis.add(candidateNode(candidate));
        }
        answer.set("candidateApis", candidateApis);
        answer.set("relatedApis", objectMapper.createArrayNode());
        answer.set("schemaFields", objectMapper.createArrayNode());
        answer.set("filterParameters", objectMapper.createArrayNode());
        answer.set("actions", objectMapper.createArrayNode());
        answer.set("recommendations", objectMapper.createArrayNode());
        answer.set("evidence", objectMapper.createArrayNode());
        return answer;
    }

    private ObjectNode apiNode(ApiMetadata metadata, AgenticAuthoringCandidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        String path = candidate != null ? candidate.resourcePath() : metadataPath(metadata);
        String method = candidate != null ? candidate.operation() : metadataMethod(metadata);
        node.put("path", path);
        node.put("method", method != null ? method.toUpperCase(Locale.ROOT) : "GET");
        if (candidate != null) {
            node.put("submitUrl", candidate.submitUrl());
            node.put("schemaUrl", candidate.schemaUrl());
            node.put("score", candidate.score());
        }
        if (metadata != null) {
            node.put("summary", valueOrDefault(metadata.getSummary(), ""));
            node.put("description", valueOrDefault(metadata.getDescription(), ""));
            node.put("operationId", valueOrDefault(metadata.getOperationId(), ""));
            node.put("tags", valueOrDefault(metadata.getTags(), ""));
        }
        return node;
    }

    private ObjectNode candidateNode(AgenticAuthoringCandidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("path", candidate.resourcePath());
        node.put("method", candidate.operation().toUpperCase(Locale.ROOT));
        node.put("submitUrl", candidate.submitUrl());
        node.put("schemaUrl", candidate.schemaUrl());
        node.put("score", candidate.score());
        node.put("reason", candidate.reason());
        return node;
    }

    private ArrayNode relatedApisArray(List<ApiMetadata> related, ApiMetadata selectedMetadata) {
        ArrayNode nodes = objectMapper.createArrayNode();
        for (ApiMetadata metadata : related.stream().limit(8).toList()) {
            ObjectNode node = apiNode(metadata, null);
            Set<String> commonFields = new LinkedHashSet<>(fields(selectedMetadata != null ? selectedMetadata.getResponseSchema() : null));
            commonFields.retainAll(fields(metadata.getResponseSchema()));
            ArrayNode common = objectMapper.createArrayNode();
            commonFields.stream().limit(8).forEach(common::add);
            node.set("commonFields", common);
            nodes.add(node);
        }
        return nodes;
    }

    private ArrayNode schemaFields(ApiMetadata metadata) {
        ArrayNode fields = objectMapper.createArrayNode();
        addSchemaFields(fields, metadata != null ? metadata.getResponseSchema() : null);
        if (fields.isEmpty()) {
            addSchemaFields(fields, metadata != null ? metadata.getRequestSchema() : null);
        }
        return fields;
    }

    private void addSchemaFields(ArrayNode fields, String schemaJson) {
        JsonNode root = readTree(schemaJson);
        if (root == null) {
            return;
        }
        addFieldNodes(fields, root.path("fields"));
        addPropertyNodes(fields, root.path("properties"));
        addFieldNodes(fields, root.path("inlineSchema").path("fields"));
        addPropertyNodes(fields, root.path("inlineSchema").path("properties"));
    }

    private void addFieldNodes(ArrayNode output, JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return;
        }
        for (JsonNode field : fieldsNode) {
            String name = text(field, "name", "field");
            if (name.isBlank()) {
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", name);
            node.put("type", text(field, "type", "format"));
            node.put("required", field.path("required").asBoolean(false));
            output.add(node);
        }
    }

    private void addPropertyNodes(ArrayNode output, JsonNode propertiesNode) {
        if (propertiesNode == null || !propertiesNode.isObject()) {
            return;
        }
        propertiesNode.fields().forEachRemaining(entry -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", entry.getKey());
            node.put("type", text(entry.getValue(), "type", "format"));
            node.put("required", false);
            output.add(node);
        });
    }

    private ArrayNode filterParameters(String parametersJson) {
        ArrayNode parameters = objectMapper.createArrayNode();
        JsonNode root = readTree(parametersJson);
        if (root == null || !root.isArray()) {
            return parameters;
        }
        for (JsonNode parameter : root) {
            String name = text(parameter, "name", "field");
            if (name.isBlank()) {
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", name);
            node.put("in", valueOrDefault(text(parameter, "in", "location"), "query"));
            node.put("type", valueOrDefault(text(parameter, "type", "format"), "value"));
            node.put("required", parameter.path("required").asBoolean(false));
            parameters.add(node);
        }
        return parameters;
    }

    private ArrayNode actions(List<ApiMetadata> related, AgenticAuthoringCandidate candidate) {
        ArrayNode actions = objectMapper.createArrayNode();
        for (ApiMetadata metadata : related.stream().limit(8).toList()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("method", metadata.getMethod().toUpperCase(Locale.ROOT));
            node.put("path", metadata.getPath());
            node.put("operationId", valueOrDefault(metadata.getOperationId(), ""));
            node.put("summary", valueOrDefault(metadata.getSummary(), ""));
            actions.add(node);
        }
        if (actions.isEmpty() && candidate != null) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("method", candidate.operation().toUpperCase(Locale.ROOT));
            node.put("path", candidate.submitUrl());
            actions.add(node);
        }
        return actions;
    }

    private void addRecommendation(ObjectNode answer, String kind, String text) {
        ObjectNode recommendation = objectMapper.createObjectNode();
        recommendation.put("kind", kind);
        recommendation.put("text", text);
        ((ArrayNode) answer.withArray("recommendations")).add(recommendation);
    }

    private void addEvidence(ObjectNode answer, String kind, String source) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("kind", kind);
        evidence.put("source", source);
        ((ArrayNode) answer.withArray("evidence")).add(evidence);
    }

    private Optional<ApiMetadata> findMetadata(AgenticAuthoringCandidate candidate) {
        if (repository == null || candidate == null) {
            return Optional.empty();
        }
        String operation = candidate.operation() == null ? "" : candidate.operation();
        Optional<ApiMetadata> exact = Optional.ofNullable(repository.findByPathAndMethod(candidate.submitUrl(), operation))
                .flatMap(optional -> optional);
        if (exact.isPresent()) {
            return exact;
        }
        exact = Optional.ofNullable(repository.findByPathAndMethod(candidate.resourcePath(), operation))
                .flatMap(optional -> optional);
        if (exact.isPresent()) {
            return exact;
        }
        String normalizedResource = normalizePath(candidate.resourcePath());
        return repository.findAll().stream()
                .filter(metadata -> normalizePath(metadata.getPath()).equals(normalizedResource)
                        || normalizePath(metadata.getPath()).startsWith(normalizedResource + "/"))
                .findFirst();
    }

    private List<ApiMetadata> relatedByBasePath(String resourcePath) {
        if (repository == null || resourcePath == null || resourcePath.isBlank()) {
            return List.of();
        }
        String base = normalizePath(resourcePath);
        return repository.findAll().stream()
                .filter(metadata -> normalizePath(metadata.getPath()).equals(base)
                        || normalizePath(metadata.getPath()).startsWith(base + "/")
                        || base.startsWith(normalizePath(metadata.getPath()) + "/"))
                .toList();
    }

    private List<ApiMetadata> relatedApis(String normalizedPrompt, ApiMetadata selectedMetadata, String selectedPath) {
        if (repository == null) {
            return List.of();
        }
        Set<String> selectedTokens = tokens(String.join(" ",
                valueOrDefault(selectedPath, ""),
                selectedMetadata != null ? valueOrDefault(selectedMetadata.getTags(), "") : "",
                selectedMetadata != null ? valueOrDefault(selectedMetadata.getSummary(), "") : "",
                selectedMetadata != null ? valueOrDefault(selectedMetadata.getDescription(), "") : "",
                normalizedPrompt));
        Set<String> selectedFields = fields(selectedMetadata != null ? selectedMetadata.getResponseSchema() : null);
        return repository.findAll().stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> selectedPath == null || !normalizePath(metadata.getPath()).equals(normalizePath(selectedPath)))
                .map(metadata -> new ScoredMetadata(metadata, relationScore(metadata, selectedTokens, selectedFields, selectedPath)))
                .filter(scored -> scored.score() >= 0.25d)
                .sorted(Comparator.comparingDouble(ScoredMetadata::score).reversed())
                .map(ScoredMetadata::metadata)
                .toList();
    }

    private double relationScore(
            ApiMetadata metadata,
            Set<String> selectedTokens,
            Set<String> selectedFields,
            String selectedPath) {
        Set<String> tokens = tokens(String.join(" ",
                valueOrDefault(metadata.getPath(), ""),
                valueOrDefault(metadata.getTags(), ""),
                valueOrDefault(metadata.getSummary(), ""),
                valueOrDefault(metadata.getDescription(), ""),
                valueOrDefault(metadata.getOperationId(), "")));
        double score = 0d;
        for (String token : selectedTokens) {
            if (tokens.contains(token)) {
                score += 0.08d;
            }
        }
        Set<String> fields = fields(metadata.getResponseSchema());
        for (String field : selectedFields) {
            if (fields.contains(field)) {
                score += 0.10d;
            }
        }
        String path = normalizePath(metadata.getPath());
        String base = normalizePath(selectedPath);
        if (!base.isBlank() && (path.startsWith(base + "/") || commonPathPrefix(path, base) >= 3)) {
            score += 0.24d;
        }
        if (path.contains("analytics") || path.contains("/vw-") || path.contains("/stats/")) {
            score += 0.08d;
        }
        return Math.min(score, 0.98d);
    }

    private String summarizeFields(String schemaJson) {
        JsonNode root = readTree(schemaJson);
        if (root == null) {
            return "";
        }
        List<String> fields = new ArrayList<>();
        collectFieldSummaries(root.path("fields"), fields);
        collectPropertySummaries(root.path("properties"), fields);
        collectFieldSummaries(root.path("inlineSchema").path("fields"), fields);
        collectPropertySummaries(root.path("inlineSchema").path("properties"), fields);
        return fields.stream().limit(8).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private Set<String> fields(String schemaJson) {
        JsonNode root = readTree(schemaJson);
        if (root == null) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        collectFieldNames(root.path("fields"), fields);
        collectPropertyNames(root.path("properties"), fields);
        collectFieldNames(root.path("inlineSchema").path("fields"), fields);
        collectPropertyNames(root.path("inlineSchema").path("properties"), fields);
        return fields;
    }

    private void collectFieldSummaries(JsonNode fieldsNode, List<String> output) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return;
        }
        for (JsonNode field : fieldsNode) {
            String name = text(field, "name", "field");
            if (name.isBlank()) {
                continue;
            }
            String type = text(field, "type", "format");
            output.add(type.isBlank() ? name : name + " (" + type + ")");
        }
    }

    private void collectPropertySummaries(JsonNode propertiesNode, List<String> output) {
        if (propertiesNode == null || !propertiesNode.isObject()) {
            return;
        }
        propertiesNode.fields().forEachRemaining(entry -> {
            String type = text(entry.getValue(), "type", "format");
            output.add(type.isBlank() ? entry.getKey() : entry.getKey() + " (" + type + ")");
        });
    }

    private void collectFieldNames(JsonNode fieldsNode, Set<String> output) {
        if (fieldsNode == null || !fieldsNode.isArray()) {
            return;
        }
        for (JsonNode field : fieldsNode) {
            String name = normalize(text(field, "name", "field"));
            if (!name.isBlank()) {
                output.add(name);
            }
        }
    }

    private void collectPropertyNames(JsonNode propertiesNode, Set<String> output) {
        if (propertiesNode == null || !propertiesNode.isObject()) {
            return;
        }
        propertiesNode.fieldNames().forEachRemaining(name -> output.add(normalize(name)));
    }

    private String summarizeParameters(String parametersJson) {
        JsonNode root = readTree(parametersJson);
        if (root == null || !root.isArray()) {
            return "";
        }
        List<String> parameters = new ArrayList<>();
        for (JsonNode parameter : root) {
            String name = text(parameter, "name", "field");
            if (name.isBlank()) {
                continue;
            }
            String location = text(parameter, "in", "location");
            String type = text(parameter, "type", "format");
            String suffix = new StringJoiner(", ", " (", ")")
                    .add(valueOrDefault(location, "query"))
                    .add(valueOrDefault(type, "valor"))
                    .toString();
            parameters.add(name + suffix);
        }
        return parameters.stream().limit(8).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String formatCandidates(List<AgenticAuthoringCandidate> candidates) {
        return candidates.stream()
                .limit(5)
                .map(candidate -> candidate.resourcePath()
                        + " (" + candidate.operation().toUpperCase(Locale.ROOT)
                        + ", schema: " + candidate.schemaUrl() + ")")
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private String recommendationReason(
            String normalizedPrompt,
            ApiMetadata metadata,
            AgenticAuthoringCandidate candidate) {
        String path = candidate != null ? normalizePath(candidate.resourcePath()) : normalizePath(metadataPath(metadata));
        if (containsAny(normalizedPrompt, "dashboard", "grafico", "indicador")
                && (path.contains("analytics") || path.contains("/vw-") || path.contains("/stats/"))) {
            return "Motivo: o endpoint parece analitico e adequado para KPIs/graficos.";
        }
        if (containsAny(normalizedPrompt, "tabela", "lista", "detalhe")) {
            return "Motivo: endpoints de colecao ou consulta operacional tendem a alimentar tabelas e detalhes.";
        }
        return "Motivo: melhor pontuacao no catalogo para a intencao informada.";
    }

    private String writeOperationsHint(List<ApiMetadata> related) {
        boolean hasWrite = related.stream().anyMatch(item -> {
            String method = item.getMethod() == null ? "" : item.getMethod().toUpperCase(Locale.ROOT);
            return method.equals("POST") || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE");
        });
        return hasWrite
                ? "Ha operacoes de escrita materializadas no catalogo local."
                : "Nao encontrei operacoes de escrita materializadas nessa familia de endpoints.";
    }

    private String relationReason(ApiMetadata selected, ApiMetadata item) {
        if (selected == null || item == null) {
            return "";
        }
        Set<String> selectedFields = fields(selected.getResponseSchema());
        Set<String> itemFields = fields(item.getResponseSchema());
        selectedFields.retainAll(itemFields);
        if (!selectedFields.isEmpty()) {
            return " (campos em comum: " + selectedFields.stream().limit(3)
                    .reduce((left, right) -> left + ", " + right).orElse("") + ")";
        }
        return "";
    }

    private String summarySuffix(ApiMetadata item) {
        String summary = valueOrDefault(item.getSummary(), item.getDescription());
        if (summary.isBlank()) {
            return "";
        }
        return " - " + summary;
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(value).replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (token.length() >= 4) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int commonPathPrefix(String left, String right) {
        String[] leftParts = left.split("/");
        String[] rightParts = right.split("/");
        int count = 0;
        for (int i = 0; i < Math.min(leftParts.length, rightParts.length); i++) {
            if (!leftParts[i].equals(rightParts[i])) {
                break;
            }
            if (!leftParts[i].isBlank()) {
                count++;
            }
        }
        return count;
    }

    private String submitUrl(AgenticAuthoringCandidate candidate, ApiMetadata metadata) {
        if (candidate != null && candidate.submitUrl() != null && !candidate.submitUrl().isBlank()) {
            return candidate.submitUrl();
        }
        return metadataPath(metadata);
    }

    private String metadataPath(ApiMetadata metadata) {
        return metadata != null && metadata.getPath() != null ? metadata.getPath() : "API recomendada";
    }

    private String metadataMethod(ApiMetadata metadata) {
        return metadata != null && metadata.getMethod() != null
                ? metadata.getMethod().toUpperCase(Locale.ROOT)
                : "GET";
    }

    private String normalizePath(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (token != null && !token.isBlank() && value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    public record ApiCatalogConversationAnswer(
            String assistantMessage,
            JsonNode apiCatalogAnswer) {
    }

    private record ScoredMetadata(ApiMetadata metadata, double score) {
    }
}
