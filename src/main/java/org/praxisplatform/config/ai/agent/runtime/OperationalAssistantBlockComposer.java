package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordance;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentRelatedDataset;
import org.springframework.util.StringUtils;

/**
 * Compositor deterministico de {@code assistantContent} ({@code praxis.assistant-content/v2}) e
 * {@code quickReplies} a partir dos artefatos autoritativos coletados no turno.
 *
 * <p>Todos os nomes de campos emitidos respeitam estritamente a politica de sanitizacao de eventos
 * SSE do starter (evitando chaves reservadas/redatadas como {@code summary}, {@code content},
 * {@code message}, {@code details} ou sufixos {@code token}).
 */
public class OperationalAssistantBlockComposer {

    public static final String CONTENT_VERSION = "praxis.assistant-content/v2";
    private static final int MAX_OVERVIEW_FIELDS = 16;
    private static final int MAX_RELATED_COLUMNS = 6;

    private final ObjectMapper objectMapper;

    public OperationalAssistantBlockComposer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    public ComposedAssistantOutput compose(String assistantNarrative, AgentTurnArtifactStore artifacts) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", CONTENT_VERSION);
        ArrayNode blocks = root.putArray("blocks");
        List<Map<String, Object>> quickReplies = new ArrayList<>();

        if (StringUtils.hasText(assistantNarrative)) {
            ObjectNode paragraph = blocks.addObject();
            paragraph.put("type", "paragraph");
            paragraph.put("text", assistantNarrative.trim());
        }

        List<AgentEntityDossier> dossiers = artifacts != null ? artifacts.dossiers() : List.of();
        List<AgentEntityMatch> matches = artifacts != null ? artifacts.lastSearchMatches() : List.of();
        List<AgentQueryResult> queries = artifacts != null ? artifacts.queryResults() : List.of();

        if (!dossiers.isEmpty()) {
            List<AgentEntityDossier> visibleDossiers = filterSupersededDossiers(dossiers);
            appendConsolidatedDossiers(blocks, visibleDossiers);
        } else if (matches.size() > 1) {
            appendDisambiguationBlock(blocks, quickReplies, matches);
        } else if (!queries.isEmpty()) {
            for (AgentQueryResult query : queries) {
                appendQueryResultBlock(blocks, query);
            }
        } else if (matches.size() == 1) {
            appendDisambiguationBlock(blocks, quickReplies, matches);
        }

