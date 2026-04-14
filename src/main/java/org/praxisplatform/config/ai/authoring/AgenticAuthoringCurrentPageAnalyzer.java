package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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
            summarizeFields(inputs, form);
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
        String componentId = text(selected.path("definition"), "id");
        return new AgenticAuthoringTarget(
                text(selected, "key"),
                componentId,
                resolveResourcePath(inputs),
                text(inputs, "schemaUrl"),
                text(inputs, "submitUrl"),
                resolveSubmitMethod(componentId, inputs)
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
        String chartDataSourceResourcePath = text(inputs.path("config").path("dataSource"), "resourcePath");
        if (!chartDataSourceResourcePath.isBlank()) {
            return normalizeResourcePath(chartDataSourceResourcePath);
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

    private String resolveSubmitMethod(String componentId, JsonNode inputs) {
        String submitMethod = text(inputs, "submitMethod");
        if (!submitMethod.isBlank()) {
            return submitMethod;
        }
        if ("praxis-table".equals(componentId) && !text(inputs, "resourcePath").isBlank()) {
            return "get";
        }
        if ("praxis-chart".equals(componentId)
                && !text(inputs.path("config").path("dataSource"), "resourcePath").isBlank()) {
            return "get";
        }
        return "";
    }

    private String normalizeResourcePath(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("/")) {
            return normalized;
        }
        return normalized.isBlank() ? "" : "/" + normalized;
    }

    private void summarizeFields(JsonNode inputs, ObjectNode form) {
        JsonNode config = inputs.path("config");
        JsonNode fieldMetadata = config.path("fieldMetadata");
        ArrayNode fieldNames = form.putArray("fieldNames");
        ArrayNode localFieldNames = form.putArray("localFieldNames");
        ArrayNode serverBackedOverrideNames = form.putArray("serverBackedOverrideNames");
        ArrayNode fieldSummaries = form.putArray("fieldMetadata");
        Set<String> seenFieldNames = new LinkedHashSet<>();
        Set<String> seenLocalNames = new LinkedHashSet<>();
        Set<String> seenServerBackedNames = new LinkedHashSet<>();

        if (fieldMetadata.isArray()) {
            for (JsonNode field : fieldMetadata) {
                String name = text(field, "name");
                if (name.isBlank()) {
                    continue;
                }
                appendUnique(fieldNames, seenFieldNames, name);
                boolean local = isLocalTransientField(field);
                if (local) {
                    appendUnique(localFieldNames, seenLocalNames, name);
                } else {
                    appendUnique(serverBackedOverrideNames, seenServerBackedNames, name);
                }

                ObjectNode fieldSummary = fieldSummaries.addObject();
                fieldSummary.put("name", name);
                putTextIfPresent(fieldSummary, "label", field);
                putTextIfPresent(fieldSummary, "controlType", field);
                putTextIfPresent(fieldSummary, "source", field);
                if (field.has("transient")) {
                    fieldSummary.put("transient", field.path("transient").asBoolean(false));
                }
                putTextIfPresent(fieldSummary, "submitPolicy", field);
                if (field.has("required")) {
                    fieldSummary.put("required", field.path("required").asBoolean(false));
                }
                if (field.has("formHidden")) {
                    fieldSummary.put("formHidden", field.path("formHidden").asBoolean(false));
                }
            }
        }

        ArrayNode layoutFieldNames = form.putArray("layoutFieldNames");
        Set<String> seenLayoutNames = new LinkedHashSet<>();
        collectLayoutFieldNames(config.path("sections"), layoutFieldNames, seenLayoutNames);
        form.put("fieldCount", seenFieldNames.size());
        form.put("localFieldCount", seenLocalNames.size());
    }

    private void collectLayoutFieldNames(JsonNode sections, ArrayNode target, Set<String> seen) {
        if (!sections.isArray()) {
            return;
        }
        for (JsonNode section : sections) {
            JsonNode rows = section.path("rows");
            if (!rows.isArray()) {
                continue;
            }
            for (JsonNode row : rows) {
                JsonNode columns = row.path("columns");
                if (!columns.isArray()) {
                    continue;
                }
                for (JsonNode column : columns) {
                    JsonNode fields = column.path("fields");
                    if (!fields.isArray()) {
                        continue;
                    }
                    for (JsonNode field : fields) {
                        String name = field.isTextual() ? field.asText().trim() : text(field, "name");
                        appendUnique(target, seen, name);
                    }
                }
            }
        }
    }

    private boolean isLocalTransientField(JsonNode field) {
        String source = text(field, "source");
        String submitPolicy = text(field, "submitPolicy");
        return "local".equalsIgnoreCase(source)
                || field.path("transient").asBoolean(false)
                || "omit".equalsIgnoreCase(submitPolicy);
    }

    private void appendUnique(ArrayNode target, Set<String> seen, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && seen.add(normalized)) {
            target.add(normalized);
        }
    }

    private void putTextIfPresent(ObjectNode target, String fieldName, JsonNode source) {
        String value = text(source, fieldName);
        if (!value.isBlank()) {
            target.put(fieldName, value);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : value.asText("").trim();
    }
}
