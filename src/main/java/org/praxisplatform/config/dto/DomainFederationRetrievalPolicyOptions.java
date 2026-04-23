package org.praxisplatform.config.dto;

public record DomainFederationRetrievalPolicyOptions(
        String policyProfile,
        Double minConfidence,
        Boolean includeDenied,
        Boolean includeLowConfidence
) {
    public DomainFederationRetrievalPolicyOptions(
            Double minConfidence,
            Boolean includeDenied,
            Boolean includeLowConfidence) {
        this(null, minConfidence, includeDenied, includeLowConfidence);
    }
}
