package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextChoicesResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextSwitchCommand;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeSecurityEventsResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenantsResponse;
import org.praxisplatform.config.service.EnterpriseRuntimeContextChoiceProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextSwitchProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeSecurityEventProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Transport for host-confirmed runtime context; servlet principal reaches providers before tenant selection. */
@RestController
@RequestMapping("/api/praxis/runtime")
@Tag(name = "Enterprise runtime", description = "Host-validated session context and safe choices. "
        + "Discovery and presentation hints do not authorize configuration or business operations.")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "SESSION_REQUIRED: host session absent, invalid, expired or revoked.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "403", description = "CONTEXT_CHOICE_DENIED: selected choice is not authorized for this session.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "CONTEXT_SELECTION_CONFLICT, CONTEXT_VERSION_CONFLICT or CONTEXT_NOT_READY.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "503", description = "RUNTIME_PROVIDER_UNAVAILABLE or RUNTIME_SOURCE_UNAVAILABLE.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
})
public class EnterpriseRuntimeContextController {

    private static final Map<Integer, Set<String>> SAFE_CODES = Map.of(
            400, Set.of("INVALID_CONTEXT_COMMAND", "INVALID_CONTEXT_CURSOR"),
            401, Set.of("SESSION_REQUIRED"),
            403, Set.of("CONTEXT_CHOICE_DENIED", "CONTEXT_CHOICE_EXPIRED"),
            409, Set.of("CONTEXT_SELECTION_CONFLICT", "CONTEXT_VERSION_CONFLICT", "CONTEXT_NOT_READY"),
            503, Set.of("RUNTIME_PROVIDER_UNAVAILABLE", "RUNTIME_SOURCE_UNAVAILABLE"));

    private final EnterpriseRuntimeContextProvider runtimeContextProvider;
    private final EnterpriseRuntimeContextSwitchProvider runtimeContextSwitchProvider;
    private final EnterpriseRuntimeContextChoiceProvider runtimeContextChoiceProvider;
    private final EnterpriseRuntimeTenantProvider runtimeTenantProvider;
    private final EnterpriseRuntimeNavigationProvider runtimeNavigationProvider;
    private final EnterpriseRuntimeSecurityEventProvider runtimeSecurityEventProvider;

    public EnterpriseRuntimeContextController(
            EnterpriseRuntimeContextProvider runtimeContextProvider,
            EnterpriseRuntimeContextSwitchProvider runtimeContextSwitchProvider,
            EnterpriseRuntimeContextChoiceProvider runtimeContextChoiceProvider,
            EnterpriseRuntimeTenantProvider runtimeTenantProvider,
            EnterpriseRuntimeNavigationProvider runtimeNavigationProvider,
            EnterpriseRuntimeSecurityEventProvider runtimeSecurityEventProvider) {
        this.runtimeContextProvider = runtimeContextProvider;
        this.runtimeContextSwitchProvider = runtimeContextSwitchProvider;
        this.runtimeContextChoiceProvider = runtimeContextChoiceProvider;
        this.runtimeTenantProvider = runtimeTenantProvider;
        this.runtimeNavigationProvider = runtimeNavigationProvider;
        this.runtimeSecurityEventProvider = runtimeSecurityEventProvider;
    }

    @GetMapping("/context")
    @Operation(summary = "Read the host-confirmed session selection state",
            description = "Requires a validated host session, not a preselected tenant. Only ready includes effectiveContext; "
                    + "tenant/user/profile headers never establish identity or selection.")
    public ResponseEntity<EnterpriseRuntimeContextResponse> getContext(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey) {
        return noStore(runtimeContextProvider.getContext(runtimeRequest(request, acceptLanguage, timezone, activeModuleKey)));
    }

