package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;

/** One immutable published snapshot and its canonical content hash. */
@Schema(description = "Immutable published RuleSet snapshot addressed by its snapshot key and canonical content digest.")
public record DomainRuleSnapshotStoredResponse(
    @Schema(description = "Runtime-neutral content retained exactly as compiled at publication.", requiredMode = Schema.RequiredMode.REQUIRED)
    PublishedRuleSnapshot snapshot,
    @Schema(description = "Canonical uppercase SHA-256 digest used as the immutable representation ETag.", requiredMode = Schema.RequiredMode.REQUIRED)
    String snapshotContentHash) {}
