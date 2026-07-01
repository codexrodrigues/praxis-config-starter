package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEventsResponse;

public interface EnterpriseRuntimeSecurityEventProvider {

    EnterpriseRuntimeSecurityEventsResponse getSecurityEvents(EnterpriseRuntimeContextRequest request);
}
