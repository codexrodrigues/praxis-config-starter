package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe immutable aggregate release identity returned after members are frozen. */
@Schema(description = "Immutable UI layout aggregate release receipt without private members or approvals.")
public record UiLayoutReleaseReceipt(
    @Schema(description = "Opaque immutable release identity.", requiredMode = Schema.RequiredMode.REQUIRED) String releaseRef,
    @Schema(description = "Registered composition root fixed by this release.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget rootTarget,
    @Schema(description = "Number of frozen member contributions in the immutable release.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED) int memberCount) {}
