package org.praxisplatform.config.dto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record DomainRuleTestRunResponse(
    UUID runId, UUID workspaceId, long workspaceRevision, String baseDefinitionHash,
    Instant evaluatedAtUtc, String userTimeZone, String activeSnapshotKey,
    String activeSnapshotContentHash, long activeActivationRevision, List<DomainRuleTestRunResultResponse> results,
    String recordedBy, Instant recordedAt) {}
