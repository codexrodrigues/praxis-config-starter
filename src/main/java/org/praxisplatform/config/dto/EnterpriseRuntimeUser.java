package org.praxisplatform.config.dto;

public record EnterpriseRuntimeUser(
        String userId,
        String displayName,
        boolean resolvedFromServerPrincipal) {
}
