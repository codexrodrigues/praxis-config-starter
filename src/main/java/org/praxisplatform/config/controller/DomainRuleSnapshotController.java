package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleSnapshotActivationResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotHeadStatusResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestRequest;
import org.praxisplatform.config.dto.DomainRuleCompositionManifestResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotPublicationRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotRollbackRequest;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.plan.RulePlanException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

/** HTTP control plane for immutable RuleSet snapshots and their active heads. */
@RestController("configDomainRuleSnapshotController")
@RequestMapping("/api/praxis/config/domain-rules/snapshots")
@RequiredArgsConstructor
@ConditionalOnBean({DomainRuleSnapshotRepository.class, DomainRuleSnapshotHeadRepository.class})
public class DomainRuleSnapshotController {
  private final DomainRuleSnapshotService snapshotService;

  @PostMapping("/composition-manifest")
  @Operation(summary = "Canonicalize a RuleSet composition for approval",
      description = "Resolves governed source hashes and the admitted implementation catalog, validates the candidate, and returns the exact SHA-256 that composition approvers must sign off before publication.")
  public ResponseEntity<DomainRuleCompositionManifestResponse> compositionManifest(
      @RequestBody DomainRuleCompositionManifestRequest request,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @RequestHeader(value = "X-Env", required = false) String environment) {
    return ResponseEntity.ok(snapshotService.prepareCompositionManifest(request, tenantId, environment));
  }

