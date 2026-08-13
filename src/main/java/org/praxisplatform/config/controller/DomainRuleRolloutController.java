package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Governed observational API for candidate preload rollouts. */
@RestController("configDomainRuleRolloutController")
@RequestMapping("/api/praxis/config/domain-rules/snapshots/rollouts")
@RequiredArgsConstructor
public class DomainRuleRolloutController {
  private final DomainRuleRolloutService service;
  private final DomainRuleGovernancePrincipalResolver principals;

  @PostMapping
  public ResponseEntity<DomainRuleRolloutResponse> create(@RequestBody DomainRuleRolloutCreateRequest body,
      @RequestHeader(value="If-Match", required=false) String ifMatch,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_OPERATOR");
    return ResponseEntity.status(201).body(service.create(body, principal, ifMatch));
  }

  @GetMapping
  public ResponseEntity<DomainRuleRolloutCatalogResponse> catalog(
      @RequestParam String ruleSetKey,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_READER");
    return ResponseEntity.ok(service.catalog(ruleSetKey, principal));
  }

  @GetMapping("/pending")
  public ResponseEntity<DomainRulePendingRolloutResponse> pending(@RequestParam String ruleSetKey,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_EXECUTION_OBSERVER");
    return service.pending(ruleSetKey, principal)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/{rolloutId}/probes")
  public ResponseEntity<DomainRuleCandidateProbeResponse> probe(@PathVariable UUID rolloutId,
      @RequestBody DomainRuleCandidateProbeRequest body,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_EXECUTION_OBSERVER");
    return ResponseEntity.accepted().body(service.probe(rolloutId, body, principal));
  }

  @GetMapping("/{rolloutId}/readiness")
  public ResponseEntity<DomainRuleRolloutReadinessResponse> readiness(@PathVariable UUID rolloutId,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_READER");
    return ResponseEntity.ok(service.readiness(rolloutId, principal));
  }

  @PostMapping("/{rolloutId}/cancel")
  public ResponseEntity<Void> cancel(@PathVariable UUID rolloutId,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_OPERATOR");
    service.cancel(rolloutId, principal);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(DomainRuleSnapshotControlPlaneException.class)
  public ResponseEntity<Map<String, String>> handleControlPlaneFailure(
      DomainRuleSnapshotControlPlaneException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of(
        "code", exception.status().name(), "message", exception.getMessage()));
  }
}
