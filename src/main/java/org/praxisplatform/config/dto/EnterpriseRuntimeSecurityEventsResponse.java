package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;

public record EnterpriseRuntimeSecurityEventsResponse(
        String schemaVersion,
        List<EnterpriseRuntimeSecurityEvent> events,
        List<String> capabilities,
        Instant resolvedAt) {

    public EnterpriseRuntimeSecurityEventsResponse {
        events = events == null ? List.of() : List.copyOf(events);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
