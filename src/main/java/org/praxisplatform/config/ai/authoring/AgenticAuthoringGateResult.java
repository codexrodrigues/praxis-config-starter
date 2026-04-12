package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringGateResult(
        String gateId,
        String status,
        List<String> messages
) {
}
