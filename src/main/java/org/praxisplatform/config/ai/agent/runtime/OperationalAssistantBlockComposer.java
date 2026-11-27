package org.praxisplatform.config.ai.agent.runtime;

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
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor.TaxIdMatch;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordance;
import org.praxisplatform.config.ai.agent.spi.AgentActionInputField;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory.EvaluatedStep;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory.EvaluatedTrail;
import org.praxisplatform.config.ai.agent.spi.AgentFieldDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeDocument;
import org.praxisplatform.config.ai.agent.spi.AgentPersistedActionProposal;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedAction;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedBulkOperation;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentRelatedDataset;
import org.praxisplatform.config.ai.agent.spi.AgentSimulationPreview;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalProvenance;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult.AgentStatsBucket;
import org.praxisplatform.config.ai.agent.spi.OperationalAssistantBlockContributor;
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
    private static final int MAX_CURATED_OVERVIEW_FIELDS = 10;
    private static final int MAX_FALLBACK_OVERVIEW_FIELDS = 16;
    private static final int MAX_RELATED_COLUMNS = 6;
    private static final int MAX_KNOWLEDGE_CITATIONS = 6;

    private final ObjectMapper objectMapper;
    private final List<OperationalAssistantBlockContributor> blockContributors;

    public OperationalAssistantBlockComposer(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    public OperationalAssistantBlockComposer(
            ObjectMapper objectMapper,
            List<OperationalAssistantBlockContributor> blockContributors) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.blockContributors = blockContributors == null
                ? List.of()
                : blockContributors.stream()
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingInt(OperationalAssistantBlockContributor::getOrder))
                        .toList();
    }

    public ComposedAssistantOutput compose(String assistantNarrative, AgentTurnArtifactStore artifacts) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", CONTENT_VERSION);
        ArrayNode blocks = root.putArray("blocks");
        List<Map<String, Object>> quickReplies = new ArrayList<>();

        String safeNarrative = maskMod11TaxIds(assistantNarrative);
        if (StringUtils.hasText(safeNarrative)) {
            ObjectNode paragraph = blocks.addObject();
            paragraph.put("type", "paragraph");
            paragraph.put("text", safeNarrative.trim());
        }

        List<AgentEntityDossier> dossiers = artifacts != null ? artifacts.dossiers() : List.of();
        List<AgentEntityMatch> matches = artifacts != null ? artifacts.lastSearchMatches() : List.of();
        List<AgentQueryResult> queries = artifacts != null ? artifacts.queryResults() : List.of();
        List<AgentStatsResult> statsResults = artifacts != null ? artifacts.statsResults() : List.of();
        List<AgentEvaluatedCalculationMemory> calculationMemories =
                artifacts != null ? artifacts.calculationMemories() : List.of();
        List<AgentKnowledgeDocument> knowledgeCitations =
                artifacts != null ? artifacts.knowledgeCitations() : List.of();
        List<AgentSimulationPreview> simulationPreviews =
                artifacts != null ? artifacts.simulationPreviews() : List.of();
        List<AgentPersistedActionProposal> actionProposals =
                artifacts != null ? artifacts.actionProposals() : List.of();

        Set<String> emittedCalculationMemoryKeys = new LinkedHashSet<>();

        if (!dossiers.isEmpty()) {
            List<AgentEntityDossier> visibleDossiers = filterSupersededDossiers(dossiers);
            appendConsolidatedDossiers(blocks, visibleDossiers, emittedCalculationMemoryKeys);
        } else if (matches.size() > 1) {
            appendDisambiguationBlock(blocks, quickReplies, matches);
        } else if (!queries.isEmpty()) {
            List<AgentQueryResult> nonEmptyQueries = queries.stream()
                    .filter(q -> q.totalElements() > 0 || (q.uiRecords() != null && !q.uiRecords().isEmpty()))
                    .toList();
            if (!nonEmptyQueries.isEmpty()) {
                for (AgentQueryResult query : nonEmptyQueries) {
                    appendQueryResultBlock(blocks, query, true);
                }
            } else {
                appendQueryResultBlock(blocks, queries.get(0), false);
            }
        } else if (matches.size() == 1) {
            appendDisambiguationBlock(blocks, quickReplies, matches);
        }

        for (AgentSimulationPreview preview : simulationPreviews) {
            if (preview != null) {
                appendSimulationPreviewBlock(blocks, preview);
            }
        }
        if (!actionProposals.isEmpty()) {
            appendActionProposalsBlock(blocks, actionProposals);
        }
        for (AgentEvaluatedCalculationMemory calc : calculationMemories) {
            if (calc != null && !calc.uiTrails().isEmpty() && emittedCalculationMemoryKeys.add(calculationMemoryKey(calc))) {
                appendCalculationMemoryBlock(blocks, calc);
            }
        }
        for (AgentStatsResult stats : statsResults) {
            if (stats != null && !stats.buckets().isEmpty()) {
                appendChartWidgetBlock(blocks, stats);
            }
        }
        if (artifacts != null && !blockContributors.isEmpty()) {
            for (OperationalAssistantBlockContributor contributor : blockContributors) {
                List<ObjectNode> contributed = contributor.contribute(artifacts, objectMapper);
                if (contributed != null) {
                    for (ObjectNode contributedNode : contributed) {
                        if (contributedNode != null) {
                            blocks.add(contributedNode);
                        }
                    }
                }
            }
        }
        List<AgentActionAffordance> handoffActions = artifacts != null ? artifacts.handoffActions() : List.of();
        if (!handoffActions.isEmpty()) {
            appendActionBar(blocks, "Criar ou editar página customizada", handoffActions);
        }
        if (!knowledgeCitations.isEmpty()) {
            appendKnowledgeCitationsBlock(blocks, knowledgeCitations);
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

    private void appendConsolidatedDossiers(
            ArrayNode blocks,
            List<AgentEntityDossier> dossiers,
            Set<String> emittedCalculationMemoryKeys) {
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
                putIfText(item, "title", maskMod11TaxIds(alert.title()));
                putIfText(item, "description", maskMod11TaxIds(alert.description()));
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
            if (dossier.calculationMemory() != null && !dossier.calculationMemory().uiTrails().isEmpty()) {
                appendCalculationMemoryBlock(blocks, dossier.calculationMemory());
                emittedCalculationMemoryKeys.add(calculationMemoryKey(dossier.calculationMemory()));
            }
            for (AgentRelatedDataset dataset : dossier.relatedDatasets()) {
                if (dataset.totalCount() <= 0 && (dataset.uiRows() == null || dataset.uiRows().isEmpty())) {
                    continue;
                }
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
                appendRowsAndColumns(collection, dataset.uiRows(), dataset.fieldDescriptors());
            }
            for (AgentActionAffordance action : dossier.actions()) {
                String actionKey = (nullToEmpty(action.actionId()) + "::" + nullToEmpty(action.entityId()))
                        .toLowerCase(Locale.ROOT);
                uniqueActions.putIfAbsent(actionKey, action);
            }
        }

        if (!uniqueActions.isEmpty()) {
            appendActionBar(blocks, "Ações disponíveis", List.copyOf(uniqueActions.values()));
        }
    }

    private void appendDossierWidgetBlock(ArrayNode blocks, AgentEntityDossier dossier) {
        ObjectNode widgetBlock = blocks.addObject();
        widgetBlock.put("type", "widget-block");
        widgetBlock.put("widgetType", "praxis-rich-content");
        String safeTitle = maskMod11TaxIds(firstNonBlank(dossier.displayTitle(), dossier.entity().title()));
        String safeSubtitle = maskMod11TaxIds(dossier.displaySubtitle());
        widgetBlock.put("title", safeTitle);
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
        putIfText(recordNode, "title", maskMod11TaxIds(firstNonBlank(dossier.displayTitle(), dossier.entityId())));
        putIfText(recordNode, "subtitle", safeSubtitle);
        putIfText(recordNode, "meta", dossier.entity().title());
        ArrayNode fields = recordNode.putArray("fields");

        Map<String, Object> safeRecord = buildSafeUiRecord(dossier);
        Map<String, AgentFieldDescriptor> descriptorMap = indexDescriptors(dossier.fieldDescriptors());
        if (descriptorMap.isEmpty()) {
            int count = 0;
            for (Map.Entry<String, Object> entry : safeRecord.entrySet()) {
                if (entry.getValue() == null || !StringUtils.hasText(entry.getValue().toString())) {
                    continue;
                }
                if (count >= MAX_FALLBACK_OVERVIEW_FIELDS) {
                    break;
                }
                ObjectNode fieldNode = fields.addObject();
                fieldNode.put("id", entry.getKey());
                fieldNode.put("label", humanizeFieldKey(entry.getKey()));
                fieldNode.put("value", maskMod11TaxIds(String.valueOf(entry.getValue())));
                count++;
            }
        } else {
            List<String> selectedKeys = selectCuratedDossierFieldKeys(
                    dossier.entity(), safeRecord, descriptorMap);
            for (String key : selectedKeys) {
                Object rawValue = safeRecord.get(key);
                if (rawValue == null || !StringUtils.hasText(rawValue.toString())) {
                    continue;
                }
                AgentFieldDescriptor desc = descriptorMap.get(key);
                ObjectNode fieldNode = fields.addObject();
                fieldNode.put("id", key);
                fieldNode.put("label", resolveFieldLabel(key, desc));
                fieldNode.put("value", formatFieldValue(rawValue, desc));
            }
        }

        ObjectNode widget = widgetBlock.putObject("widget");
        widget.put("id", "praxis-rich-content");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("layout", "block");
        inputs.set("document", doc.deepCopy());
    }

    private static Map<String, Object> buildSafeUiRecord(AgentEntityDossier dossier) {
        Map<String, Object> rawUi = dossier.uiRecord();
        if (rawUi == null || rawUi.isEmpty()) {
            return Map.of();
        }
        Set<String> maskedFields = new LinkedHashSet<>();
        if (dossier.maskedForAiFields() != null) {
            maskedFields.addAll(dossier.maskedForAiFields());
        }
        Map<String, Object> modelSafe = dossier.modelSafeRecord();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawUi.entrySet()) {
            String key = entry.getKey();
            if (maskedFields.contains(key)) {
                if (modelSafe != null && modelSafe.containsKey(key)) {
                    safe.put(key, modelSafe.get(key));
                }
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String str) {
                safe.put(key, maskMod11TaxIds(str));
            } else {
                safe.put(key, value);
            }
        }
        return safe;
    }

    private static List<String> selectCuratedDossierFieldKeys(
            AgentEntityDescriptor descriptor,
            Map<String, Object> safeRecord,
            Map<String, AgentFieldDescriptor> descriptorMap) {
        Set<String> headerExcluded = new LinkedHashSet<>();
        String idField = descriptor != null ? descriptor.idFieldName() : null;
        String titleField = descriptor != null ? descriptor.titleField() : null;
        String subtitleField = descriptor != null ? descriptor.subtitleField() : null;
        if (StringUtils.hasText(idField)) {
            headerExcluded.add(idField);
        }
        if (StringUtils.hasText(titleField)) {
            headerExcluded.add(titleField);
        }
        if (StringUtils.hasText(subtitleField)) {
            headerExcluded.add(subtitleField);
        }

        List<String> chosen = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Tier 1: @ResourceIdentity metadataFields
        if (descriptor != null && descriptor.metadataFields() != null) {
            for (String metaKey : descriptor.metadataFields()) {
                addCuratedKeyIfEligible(metaKey, safeRecord, descriptorMap, headerExcluded, chosen, seen, true);
            }
        }

        // Tier 2: @UISchema(quickViewVisible = true) sorted by quickViewOrder
        descriptorMap.values().stream()
                .filter(AgentFieldDescriptor::quickViewVisible)
                .sorted(Comparator
                        .comparingInt(AgentFieldDescriptor::quickViewOrder)
                        .thenComparingInt(d -> d.order() > 0 ? d.order() : Integer.MAX_VALUE))
                .forEach(d -> addCuratedKeyIfEligible(d.name(), safeRecord, descriptorMap, headerExcluded, chosen, seen, true));

        // Tier 3: visible table columns (!hidden && !tableHidden) sorted by order
        descriptorMap.values().stream()
                .filter(d -> !d.hidden() && !d.tableHidden())
                .sorted(Comparator.comparingInt(d -> d.order() > 0 ? d.order() : Integer.MAX_VALUE))
                .forEach(d -> addCuratedKeyIfEligible(d.name(), safeRecord, descriptorMap, headerExcluded, chosen, seen, true));

        // Fallback if very small DTO (< 2 body fields curated so far): include subtitle/tableHidden/title non-hidden fields
        if (chosen.size() < 2) {
            if (StringUtils.hasText(subtitleField)) {
                addCuratedKeyIfEligible(subtitleField, safeRecord, descriptorMap, Set.of(), chosen, seen, false);
            }
            for (String key : safeRecord.keySet()) {
                if (chosen.size() >= 4) {
                    break;
                }
                if (StringUtils.hasText(idField) && idField.equals(key)) {
                    continue;
                }
                addCuratedKeyIfEligible(key, safeRecord, descriptorMap, Set.of(), chosen, seen, false);
            }
        }

        return chosen.size() > MAX_CURATED_OVERVIEW_FIELDS
                ? chosen.subList(0, MAX_CURATED_OVERVIEW_FIELDS)
                : chosen;
    }

    private static void addCuratedKeyIfEligible(
            String key,
            Map<String, Object> safeRecord,
            Map<String, AgentFieldDescriptor> descriptorMap,
            Set<String> excluded,
            List<String> chosen,
            Set<String> seen,
            boolean checkHeaderExclusions) {
        if (!StringUtils.hasText(key) || seen.contains(key) || chosen.size() >= MAX_CURATED_OVERVIEW_FIELDS) {
            return;
        }
        if (checkHeaderExclusions && excluded.contains(key)) {
            return;
        }
        AgentFieldDescriptor desc = descriptorMap.get(key);
        if (desc != null && desc.hidden()) {
            return;
        }
        Object val = safeRecord.get(key);
        if (val == null || !StringUtils.hasText(val.toString())) {
            return;
        }
        seen.add(key);
        chosen.add(key);
    }

    private static Map<String, AgentFieldDescriptor> indexDescriptors(List<AgentFieldDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentFieldDescriptor> map = new LinkedHashMap<>();
        for (AgentFieldDescriptor desc : descriptors) {
            if (desc != null && StringUtils.hasText(desc.name())) {
                map.putIfAbsent(desc.name(), desc);
            }
        }
        return map;
    }

    private static String resolveFieldLabel(String key, AgentFieldDescriptor desc) {
        if (desc != null && StringUtils.hasText(desc.label())) {
            return desc.label().trim();
        }
        return humanizeFieldKey(key);
    }

    private static String formatFieldValue(Object rawValue, AgentFieldDescriptor desc) {
        if (rawValue == null) {
            return "";
        }
        if (rawValue instanceof Boolean boolVal) {
            if (desc != null) {
                return boolVal
                        ? firstNonBlank(desc.labelTrue(), "Sim")
                        : firstNonBlank(desc.labelFalse(), "Não");
            }
            return boolVal ? "Sim" : "Não";
        }
        String strVal = String.valueOf(rawValue).trim();
        if (desc != null && desc.optionLabels() != null && !desc.optionLabels().isEmpty()) {
            String optionLabel = desc.optionLabels().get(strVal);
            if (StringUtils.hasText(optionLabel)) {
                return maskMod11TaxIds(optionLabel.trim());
            }
        }
        return maskMod11TaxIds(strVal);
    }

    public static String maskMod11TaxIds(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        List<TaxIdMatch> matches = Mod11IdentifierHintExtractor.extractValidTaxIds(text);
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        for (TaxIdMatch match : matches) {
            sb.append(text, cursor, match.start());
            String digits = match.digits();
            if (match.cpf() && digits.length() == 11) {
                sb.append("***.").append(digits, 3, 6).append('.').append(digits, 6, 9).append("-**");
            } else if (!match.cpf() && digits.length() == 14) {
                sb.append("**.").append(digits, 2, 5).append('.').append(digits, 5, 8).append("/****-**");
            } else {
                sb.append("***");
            }
            cursor = match.end();
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    public static Map<String, Object> maskMod11InStructure(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() != null) {
                masked.put(entry.getKey(), maskMod11Value(entry.getValue()));
            }
        }
        return masked;
    }

    private static Object maskMod11Value(Object value) {
        if (value instanceof String str) {
            return maskMod11TaxIds(str);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), maskMod11Value(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof java.util.Collection<?> collection) {
            java.util.Collection<Object> out = value instanceof java.util.Set<?>
                    ? new java.util.LinkedHashSet<>(collection.size())
                    : new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(maskMod11Value(item));
            }
            return out;
        }
        if (value instanceof String[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = maskMod11TaxIds(arr[i]);
            }
            return out;
        }
        if (value instanceof Object[] arr) {
            Object[] out = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = maskMod11Value(arr[i]);
            }
            return out;
        }
        return value;
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
        collection.put("subtitle", "Selecione o registro exato para abrir o dossiê 360°");
        collection.put("totalCount", matches.size());

        ArrayNode columns = collection.putArray("columns");
        addColumn(columns, "entityTitle", "Tipo");
        addColumn(columns, "title", "Identificação");
        addColumn(columns, "subtitle", "Detalhe");
        addColumn(columns, "entityId", "Código");

        ArrayNode rows = collection.putArray("rows");
        for (AgentEntityMatch match : matches) {
            String safeMatchTitle = maskMod11TaxIds(match.title());
            String safeMatchSubtitle = maskMod11TaxIds(match.subtitle());
            ObjectNode row = rows.addObject();
            putIfText(row, "entityKey", match.entityKey());
            putIfText(row, "resourceKey", match.resourceKey());
            putIfText(row, "resourcePath", match.resourcePath());
            putIfText(row, "entityTitle", match.entityTitle());
            putIfText(row, "entityId", match.entityId());
            putIfText(row, "title", safeMatchTitle);
            putIfText(row, "subtitle", safeMatchSubtitle);
            putIfText(row, "matchedBy", match.matchedBy());
            row.put("fromActiveScreenScope", match.fromActiveScreenScope());

            Map<String, Object> quickReply = new LinkedHashMap<>();
            quickReply.put("id", "candidate-" + safeSlug(match.entityKey()) + "-" + safeSlug(match.entityId()));
            quickReply.put("label", safeMatchTitle + " (" + match.entityTitle() + ")");
            quickReply.put("prompt", "Abrir visão 360° de " + safeMatchTitle
                    + " em " + match.entityTitle() + " (ID " + match.entityId() + ")");
            quickReply.put("value", match.entityKey() + ":" + match.entityId());
            quickReply.put("kind", "entity-disambiguation");
            quickReply.put("description", safeMatchSubtitle);
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

    private void appendQueryResultBlock(ArrayNode blocks, AgentQueryResult query, boolean includeActions) {
        ObjectNode collection = blocks.addObject();
        collection.put("type", "compact-collection");
        collection.put("variant", "records");
        collection.put("title", firstNonBlank(query.title(), query.entityKey()));
        putIfText(collection, "entityKey", query.entityKey());
        putIfText(collection, "resourceKey", query.resourceKey());
        putIfText(collection, "resourcePath", query.resourcePath());
        collection.put("totalCount", query.totalElements());
        appendRowsAndColumns(collection, query.uiRecords(), query.fieldDescriptors());

        if (includeActions && query.collectionActions() != null && !query.collectionActions().isEmpty()) {
            appendActionBar(blocks, "Ações do recurso", query.collectionActions());
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

    private void appendRowsAndColumns(
            ObjectNode target,
            List<Map<String, Object>> uiRows,
            List<AgentFieldDescriptor> descriptors) {
        ArrayNode columns = target.putArray("columns");
        ArrayNode rows = target.putArray("rows");
        if (uiRows == null || uiRows.isEmpty()) {
            return;
        }
        Map<String, AgentFieldDescriptor> descriptorMap = indexDescriptors(descriptors);
        Set<String> availableKeys = uiRows.get(0).keySet();
        List<String> keys = new ArrayList<>();
        if (!descriptorMap.isEmpty()) {
            descriptorMap.values().stream()
                    .filter(d -> !d.hidden() && !d.tableHidden() && availableKeys.contains(d.name()))
                    .sorted(Comparator.comparingInt(d -> d.order() > 0 ? d.order() : Integer.MAX_VALUE))
                    .limit(MAX_RELATED_COLUMNS)
                    .forEach(d -> {
                        keys.add(d.name());
                        addColumn(columns, d.name(), resolveFieldLabel(d.name(), d));
                    });
            if (keys.isEmpty()) {
                for (String key : availableKeys) {
                    if (keys.size() >= MAX_RELATED_COLUMNS) {
                        break;
                    }
                    AgentFieldDescriptor desc = descriptorMap.get(key);
                    if (desc != null && desc.hidden()) {
                        continue;
                    }
                    keys.add(key);
                    addColumn(columns, key, resolveFieldLabel(key, desc));
                }
            }
        } else {
            for (String key : availableKeys) {
                if (keys.size() >= MAX_RELATED_COLUMNS) {
                    break;
                }
                keys.add(key);
                addColumn(columns, key, humanizeFieldKey(key));
            }
        }
        for (Map<String, Object> rowMap : uiRows) {
            ObjectNode rowNode = rows.addObject();
            for (String key : keys) {
                Object val = rowMap.get(key);
                if (val == null) {
                    continue;
                }
                AgentFieldDescriptor desc = descriptorMap.get(key);
                if (val instanceof Boolean || (desc != null && !desc.optionLabels().isEmpty())) {
                    rowNode.put(key, formatFieldValue(val, desc));
                } else if (val instanceof String str) {
                    rowNode.put(key, maskMod11TaxIds(str));
                } else {
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

    private static String calculationMemoryKey(AgentEvaluatedCalculationMemory mem) {
        return (nullToEmpty(mem.entityKey()) + "::" + nullToEmpty(mem.entityId())).toLowerCase(Locale.ROOT);
    }

    private void appendCalculationMemoryBlock(ArrayNode blocks, AgentEvaluatedCalculationMemory mem) {
        ObjectNode node = blocks.addObject();
        node.put("type", "calculation-memory");
        putIfText(node, "title", firstNonBlank(mem.title(), "Memória de cálculo"));
        putIfText(node, "subtitle", mem.subtitle());
        putIfText(node, "icon", firstNonBlank(mem.icon(), "calculate"));
        putIfText(node, "legalBasis", mem.legalBasis());
        putIfText(node, "emptyText", mem.emptyText());
        putIfText(node, "recordTitle", maskMod11TaxIds(mem.recordTitle()));
        putIfText(node, "entityKey", mem.entityKey());
        putIfText(node, "resourceKey", mem.resourceKey());
        putIfText(node, "resourcePath", mem.resourcePath());
        putIfText(node, "entityId", mem.entityId());

        ArrayNode trailsNode = node.putArray("trails");
        for (EvaluatedTrail trail : mem.uiTrails()) {
            ObjectNode t = trailsNode.addObject();
            putIfText(t, "id", trail.id());
            putIfText(t, "title", maskMod11TaxIds(trail.title()));
            putIfText(t, "ruleCode", trail.ruleCode());
            putIfText(t, "formula", trail.formula());
            putIfText(t, "legalBasis", trail.legalBasis());
            putIfText(t, "legalPurpose", trail.legalPurpose());
            putIfText(t, "resultLabel", trail.resultLabel());
            putIfText(t, "resultFormatted", maskMod11TaxIds(trail.resultFormatted()));
            putIfText(t, "resultTone", firstNonBlank(trail.resultTone(), "primary"));
            putIfText(t, "resultHint", maskMod11TaxIds(trail.resultHint()));
            ArrayNode stepsNode = t.putArray("steps");
            for (EvaluatedStep step : trail.steps()) {
                ObjectNode s = stepsNode.addObject();
                putIfText(s, "role", step.role());
                putIfText(s, "label", step.label());
                putIfText(s, "operator", step.operator());
                putIfText(s, "formattedValue", maskMod11TaxIds(step.formattedValue()));
                putIfText(s, "format", step.format());
                putIfText(s, "hint", maskMod11TaxIds(step.hint()));
                putIfText(s, "legalBasis", step.legalBasis());
            }
        }
    }

    private void appendChartWidgetBlock(ArrayNode blocks, AgentStatsResult stats) {
        String chartKind = normalizeChartKind(stats.chartKind());
        boolean isPieOrDonut = "pie".equals(chartKind) || "donut".equals(chartKind);
        boolean isHorizontal = "horizontal-bar".equals(chartKind);
        String configType = isHorizontal ? "bar" : chartKind;
        String chartId = "agent-stats-" + safeSlug(stats.entityKey()) + "-" + safeSlug(stats.field());
        String fieldLabel = firstNonBlank(stats.fieldLabel(), stats.field());
        String metricLabel = resolveMetricLabel(stats.metricOperation(), stats.metricField());

        ObjectNode widgetBlock = blocks.addObject();
        widgetBlock.put("type", "widget-block");
        widgetBlock.put("widgetType", "praxis-chart");
        putIfText(widgetBlock, "title", firstNonBlank(stats.title(), fieldLabel));
        putIfText(widgetBlock, "subtitle", stats.subtitle());
        putIfText(widgetBlock, "entityKey", stats.entityKey());
        putIfText(widgetBlock, "resourceKey", stats.resourceKey());
        putIfText(widgetBlock, "resourcePath", stats.resourcePath());
        appendAnalyticalProvenance(widgetBlock, stats.analyticalProvenance());

        ObjectNode widget = widgetBlock.putObject("widget");
        widget.put("id", "praxis-chart");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("enableCustomization", false);

        ObjectNode chartDocument = inputs.putObject("chartDocument");
        chartDocument.put("version", "0.1.0");
        chartDocument.put("kind", chartKind);
        chartDocument.put("chartId", chartId);
        ObjectNode docSizing = chartDocument.putObject("sizing");
        docSizing.put("mode", "fixed");
        docSizing.put("height", 220);
        ObjectNode docSource = chartDocument.putObject("source");
        docSource.put("kind", "derived");
        ArrayNode dimensions = chartDocument.putArray("dimensions");
        ObjectNode dim = dimensions.addObject();
        dim.put("field", "label");
        dim.put("role", "category");
        ArrayNode metrics = chartDocument.putArray("metrics");
        ObjectNode metricNode = metrics.addObject();
        metricNode.put("field", "value");
        metricNode.put("aggregation", "sum");
        metricNode.put("label", metricLabel);
        ObjectNode docLegend = chartDocument.putObject("legend");
        docLegend.put("enabled", isPieOrDonut);
        ObjectNode docTooltip = chartDocument.putObject("tooltip");
        docTooltip.put("enabled", true);
        ObjectNode docTheme = chartDocument.putObject("theme");
        ObjectNode docSurface = docTheme.putObject("surface");
        docSurface.put("mode", "embedded");

        ObjectNode config = inputs.putObject("config");
        config.put("id", chartId);
        config.put("type", configType);
        config.put("orientation", isHorizontal ? "horizontal" : "vertical");
        ObjectNode cfgSizing = config.putObject("sizing");
        cfgSizing.put("mode", "fixed");
        cfgSizing.put("height", 220);
        ObjectNode axes = config.putObject("axes");
        ObjectNode xAxis = axes.putObject("x");
        xAxis.put("field", "label");
        putIfText(xAxis, "label", fieldLabel);
        xAxis.put("type", "category");
        ObjectNode yAxis = axes.putObject("y");
        yAxis.put("field", "value");
        yAxis.put("label", metricLabel);
        yAxis.put("type", "value");
        ArrayNode series = config.putArray("series");
        ObjectNode sNode = series.addObject();
        sNode.put("id", "value");
        sNode.put("name", metricLabel);
        ObjectNode sMetric = sNode.putObject("metric");
        sMetric.put("field", "value");
        sMetric.put("aggregation", "sum");
        ObjectNode cfgTheme = config.putObject("theme");
        ObjectNode cfgSurface = cfgTheme.putObject("surface");
        cfgSurface.put("mode", "embedded");
        ObjectNode cfgLegend = cfgTheme.putObject("legend");
        cfgLegend.put("visible", isPieOrDonut);
        ObjectNode cfgTooltip = cfgTheme.putObject("tooltip");
        cfgTooltip.put("enabled", true);

        ArrayNode data = inputs.putArray("data");
        for (AgentStatsBucket bucket : stats.buckets()) {
            ObjectNode row = data.addObject();
            putIfText(row, "key", bucket.key());
            putIfText(row, "label", maskMod11TaxIds(firstNonBlank(bucket.label(), bucket.key())));
            putIfText(row, "category", maskMod11TaxIds(firstNonBlank(bucket.label(), bucket.key())));
            if (bucket.value() != null) {
                row.put("value", bucket.value());
            } else {
                row.put("value", bucket.count());
            }
            row.put("count", bucket.count());
        }
    }

    private void appendKnowledgeCitationsBlock(ArrayNode blocks, List<AgentKnowledgeDocument> citations) {
        List<AgentKnowledgeDocument> ordered = citations.stream()
                .sorted(Comparator.comparingDouble(AgentKnowledgeDocument::score).reversed()
                        .thenComparing(AgentKnowledgeDocument::title, Comparator.nullsLast(String::compareToIgnoreCase)))
                .limit(MAX_KNOWLEDGE_CITATIONS)
                .toList();
        ObjectNode node = blocks.addObject();
        node.put("type", "knowledge-citations");
        node.put("title", "Base normativa e regras aplicáveis");
        ArrayNode items = node.putArray("items");
        for (AgentKnowledgeDocument doc : ordered) {
            ObjectNode item = items.addObject();
            putIfText(item, "docId", doc.docId());
            putIfText(item, "sourceKind", doc.sourceKind());
            putIfText(item, "title", maskMod11TaxIds(doc.title()));
            putIfText(item, "normRef", doc.normRef());
            putIfText(item, "scopeLabel", doc.scopeLabel());
            putIfText(item, "excerpt", maskMod11TaxIds(doc.excerpt()));
            putIfText(item, "targetRoute", doc.targetRoute());
            ArrayNode tagsNode = item.putArray("tags");
            for (String tag : doc.tags()) {
                if (StringUtils.hasText(tag)) {
                    tagsNode.add(tag.trim());
                }
            }
        }
    }

    private void appendSimulationPreviewBlock(ArrayNode blocks, AgentSimulationPreview preview) {
        ObjectNode node = blocks.addObject();
        node.put("type", "simulation-preview");
        putIfText(node, "title", firstNonBlank(maskMod11TaxIds(preview.title()), "Simulação prévia"));
        node.put("subtitle", "Simulação não persistida");
        putIfText(node, "description", maskMod11TaxIds(preview.description()));
        putIfText(node, "entityKey", preview.entityKey());
        putIfText(node, "resourceKey", preview.resourceKey());
        putIfText(node, "resourcePath", preview.resourcePath());
        putIfText(node, "determinationId", preview.determinationId());
        node.put("nonPersistent", true);

        Map<String, AgentFieldDescriptor> descriptorMap = indexDescriptors(preview.previewFields());
        ArrayNode fieldsNode = node.putArray("fields");
        Map<String, Object> safeUiPreview = preview.uiPreview() != null ? preview.uiPreview() : Map.of();
        for (Map.Entry<String, Object> entry : safeUiPreview.entrySet()) {
            if (entry.getValue() == null || !StringUtils.hasText(entry.getValue().toString())) {
                continue;
            }
            AgentFieldDescriptor desc = descriptorMap.get(entry.getKey());
            if (desc != null && desc.hidden()) {
                continue;
            }
            ObjectNode fieldNode = fieldsNode.addObject();
            fieldNode.put("id", entry.getKey());
            fieldNode.put("label", resolveFieldLabel(entry.getKey(), desc));
            fieldNode.put("value", formatFieldValue(entry.getValue(), desc));
        }

        if (preview.validationIssues() != null && !preview.validationIssues().isEmpty()) {
            ArrayNode issuesNode = node.putArray("validationIssues");
            for (Map<String, String> issue : preview.validationIssues()) {
                ObjectNode issueNode = issuesNode.addObject();
                putIfText(issueNode, "field", issue.get("field"));
                putIfText(issueNode, "issueNote", maskMod11TaxIds(issue.get("issueNote")));
            }
        }
    }

    private void appendActionProposalsBlock(ArrayNode blocks, List<AgentPersistedActionProposal> proposals) {
        ObjectNode node = blocks.addObject();
        node.put("type", "action-proposals");
        node.put("title", "Propostas governadas aguardando confirmação");
        ArrayNode items = node.putArray("items");
        for (AgentPersistedActionProposal proposal : proposals) {
            if (proposal == null || proposal.prepared() == null) {
                continue;
            }
            AgentPreparedAction prep = proposal.prepared();
            ObjectNode item = items.addObject();
            putIfText(item, "proposalId", proposal.proposalId() != null ? proposal.proposalId().toString() : null);
            putIfText(item, "proposalStatus", proposal.proposalStatus());
            if (proposal.expiresAt() != null) {
                item.put("expiresAt", proposal.expiresAt().toString());
            }
            putIfText(item, "actionId", prep.actionId());
            putIfText(item, "label", maskMod11TaxIds(prep.actionTitle()));
            putIfText(item, "description", maskMod11TaxIds(prep.actionDescription()));
            putIfText(item, "entityKey", prep.entityKey());
            putIfText(item, "resourceKey", prep.resourceKey());
            putIfText(item, "resourcePath", prep.resourcePath());
            putIfText(item, "entityId", prep.entityId());
            putIfText(item, "recordTitle", maskMod11TaxIds(prep.recordTitle()));
            putIfText(item, "recordSubtitle", maskMod11TaxIds(prep.recordSubtitle()));
            putIfText(item, "operationKind", prep.operationKind());
            putIfText(item, "formMode", prep.formMode());
            putIfText(item, "method", prep.httpMethod());
            putIfText(item, "submitUrl", prep.submitUrl());
            putIfText(item, "schemaUrl", prep.schemaUrl());
            putIfText(item, "riskLevel", prep.riskLevel());
            item.put("confirmationRequired", prep.confirmationRequired());
            item.put("hasInputForm", prep.hasInputForm());
            putIfText(item, "organizationId", prep.organizationId());
            putIfText(item, "organizationLabel", prep.organizationLabel());
            putIfText(item, "recordLifecycleState", prep.recordLifecycleState());
            putIfText(item, "expectedVersionTag", prep.expectedVersionTag());
            item.put("allowed", prep.allowed());
            putIfText(item, "blockedCode", prep.blockedCode());
            putIfText(item, "impedimentNote", maskMod11TaxIds(prep.impedimentNote()));

            if (prep.prefill() != null && !prep.prefill().isEmpty()) {
                ObjectNode prefillNode = item.putObject("prefill");
                for (Map.Entry<String, Object> entry : prep.prefill().entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    prefillNode.set(entry.getKey(), objectMapper.valueToTree(maskMod11Value(entry.getValue())));
                }
            }
            if (prep.missingRequiredFields() != null && !prep.missingRequiredFields().isEmpty()) {
                ArrayNode missingNode = item.putArray("missingRequiredFields");
                for (String missing : prep.missingRequiredFields()) {
                    missingNode.add(missing);
                }
            }
            if (prep.inputFields() != null && !prep.inputFields().isEmpty()) {
                ArrayNode fieldsNode = item.putArray("inputFields");
                for (AgentActionInputField f : prep.inputFields()) {
                    ObjectNode fn = fieldsNode.addObject();
                    putIfText(fn, "name", f.name());
                    putIfText(fn, "label", f.label());
                    putIfText(fn, "controlType", f.controlType());
                    fn.put("required", f.required());
                }
            }
            if (proposal.bulk() != null) {
                appendBulkProposalNode(item, proposal.bulk());
            }
        }
    }

    private void appendBulkProposalNode(ObjectNode item, AgentPreparedBulkOperation bulk) {
        // NOTE: Only emit non-reserved SSE keys (never summary, content, message, details, reason, *token).
        ObjectNode bulkNode = item.putObject("bulk");
        putIfText(bulkNode, "domainProposalId", bulk.domainProposalId());
        putIfText(bulkNode, "bulkOperationId", bulk.bulkOperationId());
        putIfText(bulkNode, "bulkMode", bulk.bulkMode());
        putIfText(bulkNode, "atomicity", bulk.atomicity());
        putIfText(bulkNode, "selectionMode", bulk.selectionMode());
        putIfText(bulkNode, "domainProposalStatus", bulk.domainProposalStatus());
        ObjectNode totalsNode = bulkNode.putObject("totals");
        totalsNode.put("targetCount", bulk.targetCount());
        totalsNode.put("evaluated", bulk.evaluatedCount());
        totalsNode.put("executable", bulk.executableCount());
        totalsNode.put("blocked", bulk.blockedCount());
        putIfText(bulkNode, "filterLabel", maskMod11TaxIds(bulk.filterLabel()));
        if (bulk.base() != null) {
            putIfText(bulkNode, "statusNote", maskMod11TaxIds(bulk.base().impedimentNote()));
        }
        if (bulk.blockedDiagnostics() != null && !bulk.blockedDiagnostics().isEmpty()
                && bulk.blockedDiagnostics().get(0).get("diagnostics") instanceof List<?> firstDiags
                && !firstDiags.isEmpty()
                && firstDiags.get(0) instanceof Map<?, ?> firstDiagMap
                && firstDiagMap.get("issueNote") != null) {
            putIfText(bulkNode, "diagnosticText", maskMod11TaxIds(firstDiagMap.get("issueNote").toString()));
        }
        if (bulk.selectionPayload() != null && !bulk.selectionPayload().isEmpty()) {
            bulkNode.set("selectionPayload", objectMapper.valueToTree(bulk.selectionPayload()));
        }
        if (bulk.changes() != null && !bulk.changes().isEmpty()) {
            bulkNode.set("changes", objectMapper.valueToTree(bulk.changes()));
        }
        if (bulk.itemChanges() != null && !bulk.itemChanges().isEmpty()) {
            bulkNode.set("itemChanges", objectMapper.valueToTree(bulk.itemChanges()));
        }
        if (bulk.uiPreviewItems() != null && !bulk.uiPreviewItems().isEmpty()) {
            ArrayNode samples = bulkNode.putArray("sampleTargets");
            int limit = Math.min(8, bulk.uiPreviewItems().size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> src = bulk.uiPreviewItems().get(i);
                ObjectNode sNode = samples.addObject();
                if (src.get("id") != null) {
                    sNode.set("id", objectMapper.valueToTree(src.get("id")));
                }
                if (src.get("title") != null) {
                    putIfText(sNode, "title", maskMod11TaxIds(src.get("title").toString()));
                }
                if (src.get("subtitle") != null) {
                    putIfText(sNode, "subtitle", maskMod11TaxIds(src.get("subtitle").toString()));
                }
                if (src.get("decision") != null) {
                    putIfText(sNode, "decision", src.get("decision").toString());
                }
                if (src.get("diagnostics") != null) {
                    sNode.set("diagnostics", objectMapper.valueToTree(maskMod11Value(src.get("diagnostics"))));
                }
            }
        }
        if (bulk.blockedDiagnostics() != null && !bulk.blockedDiagnostics().isEmpty()) {
            bulkNode.set("blockedItems", objectMapper.valueToTree(maskMod11Value(bulk.blockedDiagnostics())));
        }
        if (bulk.bulkContract() != null && !bulk.bulkContract().isEmpty()) {
            bulkNode.set("bulkContract", objectMapper.valueToTree(bulk.bulkContract()));
        }
    }

    private static String normalizeChartKind(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "bar";
        }
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        if ("horizontalbar".equals(norm) || "horizontal_bar".equals(norm)) {
            return "horizontal-bar";
        }
        if ("horizontal-bar".equals(norm) || "bar".equals(norm) || "line".equals(norm)
                || "pie".equals(norm) || "donut".equals(norm)) {
            return norm;
        }
        return "bar";
    }

    private static String resolveMetricLabel(String metricOp, String metricField) {
        String op = StringUtils.hasText(metricOp) ? metricOp.trim().toUpperCase(Locale.ROOT) : "COUNT";
        String base = switch (op) {
            case "SUM" -> "Soma";
            case "AVG" -> "Média";
            case "MIN" -> "Mínimo";
            case "MAX" -> "Máximo";
            case "DISTINCT_COUNT" -> "Contagem distinta";
            default -> "Quantidade";
        };
        return StringUtils.hasText(metricField) ? base + " (" + metricField + ")" : base;
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

    private void appendAnalyticalProvenance(ObjectNode widgetBlock, AgentAnalyticalProvenance provenance) {
        if (widgetBlock == null || provenance == null) {
            return;
        }
        ObjectNode provNode = widgetBlock.putObject("analyticalProvenance");
        putIfText(provNode, "mode", provenance.mode());
        putIfText(provNode, "recipeId", provenance.recipeId());
        putIfText(provNode, "recipeTitle", maskPiiText(provenance.recipeTitle()));
        putIfText(provNode, "recipeVersion", provenance.recipeVersion());
        putIfText(provNode, "governanceTier", provenance.governanceTier());
        putIfText(provNode, "provenanceDigest", provenance.provenanceDigest());
        if (provenance.lockedFilters() != null && !provenance.lockedFilters().isEmpty()) {
            provNode.set("lockedFilters", objectMapper.valueToTree(maskPiiInStructure(provenance.lockedFilters())));
        }
        if (provenance.appliedParameters() != null && !provenance.appliedParameters().isEmpty()) {
            provNode.set("appliedParameters", objectMapper.valueToTree(maskPiiInStructure(provenance.appliedParameters())));
        }
    }

    public static String maskPiiText(String value) {
        return value == null ? null : TurnScopedPiiVault.maskRawEmails(maskMod11TaxIds(value));
    }

    public static Map<String, Object> maskPiiInStructure(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() != null) {
                masked.put(entry.getKey(), maskPiiValue(entry.getValue()));
            }
        }
        return masked;
    }

    public static Object maskPiiValue(Object value) {
        if (value instanceof String str) {
            return maskPiiText(str);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), maskPiiValue(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof java.util.Collection<?> collection) {
            java.util.Collection<Object> out = value instanceof java.util.Set<?>
                    ? new java.util.LinkedHashSet<>(collection.size())
                    : new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(maskPiiValue(item));
            }
            return out;
        }
        if (value instanceof String[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = maskPiiText(arr[i]);
            }
            return out;
        }
        if (value instanceof Object[] arr) {
            Object[] out = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = maskPiiValue(arr[i]);
            }
            return out;
        }
        return value;
    }

    public record ComposedAssistantOutput(
            JsonNode assistantContent,
            List<Map<String, Object>> quickReplies
    ) {
    }
}
