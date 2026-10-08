package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Bounded live page of choices, not an authorization snapshot or total-count guarantee. "
        + "The host orders by stable identity and revalidates a choice when selected.")
public record EnterpriseRuntimeContextChoicesResponse(
        @Schema(description = "Host-authorized discoverable choices for this page; empty is a valid catalog result, "
                + "not a fallback for an unavailable membership source.")
        List<EnterpriseRuntimeContextChoice> items,
        @Schema(description = "Opaque continuation bound by the host to session, query and position. Null means end; "
                + "invalid or expired cursors fail explicitly rather than restarting the catalog.",
                nullable = true, maxLength = 4096)
        String nextCursor) {
    public EnterpriseRuntimeContextChoicesResponse {
        items = List.copyOf(items);
        if (items.size() > 100 || (nextCursor != null && (nextCursor.isBlank() || nextCursor.length() > 4096))) {
            throw new IllegalArgumentException("Invalid context choices page");
        }
    }
}
