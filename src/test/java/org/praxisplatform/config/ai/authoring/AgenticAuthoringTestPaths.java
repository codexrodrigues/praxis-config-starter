package org.praxisplatform.config.ai.authoring;

import java.nio.file.Files;
import java.nio.file.Path;

final class AgenticAuthoringTestPaths {

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
        return resolveAgenticAuthoringDir(section).resolve(fileName);
    }

    private static Path resolveAgenticAuthoringDir(String section) {
        Path monorepoDocs = Path.of("..", "docs", "ai", "agentic-authoring", section);
        if (Files.exists(monorepoDocs)) {
            return monorepoDocs;
        }
        return Path.of("docs", "ai", "agentic-authoring", section);
    }
}
