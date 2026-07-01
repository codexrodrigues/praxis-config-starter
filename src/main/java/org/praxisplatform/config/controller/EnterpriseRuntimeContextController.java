package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/runtime")
public class EnterpriseRuntimeContextController {

    private final AiPrincipalContextResolver principalContextResolver;
    private final EnterpriseRuntimeContextProvider runtimeContextProvider;
    private final EnterpriseRuntimeTenantProvider runtimeTenantProvider;

    public EnterpriseRuntimeContextController(
            AiPrincipalContextResolver principalContextResolver,
            EnterpriseRuntimeContextProvider runtimeContextProvider,
            EnterpriseRuntimeTenantProvider runtimeTenantProvider) {
        this.principalContextResolver = principalContextResolver;
        this.runtimeContextProvider = runtimeContextProvider;
        this.runtimeTenantProvider = runtimeTenantProvider;
    }

    @GetMapping("/context")
    public ResponseEntity<EnterpriseRuntimeContextResponse> getContext(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Profile-ID", required = false) String activeProfileId,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey) {
        EnterpriseRuntimeContextRequest runtimeRequest = runtimeRequest(
                request,
                tenantId,
                userId,
                environment,
                acceptLanguage,
                timezone,
                activeProfileId,
                activeModuleKey);
        return ResponseEntity.ok(runtimeContextProvider.getContext(runtimeRequest));
    }

    @GetMapping("/tenants")
    public ResponseEntity<EnterpriseRuntimeTenantsResponse> getTenants(
            HttpServletRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Profile-ID", required = false) String activeProfileId,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey) {
        EnterpriseRuntimeContextRequest runtimeRequest = runtimeRequest(
                request,
                tenantId,
                userId,
                environment,
                acceptLanguage,
                timezone,
                activeProfileId,
                activeModuleKey);
        return ResponseEntity.ok(runtimeTenantProvider.getTenants(runtimeRequest));
    }

    private EnterpriseRuntimeContextRequest runtimeRequest(
            HttpServletRequest request,
            String tenantId,
            String userId,
            String environment,
            String acceptLanguage,
            String timezone,
            String activeProfileId,
            String activeModuleKey) {
        AiPrincipalContext principalContext =
                principalContextResolver.resolve(request, tenantId, userId, environment);
        return new EnterpriseRuntimeContextRequest(
                principalContext,
                firstLanguageTag(acceptLanguage),
                normalize(timezone),
                normalize(activeProfileId),
                normalize(activeModuleKey));
    }

    private String firstLanguageTag(String acceptLanguage) {
        String normalized = normalize(acceptLanguage);
        if (normalized == null) {
            return null;
        }
        return List.of(normalized.split(",", 2)).get(0).trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
