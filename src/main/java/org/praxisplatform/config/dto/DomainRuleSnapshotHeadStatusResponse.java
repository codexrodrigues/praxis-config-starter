package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe operational projection of a snapshot head, without exposing unverified snapshot content. */
@Schema(description = "Operational state of a scoped RuleSet head. This projection never returns unverified executable content.")
public record DomainRuleSnapshotHeadStatusResponse(
    @Schema(description = "Stable RuleSet identity owned by this head.", requiredMode = Schema.RequiredMode.REQUIRED)
    String ruleSetKey,
    @io.swagger.v3.oas.annotations.media.Schema(description = "Exact operation whose mutable head and execution readiness are described; other operations of the family have independent heads.", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
    String operationKey,
    @Schema(description = "Opaque key of the immutable snapshot last selected by the head; WITHDRAWN retains this key for audit without granting execution.", requiredMode = Schema.RequiredMode.REQUIRED)
    String activeSnapshotKey,
    @Schema(description = "Immutable RuleSet version stored in the selected snapshot.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    int ruleSetVersion,
    @Schema(description = "Append-only publication revision of the selected snapshot.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    int publicationRevision,
    @Schema(description = "Monotonic activation revision of the mutable head.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    long activationRevision,
    @Schema(description = "Opaque current-head validator required by a governed superseding publication.", requiredMode = Schema.RequiredMode.REQUIRED)
    String headEtag,
    @Schema(description = "Whether the selected snapshot passed the complete governed read verification and may be offered to runtimes.", requiredMode = Schema.RequiredMode.REQUIRED)
    boolean executionReady,
    @Schema(
        description = "Stable operational classification: READY after current runtime/catalog admission; RUNTIME_INCOMPATIBLE for intact evidence with a different engine/dialect/corpus tuple; REPUBLICATION_REQUIRED for intact pre-manifest evidence; INVALID for integrity or current admission failure; WITHDRAWN for an explicit governed withdrawal.",
        allowableValues = {"READY", "RUNTIME_INCOMPATIBLE", "REPUBLICATION_REQUIRED", "INVALID", "WITHDRAWN"},
        requiredMode = Schema.RequiredMode.REQUIRED)
    String governanceState) {}
