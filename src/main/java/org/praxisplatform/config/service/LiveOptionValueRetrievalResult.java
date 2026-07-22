package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Resultado governado e versionado da leitura de valores atuais de um option source. */
public record LiveOptionValueRetrievalResult(
        boolean valid,
        String schemaVersion,
        String resourcePath,
        String filterSchemaPath,
        String canonicalFilterField,
        String optionSourceKey,
        String filterEndpoint,
        String byIdsEndpoint,
        String datasetVersion,
        String retrievalMode,
        String fieldResolution,
        JsonNode requestedValue,
        int totalElements,
        boolean exhaustive,
        List<LiveOptionValueCandidate> candidates,
        String errorCode,
        String errorMessage) {

    public static LiveOptionValueRetrievalResult failure(
            String resourcePath,
            String errorCode,
            String errorMessage) {
        return new LiveOptionValueRetrievalResult(
                false,
                "praxis-live-option-values.v1",
                resourcePath,
                "",
                "",
                "",
                "",
                "",
                "",
                "unavailable",
                "unresolved",
                null,
                0,
                false,
                List.of(),
                errorCode,
                errorMessage);
    }
}
