package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiCapability;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiSuggestion;
import org.praxisplatform.config.dto.AiSuggestionsRequest;
import org.praxisplatform.config.dto.AiSuggestionsResponse;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiSuggestionsService {

    private static final int DEFAULT_MAX_SUGGESTIONS = 5;
    private static final String COMPONENT_TABLE = "praxis-table";
    private static final String COMPONENT_FORM = "praxis-dynamic-form";
    private static final int PAGINATION_THRESHOLD = 20;
    private static final int DENSITY_COLUMNS_THRESHOLD = 8;
    private static final int MAX_COLUMN_SUGGESTIONS = 3;
    private static final int BADGE_CARDINALITY_MAX = 6;
    private static final List<String> DEFAULT_BADGE_PALETTE = List.of(
            "primary",
            "accent",
            "warn",
            "success",
            "info"
    );

    private static final Set<String> SELECTION_CONTROL_TYPES = Set.of(
            "select",
            "multiSelect",
            "radio",
            "buttonToggle",
            "selectionList",
            "autoComplete",
            "async-select",
            "dropDownTree",
            "treeSelect",
            "multiSelectTree",
            "transferList"
    );

    private static final class SchemaFieldHint {
        private final String controlType;
        private final String numericFormat;
        private final List<String> optionValues;

        private SchemaFieldHint(String controlType, String numericFormat, List<String> optionValues) {
            this.controlType = controlType;
            this.numericFormat = numericFormat;
            this.optionValues = optionValues != null ? optionValues : List.of();
        }

        private String getControlType() {
            return controlType;
        }

        private String getNumericFormat() {
            return numericFormat;
        }

        private List<String> getOptionValues() {
            return optionValues;
        }
    }

    private final ObjectMapper objectMapper;

    public AiSuggestionsResponse suggest(AiSuggestionsRequest request, AiContextDTO context) {
        JsonNode currentState = safeObject(request != null ? request.getCurrentState() : null);
        JsonNode config = resolveConfig(currentState);
        JsonNode dataProfile = request != null ? request.getDataProfile() : null;
        Map<String, SchemaFieldHint> schemaFieldHints = extractSchemaFieldHints(currentState);

        List<AiCapability> capabilities = extractCapabilities(context != null ? context.getComponentDefinition() : null);
        Set<String> registryPaths = buildAllowedPathSet(capabilities);
        Set<String> runtimePaths = extractRuntimeCapabilities(currentState);
        Set<String> allowedPaths = resolveAllowedPaths(registryPaths, runtimePaths);

        List<AiSuggestion> suggestions = List.of();
        if (isTable(context, request)) {
            suggestions = buildTableSuggestions(config, dataProfile, allowedPaths, request != null ? request.getLocale() : null, schemaFieldHints);
        } else if (isForm(context, request)) {
            suggestions = buildFormSuggestions(config, allowedPaths);
        }

        List<AiSuggestion> ordered = sortAndLimit(suggestions, request != null ? request.getMaxSuggestions() : null);
        return AiSuggestionsResponse.builder()
                .suggestions(ordered)
                .source("heuristic")
                .build();
    }

    private boolean isTable(AiContextDTO context, AiSuggestionsRequest request) {
        String componentId = context != null ? context.getComponentId() : null;
        String componentType = context != null ? context.getComponentType() : null;
        if (request != null) {
            componentId = componentId != null ? componentId : request.getComponentId();
            componentType = componentType != null ? componentType : request.getComponentType();
        }
        return equalsIgnoreCase(componentId, COMPONENT_TABLE) || equalsIgnoreCase(componentType, "table");
    }

    private boolean isForm(AiContextDTO context, AiSuggestionsRequest request) {
        String componentId = context != null ? context.getComponentId() : null;
        String componentType = context != null ? context.getComponentType() : null;
        if (request != null) {
            componentId = componentId != null ? componentId : request.getComponentId();
            componentType = componentType != null ? componentType : request.getComponentType();
        }
        return equalsIgnoreCase(componentId, COMPONENT_FORM)
                || equalsIgnoreCase(componentType, "form")
                || equalsIgnoreCase(componentType, "dynamic-form");
    }

    private List<AiSuggestion> buildTableSuggestions(
            JsonNode config,
            JsonNode dataProfile,
            Set<String> allowedPaths,
            String locale,
            Map<String, SchemaFieldHint> schemaFieldHints) {
        Map<String, AiSuggestion> out = new LinkedHashMap<>();
        ArrayNode columns = arrayNode(config.get("columns"));

        int columnCount = columns.size();
        int visibleCount = countVisibleColumns(columns);
        int rowCount = readRowCount(dataProfile);

        boolean paginationEnabled = booleanAt(config, "/behavior/pagination/enabled");
        boolean filteringEnabled = booleanAt(config, "/behavior/filtering/enabled");
        boolean sortingEnabled = booleanAt(config, "/behavior/sorting/enabled");
        String density = textAt(config, "/appearance/density");

        if (rowCount > PAGINATION_THRESHOLD
                && !paginationEnabled
                && hasCapability(allowedPaths, "behavior.pagination.enabled")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("table.pagination.enable")
                    .label("Habilitar paginacao")
                    .description("Tabela tem " + rowCount + " linhas. Paginacao melhora performance.")
                    .icon("list_alt")
                    .group("Performance")
                    .intent("Habilitar paginacao com 10 itens por pagina")
                    .score(0.92)
                    .build());
        }

        if (rowCount > PAGINATION_THRESHOLD
                && !filteringEnabled
                && hasCapability(allowedPaths, "behavior.filtering.enabled")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("table.filtering.enable")
                    .label("Ativar filtros")
                    .description("Facilita buscar nas colunas exibidas.")
                    .icon("filter_alt")
                    .group("Usabilidade")
                    .intent("Ativar filtros por coluna")
                    .score(0.86)
                    .build());
        }

        if (columnCount > 1
                && !sortingEnabled
                && hasCapability(allowedPaths, "behavior.sorting.enabled")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("table.sorting.enable")
                    .label("Ativar ordenacao")
                    .description("Permite ordenar rapidamente por coluna.")
                    .icon("sort")
                    .group("Usabilidade")
                    .intent("Ativar ordenacao por coluna")
                    .score(0.82)
                    .build());
        }

        if (visibleCount > DENSITY_COLUMNS_THRESHOLD
                && (density == null || !"compact".equalsIgnoreCase(density))
                && hasCapability(allowedPaths, "appearance.density")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("table.density.compact")
                    .label("Modo compacto")
                    .description("Muitas colunas visiveis. Modo compacto exibe mais dados.")
                    .icon("compress")
                    .group("Visual")
                    .intent("Usar densidade compacta")
                    .score(0.78)
                    .build());
        }

        // --- NEW: Suggest Sticky Column for Wide Tables ---
        if (visibleCount >= DENSITY_COLUMNS_THRESHOLD && columnCount > 0
                && hasCapability(allowedPaths, "columns[].sticky")) {
            JsonNode firstColumn = columns.get(0);
            if (firstColumn != null && firstColumn.isObject()) {
                String sticky = textOrNull(firstColumn.get("sticky"));
                boolean isVisible = booleanAt(firstColumn, "/visible");
                if (!"start".equalsIgnoreCase(sticky) && isVisible) {
                     addSuggestion(out, AiSuggestion.builder()
                            .id("table.sticky.first")
                            .label("Fixar primeira coluna")
                            .description("Muitas colunas. Fixar a primeira melhora a navegabilidade.")
                            .icon("push_pin")
                            .group("Usabilidade")
                            .intent("Fixar coluna " + textOrNull(firstColumn.get("field")) + " à esquerda")
                            .score(0.80)
                            .build());
                }
            }
        }

        // --- NEW: Suggest Enable Selection ---
        boolean selectionEnabled = booleanAt(config, "/behavior/selection/enabled");
        if (rowCount > 0
                && !selectionEnabled
                && hasCapability(allowedPaths, "behavior.selection.enabled")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("table.selection.enable")
                    .label("Habilitar selecao de linhas")
                    .description("Permite selecionar linhas para acoes em lote.")
                    .icon("check_box")
                    .group("Produtividade")
                    .intent("Habilitar selecao de linhas")
                    .score(0.70)
                    .build());
        }

        // --- NEW: Suggest Enable Export ---
        boolean exportEnabled = booleanAt(config, "/export/enabled");
        if (rowCount > PAGINATION_THRESHOLD // Assuming PAGINATION_THRESHOLD (20) is a reasonable threshold for export
                && !exportEnabled
                && hasCapability(allowedPaths, "export.enabled")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("table.export.enable")
                    .label("Habilitar exportacao de dados")
                    .description("Permite exportar os dados da tabela para outros formatos.")
                    .icon("download")
                    .group("Produtividade")
                    .intent("Habilitar exportacao de dados para Excel e PDF")
                    .score(0.65)
                    .build());
        }

        JsonNode profileColumns = dataProfileColumns(dataProfile);
        int columnSuggestions = 0;

        for (JsonNode column : columns) {
            if (columnSuggestions >= MAX_COLUMN_SUGGESTIONS) {
                break;
            }
            if (column == null || !column.isObject()) {
                continue;
            }
            String field = textOrNull(column.get("field"));
            if (isBlank(field)) {
                continue;
            }
            String header = textOrNull(column.get("header"));
            String display = !isBlank(header) ? header : field;
            boolean hasRenderer = hasRenderer(column);
            boolean hasFormat = hasText(column.get("format"));

            JsonNode stats = profileColumns != null ? profileColumns.get(field) : null;
            String inferredType = stats != null ? textOrNull(stats.get("inferredType")) : null;
            int cardinality = stats != null ? intOrDefault(stats.get("cardinality"), -1) : -1;
            boolean isLongText = stats != null && stats.path("isLongText").asBoolean(false);
            String explicitType = textOrNull(column.get("type"));
            SchemaFieldHint schemaHint = schemaFieldHints != null ? schemaFieldHints.get(field) : null;
            String schemaControlType = schemaHint != null ? schemaHint.getControlType() : null;
            String schemaNumericFormat = schemaHint != null ? schemaHint.getNumericFormat() : null;

            if (!hasFormat
                    && isDateType(inferredType, explicitType)
                    && hasCapability(allowedPaths, "columns[].format")) {
                addSuggestion(out, AiSuggestion.builder()
                        .id("table.format.date." + field)
                        .label("Formatar " + display)
                        .description("Padronizar exibicao de data.")
                        .icon("calendar_today")
                        .group("Formatacao")
                        .intent("Formatar coluna " + field + " como dd/MM/yyyy")
                        .score(0.7)
                        .build());
                columnSuggestions++;
                continue;
            }

            if (!hasFormat
                    && isCurrencyField(explicitType, schemaControlType, schemaNumericFormat)
                    && hasCapability(allowedPaths, "columns[].format")) {
                String currency = resolveCurrency(locale);
                addSuggestion(out, AiSuggestion.builder()
                        .id("table.format.currency." + field)
                        .label("Formatar " + display + " como moeda")
                        .description("Melhor leitura de valores monetarios.")
                        .icon("paid")
                        .group("Formatacao")
                        .intent("Formatar coluna " + field + " como moeda " + currency)
                        .score(0.66)
                        .build());
                columnSuggestions++;
                continue;
            }

            if (!hasFormat
                    && isBooleanType(inferredType, explicitType)
                    && hasCapability(allowedPaths, "columns[].format")) {
                addSuggestion(out, AiSuggestion.builder()
                        .id("table.format.boolean." + field)
                        .label("Formatar " + display + " como Sim/Nao")
                        .description("Tornar valores booleanos mais claros.")
                        .icon("check_circle")
                        .group("Formatacao")
                        .intent("Exibir coluna " + field + " como Sim/Nao")
                        .score(0.62)
                        .build());
                columnSuggestions++;
                continue;
            }

            boolean hasValueMapping = hasValueMapping(column);
            if (!hasRenderer
                    && shouldSuggestBadge(inferredType, explicitType, cardinality, isLongText, hasValueMapping)
                    && hasCapability(allowedPaths, "columns[].renderer.type")
                    && hasCapability(allowedPaths, "columns[].renderer.badge")) {
                boolean supportsConditional = hasCapabilityPrefix(allowedPaths, "columns[].conditionalRenderers");
                addSuggestion(out, buildBadgeSuggestion(field, display, stats, explicitType, supportsConditional, schemaHint));
                columnSuggestions++;
            }

            // --- NEW: Suggest Right Align for Numeric Types ---
            boolean isNumericCandidate = "number".equalsIgnoreCase(inferredType) || "number".equalsIgnoreCase(explicitType)
                    || "currency".equalsIgnoreCase(explicitType) || "percentage".equalsIgnoreCase(explicitType);
            String currentAlign = textOrNull(column.get("align"));

            if (isNumericCandidate
                    && !("right".equalsIgnoreCase(currentAlign))
                    && hasCapability(allowedPaths, "columns[].align")) {
                addSuggestion(out, AiSuggestion.builder()
                        .id("table.align.right." + field)
                        .label("Alinhar " + display + " à direita")
                        .description("Melhora a leitura de valores numéricos e monetários.")
                        .icon("format_align_right")
                        .group("Visual")
                        .intent("Alinhar coluna " + field + " à direita")
                        .score(0.75)
                        .build());
                columnSuggestions++;
                continue;
            }

        }

        return new ArrayList<>(out.values());
    }

    private List<AiSuggestion> buildFormSuggestions(JsonNode config, Set<String> allowedPaths) {
        Map<String, AiSuggestion> out = new LinkedHashMap<>();
        ArrayNode fields = arrayNode(config.get("fieldMetadata"));
        ArrayNode sections = arrayNode(config.get("sections"));

        int fieldCount = fields.size();
        int sectionCount = sections.size();

        if (sectionCount == 0
                && fieldCount >= 6
                && hasCapabilityPrefix(allowedPaths, "sections[].")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("form.sections.organize")
                    .label("Organizar campos em secoes")
                    .description("Formularios longos ficam mais claros com secoes.")
                    .icon("view_list")
                    .group("Layout")
                    .intent("Organizar campos em secoes com titulos")
                    .score(0.85)
                    .build());
        }

        List<String> missingLabels = new ArrayList<>();
        for (JsonNode field : fields) {
            if (field == null || !field.isObject()) {
                continue;
            }
            String name = textOrNull(field.get("name"));
            if (isBlank(name)) {
                continue;
            }
            String label = textOrNull(field.get("label"));
            if (isBlank(label)) {
                missingLabels.add(name);
            }
        }

        if (!missingLabels.isEmpty() && hasCapabilityPrefix(allowedPaths, "fieldMetadata")) {
            String sample = sampleNames(missingLabels, 3);
            String description = sample != null
                    ? "Campos sem label: " + sample + "."
                    : "Campos sem label.";
            addSuggestion(out, AiSuggestion.builder()
                    .id("form.labels.add")
                    .label("Adicionar labels nos campos")
                    .description(description)
                    .icon("label")
                    .group("Conteudo")
                    .intent("Adicionar labels descritivos para campos sem label")
                    .score(0.76)
                    .build());
        }

        String missingOptionsField = findFirstFieldMissingOptions(fields);
        if (missingOptionsField != null && hasCapabilityPrefix(allowedPaths, "fieldMetadata")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("form.options." + missingOptionsField)
                    .label("Definir opcoes para " + missingOptionsField)
                    .description("Campos de selecao precisam de opcoes ou endpoint.")
                    .icon("list_alt")
                    .group("Dados")
                    .intent("Configurar opcoes (ou endpoint) para o campo " + missingOptionsField)
                    .score(0.7)
                    .build());
        }

        JsonNode actions = config.get("actions");
        boolean hasSubmit = actions != null && actions.has("submit") && actions.get("submit").isObject();
        if (!hasSubmit && hasCapabilityPrefix(allowedPaths, "actions.submit")) {
            addSuggestion(out, AiSuggestion.builder()
                    .id("form.actions.submit")
                    .label("Adicionar botao de envio")
                    .description("Formulario precisa de acao principal para salvar.")
                    .icon("send")
                    .group("Acoes")
                    .intent("Adicionar botao de envio (submit) com label apropriado")
                    .score(0.68)
                    .build());
        }

        return new ArrayList<>(out.values());
    }

    private String findFirstFieldMissingOptions(ArrayNode fields) {
        for (JsonNode field : fields) {
            if (field == null || !field.isObject()) {
                continue;
            }
            String controlType = textOrNull(field.get("controlType"));
            if (isBlank(controlType)) {
                continue;
            }
            if (!SELECTION_CONTROL_TYPES.contains(controlType)) {
                continue;
            }
            boolean hasOptions = field.has("options")
                    && field.get("options").isArray()
                    && field.get("options").size() > 0;
            boolean hasEndpoint = hasText(field.get("endpoint"));
            if (!hasOptions && !hasEndpoint) {
                String name = textOrNull(field.get("name"));
                return !isBlank(name) ? name : controlType;
            }
        }
        return null;
    }

    private List<AiSuggestion> sortAndLimit(List<AiSuggestion> suggestions, Integer maxSuggestions) {
        List<AiSuggestion> sorted = new ArrayList<>(suggestions != null ? suggestions : List.of());
        sorted.sort(Comparator
                .comparingDouble((AiSuggestion item) -> item.getScore() != null ? item.getScore() : 0.0)
                .reversed()
                .thenComparing(item -> item.getId() != null ? item.getId() : ""));

        int limit = maxSuggestions != null && maxSuggestions > 0 ? maxSuggestions : DEFAULT_MAX_SUGGESTIONS;
        if (sorted.size() > limit) {
            return new ArrayList<>(sorted.subList(0, limit));
        }
        return sorted;
    }

    private int countVisibleColumns(ArrayNode columns) {
        int count = 0;
        for (JsonNode column : columns) {
            if (column == null || !column.isObject()) {
                continue;
            }
            JsonNode visibleNode = column.get("visible");
            boolean visible = visibleNode == null || !visibleNode.isBoolean() || visibleNode.asBoolean(true);
            if (visible) {
                count++;
            }
        }
        return count;
    }

    private boolean hasRenderer(JsonNode column) {
        if (column == null || !column.isObject()) {
            return false;
        }
        JsonNode renderer = column.get("renderer");
        if (renderer != null && renderer.isObject() && renderer.size() > 0) {
            return true;
        }
        JsonNode conditional = column.get("conditionalRenderers");
        return conditional != null && conditional.isArray() && conditional.size() > 0;
    }

    private boolean hasValueMapping(JsonNode column) {
        if (column == null || !column.isObject()) {
            return false;
        }
        JsonNode mapping = column.get("valueMapping");
        return mapping != null && mapping.isObject() && mapping.size() > 0;
    }

    private boolean shouldSuggestBadge(
            String inferredType,
            String explicitType,
            int cardinality,
            boolean isLongText,
            boolean hasValueMapping) {
        if (isLongText) {
            return false;
        }
        if (hasValueMapping) {
            return true;
        }
        boolean lowCardinality = cardinality > 0 && cardinality <= BADGE_CARDINALITY_MAX;
        boolean typeMatch = "string".equalsIgnoreCase(inferredType)
                || "string".equalsIgnoreCase(explicitType)
                || "boolean".equalsIgnoreCase(inferredType)
                || "boolean".equalsIgnoreCase(explicitType);
        return typeMatch && lowCardinality;
    }

    private boolean isDateType(String inferredType, String explicitType) {
        return "date".equalsIgnoreCase(explicitType)
                || "date".equalsIgnoreCase(inferredType)
                || "datetime".equalsIgnoreCase(explicitType)
                || "datetime".equalsIgnoreCase(inferredType);
    }

    private boolean isBooleanType(String inferredType, String explicitType) {
        return "boolean".equalsIgnoreCase(explicitType)
                || "boolean".equalsIgnoreCase(inferredType);
    }

    private boolean isCurrencyType(String explicitType) {
        return "currency".equalsIgnoreCase(explicitType);
    }

    private boolean isCurrencyField(String explicitType, String controlType, String numericFormat) {
        return isCurrencyType(explicitType)
                || isCurrencyControlType(controlType)
                || isCurrencyNumericFormat(numericFormat);
    }

    private boolean isCurrencyControlType(String controlType) {
        if (isBlank(controlType)) {
            return false;
        }
        String normalized = controlType.trim().toLowerCase(Locale.ROOT);
        return "currency".equals(normalized)
                || "currency-input".equals(normalized)
                || "currency_input".equals(normalized)
                || "currencyinput".equals(normalized);
    }

    private boolean isCurrencyNumericFormat(String numericFormat) {
        return "currency".equalsIgnoreCase(numericFormat);
    }

    private String resolveCurrency(String locale) {
        if (locale == null || locale.isBlank()) {
            return "BRL";
        }
        String normalized = locale.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("pt")) {
            return "BRL";
        }
        if (normalized.startsWith("en-gb") || normalized.startsWith("en-uk")) {
            return "GBP";
        }
        if (normalized.startsWith("en")) {
            return "USD";
        }
        return "USD";
    }

    private int readRowCount(JsonNode dataProfile) {
        if (dataProfile == null || !dataProfile.isObject()) {
            return -1;
        }
        return intOrDefault(dataProfile.get("rowCount"), -1);
    }

    private JsonNode dataProfileColumns(JsonNode dataProfile) {
        if (dataProfile == null || !dataProfile.isObject()) {
            return null;
        }
        JsonNode columns = dataProfile.get("columns");
        return columns != null && columns.isObject() ? columns : null;
    }

    private Map<String, SchemaFieldHint> extractSchemaFieldHints(JsonNode currentState) {
        if (currentState == null || !currentState.isObject()) {
            return Map.of();
        }
        JsonNode fieldsNode = currentState.get("schemaFields");
        if (fieldsNode == null || !fieldsNode.isArray()) {
            fieldsNode = currentState.get("fieldDefinitions");
        }
        if (fieldsNode == null || !fieldsNode.isArray() || fieldsNode.size() == 0) {
            return Map.of();
        }
        Map<String, SchemaFieldHint> hints = new LinkedHashMap<>();
        for (JsonNode field : fieldsNode) {
            if (field == null || !field.isObject()) {
                continue;
            }
            String name = textOrNull(field.get("name"));
            if (isBlank(name)) {
                name = textOrNull(field.get("field"));
            }
            if (isBlank(name)) {
                continue;
            }
            String controlType = textOrNull(field.get("controlType"));
            String numericFormat = textOrNull(field.get("numericFormat"));
            List<String> optionValues = extractFieldOptions(field);
            if (isBlank(controlType) && isBlank(numericFormat) && optionValues.isEmpty()) {
                continue;
            }
            hints.put(name, new SchemaFieldHint(controlType, numericFormat, optionValues));
        }
        return hints;
    }

    private List<String> extractFieldOptions(JsonNode field) {
        if (field == null || !field.isObject()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        JsonNode optionsNode = field.get("options");
        if (optionsNode != null && optionsNode.isArray()) {
            for (JsonNode option : optionsNode) {
                if (option == null || option.isNull()) {
                    continue;
                }
                if (option.isTextual() || option.isNumber()) {
                    String value = option.asText();
                    if (!isBlank(value)) {
                        unique.add(value.trim());
                    }
                    continue;
                }
                if (!option.isObject()) {
                    continue;
                }
                String key = textOrNull(option.get("key"));
                String value = textOrNull(option.get("value"));
                if (!isBlank(key)) {
                    unique.add(key.trim());
                } else if (!isBlank(value)) {
                    unique.add(value.trim());
                }
            }
        }
        JsonNode enumNode = field.get("enumValues");
        if (enumNode != null && enumNode.isArray()) {
            for (JsonNode option : enumNode) {
                if (option == null || option.isNull()) {
                    continue;
                }
                if (option.isTextual() || option.isNumber()) {
                    String value = option.asText();
                    if (!isBlank(value)) {
                        unique.add(value.trim());
                    }
                    continue;
                }
                if (!option.isObject()) {
                    continue;
                }
                String value = textOrNull(option.get("value"));
                if (!isBlank(value)) {
                    unique.add(value.trim());
                }
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(unique);
    }

    private JsonNode resolveConfig(JsonNode currentState) {
        if (currentState == null || currentState.isNull()) {
            return objectMapper.createObjectNode();
        }
        JsonNode configNode = currentState.get("config");
        if (configNode != null && configNode.isObject()) {
            return configNode;
        }
        JsonNode inputs = currentState.get("inputs");
        if (inputs != null && inputs.isObject()) {
            JsonNode inputsConfig = inputs.get("config");
            if (inputsConfig != null && inputsConfig.isObject()) {
                return inputsConfig;
            }
        }
        return currentState;
    }

    private JsonNode safeObject(JsonNode node) {
        if (node != null && node.isObject()) {
            return node;
        }
        return objectMapper.createObjectNode();
    }

    private ArrayNode arrayNode(JsonNode node) {
        if (node != null && node.isArray()) {
            return (ArrayNode) node;
        }
        return objectMapper.createArrayNode();
    }

    private boolean booleanAt(JsonNode node, String pointer) {
        if (node == null || pointer == null) {
            return false;
        }
        JsonNode value = node.at(pointer);
        return value != null && value.isBoolean() && value.asBoolean(false);
    }

    private String textAt(JsonNode node, String pointer) {
        if (node == null || pointer == null) {
            return null;
        }
        JsonNode value = node.at(pointer);
        return textOrNull(value);
    }

    private boolean hasText(JsonNode node) {
        String value = textOrNull(node);
        return !isBlank(value);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value != null && !value.isBlank() ? value : null;
    }

    private int intOrDefault(JsonNode node, int defaultValue) {
        if (node == null || !node.isNumber()) {
            return defaultValue;
        }
        return node.asInt();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean equalsIgnoreCase(String value, String expected) {
        if (value == null || expected == null) {
            return false;
        }
        return value.equalsIgnoreCase(expected);
    }

    private AiSuggestion buildBadgeSuggestion(
            String field,
            String display,
            JsonNode stats,
            String explicitType,
            boolean supportsConditional,
            SchemaFieldHint schemaHint) {
        List<String> values = extractBadgeValues(stats, schemaHint);
        ObjectNode contextHints = buildBadgeContextHints(values);
        String inferredType = stats != null ? textOrNull(stats.get("inferredType")) : null;
        boolean isBoolean = isBooleanType(inferredType, explicitType) || looksLikeBooleanValues(values);
        boolean quoteValues = !isBoolean;
        JsonNode patch = (supportsConditional && !values.isEmpty())
                ? buildConditionalBadgePatch(field, values, quoteValues)
                : null;
        List<String> missingContext = patch == null
                ? List.of("values_and_colors")
                : List.of();

        AiSuggestion.AiSuggestionBuilder builder = AiSuggestion.builder()
                .id("table.renderer.badge." + field)
                .label("Badges para " + display)
                .description("Destacar valores com badges coloridos.")
                .icon("verified")
                .group("Visual")
                .intent("Usar badges coloridos na coluna " + field + " por valor")
                .score(0.68);

        if (patch != null) {
            builder.patch(patch);
        }
        if (contextHints != null && contextHints.size() > 0) {
            contextHints.put("field", field);
            if (!isBlank(inferredType)) {
                contextHints.put("inferredType", inferredType);
            }
            if (!isBlank(explicitType)) {
                contextHints.put("explicitType", explicitType);
            }
            builder.contextHints(contextHints);
        }
        if (!missingContext.isEmpty()) {
            builder.missingContext(missingContext);
        }

        return builder.build();
    }

    private List<String> extractBadgeValues(JsonNode stats, SchemaFieldHint schemaHint) {
        List<String> values = extractBadgeValuesFromProfile(stats);
        if ((values == null || values.isEmpty()) && schemaHint != null) {
            values = schemaHint.getOptionValues();
        }
        return limitBadgeValues(values);
    }

    private List<String> extractBadgeValuesFromProfile(JsonNode stats) {
        if (stats == null || !stats.isObject()) {
            return List.of();
        }
        JsonNode topValues = stats.get("topValues");
        if (topValues == null || !topValues.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode value : topValues) {
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText();
            if (text == null || text.isBlank()) {
                continue;
            }
            unique.add(text.trim());
            if (unique.size() >= BADGE_CARDINALITY_MAX) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private List<String> limitBadgeValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unique.add(value.trim());
            if (unique.size() >= BADGE_CARDINALITY_MAX) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private ObjectNode buildBadgeContextHints(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        ObjectNode hints = objectMapper.createObjectNode();
        ArrayNode valuesNode = hints.putArray("values");
        for (String value : values) {
            valuesNode.add(value);
        }
        ArrayNode paletteNode = hints.putArray("palette");
        for (String color : DEFAULT_BADGE_PALETTE) {
            paletteNode.add(color);
        }
        ObjectNode mapNode = hints.putObject("valueColorMap");
        Map<String, String> map = assignColors(values, DEFAULT_BADGE_PALETTE);
        for (Map.Entry<String, String> entry : map.entrySet()) {
            mapNode.put(entry.getKey(), entry.getValue());
        }
        return hints;
    }

    private JsonNode buildConditionalBadgePatch(String field, List<String> values, boolean quoteValues) {
        Map<String, String> colorMap = assignColors(values, DEFAULT_BADGE_PALETTE);
        ObjectNode patch = objectMapper.createObjectNode();
        ArrayNode columns = patch.putArray("columns");
        ObjectNode column = columns.addObject();
        column.put("field", field);
        ObjectNode renderer = column.putObject("renderer");
        renderer.put("type", "badge");
        ObjectNode badge = renderer.putObject("badge");
        badge.put("textField", field);
        badge.put("variant", "filled");

        ArrayNode conditional = column.putArray("conditionalRenderers");
        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            ObjectNode rule = conditional.addObject();
            rule.put("condition", buildEqualityCondition(field, entry.getKey(), quoteValues));
            ObjectNode ruleRenderer = rule.putObject("renderer");
            ruleRenderer.put("type", "badge");
            ObjectNode ruleBadge = ruleRenderer.putObject("badge");
            ruleBadge.put("textField", field);
            ruleBadge.put("variant", "filled");
            ruleBadge.put("color", entry.getValue());
        }
        return patch;
    }

    private Map<String, String> assignColors(List<String> values, List<String> palette) {
        if (values == null || values.isEmpty() || palette == null || palette.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        int paletteSize = palette.size();
        for (int i = 0; i < values.size(); i += 1) {
            String value = values.get(i);
            if (value == null || value.isBlank()) {
                continue;
            }
            String color = palette.get(i % paletteSize);
            out.put(value, color);
        }
        return out;
    }

    private String buildEqualityCondition(String field, String value, boolean quoteValue) {
        String safeValue = escapeDslString(value);
        if (!quoteValue) {
            return field + " == " + safeValue;
        }
        return field + " == '" + safeValue + "'";
    }

    private String escapeDslString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "\\'");
    }

    private boolean looksLikeBooleanValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value == null) {
                return false;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!"true".equals(normalized) && !"false".equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private void addSuggestion(Map<String, AiSuggestion> out, AiSuggestion suggestion) {
        if (suggestion == null || isBlank(suggestion.getId())) {
            return;
        }
        out.putIfAbsent(suggestion.getId(), suggestion);
    }

    private String sampleNames(List<String> names, int limit) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        int max = Math.min(limit, names.size());
        return String.join(", ", names.subList(0, max));
    }

    private boolean hasCapability(Set<String> allowedPaths, String path) {
        if (allowedPaths == null || allowedPaths.isEmpty() || path == null) {
            return false;
        }
        return allowedPaths.contains(path);
    }

    private boolean hasCapabilityPrefix(Set<String> allowedPaths, String prefix) {
        if (allowedPaths == null || allowedPaths.isEmpty() || prefix == null) {
            return false;
        }
        if (allowedPaths.contains(prefix)) {
            return true;
        }
        for (String path : allowedPaths) {
            if (path != null && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> buildAllowedPathSet(List<AiCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (AiCapability cap : capabilities) {
            if (cap == null) {
                continue;
            }
            String path = cap.getPath();
            if (path != null && !path.isBlank()) {
                paths.add(path.trim());
            }
        }
        return paths;
    }

    private Set<String> extractRuntimeCapabilities(JsonNode currentState) {
        if (currentState == null || !currentState.isObject()) {
            return Set.of();
        }
        JsonNode capsNode = currentState.get("capabilities");
        if (capsNode == null || !capsNode.isArray() || capsNode.isEmpty()) {
            return Set.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (JsonNode cap : capsNode) {
            if (cap == null || cap.isNull()) {
                continue;
            }
            String path = cap.isTextual() ? cap.asText() : textOrNull(cap.get("path"));
            if (!isBlank(path)) {
                paths.add(path.trim());
            }
        }
        return paths;
    }

    private Set<String> resolveAllowedPaths(Set<String> registryPaths, Set<String> runtimePaths) {
        if (runtimePaths == null || runtimePaths.isEmpty()) {
            return registryPaths != null ? registryPaths : Set.of();
        }
        if (registryPaths == null || registryPaths.isEmpty()) {
            return runtimePaths;
        }
        Set<String> intersection = new LinkedHashSet<>();
        for (String path : runtimePaths) {
            if (registryPaths.contains(path)) {
                intersection.add(path);
            }
        }
        return intersection;
    }

    private List<AiCapability> extractCapabilities(JsonNode componentDefinition) {
        if (componentDefinition == null) {
            return List.of();
        }
        JsonNode jsonSchema = componentDefinition.get("jsonSchema");
        JsonNode capsNode = jsonSchema != null ? jsonSchema.get("capabilities") : null;
        if (capsNode == null || !capsNode.isArray()) {
            JsonNode directCaps = componentDefinition.get("capabilities");
            if (directCaps == null || !directCaps.isArray()) {
                return List.of();
            }
            capsNode = directCaps;
        }
        return objectMapper.convertValue(capsNode, new TypeReference<List<AiCapability>>() {});
    }
}
