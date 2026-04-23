package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainContext;
import org.praxisplatform.config.domain.DomainContextRelationship;
import org.praxisplatform.config.domain.DomainContract;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.domain.DomainSource;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;
import org.praxisplatform.config.repository.DomainContextRelationshipRepository;
import org.praxisplatform.config.repository.DomainContextRepository;
import org.praxisplatform.config.repository.DomainContractRepository;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.repository.DomainSourceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean(DomainCatalogIngestionService.class)
public class DomainFederationQueryService {

    private static final String SCHEMA_VERSION = "praxis.domain-federation-context/v0.1";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final DomainCatalogIngestionService domainCatalogIngestionService;
    private final DomainFederationRetrievalPolicyService retrievalPolicyService;
    private final DomainFederationReleaseRepository releaseRepository;
    private final DomainSourceRepository sourceRepository;
    private final DomainContextRepository contextRepository;
    private final DomainContextRelationshipRepository relationshipRepository;
    private final DomainContractRepository contractRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DomainFederationContextQueryResponse context(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String relationshipType,
            String query,
            int limit) {
        return context(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                itemType,
                contextKey,
                nodeType,
                relationshipType,
                query,
                limit,
                null);
    }

    @Transactional(readOnly = true)
    public DomainFederationContextQueryResponse context(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String relationshipType,
            String query,
            int limit,
            DomainFederationRetrievalPolicyOptions policyOptions) {
        int effectiveLimit = clampLimit(limit);
        Optional<DomainFederationRelease> activeRelease = activeRelease(tenantId, environment);
        if (activeRelease.isPresent()) {
            return persistedContext(
                    activeRelease.get(),
                    serviceKey,
                    resourceKey,
                    tenantId,
                    environment,
                    itemType,
                    contextKey,
                    nodeType,
                    relationshipType,
                    query,
                    effectiveLimit,
                    policyOptions);
        }
        return catalogProjectionContext(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                itemType,
                contextKey,
                nodeType,
                relationshipType,
                query,
                effectiveLimit,
                policyOptions);
    }

    private DomainFederationContextQueryResponse catalogProjectionContext(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String relationshipType,
            String query,
            int effectiveLimit,
            DomainFederationRetrievalPolicyOptions policyOptions) {
        DomainCatalogContextResponse context = domainCatalogIngestionService.contextLatest(
                normalize(serviceKey),
                normalize(resourceKey),
                normalize(tenantId),
                normalize(environment),
                normalize(itemType),
                normalize(contextKey),
                normalize(nodeType),
                normalize(query),
                effectiveLimit);
        List<DomainCatalogItemResponse> relationships = domainCatalogIngestionService.relationshipsLatest(
                normalize(serviceKey),
                normalize(resourceKey),
                normalize(tenantId),
                normalize(environment),
                null,
                null,
                normalize(relationshipType),
                normalize(query),
                effectiveLimit);
        DomainFederationRetrievalPolicyService.Result contextPolicy = retrievalPolicyService.apply(
                context == null ? List.of() : context.items(),
                policyOptions);
        DomainFederationRetrievalPolicyService.Result relationshipPolicy = retrievalPolicyService.apply(relationships, policyOptions);
        DomainCatalogContextResponse governedContext = context == null ? null : new DomainCatalogContextResponse(
                context.schemaVersion(),
                context.release(),
                context.query(),
                context.itemType(),
                context.contextKey(),
                context.nodeType(),
                context.retrievalGuidance(),
                contextPolicy.items());

        return new DomainFederationContextQueryResponse(
                SCHEMA_VERSION,
                normalize(tenantId),
                normalize(environment),
                normalize(serviceKey),
                normalize(resourceKey),
                normalize(query),
                normalize(contextKey),
                normalize(itemType),
                normalize(nodeType),
                normalize(relationshipType),
                effectiveLimit,
                !StringUtils.hasText(normalize(serviceKey)),
                "catalog_projection_fallback",
                catalogRetrievalGuidance(context, contextPolicy.report(), relationshipPolicy.report()),
                mergePolicyReports(contextPolicy.report(), relationshipPolicy.report()),
                governedContext,
                relationshipPolicy.items());
    }

