package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.Map;

/** Safe aggregate of persisted execution evidence for one immutable snapshot. */
public record DomainRuleExecutionSummaryResponse(
    String ruleSetKey,
    String snapshotKey,
    String snapshotContentHash,
    int ruleSetVersion,
    long totalObservations,
    long distinctHosts,
    Map<String, Long> outcomeCounts,
    Instant firstObservedAtUtc,
    Instant lastObservedAtUtc) {
  public DomainRuleExecutionSummaryResponse {
    outcomeCounts = outcomeCounts == null ? Map.of() : Map.copyOf(outcomeCounts);
  }
}
