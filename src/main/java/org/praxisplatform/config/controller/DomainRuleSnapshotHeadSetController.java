package org.praxisplatform.config.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.LinkedHashMap;
import org.praxisplatform.config.contract.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Current authority is always read again; a stable ETag cannot make readiness fresh. */
@RestController("configDomainRuleSnapshotHeadSetController")
@RequestMapping("/api/praxis/config/domain-rules/snapshots/head-set")
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(PublishedRuleSnapshotHeadSetReader.class)
public class DomainRuleSnapshotHeadSetController {
    private final PublishedRuleSnapshotHeadSetReader reader;
    private final DomainRuleGovernancePrincipalResolver principals;
    public DomainRuleSnapshotHeadSetController(PublishedRuleSnapshotHeadSetReader reader,
            DomainRuleGovernancePrincipalResolver principals) { this.reader = reader; this.principals = principals; }

    @GetMapping
    @Operation(operationId = "readDomainRuleSnapshotHeadSet", summary = "Read a complete consistent operation-head selection",
            description = "Reads the exact operation set in one Config storage snapshot and verifies immutable content and approval evidence. Always no-store; never loops over individual head reads or returns a partial bundle.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Complete verified head selection"),
            @ApiResponse(responseCode = "400", description = "Missing, duplicate, empty or oversized operation selection"),
            @ApiResponse(responseCode = "403", description = "Authenticated principal lacks scope or reader permission"),
            @ApiResponse(responseCode = "404", description = "At least one requested operation has no selected head"),
            @ApiResponse(responseCode = "503", description = "Authority is unavailable or stored evidence cannot be verified")})
    public ResponseEntity<DomainRuleSnapshotHeadSetResponse> read(
            @Parameter(description = "Stable host-owned RuleSet family.", required = true) @RequestParam String ruleSetKey,
            @Parameter(description = "Exact operation identities; repeat this query parameter for each operation. Between 1 and 64 unique entries.", required = true)
            @RequestParam List<String> operationKeys,
            @Parameter(description = "Governed tenant hint checked against the authenticated principal.", required = true)
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @Parameter(description = "Governed deployment environment checked against the authenticated principal.", required = true)
            @RequestHeader(value = "X-Env", required = false) String environment, HttpServletRequest request) {
        var principal = principals.resolve(request, tenantId, environment, "RULE_SNAPSHOT_READER");
        var scope = new PublishedRuleSnapshotHeadSetScope(principal.tenantId(), principal.environment(), ruleSetKey, operationKeys);
        var selected = reader.findActive(scope);
        if (selected.isEmpty()) return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        var set = selected.orElseThrow();
        var heads = new LinkedHashMap<String, DomainRuleSnapshotActivationResponse>();
        set.heads().forEach((operation, head) -> heads.put(operation, new DomainRuleSnapshotActivationResponse(
                head.snapshot(), head.snapshotContentHash(), head.headEtag(), head.activationRevision(), "ACTIVE")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DomainRuleSnapshotHeadSetResponse(
                scope.tenantId(), scope.environment(), scope.ruleSetKey(), heads, set.fingerprint()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<DomainRuleSnapshotProblemResponse> invalidSelection() {
        return problem(HttpStatus.BAD_REQUEST, "HEAD_SET_SELECTION_INVALID", "Exact bounded operation identities are required");
    }
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    public ResponseEntity<DomainRuleSnapshotProblemResponse> unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "HEAD_SET_UNAVAILABLE", "Current policy authority could not be verified");
    }
    private ResponseEntity<DomainRuleSnapshotProblemResponse> problem(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new DomainRuleSnapshotProblemResponse(code, message, List.of()));
    }
}
