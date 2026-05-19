package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.util.StringUtils;

public final class AgenticAuthoringAssistantContentFactory {

    private static final int MAX_RESOURCES = 5;
    private static final int MAX_FIELDS_PER_RESOURCE = 6;

    private AgenticAuthoringAssistantContentFactory() {
    }

    public static JsonNode fromConsultativeProjection(
            AgenticAuthoringConsultativeApiCatalogProjection projection) {
        if (projection == null || !projection.hasResources()) {
            return null;
        }
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        ArrayNode blocks = content.putArray("blocks");
        addParagraph(blocks, projection.assistantMessage());
        addResourceList(blocks, projection.resources());
        addRecommendation(blocks, projection.resources());
        return blocks.isEmpty() ? null : content;
    }

    private static void addParagraph(ArrayNode blocks, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        ObjectNode block = blocks.addObject();
        block.put("type", "paragraph");
        block.put("text", text.trim());
    }

    private static void addResourceList(
            ArrayNode blocks,
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> visible = resources == null
                ? List.of()
                : resources.stream().limit(MAX_RESOURCES).toList();
        if (visible.isEmpty()) {
            return;
        }
        ObjectNode block = blocks.addObject();
        block.put("type", "resource-list");
        block.put("title", visible.size() == 1 ? "Fonte confirmada" : "Fontes confirmadas");
        ArrayNode resourceNodes = block.putArray("resources");
        for (AgenticAuthoringConsultativeApiCatalogProjection.Resource resource : visible) {
            ObjectNode node = resourceNodes.addObject();
            putText(node, "id", resource.resourceKey());
            putText(node, "label", resource.label());
            putText(node, "description", resource.description());
            putText(node, "role", resource.role());
            putText(node, "resourcePath", resource.resourcePath());
            addFields(node, resource.fields());
            addEvidence(node, resource.evidence());
        }
    }

    private static void addFields(
            ObjectNode resourceNode,
            List<AgenticAuthoringConsultativeApiCatalogProjection.Field> fields) {
        List<AgenticAuthoringConsultativeApiCatalogProjection.Field> visible = fields == null
                ? List.of()
                : fields.stream().limit(MAX_FIELDS_PER_RESOURCE).toList();
        if (visible.isEmpty()) {
            return;
        }
        ArrayNode fieldNodes = resourceNode.putArray("fields");
        for (AgenticAuthoringConsultativeApiCatalogProjection.Field field : visible) {
            ObjectNode node = fieldNodes.addObject();
            putText(node, "name", field.name());
            putText(node, "label", firstNonBlank(field.label(), field.name()));
            putText(node, "description", field.description());
        }
    }

    private static void addEvidence(ObjectNode resourceNode, List<String> evidence) {
        List<String> visible = evidence == null
                ? List.of()
                : evidence.stream().filter(StringUtils::hasText).distinct().limit(3).toList();
        if (visible.isEmpty()) {
            return;
        }
        ArrayNode evidenceNodes = resourceNode.putArray("evidence");
        visible.forEach(value -> evidenceNodes.add(value.trim()));
    }

    private static void addRecommendation(
            ArrayNode blocks,
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null || resources.isEmpty()) {
            return;
        }
        boolean hasConfirmedFields = resources.stream()
                .anyMatch(resource -> resource.fields() != null && !resource.fields().isEmpty());
        boolean hasAnalytics = resources.stream()
                .anyMatch(resource -> "analytical".equals(resource.role()));
        ObjectNode block = blocks.addObject();
        block.put("type", "recommendation");
        block.put("title", "Recomendação");
        block.put("text", hasConfirmedFields
                ? (hasAnalytics
                        ? "Comece por uma visão analítica com indicadores e mantenha uma tabela de detalhes para auditoria."
                        : "Comece por uma lista filtrável usando apenas campos confirmados no catálogo.")
                : "Antes de materializar a tela, confirme quais campos dessa fonte devem aparecer.");
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (StringUtils.hasText(value)) {
            node.put(field, value.trim());
        }
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
