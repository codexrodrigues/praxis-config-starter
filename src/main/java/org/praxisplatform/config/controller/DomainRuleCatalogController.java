package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.service.DomainRuleCatalogQueryService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bounded discovery surface for governed domain-rule definitions. */
@RestController("configDomainRuleCatalogController")
@RequestMapping("/api/praxis/config/domain-rules/definitions/catalog")
@RequiredArgsConstructor
@ConditionalOnBean({DomainRuleDefinitionRepository.class, DomainRuleCatalogQueryService.class})
public class DomainRuleCatalogController {

    private static final String DEFINITION_READER_ROLE = "RULE_DEFINITION_READER";

    private final DomainRuleCatalogQueryService catalog;
    private final DomainRuleGovernancePrincipalResolver principalResolver;

    @GetMapping
    @Operation(
            summary = "Browse governed domain decisions",
            description = "Returns a bounded, redacted page from the tenant and environment resolved from the authenticated principal. Text query ranks already-scoped catalog candidates; it does not resolve user intent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Catalog page in the server-resolved scope"),
            @ApiResponse(responseCode = "403", description = "Principal is absent or lacks RULE_DEFINITION_READER")
    })
    public ResponseEntity<DomainRuleCatalogResponse> catalog(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        return ResponseEntity.ok(catalog.search(
                query, ruleType, status, resourceKey, page, limit, principal));
    }
}
