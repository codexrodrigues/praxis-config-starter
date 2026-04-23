package org.praxisplatform.config.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationIngestDryRunResponse;
import org.praxisplatform.config.dto.DomainFederationIngestPreviewItemResponse;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DomainFederationIngestDryRunService {

    private static final String SCHEMA_VERSION = "praxis.domain-federation-ingest-dry-run/v0.1";
    private static final int PREVIEW_LIMIT = 12;

    private final DomainFederationContractValidator domainFederationContractValidator;
    private final DomainFederationQueryService domainFederationQueryService;

    public DomainFederationIngestDryRunResponse dryRun(DomainFederationValidationRequest request) {
        DomainFederationValidationReport validation = domainFederationContractValidator.validate(request);
        List<DomainFederationIngestPreviewItemResponse> previews = buildPreviews(request);
        return new DomainFederationIngestDryRunResponse(
                SCHEMA_VERSION,
                true,
                validation.valid(),
                previews.size(),
                validation,
                previews);
    }

    private List<DomainFederationIngestPreviewItemResponse> buildPreviews(DomainFederationValidationRequest request) {
        if (request == null || request.contexts() == null || request.contexts().isEmpty()) {
            return List.of();
        }
        Map<String, DomainFederationSource> sourcesByKey = indexSources(request.sources());
        return request.contexts().stream()
                .map(context -> previewForContext(context, sourcesByKey, request))
                .toList();
    }

    private DomainFederationIngestPreviewItemResponse previewForContext(
            DomainFederationContext context,
            Map<String, DomainFederationSource> sourcesByKey,
            DomainFederationValidationRequest request) {
        if (context == null) {
            return new DomainFederationIngestPreviewItemResponse(
                    null,
                    null,
                    null,
                    null,
                    false,
                    "domain_context entry is required for preview generation",
                    null);
        }
        DomainFederationSource source = sourcesByKey.get(context.sourceKey());
        String serviceKey = source != null ? source.serviceKey() : null;
        String query = StringUtils.hasText(context.label()) ? context.label().trim() : context.contextKey();

        if (!StringUtils.hasText(serviceKey)) {
            return new DomainFederationIngestPreviewItemResponse(
                    context.contextKey(),
                    context.sourceKey(),
                    null,
                    query,
                    false,
                    "preview requires a known source with serviceKey",
                    null);
        }

        try {
            DomainFederationContextQueryResponse preview = domainFederationQueryService.context(
                    serviceKey,
                    null,
                    request.tenantId(),
                    request.environment(),
                    "node",
                    context.contextKey(),
                    null,
                    null,
                    query,
                    PREVIEW_LIMIT);
            return new DomainFederationIngestPreviewItemResponse(
                    context.contextKey(),
                    context.sourceKey(),
                    serviceKey,
                    query,
                    true,
                    null,
                    preview);
        } catch (RuntimeException ex) {
            return new DomainFederationIngestPreviewItemResponse(
                    context.contextKey(),
                    context.sourceKey(),
                    serviceKey,
                    query,
                    false,
                    ex.getMessage(),
                    null);
        }
    }

    private Map<String, DomainFederationSource> indexSources(List<DomainFederationSource> sources) {
        Map<String, DomainFederationSource> indexed = new HashMap<>();
        if (sources == null) {
            return indexed;
        }
        for (DomainFederationSource source : sources) {
            if (source != null && StringUtils.hasText(source.sourceKey())) {
                indexed.put(source.sourceKey(), source);
            }
        }
        return indexed;
    }
}
