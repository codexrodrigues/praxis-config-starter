package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;

public class AgenticAuthoringResourceBackedPresentationAffordanceProvider
        implements AgenticAuthoringPresentationAffordanceProvider {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringPresentationAffordanceCatalogService catalogService;

    public AgenticAuthoringResourceBackedPresentationAffordanceProvider(
            ObjectMapper objectMapper,
            AgenticAuthoringPresentationAffordanceCatalogService catalogService) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.catalogService = catalogService != null
                ? catalogService
                : AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper);
    }

    public AgenticAuthoringResourceBackedPresentationAffordanceProvider(
            ObjectMapper objectMapper,
            List<AgenticAuthoringPresentationAffordanceCatalog> catalogs) {
        this(objectMapper, new AgenticAuthoringPresentationAffordanceCatalogService(objectMapper, catalogs));
    }

    public static AgenticAuthoringResourceBackedPresentationAffordanceProvider defaultProvider(
            ObjectMapper objectMapper) {
        return new AgenticAuthoringResourceBackedPresentationAffordanceProvider(
                objectMapper,
                AgenticAuthoringPresentationAffordanceCatalogService.defaultService(objectMapper));
    }

    @Override
    public boolean supports(PresentationAffordanceDiscoveryToolRequest request) {
        return catalogService.catalog(componentId(request)).isPresent();
    }

    @Override
    public JsonNode discover(PresentationAffordanceDiscoveryToolRequest request) {
        AgenticAuthoringPresentationAffordanceCatalog catalog = catalogService.catalog(componentId(request)).orElse(null);
        if (catalog == null) {
            return objectMapper.nullNode();
        }
        String dataType = normalizeType(firstNonBlank(request.outputType(), request.dataType(), request.inferredType()));
        String targetKind = firstNonBlank(request.targetKind(), catalog.defaultTargetKind());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("version", catalog.version());
        payload.put("kind", "praxis.ai-authoring.presentation-affordance-discovery");
        payload.put("componentId", catalog.componentId());
        payload.put("targetKind", targetKind);
        payload.put("targetField", safeText(firstNonBlank(request.targetField(), request.columnField())));
        payload.put("dataType", dataType);
        payload.put("requiresTypeConfirmation", "unknown".equals(dataType));
        payload.put("sourceRef", catalog.sourceRef());
        ArrayNode affordances = payload.putArray("affordances");
        catalog.compatibleAffordances(targetKind, dataType)
                .forEach(affordance -> addAffordance(affordances, affordance));
        return payload;
    }

    private void addAffordance(
            ArrayNode affordances,
            AgenticAuthoringPresentationAffordanceCatalog.PresentationAffordance source) {
        ObjectNode affordance = affordances.addObject();
        affordance.put("id", source.id());
        affordance.put("category", source.category());
        affordance.put("description", source.description());
        ArrayNode optionArray = affordance.putArray("options");
        source.options().forEach(optionArray::add);
        ArrayNode appliesTo = affordance.putArray("appliesToTypes");
        source.appliesToTypes().forEach(appliesTo::add);
    }

    private String componentId(PresentationAffordanceDiscoveryToolRequest request) {
        return request == null ? "" : safeText(firstNonBlank(request.targetComponentId(), request.componentId()));
    }

    private String normalizeType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "unknown";
        }
        if ("text".equals(normalized)) {
            return "string";
        }
        if ("datetime".equals(normalized) || "date-time".equals(normalized)) {
            return "date";
        }
        if ("integer".equals(normalized) || "decimal".equals(normalized) || "float".equals(normalized)
                || "double".equals(normalized)) {
            return "number";
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
