package org.praxisplatform.config.service;

public record AiPrincipalContext(
        String tenantId,
        String userId,
        String environment,
        boolean resolvedFromServerPrincipal) {

    public AiPrincipalContext {
        tenantId = normalize(tenantId);
        userId = normalize(userId);
        environment = normalize(environment);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
