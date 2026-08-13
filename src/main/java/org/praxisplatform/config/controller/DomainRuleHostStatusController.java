package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleHostStatusIngestionResponse;
import org.praxisplatform.config.dto.DomainRuleHostStatusRequest;
import org.praxisplatform.config.dto.DomainRuleHostStatusSummaryResponse;
import org.praxisplatform.config.repository.DomainRuleHostStatusRepository;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleHostStatusService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Governed ingestion and safe aggregate projection of host runtime status. */
@RestController("configDomainRuleHostStatusController")
@RequestMapping("/api/praxis/config/domain-rules/snapshots")
@RequiredArgsConstructor
@ConditionalOnBean(DomainRuleHostStatusRepository.class)
public class DomainRuleHostStatusController {
  private final DomainRuleHostStatusService service;
  private final DomainRuleGovernancePrincipalResolver principalResolver;

  @PostMapping("/host-status")
  public ResponseEntity<DomainRuleHostStatusIngestionResponse> ingest(
      @RequestBody DomainRuleHostStatusRequest request,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal = principalResolver.resolve(
        servletRequest, tenantId, environment, "RULE_EXECUTION_OBSERVER");
    return ResponseEntity.accepted().body(service.ingest(request, principal));
  }

  @GetMapping("/head/host-status-summary")
  public ResponseEntity<DomainRuleHostStatusSummaryResponse> summarizeHead(
      @RequestParam String ruleSetKey,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      HttpServletRequest servletRequest) {
    DomainRuleGovernancePrincipal principal = principalResolver.resolve(
        servletRequest, tenantId, environment, "RULE_SNAPSHOT_READER");
    return ResponseEntity.ok(service.summarizeHead(ruleSetKey, principal));
  }
}
