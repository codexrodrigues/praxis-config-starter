package org.praxisplatform.config.dto;

public record DomainFederationRetrievalPolicyOptions(
        Double minConfidence,
        Boolean includeDenied,
        Boolean includeLowConfidence
) {
}
