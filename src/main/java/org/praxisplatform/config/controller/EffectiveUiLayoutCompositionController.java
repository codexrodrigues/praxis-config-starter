package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.security.Principal;
import org.praxisplatform.config.dto.EffectiveUiLayoutCompositionResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.service.UiLayoutCompositionReadService;
import org.praxisplatform.config.service.UiLayoutResolutionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only aggregate receipt. The controller evaluates conditional requests only after resolution. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(UiLayoutCompositionReadService.class)
@RequestMapping("/api/praxis/config/ui-layouts")
public class EffectiveUiLayoutCompositionController {
  private final UiLayoutCompositionReadService service;

  public EffectiveUiLayoutCompositionController(UiLayoutCompositionReadService service) { this.service = service; }

  @Operation(
      summary = "Resolve a coherent effective UI layout composition",
      description = "Authorizes the server-confirmed runtime context and resolves every target in its host-registered composition before evaluating If-None-Match. The response never discloses private audience membership or unapplied contributions.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sanitized complete aggregate receipt; ETag equals receiptEtag and Cache-Control is private, no-cache."),
      @ApiResponse(responseCode = "304", description = "The caller's weak validator matches the fully authorized current receipt; ETag and private, no-cache are returned without a body."),
      @ApiResponse(responseCode = "400", description = "A required target, context assertion, or conditional header is malformed or missing."),
      @ApiResponse(responseCode = "401", description = "No authenticated host principal is available."),
      @ApiResponse(responseCode = "403", description = "The resolved context is not authorized for the root or one registered target; registry absence is deliberately reported as access denial."),
      @ApiResponse(responseCode = "409", description = "The supplied runtime-context version became stale before resolution completed."),
      @ApiResponse(responseCode = "422", description = "Published layout inputs are ambiguous or violate protected presentation invariants."),
      @ApiResponse(responseCode = "503", description = "An authoritative audience/layout source is unavailable or a pinned release fails integrity verification.")
  })
  @Parameter(name = "X-Praxis-Context-Version", in = ParameterIn.HEADER, required = true,
      description = "Opaque assertion from the current server-confirmed runtime context. Authorization and context-staleness checks occur before conditional ETag evaluation.")
  @GetMapping("/effective-composition")
  public ResponseEntity<EffectiveUiLayoutCompositionResponse> effectiveComposition(
      @Parameter(description = "Canonical component type of the registered composition root.", required = true)
      @RequestParam String rootComponentType,
      @Parameter(description = "Exact host component instance identifier of the registered composition root.", required = true)
      @RequestParam String rootComponentId,
      @RequestHeader("X-Praxis-Context-Version") String contextVersion,
      @Parameter(description = "Previously received receipt ETag. Weak matching is accepted only after the current request is fully authorized and resolved.")
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
      Principal principal) {
    if (principal == null) throw new UiLayoutResolutionException(UiLayoutResolutionException.Code.SESSION_REQUIRED, "Authenticated host principal is required.", null);
    EffectiveUiLayoutCompositionResponse body = service.resolve(principal, contextVersion, new UiLayoutTarget(rootComponentType, rootComponentId));
    if (HttpEntityTagCondition.parse(ifNoneMatch).matchesWeak(body.receiptEtag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.CACHE_CONTROL, "private, no-cache").eTag(body.receiptEtag()).build();
    }
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-cache").eTag(body.receiptEtag()).body(body);
  }
}
