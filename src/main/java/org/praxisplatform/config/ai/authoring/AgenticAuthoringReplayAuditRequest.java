package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;
import org.praxisplatform.config.service.AiPrincipalContext;

public record AgenticAuthoringReplayAuditRequest(
        AiPrincipalContext principalContext,
        UUID streamId,
        UUID threadId,
        UUID turnId,
        JsonNode replayBundle,
        AgenticAuthoringDryRunResult dryRunResult
) {

    public AgenticAuthoringReplayAuditRequest {
        Objects.requireNonNull(principalContext, "principalContext must not be null");
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(replayBundle, "replayBundle must not be null");
        Objects.requireNonNull(dryRunResult, "dryRunResult must not be null");
    }
}
