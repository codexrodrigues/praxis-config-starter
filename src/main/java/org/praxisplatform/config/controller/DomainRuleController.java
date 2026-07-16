package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRulePublicationResponse;
import org.praxisplatform.config.dto.DomainRuleSimulationRequest;
import org.praxisplatform.config.dto.DomainRuleSimulationResponse;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleTimelineResponse;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared domain rule definitions and their concrete runtime materializations.
 */
@RestController("configDomainRuleController")
@RequestMapping("/api/praxis/config/domain-rules")
@RequiredArgsConstructor
@ConditionalOnBean({DomainRuleDefinitionRepository.class, DomainRuleMaterializationRepository.class})
public class DomainRuleController {

    private final DomainRuleService domainRuleService;
    private final DomainRuleGovernancePrincipalResolver principalResolver;

    @PostMapping("/intake")
    public ResponseEntity<DomainRuleIntakeResponse> intake(
            @RequestBody DomainRuleIntakeRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, "RULE_DEFINITION_AUTHOR");
        return ResponseEntity.accepted().body(domainRuleService.intake(request, principal));
    }

    @PostMapping("/definitions")
    public ResponseEntity<DomainRuleDefinitionResponse> createDefinition(
            @RequestBody DomainRuleDefinitionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, "RULE_DEFINITION_AUTHOR");
        return ResponseEntity.accepted().body(domainRuleService.createDefinition(request, principal));
    }

    @GetMapping("/definitions")
    public ResponseEntity<List<DomainRuleDefinitionResponse>> definitions(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String ruleKey) {
        return ResponseEntity.ok(domainRuleService.definitions(
                tenantId,
                environment,
                resourceKey,
                status,
                ruleType,
                ruleKey));
    }

    @PatchMapping("/definitions/{definitionId}/status")
    public ResponseEntity<DomainRuleDefinitionResponse> transitionDefinitionStatus(
            @PathVariable UUID definitionId,
            @RequestBody DomainRuleDefinitionStatusTransitionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        String requiredRole = isAuthorTransition(request)
                ? "RULE_DEFINITION_AUTHOR"
                : "RULE_DEFINITION_APPROVER";
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, requiredRole);
        return ResponseEntity.ok(domainRuleService.transitionDefinitionStatus(
                definitionId,
                request,
                principal));
    }

    @GetMapping("/definitions/{definitionId}/timeline")
    public ResponseEntity<DomainRuleTimelineResponse> definitionTimeline(
            @PathVariable UUID definitionId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainRuleService.definitionTimeline(definitionId, tenantId, environment));
    }

    @PostMapping("/simulations")
    public ResponseEntity<DomainRuleSimulationResponse> simulate(
            @RequestBody DomainRuleSimulationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainRuleService.simulate(request, tenantId, environment));
    }

    @PostMapping("/publications")
    public ResponseEntity<DomainRulePublicationResponse> publish(
            @RequestBody DomainRulePublicationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainRuleService.publish(request, tenantId, environment));
    }

    @PostMapping("/materializations")
    public ResponseEntity<DomainRuleMaterializationResponse> createMaterialization(
            @RequestBody DomainRuleMaterializationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.accepted().body(domainRuleService.createMaterialization(request, tenantId, environment));
    }

    @GetMapping("/materializations")
    public ResponseEntity<List<DomainRuleMaterializationResponse>> materializations(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) UUID ruleDefinitionId,
            @RequestParam(required = false) String targetLayer,
            @RequestParam(required = false) String targetArtifactType,
            @RequestParam(required = false) String targetArtifactKey,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(domainRuleService.materializations(
                tenantId,
                environment,
                ruleDefinitionId,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                status));
    }

    @PatchMapping("/materializations/{materializationId}/status")
    public ResponseEntity<DomainRuleMaterializationResponse> transitionMaterializationStatus(
            @PathVariable UUID materializationId,
            @RequestBody DomainRuleStatusTransitionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(domainRuleService.transitionMaterializationStatus(
                materializationId,
                request,
                tenantId,
                environment));
    }

    private boolean isAuthorTransition(DomainRuleDefinitionStatusTransitionRequest request) {
        return request != null
                && ("draft".equals(request.status()) || "proposed".equals(request.status()));
    }
}
