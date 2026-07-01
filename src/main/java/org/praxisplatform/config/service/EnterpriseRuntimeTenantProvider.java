package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;

public interface EnterpriseRuntimeTenantProvider {

    EnterpriseRuntimeTenantsResponse getTenants(EnterpriseRuntimeContextRequest request);
}
