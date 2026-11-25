package org.praxisplatform.config.service;

import static org.praxisplatform.config.domain.DomainRuleDefinition.AUTHORIZATION_POLICY_RULE_TYPE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleCatalogSummaryResponse;
import org.praxisplatform.config.repository.DomainRuleChangeWorkspaceRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;

/** Scoped, bounded and redacted catalog of governed domain decisions. */
public class DomainRuleCatalogQueryService {

    public static final int MAX_LIMIT = 12;
    public static final int MAX_PAGE = 100;
    public static final int MAX_BROWSE_LIMIT = 100;
    public static final int MAX_BROWSE_PAGE = 500;
    public static final int DEFAULT_BROWSE_LIMIT = 50;
    /** Public bound of the catalog query so programmatic callers can honour the same contract. */
    public static final int MAX_QUERY_LENGTH = 256;
    private static final int MAX_LABEL_LENGTH = 255;

    private final DomainRuleDefinitionRepository definitions;
    private final DomainRuleChangeWorkspaceRepository workspaces;
    private final ObjectMapper objectMapper;

    public DomainRuleCatalogQueryService(DomainRuleDefinitionRepository definitions) {
        this(definitions, null, new ObjectMapper());
    }

    public DomainRuleCatalogQueryService(
            DomainRuleDefinitionRepository definitions,
            DomainRuleChangeWorkspaceRepository workspaces,
            ObjectMapper objectMapper) {
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
        this.workspaces = workspaces;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public DomainRuleCatalogResponse search(
            String query,
            String ruleType,
            String status,
            String resourceKey,
            Integer requestedPage,
            Integer requestedLimit,
            DomainRuleGovernancePrincipal principal) {
        requirePrincipal(principal);
        int page = requestedPage == null ? 0 : requestedPage;
        int limit = requestedLimit == null ? 6 : requestedLimit;
        if (page < 0 || page > MAX_PAGE) {
            throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        // PostgreSQL cannot infer a text type for a null parameter used inside lower/concat
        // and binds it as bytea. The empty string is the repository's explicit no-filter sentinel.
        String normalizedQuery = Objects.requireNonNullElse(optional(query, MAX_QUERY_LENGTH, "query"), "");
        Page<DomainRuleDefinition> result = definitions.searchCatalogCandidates(
                principal.tenantId().trim(),
                principal.environment().trim(),
                normalizedQuery,
                optional(ruleType, 64, "ruleType"),
                optional(status, 32, "status"),
                optional(resourceKey, 255, "resourceKey"),
                PageRequest.of(page, limit));
        return toCatalogResponse(result, page, limit, MAX_PAGE);
    }

    /** Enterprise workstation browse projection supporting higher page density and optional context filtering. */
    public DomainRuleCatalogResponse browse(
            String query,
            String ruleType,
            String status,
            String resourceKey,
            String contextKey,
            Integer requestedPage,
            Integer requestedLimit,
            DomainRuleGovernancePrincipal principal) {
        requirePrincipal(principal);
        int page = requestedPage == null ? 0 : requestedPage;
        int limit = requestedLimit == null ? DEFAULT_BROWSE_LIMIT : requestedLimit;
        if (page < 0 || page > MAX_BROWSE_PAGE) {
            throw new IllegalArgumentException("page must be between 0 and " + MAX_BROWSE_PAGE);
        }
        if (limit < 1 || limit > MAX_BROWSE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BROWSE_LIMIT);
        }
        String normalizedQuery = Objects.requireNonNullElse(optional(query, MAX_QUERY_LENGTH, "query"), "");
        String normalizedRuleType = optional(ruleType, 64, "ruleType");
        String normalizedStatus = optional(status, 32, "status");
        String normalizedResourceKey = optional(resourceKey, 255, "resourceKey");
        String normalizedContextKey = optional(contextKey, 255, "contextKey");
        Page<DomainRuleDefinition> result = normalizedContextKey == null
                ? definitions.searchCatalogCandidates(
                        principal.tenantId().trim(),
                        principal.environment().trim(),
                        normalizedQuery,
                        normalizedRuleType,
                        normalizedStatus,
                        normalizedResourceKey,
                        PageRequest.of(page, limit))
                : definitions.searchCatalogCandidatesByContext(
                        principal.tenantId().trim(),
                        principal.environment().trim(),
                        normalizedQuery,
                        normalizedRuleType,
                        normalizedStatus,
                        normalizedResourceKey,
                        normalizedContextKey,
                        PageRequest.of(page, limit));
        return toCatalogResponse(result, page, limit, MAX_BROWSE_PAGE);
    }

    /** Aggregates latest-version portfolio counts and pending review queue in one scoped server pass. */
    public DomainRuleCatalogSummaryResponse summarize(DomainRuleGovernancePrincipal principal) {
        requirePrincipal(principal);
        String tenantId = principal.tenantId().trim();
        String environment = principal.environment().trim();

        List<DomainRuleCatalogSummaryResponse.BucketCount> byRuleType =
                toBuckets(definitions.countLatestByRuleType(tenantId, environment));
        long totalBusinessRules = 0L;
        long totalAuthorizationPolicies = 0L;
        for (DomainRuleCatalogSummaryResponse.BucketCount bucket : byRuleType) {
            if (AUTHORIZATION_POLICY_RULE_TYPE.equalsIgnoreCase(bucket.key())) {
                totalAuthorizationPolicies += bucket.count();
            } else {
                totalBusinessRules += bucket.count();
            }
        }
        List<DomainRuleCatalogSummaryResponse.BucketCount> byContextKey = toBuckets(
                definitions.countLatestBusinessByContextKey(tenantId, environment, AUTHORIZATION_POLICY_RULE_TYPE));
        List<DomainRuleCatalogSummaryResponse.BucketCount> byResourceKey = toBuckets(
                definitions.countLatestBusinessByResourceKey(tenantId, environment, AUTHORIZATION_POLICY_RULE_TYPE));
        List<DomainRuleCatalogSummaryResponse.BucketCount> byDefinitionStatus = toBuckets(
                definitions.countLatestBusinessByStatus(tenantId, environment, AUTHORIZATION_POLICY_RULE_TYPE));
        DomainRuleCatalogSummaryResponse.WorkspaceQueueSummary reviewQueue = summarizeWorkspaceQueue(tenantId, environment);
        return new DomainRuleCatalogSummaryResponse(
                DomainRuleCatalogSummaryResponse.SCHEMA_VERSION,
                totalBusinessRules + totalAuthorizationPolicies,
                totalBusinessRules,
                totalAuthorizationPolicies,
                byContextKey,
                byResourceKey,
                byRuleType,
                byDefinitionStatus,
                reviewQueue);
    }

    private DomainRuleCatalogResponse toCatalogResponse(
            Page<DomainRuleDefinition> result,
            int page,
            int limit,
            int maxPageBound) {
        List<DomainRuleCatalogResponse.Candidate> candidates = result.getContent().stream()
                .map(this::candidate)
                .toList();
        long totalElements = result.getTotalElements();
        int totalPages = result.getTotalPages();
        boolean truncated = (result.hasNext() && page >= maxPageBound) || totalPages > (maxPageBound + 1);
        return new DomainRuleCatalogResponse(
                DomainRuleCatalogResponse.SCHEMA_VERSION,
                candidates,
                page,
                limit,
                result.hasNext(),
                totalElements,
                totalPages,
                truncated);
    }

    private DomainRuleCatalogResponse.Candidate candidate(DomainRuleDefinition definition) {
        JsonNode definitionNode = readDefinitionMetadata(definition.getDefinition());
        String displayName = extractDisplayName(definitionNode);
        String domainLabel = extractScopedLabel(definitionNode, "domainLabel", "domain");
        String ruleSetLabel = extractScopedLabel(definitionNode, "ruleSetLabel", "ruleSet");
        return new DomainRuleCatalogResponse.Candidate(
                definition.getId(),
                definition.getRuleKey(),
                definition.getVersion(),
                definition.getRuleType(),
                definition.getStatus(),
                definition.getContextKey(),
                definition.getResourceKey(),
                definition.getServiceKey(),
                definition.getSemanticOwner(),
                definition.getUpdatedAt(),
                displayName,
                domainLabel,
                ruleSetLabel);
    }

    private DomainRuleCatalogSummaryResponse.WorkspaceQueueSummary summarizeWorkspaceQueue(
            String tenantId,
            String environment) {
        if (workspaces == null) {
            return DomainRuleCatalogSummaryResponse.WorkspaceQueueSummary.empty();
        }
        List<Object[]> rows = workspaces.countByStatus(tenantId, environment);
        if (rows == null || rows.isEmpty()) {
            return DomainRuleCatalogSummaryResponse.WorkspaceQueueSummary.empty();
        }
        long openDrafts = 0L;
        long inReview = 0L;
        long approved = 0L;
        long rejected = 0L;
        long promoted = 0L;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[1] == null) {
                continue;
            }
            String status = row[0] == null ? "" : row[0].toString().trim().toUpperCase(Locale.ROOT);
            long count = ((Number) row[1]).longValue();
            switch (status) {
                case "OPEN", "DRAFT" -> openDrafts += count;
                case "SUBMITTED", "IN_REVIEW" -> inReview += count;
                case "APPROVED" -> approved += count;
                case "REJECTED" -> rejected += count;
                case "PROMOTED" -> promoted += count;
                default -> { }
            }
        }
        long totalPending = openDrafts + inReview + approved + rejected;
        return new DomainRuleCatalogSummaryResponse.WorkspaceQueueSummary(
                openDrafts, inReview, approved, rejected, promoted, totalPending);
    }

    private List<DomainRuleCatalogSummaryResponse.BucketCount> toBuckets(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[1] == null) {
                continue;
            }
            String key = row[0] == null ? "" : row[0].toString().trim();
            long count = ((Number) row[1]).longValue();
            if (count > 0) {
                merged.merge(key, count, Long::sum);
            }
        }
        List<DomainRuleCatalogSummaryResponse.BucketCount> buckets = new ArrayList<>(merged.size());
        merged.forEach((key, count) -> buckets.add(new DomainRuleCatalogSummaryResponse.BucketCount(key, count)));
        buckets.sort(Comparator
                .comparingLong(DomainRuleCatalogSummaryResponse.BucketCount::count).reversed()
                .thenComparing(DomainRuleCatalogSummaryResponse.BucketCount::key));
        return List.copyOf(buckets);
    }

    /** Reads only the non-secret presentation block inside definition JSONB; never parses conditions or governance. */
    private JsonNode readDefinitionMetadata(String rawDefinition) {
        if (!StringUtils.hasText(rawDefinition)) {
            return null;
        }
        try {
            JsonNode tree = objectMapper.readTree(rawDefinition);
            return tree != null && tree.isObject() ? tree : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractDisplayName(JsonNode def) {
        if (def == null) {
            return null;
        }
        return firstNonBlank(
                textValue(def.path("documentation").path("title")),
                textValue(def.path("presentationLabel")),
                localizedOrText(def.path("title")),
                localizedOrText(def.path("labels")));
    }

    private String extractScopedLabel(JsonNode def, String directField, String presentationLabelsKey) {
        if (def == null) {
            return null;
        }
        return firstNonBlank(
                localizedOrText(def.path(directField)),
                localizedOrText(def.path("presentationLabels").path(presentationLabelsKey)));
    }

    private String localizedOrText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return capLabel(node.asText());
        }
        if (node.isObject()) {
            String candidate = firstNonBlank(
                    textValue(node.path("pt-BR")),
                    textValue(node.path("pt")),
                    textValue(node.path("en-US")),
                    textValue(node.path("en")));
            if (candidate != null) {
                return candidate;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String fallback = textValue(entry.getValue());
                if (fallback != null) {
                    return fallback;
                }
            }
        }
        return null;
    }

    private String textValue(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return capLabel(node.asText());
    }

    private String capLabel(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > MAX_LABEL_LENGTH ? trimmed.substring(0, MAX_LABEL_LENGTH) : trimmed;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void requirePrincipal(DomainRuleGovernancePrincipal principal) {
        if (principal == null
                || !StringUtils.hasText(principal.tenantId())
                || !StringUtils.hasText(principal.environment())
                || !StringUtils.hasText(principal.actorRef())) {
            throw new IllegalArgumentException("A server-resolved governed principal is required");
        }
    }

    private String optional(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