        return new ComposedAssistantOutput(root, List.copyOf(quickReplies));
    }

    private static List<AgentEntityDossier> filterSupersededDossiers(List<AgentEntityDossier> dossiers) {
        if (dossiers.size() <= 1) {
            return dossiers;
        }
        Set<String> supersededKeys = new LinkedHashSet<>();
        for (AgentEntityDossier dossier : dossiers) {
            if (dossier.entity() != null && StringUtils.hasText(dossier.entity().supersedesEntityKey())) {
                supersededKeys.add(dossier.entity().supersedesEntityKey().trim().toLowerCase(Locale.ROOT));
            }
        }
        if (supersededKeys.isEmpty()) {
            return dossiers;
        }
        List<AgentEntityDossier> remaining = new ArrayList<>(dossiers.size());
        for (AgentEntityDossier dossier : dossiers) {
            String entityKey = dossier.entity() != null && StringUtils.hasText(dossier.entity().entityKey())
                    ? dossier.entity().entityKey().trim().toLowerCase(Locale.ROOT)
                    : "";
            String resourceKey = dossier.entity() != null && StringUtils.hasText(dossier.entity().resourceKey())
                    ? dossier.entity().resourceKey().trim().toLowerCase(Locale.ROOT)
                    : "";
            if (!supersededKeys.contains(entityKey) && !supersededKeys.contains(resourceKey)) {
                remaining.add(dossier);
            }
        }
        return remaining.isEmpty() ? dossiers : remaining;
    }

    private void appendConsolidatedDossiers(ArrayNode blocks, List<AgentEntityDossier> dossiers) {
        Map<String, AgentProactiveAlert> uniqueAlerts = new LinkedHashMap<>();
        for (AgentEntityDossier dossier : dossiers) {
            for (AgentProactiveAlert alert : dossier.alerts()) {
                String codeOrTitle = firstNonBlank(alert.code(), firstNonBlank(alert.title(), ""));
                String key = (nullToEmpty(codeOrTitle) + "::" + nullToEmpty(alert.entityId())).toLowerCase(Locale.ROOT);
                uniqueAlerts.putIfAbsent(key, alert);
            }
        }
        if (!uniqueAlerts.isEmpty()) {
            ObjectNode alertList = blocks.addObject();
            alertList.put("type", "alert-list");
            alertList.put("title", "Alertas operacionais");
            ArrayNode items = alertList.putArray("items");
            for (AgentProactiveAlert alert : uniqueAlerts.values()) {
                ObjectNode item = items.addObject();
                putIfText(item, "code", alert.code());
                putIfText(item, "severity", firstNonBlank(alert.severity(), "warning"));
                putIfText(item, "title", alert.title());
                putIfText(item, "description", alert.description());
                putIfText(item, "entityKey", alert.entityKey());
                putIfText(item, "resourcePath", alert.resourcePath());
                putIfText(item, "entityId", alert.entityId());
                putIfText(item, "suggestedActionId", alert.suggestedActionId());
                putIfText(item, "suggestedActionLabel", alert.suggestedActionLabel());
            }
        }

        Set<String> seenRelated = new LinkedHashSet<>();
        Map<String, AgentActionAffordance> uniqueActions = new LinkedHashMap<>();
        for (AgentEntityDossier dossier : dossiers) {
            appendDossierWidgetBlock(blocks, dossier);
            for (AgentRelatedDataset dataset : dossier.relatedDatasets()) {
                String relatedKey = (nullToEmpty(dataset.surfaceId()) + "::" + nullToEmpty(dataset.childResourcePath()))
                        .toLowerCase(Locale.ROOT);
                if (!seenRelated.add(relatedKey)) {
                    continue;
                }
                ObjectNode collection = blocks.addObject();
                collection.put("type", "compact-collection");
                collection.put("variant", "related-dataset");
                collection.put("title", firstNonBlank(dataset.title(), dataset.surfaceId()));
                putIfText(collection, "surfaceId", dataset.surfaceId());
                putIfText(collection, "entityKey", dataset.childResourceKey());
                putIfText(collection, "resourcePath", dataset.childResourcePath());
                collection.put("totalCount", dataset.totalCount());
                appendRowsAndColumns(collection, dataset.uiRows());
            }
            for (AgentActionAffordance action : dossier.actions()) {
                String actionKey = (nullToEmpty(action.actionId()) + "::" + nullToEmpty(action.entityId()))
                        .toLowerCase(Locale.ROOT);
                uniqueActions.putIfAbsent(actionKey, action);
            }
        }

        if (!uniqueActions.isEmpty()) {
            appendActionBar(blocks, "Acoes disponiveis", List.copyOf(uniqueActions.values()));
        }
    }

    private void appendDossierWidgetBlock(ArrayNode blocks, AgentEntityDossier dossier) {
        ObjectNode widgetBlock = blocks.addObject();
        widgetBlock.put("type", "widget-block");
        widgetBlock.put("widgetType", "praxis-rich-content");
        widgetBlock.put("title", firstNonBlank(dossier.displayTitle(), dossier.entity().title()));
        putIfText(widgetBlock, "entityKey", dossier.entity().entityKey());
        putIfText(widgetBlock, "resourceKey", dossier.entity().resourceKey());
        putIfText(widgetBlock, "resourcePath", dossier.entity().resourcePath());
        putIfText(widgetBlock, "entityId", dossier.entityId());

        ObjectNode doc = widgetBlock.putObject("document");
        doc.put("kind", "praxis.rich-content");
        doc.put("version", "1.0.0");

        ArrayNode nodes = doc.putArray("nodes");
        ObjectNode recordNode = nodes.addObject();
        recordNode.put("type", "recordSummary");
        putIfText(recordNode, "title", firstNonBlank(dossier.displayTitle(), dossier.entityId()));
        putIfText(recordNode, "subtitle", dossier.displaySubtitle());
        putIfText(recordNode, "meta", dossier.entity().title());
        ArrayNode fields = recordNode.putArray("fields");
        int count = 0;
        for (Map.Entry<String, Object> entry : dossier.uiRecord().entrySet()) {
            if (entry.getValue() == null || !StringUtils.hasText(entry.getValue().toString())) {
                continue;
            }
            if (count >= MAX_OVERVIEW_FIELDS) {
                break;
            }
            ObjectNode fieldNode = fields.addObject();
            fieldNode.put("id", entry.getKey());
            fieldNode.put("label", humanizeFieldKey(entry.getKey()));
            fieldNode.put("value", String.valueOf(entry.getValue()));
            count++;
        }

        ObjectNode widget = widgetBlock.putObject("widget");
        widget.put("id", "praxis-rich-content");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("layout", "block");
        inputs.set("document", doc.deepCopy());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void appendDisambiguationBlock(
            ArrayNode blocks,
            List<Map<String, Object>> quickReplies,
            List<AgentEntityMatch> matches) {
        ObjectNode collection = blocks.addObject();
        collection.put("type", "compact-collection");
        collection.put("variant", "disambiguation");
        collection.put("title", "Registros encontrados (" + matches.size() + ")");
        collection.put("subtitle", "Selecione o registro exato para abrir o dossie 360");
        collection.put("totalCount", matches.size());

        ArrayNode columns = collection.putArray("columns");
        addColumn(columns, "entityTitle", "Tipo");
        addColumn(columns, "title", "Identificacao");
        addColumn(columns, "subtitle", "Detalhe");
        addColumn(columns, "entityId", "Codigo");

        ArrayNode rows = collection.putArray("rows");
        for (AgentEntityMatch match : matches) {
            ObjectNode row = rows.addObject();
            putIfText(row, "entityKey", match.entityKey());
            putIfText(row, "resourceKey", match.resourceKey());
            putIfText(row, "resourcePath", match.resourcePath());
            putIfText(row, "entityTitle", match.entityTitle());
            putIfText(row, "entityId", match.entityId());
            putIfText(row, "title", match.title());
            putIfText(row, "subtitle", match.subtitle());
            putIfText(row, "matchedBy", match.matchedBy());
            row.put("fromActiveScreenScope", match.fromActiveScreenScope());

            Map<String, Object> quickReply = new LinkedHashMap<>();
            quickReply.put("id", "candidate-" + safeSlug(match.entityKey()) + "-" + safeSlug(match.entityId()));
            quickReply.put("label", match.title() + " (" + match.entityTitle() + ")");
            quickReply.put("prompt", "Abrir visao 360 de " + match.title()
                    + " em " + match.entityTitle() + " (ID " + match.entityId() + ")");
            quickReply.put("value", match.entityKey() + ":" + match.entityId());
            quickReply.put("kind", "entity-disambiguation");
            quickReply.put("description", match.subtitle());
            quickReply.put("icon", "person_search");
            quickReply.put("tone", match.fromActiveScreenScope() ? "primary" : "neutral");

            Map<String, Object> contextHints = new LinkedHashMap<>();
            contextHints.put("kind", "entity-disambiguation");
            if (StringUtils.hasText(match.resourceKey())) {
                contextHints.put("resourceKey", match.resourceKey());
            }
            if (StringUtils.hasText(match.resourcePath())) {
                contextHints.put("resourcePath", match.resourcePath());
            }
            contextHints.put("candidateRef", match.entityKey() + ":" + match.entityId());
            quickReply.put("contextHints", contextHints);
            quickReplies.add(quickReply);
        }
    }

    private void appendQueryResultBlock(ArrayNode blocks, AgentQueryResult query) {
        ObjectNode collection = blocks.addObject();
        collection.put("type", "compact-collection");
        collection.put("variant", "records");
        collection.put("title", firstNonBlank(query.title(), query.entityKey()));
        putIfText(collection, "entityKey", query.entityKey());
        putIfText(collection, "resourceKey", query.resourceKey());
        putIfText(collection, "resourcePath", query.resourcePath());
        collection.put("totalCount", query.totalElements());
        appendRowsAndColumns(collection, query.uiRecords());

        if (!query.collectionActions().isEmpty()) {
            appendActionBar(blocks, "Acoes do recurso", query.collectionActions());
        }
    }

    private void appendActionBar(ArrayNode blocks, String title, List<AgentActionAffordance> actions) {
        ObjectNode bar = blocks.addObject();
        bar.put("type", "action-bar");
        bar.put("title", title);
        ArrayNode items = bar.putArray("items");
        for (AgentActionAffordance action : actions) {
            ObjectNode node = items.addObject();
            putIfText(node, "actionId", action.actionId());
            putIfText(node, "label", action.label());
            putIfText(node, "description", action.description());
            putIfText(node, "icon", action.icon());
            putIfText(node, "tone", action.tone());
            putIfText(node, "resourceKey", action.resourceKey());
            putIfText(node, "resourcePath", action.resourcePath());
            putIfText(node, "entityId", action.entityId());
            putIfText(node, "operationKind", action.operationKind());
            putIfText(node, "formMode", action.formMode());
            putIfText(node, "method", action.method());
            putIfText(node, "submitUrl", action.submitUrl());
            putIfText(node, "schemaUrl", action.schemaUrl());
            putIfText(node, "route", action.route());
            node.set("queryParams", objectMapper.valueToTree(action.queryParams()));
            node.set("prefill", objectMapper.valueToTree(action.prefill()));
            node.put("confirmRequired", action.confirmRequired());
            node.put("disabled", action.disabled());
            putIfText(node, "unavailableNote", action.disabledReason());
        }
    }

    private void appendRowsAndColumns(ObjectNode target, List<Map<String, Object>> uiRows) {
        ArrayNode columns = target.putArray("columns");
        ArrayNode rows = target.putArray("rows");
        if (uiRows == null || uiRows.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (String key : uiRows.get(0).keySet()) {
            if (keys.size() >= MAX_RELATED_COLUMNS) {
                break;
            }
            keys.add(key);
            addColumn(columns, key, humanizeFieldKey(key));
        }
        for (Map<String, Object> rowMap : uiRows) {
            ObjectNode rowNode = rows.addObject();
            for (String key : keys) {
                Object val = rowMap.get(key);
                if (val != null) {
                    rowNode.set(key, objectMapper.valueToTree(val));
                }
            }
        }
    }

    private static void addColumn(ArrayNode columns, String key, String label) {
        ObjectNode col = columns.addObject();
        col.put("key", key);
        col.put("label", label);
    }

    private static void putIfText(ObjectNode node, String field, String value) {
        if (StringUtils.hasText(value)) {
            node.put(field, value.trim());
        }
    }

    private static String humanizeFieldKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            return "";
        }
        String spaced = rawKey.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String safeSlug(String value) {
        if (!StringUtils.hasText(value)) {
            return "item";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (StringUtils.hasText(second) ? second.trim() : null);
    }

    public record ComposedAssistantOutput(
            JsonNode assistantContent,
            List<Map<String, Object>> quickReplies
    ) {
    }
}
