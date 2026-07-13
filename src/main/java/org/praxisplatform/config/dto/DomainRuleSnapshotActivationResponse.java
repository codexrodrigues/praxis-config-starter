package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;

/** Active snapshot plus immutable content identity and mutable head identity. */
@Schema(description = "Current activation state, keeping immutable snapshot identity separate from mutable head concurrency identity.")
public record DomainRuleSnapshotActivationResponse(
    @Schema(description = "Immutable runtime-neutral snapshot selected by the active head.", requiredMode = Schema.RequiredMode.REQUIRED)
    PublishedRuleSnapshot snapshot,
    @Schema(description = "Canonical uppercase SHA-256 digest of normalized immutable snapshot content.", requiredMode = Schema.RequiredMode.REQUIRED)
    String snapshotContentHash,
    @Schema(description = "Opaque mutable-head validator rotated on every publication or rollback to prevent ABA concurrency errors.", requiredMode = Schema.RequiredMode.REQUIRED)
    String headEtag,
    @Schema(description = "Monotonic count of head activations, including rollbacks.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    long activationRevision,
    @Schema(description = "Reason the head has this state: PUBLISHED, ROLLED_BACK or ACTIVE.", allowableValues = {"PUBLISHED", "ROLLED_BACK", "ACTIVE"}, requiredMode = Schema.RequiredMode.REQUIRED)
    String activationType) {}