    @PutMapping("/context")
    @Operation(summary = "Select one complete authorized context choice",
            description = "Accepts only choiceRef and expectedSelectionVersion. The host revalidates session and choice, "
                    + "executes selection CAS and returns canonical state after commit.")
    @ApiResponse(responseCode = "400", description = "INVALID_CONTEXT_COMMAND: malformed, missing, oversized, unknown "
            + "or duplicate properties; no provider mutation occurs.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<EnterpriseRuntimeContextResponse> switchContext(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey,
            @RequestBody(required = false) EnterpriseRuntimeContextSwitchCommand command) {
        if (command == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT_COMMAND");
        return noStore(runtimeContextSwitchProvider.switchContext(
                runtimeRequest(request, acceptLanguage, timezone, activeModuleKey), command));
    }

    @GetMapping("/context/choices")
    @Operation(summary = "Discover a bounded page of complete choices for this session",
            description = "Available before first selection and after invalidation. Cursor and choices are host-bound; "
                    + "this catalog grants no access and never fabricates a default choice.")
    public ResponseEntity<EnterpriseRuntimeContextChoicesResponse> getChoices(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey,
            @Parameter(description = "Opaque host continuation bound to session, query and position; omit for first page.")
            @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "Maximum items requested; default 50, inclusive range 1..100.")
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize) {
        if (pageSize < 1 || pageSize > 100 || (cursor != null && (cursor.isBlank() || cursor.length() > 4096))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT_CURSOR");
        }
        return noStore(runtimeContextChoiceProvider.getChoices(
                runtimeRequest(request, acceptLanguage, timezone, activeModuleKey), cursor, pageSize));
    }

    @GetMapping("/tenants")
    @Operation(summary = "Read host-projected tenant discovery",
            description = "This remains a distinct host discovery surface. It neither chooses context nor replaces opaque choices.")
    public ResponseEntity<EnterpriseRuntimeTenantsResponse> getTenants(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey) {
        return noStore(runtimeTenantProvider.getTenants(runtimeRequest(request, acceptLanguage, timezone, activeModuleKey)));
    }

    @GetMapping("/navigation")
    public ResponseEntity<EnterpriseRuntimeNavigationResponse> getNavigation(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey) {
        return noStore(runtimeNavigationProvider.getNavigation(runtimeRequest(request, acceptLanguage, timezone, activeModuleKey)));
    }

    @GetMapping("/security-events")
    public ResponseEntity<EnterpriseRuntimeSecurityEventsResponse> getSecurityEvents(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestHeader(value = "X-Timezone", required = false) String timezone,
            @RequestHeader(value = "X-Praxis-Module-Key", required = false) String activeModuleKey) {
        return noStore(runtimeSecurityEventProvider.getSecurityEvents(runtimeRequest(request, acceptLanguage, timezone, activeModuleKey)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> runtimeFailure(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        String code = error.getReason();
        if (code == null || !SAFE_CODES.getOrDefault(status, Set.of()).contains(code)) {
            code = switch (status) {
                case 400 -> "INVALID_CONTEXT_COMMAND";
                case 401 -> "SESSION_REQUIRED";
                case 403 -> "CONTEXT_CHOICE_DENIED";
                case 409 -> "CONTEXT_NOT_READY";
                case 503 -> "RUNTIME_SOURCE_UNAVAILABLE";
                default -> "RUNTIME_REQUEST_FAILED";
            };
        }
        return problem(status, code);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> invalidCommand(HttpMessageNotReadableException ignored) {
        return problem(400, "INVALID_CONTEXT_COMMAND");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> invalidCursor(MethodArgumentTypeMismatchException ignored) {
        return problem(400, "INVALID_CONTEXT_CURSOR");
    }

    private EnterpriseRuntimeContextRequest runtimeRequest(
            HttpServletRequest request, String acceptLanguage, String timezone, String activeModuleKey) {
        if (request == null || request.getUserPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        }
        String language = normalize(acceptLanguage);
        return new EnterpriseRuntimeContextRequest(
                request.getUserPrincipal(), language == null ? null : language.split(",", 2)[0].trim(),
                normalize(timezone), normalize(activeModuleKey));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_PROVIDER_UNAVAILABLE");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), code);
        body.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
