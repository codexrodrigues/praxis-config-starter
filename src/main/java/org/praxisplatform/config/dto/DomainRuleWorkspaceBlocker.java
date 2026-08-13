package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Server-owned reason why a governed workspace action is unavailable.")
public record DomainRuleWorkspaceBlocker(
    @Schema(description = "Stable machine-readable blocker code.") String code,
    @Schema(description = "Action prevented by this blocker.") String action,
    @Schema(description = "Human-readable explanation safe for an authenticated authoring UI.") String message) {}