    private DomainFederationContextQueryResponse persistedContext(
            DomainFederationRelease release,
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String relationshipType,
            String query,
            int effectiveLimit,
            DomainFederationRetrievalPolicyOptions policyOptions) {
        List<DomainSource> sources = sourceRepository.findByFederationRelease_IdOrderBySourceKey(release.getId());
        Map<String, DomainSource> sourceByKey = sources.stream()
                .collect(Collectors.toMap(DomainSource::getSourceKey, Function.identity(), (left, right) -> left));
        List<DomainContract> contracts = contractRepository.findByFederationRelease_IdOrderByContractKey(release.getId());
        Map<String, DomainContract> contractByKey = contracts.stream()
                .collect(Collectors.toMap(DomainContract::getContractKey, Function.identity(), (left, right) -> left));

        String normalizedServiceKey = normalize(serviceKey);
        String normalizedResourceKey = normalize(resourceKey);
        String normalizedContextKey = normalize(contextKey);
        String normalizedRelationshipType = normalize(relationshipType);
        String normalizedQuery = normalize(query);

        List<DomainCatalogItemResponse> contextItems = contextRepository
                .findByFederationRelease_IdOrderByContextKey(release.getId())
                .stream()
                .filter(context -> matchesText(context.getContextKey(), normalizedContextKey))
                .filter(context -> matchesSourceService(sourceByKey.get(context.getSourceKey()), normalizedServiceKey))
                .filter(context -> matchesContextResource(context, contractByKey.values().stream().toList(), normalizedResourceKey))
                .filter(context -> matchesQuery(List.of(
                        context.getContextKey(),
                        context.getLabel(),
                        context.getDescription(),
                        context.getSemanticOwner(),
                        context.getTechnicalOwner()), normalizedQuery))
                .limit(effectiveLimit)
                .map(context -> toContextItem(release, context, sourceByKey.get(context.getSourceKey())))
                .toList();

        List<DomainCatalogItemResponse> relationships = relationshipRepository
                .findByFederationRelease_IdOrderByRelationshipKey(release.getId())
                .stream()
                .filter(relationship -> matchesRelationshipContext(relationship, normalizedContextKey))
                .filter(relationship -> matchesText(relationship.getRelationshipType(), normalizedRelationshipType))
                .filter(relationship -> matchesContractResource(contractByKey.get(relationship.getContractKey()), normalizedResourceKey))
                .filter(relationship -> matchesRelationshipService(relationship, sourceByKey, contextItems, normalizedServiceKey))
                .filter(relationship -> matchesQuery(List.of(
                        relationship.getRelationshipKey(),
                        relationship.getSourceContextKey(),
                        relationship.getTargetContextKey(),
                        relationship.getRelationshipType(),
                        relationship.getContractKey()), normalizedQuery))
                .limit(effectiveLimit)
                .map(relationship -> toRelationshipItem(release, relationship, contractByKey.get(relationship.getContractKey())))
                .toList();

        DomainFederationRetrievalPolicyService.Result contextPolicy = retrievalPolicyService.apply(contextItems, policyOptions);
        DomainFederationRetrievalPolicyService.Result relationshipPolicy = retrievalPolicyService.apply(relationships, policyOptions);
        DomainCatalogContextResponse governedContext = new DomainCatalogContextResponse(
                "praxis.domain-federation-context-items/v0.1",
                toReleaseResponse(release),
                normalize(query),
                "context",
                normalize(contextKey),
                normalize(nodeType),
                List.of("Context items are read from the active persisted domain federation release."),
                contextPolicy.items());

        return new DomainFederationContextQueryResponse(
                SCHEMA_VERSION,
                normalize(tenantId),
                normalize(environment),
                normalize(serviceKey),
                normalize(resourceKey),
                normalize(query),
                normalize(contextKey),
                normalize(itemType),
                normalize(nodeType),
                normalize(relationshipType),
                effectiveLimit,
                true,
                "persisted_federation",
                persistedRetrievalGuidance(release, contextPolicy.report(), relationshipPolicy.report()),
                mergePolicyReports(contextPolicy.report(), relationshipPolicy.report()),
                governedContext,
                relationshipPolicy.items());
    }

    private Optional<DomainFederationRelease> activeRelease(String tenantId, String environment) {
        return releaseRepository.findActiveByOptionalScope(normalize(tenantId), normalize(environment)).stream()
                .findFirst();
    }

