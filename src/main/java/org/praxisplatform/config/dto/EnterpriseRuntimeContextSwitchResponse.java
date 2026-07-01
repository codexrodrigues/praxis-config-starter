package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EnterpriseRuntimeContextSwitchResponse(
        String schemaVersion,
        boolean accepted,
        String message,
        EnterpriseRuntimeContextResponse effectiveContext,
        Map<String, String> propagationHeaders,
        List<String> capabilities,
        Instant resolvedAt) {

    public EnterpriseRuntimeContextSwitchResponse {
        propagationHeaders = propagationHeaders == null ? Map.of() : Map.copyOf(propagationHeaders);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
