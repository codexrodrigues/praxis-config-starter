package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;

/** No host authority is installed: never synthesize navigation or successful emptiness. */
public class DefaultEnterpriseRuntimeNavigationProvider implements EnterpriseRuntimeNavigationProvider {
    @Override
    public EnterpriseRuntimeNavigationResponse getNavigation(EnterpriseRuntimeContextRequest request) {
        throw EnterpriseRuntimeProviderUnavailable.failure(request);
    }
}
