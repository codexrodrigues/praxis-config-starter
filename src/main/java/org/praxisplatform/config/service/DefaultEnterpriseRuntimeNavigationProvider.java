package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.List;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;

public class DefaultEnterpriseRuntimeNavigationProvider implements EnterpriseRuntimeNavigationProvider {

    static final String SCHEMA_VERSION = "praxis-enterprise-runtime-navigation.v1";

    @Override
    public EnterpriseRuntimeNavigationResponse getNavigation(EnterpriseRuntimeContextRequest request) {
        return new EnterpriseRuntimeNavigationResponse(
                SCHEMA_VERSION,
                List.of(),
                List.of("runtime.navigation.read"),
                Instant.now());
    }
}
