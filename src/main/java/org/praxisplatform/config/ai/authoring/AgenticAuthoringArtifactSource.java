package org.praxisplatform.config.ai.authoring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class AgenticAuthoringArtifactSource {

    private final AgenticAuthoringArtifactProperties properties;

    public AgenticAuthoringArtifactSource(AgenticAuthoringArtifactProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public AgenticAuthoringDryRunRequest dryRunRequest() {
        Path root = requireArtifactsDir();
        return new AgenticAuthoringDryRunRequest(
                requireFile(root, properties.getExamplesGovernanceManifest(), "examplesGovernanceManifest"),
                requireFile(root, properties.getPageCreateCatalog(), "pageCreateCatalog"),
                requireFile(root, properties.getCompiledFormPatch(), "compiledFormPatch"),
                requireFile(root, properties.getAuthoringReplayBundle(), "authoringReplayBundle"),
                properties.getProfileId()
        );
    }

    private Path requireArtifactsDir() {
        Path artifactsDir = properties.getArtifactsDir();
        if (artifactsDir == null) {
            throw new IllegalStateException("praxis.ai.authoring.artifacts-dir must be configured before running agentic authoring dry-run.");
        }
        Path normalized = artifactsDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalStateException("praxis.ai.authoring.artifacts-dir does not exist or is not a directory: " + normalized);
        }
        return normalized;
    }

    private Path requireFile(Path root, String fileName, String propertyName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalStateException("praxis.ai.authoring." + propertyName + " must not be blank.");
        }
        Path resolved = root.resolve(fileName).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("praxis.ai.authoring." + propertyName + " must resolve inside artifacts-dir.");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Required agentic authoring artifact not found: " + resolved);
        }
        return resolved;
    }
}
