package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

final class AgenticAuthoringPresentationAffordanceCatalog {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String version;
    private final String componentId;
    private final String defaultTargetKind;
    private final String sourceRef;
    private final List<PresentationAffordance> affordances;

    private AgenticAuthoringPresentationAffordanceCatalog(
            String version,
            String componentId,
            String defaultTargetKind,
            String sourceRef,
            List<PresentationAffordance> affordances) {
        this.version = version;
        this.componentId = componentId;
        this.defaultTargetKind = defaultTargetKind;
        this.sourceRef = sourceRef;
        this.affordances = List.copyOf(affordances);
    }

    static AgenticAuthoringPresentationAffordanceCatalog load(String resourcePath) {
        try (InputStream inputStream = AgenticAuthoringPresentationAffordanceCatalog.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Presentation affordance catalog resource not found: " + resourcePath);
            }
            JsonNode root = OBJECT_MAPPER.readTree(inputStream);
            return new AgenticAuthoringPresentationAffordanceCatalog(
                    requiredText(root, "version"),
                    requiredText(root, "componentId"),
                    requiredText(root, "defaultTargetKind"),
                    requiredText(root, "sourceRef"),
                    parseAffordances(root.path("affordances")));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read presentation affordance catalog: " + resourcePath, exception);
        }
    }

    static AgenticAuthoringPresentationAffordanceCatalog fromJson(JsonNode root, String fallbackComponentId) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Presentation affordance catalog must be an object.");
        }
        return new AgenticAuthoringPresentationAffordanceCatalog(
                requiredText(root, "version"),
                firstText(root, "componentId", fallbackComponentId),
                requiredText(root, "defaultTargetKind"),
                requiredText(root, "sourceRef"),
                parseAffordances(root.path("affordances")));
    }

    List<PresentationAffordance> compatibleAffordances(String targetKind, String dataType) {
        String safeTargetKind = targetKind == null || targetKind.isBlank() ? defaultTargetKind : targetKind;
        String safeDataType = dataType == null || dataType.isBlank() ? "unknown" : dataType;
        return affordances.stream()
                .filter(affordance -> safeTargetKind.equals(affordance.targetKind()))
                .filter(affordance -> affordance.supportsType(safeDataType))
                .toList();
    }

    String version() {
        return version;
    }

    String componentId() {
        return componentId;
    }

    String defaultTargetKind() {
        return defaultTargetKind;
    }

    String sourceRef() {
        return sourceRef;
    }

    JsonNode toJson(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("version", version);
        root.put("kind", "praxis.ai-authoring.presentation-affordance-catalog");
        root.put("componentId", componentId);
        root.put("defaultTargetKind", defaultTargetKind);
        root.put("sourceRef", sourceRef);
        ArrayNode affordanceNodes = root.putArray("affordances");
        for (PresentationAffordance affordance : affordances) {
            ObjectNode node = affordanceNodes.addObject();
            node.put("id", affordance.id());
            node.put("targetKind", affordance.targetKind());
            node.put("category", affordance.category());
            node.put("description", affordance.description());
            ArrayNode options = node.putArray("options");
            affordance.options().forEach(options::add);
            ArrayNode appliesToTypes = node.putArray("appliesToTypes");
            affordance.appliesToTypes().forEach(appliesToTypes::add);
            node.put("unknownCompatible", affordance.unknownCompatible());
        }
        return root;
    }

    private static List<PresentationAffordance> parseAffordances(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalStateException("Presentation affordance catalog must declare at least one affordance.");
        }
        List<PresentationAffordance> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            parsed.add(new PresentationAffordance(
                    requiredText(node, "id"),
                    requiredText(node, "targetKind"),
                    requiredText(node, "category"),
                    requiredText(node, "description"),
                    parseStringList(node.path("options")),
                    parseStringList(node.path("appliesToTypes")),
                    node.path("unknownCompatible").asBoolean(false)));
        }
        return parsed;
    }

    private static List<String> parseStringList(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new IllegalStateException("Catalog lists must contain only non-blank strings.");
            }
            parsed.add(node.asText());
        }
        return parsed;
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Presentation affordance catalog field is required: " + fieldName);
        }
        return value;
    }

    private static String firstText(JsonNode node, String fieldName, String fallback) {
        String value = node.path(fieldName).asText("");
        if (value.isBlank()) {
            value = fallback == null ? "" : fallback;
        }
        if (value.isBlank()) {
            throw new IllegalStateException("Presentation affordance catalog field is required: " + fieldName);
        }
        return value;
    }

    record PresentationAffordance(
            String id,
            String targetKind,
            String category,
            String description,
            List<String> options,
            List<String> appliesToTypes,
            boolean unknownCompatible) {

        boolean supportsType(String dataType) {
            if ("unknown".equals(dataType)) {
                return unknownCompatible;
            }
            return appliesToTypes.contains(dataType);
        }
    }
}
