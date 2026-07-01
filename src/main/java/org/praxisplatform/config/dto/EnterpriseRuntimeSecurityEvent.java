package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.Map;

public record EnterpriseRuntimeSecurityEvent(
        String eventRef,
        String eventType,
        String severity,
        String summary,
        String tenantId,
        String environment,
        Instant occurredAt,
        Map<String, String> metadata) {

    public EnterpriseRuntimeSecurityEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