    private List<String> catalogRetrievalGuidance(
            DomainCatalogContextResponse context,
            DomainFederationRetrievalPolicyReport contextPolicy,
            DomainFederationRetrievalPolicyReport relationshipPolicy) {
        List<String> guidance = new ArrayList<>();
        if (context != null && context.retrievalGuidance() != null) {
            guidance.addAll(context.retrievalGuidance());
        }
        guidance.add("Federated context is currently projected from domain catalog releases and edge rows.");
        guidance.add("Federation retrieval policy excludes aiUsage.visibility=deny and reports governed or low-confidence items.");
        guidance.add("Contracts, resolutions and final redaction decisions are validated separately and are not yet materialized in query results.");
        guidance.addAll(contextPolicy.decisions());
        guidance.addAll(relationshipPolicy.decisions());
        return List.copyOf(guidance);
    }

    private List<String> persistedRetrievalGuidance(
            DomainFederationRelease release,
            DomainFederationRetrievalPolicyReport contextPolicy,
            DomainFederationRetrievalPolicyReport relationshipPolicy) {
        List<String> guidance = new ArrayList<>();
        guidance.add("Federated context is read from active persisted domain federation release " + release.getReleaseKey() + ".");
        guidance.add("Only explicit persisted contexts and relationships are returned; no label inference is performed.");
        guidance.add("Federation retrieval policy excludes aiUsage.visibility=deny and reports governed or low-confidence items.");
        guidance.addAll(contextPolicy.decisions());
        guidance.addAll(relationshipPolicy.decisions());
        return List.copyOf(guidance);
    }

    private DomainFederationRetrievalPolicyReport mergePolicyReports(
            DomainFederationRetrievalPolicyReport contextPolicy,
            DomainFederationRetrievalPolicyReport relationshipPolicy) {
        List<String> decisions = new ArrayList<>();
        decisions.addAll(contextPolicy.decisions());
        decisions.addAll(relationshipPolicy.decisions());
        return new DomainFederationRetrievalPolicyReport(
                contextPolicy.policyProfile(),
                contextPolicy.minConfidence(),
                contextPolicy.includeDenied(),
                contextPolicy.includeLowConfidence(),
                contextPolicy.inputItemCount() + relationshipPolicy.inputItemCount(),
                contextPolicy.returnedItemCount() + relationshipPolicy.returnedItemCount(),
                contextPolicy.deniedItemCount() + relationshipPolicy.deniedItemCount(),
                contextPolicy.governedSummaryItemCount() + relationshipPolicy.governedSummaryItemCount(),
                contextPolicy.lowConfidenceItemCount() + relationshipPolicy.lowConfidenceItemCount(),
                List.copyOf(decisions));
    }

