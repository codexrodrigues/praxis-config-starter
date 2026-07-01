package org.praxisplatform.config.dto;

import org.praxisplatform.config.service.AiPrincipalContext;

public record EnterpriseRuntimeContextRequest(
        AiPrincipalContext principalContext,
        String locale,
        String timezone,
        String activeProfileId,
        String activeModuleKey) {
}
