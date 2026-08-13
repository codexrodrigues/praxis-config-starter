package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchRequest;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchResponse;
import org.praxisplatform.config.dto.DomainRuleExecutionSummaryResponse;
import org.praxisplatform.config.service.DomainRuleExecutionObservationService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class DomainRuleExecutionObservationControllerTest {
  private final DomainRuleExecutionObservationService service =
      mock(DomainRuleExecutionObservationService.class);
  private final DomainRuleGovernancePrincipalResolver principalResolver =
      mock(DomainRuleGovernancePrincipalResolver.class);
  private final DomainRuleExecutionObservationController controller =
      new DomainRuleExecutionObservationController(service, principalResolver);

  @Test
  void ingestionRequiresTheServerResolvedExecutionObserver() {
    var servletRequest = new MockHttpServletRequest();
    var principal = new DomainRuleGovernancePrincipal("tenant-server", "service:host-a", "prod");
    var request = new DomainRuleExecutionObservationBatchRequest(List.of());
    var result = new DomainRuleExecutionObservationBatchResponse(1, 0);
    when(principalResolver.resolve(
        servletRequest, "tenant-caller", "test", "RULE_EXECUTION_OBSERVER"))
        .thenReturn(principal);
    when(service.ingest(request, principal)).thenReturn(result);

    var response = controller.ingest(
        request, "tenant-caller", "test", servletRequest);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isSameAs(result);
    verify(service).ingest(request, principal);
  }

  @Test
  void summaryUsesReaderRoleAndAuthenticatedScope() {
    var servletRequest = new MockHttpServletRequest();
    var principal = new DomainRuleGovernancePrincipal("tenant-server", "auditor-a", "prod");
    var summary = new DomainRuleExecutionSummaryResponse(
        "grant-rules", "snapshot-7", "A".repeat(64), 7, 4, 2,
        Map.of("ALLOW", 4L), null, null);
    when(principalResolver.resolve(
        servletRequest, "tenant-caller", "test", "RULE_SNAPSHOT_READER"))
        .thenReturn(principal);
    when(service.summary("snapshot-7", principal)).thenReturn(summary);

    var response = controller.summary(
        "snapshot-7", "tenant-caller", "test", servletRequest);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(summary);
    verify(service).summary("snapshot-7", principal);
  }
}
