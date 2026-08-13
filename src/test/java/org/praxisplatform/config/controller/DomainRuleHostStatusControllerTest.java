package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleHostStatusIngestionResponse;
import org.praxisplatform.config.dto.DomainRuleHostStatusRequest;
import org.praxisplatform.config.dto.DomainRuleHostStatusSummaryResponse;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleHostStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class DomainRuleHostStatusControllerTest {
  private final DomainRuleHostStatusService service = mock(DomainRuleHostStatusService.class);
  private final DomainRuleGovernancePrincipalResolver resolver =
      mock(DomainRuleGovernancePrincipalResolver.class);
  private final DomainRuleHostStatusController controller =
      new DomainRuleHostStatusController(service, resolver);

  @Test
  void ingestionUsesServerResolvedObserverIdentity() {
    Instant now = Instant.parse("2026-08-13T12:00:00Z");
    var servletRequest = new MockHttpServletRequest();
    var principal = new DomainRuleGovernancePrincipal("tenant-server", "service:host-a", "prod");
    var request = new DomainRuleHostStatusRequest(
        "benefit.eligibility", "snap-2", "A".repeat(64), 7L, true, "quickstart/1.0",
        "1.4", "praxis-json-logic/1.0", "B".repeat(64), "C".repeat(64), null, now);
    var result = new DomainRuleHostStatusIngestionResponse(true, now);
    when(resolver.resolve(servletRequest, "tenant-caller", "test", "RULE_EXECUTION_OBSERVER"))
        .thenReturn(principal);
    when(service.ingest(request, principal)).thenReturn(result);

    var response = controller.ingest(request, "tenant-caller", "test", servletRequest);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isSameAs(result);
    verify(service).ingest(request, principal);
  }

  @Test
  void summaryUsesReaderRoleAndServerResolvedScope() {
    Instant now = Instant.parse("2026-08-13T12:00:00Z");
    var servletRequest = new MockHttpServletRequest();
    var principal = new DomainRuleGovernancePrincipal("tenant-server", "auditor-a", "prod");
    var result = new DomainRuleHostStatusSummaryResponse(
        "benefit.eligibility", "snap-2", "A".repeat(64), 7L,
        "quickstart/1.0", "1.4", "praxis-json-logic/1.0", "B".repeat(64), "C".repeat(64),
        3, 1, 1, 1, 0, 0, now, now.minusSeconds(120));
    when(resolver.resolve(servletRequest, "tenant-caller", "test", "RULE_SNAPSHOT_READER"))
        .thenReturn(principal);
    when(service.summarizeHead("benefit.eligibility", principal)).thenReturn(result);

    var response = controller.summarizeHead(
        "benefit.eligibility", "tenant-caller", "test", servletRequest);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(result);
    verify(service).summarizeHead("benefit.eligibility", principal);
  }
}
