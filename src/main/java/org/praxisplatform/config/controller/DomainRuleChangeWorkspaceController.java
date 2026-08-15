package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceCreateRequest;
import org.praxisplatform.config.contract.DomainRuleChangeWorkspaceContract;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceResponse;
import org.praxisplatform.config.dto.DomainRuleChangeWorkspaceUpdateRequest;
import org.praxisplatform.config.dto.DomainRuleTestScenarioRequest;
import org.praxisplatform.config.dto.DomainRuleTestScenarioResponse;
import org.praxisplatform.config.contract.DomainRuleTestRunRecordRequest;
import org.praxisplatform.config.contract.DomainRuleTestRunResponse;
import org.praxisplatform.config.dto.DomainRuleWorkspaceReviewRequest;
import org.praxisplatform.config.dto.DomainRuleWorkspaceReviewResponse;
import org.praxisplatform.config.dto.DomainRuleWorkspaceCapabilityResponse;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleTestRunService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Governed authoring workspaces and reusable outcome scenarios for Policy Studio. */
@RestController("configDomainRuleChangeWorkspaceController")
@RequestMapping(DomainRuleChangeWorkspaceContract.RESOURCE_PATH)
@RequiredArgsConstructor
public class DomainRuleChangeWorkspaceController {
  private final DomainRuleChangeWorkspaceService service;
  private final DomainRuleGovernancePrincipalResolver principalResolver;
  private final DomainRuleTestRunService testRunService;

  @PostMapping
  public ResponseEntity<DomainRuleChangeWorkspaceResponse> create(
      @RequestBody DomainRuleChangeWorkspaceCreateRequest request,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleChangeWorkspaceResponse response = service.create(
        request, principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR"));
    return withEtag(HttpStatus.CREATED, response, response.etag());
  }

  @GetMapping
  public ResponseEntity<List<DomainRuleChangeWorkspaceResponse>> list(
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(service.list(
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_READER")));
  }

  @GetMapping("/{workspaceId}")
  public ResponseEntity<DomainRuleChangeWorkspaceResponse> get(
      @PathVariable UUID workspaceId,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleChangeWorkspaceResponse response = service.get(
        workspaceId, principal(servletRequest, tenant, environment, "RULE_DEFINITION_READER"));
    if (matchesWeak(ifNoneMatch, response.etag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(response.etag()).cacheControl(CacheControl.noCache()).build();
    }
    return withEtag(HttpStatus.OK, response, response.etag());
  }

  @GetMapping("/{workspaceId}/capabilities")
  public ResponseEntity<DomainRuleWorkspaceCapabilityResponse> capabilities(
      @PathVariable UUID workspaceId,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal = principal(
        servletRequest, tenant, environment, "RULE_DEFINITION_READER");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.capabilities(
            workspaceId, principal,
            principalResolver.hasRole(servletRequest, "RULE_DEFINITION_AUTHOR"),
            principalResolver.hasRole(servletRequest, "RULE_DEFINITION_APPROVER")));
  }

  @PutMapping("/{workspaceId}/draft")
  public ResponseEntity<DomainRuleChangeWorkspaceResponse> updateDraft(
      @PathVariable UUID workspaceId,
      @RequestBody DomainRuleChangeWorkspaceUpdateRequest request,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleChangeWorkspaceResponse response = service.updateDraft(
        workspaceId, request, ifMatch,
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR"));
    return withEtag(HttpStatus.OK, response, response.etag());
  }

  @PostMapping("/{workspaceId}/scenarios")
  public ResponseEntity<DomainRuleTestScenarioResponse> createScenario(
      @PathVariable UUID workspaceId,
      @RequestBody DomainRuleTestScenarioRequest request,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleTestScenarioResponse response = service.createScenario(
        workspaceId, request,
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR"));
    return withEtag(HttpStatus.CREATED, response, response.etag());
  }

  @GetMapping("/{workspaceId}/scenarios")
  public ResponseEntity<List<DomainRuleTestScenarioResponse>> scenarios(
      @PathVariable UUID workspaceId,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(service.scenarios(
        workspaceId, principal(servletRequest, tenant, environment, "RULE_DEFINITION_READER")));
  }

  @PutMapping("/{workspaceId}/scenarios/{scenarioId}")
  public ResponseEntity<DomainRuleTestScenarioResponse> updateScenario(
      @PathVariable UUID workspaceId,
      @PathVariable UUID scenarioId,
      @RequestBody DomainRuleTestScenarioRequest request,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleTestScenarioResponse response = service.updateScenario(
        workspaceId, scenarioId, request, ifMatch,
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR"));
    return withEtag(HttpStatus.OK, response, response.etag());
  }

  @PostMapping("/{workspaceId}/test-runs")
  public ResponseEntity<DomainRuleTestRunResponse> recordTestRun(
      @PathVariable UUID workspaceId, @RequestBody DomainRuleTestRunRecordRequest request,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(HttpStatus.CREATED).body(testRunService.record(
        workspaceId, request, principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR")));
  }

  @PostMapping("/{workspaceId}/submit")
  public ResponseEntity<DomainRuleChangeWorkspaceResponse> submit(
      @PathVariable UUID workspaceId,
      @RequestHeader(value="If-Match", required=false) String ifMatch,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(service.submit(workspaceId, ifMatch,
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR")));
  }

  @GetMapping("/{workspaceId}/test-runs")
  public ResponseEntity<List<DomainRuleTestRunResponse>> testRuns(
      @PathVariable UUID workspaceId,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(testRunService.list(
        workspaceId, principal(servletRequest, tenant, environment, "RULE_DEFINITION_READER")));
  }

  @PostMapping("/{workspaceId}/reviews")
  public ResponseEntity<DomainRuleWorkspaceReviewResponse> review(
      @PathVariable UUID workspaceId,
      @RequestBody DomainRuleWorkspaceReviewRequest request,
      @RequestHeader(value="If-Match", required=false) String ifMatch,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.review(
        workspaceId, request, ifMatch,
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_APPROVER")));
  }

  @GetMapping("/{workspaceId}/reviews")
  public ResponseEntity<List<DomainRuleWorkspaceReviewResponse>> reviews(
      @PathVariable UUID workspaceId,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String environment,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok(service.reviews(
        workspaceId, principal(servletRequest, tenant, environment, "RULE_DEFINITION_READER")));
  }

  @PostMapping("/{workspaceId}/promote")
  public ResponseEntity<DomainRuleChangeWorkspaceResponse> promote(
      @PathVariable UUID workspaceId,
      @RequestHeader(value="If-Match", required=false) String ifMatch,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleChangeWorkspaceResponse response = service.promote(
        workspaceId, ifMatch,
        principal(servletRequest, tenant, environment, "RULE_DEFINITION_AUTHOR"));
    return withEtag(HttpStatus.OK, response, response.etag());
  }

  private DomainRuleGovernancePrincipal principal(
      HttpServletRequest request, String tenant, String environment, String role) {
    return principalResolver.resolve(request, tenant, environment, role);
  }

  private boolean matchesWeak(String condition, String etag) {
    try {
      return !HttpEntityTagCondition.parse(condition).isEmpty()
          && HttpEntityTagCondition.parse(condition).matchesWeak(etag);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
  }

  private <T> ResponseEntity<T> withEtag(HttpStatus status, T body, String etag) {
    return ResponseEntity.status(status).eTag(etag).cacheControl(CacheControl.noCache()).body(body);
  }
}
