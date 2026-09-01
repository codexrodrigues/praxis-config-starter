package org.praxisplatform.config.ai.authoring;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@ConditionalOnBean(DomainKnowledgeConceptRepository.class)
public class RepositoryBackedProjectKnowledgeCandidateRetriever
        implements AgenticAuthoringProjectKnowledgeCandidateRetriever {

    private static final int CANDIDATE_OVERSAMPLE_FACTOR = 4;
    private static final int MAX_CANDIDATE_POOL_SIZE = 64;

    private final DomainKnowledgeConceptRepository conceptRepository;

    @Override
    public List<DomainKnowledgeConcept> retrieve(AgenticAuthoringProjectKnowledgeQuery query) {
        if (query == null || isSemanticOnlyQuery(query)) {
            return List.of();
        }
        return conceptRepository.findGovernedProjectKnowledgeCandidates(
                query.tenantId(),
                query.environment(),
                query.contextKey(),
                query.resourceKey(),
                query.nodeType(),
                PageRequest.of(0, candidatePoolSize(query.limit())));
    }

    private boolean isSemanticOnlyQuery(AgenticAuthoringProjectKnowledgeQuery query) {
        return StringUtils.hasText(query.semanticQuery())
                && !StringUtils.hasText(query.contextKey())
                && !StringUtils.hasText(query.resourceKey());
    }

    private int candidatePoolSize(int requestedLimit) {
        return Math.min(
                MAX_CANDIDATE_POOL_SIZE,
                Math.max(requestedLimit, 1) * CANDIDATE_OVERSAMPLE_FACTOR);
    }
}
