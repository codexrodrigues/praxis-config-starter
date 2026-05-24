package org.praxisplatform.config.dto;

import java.util.List;

public record AiAssistantObservationSummaryResponse(
        List<Row> rows
) {

    public record Row(
            String admissionOutcome,
            String terminalOutcome,
            String qualityOutcome,
            String componentId,
            String componentType,
            long total
    ) {
    }
}
