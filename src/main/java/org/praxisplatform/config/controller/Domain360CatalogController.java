package org.praxisplatform.config.controller;

import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.Domain360CatalogResponse;
import org.praxisplatform.config.service.Domain360CatalogService;
import org.praxisplatform.config.service.DomainFederationQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("configDomain360CatalogController")
@RequestMapping("/api/praxis/config/domain-360")
@RequiredArgsConstructor
@ConditionalOnBean(DomainFederationQueryService.class)
@ConditionalOnProperty(prefix = "praxis.domain-360", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Domain360CatalogController {

    private final Domain360CatalogService domain360CatalogService;

    @GetMapping
    public ResponseEntity<Domain360CatalogResponse> catalog(
            @RequestParam(required = false) String serviceKey,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) String contextKey,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domain360CatalogService.catalog(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                contextKey,
                q,
                limit));
    }
}
