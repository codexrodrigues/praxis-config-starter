package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(DomainCatalogIngestionService.class)
public class DomainCatalogPromptContextService {

    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 30;

    private final DomainCatalogIngestionService domainCatalogIngestionService;

    public String buildPromptContext(
            String userPrompt,
            JsonNode contextHints,
            String tenantId,
            String environment) {
        DomainCatalogRequest request = resolveRequest(userPrompt, contextHints);
        if (request == null) {
            return "";
        }
        try {
            DomainCatalogContextResponse context = domainCatalogIngestionService.contextLatest(
                    request.serviceKey(),
                    tenantId,
                    environment,
                    request.itemType(),
                    request.contextKey(),
                    request.nodeType(),
                    request.query(),
                    request.limit());
            return formatContext(context);
        } catch (RuntimeException ex) {
            log.warn(
                    "Could not build domain catalog prompt context for serviceKey={}: {}",
                    request.serviceKey(),
                    ex.getMessage());
            return "";
        }
    }

    private DomainCatalogRequest resolveRequest(String userPrompt, JsonNode contextHints) {
        JsonNode domainCatalog = objectNode(contextHints != null ? contextHints.get("domainCatalog") : null);
        if (domainCatalog == null && !hasText(contextHints, "domainCatalogServiceKey")) {
            return null;
        }
        if (domainCatalog != null && domainCatalog.has("enabled") && !domainCatalog.path("enabled").asBoolean(true)) {
            return null;
        }

        String serviceKey = firstText(
                text(domainCatalog, "serviceKey"),
                text(contextHints, "domainCatalogServiceKey"),
                text(contextHints, "domainServiceKey"),
                text(contextHints, "serviceKey"));
        if (!StringUtils.hasText(serviceKey)) {
            return null;
        }

        String query = firstText(
                text(domainCatalog, "query"),
                text(domainCatalog, "q"),
                text(contextHints, "domainCatalogQuery"),
                text(contextHints, "retrievalQuery"),
                userPrompt);
        String itemType = firstText(text(domainCatalog, "type"), text(domainCatalog, "itemType"), "node");
        String contextKey = firstText(text(domainCatalog, "contextKey"), text(contextHints, "domainContextKey"));
        String nodeType = firstText(text(domainCatalog, "nodeType"), text(contextHints, "domainNodeType"));
        int limit = clampLimit(domainCatalog != null && domainCatalog.has("limit")
                ? domainCatalog.path("limit").asInt(DEFAULT_LIMIT)
                : DEFAULT_LIMIT);

        return new DomainCatalogRequest(
                serviceKey,
                itemType,
                contextKey,
                nodeType,
                query,
                limit);
    }

    private String formatContext(DomainCatalogContextResponse context) {
        if (context == null || context.items() == null || context.items().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("DOMAIN_CATALOG_CONTEXT\n");
        builder.append("schemaVersion: ").append(context.schemaVersion()).append('\n');
        if (context.release() != null) {
            builder.append("releaseKey: ").append(context.release().releaseKey()).append('\n');
            builder.append("serviceKey: ").append(context.release().serviceKey()).append('\n');
        }
        appendLine(builder, "query", context.query());
        appendLine(builder, "itemType", context.itemType());
        appendLine(builder, "contextKey", context.contextKey());
        appendLine(builder, "nodeType", context.nodeType());
        appendGuidance(builder, context.retrievalGuidance());
        builder.append("items:\n");
        for (DomainCatalogItemResponse item : context.items()) {
            builder.append("- ")
                    .append("[")
                    .append(nullToDash(item.itemType()))
                    .append("/")
                    .append(nullToDash(item.nodeType()))
                    .append("] ")
                    .append(label(item))
                    .append(" (")
                    .append(item.itemKey())
                    .append(")");
            appendPayloadSummary(builder, item.payload());
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private void appendGuidance(StringBuilder builder, List<String> guidance) {
        if (guidance == null || guidance.isEmpty()) {
            return;
        }
        builder.append("guidance:\n");
        guidance.forEach(item -> builder.append("- ").append(item).append('\n'));
    }

    private void appendPayloadSummary(StringBuilder builder, JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return;
        }
        appendInline(builder, "field", text(payload.path("metadata"), "fieldName"));
        appendInline(builder, "type", text(payload.path("metadata"), "type"));
        appendInline(builder, "binding", text(payload, "bindingType"));
        appendInline(builder, "edge", text(payload, "edgeType"));
        appendInline(builder, "source", text(payload, "source"));
        appendInline(builder, "classification", text(payload, "classification"));
        appendInline(builder, "dataCategory", text(payload, "dataCategory"));
        appendInline(builder, "visibility", text(payload.path("aiUsage"), "visibility"));
        appendInline(builder, "trainingUse", text(payload.path("aiUsage"), "trainingUse"));
        appendInline(builder, "ruleAuthoring", text(payload.path("aiUsage"), "ruleAuthoring"));
        appendInline(builder, "reasoningUse", text(payload.path("aiUsage"), "reasoningUse"));
        appendInline(builder, "complianceTags", textArray(payload.path("complianceTags")));
        if (payload.path("metadata").has("required")) {
            appendInline(builder, "required", Boolean.toString(payload.path("metadata").path("required").asBoolean()));
        }
    }

    private String label(DomainCatalogItemResponse item) {
        String payloadLabel = text(item.payload(), "label");
        return StringUtils.hasText(payloadLabel) ? payloadLabel : item.itemKey();
    }

    private void appendLine(StringBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(name).append(": ").append(value).append('\n');
        }
    }

    private void appendInline(StringBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(" | ").append(name).append("=").append(value);
        }
    }

    private JsonNode objectNode(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }

    private boolean hasText(JsonNode node, String fieldName) {
        return StringUtils.hasText(text(node, fieldName));
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !node.isObject() || !node.has(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() && StringUtils.hasText(value.asText())
                ? value.asText()
                : null;
    }

    private String textArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        node.forEach(item -> {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(item.asText());
            }
        });
        return builder.length() == 0 ? null : builder.toString();
    }

    private int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private String nullToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private record DomainCatalogRequest(
            String serviceKey,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
    }
}
