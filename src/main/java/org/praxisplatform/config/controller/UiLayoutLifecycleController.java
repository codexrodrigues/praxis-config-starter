package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.security.Principal;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutAssignmentCommandRequest;
import org.praxisplatform.config.dto.UiLayoutFreezeReleaseRequest;
import org.praxisplatform.config.dto.UiLayoutHeadReleaseRequest;
import org.praxisplatform.config.dto.UiLayoutReasonRequest;
import org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.service.UiLayoutLifecycleCommandService;
import org.praxisplatform.config.service.UiLayoutLifecycleException;
import org.praxisplatform.config.service.UiLayoutLifecycleReadService;
import org.praxisplatform.config.service.UiLayoutLifecycleRequestDecoder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP facade for typed lifecycle commands; tenant, actor and policy are resolved only by host invocation. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean({UiLayoutLifecycleReadService.class,
    UiLayoutLifecycleCommandService.class, UiLayoutLifecycleRequestDecoder.class})
@RequestMapping("/api/praxis/config/ui-layouts")
public class UiLayoutLifecycleController {
  private static final String CACHE_CONTROL = "private, no-cache";
  private final UiLayoutLifecycleReadService reads;
  private final UiLayoutLifecycleCommandService commands;
  private final UiLayoutLifecycleRequestDecoder decoder;

  public UiLayoutLifecycleController(UiLayoutLifecycleReadService reads, UiLayoutLifecycleCommandService commands,
      UiLayoutLifecycleRequestDecoder decoder) {
    this.reads = reads; this.commands = commands; this.decoder = decoder;
  }

