package org.praxisplatform.config.dto;

import java.time.Instant;

/** Redacted host heartbeat; governed scope and host identity come from the server principal. */
public record DomainRuleHostStatusRequest(
    String ruleSetKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Exact operation loaded or attempted by this host; failures without a loaded snapshot must still identify the intended operation.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    String operationKey,
    String loadedSnapshotKey,
    String loadedSnapshotContentHash,
    Long activationRevision,
    boolean ready,
    String hostContractVersion,
    String engineContractVersion,
    String jsonLogicDialectVersion,
    String jsonLogicCorpusSha256,
    String implementationCatalogDigest,
    String failureCode,
    Instant observedAtUtc) {}
