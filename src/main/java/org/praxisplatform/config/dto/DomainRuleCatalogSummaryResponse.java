package org.praxisplatform.config.dto;

import java.util.List;

/** Pre-aggregated catalog counts and pending review buckets for enterprise workstations. */
public record DomainRuleCatalogSummaryResponse(
        String schemaVersion,
        long totalDefinitions,
        long totalBusinessRules,
        long totalAuthorizationPolicies,
        List<BucketCount> byContextKey,
        List<BucketCount> byResourceKey,
        List<BucketCount> byRuleType,
        List<BucketCount> byDefinitionStatus,
        WorkspaceQueueSummary reviewQueue) {

    public static final String SCHEMA_VERSION = "praxis-domain-rule-catalog-summary.v1";

    public DomainRuleCatalogSummaryResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        byContextKey = byContextKey == null ? List.of() : List.copyOf(byContextKey);
        byResourceKey = byResourceKey == null ? List.of() : List.copyOf(byResourceKey);
        byRuleType = byRuleType == null ? List.of() : List.copyOf(byRuleType);
        byDefinitionStatus = byDefinitionStatus == null ? List.of() : List.copyOf(byDefinitionStatus);
        reviewQueue = reviewQueue == null ? WorkspaceQueueSummary.empty() : reviewQueue;
    }

    public record BucketCount(String key, long count) {
        public BucketCount {
            key = key == null ? "" : key;
        }
    }

    public record WorkspaceQueueSummary(
            long openDrafts,
            long inReview,
            long approved,
            long rejected,
            long promoted,
            long totalPending) {

        public static WorkspaceQueueSummary empty() {
            return new WorkspaceQueueSummary(0L, 0L, 0L, 0L, 0L, 0L);
        }
    }
}
