package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.rules.contract.RuleSetDefinition;

/** Exact candidate whose canonical digest must be approved before publication. */
@Schema(description = "Candidate RuleSet composition used to calculate the immutable approval digest.")
public record DomainRuleCompositionManifestRequest(
    @Schema(description = "Complete deterministic RuleSet graph whose exact canonical content will be approved.", requiredMode = Schema.RequiredMode.REQUIRED)
    RuleSetDefinition ruleSet,
    @Schema(description = "Distinct governed definition IDs whose current approved content hashes become immutable manifest provenance.", requiredMode = Schema.RequiredMode.REQUIRED)
    List<UUID> sourceDefinitionIds,
    @Schema(description = "Stable domain-host service identity authorized to consume the composition.", example = "praxis-api-quickstart", requiredMode = Schema.RequiredMode.REQUIRED)
    String ownerServiceKey,
    @Schema(description = "Exact host contract required to activate the resulting snapshot.", example = "quickstart/1.0", requiredMode = Schema.RequiredMode.REQUIRED)
    String requiredHostContractVersion,
    @Schema(description = "UTC instant from which the approved composition may be evaluated.", requiredMode = Schema.RequiredMode.REQUIRED)
    String validFromUtc,
    @Schema(description = "Optional exclusive UTC end of the composition validity interval.")
    String validUntilUtc) {}
