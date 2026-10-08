package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEventsResponse;

/** No host authority is installed: never synthesize security events or successful emptiness. */
public class DefaultEnterpriseRuntimeSecurityEventProvider implements EnterpriseRuntimeSecurityEventProvider {
    @Override
    public EnterpriseRuntimeSecurityEventsResponse getSecurityEvents(EnterpriseRuntimeContextRequest request) {
        throw EnterpriseRuntimeProviderUnavailable.failure(request);
    }
}
