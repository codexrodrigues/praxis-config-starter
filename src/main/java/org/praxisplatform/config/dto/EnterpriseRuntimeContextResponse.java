package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Safe result of host session validation; a ready snapshot is not an authorization grant. */
@Schema(description = "Host-confirmed session selection state. An authenticated session can require an initial "
        + "selection or recover from a revoked selection without receiving an effective data context.")
public record EnterpriseRuntimeContextResponse(
        @Schema(description = "Schema identity of this runtime projection, not a session or document version.")
        String schemaVersion,
        @Schema(description = "selection-required: no choice selected; ready: current choice remains authorized; "
                + "selection-invalid: stored choice is no longer authorized and must not be exposed.",
                allowableValues = {"selection-required", "ready", "selection-invalid"})
        String state,
        @Schema(description = "Opaque host selection version bound to this authenticated issuance. Submit unchanged "
                + "as expectedSelectionVersion when selecting; never calculate it or use a layout ETag instead.",
                maxLength = 256, minLength = 1)
        String selectionVersion,
        @Schema(description = "Safe identity projection of the session validated by the host; excludes credentials and grants.")
        EnterpriseRuntimeUser user,
        @Schema(description = "Present only in ready state. Non-ready responses expose no revoked or speculative context.",
                nullable = true)
        EnterpriseRuntimeEffectiveContext effectiveContext,
        @Schema(description = "Optional safe host reason code for the state; never private membership, policy or token data.",
                nullable = true)
        String reasonCode,
        @Schema(description = "Time when the host resolved this response, not an authorization expiry or cache validator.")
        Instant resolvedAt) {

    public EnterpriseRuntimeContextResponse {
        if (schemaVersion == null || schemaVersion.isBlank()
                || state == null || !Set.of("selection-required", "ready", "selection-invalid").contains(state)
                || selectionVersion == null || selectionVersion.isBlank() || selectionVersion.length() > 256) {
            throw new IllegalArgumentException("Invalid runtime context projection");
        }
        Objects.requireNonNull(user, "Runtime user projection required");
        Objects.requireNonNull(resolvedAt, "Runtime resolution time required");
        if ("ready".equals(state) != (effectiveContext != null)) {
            throw new IllegalArgumentException("Effective context requires ready state");
        }
    }
}
