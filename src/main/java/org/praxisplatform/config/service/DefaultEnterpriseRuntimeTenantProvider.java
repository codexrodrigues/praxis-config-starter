package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;

/** Host tenant discovery remains distinct from context choice selection and is unavailable without a host source. */
public class DefaultEnterpriseRuntimeTenantProvider implements EnterpriseRuntimeTenantProvider {
    @Override
    public EnterpriseRuntimeTenantsResponse getTenants(EnterpriseRuntimeContextRequest request) {
        throw EnterpriseRuntimeProviderUnavailable.failure(request);
    }
}
