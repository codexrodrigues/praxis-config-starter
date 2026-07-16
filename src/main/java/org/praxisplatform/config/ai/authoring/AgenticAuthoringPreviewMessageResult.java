package org.praxisplatform.config.ai.authoring;

import java.util.List;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;

record AgenticAuthoringPreviewMessageResult(
        String message,
        List<AiProviderInvocationTelemetry> providerInvocations) {

    AgenticAuthoringPreviewMessageResult {
        message = message == null ? "" : message;
        providerInvocations = providerInvocations == null ? List.of() : List.copyOf(providerInvocations);
    }

    static AgenticAuthoringPreviewMessageResult deterministic(String message) {
        return new AgenticAuthoringPreviewMessageResult(message, List.of());
    }
}
