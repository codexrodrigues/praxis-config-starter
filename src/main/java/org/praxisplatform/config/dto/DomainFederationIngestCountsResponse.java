package org.praxisplatform.config.dto;

public record DomainFederationIngestCountsResponse(
        int sources,
        int contexts,
        int contextRelationships,
        int contracts,
        int resolutions
) {
}