  @Operation(summary = "Find a draft created with the current actor's creation key",
      description = "Reads only the existing association in the authenticated tenant, environment, root and actor scope. Rechecks READ_DRAFT admission before returning the current identity receipt. Absence does not establish that creation failed. No creation, retry or conditional 304 is performed; read the canonical workspace before editing.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Current draft identity receipt, canonical workspace Location, strong draft ETag and no-store. Does not establish workspace integrity or publication."),
      @ApiResponse(responseCode = "400", description = "Required root, context or creation key is missing or invalid."),
      @ApiResponse(responseCode = "401", description = "Authenticated host session is required."),
      @ApiResponse(responseCode = "403", description = "Current host admission denies reading this root."),
      @ApiResponse(responseCode = "404", description = "No creation association was observed in the authorized scope; the original command outcome remains unconfirmed."),
      @ApiResponse(responseCode = "409", description = "Authenticated lifecycle context changed during lookup."),
      @ApiResponse(responseCode = "503", description = "Context or draft association source is unavailable.")})
  @GetMapping("/drafts/by-creation-key")
  public org.praxisplatform.config.dto.UiLayoutDraftReceipt draftByCreationKey(
      @Parameter(description = "Registered composition root component type whose draft creation is being reconciled.") @RequestParam String rootComponentType,
      @Parameter(description = "Registered composition root instance identifier within the authenticated scope.") @RequestParam String rootComponentId,
      @Parameter(description = "Current host-attested context version authorizing this read; not part of the persisted creation identity.") @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @Parameter(description = "Same case-sensitive key used for draft creation by this actor and root; trimmed length 1 to 180. Not a credential; keep out of URLs and redact in host logs.") @RequestHeader("Idempotency-Key") String creationKey,
      Principal principal, jakarta.servlet.http.HttpServletResponse response) {
    var body = reads.draftByCreationKey(principal, contextVersion, root(rootComponentType, rootComponentId), creationKey);
    // ResponseEntity's processor evaluates If-None-Match automatically when ETag is present.
    // A plain response body keeps this association lookup unconditional while retaining its validator.
    response.setHeader(HttpHeaders.LOCATION, "/api/praxis/config/ui-layouts/drafts/" + body.draftRef());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader(HttpHeaders.ETAG, "\"" + body.draftEtag() + "\"");
    return body;
  }

  @Operation(summary = "Read a scoped UI layout draft", description = "Resolves host context and admission before scoped lookup or conditional evaluation.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Sanitized draft receipt and draft ETag."), @ApiResponse(responseCode = "304", description = "Current authorized draft representation matches the weak validator."), @ApiResponse(responseCode = "403", description = "Root is denied or unregistered."), @ApiResponse(responseCode = "404", description = "Authorized scope has no such draft.")})
  @GetMapping("/drafts/{draftId}")
  public ResponseEntity<?> draft(@PathVariable UUID draftId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Principal principal) {
    var body = reads.draft(principal, contextVersion, root(rootComponentType, rootComponentId), draftId);
    return conditional(ifNoneMatch, body.draft().draftEtag(), body);
  }

  @Operation(summary = "Read a scoped UI layout release review", description = "Returns review state without approval actor or private evidence.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Sanitized review receipt and review ETag."), @ApiResponse(responseCode = "304", description = "Current authorized review representation matches the weak validator."), @ApiResponse(responseCode = "403", description = "Root is denied or unregistered."), @ApiResponse(responseCode = "404", description = "Authorized scope has no such review.")})
  @GetMapping("/releases/{releaseId}/review")
  public ResponseEntity<?> review(@PathVariable UUID releaseId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Principal principal) {
    var body = reads.review(principal, contextVersion, root(rootComponentType, rootComponentId), releaseId);
    return conditional(ifNoneMatch, body.reviewEtag(), body);
  }

  @Operation(summary = "Read the scoped UI layout release head", description = "Returns the sole mutable active-release pointer only after host context admission.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Sanitized head receipt and head ETag."), @ApiResponse(responseCode = "304", description = "Current authorized head representation matches the weak validator."), @ApiResponse(responseCode = "403", description = "Root is denied or unregistered."), @ApiResponse(responseCode = "404", description = "Authorized scope has no release head.")})
  @GetMapping("/release-head")
  public ResponseEntity<?> head(@RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Principal principal) {
    var body = reads.head(principal, contextVersion, root(rootComponentType, rootComponentId));
    return conditional(ifNoneMatch, body.headEtag(), body);
  }

  @Operation(summary = "Create a scoped UI layout authoring workspace", description = "Captures all registered native authoring baselines once. Replaying the same scoped Idempotency-Key returns the current workspace without recapturing source state.")
  @ApiResponses({@ApiResponse(responseCode = "201", description = "Draft receipt, Location and strong draft ETag."), @ApiResponse(responseCode = "401", description = "Host principal is absent."), @ApiResponse(responseCode = "403", description = "Host lifecycle admission denies creation."), @ApiResponse(responseCode = "503", description = "Host context or structure source is unavailable.")})
  @PostMapping("/drafts")
  public ResponseEntity<?> createDraft(@RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @RequestHeader("Idempotency-Key") String idempotencyKey, Principal principal) {
    var body = commands.createDraft(principal, contextVersion, root(rootComponentType, rootComponentId), idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "/api/praxis/config/ui-layouts/drafts/" + body.draft().draftRef())
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(body.draft().draftEtag()).body(body);
  }

  @Operation(summary = "Create a server-compiled immutable UI layout revision", description = "Submit complete raw native authorship; Config compiles its contribution against the draft's original pinned baseline and validates it before persistence. Client patchDocument is rejected. Requires UTF-8 JSON with a maximum actual request body of 1 MiB. The candidate and generated patch each have an independent 256 KiB compact JSON budget and 64 container levels; the request envelope allows 65 levels.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
      description = "Complete raw native authorship and revision correlation. Baseline, descriptors and contribution are resolved by Config, not submitted by the caller.",
      content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
          schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = UiLayoutRevisionCommandRequest.class)))
  @ApiResponses({@ApiResponse(responseCode = "201", description = "Immutable revision receipt and next draft receipt; response ETag is nextDraft.draftEtag."), @ApiResponse(responseCode = "400", description = "Body encoding, JSON structure or document budget is invalid."), @ApiResponse(responseCode = "413", description = "Actual or declared body exceeds 1 MiB; sanitized REVISION_BODY_TOO_LARGE problem and no-store response."), @ApiResponse(responseCode = "412", description = "Draft If-Match is missing, weak, ambiguous or stale."), @ApiResponse(responseCode = "422", description = "Patch fails target schema or presentation policy validation.")})
  @PostMapping("/drafts/{draftId}/revisions")
  public ResponseEntity<?> revision(@PathVariable UUID draftId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody String rawBody, Principal principal) {
    UiLayoutRevisionCommandRequest request = decoder.decode(rawBody, UiLayoutRevisionCommandRequest.class);
    var body = commands.createRevision(principal, contextVersion, root(rootComponentType, rootComponentId), draftId, strong(ifMatch),
        new UiLayoutLifecycleCommandService.RevisionInput(request.commandRef(), request.target(), request.authoringDocument().toString(), request.reason()));
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(body.nextDraft().draft().draftEtag()).body(body);
  }

  @Operation(summary = "Create a validated immutable UI layout assignment")
  @ApiResponses({@ApiResponse(responseCode = "201", description = "Assignment receipt without selector and next draft receipt; response ETag is nextDraft.draftEtag."), @ApiResponse(responseCode = "400", description = "Body is null, non-object, incomplete or structurally malformed."), @ApiResponse(responseCode = "412", description = "Draft If-Match is missing, weak, ambiguous or stale."), @ApiResponse(responseCode = "422", description = "Selector, layer or target violates authorized lifecycle policy.")})
  @PostMapping("/drafts/{draftId}/assignments")
  public ResponseEntity<?> assignment(@PathVariable UUID draftId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody String rawBody, Principal principal) {
    UiLayoutAssignmentCommandRequest request = decoder.decode(rawBody, UiLayoutAssignmentCommandRequest.class);
    var body = commands.createAssignment(principal, contextVersion, root(rootComponentType, rootComponentId), draftId, strong(ifMatch),
        new UiLayoutLifecycleCommandService.AssignmentInput(request.commandRef(), request.target(), request.layerClass(), request.selector(), request.priority(), request.contributionKey()));
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(body.nextDraft().draft().draftEtag()).body(body);
  }

  @Operation(summary = "Freeze an immutable aggregate UI layout release",
      description = "Derives every member from authoritative workspace selections and atomically persists freezeCommandRef for lost-response reconciliation.")
  @ApiResponses({@ApiResponse(responseCode = "201", description = "Release, next workspace with freezeCommandRef, and DRAFT review receipt; response ETag is review.reviewEtag."), @ApiResponse(responseCode = "400", description = "Body is null, non-object, incomplete or structurally malformed."), @ApiResponse(responseCode = "412", description = "Draft If-Match is missing, weak, ambiguous or stale."), @ApiResponse(responseCode = "422", description = "Derived selections fail composition, assignment or content-revision invariants.")})
  @PostMapping("/drafts/{draftId}/releases")
  public ResponseEntity<?> freeze(@PathVariable UUID draftId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody String rawBody, Principal principal) {
    UiLayoutFreezeReleaseRequest request = decoder.decode(rawBody, UiLayoutFreezeReleaseRequest.class);
    var body = commands.freezeRelease(principal, contextVersion, root(rootComponentType, rootComponentId), draftId, strong(ifMatch), request.commandRef());
    return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(body.review().reviewEtag()).body(body);
  }

  @Operation(summary = "Submit an immutable release for review")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "SUBMITTED review receipt and rotated review ETag."), @ApiResponse(responseCode = "412", description = "Review If-Match is missing, weak, ambiguous or stale."), @ApiResponse(responseCode = "409", description = "Release review is not in DRAFT state.")})
  @PostMapping("/releases/{releaseId}/submit")
  public ResponseEntity<?> submit(@PathVariable UUID releaseId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, Principal principal) {
    var body = commands.submit(principal, contextVersion, root(rootComponentType, rootComponentId), releaseId, strong(ifMatch));
    return ok(body.reviewEtag(), body);
  }

  @Operation(summary = "Approve a submitted immutable release")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "APPROVED review receipt and rotated review ETag."), @ApiResponse(responseCode = "400", description = "Body is null, non-object, incomplete or structurally malformed."), @ApiResponse(responseCode = "412", description = "Review If-Match is missing, weak, ambiguous or stale."), @ApiResponse(responseCode = "409", description = "Release review is not in SUBMITTED state.")})
  @PostMapping("/releases/{releaseId}/approve")
  public ResponseEntity<?> approve(@PathVariable UUID releaseId, @RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody String rawBody, Principal principal) {
    var request = decoder.decode(rawBody, UiLayoutReasonRequest.class);
    var body = commands.approve(principal, contextVersion, root(rootComponentType, rootComponentId), releaseId, strong(ifMatch), request.reason());
    return ok(body.reviewEtag(), body);
  }

  @Operation(summary = "Publish an approved immutable release as the active scoped head")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated head receipt and rotated head ETag."), @ApiResponse(responseCode = "400", description = "Body is null, non-object, incomplete or structurally malformed."), @ApiResponse(responseCode = "412", description = "First-publish or existing-head precondition is absent, ambiguous, weak or stale."), @ApiResponse(responseCode = "422", description = "Current registered composition or policy rejects the frozen release.")})
  @PostMapping("/release-head/publish")
  public ResponseEntity<?> publish(@RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, @RequestBody String rawBody, Principal principal) {
    var request = decoder.decode(rawBody, UiLayoutHeadReleaseRequest.class);
    var body = commands.publish(principal, contextVersion, root(rootComponentType, rootComponentId), headCondition(ifMatch, ifNoneMatch), request.releaseId(), request.reason());
    return ok(body.headEtag(), body);
  }

  @Operation(summary = "Withdraw the active scoped release head")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated withdrawn head receipt and rotated head ETag."), @ApiResponse(responseCode = "400", description = "Body is null, non-object, incomplete or structurally malformed."), @ApiResponse(responseCode = "412", description = "Existing strong head If-Match is absent, weak, ambiguous or stale."), @ApiResponse(responseCode = "409", description = "No active release exists to withdraw.")})
  @PostMapping("/release-head/withdraw")
  public ResponseEntity<?> withdraw(@RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody String rawBody, Principal principal) {
    var request = decoder.decode(rawBody, UiLayoutReasonRequest.class);
    var body = commands.withdraw(principal, contextVersion, root(rootComponentType, rootComponentId), UiLayoutLifecycleCommandService.HeadWriteCondition.match(strong(ifMatch)), request.reason());
    return ok(body.headEtag(), body);
  }

  @Operation(summary = "Select an approved prior release through the scoped rollback head transition")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated head receipt and rotated head ETag."), @ApiResponse(responseCode = "400", description = "Body is null, non-object, incomplete or structurally malformed."), @ApiResponse(responseCode = "412", description = "Existing strong head If-Match is absent, weak, ambiguous or stale."), @ApiResponse(responseCode = "422", description = "Current registered composition or policy rejects the selected frozen release.")})
  @PostMapping("/release-head/rollback")
  public ResponseEntity<?> rollback(@RequestParam String rootComponentType, @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody String rawBody, Principal principal) {
    var request = decoder.decode(rawBody, UiLayoutHeadReleaseRequest.class);
    var body = commands.rollback(principal, contextVersion, root(rootComponentType, rootComponentId), UiLayoutLifecycleCommandService.HeadWriteCondition.match(strong(ifMatch)), request.releaseId(), request.reason());
    return ok(body.headEtag(), body);
  }

  private ResponseEntity<?> conditional(String ifNoneMatch, String etag, Object body) {
    if (HttpEntityTagCondition.parse(ifNoneMatch).matchesWeak(etag)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(etag).build();
    return ok(etag, body);
  }
  private ResponseEntity<?> ok(String etag, Object body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).eTag(etag).body(body); }
  private String strong(String header) { HttpEntityTagCondition condition = HttpEntityTagCondition.parse(header); if (condition.wildcard() || condition.validators().size() != 1 || condition.validators().getFirst().weak()) throw precondition(); return condition.validators().getFirst().value(); }
  private UiLayoutLifecycleCommandService.HeadWriteCondition headCondition(String ifMatch, String ifNoneMatch) { if (ifMatch != null && ifNoneMatch != null) throw precondition(); if (ifNoneMatch != null) { HttpEntityTagCondition value = HttpEntityTagCondition.parse(ifNoneMatch); if (!value.wildcard()) throw precondition(); return UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish(); } if (ifMatch != null) return UiLayoutLifecycleCommandService.HeadWriteCondition.match(strong(ifMatch)); throw precondition(); }
  private UiLayoutTarget root(String type, String id) { return new UiLayoutTarget(type, id); }
  private UiLayoutLifecycleException precondition() { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.PRECONDITION_FAILED, "Lifecycle precondition failed."); }
}
