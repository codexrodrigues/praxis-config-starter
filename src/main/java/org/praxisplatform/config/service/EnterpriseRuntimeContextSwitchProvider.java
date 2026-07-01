package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchResponse;

public interface EnterpriseRuntimeContextSwitchProvider {

    EnterpriseRuntimeContextSwitchResponse switchContext(
            EnterpriseRuntimeContextRequest currentRequest,
            EnterpriseRuntimeContextSwitchCommand command);
}
