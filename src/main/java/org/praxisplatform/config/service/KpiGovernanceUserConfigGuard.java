package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.service.PublishedUiLayoutBaselineProvider.PublishedBaseline;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Guarda server-side em {@code ui_user_config} que garante integridade estrutural incondicional
 * (anti-SSRF e cotas) e proteção de cards KPI governados com {@code governance.lockLevel:
 * "metric-locked" | "full"} quando houver revisão publicada ativa em {@code ui-layouts}.
 */
public final class KpiGovernanceUserConfigGuard {

    public static final String KPI_GOVERNANCE_LOCK_ERROR_CODE = "KPI_GOVERNANCE_LOCK_VIOLATION";
    public static final String KPI_STRUCTURE_VIOLATION_ERROR_CODE = "KPI_STRUCTURE_VIOLATION";
    private static final int MAX_WIDGET_NESTING_DEPTH = 12;
    private static final String LOCK_NONE = "none";
    private static final String LOCK_METRIC = "metric-locked";
    private static final String LOCK_FULL = "full";
    private static final Set<String> SUPPORTED_TYPES =
            Set.of("praxis-dynamic-page", "praxis-kpi-strip", "praxis-kpi-card");
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

    private final PublishedUiLayoutBaselineProvider baselineProvider;

    public KpiGovernanceUserConfigGuard(PublishedUiLayoutBaselineProvider baselineProvider) {
        this.baselineProvider = baselineProvider;
    }

    public void assertWriteAllowed(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment,
            JsonNode candidatePayload) {
        if (!StringUtils.hasText(componentType)
                || !SUPPORTED_TYPES.contains(componentType.trim())) {
            return;
        }
        String normalizedType = componentType.trim();

        // P0-4: anti-SSRF, cotas 12/24 e lockLevel válido valem incondicionalmente em ui_user_config,
        // inclusive quando não houver baseline publicado ou em cards lockLevel "none"/"metric-locked".
        if (candidatePayload != null && candidatePayload.isObject()) {
            try {
                UiLayoutKpiStructureValidator.validateRuntimePayloadStructure(
                        normalizedType, candidatePayload);
            } catch (UiLayoutLifecycleException ex) {
                throw new UiConfigWriteDeniedException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        KPI_STRUCTURE_VIOLATION_ERROR_CODE,
                        ex.getMessage());
            }
        }

        if (baselineProvider == null) {
            return;
        }
        Optional<PublishedBaseline> baselineOpt =
                baselineProvider.findActivePublishedBaseline(
                        tenantId, userId, normalizedType, componentId, environment);
        if (baselineOpt.isEmpty() || baselineOpt.get().document() == null || baselineOpt.get().document().isNull()) {
            return;
        }
        List<LockedCardEntry> lockedCards =
                extractLockedCards(normalizedType, baselineOpt.get().document());
        if (lockedCards.isEmpty()) {
            return;
        }
        if (candidatePayload == null || !candidatePayload.isObject()) {
            throw denied("Published KPI governance lock prevents clearing or replacing governed configuration.");
        }

