package org.praxisplatform.config.ai.authoring;

import java.nio.file.Path;
import java.util.Objects;

public record AgenticAuthoringDryRunRequest(
        Path examplesGovernanceManifest,
        Path pageCreateCatalog,
        Path compiledFormPatch,
        Path authoringReplayBundle,
        String profileId
) {

    public AgenticAuthoringDryRunRequest {
        Objects.requireNonNull(examplesGovernanceManifest, "examplesGovernanceManifest must not be null");
        Objects.requireNonNull(pageCreateCatalog, "pageCreateCatalog must not be null");
        Objects.requireNonNull(compiledFormPatch, "compiledFormPatch must not be null");
        Objects.requireNonNull(authoringReplayBundle, "authoringReplayBundle must not be null");
        if (profileId == null || profileId.isBlank()) {
            profileId = "create-minimal-form";
        }
    }
}
