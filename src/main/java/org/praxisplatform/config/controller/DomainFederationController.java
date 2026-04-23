package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.service.DomainFederationContractValidator;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("configDomainFederationController")
@RequestMapping("/api/praxis/config/domain-federation")
@RequiredArgsConstructor
public class DomainFederationController {

    private final DomainFederationContractValidator domainFederationContractValidator;

    @PostMapping("/dry-run")
    public ResponseEntity<DomainFederationValidationReport> dryRun(
            @RequestBody(required = false) DomainFederationValidationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        DomainFederationValidationRequest effectiveRequest = applyScopeFallback(request, tenantId, environment);
        return ResponseEntity.ok(domainFederationContractValidator.validate(effectiveRequest));
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
