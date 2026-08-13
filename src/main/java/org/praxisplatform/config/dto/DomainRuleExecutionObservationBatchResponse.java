package org.praxisplatform.config.dto;

/** Idempotent ingestion result without echoing observation payloads. */
public record DomainRuleExecutionObservationBatchResponse(
    int acceptedCount,
    int duplicateCount) {}
