package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotHeadStatusResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

@Tag("unit")
class DomainRuleSnapshotControllerTest {
  private final DomainRuleSnapshotService service = mock(DomainRuleSnapshotService.class);
  private final DomainRuleSnapshotController controller = new DomainRuleSnapshotController(service);

  @Test
  void unchangedHeadReturnsNotModifiedWithNoCachePolicy() {
    var active = new DomainRuleSnapshotActivationResponse(
        null, "A".repeat(64), "head-7", 7, "ACTIVE");
    when(service.findActive("tenant-a", "prod", "grant-rules")).thenReturn(Optional.of(active));

    var response = controller.head(
        "grant-rules", "tenant-a", "prod", "W/\"head-7\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"head-7\"");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(response.getBody()).isNull();
  }

  @Test
  void immutableSnapshotUsesContentHashAndLongLivedPrivateCache() {
    String contentHash = "B".repeat(64);
    when(service.findSnapshot("tenant-a", "prod", "snapshot-1"))
        .thenReturn(Optional.of(new DomainRuleSnapshotStoredResponse(null, contentHash)));

    var response = controller.snapshot(
        "snapshot-1", "tenant-a", "prod", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"" + contentHash + "\"");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .contains("private")
        .contains("immutable")
        .contains("max-age=31536000");
  }

  @Test
  void recoveryStatusReturnsHeadEtagWithoutReadingUnverifiedContent() {
    var status = new DomainRuleSnapshotHeadStatusResponse(
        "grant-rules", "legacy-snapshot", 1, 1, 3, "head-3", false,
        "REPUBLICATION_REQUIRED");
    when(service.findHeadStatus("tenant-a", "prod", "grant-rules"))
        .thenReturn(Optional.of(status));

    var response = controller.headStatus("grant-rules", "tenant-a", "prod", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isEqualTo("\"head-3\"");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    assertThat(response.getBody()).isSameAs(status);
    verify(service).findHeadStatus("tenant-a", "prod", "grant-rules");

    var notModified = controller.headStatus(
        "grant-rules", "tenant-a", "prod", "W/\"head-3\"");
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
}
