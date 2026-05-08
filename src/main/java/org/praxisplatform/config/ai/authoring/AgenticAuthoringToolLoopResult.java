package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringToolLoopResult(
        boolean completed,
        String terminalReason,
        List<AgenticAuthoringToolLoopStep> trace
) {
}
