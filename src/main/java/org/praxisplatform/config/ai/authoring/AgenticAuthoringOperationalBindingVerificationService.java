package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ResourceActionCatalogFetchResult;
import org.praxisplatform.config.service.ResourceActionCatalogRetrievalService;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.springframework.util.StringUtils;

/** Verifies that a governed semantic binding still resolves to an executable metadata contract. */
public class AgenticAuthoringOperationalBindingVerificationService {

    private final AgenticAuthoringDomainBindingService bindingService;
    private final SchemaRetrievalService schemaRetrievalService;
    private final ResourceCapabilitiesRetrievalService capabilitiesRetrievalService;
    private final ResourceActionCatalogRetrievalService actionCatalogRetrievalService;

    public AgenticAuthoringOperationalBindingVerificationService(
            AgenticAuthoringDomainBindingService bindingService,
            SchemaRetrievalService schemaRetrievalService,
            ResourceCapabilitiesRetrievalService capabilitiesRetrievalService,
            ResourceActionCatalogRetrievalService actionCatalogRetrievalService) {
        this.bindingService = bindingService;
        this.schemaRetrievalService = schemaRetrievalService;
        this.capabilitiesRetrievalService = capabilitiesRetrievalService;
        this.actionCatalogRetrievalService = actionCatalogRetrievalService;
    }

