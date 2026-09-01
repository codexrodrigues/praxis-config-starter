package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Metrics;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.DomainCatalogReleaseChangedEvent;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@ConditionalOnBean(DomainCatalogIngestionService.class)
public class DomainCatalogPromptContextService {

    private static final int DEFAULT_LIMIT = 12;
    private static final int MAX_LIMIT = 30;
    private static final long DEFAULT_RESOURCE_IDENTITY_CACHE_TTL_MS = 300_000L;
    private static final int DEFAULT_RESOURCE_IDENTITY_CACHE_MAX_ENTRIES = 256;

    private final DomainCatalogIngestionService domainCatalogIngestionService;
    private final DomainFederationQueryService domainFederationQueryService;
    private final String defaultServiceKey;
    private final long resourceIdentityCacheTtlMs;
    private final int resourceIdentityCacheMaxEntries;
    private final LongSupplier currentTimeMillis;
    private final ConcurrentHashMap<ResourceIdentityCacheKey, ResourceIdentityCacheEntry> resourceIdentityCache =
            new ConcurrentHashMap<>();

    public DomainCatalogPromptContextService(DomainCatalogIngestionService domainCatalogIngestionService) {
        this(domainCatalogIngestionService, null, "praxis-service", DEFAULT_RESOURCE_IDENTITY_CACHE_TTL_MS,
                DEFAULT_RESOURCE_IDENTITY_CACHE_MAX_ENTRIES, System::currentTimeMillis);
    }

    public DomainCatalogPromptContextService(
            DomainCatalogIngestionService domainCatalogIngestionService,
            DomainFederationQueryService domainFederationQueryService) {
        this(domainCatalogIngestionService, domainFederationQueryService, "praxis-service",
                DEFAULT_RESOURCE_IDENTITY_CACHE_TTL_MS, DEFAULT_RESOURCE_IDENTITY_CACHE_MAX_ENTRIES,
                System::currentTimeMillis);
    }

    @Autowired
    public DomainCatalogPromptContextService(
            DomainCatalogIngestionService domainCatalogIngestionService,
            DomainFederationQueryService domainFederationQueryService,
            @Value("${praxis.domain-catalog.service-key:praxis-service}") String defaultServiceKey,
            @Value("${praxis.domain-catalog.prompt-context.resource-identity-cache-ttl-ms:300000}") long cacheTtlMs,
            @Value("${praxis.domain-catalog.prompt-context.resource-identity-cache-max-entries:256}") int cacheMaxEntries) {
        this(domainCatalogIngestionService, domainFederationQueryService, defaultServiceKey, cacheTtlMs,
                cacheMaxEntries, System::currentTimeMillis);
    }

    DomainCatalogPromptContextService(
            DomainCatalogIngestionService domainCatalogIngestionService,
            DomainFederationQueryService domainFederationQueryService,
            String defaultServiceKey,
            long cacheTtlMs,
            int cacheMaxEntries,
            LongSupplier currentTimeMillis) {
        this.domainCatalogIngestionService = domainCatalogIngestionService;
        this.domainFederationQueryService = domainFederationQueryService;
        this.defaultServiceKey = defaultServiceKey;
        this.resourceIdentityCacheTtlMs = Math.max(1L, cacheTtlMs);
        this.resourceIdentityCacheMaxEntries = Math.max(1, cacheMaxEntries);
        this.currentTimeMillis = currentTimeMillis;
    }

    public String buildPromptContext(
            String userPrompt,
            JsonNode contextHints,
            String tenantId,
            String environment) {
        DomainCatalogRequest request = resolveRequest(userPrompt, contextHints);
        if (request == null) {
            return "";
        }
        if (request.relationships() != null && request.relationships().federated() && domainFederationQueryService != null) {
            return formatFederatedContext(request, tenantId, environment);
        }
        String contextBlock = formatContext(request, tenantId, environment);
        String relationshipBlock = formatRelationships(request.relationships(), tenantId, environment);
        return appendOptionalPromptBlock(contextBlock, relationshipBlock);
    }

