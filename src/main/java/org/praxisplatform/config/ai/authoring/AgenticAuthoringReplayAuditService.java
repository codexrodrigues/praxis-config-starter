package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiTurnEventService;

public class AgenticAuthoringReplayAuditService {

    public static final String EVENT_TYPE_AUTHORING_REPLAY = "authoring.replay";

    private final AiTurnEventService turnEventService;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringReplayAuditService(
            AiTurnEventService turnEventService,
            ObjectMapper objectMapper
    ) {
        this.turnEventService = turnEventService;
        this.objectMapper = objectMapper;
    }

    public AiTurnEventEnvelope appendReplayEvent(AgenticAuthoringReplayAuditRequest request) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("replayBundle", request.replayBundle());
        payload.set("dryRun", objectMapper.valueToTree(toDryRunSummary(request.dryRunResult())));
        return turnEventService.appendEvent(
                request.principalContext(),
                request.streamId(),
                request.threadId(),
                request.turnId(),
                EVENT_TYPE_AUTHORING_REPLAY,
                payload
        );
    }

    private DryRunSummary toDryRunSummary(AgenticAuthoringDryRunResult result) {
        return new DryRunSummary(
                result.valid(),
                result.gates().stream()
                        .map(gate -> new GateSummary(gate.gateId(), gate.status(), gate.messages()))
                        .toList(),
                result.failureCodes(),
                result.warnings()
        );
    }

    private record DryRunSummary(
            boolean valid,
            List<GateSummary> gates,
            List<String> failureCodes,
            List<String> warnings
    ) {
    }

    private record GateSummary(
            String gateId,
            String status,
            List<String> messages
    ) {
    }
}
