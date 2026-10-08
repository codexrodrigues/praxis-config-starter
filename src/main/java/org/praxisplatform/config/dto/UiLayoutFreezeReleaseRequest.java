package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Correlated freeze command; immutable members are derived only from workspace selections. */
@Schema(description = "Freeze every registered target in exact registry order from authoritative workspace selections.")
public record UiLayoutFreezeReleaseRequest(
    @Schema(description = "Client-known correlation for this non-idempotent freeze attempt.", requiredMode = Schema.RequiredMode.REQUIRED) UUID commandRef) {}
