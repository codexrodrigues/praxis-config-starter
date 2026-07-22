package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Valor vivo retornado pelo option source canonico. */
public record LiveOptionValueCandidate(
        JsonNode id,
        String label,
        JsonNode extra) {
}
