package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.UUID;

public record DomainRuleWorkspaceReviewResponse(
    UUID id, UUID workspaceId, Long workspaceRevision, String baseDefinitionHash,
    String decision, String rationale, String reviewerRef, Instant reviewedAt) {}
