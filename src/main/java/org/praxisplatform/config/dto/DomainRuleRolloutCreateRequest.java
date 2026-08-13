package org.praxisplatform.config.dto;

import java.time.Instant;

/** Requests a non-authoritative preload rollout for an immutable candidate. */
public record DomainRuleRolloutCreateRequest(String candidateSnapshotKey, Instant expiresAtUtc) {}
