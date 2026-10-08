package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextChoicesResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;

/** Host-owned discovery of complete safe choices for a validated session, including initial selection. */
@FunctionalInterface
public interface EnterpriseRuntimeContextChoiceProvider {
    EnterpriseRuntimeContextChoicesResponse getChoices(EnterpriseRuntimeContextRequest request, String cursor, int pageSize);
}
