package org.praxisplatform.config.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextRelationship;
import org.praxisplatform.config.dto.DomainFederationContract;
import org.praxisplatform.config.dto.DomainFederationResolution;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationIssue;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DomainFederationContractValidator {

    public static final String SCHEMA_VERSION = "praxis.domain-federation/v0.1";

    private static final Set<String> SOURCE_TYPES = Set.of(
            "microservice",
            "monolith",
            "external_system",
            "manual_catalog",
            "generated",
            "federated"
    );
    private static final Set<String> SOURCE_TRUST_LEVELS = Set.of(
            "authoritative",
            "curated",
            "generated",
            "experimental",
            "untrusted"
    );
    private static final Set<String> SOURCE_STATUSES = Set.of("active", "deprecated", "retired", "blocked");
    private static final Set<String> CONTEXT_TYPES = Set.of(
            "bounded_context",
            "subdomain",
            "capability",
            "external_context",
            "federated_context",
            "system",
            "application",
            "external_system",
            "legacy_system"
    );
    private static final Set<String> CONTEXT_STATUSES = Set.of("candidate", "active", "deprecated", "blocked", "retired");
    private static final Set<String> RELATIONSHIP_TYPES = Set.of(
            "references",
            "depends_on",
            "uses",
            "publishes_to",
            "subscribes_to",
            "shared_kernel",
            "anti_corruption_layer",
            "customer_supplier",
            "conformist",
            "open_host_service",
            "separate_ways"
    );
    private static final Set<String> RELATIONSHIP_DIRECTIONS = Set.of(
            "source_to_target",
            "target_to_source",
            "bidirectional"
    );
    private static final Set<String> RELATIONSHIP_OWNERSHIPS = Set.of(
            "source_owned",
            "target_owned",
            "shared",
            "external",
            "unknown"
    );
    private static final Set<String> FEDERATION_STATUSES = Set.of(
            "candidate",
            "active",
            "deprecated",
            "blocked",
            "conflict",
            "rejected"
    );
    private static final Set<String> CONTRACT_TYPES = Set.of(
            "rest_endpoint",
            "openapi_operation",
            "event_schema",
            "asyncapi_message",
            "shared_identifier",
            "lookup_option_source",
            "workflow_action",
            "external_system",
            "policy_dependency"
    );
    private static final Set<String> CONTRACT_COMPATIBILITY = Set.of(
            "stable",
            "backward_compatible",
            "breaking",
            "experimental"
    );
    private static final Set<String> CONTRACT_VISIBILITY = Set.of(
            "public",
            "internal",
            "restricted",
            "deny_for_llm"
    );
    private static final Set<String> CONTRACT_STATUSES = Set.of(
            "candidate",
            "active",
            "deprecated",
            "blocked",
            "retired"
    );
    private static final Set<String> RESOLUTION_TYPES = Set.of(
            "same_as",
            "equivalent_to",
            "broader_than",
            "narrower_than",
            "maps_to",
            "local_projection_of",
            "conflicts_with"
    );
    private static final Set<String> RESOLUTION_STATUSES = Set.of(
            "candidate",
            "review_required",
            "approved",
            "rejected",
            "conflict"
    );

    public DomainFederationValidationReport validate(DomainFederationValidationRequest request) {
        List<DomainFederationValidationIssue> issues = new ArrayList<>();
        if (request == null) {
            error(issues, "request.required", "/", "domain federation validation request is required");
            return report(issues);
        }

        validateSchema(request, issues);

        Map<String, DomainFederationSource> sources = indexSources(request, issues);
        Map<String, DomainFederationContext> contexts = indexContexts(request, sources, issues);
        Map<String, DomainFederationContract> contracts = indexContracts(request.contracts(), sources, contexts, issues);

        validateRelationships(request.contextRelationships(), contexts, contracts, issues);
        validateResolutions(request.resolutions(), contexts, issues);

        return report(issues);
    }

    private void validateSchema(DomainFederationValidationRequest request, List<DomainFederationValidationIssue> issues) {
        if (!SCHEMA_VERSION.equals(trim(request.schemaVersion()))) {
            error(issues, "schemaVersion.unsupported", "/schemaVersion",
                    "schemaVersion must be " + SCHEMA_VERSION);
        }
        if (!hasText(request.tenantId())) {
            error(issues, "tenant.required", "/tenantId", "tenantId is required");
        }
        if (!hasText(request.environment())) {
            error(issues, "environment.required", "/environment", "environment is required");
        }
    }

    private Map<String, DomainFederationSource> indexSources(
            DomainFederationValidationRequest request,
            List<DomainFederationValidationIssue> issues) {
        Map<String, DomainFederationSource> sources = new HashMap<>();
        List<DomainFederationSource> sourceList = request.sources();
        List<DomainFederationSource> effectiveSources = sourceList == null ? List.of() : sourceList;
        if (effectiveSources.isEmpty()) {
            error(issues, "sources.required", "/sources", "at least one domain_source is required");
        }

        for (int i = 0; i < effectiveSources.size(); i++) {
            DomainFederationSource source = effectiveSources.get(i);
            String pointer = "/sources/" + i;
            if (source == null) {
                error(issues, "source.required", pointer, "domain_source entry is required");
                continue;
            }

            String sourceKey = trim(source.sourceKey());
            if (!hasText(sourceKey)) {
                error(issues, "source.sourceKey.required", pointer + "/sourceKey", "sourceKey is required");
            } else if (sources.putIfAbsent(sourceKey, source) != null) {
                error(issues, "source.sourceKey.duplicate", pointer + "/sourceKey", "sourceKey must be unique: " + sourceKey);
            }

            requireEnum(source.sourceType(), SOURCE_TYPES, pointer + "/sourceType", "source.sourceType.unsupported", issues);
            requireText(source.serviceKey(), pointer + "/serviceKey", "source.serviceKey.required", "serviceKey is required", issues);
            requireText(source.serviceName(), pointer + "/serviceName", "source.serviceName.required", "serviceName is required", issues);
            requireText(source.tenantId(), pointer + "/tenantId", "source.tenantId.required", "tenantId is required", issues);
            requireSameScope(source.tenantId(), request.tenantId(), pointer + "/tenantId", "source.tenantId.mismatch",
                    "source tenantId must match request tenantId", issues);
            requireText(source.environment(), pointer + "/environment", "source.environment.required", "environment is required", issues);
            requireSameScope(source.environment(), request.environment(), pointer + "/environment", "source.environment.mismatch",
                    "source environment must match request environment", issues);
            requireText(source.semanticOwner(), pointer + "/semanticOwner", "source.semanticOwner.required", "semanticOwner is required", issues);
            requireText(source.technicalOwner(), pointer + "/technicalOwner", "source.technicalOwner.required", "technicalOwner is required", issues);
            requireEnum(source.trustLevel(), SOURCE_TRUST_LEVELS, pointer + "/trustLevel", "source.trustLevel.unsupported", issues);
            requireEnum(source.status(), SOURCE_STATUSES, pointer + "/status", "source.status.unsupported", issues);

            if ("active".equals(normalize(source.status())) && "untrusted".equals(normalize(source.trustLevel()))) {
                error(issues, "source.untrustedActive", pointer,
                        "source trustLevel=untrusted cannot be active");
            }
            if ("generated".equals(normalize(source.trustLevel())) && "active".equals(normalize(source.status()))) {
                warning(issues, "source.generatedActive", pointer,
                        "generated active source should receive human review");
            }
        }

        return sources;
    }

    private Map<String, DomainFederationContext> indexContexts(
            DomainFederationValidationRequest request,
            Map<String, DomainFederationSource> sources,
            List<DomainFederationValidationIssue> issues) {
        Map<String, DomainFederationContext> contexts = new HashMap<>();
        List<DomainFederationContext> contextList = request.contexts();
        List<DomainFederationContext> effectiveContexts = contextList == null ? List.of() : contextList;
        if (effectiveContexts.isEmpty()) {
            error(issues, "contexts.required", "/contexts", "at least one domain_context is required");
        }

        for (int i = 0; i < effectiveContexts.size(); i++) {
            DomainFederationContext context = effectiveContexts.get(i);
            String pointer = "/contexts/" + i;
            if (context == null) {
                error(issues, "context.required", pointer, "domain_context entry is required");
                continue;
            }

            String contextKey = trim(context.contextKey());
            if (!hasText(contextKey)) {
                error(issues, "context.contextKey.required", pointer + "/contextKey", "contextKey is required");
            } else if (contexts.putIfAbsent(contextKey, context) != null) {
                error(issues, "context.contextKey.duplicate", pointer + "/contextKey", "contextKey must be unique: " + contextKey);
            }

            requireText(context.sourceKey(), pointer + "/sourceKey", "context.sourceKey.required", "sourceKey is required", issues);
            if (hasText(context.sourceKey()) && !sources.containsKey(trim(context.sourceKey()))) {
                error(issues, "context.sourceKey.unknown", pointer + "/sourceKey",
                        "context sourceKey must reference a known domain_source: " + context.sourceKey());
            }
            requireEnum(context.contextType(), CONTEXT_TYPES, pointer + "/contextType", "context.contextType.unsupported", issues);
            requireText(context.label(), pointer + "/label", "context.label.required", "label is required", issues);
            requireText(context.description(), pointer + "/description", "context.description.required", "description is required", issues);
            requireText(context.semanticOwner(), pointer + "/semanticOwner", "context.semanticOwner.required", "semanticOwner is required", issues);
            requireText(context.technicalOwner(), pointer + "/technicalOwner", "context.technicalOwner.required", "technicalOwner is required", issues);
            requireText(context.tenantId(), pointer + "/tenantId", "context.tenantId.required", "tenantId is required", issues);
            requireSameScope(context.tenantId(), request.tenantId(), pointer + "/tenantId", "context.tenantId.mismatch",
                    "context tenantId must match request tenantId", issues);
            requireText(context.environment(), pointer + "/environment", "context.environment.required", "environment is required", issues);
            requireSameScope(context.environment(), request.environment(), pointer + "/environment", "context.environment.mismatch",
                    "context environment must match request environment", issues);
            requireEnum(context.status(), CONTEXT_STATUSES, pointer + "/status", "context.status.unsupported", issues);
        }

        return contexts;
    }

    private Map<String, DomainFederationContract> indexContracts(
            List<DomainFederationContract> contractList,
            Map<String, DomainFederationSource> sources,
            Map<String, DomainFederationContext> contexts,
            List<DomainFederationValidationIssue> issues) {
        Map<String, DomainFederationContract> contracts = new HashMap<>();
        List<DomainFederationContract> effectiveContracts = contractList == null ? List.of() : contractList;

        for (int i = 0; i < effectiveContracts.size(); i++) {
            DomainFederationContract contract = effectiveContracts.get(i);
            String pointer = "/contracts/" + i;
            if (contract == null) {
                error(issues, "contract.required", pointer, "domain_contract entry is required");
                continue;
            }

            String contractKey = trim(contract.contractKey());
            if (!hasText(contractKey)) {
                error(issues, "contract.contractKey.required", pointer + "/contractKey", "contractKey is required");
            } else if (contracts.putIfAbsent(contractKey, contract) != null) {
                error(issues, "contract.contractKey.duplicate", pointer + "/contractKey", "contractKey must be unique: " + contractKey);
            }

            requireEnum(contract.contractType(), CONTRACT_TYPES, pointer + "/contractType", "contract.contractType.unsupported", issues);
            requireText(contract.providerSourceKey(), pointer + "/providerSourceKey", "contract.providerSourceKey.required", "providerSourceKey is required", issues);
            if (hasText(contract.providerSourceKey()) && !sources.containsKey(trim(contract.providerSourceKey()))) {
                error(issues, "contract.providerSourceKey.unknown", pointer + "/providerSourceKey",
                        "providerSourceKey must reference a known domain_source: " + contract.providerSourceKey());
            }
            requireText(contract.providerContextKey(), pointer + "/providerContextKey", "contract.providerContextKey.required", "providerContextKey is required", issues);
            if (hasText(contract.providerContextKey()) && !contexts.containsKey(trim(contract.providerContextKey()))) {
                error(issues, "contract.providerContextKey.unknown", pointer + "/providerContextKey",
                        "providerContextKey must reference a known domain_context: " + contract.providerContextKey());
            }
            if (hasText(contract.consumerContextKey()) && !contexts.containsKey(trim(contract.consumerContextKey()))) {
                error(issues, "contract.consumerContextKey.unknown", pointer + "/consumerContextKey",
                        "consumerContextKey must reference a known domain_context: " + contract.consumerContextKey());
            }
            requireEnum(contract.compatibility(), CONTRACT_COMPATIBILITY, pointer + "/compatibility", "contract.compatibility.unsupported", issues);
            requireEnum(contract.visibility(), CONTRACT_VISIBILITY, pointer + "/visibility", "contract.visibility.unsupported", issues);
            requireEnum(contract.status(), CONTRACT_STATUSES, pointer + "/status", "contract.status.unsupported", issues);

            if ("experimental".equals(normalize(contract.compatibility()))) {
                warning(issues, "contract.experimental", pointer + "/compatibility",
                        "experimental contract requires review before authoring use");
            }
            if ("deny_for_llm".equals(normalize(contract.visibility())) && "active".equals(normalize(contract.status()))) {
                warning(issues, "contract.llmDenied", pointer + "/visibility",
                        "active contract is denied for LLM context and must be redacted from retrieval");
            }
        }

        return contracts;
    }

    private void validateRelationships(
            List<DomainFederationContextRelationship> relationshipList,
            Map<String, DomainFederationContext> contexts,
            Map<String, DomainFederationContract> contracts,
            List<DomainFederationValidationIssue> issues) {
        List<DomainFederationContextRelationship> effectiveRelationships =
                relationshipList == null ? List.of() : relationshipList;
        Set<String> relationshipKeys = new HashSet<>();

        for (int i = 0; i < effectiveRelationships.size(); i++) {
            DomainFederationContextRelationship relationship = effectiveRelationships.get(i);
            String pointer = "/contextRelationships/" + i;
            if (relationship == null) {
                error(issues, "relationship.required", pointer, "domain_context_relationship entry is required");
                continue;
            }

            String relationshipKey = trim(relationship.relationshipKey());
            if (!hasText(relationshipKey)) {
                error(issues, "relationship.relationshipKey.required", pointer + "/relationshipKey", "relationshipKey is required");
            } else if (!relationshipKeys.add(relationshipKey)) {
                error(issues, "relationship.relationshipKey.duplicate", pointer + "/relationshipKey",
                        "relationshipKey must be unique: " + relationshipKey);
            }

            requireKnownContext(relationship.sourceContextKey(), contexts, pointer + "/sourceContextKey",
                    "relationship.sourceContextKey", issues);
            requireKnownContext(relationship.targetContextKey(), contexts, pointer + "/targetContextKey",
                    "relationship.targetContextKey", issues);
            requireEnum(relationship.relationshipType(), RELATIONSHIP_TYPES, pointer + "/relationshipType",
                    "relationship.relationshipType.unsupported", issues);
            requireEnum(relationship.direction(), RELATIONSHIP_DIRECTIONS, pointer + "/direction",
                    "relationship.direction.unsupported", issues);
            requireEnum(relationship.ownership(), RELATIONSHIP_OWNERSHIPS, pointer + "/ownership",
                    "relationship.ownership.unsupported", issues);
            requireEnum(relationship.status(), FEDERATION_STATUSES, pointer + "/status",
                    "relationship.status.unsupported", issues);
            validateConfidence(relationship.confidence(), pointer + "/confidence", "relationship.confidence.invalid", issues);

            if (requiresContract(relationship.relationshipType()) && !hasText(relationship.contractKey())) {
                error(issues, "relationship.contractKey.required", pointer + "/contractKey",
                        "relationshipType " + relationship.relationshipType() + " requires contractKey");
            }
            if (hasText(relationship.contractKey()) && !contracts.containsKey(trim(relationship.contractKey()))) {
                error(issues, "relationship.contractKey.unknown", pointer + "/contractKey",
                        "contractKey must reference a known domain_contract: " + relationship.contractKey());
            }
            if (relationship.confidence() != null && relationship.confidence() < 0.7) {
                warning(issues, "relationship.lowConfidence", pointer + "/confidence",
                        "low-confidence relationship requires semantic review");
            }
        }
    }

    private void validateResolutions(
            List<DomainFederationResolution> resolutionList,
            Map<String, DomainFederationContext> contexts,
            List<DomainFederationValidationIssue> issues) {
        List<DomainFederationResolution> effectiveResolutions = resolutionList == null ? List.of() : resolutionList;
        Set<String> resolutionKeys = new HashSet<>();

        for (int i = 0; i < effectiveResolutions.size(); i++) {
            DomainFederationResolution resolution = effectiveResolutions.get(i);
            String pointer = "/resolutions/" + i;
            if (resolution == null) {
                error(issues, "resolution.required", pointer, "domain_resolution entry is required");
                continue;
            }

            String resolutionKey = trim(resolution.resolutionKey());
            if (!hasText(resolutionKey)) {
                error(issues, "resolution.resolutionKey.required", pointer + "/resolutionKey", "resolutionKey is required");
            } else if (!resolutionKeys.add(resolutionKey)) {
                error(issues, "resolution.resolutionKey.duplicate", pointer + "/resolutionKey",
                        "resolutionKey must be unique: " + resolutionKey);
            }

            requireText(resolution.sourceConceptKey(), pointer + "/sourceConceptKey",
                    "resolution.sourceConceptKey.required", "sourceConceptKey is required", issues);
            requireText(resolution.targetConceptKey(), pointer + "/targetConceptKey",
                    "resolution.targetConceptKey.required", "targetConceptKey is required", issues);
            requireKnownContext(resolution.sourceContextKey(), contexts, pointer + "/sourceContextKey",
                    "resolution.sourceContextKey", issues);
            requireKnownContext(resolution.targetContextKey(), contexts, pointer + "/targetContextKey",
                    "resolution.targetContextKey", issues);
            requireEnum(resolution.resolutionType(), RESOLUTION_TYPES, pointer + "/resolutionType",
                    "resolution.resolutionType.unsupported", issues);
            requireEnum(resolution.status(), RESOLUTION_STATUSES, pointer + "/status",
                    "resolution.status.unsupported", issues);
            validateConfidence(resolution.confidence(), pointer + "/confidence", "resolution.confidence.invalid", issues);

            if (resolution.confidence() != null && resolution.confidence() < 0.85) {
                warning(issues, "resolution.lowConfidence", pointer + "/confidence",
                        "low-confidence resolution requires review");
            }
            if ("conflicts_with".equals(normalize(resolution.resolutionType()))
                    && !"conflict".equals(normalize(resolution.status()))) {
                warning(issues, "resolution.conflictStatus", pointer + "/status",
                        "conflicts_with resolution should use status=conflict");
            }
            if (("review_required".equals(normalize(resolution.status()))
                    || "conflict".equals(normalize(resolution.status())))
                    && !hasText(resolution.reviewOwner())) {
                warning(issues, "resolution.reviewOwner.required", pointer + "/reviewOwner",
                        "reviewOwner should be set for review_required or conflict resolutions");
            }
        }
    }

    private boolean requiresContract(String relationshipType) {
        String normalized = normalize(relationshipType);
        return Set.of("references", "depends_on", "uses", "publishes_to", "subscribes_to", "open_host_service")
                .contains(normalized);
    }

    private void requireKnownContext(
            String contextKey,
            Map<String, DomainFederationContext> contexts,
            String pointer,
            String codePrefix,
            List<DomainFederationValidationIssue> issues) {
        if (!hasText(contextKey)) {
            error(issues, codePrefix + ".required", pointer, "context key is required");
            return;
        }
        if (!contexts.containsKey(trim(contextKey))) {
            error(issues, codePrefix + ".unknown", pointer,
                    "context key must reference a known domain_context: " + contextKey);
        }
    }

    private void requireText(
            String value,
            String pointer,
            String code,
            String message,
            List<DomainFederationValidationIssue> issues) {
        if (!hasText(value)) {
            error(issues, code, pointer, message);
        }
    }

    private void requireEnum(
            String value,
            Set<String> supportedValues,
            String pointer,
            String code,
            List<DomainFederationValidationIssue> issues) {
        if (!hasText(value)) {
            error(issues, code, pointer, "value is required and must be one of " + supportedValues);
            return;
        }
        if (!supportedValues.contains(normalize(value))) {
            error(issues, code, pointer, "unsupported value '" + value + "'. Supported values: " + supportedValues);
        }
    }

    private void requireSameScope(
            String actual,
            String expected,
            String pointer,
            String code,
            String message,
            List<DomainFederationValidationIssue> issues) {
        if (hasText(actual) && hasText(expected) && !trim(actual).equals(trim(expected))) {
            error(issues, code, pointer, message + ": expected " + trim(expected) + " but found " + trim(actual));
        }
    }

    private void validateConfidence(
            Double confidence,
            String pointer,
            String code,
            List<DomainFederationValidationIssue> issues) {
        if (confidence == null) {
            return;
        }
        if (confidence < 0.0 || confidence > 1.0 || confidence.isNaN()) {
            error(issues, code, pointer, "confidence must be between 0.0 and 1.0");
        }
    }

    private DomainFederationValidationReport report(List<DomainFederationValidationIssue> issues) {
        int errorCount = (int) issues.stream().filter(issue -> "error".equals(issue.severity())).count();
        int warningCount = (int) issues.stream().filter(issue -> "warning".equals(issue.severity())).count();
        return new DomainFederationValidationReport(errorCount == 0, errorCount, warningCount, List.copyOf(issues));
    }

    private void error(List<DomainFederationValidationIssue> issues, String code, String pointer, String message) {
        issues.add(new DomainFederationValidationIssue("error", code, pointer, message));
    }

    private void warning(List<DomainFederationValidationIssue> issues, String code, String pointer, String message) {
        issues.add(new DomainFederationValidationIssue("warning", code, pointer, message));
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String normalize(String value) {
        return trim(value) == null ? "" : trim(value).toLowerCase(Locale.ROOT);
    }
}
