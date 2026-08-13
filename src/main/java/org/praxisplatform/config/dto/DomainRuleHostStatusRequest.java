package org.praxisplatform.config.dto;

import java.time.Instant;

/** Redacted host heartbeat; governed scope and host identity come from the server principal. */
public record DomainRuleHostStatusRequest(
    String ruleSetKey,
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
