package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;

public class DefaultEnterpriseRuntimeContextSwitchProvider implements EnterpriseRuntimeContextSwitchProvider {

    static final String SCHEMA_VERSION = "praxis-enterprise-runtime-context-switch.v1";

    @Override
    public EnterpriseRuntimeContextSwitchResponse switchContext(
            EnterpriseRuntimeContextRequest currentRequest,
            EnterpriseRuntimeContextSwitchCommand command) {
        AiPrincipalContext principal = currentRequest != null ? currentRequest.principalContext() : null;
        String currentTenantId = principal != null ? principal.tenantId() : null;
        String requestedTenantId = normalize(command != null ? command.targetTenantId() : null);
        boolean tenantSwitchRequested = requestedTenantId != null && !requestedTenantId.equals(currentTenantId);
        boolean accepted = !tenantSwitchRequested;

        EnterpriseRuntimeContextResponse effectiveContext = new EnterpriseRuntimeContextResponse(
                DefaultEnterpriseRuntimeContextProvider.SCHEMA_VERSION,
                new EnterpriseRuntimeUser(
                        principal != null ? principal.userId() : null,
                        null,
                        principal != null && principal.resolvedFromServerPrincipal()),
                new EnterpriseRuntimeTenant(
                        currentTenantId,
                        currentTenantId,
                        currentTenantId != null),
                principal != null ? principal.environment() : null,
                firstNonBlank(command != null ? command.locale() : null, currentRequest != null ? currentRequest.locale() : null),
                firstNonBlank(command != null ? command.timezone() : null, currentRequest != null ? currentRequest.timezone() : null),
                firstNonBlank(
                        command != null ? command.targetProfileId() : null,
                        currentRequest != null ? currentRequest.activeProfileId() : null),
                firstNonBlank(
                        command != null ? command.targetModuleKey() : null,
                        currentRequest != null ? currentRequest.activeModuleKey() : null),
                List.of("runtime.context.read"),
                Instant.now());

        return new EnterpriseRuntimeContextSwitchResponse(
                SCHEMA_VERSION,
                accepted,
                accepted
                        ? "Context switch materialized with safe default provider."
                        : "Tenant switch requires a host-owned EnterpriseRuntimeContextSwitchProvider.",
                effectiveContext,
                propagationHeaders(effectiveContext),
                accepted
                        ? List.of("runtime.context.switch", "runtime.context.switch.default-provider")
                        : List.of("runtime.context.switch.denied", "runtime.context.switch.default-provider"),
                Instant.now());
    }

    private Map<String, String> propagationHeaders(EnterpriseRuntimeContextResponse context) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (context.activeTenant() != null) {
            putIfPresent(headers, "X-Tenant-ID", context.activeTenant().tenantId());
        }
        putIfPresent(headers, "X-Env", context.environment());
        putIfPresent(headers, "X-Praxis-Profile-ID", context.activeProfileId());
        putIfPresent(headers, "X-Praxis-Module-Key", context.activeModuleKey());
        putIfPresent(headers, "X-Timezone", context.timezone());
        return headers;
    }

    private void putIfPresent(Map<String, String> headers, String name, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            headers.put(name, normalized);
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        String normalized = normalize(preferred);
        return normalized != null ? normalized : normalize(fallback);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
