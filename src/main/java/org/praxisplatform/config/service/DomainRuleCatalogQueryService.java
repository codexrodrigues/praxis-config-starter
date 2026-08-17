package org.praxisplatform.config.service;

import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;

/** Scoped, bounded and redacted catalog of governed domain decisions. */
public class DomainRuleCatalogQueryService {

    private static final int MAX_LIMIT = 12;
    private static final int MAX_PAGE = 100;
    private static final int MAX_QUERY_LENGTH = 256;

    private final DomainRuleDefinitionRepository definitions;

    public DomainRuleCatalogQueryService(DomainRuleDefinitionRepository definitions) {
        this.definitions = Objects.requireNonNull(definitions, "definitions must not be null");
    }

    public DomainRuleCatalogResponse search(
            String query,
            String ruleType,
            String status,
            String resourceKey,
            Integer requestedPage,
            Integer requestedLimit,
            DomainRuleGovernancePrincipal principal) {
        if (principal == null
                || !StringUtils.hasText(principal.tenantId())
                || !StringUtils.hasText(principal.environment())
                || !StringUtils.hasText(principal.actorRef())) {
            throw new IllegalArgumentException("A server-resolved governed principal is required");
        }
        int page = requestedPage == null ? 0 : requestedPage;
        int limit = requestedLimit == null ? 6 : requestedLimit;
        if (page < 0 || page > MAX_PAGE) {
            throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        String normalizedQuery = optional(query, MAX_QUERY_LENGTH, "query");
        Page<DomainRuleDefinition> result = definitions.searchCatalogCandidates(
                principal.tenantId().trim(),
                principal.environment().trim(),
                normalizedQuery,
                optional(ruleType, 64, "ruleType"),
                optional(status, 32, "status"),
                optional(resourceKey, 255, "resourceKey"),
                PageRequest.of(page, limit));
        List<DomainRuleCatalogResponse.Candidate> candidates = result.getContent().stream()
                .map(this::candidate)
                .toList();
        return new DomainRuleCatalogResponse(
                DomainRuleCatalogResponse.SCHEMA_VERSION,
                candidates,
                page,
                limit,
                result.hasNext());
    }

    private DomainRuleCatalogResponse.Candidate candidate(DomainRuleDefinition definition) {
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
                definition.getUpdatedAt());
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
