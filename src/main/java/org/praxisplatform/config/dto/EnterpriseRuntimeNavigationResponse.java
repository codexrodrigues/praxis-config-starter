package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;

public record EnterpriseRuntimeNavigationResponse(
        String schemaVersion,
        List<EnterpriseRuntimeNavigationNode> nodes,
        List<String> capabilities,
        Instant resolvedAt) {
}
