package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contexto imutavel de execucao capturado por turno do agente operacional.
 *
 * <p>Nunca retem {@code HttpServletRequest} ou referencias a ciclo de vida servlet para poder
 * trafegar com seguranca pelo executor assincrono SSE.
 */
public record AgentExecutionContext(
        UUID threadId,
        UUID turnId,
        UUID streamId,
        String tenantId,
        String userId,
        String environment,
        String principalName,
        Set<String> authorities,
        String activeScreenResourcePath,
        String activeTopicKey,
        Map<String, Object> activeSelectionHints,
        Map<String, Object> propagatedClaims
) {
    public AgentExecutionContext {
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
        activeSelectionHints = AgentMapCopies.immutableOrderedMap(activeSelectionHints);
        propagatedClaims = AgentMapCopies.immutableOrderedMap(propagatedClaims);
    }

    public boolean hasAuthority(String authority) {
        return authority != null && authorities.contains(authority);
    }
}
