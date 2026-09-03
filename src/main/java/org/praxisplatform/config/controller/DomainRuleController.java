package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionCapability;
import org.praxisplatform.config.dto.DomainRuleDefinitionCapabilitiesResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleFactCatalogResponse;
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

    private static final String DEFINITION_READER_ROLE = "RULE_DEFINITION_READER";
    private static final String DEFINITION_AUTHOR_ROLE = "RULE_DEFINITION_AUTHOR";
    private static final String DEFINITION_APPROVER_ROLE = "RULE_DEFINITION_APPROVER";

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
    @Operation(summary = "List governed domain-rule definitions",
            description = "Returns definitions only from the tenant and environment resolved from the authenticated RULE_DEFINITION_READER principal.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Definitions in the server-resolved scope"),
            @ApiResponse(responseCode = "403", description = "Principal is absent or lacks RULE_DEFINITION_READER")
    })
    public ResponseEntity<List<DomainRuleDefinitionResponse>> definitions(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String ruleKey,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        return ResponseEntity.ok(domainRuleService.definitions(
                principal.tenantId(),
                principal.environment(),
                resourceKey,
                status,
                ruleType,
                ruleKey));
    }

    @GetMapping("/definitions/capabilities")
    @Operation(summary = "Read server-owned definition capabilities",
            description = "Returns actions authorized by the server for each governed definition in the authenticated reader's tenant and environment.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capabilities in the server-resolved scope"),
            @ApiResponse(responseCode = "403", description = "Principal is absent or lacks RULE_DEFINITION_READER")
    })
    public ResponseEntity<DomainRuleDefinitionCapabilitiesResponse> definitionCapabilities(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        boolean canCreateVersion = principalResolver.hasRole(servletRequest, "RULE_DEFINITION_AUTHOR");
        boolean canPublish = principalResolver.hasRole(servletRequest, "RULE_SNAPSHOT_PUBLISHER");
        List<DomainRuleDefinitionCapability> capabilities = domainRuleService.definitions(
                        principal.tenantId(), principal.environment(), null, null, null, null)
                .stream()
                .map(definition -> new DomainRuleDefinitionCapability(
                        definition.id(),
                        definition.ruleKey(),
                        definition.version(),
                        definitionActions(definition, canCreateVersion, canPublish)))
                .toList();
        return ResponseEntity.ok(new DomainRuleDefinitionCapabilitiesResponse(
                principal.tenantId(), principal.environment(), capabilities));
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

    @GetMapping("/definitions/{definitionId}")
    public ResponseEntity<DomainRuleDefinitionResponse> definition(
            @PathVariable UUID definitionId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        return ResponseEntity.ok(domainRuleService.definition(definitionId, principal));
    }

    @GetMapping("/definitions/{definitionId}/facts")
    @Operation(summary = "Read the governed fact catalog for a definition",
            description = "Returns the typed, localized and redaction-aware fact vocabulary from the definition in the authenticated reader's server-resolved scope.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Versioned fact catalog in the server-resolved scope"),
            @ApiResponse(responseCode = "403", description = "Principal is absent or lacks RULE_DEFINITION_READER"),
            @ApiResponse(responseCode = "404", description = "Definition does not exist in the resolved scope")
    })
    public ResponseEntity<DomainRuleFactCatalogResponse> definitionFacts(
            @PathVariable UUID definitionId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        return ResponseEntity.ok(domainRuleService.definitionFacts(definitionId, principal));
    }

    @GetMapping("/definitions/{definitionId}/timeline")
    @Operation(summary = "Read a safe governed-rule timeline",
            description = "Returns persisted safe lifecycle evidence only when the definition belongs to the authenticated reader's server-resolved scope.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Safe timeline in the server-resolved scope"),
            @ApiResponse(responseCode = "403", description = "Principal is absent or lacks RULE_DEFINITION_READER"),
            @ApiResponse(responseCode = "404", description = "Definition does not exist in the resolved scope")
    })
    public ResponseEntity<DomainRuleTimelineResponse> definitionTimeline(
            @PathVariable UUID definitionId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        return ResponseEntity.ok(domainRuleService.definitionTimeline(
                definitionId, principal.tenantId(), principal.environment()));
    }

    @PostMapping("/simulations")
    @Operation(summary = "Simulate a governed domain-rule definition",
            description = "Runs the canonical structural simulation for an authenticated definition author or approver. Readers and auditors cannot create simulation evidence.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Canonical simulation and publication readiness"),
            @ApiResponse(responseCode = "403", description = "Principal lacks RULE_DEFINITION_AUTHOR and RULE_DEFINITION_APPROVER")
    })
    public ResponseEntity<DomainRuleSimulationResponse> simulate(
            @RequestBody DomainRuleSimulationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolveAnyRole(
                servletRequest,
                tenantId,
                environment,
                List.of(DEFINITION_AUTHOR_ROLE, DEFINITION_APPROVER_ROLE));
        return ResponseEntity.ok(domainRuleService.simulate(
                request, principal.tenantId(), principal.environment()));
    }

    @PostMapping("/publications")
    public ResponseEntity<DomainRulePublicationResponse> publish(
            @RequestBody DomainRulePublicationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, "RULE_SNAPSHOT_PUBLISHER");
        return ResponseEntity.ok(domainRuleService.publish(request, principal));
    }

    @PostMapping("/materializations")
    public ResponseEntity<DomainRuleMaterializationResponse> createMaterialization(
            @RequestBody DomainRuleMaterializationRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, "RULE_DEFINITION_AUTHOR");
        return ResponseEntity.accepted().body(domainRuleService.createMaterialization(request, principal));
    }

    @GetMapping("/materializations")
    @Operation(summary = "List governed rule materializations",
            description = "Returns runtime-target projections only from the tenant and environment resolved from the authenticated RULE_DEFINITION_READER principal.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Materializations in the server-resolved scope"),
            @ApiResponse(responseCode = "403", description = "Principal is absent or lacks RULE_DEFINITION_READER")
    })
    public ResponseEntity<List<DomainRuleMaterializationResponse>> materializations(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) UUID ruleDefinitionId,
            @RequestParam(required = false) String targetLayer,
            @RequestParam(required = false) String targetArtifactType,
            @RequestParam(required = false) String targetArtifactKey,
            @RequestParam(required = false) String status,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, DEFINITION_READER_ROLE);
        return ResponseEntity.ok(domainRuleService.materializations(
                principal.tenantId(),
                principal.environment(),
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
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) {
        DomainRuleGovernancePrincipal principal = principalResolver.resolve(
                servletRequest, tenantId, environment, requiredMaterializationRole(request));
        return ResponseEntity.ok(domainRuleService.transitionMaterializationStatus(
                materializationId,
                request,
                principal));
    }

    private boolean isAuthorTransition(DomainRuleDefinitionStatusTransitionRequest request) {
        return request != null
                && ("draft".equals(request.status()) || "proposed".equals(request.status()));
    }

    private List<String> definitionActions(
            DomainRuleDefinitionResponse definition, boolean canCreateVersion, boolean canPublish) {
        var actions = new java.util.ArrayList<String>();
        if (canCreateVersion) actions.add("CREATE_NEW_VERSION");
        if (canPublish && ("approved".equals(definition.status())
                || "active".equals(definition.status()))) actions.add("PUBLISH");
        return List.copyOf(actions);
    }

    private String requiredMaterializationRole(DomainRuleStatusTransitionRequest request) {
        if (request != null && ("draft".equals(request.status()) || "pending_review".equals(request.status()))) {
            return "RULE_DEFINITION_AUTHOR";
        }
        if (request != null && "applied".equals(request.status())) {
            return "RULE_SNAPSHOT_PUBLISHER";
        }
        return "RULE_SNAPSHOT_OPERATOR";
    }
}
