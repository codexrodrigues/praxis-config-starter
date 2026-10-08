package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.security.Principal;
import org.praxisplatform.config.dto.EffectiveUiLayoutResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.praxisplatform.config.service.EffectiveUiLayoutResolver;
import org.praxisplatform.config.service.UiLayoutResolutionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only projection of the effective governed layout for one exact component instance. */
@RestController
@RequestMapping("/api/praxis/config/ui-layouts")
public class EffectiveUiLayoutController {
    private static final String CACHE_CONTROL = "private, no-cache";

    private final EffectiveUiLayoutResolver service;

    public EffectiveUiLayoutController(EffectiveUiLayoutResolver service) {
        this.service = service;
    }

    @Operation(
            summary = "Resolve an effective governed UI layout",
            description = "Resolves presentation-only layers using host-validated audience facts. "
                    + "Audience membership is neither accepted from nor disclosed to the caller.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Effective layout and sanitized resolution receipt."),
        @ApiResponse(responseCode = "304", description = "The exact context and target validator still matches."),
        @ApiResponse(responseCode = "400", description = "Target identity or conditional header is invalid."),
        @ApiResponse(responseCode = "401", description = "No authenticated host principal is available."),
        @ApiResponse(responseCode = "403", description = "The current host-authorized context cannot consume layouts."),
        @ApiResponse(responseCode = "404", description = "No published layout applies to the exact target."),
        @ApiResponse(responseCode = "409", description = "The asserted runtime context is stale."),
        @ApiResponse(responseCode = "422", description = "Published inputs are ambiguous or violate presentation invariants."),
        @ApiResponse(responseCode = "503", description = "An authoritative audience or layout source is unavailable.")
    })
    @Parameter(
            name = "X-Praxis-Context-Version",
            in = ParameterIn.HEADER,
            required = true,
            description = "Opaque assertion from the current server-confirmed runtime context; independent of the layout ETag.")
    @GetMapping("/effective")
    public ResponseEntity<EffectiveUiLayoutResponse> effective(
            @RequestParam String componentType,
            @RequestParam String componentId,
            @RequestHeader("X-Praxis-Context-Version") String contextVersion,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Principal principal) {
        if (principal == null) {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.SESSION_REQUIRED,
                    "Authenticated host principal is required.",
                    null);
        }
        var resolved = service.resolve(principal, contextVersion, new UiLayoutTarget(componentType, componentId));
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        EffectiveUiLayoutResponse body = resolved.orElseThrow();
        if (HttpEntityTagCondition.parse(ifNoneMatch).matchesWeak(body.compositeEtag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .eTag(body.compositeEtag())
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .eTag(body.compositeEtag())
                .body(body);
    }
}