    private DomainCatalogItemResponse toContextItem(
            DomainFederationRelease release,
            DomainContext context,
            DomainSource source) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceMode", "persisted_federation");
        payload.put("releaseKey", release.getReleaseKey());
        payload.put("contextKey", context.getContextKey());
        payload.put("sourceKey", context.getSourceKey());
        putIfText(payload, "serviceKey", source == null ? null : source.getServiceKey());
        putIfText(payload, "contextType", context.getContextType());
        putIfText(payload, "label", context.getLabel());
        putIfText(payload, "description", context.getDescription());
        putIfText(payload, "semanticOwner", context.getSemanticOwner());
        putIfText(payload, "technicalOwner", context.getTechnicalOwner());
        putIfText(payload, "status", context.getStatus());
        putIfText(payload, "latestReleaseKey", context.getLatestReleaseKey());
        payload.set("evidence", read(context.getEvidence()));
        return new DomainCatalogItemResponse(
                context.getId(),
                release.getReleaseKey(),
                "context",
                context.getContextKey(),
                context.getContextKey(),
                context.getContextType(),
                null,
                null,
                payload);
    }

    private DomainCatalogItemResponse toRelationshipItem(
            DomainFederationRelease release,
            DomainContextRelationship relationship,
            DomainContract contract) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceMode", "persisted_federation");
        payload.put("releaseKey", release.getReleaseKey());
        payload.put("sourceContextKey", relationship.getSourceContextKey());
        payload.put("targetContextKey", relationship.getTargetContextKey());
        putIfText(payload, "contractKey", relationship.getContractKey());
        putIfText(payload, "direction", relationship.getDirection());
        putIfText(payload, "ownership", relationship.getOwnership());
        putIfText(payload, "status", relationship.getStatus());
        if (relationship.getConfidence() != null) {
            payload.put("confidence", relationship.getConfidence());
        }
        if (contract != null) {
            ObjectNode contractNode = payload.putObject("contract");
            putIfText(contractNode, "contractKey", contract.getContractKey());
            putIfText(contractNode, "contractType", contract.getContractType());
            putIfText(contractNode, "providerSourceKey", contract.getProviderSourceKey());
            putIfText(contractNode, "providerContextKey", contract.getProviderContextKey());
            putIfText(contractNode, "consumerContextKey", contract.getConsumerContextKey());
            putIfText(contractNode, "resourceKey", contract.getResourceKey());
            putIfText(contractNode, "operationKey", contract.getOperationKey());
            putIfText(contractNode, "schemaRef", contract.getSchemaRef());
            putIfText(contractNode, "compatibility", contract.getCompatibility());
            putIfText(contractNode, "visibility", contract.getVisibility());
            putIfText(contractNode, "status", contract.getStatus());
        }
        payload.set("evidence", read(relationship.getEvidence()));
        return new DomainCatalogItemResponse(
                relationship.getId(),
                release.getReleaseKey(),
                "edge",
                relationship.getRelationshipKey(),
                relationship.getSourceContextKey(),
                null,
                null,
                relationship.getRelationshipType(),
                payload);
    }

    private DomainCatalogReleaseResponse toReleaseResponse(DomainFederationRelease release) {
        return new DomainCatalogReleaseResponse(
                release.getId(),
                release.getReleaseKey(),
                "praxis.domain-federation/v0.1",
                null,
                "Domain Federation",
                null,
                release.getActivatedAt(),
                release.getPayloadHash(),
                release.getTenantId(),
                release.getEnvironment(),
                release.getCreatedAt());
    }

    private boolean matchesSourceService(DomainSource source, String serviceKey) {
        return !StringUtils.hasText(serviceKey)
                || (source != null && matchesText(source.getServiceKey(), serviceKey));
    }

    private boolean matchesRelationshipService(
            DomainContextRelationship relationship,
            Map<String, DomainSource> sourceByKey,
            List<DomainCatalogItemResponse> contextItems,
            String serviceKey) {
        if (!StringUtils.hasText(serviceKey)) {
            return true;
        }
        if (contextItems.stream().anyMatch(context -> matchesText(context.itemKey(), relationship.getSourceContextKey())
                || matchesText(context.itemKey(), relationship.getTargetContextKey()))) {
            return true;
        }
        return sourceByKey.values().stream()
                .anyMatch(source -> matchesText(source.getServiceKey(), serviceKey)
                        && (matchesText(source.getSourceKey(), relationship.getSourceContextKey())
                        || matchesText(source.getSourceKey(), relationship.getTargetContextKey())));
    }

    private boolean matchesRelationshipContext(DomainContextRelationship relationship, String contextKey) {
        return !StringUtils.hasText(contextKey)
                || matchesText(relationship.getSourceContextKey(), contextKey)
                || matchesText(relationship.getTargetContextKey(), contextKey);
    }

    private boolean matchesContextResource(DomainContext context, List<DomainContract> contracts, String resourceKey) {
        return !StringUtils.hasText(resourceKey)
                || contracts.stream().anyMatch(contract -> matchesContractResource(contract, resourceKey)
                && (matchesText(contract.getProviderContextKey(), context.getContextKey())
                || matchesText(contract.getConsumerContextKey(), context.getContextKey())));
    }

    private boolean matchesContractResource(DomainContract contract, String resourceKey) {
        return !StringUtils.hasText(resourceKey)
                || (contract != null && matchesText(contract.getResourceKey(), resourceKey));
    }

    private boolean matchesQuery(List<String> values, String query) {
        if (!StringUtils.hasText(query)) {
            return true;
        }
        String normalizedQuery = query.toLowerCase();
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .anyMatch(value -> value.contains(normalizedQuery));
    }

    private boolean matchesText(String value, String expected) {
        return !StringUtils.hasText(expected) || expected.equals(value);
    }

    private JsonNode read(String raw) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("unreadable", true);
            return fallback;
        }
    }

    private void putIfText(ObjectNode node, String field, String value) {
        if (StringUtils.hasText(value)) {
            node.put(field, value);
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
