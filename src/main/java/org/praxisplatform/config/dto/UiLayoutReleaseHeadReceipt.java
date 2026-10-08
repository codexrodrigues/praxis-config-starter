package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Sanitized mutable selection of the active immutable release for one composition root. */
@Schema(description = "Scoped mutable release-head receipt; it is the only active-release pointer for the registered root.")
public record UiLayoutReleaseHeadReceipt(
    @Schema(description = "Registered composition root whose active release is selected.", requiredMode = Schema.RequiredMode.REQUIRED) UiLayoutTarget rootTarget,
    @Schema(description = "Opaque active immutable release identity, or null when the head is withdrawn.", nullable = true) String activeReleaseRef,
    @Schema(description = "Opaque strong validator for head publication, withdrawal and rollback. It is distinct from draft and review validators.", requiredMode = Schema.RequiredMode.REQUIRED) String headEtag,
    @Schema(description = "Server timestamp of the latest head mutation.", requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {}