    /**
     * Projects only the canonical business-resource identities of the current host. This compact
     * index is baseline semantic context for the LLM: it avoids scanning endpoints while leaving
     * fields, capabilities, bindings and live data to the progressive grounding tools.
     */
    public String buildResourceIdentityContext(
            String tenantId,
            String environment,
            int limit) {
        int effectiveLimit = clampLimit(limit);
        ResourceIdentityCacheKey cacheKey = new ResourceIdentityCacheKey(
                normalizeScope(tenantId), normalizeScope(environment), defaultServiceKey, effectiveLimit);
        long now = currentTimeMillis.getAsLong();
        ResourceIdentityCacheEntry cached = resourceIdentityCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > now) {
            Metrics.counter("domain_catalog_prompt_context_cache_total", "status", "hit").increment();
            return cached.context();
        }
        if (cached != null) {
            resourceIdentityCache.remove(cacheKey, cached);
        }
        Metrics.counter("domain_catalog_prompt_context_cache_total", "status", "miss").increment();
        try {
            DomainCatalogContextResponse context = domainCatalogIngestionService.contextLatest(
                    defaultServiceKey,
                    tenantId,
                    environment,
                    "node",
                    null,
                    "concept",
                    null,
                    effectiveLimit);
            if (context == null || context.items() == null || context.items().isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder("DOMAIN_RESOURCE_IDENTITY_CATALOG\nitems:\n");
            for (DomainCatalogItemResponse item : context.items()) {
                JsonNode payload = item.payload();
                String resourceKey = firstText(
                        text(payload == null ? null : payload.path("metadata"), "resourceKey"),
                        text(payload, "resourceKey"),
                        item.itemKey());
                if (!StringUtils.hasText(resourceKey)) {
                    continue;
                }
                builder.append("- resourceKey=")
                        .append(resourceKey)
                        .append(" | label=")
                        .append(label(item));
                String description = firstText(
                        text(payload, "description"),
                        text(payload == null ? null : payload.path("businessGlossary"), "description"));
                if (StringUtils.hasText(description)) {
                    builder.append(" | description=").append(compact(description, 220));
                }
                builder.append('\n');
            }
            String promptContext = builder.toString().trim();
            cacheResourceIdentityContext(cacheKey, promptContext, now);
            return promptContext;
        } catch (RuntimeException ex) {
            log.debug(
                    "Could not build canonical domain resource identity context for tenant={}, environment={}: {}",
                    tenantId,
                    environment,
                    ex.getClass().getSimpleName());
            return "";
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDomainCatalogReleaseChanged(DomainCatalogReleaseChangedEvent event) {
        String tenantId = normalizeScope(event.tenantId());
        String environment = normalizeScope(event.environment());
        long removed = resourceIdentityCache.keySet().stream()
                .filter(key -> key.tenantId().equals(tenantId) && key.environment().equals(environment))
                .filter(key -> resourceIdentityCache.remove(key) != null)
                .count();
        if (removed > 0) {
            Metrics.counter("domain_catalog_prompt_context_cache_total", "status", "invalidated").increment(removed);
        }
    }

    private void cacheResourceIdentityContext(
            ResourceIdentityCacheKey cacheKey,
            String promptContext,
            long now) {
        if (!StringUtils.hasText(promptContext)) {
            return;
        }
        resourceIdentityCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (resourceIdentityCache.size() >= resourceIdentityCacheMaxEntries && !resourceIdentityCache.containsKey(cacheKey)) {
            resourceIdentityCache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAtMillis()))
                    .ifPresent(entry -> resourceIdentityCache.remove(entry.getKey(), entry.getValue()));
        }
        resourceIdentityCache.put(cacheKey, new ResourceIdentityCacheEntry(
                promptContext, now, now + resourceIdentityCacheTtlMs));
    }

