package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** Sanitized coherent receipt for a registered root target and all of its registered targets. */
@Schema(description = "Coherent effective UI layout receipt for one registered composition.")
public record EffectiveUiLayoutCompositionResponse(
    @Schema(description = "Fixed version of the aggregate receipt contract, currently praxis.effective-ui-layout-composition/v1.", requiredMode = Schema.RequiredMode.REQUIRED)
    String schemaVersion,
    @Schema(description = "Opaque immutable active-release reference. Null means that no corporate release is active and only host-permitted baseline or current overlays were evaluated.", nullable = true)
    String releaseRef,
    @Schema(description = "Exact registered root target requested by the caller and authorized by the host.", requiredMode = Schema.RequiredMode.REQUIRED)
    UiLayoutTarget rootTarget,
    @Schema(description = "Opaque server-confirmed runtime-context version used for authorization and resolution; it must equal the request header assertion.", requiredMode = Schema.RequiredMode.REQUIRED)
    String contextVersion,
    @Schema(description = "Canonical receipt validator for this sanitized aggregate representation. The HTTP ETag header carries this same value and supports weak conditional reads.", requiredMode = Schema.RequiredMode.REQUIRED)
    String receiptEtag,
    @Schema(description = "All registered targets in deterministic registry order. The list is complete only after every target passes authorization and integrity checks.", requiredMode = Schema.RequiredMode.REQUIRED)
    List<EffectiveUiLayoutCompositionMemberReceipt> members,
    @Schema(description = "Server timestamp at which this receipt was assembled. It is informational and intentionally excluded from receiptEtag.", requiredMode = Schema.RequiredMode.REQUIRED)
    Instant resolvedAt) {
  public EffectiveUiLayoutCompositionResponse {
    members = members == null ? List.of() : List.copyOf(members);
  }
}
