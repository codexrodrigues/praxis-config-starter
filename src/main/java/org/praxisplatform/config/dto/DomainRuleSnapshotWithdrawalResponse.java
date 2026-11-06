package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Tombstone identity, never an executable snapshot or an activation receipt. */
@Schema(description="Confirmed policy withdrawal. Reactivation requires a new governed rollout against this head validator.")
public record DomainRuleSnapshotWithdrawalResponse(
    @Schema(description="Family of the withdrawn policy.",requiredMode=Schema.RequiredMode.REQUIRED) String ruleSetKey,
    @Schema(description="Operation whose selection was withdrawn.",requiredMode=Schema.RequiredMode.REQUIRED) String operationKey,
    @Schema(description="Last selected immutable publication retained for audit; it is no longer active.",requiredMode=Schema.RequiredMode.REQUIRED) String lastSnapshotKey,
    @Schema(description="New strong head validator issued for this withdrawal.",requiredMode=Schema.RequiredMode.REQUIRED) String headEtag,
    @Schema(description="Monotonically advanced selection revision, including this withdrawal.",minimum="1",requiredMode=Schema.RequiredMode.REQUIRED) long activationRevision) {}
