package org.praxisplatform.config.dto;

import java.time.Instant;

/** Safe aggregate of runtime alignment against the server-owned active snapshot head. */
public record DomainRuleHostStatusSummaryResponse(
    String ruleSetKey,
    String expectedSnapshotKey,
    String expectedSnapshotContentHash,
    long expectedActivationRevision,
    String expectedHostContractVersion,
    String expectedEngineContractVersion,
    String expectedJsonLogicDialectVersion,
    String expectedJsonLogicCorpusSha256,
    String expectedImplementationCatalogDigest,
    long totalHosts,
    long alignedHosts,
    long snapshotDriftedHosts,
    long incompatibleHosts,
    long unavailableHosts,
    long staleHosts,
    Instant lastObservedAtUtc,
    Instant staleBeforeUtc) {}
