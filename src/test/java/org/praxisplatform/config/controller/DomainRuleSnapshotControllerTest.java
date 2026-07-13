package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
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
}
