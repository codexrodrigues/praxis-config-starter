package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe provenance for one contribution that was authorized and applied to an effective member. */
@Schema(description = "Authorized immutable layout contribution applied to one composition member.")
public record AppliedUiLayoutContributionReceipt(
    @Schema(description = "Zero-based immutable member position inside the active release that supplied this contribution.", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    int memberOrder,
    @Schema(description = "Opaque immutable assignment-revision reference whose selector and contribution key were authorized for this resolution.", requiredMode = Schema.RequiredMode.REQUIRED)
    String assignmentRevisionRef,
    @Schema(description = "Opaque immutable content-revision reference that supplied the applied presentation patch.", requiredMode = Schema.RequiredMode.REQUIRED)
    String contentRevisionRef,
    @Schema(description = "Canonical SHA-256 hash of the exact immutable content patch. It identifies patch integrity and is distinct from the aggregate receipt ETag.", requiredMode = Schema.RequiredMode.REQUIRED)
    String contentHash) {}
