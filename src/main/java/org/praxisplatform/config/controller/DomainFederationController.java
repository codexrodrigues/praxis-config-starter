package org.praxisplatform.config.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationIngestDryRunResponse;
import org.praxisplatform.config.dto.DomainFederationReleaseResponse;
import org.praxisplatform.config.dto.DomainFederationReleaseValidationResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.service.DomainFederationContractValidator;
import org.praxisplatform.config.service.DomainFederationIngestDryRunService;
import org.praxisplatform.config.service.DomainFederationIngestPersistenceService;
import org.praxisplatform.config.service.DomainFederationQueryService;
import org.praxisplatform.config.service.DomainFederationReleaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("configDomainFederationController")
@RequestMapping("/api/praxis/config/domain-federation")
@RequiredArgsConstructor
public class DomainFederationController {

    private final DomainFederationContractValidator domainFederationContractValidator;
    private final DomainFederationIngestDryRunService domainFederationIngestDryRunService;
    private final DomainFederationIngestPersistenceService domainFederationIngestPersistenceService;
    private final DomainFederationQueryService domainFederationQueryService;
    private final DomainFederationReleaseService domainFederationReleaseService;

    @GetMapping("/context")
    public ResponseEntity<DomainFederationContextQueryResponse> context(
            @RequestParam(required = false) String serviceKey,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false, defaultValue = "node") String itemType,
            @RequestParam(required = false) String contextKey,
            @RequestParam(required = false) String nodeType,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String policyProfile,
            @RequestParam(required = false) Double minConfidence,
            @RequestParam(required = false) Boolean includeDenied,
            @RequestParam(required = false) Boolean includeLowConfidence,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainFederationQueryService.context(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                itemType,
                contextKey,
                nodeType,
                relationshipType,
                q,
                limit,
                new DomainFederationRetrievalPolicyOptions(policyProfile, minConfidence, includeDenied, includeLowConfidence)));
    }

    @PostMapping("/dry-run")
    public ResponseEntity<DomainFederationValidationReport> dryRun(
            @RequestBody(required = false) DomainFederationValidationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        DomainFederationValidationRequest effectiveRequest = applyScopeFallback(request, tenantId, environment);
        return ResponseEntity.ok(domainFederationContractValidator.validate(effectiveRequest));
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody(required = false) DomainFederationValidationRequest request,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        if (!dryRun) {
            DomainFederationValidationRequest effectiveRequest = applyScopeFallback(request, tenantId, environment);
            return ResponseEntity.ok(domainFederationIngestPersistenceService.ingestCandidate(effectiveRequest));
        }
        DomainFederationValidationRequest effectiveRequest = applyScopeFallback(request, tenantId, environment);
        DomainFederationIngestDryRunResponse response = domainFederationIngestDryRunService.dryRun(effectiveRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/releases")
    public ResponseEntity<List<DomainFederationReleaseResponse>> releases(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainFederationReleaseService.releases(tenantId, environment, status, limit));
    }

    @GetMapping("/releases/{releaseKey}/validation")
    public ResponseEntity<DomainFederationReleaseValidationResponse> validation(
            @PathVariable String releaseKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainFederationReleaseService.validation(releaseKey, tenantId, environment));
    }

    private DomainFederationValidationRequest applyScopeFallback(
            DomainFederationValidationRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            return null;
        }
        return new DomainFederationValidationRequest(
                request.schemaVersion(),
                firstText(request.tenantId(), tenantId),
                firstText(request.environment(), environment),
                request.sources(),
                request.contexts(),
                request.contextRelationships(),
                request.contracts(),
                request.resolutions());
    }

    private String firstText(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        return StringUtils.hasText(fallback) ? fallback : primary;
    }
}
