package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class DomainRuleRolloutPolicyControllerTest {
  private final DomainRuleRolloutPolicyService service = mock(DomainRuleRolloutPolicyService.class);
  private final DomainRuleGovernancePrincipalResolver resolver =
      mock(DomainRuleGovernancePrincipalResolver.class);
  private final DomainRuleRolloutPolicyController controller =
      new DomainRuleRolloutPolicyController(service, resolver);

  @Test void routesAuthorReviewerOperatorAndReaderThroughDistinctServerRoles() {
    var request = new MockHttpServletRequest();
    var author = new DomainRuleGovernancePrincipal("tenant", "author", "prod");
    var reviewer = new DomainRuleGovernancePrincipal("tenant", "reviewer", "prod");
    var operator = new DomainRuleGovernancePrincipal("tenant", "operator", "prod");
    var reader = new DomainRuleGovernancePrincipal("tenant", "auditor", "prod");
    UUID policyId = UUID.randomUUID();
    String etag = UUID.randomUUID().toString();
    var body = new DomainRuleRolloutPolicyCreateRequest(
        "rules", "safe", "REQUIRED", 1, BigDecimal.ONE, true, 120L, 600L);
    var policy = new DomainRuleRolloutPolicyResponse(policyId, "rules", "safe", 1,
        "DRAFT", "REQUIRED", 1, BigDecimal.ONE, true, 120L, 600L,
        "author", Instant.now(), null, null, null, null);
    var mutation = new DomainRuleRolloutPolicyMutationResponse(policy, 1, etag);
    var catalog = new DomainRuleRolloutPolicyCatalogResponse("rules", 1, etag, null, List.of(policy));
    when(resolver.resolve(request, "caller", "test", "RULE_DEFINITION_AUTHOR")).thenReturn(author);
    when(resolver.resolve(request, "caller", "test", "RULE_DEFINITION_APPROVER")).thenReturn(reviewer);
    when(resolver.resolve(request, "caller", "test", "RULE_SNAPSHOT_OPERATOR")).thenReturn(operator);
    when(resolver.resolve(request, "caller", "test", "RULE_SNAPSHOT_READER")).thenReturn(reader);
    when(service.create(body, author)).thenReturn(mutation);
    when(service.approve(policyId, reviewer)).thenReturn(mutation);
    when(service.activate(policyId, "\"old\"", operator)).thenReturn(mutation);
    when(service.catalog("rules", reader)).thenReturn(Optional.of(catalog));
    when(service.timeline("rules", reader)).thenReturn(List.of());

    assertThat(controller.create(body, "caller", "test", request).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.approve(policyId, "caller", "test", request).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(controller.activate(policyId, "\"old\"", "caller", "test", request)
        .getHeaders().getETag()).isEqualTo('"' + etag + '"');
    assertThat(controller.catalog("rules", null, "caller", "test", request).getBody())
        .isSameAs(catalog);
    assertThat(controller.timeline("rules", "caller", "test", request).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test void catalogSupportsWeakConditionalRead() {
    var request = new MockHttpServletRequest();
    var reader = new DomainRuleGovernancePrincipal("tenant", "auditor", "prod");
    String etag = UUID.randomUUID().toString();
    when(resolver.resolve(request, null, null, "RULE_SNAPSHOT_READER")).thenReturn(reader);
    when(service.catalog("rules", reader)).thenReturn(Optional.of(
        new DomainRuleRolloutPolicyCatalogResponse("rules", 1, etag, null, List.of())));

    var response = controller.catalog("rules", "W/\"" + etag + "\"", null, null, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getETag()).isEqualTo('"' + etag + '"');
  }
}
