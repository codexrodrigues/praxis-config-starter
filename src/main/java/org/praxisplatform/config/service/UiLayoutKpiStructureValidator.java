package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.praxisplatform.config.service.UiLayoutKpiCatalogOracle.ResourceStatsContract;
import org.praxisplatform.config.service.UiLayoutKpiCatalogOracle.StatsFieldCapabilities;
import org.praxisplatform.config.service.UiLayoutKpiCatalogOracle.VerifiedRecipeSpec;
import org.springframework.util.StringUtils;

/**
 * Validador fail-closed de estrutura, cotas anti-DoS, anti-SSRF e aderência semântica ao catálogo
 * para widgets e documentos executivos {@code praxis-kpi-strip} e {@code praxis-kpi-card}
 * ({@code ADR-0007} Fase 4).
 */
public final class UiLayoutKpiStructureValidator {

    public static final int MAX_CARDS_PER_STRIP = 12;
    public static final int MAX_DISTINCT_KPI_QUERIES_PER_PAGE = 24;
    private static final int MAX_WIDGET_NESTING_DEPTH = 12;
    private static final Set<String> VALID_LOCK_LEVELS = Set.of("none", "metric-locked", "full");
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

    private UiLayoutKpiStructureValidator() {}

    public static void validateStructureOnly(String documentType, JsonNode document) {
        validate(documentType, document, null);
    }

    /**
     * Valida estruturalmente payloads runtime persistidos em {@code ui_user_config}
     * (cotas anti-DoS, {@code governance.lockLevel} válido e caminhos canônicos anti-SSRF),
     * inclusive dentro de containers aninhados, mesmo na ausência de baseline publicado em
     * {@code ui-layouts}.
     */
    public static void validateRuntimePayloadStructure(String componentType, JsonNode payload) {
        if (!StringUtils.hasText(componentType) || payload == null || payload.isNull()) {
            return;
        }
        String type = componentType.trim();
        String mappedDocType =
                switch (type) {
                    case "praxis-dynamic-page" -> "praxis.dynamic-page.editor";
                    case "praxis-kpi-strip" -> "praxis.kpi-strip.editor";
                    case "praxis-kpi-card" -> "praxis.kpi-card.editor";
                    default -> null;
                };
        if (mappedDocType == null) {
            return;
        }
        List<JsonNode> cards = collectCardsFromRuntimeOrEditor(mappedDocType, payload, true);
        validateCards(cards, null);
    }

    public static void validate(
            String documentType, JsonNode document, UiLayoutKpiCatalogOracle catalog) {
        if (!StringUtils.hasText(documentType) || document == null || document.isNull()) {
            return;
        }
        String kind = documentType.trim();
        if (!"praxis.dynamic-page.editor".equals(kind)
                && !"praxis.kpi-strip.editor".equals(kind)
                && !"praxis.kpi-card.editor".equals(kind)) {
            return;
        }

        List<JsonNode> cards = collectCardsFromRuntimeOrEditor(kind, document, false);
        validateCards(cards, catalog);
    }

    private static void validateCards(List<JsonNode> cards, UiLayoutKpiCatalogOracle catalog) {
        Set<String> distinctQueries = new LinkedHashSet<>();

        for (int index = 0; index < cards.size(); index++) {
            JsonNode card = cards.get(index);
            if (card == null || !card.isObject()) {
                throw fail("KPI card entry must be a JSON object.");
            }
            validateGovernanceLockLevel(card);

            JsonNode source = card.path("source");
            if (!source.isObject()) {
                throw fail("KPI card source must be a JSON object.");
            }
            String canonicalSourcePath =
                    requireCanonicalResourcePath(source.path("resourcePath"), "source.resourcePath");

            JsonNode interaction = card.path("interaction");
            String canonicalCrossFilterTargetPath = null;
            String canonicalDrillDownPath = null;
            if (interaction.isObject()) {
                JsonNode crossFilterTarget = interaction.path("crossFilter").path("targetResourcePath");
                if (!crossFilterTarget.isMissingNode() && !crossFilterTarget.isNull()) {
                    canonicalCrossFilterTargetPath =
                            requireCanonicalResourcePath(
                                    crossFilterTarget, "interaction.crossFilter.targetResourcePath");
                }
                JsonNode drillDownPath = interaction.path("drillDown").path("resourcePath");
                if (!drillDownPath.isMissingNode() && !drillDownPath.isNull()) {
                    canonicalDrillDownPath =
                            requireCanonicalResourcePath(
                                    drillDownPath, "interaction.drillDown.resourcePath");
                }
            }

            distinctQueries.add(canonicalSourceQueryKey(source, canonicalSourcePath));
            if (distinctQueries.size() > MAX_DISTINCT_KPI_QUERIES_PER_PAGE) {
                throw fail(
                        "Distinct KPI queries per page exceed maximum allowed limit of "
                                + MAX_DISTINCT_KPI_QUERIES_PER_PAGE
                                + ".");
            }

            if (catalog != null) {
                validateAgainstCatalog(
                        source,
                        canonicalSourcePath,
                        canonicalCrossFilterTargetPath,
                        canonicalDrillDownPath,
                        catalog);
            }
        }
    }

