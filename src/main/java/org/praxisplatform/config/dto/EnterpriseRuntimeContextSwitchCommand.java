package org.praxisplatform.config.dto;

public record EnterpriseRuntimeContextSwitchCommand(
        String targetTenantId,
        String targetProfileId,
        String targetModuleKey,
        String locale,
        String timezone,
        String reason) {
}
