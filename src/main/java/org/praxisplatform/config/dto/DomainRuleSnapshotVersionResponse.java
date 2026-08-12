package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Safe catalog projection of one immutable RuleSet version. */
@Schema(description = "Safe version entry for a governed RuleSet. Executable snapshot content is intentionally omitted.")
public record DomainRuleSnapshotVersionResponse(
    @Schema(description = "Opaque immutable snapshot identity used for retrieval or rollback.", requiredMode = Schema.RequiredMode.REQUIRED)
    String snapshotKey,
    @Schema(description = "Stable semantic RuleSet identity shared by all versions in the catalog.", requiredMode = Schema.RequiredMode.REQUIRED)
    String ruleSetKey,
    @Schema(description = "Immutable version declared by the RuleSet content.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    int ruleSetVersion,
    @Schema(description = "Append-only publication order within the governed scope.", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    int publicationRevision,
    @Schema(description = "Canonical SHA-256 identity of the immutable snapshot content.", requiredMode = Schema.RequiredMode.REQUIRED)
    String snapshotContentHash,
    @Schema(description = "Safe authenticated publisher reference recorded at publication.", requiredMode = Schema.RequiredMode.REQUIRED)
    String publishedBy,
    @Schema(description = "Server-assigned UTC publication instant.", requiredMode = Schema.RequiredMode.REQUIRED)
    String publishedAtUtc,
    @Schema(description = "Whether this immutable version is currently selected by the scoped mutable head.", requiredMode = Schema.RequiredMode.REQUIRED)
    boolean active,
    @Schema(description = "Safe verification state: READY, REPUBLICATION_REQUIRED or INVALID.", allowableValues = {"READY", "REPUBLICATION_REQUIRED", "INVALID"}, requiredMode = Schema.RequiredMode.REQUIRED)
    String governanceState,
    @Schema(description = "Server-derived operation available for this version relative to the current head.", allowableValues = {"ACTIVE", "ACTIVATE", "ROLLBACK", "UNAVAILABLE"}, requiredMode = Schema.RequiredMode.REQUIRED)
    String availableAction) {}
