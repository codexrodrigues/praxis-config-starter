package org.praxisplatform.config.ai.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.rag.RagFilters;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnBean(DomainKnowledgeConceptRepository.class)
@ConditionalOnProperty(
        prefix = "praxis.project-knowledge.rag-retrieval",
        name = "enabled",
        havingValue = "true")
public class VectorRankedProjectKnowledgeCandidateRetriever
        implements AgenticAuthoringProjectKnowledgeCandidateRetriever {

    private static final int VECTOR_OVERSAMPLE_FACTOR = 3;

    private final RagVectorStoreService ragVectorStoreService;
    private final DomainKnowledgeConceptRepository conceptRepository;

    @Override
    public List<DomainKnowledgeConcept> retrieve(AgenticAuthoringProjectKnowledgeQuery query) {
        if (query == null || !ragVectorStoreService.isAvailable()) {
            return repositoryFallback(query);
        }
        String searchText = searchText(query);
        if (!StringUtils.hasText(searchText)) {
            return repositoryFallback(query);
        }
        List<Document> documents = ragVectorStoreService.search(
                searchText,
                Math.max(query.limit(), 1) * VECTOR_OVERSAMPLE_FACTOR,
                filter(query));
        if (documents.isEmpty()) {
            return List.of();
        }
        List<String> conceptKeys = conceptKeys(documents);
        if (conceptKeys.isEmpty()) {
            return List.of();
        }
        List<DomainKnowledgeConcept> concepts = conceptRepository.findByTenantIdAndEnvironmentAndConceptKeyIn(
                query.tenantId(),
                query.environment(),
                conceptKeys);
        return rankedConcepts(conceptKeys, concepts, query.limit());
    }

    private List<DomainKnowledgeConcept> repositoryFallback(AgenticAuthoringProjectKnowledgeQuery query) {
        if (query == null) {
            return List.of();
        }
        return conceptRepository.findGovernedProjectKnowledgeCandidates(
                query.tenantId(),
                query.environment(),
                query.contextKey(),
                query.resourceKey(),
                query.nodeType(),
                PageRequest.of(0, query.limit()));
    }

    private org.springframework.ai.vectorstore.filter.Filter.Expression filter(
            AgenticAuthoringProjectKnowledgeQuery query) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = builder.eq(
                RagMetadataKeys.RESOURCE_TYPE,
                RagResourceTypes.PROJECT_KNOWLEDGE);
        FilterExpressionBuilder.Op tenantEnv = RagFilters.buildTenantEnvironmentFilter(
                builder,
                query.tenantId(),
                query.environment());
        if (tenantEnv != null) {
            filter = builder.and(filter, tenantEnv);
        }
        filter = builder.and(filter, builder.eq(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_STATUS, "active"));
        return filter.build();
    }

    private String searchText(AgenticAuthoringProjectKnowledgeQuery query) {
        List<String> tokens = new ArrayList<>();
        add(tokens, query.contextKey());
        add(tokens, query.resourceKey());
        add(tokens, query.nodeType());
        if (query.kinds() != null) {
            query.kinds().forEach(kind -> add(tokens, kind));
        }
        return String.join(" ", tokens);
    }

    private List<String> conceptKeys(List<Document> documents) {
        List<String> keys = new ArrayList<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document == null ? Map.of() : document.getMetadata();
            Object value = metadata.get(RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_KEY);
            if (value == null) {
                value = metadata.get(RagMetadataKeys.RESOURCE_ID);
            }
            String key = value == null ? null : String.valueOf(value).trim();
            if (StringUtils.hasText(key) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    private List<DomainKnowledgeConcept> rankedConcepts(
            List<String> rankedKeys,
            List<DomainKnowledgeConcept> concepts,
            int limit) {
        if (concepts == null || concepts.isEmpty()) {
            return List.of();
        }
        Map<String, DomainKnowledgeConcept> byKey = new LinkedHashMap<>();
        for (DomainKnowledgeConcept concept : concepts) {
            if (concept != null && StringUtils.hasText(concept.getConceptKey())) {
                byKey.putIfAbsent(concept.getConceptKey(), concept);
            }
        }
        List<DomainKnowledgeConcept> ranked = new ArrayList<>();
        for (String key : rankedKeys) {
            DomainKnowledgeConcept concept = byKey.get(key);
            if (concept != null) {
                ranked.add(concept);
            }
            if (ranked.size() >= limit) {
                break;
            }
        }
        return List.copyOf(ranked);
    }

    private void add(List<String> tokens, String value) {
        if (StringUtils.hasText(value)) {
            tokens.add(value.trim());
        }
    }
}
