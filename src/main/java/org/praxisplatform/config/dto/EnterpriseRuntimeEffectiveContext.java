package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/** Host-safe materialization of the currently authorized choice. */
@Schema(description = "Effective context for a ready session. Every context-bound operation must still compare "
        + "contextVersion with live server evidence; this projection does not grant data or authoring access.")
public record EnterpriseRuntimeEffectiveContext(
        @Schema(description = "Opaque host version of selection and current authorization/audience facts. Changes "
                + "when those facts change, independently of selectionVersion and configuration ETag.", minLength = 1)
        String contextVersion,
        @Schema(description = "Safe active tenant label and identifier. It does not reveal the underlying entitlement tuple.")
        EnterpriseRuntimeTenant activeTenant,
        @Schema(description = "Safe identifier of the administrative organization selected and confirmed by the host for this "
                + "contextVersion. It is a current scope fact, not a role, capability, grant or layout-assignment authorization.",
                nullable = true)
        String activeOrganizationId,
        @Schema(description = "Host-selected runtime environment; callers must request their Config target environment "
                + "explicitly rather than reinterpret an omitted/global target.")
        String environment,
        @Schema(description = "Preferred locale for presentation only; never a grant or identity selector.", nullable = true)
        String locale,
        @Schema(description = "Preferred presentation timezone only; does not change authorization.", nullable = true)
        String timezone,
        @Schema(description = "Safe active profile projection confirmed by the host, not caller-controlled authority.", nullable = true)
        String activeProfileId,
        @Schema(description = "Current experience module hint, not a capability or membership selector.", nullable = true)
        String activeModuleKey,
        @Schema(description = "Minimum host-approved authorities required by public field-access vocabulary. This is not "
                + "a raw role or grant list and is never inferred from capabilities.")
        List<String> authorities,
        @Schema(description = "Only host-approved public capabilities for presentation; raw roles, grants and policies "
                + "must remain private. The backend authorizes every operation independently.")
        List<String> capabilities) {
    public EnterpriseRuntimeEffectiveContext {
        if (contextVersion == null || contextVersion.isBlank()) {
            throw new IllegalArgumentException("Context version required");
        }
        Objects.requireNonNull(activeTenant, "Active tenant projection required");
        if (activeOrganizationId != null && activeOrganizationId.isBlank()) {
            throw new IllegalArgumentException("Active organization projection must be non-blank when present");
        }
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("Runtime environment required");
        }
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
