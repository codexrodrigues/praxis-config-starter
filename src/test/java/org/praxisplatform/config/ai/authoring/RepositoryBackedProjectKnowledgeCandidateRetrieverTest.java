package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.springframework.data.domain.Pageable;

@Tag("unit")
class RepositoryBackedProjectKnowledgeCandidateRetrieverTest {

    @Test
    void retrievesBoundedCandidatePoolBeforeTheOwningServiceAppliesFinalLimit() {
        DomainKnowledgeConceptRepository repository = mock(DomainKnowledgeConceptRepository.class);
        RepositoryBackedProjectKnowledgeCandidateRetriever retriever =
                new RepositoryBackedProjectKnowledgeCandidateRetriever(repository);
        AgenticAuthoringProjectKnowledgeQuery query = new AgenticAuthoringProjectKnowledgeQuery(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                List.of("project_preference"),
                null,
                3);

        retriever.retrieve(query);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findGovernedProjectKnowledgeCandidates(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources"),
                eq("human-resources.funcionarios"),
                eq(null),
                pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(12);
    }
}
