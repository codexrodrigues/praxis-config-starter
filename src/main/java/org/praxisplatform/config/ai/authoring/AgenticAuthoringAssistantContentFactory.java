package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class AgenticAuthoringAssistantContentFactory {

    private static final int MAX_RESOURCES = 5;
    private static final int MAX_FIELDS_PER_RESOURCE = 6;
    private static final int MAX_INLINES_PER_PARAGRAPH = 24;
    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,120}$");
    private static final Pattern SAFE_RESOURCE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$");

    private AgenticAuthoringAssistantContentFactory() {
    }

    public sealed interface ParagraphInline permits ParagraphTextInline, ParagraphRecordReferenceInline {
    }

    public record ParagraphTextInline(String text) implements ParagraphInline {
    }

    public record QuickViewContentRef(
            String namespace,
            String presetId,
            String version
    ) {
    }

    public record ParagraphRecordReferenceInline(
            String label,
            String resourceKey,
            String surfaceId,
            String sourceIdentityField,
            QuickViewContentRef contentRef,
            Object identityValue
    ) implements ParagraphInline {
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

    public static ObjectNode buildParagraphBlock(
            String text,
            List<? extends ParagraphInline> inlines) {
        ArrayNode inlineNodes = JsonNodeFactory.instance.arrayNode();
        StringBuilder fallbackBuilder = new StringBuilder();

        if (inlines != null) {
            for (ParagraphInline inline : inlines.stream().limit(MAX_INLINES_PER_PARAGRAPH).toList()) {
                if (inline instanceof ParagraphTextInline textInline) {
                    if (StringUtils.hasText(textInline.text())) {
                        ObjectNode node = inlineNodes.addObject();
                        node.put("type", "text");
                        node.put("text", textInline.text());
                        fallbackBuilder.append(textInline.text());
                    }
                } else if (inline instanceof ParagraphRecordReferenceInline refInline) {
                    addValidatedRecordReferenceInline(inlineNodes, fallbackBuilder, refInline);
                }
            }
        }

        String resolvedText = StringUtils.hasText(text)
                ? text.trim()
                : fallbackBuilder.toString().trim();
        if (!StringUtils.hasText(resolvedText) && inlineNodes.isEmpty()) {
            return null;
        }

        ObjectNode block = JsonNodeFactory.instance.objectNode();
        block.put("type", "paragraph");
        block.put("text", resolvedText);
        if (!inlineNodes.isEmpty()) {
            block.set("inlines", inlineNodes);
        }
        return block;
    }

    private static void addValidatedRecordReferenceInline(
            ArrayNode inlineNodes,
            StringBuilder fallbackBuilder,
            ParagraphRecordReferenceInline ref) {
        if (ref == null || !StringUtils.hasText(ref.label())) {
            return;
        }
        String label = ref.label().trim();
        String resourceKey = normalizeResourceKey(ref.resourceKey());
        String surfaceId = normalizeIdentifier(ref.surfaceId());
        String sourceIdentityField = normalizeIdentifier(ref.sourceIdentityField());
        QuickViewContentRef normalizedContentRef = normalizeContentRef(ref.contentRef());
        boolean invalidContentRefProvided = ref.contentRef() != null && normalizedContentRef == null;
        Object identityValue = ref.identityValue();
        boolean validPrimitiveIdentity = (identityValue instanceof String str && StringUtils.hasText(str))
                || (identityValue instanceof Integer)
                || (identityValue instanceof Long);

        if (resourceKey == null
                || surfaceId == null
                || sourceIdentityField == null
                || invalidContentRefProvided
                || !validPrimitiveIdentity) {
            ObjectNode degraded = inlineNodes.addObject();
            degraded.put("type", "text");
            degraded.put("text", label);
            fallbackBuilder.append(label);
            return;
        }

        ObjectNode node = inlineNodes.addObject();
        node.put("type", "record-reference");
        node.put("label", label);

        ObjectNode quickView = node.putObject("quickView");
        ObjectNode recordOpen = quickView.putObject("recordOpen");
        recordOpen.put("sourceIdentityField", sourceIdentityField);
        ObjectNode target = recordOpen.putObject("target");
        target.put("resourceKey", resourceKey);
        target.put("surfaceId", surfaceId);
        if (normalizedContentRef != null) {
            ObjectNode contentRefNode = quickView.putObject("contentRef");
            contentRefNode.put("kind", "rich-block");
            contentRefNode.put("namespace", normalizedContentRef.namespace());
            contentRefNode.put("presetId", normalizedContentRef.presetId());
            contentRefNode.put("version", normalizedContentRef.version());
        }

        ObjectNode identityValues = node.putObject("identityValues");
        if (identityValue instanceof String str) {
            identityValues.put(sourceIdentityField, str.trim());
        } else if (identityValue instanceof Integer intVal) {
            identityValues.put(sourceIdentityField, intVal);
        } else if (identityValue instanceof Long longVal) {
            identityValues.put(sourceIdentityField, longVal);
        }
        fallbackBuilder.append(label);
    }

    private static String normalizeResourceKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_RESOURCE_KEY_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private static String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_IDENTIFIER_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private static QuickViewContentRef normalizeContentRef(QuickViewContentRef value) {
        if (value == null) {
            return null;
        }
        String namespace = normalizeIdentifier(value.namespace());
        String presetId = normalizeIdentifier(value.presetId());
        String version = normalizeIdentifier(value.version());
        if (namespace == null || presetId == null || version == null) {
            return null;
        }
        return new QuickViewContentRef(namespace, presetId, version);
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
            putText(node, "label", AgenticAuthoringPresentationText.display(resource.label()));
            putText(node, "description", AgenticAuthoringPresentationText.display(resource.description()));
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
            putText(node, "label", AgenticAuthoringPresentationText.display(firstNonBlank(field.label(), field.name())));
            putText(node, "description", AgenticAuthoringPresentationText.display(field.description()));
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
