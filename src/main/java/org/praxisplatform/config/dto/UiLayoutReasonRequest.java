package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bounded audit reason for a governed lifecycle transition. */
@Schema(description = "Required bounded reason recorded for a governed lifecycle transition.")
public record UiLayoutReasonRequest(@Schema(description = "Business justification for the transition.", requiredMode = Schema.RequiredMode.REQUIRED) String reason) {}
