package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record DomainRuleMaterializationRequest(
        @Schema(description = "Identifier of the governed rule definition from which this target draft is derived.")
        UUID ruleDefinitionId,
        @Schema(description = "Stable idempotency key for this definition and target coordinate.")
        String materializationKey,
        @Schema(description = "Canonical runtime layer that owns the derived target artifact.")
        String targetLayer,
        @Schema(description = "Canonical contract type of the target artifact.")
        String targetArtifactType,
        @Schema(description = "Stable target artifact identity within its owning runtime layer.")
        String targetArtifactKey,
        @Schema(description = "Optional pointer to the exact insertion or replacement location inside the target artifact.")
        String targetPointer,
        @Schema(description = "Optional governed release key against which the draft was produced.")
        String targetReleaseKey,
        @Schema(description = "Optional runtime rule identity produced by the target adapter.")
        String materializedRuleId,
        @Schema(description = "Initial lifecycle status. The public creation boundary accepts only draft or pending_review; activation is a separate authenticated operation.")
        String status,
        @Schema(description = "Target-specific draft payload derived from the governed definition.")
        JsonNode materializedPayload,
        @Schema(description = "Digest binding the draft to its exact semantic source and target projection.")
        String sourceHash,
        @Schema(description = "Machine validation evidence for the draft. It does not represent business homologation.")
        JsonNode validationResult
) {
}
