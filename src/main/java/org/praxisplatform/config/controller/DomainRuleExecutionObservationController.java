package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchRequest;
import org.praxisplatform.config.dto.DomainRuleExecutionObservationBatchResponse;
import org.praxisplatform.config.dto.DomainRuleExecutionSummaryResponse;
import org.praxisplatform.config.repository.DomainRuleExecutionObservationRepository;
import org.praxisplatform.config.service.DomainRuleExecutionObservationService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Governed ingestion and safe aggregation of redacted host execution evidence. */
@RestController("configDomainRuleExecutionObservationController")
@RequestMapping("/api/praxis/config/domain-rules/snapshots")
@RequiredArgsConstructor
@ConditionalOnBean(DomainRuleExecutionObservationRepository.class)
public class DomainRuleExecutionObservationController {
  private final DomainRuleExecutionObservationService service;
  private final DomainRuleGovernancePrincipalResolver principalResolver;

  @PostMapping("/execution-observations")
  public ResponseEntity<DomainRuleExecutionObservationBatchResponse> ingest(
      @RequestBody DomainRuleExecutionObservationBatchRequest request,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal = principalResolver.resolve(
        servletRequest, tenantId, environment, "RULE_EXECUTION_OBSERVER");
    return ResponseEntity.accepted().body(service.ingest(request, principal));
  }

  @GetMapping("/{snapshotKey}/execution-summary")
  public ResponseEntity<DomainRuleExecutionSummaryResponse> summary(
      @PathVariable String snapshotKey,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal = principalResolver.resolve(
        servletRequest, tenantId, environment, "RULE_SNAPSHOT_READER");
    return ResponseEntity.ok(service.summary(snapshotKey, principal));
  }
}