  @PostMapping
  @Operation(summary = "Publish and activate an immutable RuleSet snapshot",
      description = "Compiles approved governed definitions and atomically advances the scoped RuleSet head. Initial creation uses If-None-Match; later changes use the current strong If-Match validator.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Snapshot persisted and selected as the active head"),
    @ApiResponse(responseCode = "400", description = "Invalid RuleSet, provenance or approval evidence"),
    @ApiResponse(responseCode = "412", description = "The supplied head precondition is stale"),
    @ApiResponse(responseCode = "428", description = "The required creation or mutation precondition is absent")
  })
  public ResponseEntity<DomainRuleSnapshotActivationResponse> publish(
      @RequestBody DomainRuleSnapshotPublicationRequest request,
      @Parameter(description = "Governed tenant scope; snapshot provenance and head identity never cross this boundary.", required = true)
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @Parameter(description = "Governed deployment environment within the tenant scope.", required = true)
      @RequestHeader(value = "X-Env", required = false) String environment,
      @Parameter(description = "Strong current-head ETag required after the first publication.")
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Parameter(description = "Must be * when creating the first scoped RuleSet head.")
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    DomainRuleSnapshotActivationResponse response =
        snapshotService.publish(request, tenantId, environment, ifMatch, ifNoneMatch);
    return ResponseEntity.status(HttpStatus.CREATED)
        .eTag(quoted(response.headEtag()))
        .cacheControl(CacheControl.noCache())
        .body(response);
  }

  @GetMapping("/head")
  @Operation(summary = "Read the active snapshot head",
      description = "Returns the currently selected immutable snapshot and the opaque head ETag used for optimistic concurrency and change polling.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Current active snapshot and head identity"),
    @ApiResponse(responseCode = "304", description = "The caller already has the current head representation"),
    @ApiResponse(responseCode = "404", description = "No snapshot has been published for this scoped RuleSet")
  })
  public ResponseEntity<DomainRuleSnapshotActivationResponse> head(
      @Parameter(description = "Stable semantic RuleSet identity whose active selection is requested.", required = true)
      @RequestParam String ruleSetKey,
      @Parameter(description = "Governed tenant scope of the active head.", required = true)
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @Parameter(description = "Governed deployment environment of the active head.", required = true)
      @RequestHeader(value = "X-Env", required = false) String environment,
      @Parameter(description = "Cached head ETag; weak comparison is accepted for conditional reads.")
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    DomainRuleSnapshotActivationResponse response = snapshotService
        .findActive(tenantId, environment, ruleSetKey)
        .orElseThrow(() -> new DomainRuleSnapshotControlPlaneException(
            HttpStatus.NOT_FOUND, "RuleSet head was not found"));
    if (matchesWeak(ifNoneMatch, response.headEtag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(quoted(response.headEtag()))
          .cacheControl(CacheControl.noCache())
          .build();
    }
    return ResponseEntity.ok()
        .eTag(quoted(response.headEtag()))
        .cacheControl(CacheControl.noCache())
        .body(response);
  }

  @GetMapping("/head/status")
  @Operation(summary = "Inspect the operational state of a RuleSet head",
      description = "Returns only safe head metadata and the current concurrency ETag. It never returns snapshot content that failed governed verification.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Head metadata and recovery classification"),
    @ApiResponse(responseCode = "304", description = "The caller already has this head state"),
    @ApiResponse(responseCode = "404", description = "No scoped RuleSet head exists")
  })
  public ResponseEntity<DomainRuleSnapshotHeadStatusResponse> headStatus(
      @RequestParam String ruleSetKey,
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @RequestHeader(value = "X-Env", required = false) String environment,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    DomainRuleSnapshotHeadStatusResponse response = snapshotService
        .findHeadStatus(tenantId, environment, ruleSetKey)
        .orElseThrow(() -> new DomainRuleSnapshotControlPlaneException(
            HttpStatus.NOT_FOUND, "RuleSet head was not found"));
    String headEtag = response.headEtag();
    if (matchesWeak(ifNoneMatch, headEtag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(quoted(headEtag))
          .cacheControl(CacheControl.noCache())
          .build();
    }
    return ResponseEntity.ok()
        .eTag(quoted(headEtag))
        .cacheControl(CacheControl.noCache())
        .body(response);
  }

  @GetMapping("/{snapshotKey}")
  @Operation(summary = "Read one immutable RuleSet snapshot",
      description = "Returns previously published content without consulting or changing the active head. The canonical content hash is the immutable representation ETag.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Immutable snapshot content"),
    @ApiResponse(responseCode = "304", description = "The caller already has this immutable representation"),
    @ApiResponse(responseCode = "404", description = "The snapshot does not exist in the requested tenant and environment")
  })
  public ResponseEntity<DomainRuleSnapshotStoredResponse> snapshot(
      @Parameter(description = "Opaque immutable snapshot identity assigned at publication.", required = true)
      @PathVariable String snapshotKey,
      @Parameter(description = "Governed tenant scope that owns the immutable snapshot.", required = true)
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @Parameter(description = "Governed deployment environment that owns the immutable snapshot.", required = true)
      @RequestHeader(value = "X-Env", required = false) String environment,
      @Parameter(description = "Cached canonical content hash; weak comparison is accepted for immutable reads.")
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    DomainRuleSnapshotStoredResponse response = snapshotService
        .findSnapshot(tenantId, environment, snapshotKey)
        .orElseThrow(() -> new DomainRuleSnapshotControlPlaneException(
            HttpStatus.NOT_FOUND, "Rule snapshot was not found in the requested scope"));
    if (matchesWeak(ifNoneMatch, response.snapshotContentHash())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(quoted(response.snapshotContentHash()))
          .cacheControl(immutablePrivateCache())
          .build();
    }
    return ResponseEntity.ok()
        .eTag(quoted(response.snapshotContentHash()))
        .cacheControl(immutablePrivateCache())
        .body(response);
  }

  @PostMapping("/{snapshotKey}/rollback")
  @Operation(summary = "Reactivate a previously published snapshot",
      description = "Moves the mutable head to existing immutable content, rotates the head ETag and appends an audit event. Snapshot content is never rewritten.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Prior snapshot selected and a new head identity issued"),
    @ApiResponse(responseCode = "404", description = "The target snapshot or RuleSet head does not exist"),
    @ApiResponse(responseCode = "409", description = "The target snapshot is already active"),
    @ApiResponse(responseCode = "412", description = "The supplied head ETag is stale"),
    @ApiResponse(responseCode = "428", description = "If-Match was not supplied")
  })
  public ResponseEntity<DomainRuleSnapshotActivationResponse> rollback(
      @Parameter(description = "Previously published immutable snapshot to select as the new active head.", required = true)
      @PathVariable String snapshotKey,
      @RequestBody DomainRuleSnapshotRollbackRequest request,
      @Parameter(description = "Governed tenant scope whose head will change.", required = true)
      @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
      @Parameter(description = "Governed deployment environment whose head will change.", required = true)
      @RequestHeader(value = "X-Env", required = false) String environment,
      @Parameter(description = "Strong current-head ETag required to prevent overwriting a concurrent activation.", required = true)
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    DomainRuleSnapshotActivationResponse response = snapshotService.rollback(
        snapshotKey,
        request == null ? null : request.activatedBy(),
        tenantId,
        environment,
        ifMatch);
    return ResponseEntity.ok()
        .eTag(quoted(response.headEtag()))
        .cacheControl(CacheControl.noCache())
        .body(response);
  }

  @ExceptionHandler(DomainRuleSnapshotControlPlaneException.class)
  public ResponseEntity<Map<String, String>> handleControlPlaneFailure(
      DomainRuleSnapshotControlPlaneException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of(
        "code", exception.status().name(),
        "message", exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> handleInvalidContract(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", "INVALID_RULE_SNAPSHOT",
        "message", exception.getMessage()));
  }

  @ExceptionHandler(RulePlanException.class)
  public ResponseEntity<Map<String, String>> handleInvalidPlan(RulePlanException exception) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", exception.getCode().name(),
        "message", exception.getMessage()));
  }

  private boolean matchesWeak(String header, String etag) {
    try {
      return HttpEntityTagCondition.parse(header).matchesWeak(etag);
    } catch (IllegalArgumentException exception) {
      throw new DomainRuleSnapshotControlPlaneException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
  }

  private CacheControl immutablePrivateCache() {
    return CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();
  }

  private String quoted(String etag) {
    return "\"" + etag + "\"";
  }
}
