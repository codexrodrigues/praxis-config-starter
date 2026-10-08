package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;

/** Host boundary for a complete authorized choice and selection compare-and-set. */
@FunctionalInterface
public interface EnterpriseRuntimeContextSwitchProvider {
    EnterpriseRuntimeContextResponse switchContext(
            EnterpriseRuntimeContextRequest currentRequest, EnterpriseRuntimeContextSwitchCommand command);
}
