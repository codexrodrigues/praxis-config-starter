package org.praxisplatform.config.dto;

import java.time.Instant;

/** Acknowledges whether the current host status advanced or was an idempotent stale report. */
public record DomainRuleHostStatusIngestionResponse(boolean updated, Instant observedAtUtc) {}
