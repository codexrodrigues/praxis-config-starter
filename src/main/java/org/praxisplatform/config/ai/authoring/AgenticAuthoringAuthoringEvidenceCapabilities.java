package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.springframework.util.StringUtils;

/** Projects vector-ranked operation cards onto the manifest-derived capability catalog. */
final class AgenticAuthoringAuthoringEvidenceCapabilities {

    private AgenticAuthoringAuthoringEvidenceCapabilities() {
    }

    static List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> select(
            ObjectMapper objectMapper,
            String componentId,
            List<ContextRetrievalService.ComponentCorpusEvidence> evidence,
            AgenticAuthoringComponentCapabilitiesResult capabilityCatalog,
            int limit) {
        if (!StringUtils.hasText(componentId) || evidence == null || capabilityCatalog == null
                || capabilityCatalog.catalogs() == null) {
            return List.of();
        }
        Map<String, AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities = new LinkedHashMap<>();
        capabilityCatalog.catalogs().stream()
                .filter(catalog -> catalog != null && componentId.equals(catalog.componentId()))
                .flatMap(catalog -> catalog.capabilities() == null ? java.util.stream.Stream.empty() : catalog.capabilities().stream())
                .filter(capability -> capability != null && StringUtils.hasText(capability.id()))
                .forEach(capability -> capabilities.putIfAbsent(capability.id(), capability));
        LinkedHashMap<String, AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> selected = new LinkedHashMap<>();
        for (ContextRetrievalService.ComponentCorpusEvidence item : evidence) {
            if (item == null || !"authoring_manifest".equals(item.chunkKind()) || !componentId.equals(item.sourceId())) {
                continue;
            }
            JsonNode card;
            try {
                card = objectMapper.readTree(item.content());
            } catch (Exception ignored) {
                continue;
            }
            if (card == null || !card.isObject() || !componentId.equals(card.path("componentId").asText())) {
                continue;
            }
            String operationId = card.path("operationId").asText();
            if (!StringUtils.hasText(operationId) || !capabilities.containsKey(operationId)) {
                continue;
            }
            selected.putIfAbsent(operationId, capabilities.get(operationId));
            if (selected.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return List.copyOf(selected.values());
    }
}
