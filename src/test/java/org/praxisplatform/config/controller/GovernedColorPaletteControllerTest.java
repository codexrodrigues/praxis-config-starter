package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.GovernedColorPaletteProjectionService;

@Tag("unit")
class GovernedColorPaletteControllerTest {

    @Test
    void advertisesPreviewAndGovernanceOperationsWithCallerCapabilities() {
        GovernedColorPaletteProjectionService service = mock(GovernedColorPaletteProjectionService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.resolve(request, "tenant-a", "dev", "RULE_DEFINITION_READER"))
                .thenReturn(new DomainRuleGovernancePrincipal("tenant-a", "reader", "dev"));
        when(request.isUserInRole("RULE_DEFINITION_AUTHOR")).thenReturn(true);
        when(request.isUserInRole("RULE_DEFINITION_APPROVER")).thenReturn(false);
        when(request.isUserInRole("RULE_SNAPSHOT_PUBLISHER")).thenReturn(false);
        when(service.supportedPurposes()).thenReturn(
                java.util.Set.of("text", "fill", "chart", "state", "surface", "border", "focus"));

        var response = new GovernedColorPaletteController(service, resolver)
                .capabilities("tenant-a", "dev", request)
                .getBody();

        assertThat(response).isNotNull();
        assertThat(response.operations()).contains(
                new org.praxisplatform.config.dto.GovernedColorPaletteCapabilitiesResponse.Operation(
                        "palette.preview", "RULE_DEFINITION_AUTHOR", true),
                new org.praxisplatform.config.dto.GovernedColorPaletteCapabilitiesResponse.Operation(
                        "palette.approve", "RULE_DEFINITION_APPROVER", false));
        assertThat(response.supportedPurposes()).contains("text", "chart", "focus");
    }
}
