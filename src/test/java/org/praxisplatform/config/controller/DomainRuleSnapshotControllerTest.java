package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotHeadStatusResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotVersionResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class DomainRuleSnapshotControllerTest {
  private final DomainRuleSnapshotService service = mock(DomainRuleSnapshotService.class);
  private final DomainRuleGovernancePrincipalResolver principalResolver =
      mock(DomainRuleGovernancePrincipalResolver.class);
  private final DomainRuleSnapshotController controller =
      new DomainRuleSnapshotController(service, principalResolver);

  @Test
  void unchangedHeadReturnsNotModifiedWithNoCachePolicy() {
    MockHttpServletRequest request = readerRequest("tenant-a", "prod");
    var active = new DomainRuleSnapshotActivationResponse(
        null, "A".repeat(64), "head-7", 7, "ACTIVE");
    when(service.findActive("tenant-a", "prod", "grant-rules")).thenReturn(Optional.of(active));

    var response = controller.head(
        "grant-rules", "tenant-a", "prod", "W/\"head-7\"", request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"head-7\"");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(response.getBody()).isNull();
  }

  @Test
  void immutableSnapshotUsesContentHashAndLongLivedPrivateCache() {
    MockHttpServletRequest request = readerRequest("tenant-a", "prod");
    String contentHash = "B".repeat(64);
    when(service.findSnapshot("tenant-a", "prod", "snapshot-1"))
        .thenReturn(Optional.of(new DomainRuleSnapshotStoredResponse(null, contentHash)));

    var response = controller.snapshot(
        "snapshot-1", "tenant-a", "prod", null, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"" + contentHash + "\"");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .contains("private")
        .contains("immutable")
        .contains("max-age=31536000");
  }

  @Test
  void versionCatalogUsesAuthenticatedScopeAndNoCache() {
    MockHttpServletRequest request = readerRequest("tenant-a", "prod");
    var versions = List.of(new DomainRuleSnapshotVersionResponse(
        "snapshot-2", "grant-rules", 2, 2, "B".repeat(64),
        "publisher", "2026-07-14T10:00:00Z", true, "READY", "ACTIVE"));
    when(service.listVersions("tenant-a", "prod", "grant-rules", 25))
        .thenReturn(versions);

    var response = controller.versions(
        "grant-rules", 25, "tenant-a", "prod", request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(response.getBody()).isSameAs(versions);
    verify(service).listVersions("tenant-a", "prod", "grant-rules", 25);
  }

  @Test
  void explicitActivationUsesAuthenticatedOperatorAndStrongHeadEtag() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    when(principalResolver.resolve(
        request, "tenant-caller", "test", "RULE_SNAPSHOT_OPERATOR"))
        .thenReturn(new DomainRuleGovernancePrincipal(
            "tenant-server", "operator-a", "prod"));
    var activated = new DomainRuleSnapshotActivationResponse(
        null, "A".repeat(64), "head-9", 9, "ACTIVATED");
    UUID rolloutId = UUID.randomUUID();
    when(service.activatePublished(
        "snapshot-3", "operator-a", "tenant-server", "prod", "\"head-8\"", rolloutId))
        .thenReturn(activated);

    var response = controller.activate(
        "snapshot-3", "tenant-caller", "test", "\"head-8\"", rolloutId, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"head-9\"");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(response.getBody()).isSameAs(activated);
    verify(service).activatePublished(
        "snapshot-3", "operator-a", "tenant-server", "prod", "\"head-8\"", rolloutId);
  }

  @Test
  void recoveryStatusReturnsHeadEtagWithoutReadingUnverifiedContent() {
    MockHttpServletRequest request = readerRequest("tenant-a", "prod");
    var status = new DomainRuleSnapshotHeadStatusResponse(
        "grant-rules", "legacy-snapshot", 1, 1, 3, "head-3", false,
        "REPUBLICATION_REQUIRED");
    when(service.findHeadStatus("tenant-a", "prod", "grant-rules"))
        .thenReturn(Optional.of(status));

    var response = controller.headStatus("grant-rules", "tenant-a", "prod", null, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"head-3\"");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(response.getBody()).isSameAs(status);
    verify(service).findHeadStatus("tenant-a", "prod", "grant-rules");

    var notModified = controller.headStatus(
        "grant-rules", "tenant-a", "prod", "W/\"head-3\"", request);
    assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(notModified.getHeaders().getETag()).isEqualTo("\"head-3\"");
    assertThat(notModified.getBody()).isNull();
  }

  @Test
  void compositionManifestEndpointReturnsServerCanonicalDigest() {
    DomainRuleCompositionManifestRequest request = new DomainRuleCompositionManifestRequest(
        null, java.util.List.of(), "quickstart", "quickstart/1.0", "2026-07-15T20:00:00Z", null);
    DomainRuleCompositionManifestResponse manifest = new DomainRuleCompositionManifestResponse(
        "praxis-rule-composition/1", "A".repeat(64), "B".repeat(64), null);
    when(service.prepareCompositionManifest(request, "tenant-a", "prod")).thenReturn(manifest);

    var response = controller.compositionManifest(request, "tenant-a", "prod");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(manifest);
    verify(service).prepareCompositionManifest(request, "tenant-a", "prod");
  }

  @Test
  void invalidPlanningCatalogCoordinateReturnsStableBadRequest() {
    var exception = new org.praxisplatform.rules.plan.RulePlanException(
        org.praxisplatform.rules.plan.RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID,
        "Java implementation version is incompatible", "calculation");

    var response = controller.handleInvalidPlan(exception);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("code", "PLAN_COMPATIBILITY_INVALID");
  }

  @Test
  void readScopeComesFromTheAuthenticatedPrincipalInsteadOfCallerHeaders() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    when(principalResolver.resolve(
        request, "tenant-caller", "test", "RULE_SNAPSHOT_READER"))
        .thenReturn(new DomainRuleGovernancePrincipal(
            "tenant-server", "service:ergon", "prod"));
    var active = new DomainRuleSnapshotActivationResponse(
        null, "A".repeat(64), "head-8", 8, "ACTIVE");
    when(service.findActive("tenant-server", "prod", "grant-rules"))
        .thenReturn(Optional.of(active));

    var response = controller.head(
        "grant-rules", "tenant-caller", "test", null, request);

    assertThat(response.getBody()).isSameAs(active);
    verify(service).findActive("tenant-server", "prod", "grant-rules");
  }

  private MockHttpServletRequest readerRequest(String tenantId, String environment) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    when(principalResolver.resolve(
        request, tenantId, environment, "RULE_SNAPSHOT_READER"))
        .thenReturn(new DomainRuleGovernancePrincipal(
            tenantId, "service:ergon", environment));
    return request;
  }
}
