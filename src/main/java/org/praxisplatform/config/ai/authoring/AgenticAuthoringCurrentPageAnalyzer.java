package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public class AgenticAuthoringCurrentPageAnalyzer {

    private static final String WIDGET_DYNAMIC_FORM = "praxis-dynamic-form";

    private final ObjectMapper objectMapper;

    public AgenticAuthoringCurrentPageAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public JsonNode summarize(JsonNode currentPage) {
        ObjectNode summary = objectMapper.createObjectNode();
        JsonNode widgets = widgets(currentPage);
        summary.put("widgetsCount", widgets.size());
        ArrayNode forms = summary.putArray("formWidgets");
        for (JsonNode widget : widgets) {
            String componentId = text(widget.path("definition"), "id");
            if (!WIDGET_DYNAMIC_FORM.equals(componentId)) {
                continue;
            }
            JsonNode inputs = widget.path("definition").path("inputs");
            ObjectNode form = forms.addObject();
            form.put("widgetKey", text(widget, "key"));
            form.put("componentId", componentId);
            form.put("formId", text(inputs, "formId"));
            form.put("resourcePath", text(inputs, "resourcePath"));
            form.put("schemaUrl", text(inputs, "schemaUrl"));
            form.put("submitUrl", text(inputs, "submitUrl"));
            form.put("submitMethod", text(inputs, "submitMethod"));
        }
        return summary;
    }

    public AgenticAuthoringTarget resolveTarget(JsonNode currentPage, String selectedWidgetKey) {
        JsonNode selected = selectedWidget(currentPage, selectedWidgetKey);
        if (selected.isMissingNode()) {
            selected = firstFormWidget(currentPage);
        }
        if (selected.isMissingNode()) {
            return null;
        }
        JsonNode inputs = selected.path("definition").path("inputs");
        return new AgenticAuthoringTarget(
                text(selected, "key"),
                text(selected.path("definition"), "id"),
                resolveResourcePath(inputs),
                text(inputs, "schemaUrl"),
                text(inputs, "submitUrl"),
                text(inputs, "submitMethod")
        );
    }

    private JsonNode selectedWidget(JsonNode currentPage, String selectedWidgetKey) {
        if (selectedWidgetKey == null || selectedWidgetKey.isBlank()) {
            return MissingNode.getInstance();
        }
        for (JsonNode widget : widgets(currentPage)) {
            if (selectedWidgetKey.equals(text(widget, "key"))) {
                return widget;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode firstFormWidget(JsonNode currentPage) {
        for (JsonNode widget : widgets(currentPage)) {
            if (WIDGET_DYNAMIC_FORM.equals(text(widget.path("definition"), "id"))) {
                return widget;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode widgets(JsonNode currentPage) {
        JsonNode widgets = currentPage == null ? MissingNode.getInstance() : currentPage.path("widgets");
        return widgets.isArray() ? widgets : objectMapper.createArrayNode();
    }

    private String resolveResourcePath(JsonNode inputs) {
        String resourcePath = text(inputs, "resourcePath");
        if (!resourcePath.isBlank()) {
            return normalizeResourcePath(resourcePath);
        }
        String schemaUrl = text(inputs, "schemaUrl");
        int marker = schemaUrl.indexOf("path=");
        if (marker < 0) {
            return "";
        }
        String value = schemaUrl.substring(marker + 5);
        int end = value.indexOf('&');
        if (end >= 0) {
            value = value.substring(0, end);
        }
        return normalizeResourcePath(value.replace("%2F", "/").replace("%2f", "/"));
    }

    private String normalizeResourcePath(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return normalized.isBlank() ? "" : "/" + normalized;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : value.asText("").trim();
    }
}