    private static List<JsonNode> collectCardsFromRuntimeOrEditor(
            String documentType, JsonNode document, boolean runtimeMode) {
        List<JsonNode> cards = new ArrayList<>();
        if ("praxis.kpi-card.editor".equals(documentType)) {
            JsonNode cardConfig =
                    runtimeMode
                            ? locateRuntimeSingleCardContainer(document)
                            : extractStandalonePayloadConfig(document, "source");
            if (cardConfig == null || !cardConfig.isObject()) {
                throw fail("KPI card configuration must declare an object payload.");
            }
            cards.add(cardConfig);
            return cards;
        }
        if ("praxis.kpi-strip.editor".equals(documentType)) {
            JsonNode stripConfig =
                    runtimeMode
                            ? locateRuntimeStripContainer(document)
                            : extractStandalonePayloadConfig(document, "cards");
            if (stripConfig == null || !stripConfig.isObject()) {
                throw fail("KPI strip configuration must declare an object payload.");
            }
            collectStripCards(stripConfig, cards);
            return cards;
        }
        JsonNode rootWidgets =
                runtimeMode
                        ? extractRuntimePageWidgetsArray(document)
                        : document.at("/config/definition/widgets");
        if (!rootWidgets.isArray()) {
            return cards;
        }
        List<WidgetNodeView> widgets = new ArrayList<>();
        collectWidgetsRecursive(rootWidgets, widgets, 0);
        for (WidgetNodeView widget : widgets) {
            String widgetId = widget.widgetId();
            if ("praxis-kpi-card".equals(widgetId)) {
                JsonNode cardConfig = widget.inputs().path("config");
                if (!cardConfig.isObject()) {
                    throw fail("praxis-kpi-card widget must declare an object inputs.config.");
                }
                cards.add(cardConfig);
            } else if ("praxis-kpi-strip".equals(widgetId)) {
                JsonNode stripConfig = widget.inputs().path("config");
                if (!stripConfig.isObject()) {
                    throw fail("praxis-kpi-strip widget must declare an object inputs.config.");
                }
                collectStripCards(stripConfig, cards);
            }
        }
        return cards;
    }

