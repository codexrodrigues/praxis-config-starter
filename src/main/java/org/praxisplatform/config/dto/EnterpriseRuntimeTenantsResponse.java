package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;

public record EnterpriseRuntimeTenantsResponse(
        String schemaVersion,
        EnterpriseRuntimeTenant activeTenant,
        List<EnterpriseRuntimeTenant> tenants,
        List<String> capabilities,
        Instant resolvedAt) {

    public EnterpriseRuntimeTenantsResponse {
        tenants = tenants == null ? List.of() : List.copyOf(tenants);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
