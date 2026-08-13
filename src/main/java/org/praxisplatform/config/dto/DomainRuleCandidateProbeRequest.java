package org.praxisplatform.config.dto;

import java.time.Instant;

/** Redacted compatibility result from an isolated host candidate preload lane. */
public record DomainRuleCandidateProbeRequest(
    String candidateSnapshotKey, String candidateContentHash, boolean preloadReady,
    String hostContractVersion, String engineContractVersion, String jsonLogicDialectVersion,
    String jsonLogicCorpusSha256, String implementationCatalogDigest, String failureCode,
    Instant observedAtUtc) {}
