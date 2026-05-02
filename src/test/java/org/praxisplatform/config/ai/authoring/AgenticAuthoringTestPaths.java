package org.praxisplatform.config.ai.authoring;

import java.nio.file.Files;
import java.nio.file.Path;

final class AgenticAuthoringTestPaths {

    private static final Path LOCAL_AGENTIC_AUTHORING_DIR = Path.of("docs", "ai", "agentic-authoring");
    private static final Path MONOREPO_AGENTIC_AUTHORING_DIR = Path.of("..", "docs", "ai", "agentic-authoring");
    private static final String DEFAULT_PROOF_FILE = "examples-governance-manifest.v0.json";

    private AgenticAuthoringTestPaths() {
    }

    static Path contract(String fileName) {
        return resolveAgenticAuthoringPath("contracts", fileName);
    }

    static Path proof(String fileName) {
        return proofsDir().resolve(fileName);
    }

    static Path proofsDir() {
        return resolveAgenticAuthoringDir("proofs");
    }

    private static Path resolveAgenticAuthoringPath(String section, String fileName) {
        Path localPath = LOCAL_AGENTIC_AUTHORING_DIR.resolve(section).resolve(fileName);
        if (Files.isRegularFile(localPath)) {
            return localPath;
        }
        Path monorepoPath = MONOREPO_AGENTIC_AUTHORING_DIR.resolve(section).resolve(fileName);
        if (Files.isRegularFile(monorepoPath)) {
            return monorepoPath;
        }
        return localPath;
    }

    private static Path resolveAgenticAuthoringDir(String section) {
        Path localDir = LOCAL_AGENTIC_AUTHORING_DIR.resolve(section);
        if (containsExpectedArtifacts(localDir, section)) {
            return localDir;
        }
        Path monorepoDir = MONOREPO_AGENTIC_AUTHORING_DIR.resolve(section);
        if (containsExpectedArtifacts(monorepoDir, section)) {
            return monorepoDir;
        }
        if (Files.isDirectory(localDir)) {
            return localDir;
        }
        return monorepoDir;
    }

    private static boolean containsExpectedArtifacts(Path directory, String section) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        if ("proofs".equals(section)) {
            return Files.isRegularFile(directory.resolve(DEFAULT_PROOF_FILE));
        }
        return true;
    }
}
