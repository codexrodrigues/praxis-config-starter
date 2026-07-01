package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;

public interface EnterpriseRuntimeContextProvider {

    EnterpriseRuntimeContextResponse getContext(EnterpriseRuntimeContextRequest request);
}
