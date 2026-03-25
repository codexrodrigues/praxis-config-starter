package org.praxisplatform.config.service;

/**
 * Identidade operacional resolvida para uma chamada AI/config.
 *
 * <p>
 * Normaliza tenant, usuário e ambiente a partir de headers e/ou principal do servidor para que
 * serviços internos não precisem repetir lógica de saneamento de contexto.
 * </p>
 */
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
