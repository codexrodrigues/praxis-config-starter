package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;

/** No host authority is installed: never synthesize session state or successful emptiness. */
public class DefaultEnterpriseRuntimeContextProvider implements EnterpriseRuntimeContextProvider {
    @Override
    public EnterpriseRuntimeContextResponse getContext(EnterpriseRuntimeContextRequest request) {
        throw EnterpriseRuntimeProviderUnavailable.failure(request);
    }
}
