package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record DomainFederationContract(
        String contractKey,
        String contractType,
        String providerSourceKey,
        String providerContextKey,
        String consumerContextKey,
        String resourceKey,
        String operationKey,
        String schemaRef,
        String compatibility,
        String visibility,
        String status,
        JsonNode evidence
) {
}
