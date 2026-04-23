package org.praxisplatform.config.dto;

import java.util.List;

public record DomainFederationValidationRequest(
        String schemaVersion,
        String tenantId,
        String environment,
        List<DomainFederationSource> sources,
        List<DomainFederationContext> contexts,
        List<DomainFederationContextRelationship> contextRelationships,
        List<DomainFederationContract> contracts,
        List<DomainFederationResolution> resolutions
) {
}
