package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
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

    public AgenticAuthoringOperationalBindingVerificationService(
            AgenticAuthoringDomainBindingService bindingService,
            SchemaRetrievalService schemaRetrievalService,
            ResourceCapabilitiesRetrievalService capabilitiesRetrievalService) {
        this.bindingService = bindingService;
        this.schemaRetrievalService = schemaRetrievalService;
        this.capabilitiesRetrievalService = capabilitiesRetrievalService;
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
        for (AgenticAuthoringDomainBindingService.BindingProjection binding : bindings) {
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
            ResourceCapabilitiesFetchResult capabilities = capabilitiesRetrievalService.fetchCapabilitiesResult(
                    binding.apiPath(),
                    requestBaseUrl,
                    principalContext.tenantId(),
                    principalContext.userId(),
                    principalContext.environment());
            if (capabilities == null || !capabilities.isSuccess()) {
                failures.add("operational-binding-capabilities-" + capabilitiesStatus(capabilities));
                continue;
            }
            CapabilityDecision capabilityDecision = operationAvailable(capabilities.getCapabilities(), method);
            if (!capabilityDecision.verified()) {
                failures.add(capabilityDecision.failureCode());
                continue;
            }
            verified.add(new OperationProjection(
                    binding.conceptKey(),
                    binding.bindingKey(),
                    binding.resourceKey(),
                    binding.apiPath(),
                    method,
                    schemaType(method),
                    schema.getEndpointUrl(),
                    capabilities.getEndpointUrl(),
                    capabilityDecision.operationId(),
                    binding.sourceRelease(),
                    binding.evidence()));
        }
        return verified.isEmpty()
                ? new VerificationResult(false, resourceKey, List.of(), List.copyOf(failures))
                : new VerificationResult(true, resourceKey, List.copyOf(verified), List.copyOf(failures));
    }

    private CapabilityDecision operationAvailable(JsonNode capabilities, String method) {
        JsonNode root = capabilities != null && capabilities.path("data").isObject()
                ? capabilities.path("data")
                : capabilities;
        JsonNode operations = root == null ? null : root.path("operations");
        if (operations == null || !operations.isObject()) {
            return CapabilityDecision.blocked("operational-binding-capabilities-operations-missing");
        }
        for (String operationId : canonicalOperationIds(method)) {
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

    private List<String> canonicalOperationIds(String method) {
        return switch (method) {
            case "get" -> List.of("list", "read", "get");
            case "post" -> List.of("create");
            case "put" -> List.of("replace", "update");
            case "patch" -> List.of("update", "patch");
            case "delete" -> List.of("delete");
            default -> List.of();
        };
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
            String resourceKey,
            String apiPath,
            String apiMethod,
            String schemaType,
            String schemaUrl,
            String capabilitiesUrl,
            String capabilityOperationId,
            String sourceRelease,
            List<String> evidence) {
    }

    private record CapabilityDecision(boolean verified, String operationId, String failureCode) {
        static CapabilityDecision verified(String operationId) {
            return new CapabilityDecision(true, operationId, "");
        }

        static CapabilityDecision blocked(String failureCode) {
            return new CapabilityDecision(false, "", failureCode);
        }
    }
}