    VerificationResult verify(
            String resourceKey,
            String requestBaseUrl,
            AiPrincipalContext principalContext) {
        if (principalContext == null
                || !StringUtils.hasText(principalContext.tenantId())
                || !StringUtils.hasText(principalContext.environment())
                || !StringUtils.hasText(resourceKey)) {
            return VerificationResult.blocked(resourceKey, "operational-grounding-scope-required");
        }
        List<AgenticAuthoringDomainBindingService.BindingProjection> bindings = bindingService.resolve(
                principalContext.tenantId(), principalContext.environment(), resourceKey, 12);
        if (bindings.isEmpty()) {
            return VerificationResult.blocked(resourceKey, "operational-grounding-binding-required");
        }
        List<OperationProjection> verified = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        String capabilitiesResourcePath = bindings.stream()
                .filter(binding -> "api_resource".equals(binding.bindingType()))
                .map(AgenticAuthoringDomainBindingService.BindingProjection::apiPath)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElseGet(() -> canonicalResourcePath(resourceKey));
        for (AgenticAuthoringDomainBindingService.BindingProjection binding : bindings) {
            if ("workflow_action".equals(binding.bindingType())) {
                continue;
            }
            if (!StringUtils.hasText(binding.apiPath()) || !StringUtils.hasText(binding.apiMethod())) {
                failures.add("operational-binding-operation-incomplete");
                continue;
            }
            String method = binding.apiMethod().trim().toLowerCase(Locale.ROOT);
            SchemaFetchResult schema = schemaRetrievalService.fetchSchemaResult(
                    AiSchemaContext.builder()
                            .path(binding.apiPath())
                            .operation(method)
                            .schemaType(schemaType(method))
                            .build(),
                    requestBaseUrl,
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            if (schema == null || !schema.isSuccess()) {
                failures.add("operational-binding-schema-" + schemaStatus(schema));
                continue;
            }
            String capabilityPath = switch (binding.bindingType()) {
                case "api_operation" -> capabilitiesResourcePath;
                case "api_resource" -> binding.apiPath();
                default -> StringUtils.hasText(capabilitiesResourcePath)
                        ? capabilitiesResourcePath
                        : binding.apiPath();
            };
            if (!StringUtils.hasText(capabilityPath)) {
                failures.add("operational-binding-capabilities-resource-binding-required");
                continue;
            }
            ResourceCapabilitiesFetchResult capabilities = capabilitiesRetrievalService.fetchCapabilitiesResult(
                    capabilityPath,
                    requestBaseUrl,
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            if (capabilities == null || !capabilities.isSuccess()) {
                failures.add("operational-binding-capabilities-" + capabilitiesStatus(capabilities));
                continue;
            }
            CapabilityDecision capabilityDecision = operationAvailable(
                    capabilities.getCapabilities(), binding, method);
            if (!capabilityDecision.verified()) {
                failures.add(capabilityDecision.failureCode());
                continue;
            }
            verified.add(new OperationProjection(
                    binding.conceptKey(),
                    binding.bindingKey(),
                    "resource_operation",
                    binding.resourceKey(),
                    capabilitiesResourcePath,
                    binding.apiPath(),
                    method,
                    schemaType(method),
                    schema.getEndpointUrl(),
                    capabilities.getEndpointUrl(),
                    capabilityDecision.operationId(),
                    "",
                    "",
                    "principal_capability",
                    new AvailabilityProjection(true, "", "resource_capabilities"),
                    binding.sourceRelease(),
                    binding.evidence()));
        }
        int failuresBeforeActionCatalog = failures.size();
        verifyPublishedActions(
                resourceKey,
                capabilitiesResourcePath,
                requestBaseUrl,
                principalContext,
                bindings,
                verified,
                failures);
        boolean actionCatalogVerified = failures.size() == failuresBeforeActionCatalog;
        return verified.isEmpty() || !actionCatalogVerified
                ? new VerificationResult(false, resourceKey, List.of(), List.copyOf(failures))
                : new VerificationResult(true, resourceKey, List.copyOf(verified), List.copyOf(failures));
    }

    private void verifyPublishedActions(
            String resourceKey,
            String resourcePath,
            String requestBaseUrl,
            AiPrincipalContext principalContext,
            List<AgenticAuthoringDomainBindingService.BindingProjection> bindings,
            List<OperationProjection> verified,
            List<String> failures) {
        if (actionCatalogRetrievalService == null) {
            failures.add("operational-binding-action-catalog-unavailable");
            return;
        }
        ResourceActionCatalogFetchResult catalogResult = actionCatalogRetrievalService.fetchCatalogResult(
                resourceKey,
                requestBaseUrl,
                principalContext.tenantId(),
                principalContext.userId(),
                principalContext.environment());
        // The metadata contract publishes 404 when a valid resource declares no WorkflowAction.
        // Resource/schema/capability verification remains the authority for the resource itself;
        // only a present action catalog must satisfy the stricter action contract below.
        if (catalogResult != null
                && catalogResult.getStatus() == ResourceActionCatalogFetchResult.Status.NOT_FOUND) {
            return;
        }
        if (catalogResult == null || !catalogResult.isSuccess()) {
            failures.add("operational-binding-action-catalog-" + actionCatalogStatus(catalogResult));
            return;
        }
        JsonNode catalog = catalogResult.getCatalog();
        String catalogResourcePath = canonicalPath(catalog.path("resourcePath").asText(""));
        if (!StringUtils.hasText(catalogResourcePath)
                || !catalogResourcePath.equals(canonicalPath(resourcePath))) {
            failures.add("operational-binding-action-catalog-resource-mismatch");
            return;
        }
        for (JsonNode action : catalog.path("actions")) {
            ActionDecision decision = publishedAction(action, resourceKey, catalogResourcePath);
            if (!decision.verified()) {
                failures.add(decision.failureCode());
                continue;
            }
            SchemaFetchResult schema = schemaRetrievalService.fetchSchemaResult(
                    AiSchemaContext.builder()
                            .path(decision.apiPath())
                            .operation(decision.method())
                            .schemaType(schemaType(decision.method()))
                            .build(),
                    requestBaseUrl,
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            if (schema == null || !schema.isSuccess()) {
                failures.add("operational-binding-action-schema-" + schemaStatus(schema));
                continue;
            }
            AgenticAuthoringDomainBindingService.BindingProjection governedBinding = bindings.stream()
                    .filter(binding -> "workflow_action".equals(binding.bindingType()))
                    .filter(binding -> decision.actionId().equals(binding.operationId()))
                    .filter(binding -> decision.apiPath().equals(canonicalPath(binding.apiPath())))
                    .filter(binding -> decision.method().equals(normalizedMethod(binding.apiMethod())))
                    .findFirst()
                    .orElse(null);
            List<String> evidence = new ArrayList<>();
            evidence.add("metadata-action-catalog:" + decision.actionId());
            evidence.add("schema-grounding-verified");
            if (governedBinding != null && governedBinding.evidence() != null) {
                evidence.addAll(governedBinding.evidence());
            }
            verified.add(new OperationProjection(
                    governedBinding == null ? "" : governedBinding.conceptKey(),
                    governedBinding == null ? "" : governedBinding.bindingKey(),
                    "workflow_action",
                    resourceKey,
                    catalogResourcePath,
                    decision.apiPath(),
                    decision.method(),
                    schemaType(decision.method()),
                    schema.getEndpointUrl(),
                    catalogResult.getEndpointUrl(),
                    decision.operationId(),
                    decision.actionId(),
                    decision.scope(),
                    "runtime_action_discovery",
                    new AvailabilityProjection(
                            decision.catalogAllowed(),
                            decision.availabilityReason(),
                            decision.availabilityResolution()),
                    governedBinding == null ? "" : governedBinding.sourceRelease(),
                    List.copyOf(evidence)));
        }
    }

    private ActionDecision publishedAction(JsonNode action, String resourceKey, String resourcePath) {
        if (action == null || !action.isObject()) {
            return ActionDecision.blocked("operational-binding-action-catalog-entry-invalid");
        }
        String actionId = action.path("id").asText("").trim();
        String actionResourceKey = action.path("resourceKey").asText("").trim();
        String scope = action.path("scope").asText("").trim().toUpperCase(Locale.ROOT);
        String apiPath = canonicalPath(action.path("path").asText(""));
        String method = normalizedMethod(action.path("method").asText(""));
        String operationId = action.path("operationId").asText("").trim();
        if (!StringUtils.hasText(actionId)
                || !resourceKey.equals(actionResourceKey)
                || !("ITEM".equals(scope) || "COLLECTION".equals(scope))
                || !StringUtils.hasText(apiPath)
                || !apiPath.startsWith(resourcePath + "/")
                || !StringUtils.hasText(method)
                || !StringUtils.hasText(operationId)) {
            return ActionDecision.blocked("operational-binding-action-catalog-contract-mismatch");
        }
        boolean itemPath = apiPath.matches(".*/\\{[^/]+}/actions/[^/]+$");
        boolean collectionPath = apiPath.matches(".*/actions/[^/]+$") && !itemPath;
        if (("ITEM".equals(scope) && !itemPath)
                || ("COLLECTION".equals(scope) && !collectionPath)) {
            return ActionDecision.blocked("operational-binding-action-catalog-scope-mismatch");
        }
        JsonNode availability = action.path("availability");
        if (!availability.isObject() || !availability.path("allowed").isBoolean()) {
            return ActionDecision.blocked("operational-binding-action-catalog-availability-unverified");
        }
        boolean allowed = availability.path("allowed").asBoolean(false);
        String reason = availability.path("reason").asText("").trim();
        if ("ITEM".equals(scope)) {
            if (allowed || !"resource-context-required".equals(reason)) {
                return ActionDecision.blocked("operational-binding-action-catalog-item-context-invalid");
            }
            return ActionDecision.verified(
                    actionId,
                    operationId,
                    scope,
                    apiPath,
                    method,
                    false,
                    reason,
                    "item_capabilities_at_selection");
        }
        if (!allowed) {
            return ActionDecision.blocked("operational-binding-action-catalog-collection-unavailable");
        }
        return ActionDecision.verified(
                actionId,
                operationId,
                scope,
                apiPath,
                method,
                true,
                reason,
                "catalog_principal");
    }

    private CapabilityDecision operationAvailable(
            JsonNode capabilities,
            AgenticAuthoringDomainBindingService.BindingProjection binding,
            String method) {
        JsonNode root = capabilities != null && capabilities.path("data").isObject()
                ? capabilities.path("data")
                : capabilities;
        JsonNode operations = root == null ? null : root.path("operations");
        if (operations == null || !operations.isObject()) {
            return CapabilityDecision.blocked("operational-binding-capabilities-operations-missing");
        }
        for (String operationId : canonicalOperationIds(binding, method)) {
            JsonNode operation = operations.path(operationId);
            if (!operation.isObject() || !operation.path("supported").asBoolean(false)) {
                continue;
            }
            JsonNode availability = operation.path("availability");
            if (!availability.path("allowed").isBoolean()) {
                return CapabilityDecision.blocked("operational-binding-availability-unverified");
            }
            return availability.path("allowed").asBoolean(false)
                    ? CapabilityDecision.verified(operationId)
                    : CapabilityDecision.blocked("operational-binding-operation-unavailable");
        }
        return CapabilityDecision.blocked("operational-binding-operation-unsupported");
    }

    private List<String> canonicalOperationIds(
            AgenticAuthoringDomainBindingService.BindingProjection binding,
            String method) {
        if (binding != null
                && "api_operation".equals(binding.bindingType())
                && StringUtils.hasText(binding.bindingKey())) {
            return List.of(binding.bindingKey().trim());
        }
        if (binding != null
                && "workflow_action".equals(binding.bindingType())
                && StringUtils.hasText(binding.operationId())) {
            return List.of(binding.operationId().trim());
        }
        String bindingType = binding == null || binding.bindingType() == null
                ? ""
                : binding.bindingType().trim().toLowerCase(Locale.ROOT);
        String path = binding == null || binding.apiPath() == null
                ? ""
                : binding.apiPath().trim().toLowerCase(Locale.ROOT);
        if ("stats_endpoint".equals(bindingType)) {
            if (path.endsWith("/stats/timeseries")) {
                return List.of("statsTimeSeries");
            }
            if (path.endsWith("/stats/group-by")) {
                return List.of("statsGroupBy");
            }
            if (path.endsWith("/stats/distribution")) {
                return List.of("statsDistribution");
            }
        }
        if ("ui_surface".equals(bindingType)) {
            String resourcePath = canonicalResourcePath(binding.resourceKey());
            if ("post".equals(method) && path.equals(resourcePath)) {
                return List.of("create");
            }
            if ("get".equals(method) && path.endsWith("/all")) {
                return List.of("all", "list");
            }
            if ("post".equals(method) && path.endsWith("/filter/cursor")) {
                return List.of("cursor", "filter");
            }
            if ("post".equals(method) && path.endsWith("/filter")) {
                return List.of("filter");
            }
            if (path.contains("/{id}")) {
                return switch (method) {
                    case "get" -> List.of("byId", "view", "read", "get");
                    case "put" -> List.of("update", "edit", "replace");
                    case "patch" -> List.of("update", "edit", "patch");
                    case "delete" -> List.of("delete");
                    default -> List.of();
                };
            }
        }
        return switch (method) {
            case "get" -> List.of("list", "read", "get");
            case "post" -> List.of("create");
            case "put" -> List.of("replace", "update");
            case "patch" -> List.of("update", "patch");
            case "delete" -> List.of("delete");
            default -> List.of();
        };
    }

    private String canonicalResourcePath(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return null;
        }
        return "/api/" + resourceKey.trim().replace('.', '/');
    }

    private String canonicalPath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("/+$", "");
        if (!normalized.startsWith("/")
                || normalized.contains("..")
                || normalized.contains("?")
                || normalized.contains("#")
                || normalized.contains("://")) {
            return "";
        }
        return normalized;
    }

    private String normalizedMethod(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return List.of("get", "post", "put", "patch", "delete").contains(normalized)
                ? normalized
                : "";
    }

    private String schemaType(String method) {
        return "get".equals(method) ? "response" : "request";
    }

    private String schemaStatus(SchemaFetchResult result) {
        return result == null ? "unavailable" : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private String capabilitiesStatus(ResourceCapabilitiesFetchResult result) {
        return result == null ? "unavailable" : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private String actionCatalogStatus(ResourceActionCatalogFetchResult result) {
        return result == null ? "unavailable" : result.getStatus().name().toLowerCase(Locale.ROOT);
    }

    record VerificationResult(
            boolean verified,
            String resourceKey,
            List<OperationProjection> operations,
            List<String> failureCodes) {
        static VerificationResult blocked(String resourceKey, String failureCode) {
            return new VerificationResult(false, resourceKey, List.of(), List.of(failureCode));
        }
    }

    record OperationProjection(
            String conceptKey,
            String bindingKey,
            String kind,
            String resourceKey,
            String resourcePath,
            String apiPath,
            String apiMethod,
            String schemaType,
            String schemaUrl,
            String metadataUrl,
            String operationId,
            String actionId,
            String scope,
            String verificationMode,
            AvailabilityProjection availability,
            String sourceRelease,
            List<String> evidence) {

        boolean executableCandidate() {
            return !"runtime_action_discovery".equals(verificationMode);
        }
    }

    record AvailabilityProjection(Boolean allowed, String reason, String resolution) {
    }

    private record CapabilityDecision(boolean verified, String operationId, String failureCode) {
        static CapabilityDecision verified(String operationId) {
            return new CapabilityDecision(true, operationId, "");
        }

        static CapabilityDecision blocked(String failureCode) {
            return new CapabilityDecision(false, "", failureCode);
        }
    }

    private record ActionDecision(
            boolean verified,
            String actionId,
            String operationId,
            String scope,
            String apiPath,
            String method,
            Boolean catalogAllowed,
            String availabilityReason,
            String availabilityResolution,
            String failureCode) {

        static ActionDecision verified(
                String actionId,
                String operationId,
                String scope,
                String apiPath,
                String method,
                Boolean catalogAllowed,
                String availabilityReason,
                String availabilityResolution) {
            return new ActionDecision(
                    true,
                    actionId,
                    operationId,
                    scope,
                    apiPath,
                    method,
                    catalogAllowed,
                    availabilityReason,
                    availabilityResolution,
                    "");
        }

        static ActionDecision blocked(String failureCode) {
            return new ActionDecision(false, "", "", "", "", "", null, "", "", failureCode);
        }
    }
}
