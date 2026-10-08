package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Sanitized mutable approval state for one immutable release. */
@Schema(description = "Scoped mutable review receipt for one immutable UI layout release.")
public record UiLayoutReleaseReviewReceipt(
    @Schema(description = "Opaque immutable release identity under review.", requiredMode = Schema.RequiredMode.REQUIRED) String releaseRef,
    @Schema(description = "Registered composition root that scopes the release.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget rootTarget,
    @Schema(description = "Current review state, such as DRAFT, SUBMITTED or APPROVED.", requiredMode = Schema.RequiredMode.REQUIRED) String state,
    @Schema(description = "Opaque strong validator for review transitions. It is distinct from draft and release-head validators.", requiredMode = Schema.RequiredMode.REQUIRED) String reviewEtag,
    @Schema(description = "Server timestamp when the release was submitted, or null while it has not been submitted.", nullable = true) Instant submittedAt) {}
