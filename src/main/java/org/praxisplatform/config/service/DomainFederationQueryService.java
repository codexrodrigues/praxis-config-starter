package org.praxisplatform.config.service;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;
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
                retrievalGuidance(context, contextPolicy.report(), relationshipPolicy.report()),
                mergePolicyReports(contextPolicy.report(), relationshipPolicy.report()),
                governedContext,
                relationshipPolicy.items());
    }

    private List<String> retrievalGuidance(
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

    private DomainFederationRetrievalPolicyReport mergePolicyReports(
            DomainFederationRetrievalPolicyReport contextPolicy,
            DomainFederationRetrievalPolicyReport relationshipPolicy) {
        List<String> decisions = new ArrayList<>();
        decisions.addAll(contextPolicy.decisions());
        decisions.addAll(relationshipPolicy.decisions());
        return new DomainFederationRetrievalPolicyReport(
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
