package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;

public record EnterpriseRuntimeContextResponse(
        String schemaVersion,
        EnterpriseRuntimeUser user,
        EnterpriseRuntimeTenant activeTenant,
        String environment,
        String locale,
        String timezone,
        String activeProfileId,
        String activeModuleKey,
        List<String> capabilities,
        Instant resolvedAt) {
}
