package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Safe server-owned reason why a governed snapshot command is unavailable. */
@Schema(description = "Safe machine-readable blocker for a governed RuleSet snapshot command.")
public record DomainRuleSnapshotBlocker(
    @Schema(description = "Stable blocker code for client rendering; never derived from message text.")
    String code,
    @Schema(description = "Governed lifecycle stage blocked by this diagnostic.")
    String stage,
    @Schema(description = "Source Definition whose evidence did not satisfy the stage policy.")
    UUID definitionId,
    @Schema(description = "Sanitized human-readable explanation without facts or private evidence.")
    String message) {}
