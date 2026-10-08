package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.EnterpriseRuntimeContextChoicesResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;

/** No host authority is installed: never fabricate an empty choice catalog. */
public class DefaultEnterpriseRuntimeContextChoiceProvider implements EnterpriseRuntimeContextChoiceProvider {
    @Override
    public EnterpriseRuntimeContextChoicesResponse getChoices(
            EnterpriseRuntimeContextRequest request, String cursor, int pageSize) {
        throw EnterpriseRuntimeProviderUnavailable.failure(request);
    }
}
