package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringDryRunErrorResponse(
        boolean valid,
        List<String> failureCodes,
        String message
) {

    public static AgenticAuthoringDryRunErrorResponse configurationInvalid(String message) {
        return new AgenticAuthoringDryRunErrorResponse(
                false,
                List.of("DRY_RUN_CONFIGURATION_INVALID"),
                message
        );
    }
}
