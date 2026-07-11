package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Contexto server-side de uma mutacao em {@code ui_user_config}.
 *
 * <p>Implementacoes devem resolver o principal e as capabilities a partir da
 * seguranca do servidor. Os valores de tenant, usuario e atualizador sao
 * metadados da requisicao e nao devem ser tratados como prova de identidade.
 */
public record UiConfigWriteAuthorizationRequest(
        String operation,
        String scope,
        String tenantId,
        String userId,
        String componentType,
        String componentId,
        String environment,
        String updatedBy,
        JsonNode payload,
        JsonNode tags) {
}