        switch (normalizedType) {
            case "praxis-dynamic-page" -> enforcePageWrite(lockedCards, candidatePayload);
            case "praxis-kpi-strip" -> enforceStripWrite(lockedCards, candidatePayload);
            case "praxis-kpi-card" -> enforceSingleCardWrite(lockedCards.getFirst(), candidatePayload);
            default -> {}
        }
    }

    public String resolveGovernanceVersionToken(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment) {
        if (baselineProvider == null
                || !StringUtils.hasText(componentType)
                || !SUPPORTED_TYPES.contains(componentType.trim())) {
            return null;
        }
        String normalizedType = componentType.trim();
        Optional<PublishedBaseline> baselineOpt =
                baselineProvider.findActivePublishedBaseline(
                        tenantId, userId, normalizedType, componentId, environment);
        if (baselineOpt.isEmpty() || baselineOpt.get().document() == null || baselineOpt.get().document().isNull()) {
            return null;
        }
        List<LockedCardEntry> lockedCards =
                extractLockedCards(normalizedType, baselineOpt.get().document());
        if (lockedCards.isEmpty()) {
            return null;
        }
        String explicitToken = baselineOpt.get().versionToken();
        if (StringUtils.hasText(explicitToken)) {
            return explicitToken.trim();
        }
        return digestLockedCards(lockedCards);
    }

    public JsonNode reconcileResolvedPayload(
            String tenantId,
            String userId,
            String componentType,
            String componentId,
            String environment,
            JsonNode persistedPayload) {
        if (baselineProvider == null
                || persistedPayload == null
                || persistedPayload.isNull()
                || !persistedPayload.isObject()
                || !StringUtils.hasText(componentType)
                || !SUPPORTED_TYPES.contains(componentType.trim())) {
            return persistedPayload;
        }
        String normalizedType = componentType.trim();
        Optional<PublishedBaseline> baselineOpt =
                baselineProvider.findActivePublishedBaseline(
                        tenantId, userId, normalizedType, componentId, environment);
        if (baselineOpt.isEmpty() || baselineOpt.get().document() == null || baselineOpt.get().document().isNull()) {
            return persistedPayload;
        }
        List<LockedCardEntry> lockedCards =
                extractLockedCards(normalizedType, baselineOpt.get().document());
        if (lockedCards.isEmpty()) {
            return persistedPayload;
        }

        JsonNode copy = persistedPayload.deepCopy();
        switch (normalizedType) {
            case "praxis-dynamic-page" -> reconcilePagePayload(lockedCards, (ObjectNode) copy);
            case "praxis-kpi-strip" -> reconcileStripPayload(lockedCards, copy);
            case "praxis-kpi-card" -> reconcileSingleCardPayload(lockedCards.getFirst(), copy);
            default -> {}
        }
        return copy;
    }

    private static void enforcePageWrite(
            List<LockedCardEntry> lockedCards, JsonNode candidatePayload) {
        Map<String, List<WidgetLocation>> candidateWidgetsByKey =
                indexWidgetsRecursiveByKey(candidatePayload);
        for (LockedCardEntry locked : lockedCards) {
            List<WidgetLocation> matches =
                    candidateWidgetsByKey.getOrDefault(locked.widgetKey(), List.of());
            if (matches.size() > 1) {
                throw denied(
                        "Duplicate governed KPI widget key '"
                                + locked.widgetKey()
                                + "' is not permitted.");
            }
            WidgetLocation candidateLocation = matches.isEmpty() ? null : matches.getFirst();
            if (candidateLocation == null) {
                throw denied(
                        "Cannot remove governed KPI widget '"
                                + locked.widgetKey()
                                + "' ("
                                + locked.lockLevel()
                                + ").");
            }
            String candidateWidgetId = candidateLocation.definition().path("id").asText("");
            if (!locked.widgetId().equals(candidateWidgetId)) {
                throw denied(
                        "Cannot alter definition id of governed KPI widget '"
                                + locked.widgetKey()
                                + "'.");
            }
            JsonNode inputsConfig = candidateLocation.definition().path("inputs").path("config");
            if (!inputsConfig.isObject()) {
                throw denied(
                        "Cannot remove configuration of governed KPI widget '"
                                + locked.widgetKey()
                                + "'.");
            }
            if ("praxis-kpi-card".equals(locked.widgetId())) {
                verifyLockedCard(locked, inputsConfig);
            } else {
                verifyLockedStripCard(locked, indexStripCardsById(inputsConfig));
            }
        }
    }

    private static void enforceStripWrite(
            List<LockedCardEntry> lockedCards, JsonNode candidatePayload) {
        JsonNode stripConfig = locateStripConfigContainer(candidatePayload);
        if (stripConfig == null || !stripConfig.isObject()) {
            throw denied("Cannot remove governed KPI strip configuration.");
        }
        Map<String, List<JsonNode>> candidateCards = indexStripCardsById(stripConfig);
        for (LockedCardEntry locked : lockedCards) {
            verifyLockedStripCard(locked, candidateCards);
        }
    }

    private static void enforceSingleCardWrite(
            LockedCardEntry locked, JsonNode candidatePayload) {
        JsonNode candidateCard = locateSingleCardContainer(candidatePayload);
        verifyLockedCard(locked, candidateCard);
    }

    private static void verifyLockedCard(LockedCardEntry locked, JsonNode candidateCard) {
        if (candidateCard == null || !candidateCard.isObject()) {
            throw denied(
                    "Cannot remove governed KPI card '"
                            + locked.cardId()
                            + "' under lockLevel '"
                            + locked.lockLevel()
                            + "'.");
        }
        if (StringUtils.hasText(locked.cardId())
                && !locked.cardId().equals(candidateCard.path("id").asText(""))) {
            throw denied(
                    "Cannot alter identity of governed KPI card '"
                            + locked.cardId()
                            + "'.");
        }
        String candidateLockLevel = candidateCard.path("governance").path("lockLevel").asText("");
        if (!locked.lockLevel().equals(candidateLockLevel)) {
            throw denied(
                    "Cannot downgrade or remove governance.lockLevel '"
                            + locked.lockLevel()
                            + "' on KPI card '"
                            + locked.cardId()
                            + "'.");
        }
        if (LOCK_FULL.equals(locked.lockLevel())) {
            if (!Objects.equals(locked.baselineCard(), candidateCard)) {
                throw denied(
                        "Cannot modify KPI card '"
                                + locked.cardId()
                                + "' while governance.lockLevel is 'full'.");
            }
            return;
        }
        if (LOCK_METRIC.equals(locked.lockLevel())) {
            if (!Objects.equals(locked.baselineCard().get("source"), candidateCard.get("source"))
                    || !Objects.equals(
                            locked.baselineCard().get("derivation"),
                            candidateCard.get("derivation"))) {
                throw denied(
                        "Cannot modify source or derivation of KPI card '"
                                + locked.cardId()
                                + "' while governance.lockLevel is 'metric-locked'.");
            }
        }
    }

    private static void reconcilePagePayload(
            List<LockedCardEntry> lockedCards, ObjectNode candidatePayload) {
        ArrayNode widgetsArray = ensureWidgetsArray(candidatePayload);
        for (LockedCardEntry locked : lockedCards) {
            Map<String, List<WidgetLocation>> byKey = indexWidgetsRecursiveByKey(candidatePayload);
            List<WidgetLocation> matches = byKey.getOrDefault(locked.widgetKey(), List.of());
            if (matches.isEmpty()) {
                widgetsArray.add(buildRestorableRootWidget(locked));
                continue;
            }
            // Deduplica possíveis réplicas forjadas em ordem reversa mantendo o primeiro nó.
            for (int i = matches.size() - 1; i >= 1; i--) {
                WidgetLocation dup = matches.get(i);
                dup.parentArray().remove(dup.index());
            }
            WidgetLocation loc = matches.getFirst();
            String currentId = loc.definition().path("id").asText("");
            if (!locked.widgetId().equals(currentId)
                    || !loc.definition().path("inputs").path("config").isObject()) {
                loc.parentArray().set(loc.index(), locked.baselineWidget().deepCopy());
                continue;
            }
            ObjectNode inputs = (ObjectNode) loc.definition().path("inputs");
            if ("praxis-kpi-card".equals(locked.widgetId())) {
                ObjectNode currentCard = (ObjectNode) inputs.get("config");
                if (LOCK_FULL.equals(locked.lockLevel())) {
                    inputs.set("config", locked.baselineCard().deepCopy());
                } else {
                    reconcileMetricLockedCardInPlace(locked.baselineCard(), currentCard);
                }
            } else {
                ObjectNode stripConfig = (ObjectNode) inputs.get("config");
                reconcileCardInsideStrip(locked, stripConfig);
            }
        }
    }

    private static ObjectNode buildRestorableRootWidget(LockedCardEntry locked) {
        ObjectNode baseline = locked.baselineWidget().deepCopy();
        if (baseline.path("definition").isObject()) {
            return baseline;
        }
        ObjectNode wrapped = CANONICAL_JSON.createObjectNode();
        String fallbackKey = locked.widgetKey().replace('/', '-');
        wrapped.put("key", StringUtils.hasText(fallbackKey) ? fallbackKey : locked.cardId());
        wrapped.set("definition", baseline);
        return wrapped;
    }

    private static void reconcileStripPayload(
            List<LockedCardEntry> lockedCards, JsonNode candidatePayload) {
        JsonNode container = locateStripConfigContainer(candidatePayload);
        if (!(container instanceof ObjectNode stripConfig)) {
            return;
        }
        for (LockedCardEntry locked : lockedCards) {
            reconcileCardInsideStrip(locked, stripConfig);
        }
    }

    private static void reconcileSingleCardPayload(
            LockedCardEntry locked, JsonNode candidatePayload) {
        if (!(candidatePayload instanceof ObjectNode root)) {
            return;
        }
        if (root.has("config") && root.get("config").isObject()) {
            ObjectNode cfg = (ObjectNode) root.get("config");
            if (cfg.has("config") && cfg.get("config").isObject()) {
                if (LOCK_FULL.equals(locked.lockLevel())) {
                    cfg.set("config", locked.baselineCard().deepCopy());
                } else {
                    reconcileMetricLockedCardInPlace(
                            locked.baselineCard(), (ObjectNode) cfg.get("config"));
                }
                return;
            }
            if (LOCK_FULL.equals(locked.lockLevel())) {
                root.set("config", locked.baselineCard().deepCopy());
            } else {
                reconcileMetricLockedCardInPlace(locked.baselineCard(), cfg);
            }
            return;
        }
        if (LOCK_FULL.equals(locked.lockLevel())) {
            root.removeAll();
            root.setAll(locked.baselineCard().deepCopy());
        } else {
            reconcileMetricLockedCardInPlace(locked.baselineCard(), root);
        }
    }

    private static void reconcileCardInsideStrip(LockedCardEntry locked, ObjectNode stripConfig) {
        ArrayNode cards =
                stripConfig.get("cards") instanceof ArrayNode existing
                        ? existing
                        : stripConfig.putArray("cards");
        List<Integer> matchingIndices = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            JsonNode item = cards.get(i);
            if (item != null
                    && item.isObject()
                    && locked.cardId().equals(item.path("id").asText(""))) {
                matchingIndices.add(i);
            }
        }
        if (matchingIndices.isEmpty()) {
            cards.add(locked.baselineCard().deepCopy());
            return;
        }
        int firstIndex = matchingIndices.getFirst();
        if (LOCK_FULL.equals(locked.lockLevel())) {
            cards.set(firstIndex, locked.baselineCard().deepCopy());
        } else {
            reconcileMetricLockedCardInPlace(
                    locked.baselineCard(), (ObjectNode) cards.get(firstIndex));
        }
        for (int i = matchingIndices.size() - 1; i >= 1; i--) {
            cards.remove(matchingIndices.get(i).intValue());
        }
    }

    private static void reconcileMetricLockedCardInPlace(
            ObjectNode baselineCard, ObjectNode candidateCard) {
        if (baselineCard.has("id")) {
            candidateCard.set("id", baselineCard.get("id").deepCopy());
        }
        if (baselineCard.has("source")) {
            candidateCard.set("source", baselineCard.get("source").deepCopy());
        } else {
            candidateCard.remove("source");
        }
        if (baselineCard.has("derivation")) {
            candidateCard.set("derivation", baselineCard.get("derivation").deepCopy());
        } else {
            candidateCard.remove("derivation");
        }
        if (baselineCard.has("governance")) {
            candidateCard.set("governance", baselineCard.get("governance").deepCopy());
        } else {
            candidateCard.remove("governance");
        }
    }

    private static List<LockedCardEntry> extractLockedCards(
            String componentType, JsonNode document) {
        List<LockedCardEntry> locked = new ArrayList<>();
        if ("praxis-dynamic-page".equals(componentType)) {
            List<WidgetLocation> allWidgets = new ArrayList<>();
            collectWidgetsRecursive(
                    extractWidgetsArray(document), null, null, allWidgets, 0);
            for (WidgetLocation loc : allWidgets) {
                String widgetId = loc.definition().path("id").asText("");
                if ("praxis-kpi-card".equals(widgetId)) {
                    JsonNode cardNode = loc.definition().path("inputs").path("config");
                    addLockedCardIfPresent(
                            loc.key(), widgetId, loc.entry(), cardNode, locked);
                } else if ("praxis-kpi-strip".equals(widgetId)) {
                    JsonNode stripCards = loc.definition().path("inputs").path("config").path("cards");
                    if (stripCards.isArray()) {
                        for (JsonNode cardNode : stripCards) {
                            addLockedCardIfPresent(
                                    loc.key(), widgetId, loc.entry(), cardNode, locked);
                        }
                    }
                }
            }
            return locked;
        }
        if ("praxis-kpi-strip".equals(componentType)) {
            JsonNode stripConfig = locateStripConfigContainer(document);
            if (stripConfig != null && stripConfig.path("cards").isArray()) {
                for (JsonNode cardNode : stripConfig.path("cards")) {
                    addLockedCardIfPresent("", componentType, null, cardNode, locked);
                }
            }
            return locked;
        }
        if ("praxis-kpi-card".equals(componentType)) {
            JsonNode cardNode = locateSingleCardContainer(document);
            addLockedCardIfPresent("", componentType, null, cardNode, locked);
        }
        return locked;
    }

    private static void addLockedCardIfPresent(
            String widgetKey,
            String widgetId,
            ObjectNode baselineWidget,
            JsonNode cardNode,
            List<LockedCardEntry> out) {
        if (!(cardNode instanceof ObjectNode cardObj)) {
            return;
        }
        String lockLevel = cardObj.path("governance").path("lockLevel").asText(LOCK_NONE);
        if (!LOCK_METRIC.equals(lockLevel) && !LOCK_FULL.equals(lockLevel)) {
            return;
        }
        String cardId = cardObj.path("id").asText("");
        out.add(
                new LockedCardEntry(
                        widgetKey,
                        widgetId,
                        cardId,
                        lockLevel,
                        baselineWidget == null ? null : baselineWidget.deepCopy(),
                        cardObj.deepCopy()));
    }

    private static Map<String, List<WidgetLocation>> indexWidgetsRecursiveByKey(
            JsonNode pageDocument) {
        Map<String, List<WidgetLocation>> byKey = new LinkedHashMap<>();
        List<WidgetLocation> collected = new ArrayList<>();
        collectWidgetsRecursive(extractWidgetsArray(pageDocument), null, null, collected, 0);
        for (WidgetLocation loc : collected) {
            if (StringUtils.hasText(loc.key())) {
                byKey.computeIfAbsent(loc.key(), ignored -> new ArrayList<>()).add(loc);
            }
        }
        return byKey;
    }

    private static void collectWidgetsRecursive(
            JsonNode widgetsNode,
            String parentKeyPrefix,
            String slotKeyPrefix,
            List<WidgetLocation> out,
            int depth) {
        if (!(widgetsNode instanceof ArrayNode widgetsArray) || depth > MAX_WIDGET_NESTING_DEPTH) {
            return;
        }
        for (int i = 0; i < widgetsArray.size(); i++) {
            JsonNode entryNode = widgetsArray.get(i);
            if (!(entryNode instanceof ObjectNode entryObj)) {
                continue;
            }
            boolean isFullInstance = entryObj.path("definition").isObject();
            ObjectNode definitionObj =
                    isFullInstance ? (ObjectNode) entryObj.get("definition") : entryObj;
            String widgetId = definitionObj.path("id").asText("");
            String stableKey;
            if (isFullInstance) {
                stableKey = resolveWidgetKey(entryObj);
                if (StringUtils.hasText(parentKeyPrefix) && StringUtils.hasText(stableKey)) {
                    stableKey = parentKeyPrefix + "/page/" + stableKey;
                }
            } else {
                String childKey = entryObj.path("childWidgetKey").asText("");
                if (!StringUtils.hasText(childKey)) {
                    childKey = widgetId + "@" + i;
                }
                stableKey = StringUtils.hasText(slotKeyPrefix) ? slotKeyPrefix + "/" + childKey : childKey;
            }

            out.add(new WidgetLocation(stableKey, widgetsArray, i, entryObj, definitionObj));

            JsonNode inputs = definitionObj.path("inputs");
            JsonNode containerConfig = inputs.path("config");
            if (containerConfig.isObject()) {
                collectSlotWidgetsRecursive(containerConfig.path("tabs"), stableKey, "tabs", out, depth + 1);
                collectSlotWidgetsRecursive(containerConfig.path("panels"), stableKey, "panels", out, depth + 1);
                collectSlotWidgetsRecursive(containerConfig.path("steps"), stableKey, "steps", out, depth + 1);
                JsonNode nav = containerConfig.path("nav");
                if (nav.isObject()) {
                    collectSlotWidgetsRecursive(nav.path("links"), stableKey, "nav.links", out, depth + 1);
                }
            }
            JsonNode nestedPageWidgets = inputs.path("page").path("widgets");
            if (nestedPageWidgets.isArray()) {
                collectWidgetsRecursive(nestedPageWidgets, stableKey, null, out, depth + 1);
            }
        }
    }

    private static void collectSlotWidgetsRecursive(
            JsonNode slotsNode,
            String parentKey,
            String containerType,
            List<WidgetLocation> out,
            int depth) {
        if (slotsNode == null || !slotsNode.isArray()) {
            return;
        }
        for (int s = 0; s < slotsNode.size(); s++) {
            JsonNode slot = slotsNode.get(s);
            if (slot == null || !slot.isObject() || !slot.path("widgets").isArray()) {
                continue;
            }
            String slotId = slot.path("id").asText("").trim();
            String slotToken = StringUtils.hasText(slotId) ? slotId : String.valueOf(s);
            String slotPrefix =
                    (StringUtils.hasText(parentKey) ? parentKey + "/" : "")
                            + containerType
                            + "["
                            + slotToken
                            + "]";
            collectWidgetsRecursive(slot.path("widgets"), parentKey, slotPrefix, out, depth);
        }
    }

    private static JsonNode extractWidgetsArray(JsonNode pageDocument) {
        JsonNode direct = pageDocument.get("widgets");
        if (direct != null && direct.isArray()) {
            return direct;
        }
        JsonNode def = pageDocument.at("/definition/widgets");
        if (def.isArray()) {
            return def;
        }
        return pageDocument.at("/config/definition/widgets");
    }

    private static ArrayNode ensureWidgetsArray(ObjectNode pageDocument) {
        JsonNode existing = pageDocument.get("widgets");
        if (existing instanceof ArrayNode arr) {
            return arr;
        }
        if (pageDocument.path("definition").isObject()) {
            ObjectNode def = (ObjectNode) pageDocument.get("definition");
            return def.get("widgets") instanceof ArrayNode arr ? arr : def.putArray("widgets");
        }
        if (pageDocument.path("config").path("definition").isObject()) {
            ObjectNode def = (ObjectNode) pageDocument.path("config").path("definition");
            return def.get("widgets") instanceof ArrayNode arr ? arr : def.putArray("widgets");
        }
        return pageDocument.putArray("widgets");
    }

    private static String resolveWidgetKey(JsonNode widget) {
        String key = widget.path("key").asText("");
        if (!StringUtils.hasText(key)) {
            key = widget.path("id").asText("");
        }
        return key;
    }

    private static String digestLockedCards(List<LockedCardEntry> lockedCards) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LockedCardEntry entry : lockedCards) {
                digest.update(
                        (entry.widgetKey()
                                        + "|"
                                        + entry.widgetId()
                                        + "|"
                                        + entry.cardId()
                                        + "|"
                                        + entry.lockLevel()
                                        + "|")
                                .getBytes(StandardCharsets.UTF_8));
                digest.update(CANONICAL_JSON.writeValueAsBytes(entry.baselineCard()));
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to digest KPI governance locks.", ex);
        }
    }

    private static void verifyLockedStripCard(
            LockedCardEntry locked, Map<String, List<JsonNode>> candidateCardsById) {
        List<JsonNode> matches = candidateCardsById.getOrDefault(locked.cardId(), List.of());
        if (matches.size() > 1) {
            throw denied(
                    "Duplicate governed KPI card id '"
                            + locked.cardId()
                            + "' is not permitted.");
        }
        verifyLockedCard(locked, matches.isEmpty() ? null : matches.getFirst());
    }

    private static Map<String, List<JsonNode>> indexStripCardsById(JsonNode stripConfig) {
        Map<String, List<JsonNode>> map = new LinkedHashMap<>();
        JsonNode cards = stripConfig.path("cards");
        if (!cards.isArray()) {
            return map;
        }
        for (JsonNode card : cards) {
            if (card != null && card.isObject() && StringUtils.hasText(card.path("id").asText(""))) {
                String cardId = card.path("id").asText("");
                map.computeIfAbsent(cardId, ignored -> new ArrayList<>()).add(card);
            }
        }
        return map;
    }

    private static JsonNode locateStripConfigContainer(JsonNode root) {
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

    private static JsonNode locateSingleCardContainer(JsonNode root) {
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

    private static UiConfigWriteDeniedException denied(String message) {
        return new UiConfigWriteDeniedException(
                HttpStatus.UNPROCESSABLE_ENTITY, KPI_GOVERNANCE_LOCK_ERROR_CODE, message);
    }

    private record WidgetLocation(
            String key,
            ArrayNode parentArray,
            int index,
            ObjectNode entry,
            ObjectNode definition) {}

    private record LockedCardEntry(
            String widgetKey,
            String widgetId,
            String cardId,
            String lockLevel,
            ObjectNode baselineWidget,
            ObjectNode baselineCard) {}
}
