package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pedido semanticamente escopado para ler valores atuais de um option source governado.
 *
 * <p>O recurso e o conceito ja devem ter sido resolvidos pela IA antes desta leitura. O texto
 * semantico serve apenas para reconciliar o campo com o schema canonico; ele nunca escolhe a
 * intencao primaria nem e enviado como busca textual ao option source.</p>
 */
public record LiveOptionValueRetrievalRequest(
        String resourcePath,
        String semanticField,
        String concept,
        String operator,
        JsonNode requestedValue,
        JsonNode dependencyFilters,
        int limit,
        boolean confirmSelection) {

    public LiveOptionValueRetrievalRequest(
            String resourcePath,
            String semanticField,
            String concept,
            String operator,
            JsonNode requestedValue,
            JsonNode dependencyFilters,
            int limit) {
        this(resourcePath, semanticField, concept, operator, requestedValue, dependencyFilters, limit, false);
    }
}
