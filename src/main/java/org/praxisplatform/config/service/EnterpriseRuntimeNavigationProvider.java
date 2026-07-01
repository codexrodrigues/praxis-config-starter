package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;

public interface EnterpriseRuntimeNavigationProvider {

    EnterpriseRuntimeNavigationResponse getNavigation(EnterpriseRuntimeContextRequest request);
}
