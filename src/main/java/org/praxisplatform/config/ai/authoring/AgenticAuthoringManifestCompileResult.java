package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgenticAuthoringManifestCompileResult(
        boolean compiled,
        List<String> failures,
        List<String> warnings,
        JsonNode patch
) {
}
