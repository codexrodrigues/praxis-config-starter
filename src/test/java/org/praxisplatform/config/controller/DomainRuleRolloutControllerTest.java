package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.springframework.http.HttpStatus;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class DomainRuleRolloutControllerTest {
  private final DomainRuleRolloutService service = mock(DomainRuleRolloutService.class);
  private final DomainRuleGovernancePrincipalResolver resolver =
      mock(DomainRuleGovernancePrincipalResolver.class);
  private final DomainRuleRolloutController controller = new DomainRuleRolloutController(service, resolver);

  @Test void routesCreateProbeReadinessAndCancelThroughServerOwnedPrincipals() {
    var request = new MockHttpServletRequest();
    var operator = new DomainRuleGovernancePrincipal("tenant-server", "operator", "prod");
    var observer = new DomainRuleGovernancePrincipal("tenant-server", "service:host", "prod");
    var reader = new DomainRuleGovernancePrincipal("tenant-server", "auditor", "prod");
    UUID rolloutId = UUID.randomUUID();
    var create = new DomainRuleRolloutCreateRequest("candidate-v2", null);
    var created = new DomainRuleRolloutResponse(rolloutId, "rules", "candidate-v2", "A".repeat(64),
        "active-v1", UUID.randomUUID().toString(), "default", 1, "OBSERVE_ONLY", "PREPARING",
        Instant.now(), null);
    var probe = new DomainRuleCandidateProbeRequest("candidate-v2", "A".repeat(64), false,
        "host/1", null, null, null, null, "UNAVAILABLE", Instant.now());
    var readiness = new DomainRuleRolloutReadinessResponse(rolloutId, "rules", "candidate-v2",
        "BLOCKED", "OBSERVE_ONLY", 0, 0, 1, 0, 0, 1, 0, true, Instant.now());
    when(resolver.resolve(request, "caller", "test", "RULE_SNAPSHOT_OPERATOR")).thenReturn(operator);
    when(resolver.resolve(request, "caller", "test", "RULE_EXECUTION_OBSERVER")).thenReturn(observer);
    when(resolver.resolve(request, "caller", "test", "RULE_SNAPSHOT_READER")).thenReturn(reader);
    when(service.create(create, operator, "\"etag\"")).thenReturn(created);
    when(service.probe(rolloutId, probe, observer))
        .thenReturn(new DomainRuleCandidateProbeResponse(true, probe.observedAtUtc()));
    when(service.readiness(rolloutId, reader)).thenReturn(readiness);
    var catalog = new DomainRuleRolloutCatalogResponse(
        "rules", java.util.List.of(), java.util.List.of("CREATE_ROLLOUT"));
    when(resolver.hasRole(request, "RULE_SNAPSHOT_OPERATOR")).thenReturn(true);
    when(service.catalog("rules", reader, true)).thenReturn(catalog);
    var pending = new DomainRulePendingRolloutResponse(
        rolloutId, "rules", "candidate-v2", "A".repeat(64), "PREPARING", null);
    when(service.pending("rules", observer)).thenReturn(Optional.of(pending));

    assertThat(controller.create(create, "\"etag\"", "caller", "test", request).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.probe(rolloutId, probe, "caller", "test", request).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(controller.readiness(rolloutId, "caller", "test", request).getBody()).isSameAs(readiness);
    assertThat(controller.catalog("rules", "caller", "test", request).getBody()).isSameAs(catalog);
    assertThat(controller.cancel(rolloutId, "caller", "test", request).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(controller.pending("rules", "caller", "test", request).getBody()).isSameAs(pending);
    verify(service).cancel(rolloutId, operator);
  }

  @Test void preservesStableControlPlaneHttpStatus() {
    var response = controller.handleControlPlaneFailure(
        new DomainRuleSnapshotControlPlaneException(HttpStatus.CONFLICT, "quorum not ready"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).containsEntry("code", "CONFLICT")
        .containsEntry("message", "quorum not ready");
  }
}
