package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "One complete choice currently discoverable by the authenticated session. Facets describe "
        + "the choice for humans and cannot be recombined into independent authorization selectors.")
public record EnterpriseRuntimeContextChoice(
        @Schema(description = "Opaque host reference to submit unchanged to context selection; not a credential or grant.",
                minLength = 1, maxLength = 4096)
        String choiceRef,
        @Schema(description = "Safe human-readable label for this complete choice, without private entitlement internals.")
        String label,
        @Schema(description = "Safe descriptive facets such as unit or profile labels. Excludes raw groups, policies, "
                + "credentials and independently selectable membership IDs.")
        Map<String, String> facets) {
    public EnterpriseRuntimeContextChoice {
        if (choiceRef == null || choiceRef.isBlank() || choiceRef.length() > 4096
                || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Invalid runtime context choice");
        }
        facets = facets == null ? Map.of() : Map.copyOf(facets);
    }
}
