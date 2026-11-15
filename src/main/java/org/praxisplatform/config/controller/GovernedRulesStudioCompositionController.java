package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.praxisplatform.config.contract.RuleSetCompositionAction;
import org.praxisplatform.config.contract.RuleSetCompositionCandidate;
import org.praxisplatform.config.contract.RuleSetCompositionCandidateCommand;
import org.praxisplatform.config.contract.RuleSetCompositionCandidateRequest;
import org.praxisplatform.config.contract.RuleSetCompositionPublication;
import org.praxisplatform.config.dto.DomainRuleCandidateProbeResponse;
import org.praxisplatform.config.dto.DomainRuleCompositionApprovalResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotProblemResponse;
import org.praxisplatform.config.dto.DomainRuleSnapshotStoredResponse;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionService;
import org.praxisplatform.config.runtime.TenantScopeResolver;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.rules.plan.RulePlanException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controlador HTTP canônico auto-configurado pelo {@code praxis-config-starter} para o
 * {@code praxis-rules-studio} ({@code /api/praxis/rules-studio/rule-sets/**}, com alias de
 * transição {@code /api/praxis/policy-studio/rule-sets/**}).
 *
 * <p>Permite que qualquer host consumidor publique composição 4-olhos Maker-Checker, publicação de
 * snapshots e preload/probe de rollout sem escrever nenhum controlador HTTP dedicado.
 */
@RestController
@RequestMapping({"/api/praxis/rules-studio/rule-sets", "/api/praxis/policy-studio/rule-sets"})
@ConditionalOnBean({GovernedRulesStudioCompositionService.class, DomainRuleGovernancePrincipalResolver.class})
public class GovernedRulesStudioCompositionController
        implements GovernedRulesStudioCompositionEndpointOwner {

    private final GovernedRulesStudioCompositionService compositionService;
    private final DomainRuleGovernancePrincipalResolver principalResolver;

    public GovernedRulesStudioCompositionController(
            GovernedRulesStudioCompositionService compositionService,
            DomainRuleGovernancePrincipalResolver principalResolver) {
        this.compositionService = compositionService;
        this.principalResolver = principalResolver;
    }

    @PostMapping("/{ruleSetKey}/candidate")
    public ResponseEntity<RuleSetCompositionCandidate> prepareCandidate(
            @PathVariable String ruleSetKey,
            @RequestBody RuleSetCompositionCandidateRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        String effectiveTenant =
                TenantScopeResolver.resolveHeaderTenant(servletRequest).orElse(tenant);
        boolean publisher =
                principalResolver.hasRole(servletRequest, "RULE_SNAPSHOT_PUBLISHER")
                        || principalResolver.hasRole(
                                servletRequest, "ROLE_RULE_SNAPSHOT_PUBLISHER");
        String requiredRole = publisher ? "RULE_SNAPSHOT_PUBLISHER" : "RULE_COMPOSITION_APPROVER";
        DomainRuleGovernancePrincipal principal =
                principalResolver.resolve(servletRequest, effectiveTenant, environment, requiredRole);
        List<RuleSetCompositionAction> authorizedActions =
                publisher
                        ? List.of(
                                RuleSetCompositionAction.PREPARE, RuleSetCompositionAction.PUBLISH)
                        : List.of(
                                RuleSetCompositionAction.PREPARE, RuleSetCompositionAction.APPROVE);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        compositionService.prepareCandidate(
                                ruleSetKey, request, principal, authorizedActions));
    }

    @PostMapping("/{ruleSetKey}/candidate/approvals")
    public ResponseEntity<DomainRuleCompositionApprovalResponse> approveCandidate(
            @PathVariable String ruleSetKey,
            @RequestBody RuleSetCompositionCandidateCommand command,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        String effectiveTenant =
                TenantScopeResolver.resolveHeaderTenant(servletRequest).orElse(tenant);
        DomainRuleGovernancePrincipal principal =
                principalResolver.resolve(
                        servletRequest, effectiveTenant, environment, "RULE_COMPOSITION_APPROVER");
        return ResponseEntity.ok(
                compositionService.approveCandidate(ruleSetKey, command, principal));
    }

    @PostMapping("/{ruleSetKey}/candidate/publish")
    public ResponseEntity<RuleSetCompositionPublication> publishCandidate(
            @PathVariable String ruleSetKey,
            @RequestBody RuleSetCompositionCandidateCommand command,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletRequest servletRequest) {
        String effectiveTenant =
                TenantScopeResolver.resolveHeaderTenant(servletRequest).orElse(tenant);
        DomainRuleGovernancePrincipal principal =
                principalResolver.resolve(
                        servletRequest, effectiveTenant, environment, "RULE_SNAPSHOT_PUBLISHER");
        DomainRuleSnapshotStoredResponse response =
                compositionService.publishCandidate(
                        ruleSetKey, command, ifMatch, ifNoneMatch, principal);
        RuleSetCompositionPublication safe =
                new RuleSetCompositionPublication(
                        response.snapshot().snapshotKey(),
                        response.snapshot().ruleSet().ref().ruleSetKey(),
                        response.snapshot().ruleSet().ref().operationKey(),
                        response.snapshot().ruleSet().ref().version(),
                        response.snapshotContentHash());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag('"' + response.snapshotContentHash() + '"')
                .cacheControl(CacheControl.noCache())
                .body(safe);
    }

    @PostMapping("/{ruleSetKey}/rollouts/{rolloutId}/probe")
    public ResponseEntity<DomainRuleCandidateProbeResponse> probeCandidate(
            @PathVariable String ruleSetKey,
            @PathVariable UUID rolloutId,
            @RequestParam(value = "operationKey", required = false) String operationKey,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenant,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        String effectiveTenant =
                TenantScopeResolver.resolveHeaderTenant(servletRequest).orElse(tenant);
        DomainRuleGovernancePrincipal principal =
                principalResolver.resolve(
                        servletRequest, effectiveTenant, environment, "RULE_SNAPSHOT_OPERATOR");
        TenantScopeResolver.requireAuthorizedTenant(effectiveTenant, principal);
        return ResponseEntity.ok(
                compositionService.probeRolloutCandidate(
                        ruleSetKey, operationKey, rolloutId, principal));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatusFailure(
            ResponseStatusException exception) {
        String reason =
                exception.getReason() != null ? exception.getReason() : exception.getMessage();
        return ResponseEntity.status(exception.getStatusCode())
                .body(
                        Map.of(
                                "code", exception.getStatusCode().toString(),
                                "message", reason != null ? reason : "Request failed."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidCandidate(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "code", "INVALID_RULESET_CANDIDATE",
                                "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> staleCandidate(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        Map.of(
                                "code", "STALE_RULESET_CANDIDATE",
                                "message", exception.getMessage()));
    }

    @ExceptionHandler(DomainRuleSnapshotControlPlaneException.class)
    public ResponseEntity<DomainRuleSnapshotProblemResponse> controlPlaneFailure(
            DomainRuleSnapshotControlPlaneException exception) {
        return ResponseEntity.status(exception.status())
                .body(
                        new DomainRuleSnapshotProblemResponse(
                                exception.code(), exception.getMessage(), exception.blockers()));
    }

    @ExceptionHandler(RulePlanException.class)
    public ResponseEntity<Map<String, String>> invalidRuleSetGraph(RulePlanException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(
                        Map.of(
                                "code", "INVALID_RULESET_GRAPH",
                                "message", exception.getMessage()));
    }
}
