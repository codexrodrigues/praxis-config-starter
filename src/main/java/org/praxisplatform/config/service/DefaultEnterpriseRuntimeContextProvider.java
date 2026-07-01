package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.List;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;

public class DefaultEnterpriseRuntimeContextProvider implements EnterpriseRuntimeContextProvider {

    static final String SCHEMA_VERSION = "praxis-enterprise-runtime-context.v1";

    @Override
    public EnterpriseRuntimeContextResponse getContext(EnterpriseRuntimeContextRequest request) {
        AiPrincipalContext principal = request != null ? request.principalContext() : null;
        String tenantId = principal != null ? principal.tenantId() : null;
        String userId = principal != null ? principal.userId() : null;
        return new EnterpriseRuntimeContextResponse(
                SCHEMA_VERSION,
                new EnterpriseRuntimeUser(
                        userId,
                        null,
                        principal != null && principal.resolvedFromServerPrincipal()),
                new EnterpriseRuntimeTenant(
                        tenantId,
                        tenantId,
                        tenantId != null),
                principal != null ? principal.environment() : null,
                request != null ? request.locale() : null,
                request != null ? request.timezone() : null,
                request != null ? request.activeProfileId() : null,
                request != null ? request.activeModuleKey() : null,
                List.of("runtime.context.read"),
                Instant.now());
    }
}
