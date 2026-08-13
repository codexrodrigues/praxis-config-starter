package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyCreateRequest;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyEventResponse;
import org.praxisplatform.config.dto.DomainRuleRolloutPolicyMutationResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Governed maker-checker API for staged-rollout quorum policies. */
@RestController("configDomainRuleRolloutPolicyController")
@RequestMapping("/api/praxis/config/domain-rules/snapshots/rollout-policies")
@RequiredArgsConstructor
public class DomainRuleRolloutPolicyController {
  private final DomainRuleRolloutPolicyService service;
  private final DomainRuleGovernancePrincipalResolver principals;

  @PostMapping
  public ResponseEntity<DomainRuleRolloutPolicyMutationResponse> create(
      @RequestBody DomainRuleRolloutPolicyCreateRequest body,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_DEFINITION_AUTHOR");
    var response = service.create(body, principal);
    return ResponseEntity.status(HttpStatus.CREATED).eTag(quote(response.headEtag())).body(response);
  }

  @GetMapping
  public ResponseEntity<DomainRuleRolloutPolicyCatalogResponse> catalog(
      @RequestParam String ruleSetKey,
      @RequestHeader(value="If-None-Match", required=false) String ifNoneMatch,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_READER");
    var response = service.catalog(ruleSetKey, principal)
        .orElseThrow(() -> new DomainRuleSnapshotControlPlaneException(
            HttpStatus.NOT_FOUND, "Rollout policy head was not found"));
    if (matchesWeak(ifNoneMatch, response.headEtag()))
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(quote(response.headEtag()))
          .cacheControl(CacheControl.noCache()).build();
    return ResponseEntity.ok().eTag(quote(response.headEtag()))
        .cacheControl(CacheControl.noCache()).body(response);
  }

  @GetMapping("/timeline")
  public ResponseEntity<List<DomainRuleRolloutPolicyEventResponse>> timeline(
      @RequestParam String ruleSetKey,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_READER");
    return ResponseEntity.ok(service.timeline(ruleSetKey, principal));
  }

  @PostMapping("/{policyId}/approve")
  public ResponseEntity<DomainRuleRolloutPolicyMutationResponse> approve(
      @PathVariable UUID policyId,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_DEFINITION_APPROVER");
    var response = service.approve(policyId, principal);
    return ResponseEntity.ok().eTag(quote(response.headEtag())).body(response);
  }

  @PostMapping("/{policyId}/activate")
  public ResponseEntity<DomainRuleRolloutPolicyMutationResponse> activate(
      @PathVariable UUID policyId,
      @RequestHeader(value="If-Match", required=false) String ifMatch,
      @RequestHeader(value="X-Tenant-ID", required=false) String tenant,
      @RequestHeader(value="X-Env", required=false) String env, HttpServletRequest request) {
    var principal = principals.resolve(request, tenant, env, "RULE_SNAPSHOT_OPERATOR");
    var response = service.activate(policyId, ifMatch, principal);
    return ResponseEntity.ok().eTag(quote(response.headEtag()))
        .cacheControl(CacheControl.noCache()).body(response);
  }

  @ExceptionHandler(DomainRuleSnapshotControlPlaneException.class)
  public ResponseEntity<Map<String, String>> handleControlPlaneFailure(
      DomainRuleSnapshotControlPlaneException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of(
        "code", exception.status().name(), "message", exception.getMessage()));
  }

  private static boolean matchesWeak(String header, String current) {
    try {
      return HttpEntityTagCondition.parse(header).matchesWeak(current);
    } catch (IllegalArgumentException invalid) {
      throw new DomainRuleSnapshotControlPlaneException(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
  }

  private static String quote(String value) { return '"' + value + '"'; }
}
