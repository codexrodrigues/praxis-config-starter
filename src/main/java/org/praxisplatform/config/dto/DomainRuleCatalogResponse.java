package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded and redacted identity projection shared by human and assistant discovery. */
public record DomainRuleCatalogResponse(
        String schemaVersion,
        List<Candidate> candidates,
        int page,
        int limit,
        boolean hasMore) {

    public static final String SCHEMA_VERSION = "praxis-domain-rule-catalog.v1";

    public DomainRuleCatalogResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record Candidate(
            UUID definitionId,
            String ruleKey,
            Integer version,
            String ruleType,
            String status,
            String contextKey,
            String resourceKey,
            String serviceKey,
            String semanticOwner,
            Instant updatedAt) {}
}