    private static String normalizeScope(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record ResourceIdentityCacheKey(String tenantId, String environment, String serviceKey, int limit) {
    }

    private record ResourceIdentityCacheEntry(String context, long createdAtMillis, long expiresAtMillis) {
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
                text(contextHints, "serviceKey"),
                defaultServiceKey);
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
        String resourceKey = firstText(text(domainCatalog, "resourceKey"), text(contextHints, "domainResourceKey"));
        String contextKey = firstText(text(domainCatalog, "contextKey"), text(contextHints, "domainContextKey"));
        String nodeType = firstText(text(domainCatalog, "nodeType"), text(contextHints, "domainNodeType"));
        String policyProfile = firstText(text(domainCatalog, "policyProfile"), text(contextHints, "domainPolicyProfile"));
        int limit = clampLimit(domainCatalog != null && domainCatalog.has("limit")
                ? domainCatalog.path("limit").asInt(DEFAULT_LIMIT)
                : DEFAULT_LIMIT);
        RelationshipRequest relationships = resolveRelationshipRequest(domainCatalog, serviceKey, query);

        return new DomainCatalogRequest(
                serviceKey,
                resourceKey,
                itemType,
                contextKey,
                nodeType,
                query,
                limit,
                policyProfile,
                relationships);
    }

    private RelationshipRequest resolveRelationshipRequest(JsonNode domainCatalog, String serviceKey, String query) {
        JsonNode relationships = objectNode(domainCatalog != null ? domainCatalog.get("relationships") : null);
        if (relationships == null || (relationships.has("enabled") && !relationships.path("enabled").asBoolean(true))) {
            return null;
        }
        boolean federated = relationships.path("federated").asBoolean(false);
        String relationshipServiceKey = federated ? null : firstText(text(relationships, "serviceKey"), serviceKey);
        String relationshipQuery = firstText(text(relationships, "query"), text(relationships, "q"), query);
        int relationshipLimit = clampLimit(relationships.has("limit")
                ? relationships.path("limit").asInt(DEFAULT_LIMIT)
                : DEFAULT_LIMIT);
        return new RelationshipRequest(
                relationshipServiceKey,
                text(relationships, "sourceNodeKey"),
                text(relationships, "targetNodeKey"),
                text(relationships, "edgeType"),
                relationshipQuery,
                relationshipLimit,
                text(relationships, "policyProfile"),
                federated);
    }

