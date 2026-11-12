package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Explicit withdrawal of one operation; tenant, actor and concurrency come from the server/HTTP boundary. */
@Schema(description="Withdraw the selected operation policy while preserving immutable publications and head history.")
public record DomainRuleSnapshotWithdrawalRequest(
    @Schema(description="Stable RuleSet family whose selected operation must stop admitting new work.",requiredMode=Schema.RequiredMode.REQUIRED) String ruleSetKey,
    @Schema(description="Exact operation to withdraw; all other operation heads remain unchanged.",requiredMode=Schema.RequiredMode.REQUIRED) String operationKey,
    @Schema(description="Auditable operational reason for withdrawal, recorded with the authenticated operator.",minLength=1,maxLength=1000,requiredMode=Schema.RequiredMode.REQUIRED) String reason) {}
