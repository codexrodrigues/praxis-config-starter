package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Typed control-plane failure returned by snapshot endpoints. */
@Schema(description = "Typed snapshot control-plane failure with optional safe governance blockers.")
public record DomainRuleSnapshotProblemResponse(
    @Schema(description = "Stable error family; clients must not parse message text.")
    String code,
    @Schema(description = "Sanitized summary suitable for operator feedback.")
    String message,
    @Schema(description = "Server-owned evidence or governance blockers; empty for ordinary HTTP failures.")
    List<DomainRuleSnapshotBlocker> blockers) {

  public DomainRuleSnapshotProblemResponse {
    blockers = blockers == null ? List.of() : List.copyOf(blockers);
  }
}
