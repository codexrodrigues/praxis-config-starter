package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.List;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEventsResponse;

public class DefaultEnterpriseRuntimeSecurityEventProvider implements EnterpriseRuntimeSecurityEventProvider {

    static final String SCHEMA_VERSION = "praxis-enterprise-runtime-security-events.v1";

    @Override
    public EnterpriseRuntimeSecurityEventsResponse getSecurityEvents(EnterpriseRuntimeContextRequest request) {
        return new EnterpriseRuntimeSecurityEventsResponse(
                SCHEMA_VERSION,
                List.of(),
                List.of("runtime.security-events.read"),
                Instant.now());
    }
}
