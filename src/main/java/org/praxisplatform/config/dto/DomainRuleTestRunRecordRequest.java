package org.praxisplatform.config.dto;
import java.time.Instant;
import java.util.List;
public record DomainRuleTestRunRecordRequest(
    long workspaceRevision, String baseDefinitionHash, Instant evaluatedAtUtc, String userTimeZone,
    String activeSnapshotKey, String activeSnapshotContentHash, long activeActivationRevision,
    DomainRuleTestBaselineEvidence baselineEvidence,
    List<DomainRuleTestRunResultRequest> results) {
  public DomainRuleTestRunRecordRequest(
      long workspaceRevision, String baseDefinitionHash, Instant evaluatedAtUtc, String userTimeZone,
      String activeSnapshotKey, String activeSnapshotContentHash, long activeActivationRevision,
      List<DomainRuleTestRunResultRequest> results) {
    this(workspaceRevision, baseDefinitionHash, evaluatedAtUtc, userTimeZone, activeSnapshotKey,
        activeSnapshotContentHash, activeActivationRevision, null, results);
  }
}