    private String formatFederatedContext(DomainCatalogRequest request, String tenantId, String environment) {
        try {
            RelationshipRequest relationships = request.relationships();
            DomainFederationContextQueryResponse response = domainFederationQueryService.context(
                    null,
                    request.resourceKey(),
                    tenantId,
                    environment,
                    request.itemType(),
                    request.contextKey(),
                    request.nodeType(),
                    relationships == null ? null : relationships.edgeType(),
                    request.query(),
                    Math.max(request.limit(), relationships == null ? 0 : relationships.limit()),
                    new DomainFederationRetrievalPolicyOptions(policyProfile(request), null, null, null));
            String contextBlock = formatContext(response.context());
            String relationshipBlock = formatFederatedRelationships(response);
            String contractBlock = formatFederatedArtifacts("DOMAIN_FEDERATION_CONTRACTS", response.contracts(), "contract");
            String resolutionBlock = formatFederatedArtifacts("DOMAIN_FEDERATION_RESOLUTIONS", response.resolutions(), "resolution");
            String policyBlock = formatPolicyReport(response.policyReport());
            return appendOptionalPromptBlock(
                    appendOptionalPromptBlock(
                            appendOptionalPromptBlock(
                                    appendOptionalPromptBlock(contextBlock, relationshipBlock),
                                    contractBlock),
                            resolutionBlock),
                    policyBlock);
        } catch (RuntimeException ex) {
            log.warn("Could not build federated domain catalog prompt context: {}", ex.getMessage());
            return "";
        }
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

    private String formatContext(DomainCatalogRequest request, String tenantId, String environment) {
        try {
            DomainCatalogContextResponse context = domainCatalogIngestionService.contextLatestSemantic(
                    request.serviceKey(),
                    request.resourceKey(),
                    tenantId,
                    environment,
                    request.itemType(),
                    request.contextKey(),
                    request.nodeType(),
                    request.query(),
                    request.limit());
            if (context != null && context.items() != null && !context.items().isEmpty()) {
                return formatContext(context);
            }
            context = StringUtils.hasText(request.resourceKey())
                    ? domainCatalogIngestionService.contextLatest(
                            request.serviceKey(),
                            request.resourceKey(),
                            tenantId,
                            environment,
                            request.itemType(),
                            request.contextKey(),
                            request.nodeType(),
                            request.query(),
                            request.limit())
                    : domainCatalogIngestionService.contextLatest(
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
                    "Could not build domain catalog prompt context for serviceKey={}, resourceKey={}, tenant={}, environment={}: {}",
                    request.serviceKey(),
                    request.resourceKey(),
                    tenantId,
                    environment,
                    ex.getMessage());
            return "";
        }
    }

    private String formatRelationships(RelationshipRequest request, String tenantId, String environment) {
        if (request == null) {
            return "";
        }
        try {
            List<DomainCatalogItemResponse> relationships = domainCatalogIngestionService.relationshipsLatest(
                    request.serviceKey(),
                    tenantId,
                    environment,
                    request.sourceNodeKey(),
                    request.targetNodeKey(),
                    request.edgeType(),
                    request.query(),
                    request.limit());
            if (relationships == null || relationships.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            builder.append("DOMAIN_CATALOG_RELATIONSHIPS\n");
            if (request.federated()) {
                builder.append("federated: true\n");
            } else {
                appendLine(builder, "serviceKey", request.serviceKey());
            }
            appendLine(builder, "query", request.query());
            appendLine(builder, "sourceNodeKey", request.sourceNodeKey());
            appendLine(builder, "targetNodeKey", request.targetNodeKey());
            appendLine(builder, "edgeType", request.edgeType());
            builder.append("items:\n");
            for (DomainCatalogItemResponse item : relationships) {
                builder.append("- [edge/")
                        .append(nullToDash(item.edgeType()))
                        .append("] ")
                        .append(label(item))
                        .append(" (")
                        .append(item.itemKey())
                        .append(")");
                appendPayloadSummary(builder, item.payload());
                builder.append('\n');
            }
            return builder.toString().trim();
        } catch (RuntimeException ex) {
            log.warn("Could not build domain catalog relationship prompt context: {}", ex.getMessage());
            return "";
        }
    }

    private String formatFederatedRelationships(DomainFederationContextQueryResponse response) {
        if (response == null || response.relationships() == null || response.relationships().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("DOMAIN_CATALOG_RELATIONSHIPS\n");
        builder.append("federated: true\n");
        appendLine(builder, "sourceMode", response.sourceMode());
        appendLine(builder, "query", response.query());
        appendLine(builder, "relationshipType", response.relationshipType());
        builder.append("items:\n");
        for (DomainCatalogItemResponse item : response.relationships()) {
            builder.append("- [edge/")
                    .append(nullToDash(item.edgeType()))
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

    private String formatFederatedArtifacts(String blockName, List<DomainCatalogItemResponse> items, String defaultType) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(blockName).append('\n');
        builder.append("items:\n");
        for (DomainCatalogItemResponse item : items) {
            builder.append("- [")
                    .append(nullToDash(item.itemType()))
                    .append("/")
                    .append(nullToDash(StringUtils.hasText(item.nodeType()) ? item.nodeType() : defaultType))
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

    private String formatPolicyReport(DomainFederationRetrievalPolicyReport report) {
        if (report == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("DOMAIN_FEDERATION_POLICY\n");
        appendLine(builder, "policyProfile", report.policyProfile());
        builder.append("minConfidence: ").append(report.minConfidence()).append('\n');
        builder.append("includeDenied: ").append(report.includeDenied()).append('\n');
        builder.append("includeLowConfidence: ").append(report.includeLowConfidence()).append('\n');
        if (report.decisions() != null && !report.decisions().isEmpty()) {
            builder.append("decisions:\n");
            report.decisions().forEach(decision -> builder.append("- ").append(decision).append('\n'));
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
        appendInline(builder, "surfaceId", text(payload.path("metadata"), "surfaceId"));
        appendInline(builder, "resourceKey", text(payload.path("metadata"), "resourceKey"));
        appendInline(builder, "surfaceKind", text(payload.path("metadata"), "kind"));
        appendInline(builder, "surfaceScope", text(payload.path("metadata"), "scope"));
        appendInline(builder, "targetId", text(payload.path("target"), "id"));
        appendInline(builder, "targetResourceKey", text(payload.path("target"), "resourceKey"));
        appendInline(builder, "binding", text(payload, "bindingType"));
        appendInline(builder, "edge", text(payload, "edgeType"));
        appendInline(builder, "sourceNodeKey", text(payload, "sourceNodeKey"));
        appendInline(builder, "targetNodeKey", text(payload, "targetNodeKey"));
        appendInline(builder, "source", text(payload, "source"));
        appendInline(builder, "semanticOwner", text(payload, "semanticOwner"));
        appendInline(builder, "lifecycle", text(payload, "lifecycle"));
        appendInline(builder, "businessGlossary", objectSummary(payload.path("businessGlossary")));
        appendInline(builder, "resolution", objectSummary(payload.path("resolution")));
        appendInline(builder, "sourceEvidenceKeys", textArray(payload.path("sourceEvidenceKeys")));
        appendInline(builder, "alias", text(payload, "alias"));
        appendInline(builder, "aliasType", text(payload, "aliasType"));
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
        if (StringUtils.hasText(payloadLabel)) {
            return payloadLabel;
        }
        String alias = text(item.payload(), "alias");
        if (StringUtils.hasText(alias)) {
            return alias;
        }
        String summary = text(item.payload(), "summary");
        if (StringUtils.hasText(summary)) {
            return summary;
        }
        return item.itemKey();
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

    private String objectSummary(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        node.fields().forEachRemaining(field -> {
            String value = scalarSummary(field.getValue());
            if (StringUtils.hasText(value)) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(field.getKey()).append('=').append(value);
            }
        });
        return builder.length() == 0 ? null : builder.toString();
    }

    private String scalarSummary(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return StringUtils.hasText(node.asText()) ? node.asText() : null;
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            return textArray(node);
        }
        return null;
    }

    private int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private String appendOptionalPromptBlock(String base, String extra) {
        if (!StringUtils.hasText(base)) {
            return StringUtils.hasText(extra) ? extra : "";
        }
        if (!StringUtils.hasText(extra)) {
            return base;
        }
        return base + "\n\n" + extra;
    }

    private String compact(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, maxLength).trim();
    }

    private String nullToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private String policyProfile(DomainCatalogRequest request) {
        String relationshipProfile = request.relationships() == null ? null : request.relationships().policyProfile();
        return firstText(relationshipProfile, request.policyProfile(), "authoring");
    }

    private record DomainCatalogRequest(
            String serviceKey,
            String resourceKey,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit,
            String policyProfile,
            RelationshipRequest relationships) {
    }

    private record RelationshipRequest(
            String serviceKey,
            String sourceNodeKey,
            String targetNodeKey,
            String edgeType,
            String query,
            int limit,
            String policyProfile,
            boolean federated) {
    }
}
