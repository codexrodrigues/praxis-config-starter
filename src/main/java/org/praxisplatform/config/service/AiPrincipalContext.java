package org.praxisplatform.config.service;

import java.util.Set;
import java.util.stream.Collectors;

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
        boolean resolvedFromServerPrincipal,
        Set<String> authorities) {

    public AiPrincipalContext(
            String tenantId,
            String userId,
            String environment,
            boolean resolvedFromServerPrincipal) {
        this(tenantId, userId, environment, resolvedFromServerPrincipal, Set.of());
    }

    public AiPrincipalContext {
        tenantId = normalize(tenantId);
        userId = normalize(userId);
        environment = normalize(environment);
        authorities = authorities == null
                ? Set.of()
                : authorities.stream()
                        .map(AiPrincipalContext::normalize)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasAuthority(String authority) {
        String normalized = normalize(authority);
        return normalized != null && authorities.contains(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
