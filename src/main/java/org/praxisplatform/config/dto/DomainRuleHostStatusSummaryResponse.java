package org.praxisplatform.config.dto;

import java.time.Instant;

/** Safe aggregate of runtime alignment against the server-owned active snapshot head. */
public record DomainRuleHostStatusSummaryResponse(
    String ruleSetKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Operation whose expected head and host convergence are summarized; heartbeats for other operations are excluded.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    String operationKey,
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
