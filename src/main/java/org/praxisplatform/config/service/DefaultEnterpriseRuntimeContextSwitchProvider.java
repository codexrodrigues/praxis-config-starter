package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;

/** No host authority is installed: never materialize a context or accept a selection. */
public class DefaultEnterpriseRuntimeContextSwitchProvider implements EnterpriseRuntimeContextSwitchProvider {
    @Override
    public EnterpriseRuntimeContextResponse switchContext(
            EnterpriseRuntimeContextRequest request, EnterpriseRuntimeContextSwitchCommand command) {
        throw EnterpriseRuntimeProviderUnavailable.failure(request);
    }
}
