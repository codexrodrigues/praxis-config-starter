package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.List;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;

public class DefaultEnterpriseRuntimeTenantProvider implements EnterpriseRuntimeTenantProvider {

    static final String SCHEMA_VERSION = "praxis-enterprise-runtime-tenants.v1";

    @Override
    public EnterpriseRuntimeTenantsResponse getTenants(EnterpriseRuntimeContextRequest request) {
        AiPrincipalContext principal = request != null ? request.principalContext() : null;
        String tenantId = principal != null ? principal.tenantId() : null;
        EnterpriseRuntimeTenant activeTenant = tenantId == null
                ? null
                : new EnterpriseRuntimeTenant(tenantId, tenantId, true);
        return new EnterpriseRuntimeTenantsResponse(
                SCHEMA_VERSION,
                activeTenant,
                activeTenant == null ? List.of() : List.of(activeTenant),
                List.of("runtime.tenants.read"),
                Instant.now());
    }
}