    private static void collectWidgetsRecursive(
            JsonNode widgetsArray, List<WidgetNodeView> out, int depth) {
        if (widgetsArray == null || !widgetsArray.isArray() || depth > MAX_WIDGET_NESTING_DEPTH) {
            return;
        }
        for (JsonNode entry : widgetsArray) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            JsonNode definition = entry.path("definition").isObject() ? entry.path("definition") : entry;
            String widgetId = definition.path("id").asText("");
            JsonNode inputs = definition.path("inputs");
            out.add(new WidgetNodeView(widgetId, inputs));

            JsonNode containerConfig = inputs.path("config");
            if (containerConfig.isObject()) {
                collectSlotWidgets(containerConfig.path("tabs"), out, depth + 1);
                collectSlotWidgets(containerConfig.path("panels"), out, depth + 1);
                collectSlotWidgets(containerConfig.path("steps"), out, depth + 1);
                JsonNode nav = containerConfig.path("nav");
                if (nav.isObject()) {
                    collectSlotWidgets(nav.path("links"), out, depth + 1);
                }
            }
            JsonNode nestedPageWidgets = inputs.path("page").path("widgets");
            if (nestedPageWidgets.isArray()) {
                collectWidgetsRecursive(nestedPageWidgets, out, depth + 1);
            }
        }
    }

    private static void collectSlotWidgets(JsonNode slots, List<WidgetNodeView> out, int depth) {
        if (slots == null || !slots.isArray()) {
            return;
        }
        for (JsonNode slot : slots) {
            if (slot != null && slot.isObject() && slot.path("widgets").isArray()) {
                collectWidgetsRecursive(slot.path("widgets"), out, depth);
            }
        }
    }

    private static JsonNode extractRuntimePageWidgetsArray(JsonNode document) {
        JsonNode direct = document.get("widgets");
        if (direct != null && direct.isArray()) {
            return direct;
        }
        JsonNode def = document.at("/definition/widgets");
        if (def.isArray()) {
            return def;
        }
        return document.at("/config/definition/widgets");
    }

    private static JsonNode locateRuntimeStripContainer(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        if (root.path("cards").isArray()) {
            return root;
        }
        JsonNode cfg = root.get("config");
        if (cfg != null && cfg.isObject()) {
            if (cfg.path("cards").isArray()) {
                return cfg;
            }
            JsonNode nested = cfg.get("config");
            if (nested != null && nested.isObject()) {
                return nested;
            }
            return cfg;
        }
        return root;
    }

    private static JsonNode locateRuntimeSingleCardContainer(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        if (root.has("source") || root.has("derivation")) {
            return root;
        }
        JsonNode cfg = root.get("config");
        if (cfg != null && cfg.isObject()) {
            JsonNode nested = cfg.get("config");
            if (nested != null && nested.isObject()) {
                return nested;
            }
            return cfg;
        }
        return root;
    }

    private record WidgetNodeView(String widgetId, JsonNode inputs) {}

    private static JsonNode extractStandalonePayloadConfig(JsonNode document, String directMarkerKey) {
        JsonNode rootConfig = document.path("config");
        if (!rootConfig.isObject()) {
            return null;
        }
        JsonNode nestedConfig = rootConfig.get("config");
        if (nestedConfig != null && nestedConfig.isObject()) {
            return nestedConfig;
        }
        return rootConfig.has(directMarkerKey) ? rootConfig : null;
    }

    private static void collectStripCards(JsonNode stripConfig, List<JsonNode> out) {
        JsonNode stripCards = stripConfig.get("cards");
        if (stripCards == null || !stripCards.isArray() || stripCards.isEmpty()) {
            throw fail("KPI strip configuration must contain between 1 and " + MAX_CARDS_PER_STRIP + " cards.");
        }
        if (stripCards.size() > MAX_CARDS_PER_STRIP) {
            throw fail(
                    "KPI strip card count ("
                            + stripCards.size()
                            + ") exceeds maximum allowed limit of "
                            + MAX_CARDS_PER_STRIP
                            + ".");
        }
        for (JsonNode card : stripCards) {
            out.add(card);
        }
    }

    private static void validateGovernanceLockLevel(JsonNode card) {
        JsonNode governance = card.path("governance");
        if (governance.isMissingNode() || governance.isNull()) {
            return;
        }
        if (!governance.isObject()) {
            throw fail("KPI card governance must be a JSON object.");
        }
        JsonNode lockLevel = governance.get("lockLevel");
        if (lockLevel == null || lockLevel.isNull()) {
            return;
        }
        if (!lockLevel.isTextual() || !VALID_LOCK_LEVELS.contains(lockLevel.asText())) {
            throw fail("Unsupported KPI governance.lockLevel.");
        }
    }

    private static String requireCanonicalResourcePath(JsonNode pathNode, String fieldName) {
        if (pathNode == null || !pathNode.isTextual()) {
            throw fail("KPI " + fieldName + " must be a canonical relative resource path.");
        }
        return UiLayoutKpiCatalogOracle.normalizeCanonicalResourcePath(pathNode.asText())
                .orElseThrow(
                        () ->
                                fail(
                                        "KPI "
                                                + fieldName
                                                + " rejected by anti-SSRF canonical path policy."));
    }

    private static void validateAgainstCatalog(
            JsonNode source,
            String canonicalSourcePath,
            String canonicalCrossFilterTargetPath,
            String canonicalDrillDownPath,
            UiLayoutKpiCatalogOracle catalog) {
        if (!catalog.isResourcePathAllowed(canonicalSourcePath)) {
            throw fail("KPI source.resourcePath is not admitted by the host catalog: " + canonicalSourcePath);
        }
        if (canonicalCrossFilterTargetPath != null
                && !catalog.isResourcePathAllowed(canonicalCrossFilterTargetPath)) {
            throw fail(
                    "KPI interaction.crossFilter.targetResourcePath is not admitted by the host catalog: "
                            + canonicalCrossFilterTargetPath);
        }
        if (canonicalDrillDownPath != null && !catalog.isResourcePathAllowed(canonicalDrillDownPath)) {
            throw fail(
                    "KPI interaction.drillDown.resourcePath is not admitted by the host catalog: "
                            + canonicalDrillDownPath);
        }

        String kind = source.path("kind").asText("");
        switch (kind) {
            case "verified-recipe" -> validateVerifiedRecipe(source, canonicalSourcePath, catalog);
            case "stats-query" -> validateStatsQuery(source, canonicalSourcePath, catalog);
            case "analytics-projection" -> validateAnalyticsProjection(source, canonicalSourcePath, catalog);
            default -> throw fail("Unsupported KPI source.kind: " + kind);
        }
    }

    private static void validateVerifiedRecipe(
            JsonNode source, String canonicalSourcePath, UiLayoutKpiCatalogOracle catalog) {
        String recipeId = source.path("recipeId").asText("");
        if (!StringUtils.hasText(recipeId)) {
            throw fail("KPI verified-recipe source requires recipeId.");
        }
        VerifiedRecipeSpec spec =
                catalog.findVerifiedRecipe(canonicalSourcePath, recipeId.trim())
                        .orElseThrow(
                                () ->
                                        fail(
                                                "KPI verified recipe '"
                                                        + recipeId
                                                        + "' is not registered for resource "
                                                        + canonicalSourcePath));
        JsonNode parameters = source.path("parameters");
        if (parameters.isObject()) {
            var fieldNames = parameters.fieldNames();
            while (fieldNames.hasNext()) {
                String paramName = fieldNames.next();
                if (!spec.adjustableParams().contains(paramName)) {
                    throw fail(
                            "KPI verified-recipe parameter '"
                                    + paramName
                                    + "' is not declared in adjustableParams of recipe '"
                                    + spec.recipeId()
                                    + "'.");
                }
            }
        }
    }

    private static void validateStatsQuery(
            JsonNode source, String canonicalSourcePath, UiLayoutKpiCatalogOracle catalog) {
        ResourceStatsContract contract =
                catalog.findResourceStatsContract(canonicalSourcePath)
                        .orElseThrow(
                                () ->
                                        fail(
                                                "Resource has no registered StatsFieldRegistry contract: "
                                                        + canonicalSourcePath));
        String operation = source.path("operation").asText("");
        JsonNode request = source.path("request");
        if (!request.isObject()) {
            throw fail("KPI stats-query requires an object request payload.");
        }
        String primaryField =
                requireStatsField(request.path("field"), "request.field", contract, canonicalSourcePath);
        validatePrimaryFieldEligibility(operation, primaryField, contract, canonicalSourcePath);

        if ("comparison".equals(operation) || request.hasNonNull("periodField")) {
            JsonNode periodNode =
                    request.hasNonNull("periodField") ? request.path("periodField") : request.path("field");
            String periodField =
                    requireStatsField(
                            periodNode,
                            "request.periodField",
                            contract,
                            canonicalSourcePath);
            StatsFieldCapabilities periodCaps = contract.fieldCapabilities().get(periodField);
            if (periodCaps != null && !periodCaps.timeSeriesEligible()) {
                throw fail(
                        "KPI stats-query periodField '"
                                + periodField
                                + "' is not timeSeriesEligible for resource "
                                + canonicalSourcePath);
            }
        }

        StatsFieldCapabilities primaryCaps = contract.fieldCapabilities().get(primaryField);
        JsonNode singleMetric = request.path("metric");
        if (singleMetric.isObject()) {
            validateMetricEntry(
                    singleMetric,
                    "request.metric",
                    primaryCaps,
                    contract,
                    canonicalSourcePath);
        }

        JsonNode metrics = request.path("metrics");
        if (metrics.isArray()) {
            for (int i = 0; i < metrics.size(); i++) {
                JsonNode metric = metrics.get(i);
                if (metric != null && metric.isObject()) {
                    validateMetricEntry(
                            metric,
                            "request.metrics[" + i + "]",
                            primaryCaps,
                            contract,
                            canonicalSourcePath);
                }
            }
        }

        validateBaseFilterKeys(request.path("baseFilter"), contract.filterDtoFields(), canonicalSourcePath);
    }

    private static void validatePrimaryFieldEligibility(
            String operation,
            String fieldName,
            ResourceStatsContract contract,
            String canonicalSourcePath) {
        if (contract.fieldCapabilities().isEmpty()) {
            return;
        }
        StatsFieldCapabilities caps = contract.fieldCapabilities().get(fieldName);
        if (caps == null) {
            return;
        }
        if ("timeseries".equals(operation)) {
            if (!caps.timeSeriesEligible()) {
                throw fail(
                        "KPI stats-query field '"
                                + fieldName
                                + "' is not timeSeriesEligible for resource "
                                + canonicalSourcePath);
            }
            return;
        }
        if ("group-by".equals(operation) || "comparison".equals(operation)) {
            if (!caps.groupByEligible()) {
                throw fail(
                        "KPI stats-query field '"
                                + fieldName
                                + "' is not groupByEligible for "
                                + operation
                                + " on resource "
                                + canonicalSourcePath);
            }
        }
    }

    private static void validateMetricEntry(
            JsonNode metric,
            String location,
            StatsFieldCapabilities primaryCaps,
            ResourceStatsContract contract,
            String canonicalSourcePath) {
        String op = metric.path("operation").asText("COUNT").trim().toUpperCase(Locale.ROOT);
        if (metric.hasNonNull("field")) {
            String metricField =
                    requireStatsField(
                            metric.get("field"),
                            location + ".field",
                            contract,
                            canonicalSourcePath);
            if (!contract.fieldCapabilities().isEmpty()) {
                StatsFieldCapabilities metricCaps = contract.fieldCapabilities().get(metricField);
                if (metricCaps == null) {
                    return;
                }
                if (!metricCaps.metricFieldEligible()) {
                    throw fail(
                            "KPI stats-query metric field '"
                                    + metricField
                                    + "' at "
                                    + location
                                    + ".field is not metricFieldEligible for resource "
                                    + canonicalSourcePath);
                }
                if ("AVG_PER_CYCLE_OCCURRENCE".equals(op)) {
                    if (!metricCaps.metrics().contains("AVG_PER_CYCLE_OCCURRENCE")
                            && !metricCaps.metrics().contains("SUM")
                            && !metricCaps.metrics().contains("AVG")) {
                        throw fail(
                                "KPI stats-query metric operation '"
                                        + op
                                        + "' is not allowed on metric field '"
                                        + metricField
                                        + "' for resource "
                                        + canonicalSourcePath);
                    }
                } else if (!metricCaps.metrics().isEmpty() && !metricCaps.metrics().contains(op)) {
                    throw fail(
                            "KPI stats-query metric operation '"
                                    + op
                                    + "' is not allowed on metric field '"
                                    + metricField
                                    + "' for resource "
                                    + canonicalSourcePath);
                }
            }
            return;
        }
        if (!contract.fieldCapabilities().isEmpty()) {
            if (!"COUNT".equals(op)
                    && !"DISTINCT_COUNT".equals(op)
                    && !"AVG_PER_CYCLE_OCCURRENCE".equals(op)) {
                throw fail(
                        "KPI stats-query metric operation '"
                                + op
                                + "' at "
                                + location
                                + " requires metric.field for resource "
                                + canonicalSourcePath);
            }
            if (primaryCaps != null
                    && !"AVG_PER_CYCLE_OCCURRENCE".equals(op)
                    && !primaryCaps.metrics().isEmpty()
                    && !primaryCaps.metrics().contains(op)) {
                throw fail(
                        "KPI stats-query metric operation '"
                                + op
                                + "' is not allowed on field '"
                                + primaryCaps.field()
                                + "' for resource "
                                + canonicalSourcePath);
            }
        }
    }

    private static void validateAnalyticsProjection(
            JsonNode source, String canonicalSourcePath, UiLayoutKpiCatalogOracle catalog) {
        String projectionId = source.path("projectionId").asText("");
        if (!StringUtils.hasText(projectionId)) {
            throw fail("KPI analytics-projection source requires projectionId.");
        }
        Optional<Set<String>> registeredProjections =
                catalog.findAnalyticsProjectionIds(canonicalSourcePath);
        if (registeredProjections.isPresent()
                && !registeredProjections.get().contains(projectionId.trim())) {
            throw fail(
                    "KPI analytics-projection '"
                            + projectionId.trim()
                            + "' is not registered in @UiAnalytics for resource "
                            + canonicalSourcePath);
        }
        JsonNode baseFilter = source.path("baseFilter");
        if (baseFilter.isObject() && !baseFilter.isEmpty()) {
            Optional<ResourceStatsContract> contractOpt =
                    catalog.findResourceStatsContract(canonicalSourcePath);
            if (contractOpt.isPresent()) {
                validateBaseFilterKeys(
                        baseFilter, contractOpt.get().filterDtoFields(), canonicalSourcePath);
            }
        }
    }

    private static String requireStatsField(
            JsonNode fieldNode,
            String fieldLocation,
            ResourceStatsContract contract,
            String canonicalSourcePath) {
        if (fieldNode == null || !fieldNode.isTextual() || !StringUtils.hasText(fieldNode.asText())) {
            throw fail("KPI stats-query " + fieldLocation + " is required.");
        }
        String fieldName = fieldNode.asText().trim();
        if (!contract.statsFields().contains(fieldName)) {
            throw fail(
                    "KPI stats-query field '"
                            + fieldName
                            + "' at "
                            + fieldLocation
                            + " is not registered in StatsFieldRegistry for "
                            + canonicalSourcePath);
        }
        return fieldName;
    }

    private static void validateBaseFilterKeys(
            JsonNode baseFilter, Set<String> filterDtoFields, String canonicalSourcePath) {
        if (baseFilter == null || baseFilter.isMissingNode() || baseFilter.isNull()) {
            return;
        }
        if (!baseFilter.isObject()) {
            throw fail("KPI baseFilter must be a JSON object.");
        }
        var fieldNames = baseFilter.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (!filterDtoFields.contains(key)) {
                throw fail(
                        "KPI baseFilter key '"
                                + key
                                + "' is not a valid FilterDTO field for resource "
                                + canonicalSourcePath);
            }
        }
    }

    private static String canonicalSourceQueryKey(JsonNode source, String canonicalResourcePath) {
        ObjectNode normalized = (ObjectNode) canonicalizeNode(source);
        normalized.put("resourcePath", canonicalResourcePath);
        return normalized.toString();
    }

    private static JsonNode canonicalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return CANONICAL_JSON.nullNode();
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                sorted.put(entry.getKey(), canonicalizeNode(entry.getValue()));
            }
            ObjectNode out = CANONICAL_JSON.createObjectNode();
            for (var entry : sorted.entrySet()) {
                out.set(entry.getKey(), entry.getValue());
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = CANONICAL_JSON.createArrayNode();
            for (JsonNode element : node) {
                out.add(canonicalizeNode(element));
            }
            return out;
        }
        return node.deepCopy();
    }

    private static UiLayoutLifecycleException fail(String message) {
        return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED, message);
    }
}
