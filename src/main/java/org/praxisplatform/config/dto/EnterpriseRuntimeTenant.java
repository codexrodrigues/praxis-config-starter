package org.praxisplatform.config.dto;

public record EnterpriseRuntimeTenant(
        String tenantId,
        String label,
        boolean active) {
}
