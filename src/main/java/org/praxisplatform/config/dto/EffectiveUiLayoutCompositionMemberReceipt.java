package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One registered target in a coherent composition receipt. */
@Schema(description = "One registered composition target and its effective governed presentation.")
public record EffectiveUiLayoutCompositionMemberReceipt(
    @Schema(description = "Zero-based deterministic position assigned by the host composition registry, independent of release-member persistence order.", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    int memberOrder,
    @Schema(description = "Exact registered target to which this member receipt applies.", requiredMode = Schema.RequiredMode.REQUIRED)
    UiLayoutTarget target,
    @Schema(description = "Safe provenance for immutable release contributions that the resolver both authorized and applied to this target. Empty means no pinned release contribution was applied.", requiredMode = Schema.RequiredMode.REQUIRED)
    List<AppliedUiLayoutContributionReceipt> appliedContributions,
    @Schema(description = "Sanitized effective presentation for this explicitly authorized target. Null only when no permitted baseline, current overlay, or applied release contribution yields a layout; it does not indicate access denial.", nullable = true)
    EffectiveUiLayoutResponse effectiveLayout) {
  public EffectiveUiLayoutCompositionMemberReceipt {
    appliedContributions = appliedContributions == null ? List.of() : List.copyOf(appliedContributions);
  }
}
