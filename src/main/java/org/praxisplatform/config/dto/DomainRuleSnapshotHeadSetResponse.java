package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Safe public projection of a complete operation-head selection captured in one Config SQL snapshot. */
@Schema(description = "Complete current selection of operation heads in one governed family. Partial selections are unavailable; the fingerprint includes each head ETag and activation revision.")
public record DomainRuleSnapshotHeadSetResponse(
        @Schema(description = "Authenticated tenant whose policy heads were read.", requiredMode = Schema.RequiredMode.REQUIRED) String tenantId,
        @Schema(description = "Authenticated deployment environment of every returned head.", requiredMode = Schema.RequiredMode.REQUIRED) String environment,
        @Schema(description = "Stable host-owned RuleSet family shared by every operation.", requiredMode = Schema.RequiredMode.REQUIRED) String ruleSetKey,
        @Schema(description = "Exactly the requested operations, keyed by their canonical identity; every value is a verified current head.", requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, DomainRuleSnapshotActivationResponse> heads,
        @Schema(description = "SHA-256 binding scope, content and activation identities, including ABA changes. It does not grant authorization or freshness across databases.", requiredMode = Schema.RequiredMode.REQUIRED)
        String fingerprint) {
    public DomainRuleSnapshotHeadSetResponse { heads = Map.copyOf(heads); }
}
