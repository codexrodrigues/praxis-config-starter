package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe audit representation of an independently authenticated composition approval. */
@Schema(description = "IAM-bound approval of one exact server-canonicalized RuleSet composition.")
public record DomainRuleCompositionApprovalResponse(
    @Schema(description = "Opaque append-only approval identifier.") String approvalKey,
    @Schema(description = "Fixed governance role proven by the authenticated request.") String role,
    @Schema(description = "Safe server-resolved principal reference; credentials and claims are never exposed.") String actorRef,
    @Schema(description = "Server-assigned UTC approval instant.") String decidedAtUtc,
    @Schema(description = "Exact canonical composition digest approved by this actor.") String evidenceHash) {}
