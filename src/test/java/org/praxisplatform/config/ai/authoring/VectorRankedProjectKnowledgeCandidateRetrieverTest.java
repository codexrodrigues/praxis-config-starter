package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.data.domain.Pageable;

@Tag("unit")
class VectorRankedProjectKnowledgeCandidateRetrieverTest {

    private final RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
    private final DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
    private final VectorRankedProjectKnowledgeCandidateRetriever retriever =
            new VectorRankedProjectKnowledgeCandidateRetriever(ragVectorStoreService, conceptRepository);

    @Test
    void returnsCanonicalConceptsInVectorRankOrder() {
        AgenticAuthoringProjectKnowledgeQuery query = query(2);
        Document secondHit = document("knowledge:second");
        Document firstHit = document("knowledge:first");
        DomainKnowledgeConcept first = concept("knowledge:first");
        DomainKnowledgeConcept second = concept("knowledge:second");
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.search(eq("human-resources human-resources.funcionarios concept project_preference governance_constraint"), eq(6), any()))
                .thenReturn(List.of(secondHit, firstHit));
        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKeyIn(
                eq("tenant-a"),
                eq("dev"),
                eq(List.of("knowledge:second", "knowledge:first"))))
                .thenReturn(List.of(first, second));

        List<DomainKnowledgeConcept> result = retriever.retrieve(query);

        assertThat(result)
                .extracting(DomainKnowledgeConcept::getConceptKey)
                .containsExactly("knowledge:second", "knowledge:first");
        ArgumentCaptor<Filter.Expression> filterCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(ragVectorStoreService).search(
                eq("human-resources human-resources.funcionarios concept project_preference governance_constraint"),
                eq(6),
                filterCaptor.capture());
        assertThat(filterCaptor.getValue().toString())
                .contains(RagResourceTypes.PROJECT_KNOWLEDGE)
                .contains("tenant-a")
                .contains("dev")
                .contains("active");
    }

    @Test
    void fallsBackToRepositoryWhenVectorStoreIsUnavailable() {
        AgenticAuthoringProjectKnowledgeQuery query = query(5);
        DomainKnowledgeConcept concept = concept("knowledge:fallback");
        when(ragVectorStoreService.isAvailable()).thenReturn(false);
        when(conceptRepository.findGovernedProjectKnowledgeCandidates(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources"),
                eq("human-resources.funcionarios"),
                eq("concept"),
                any(Pageable.class)))
                .thenReturn(List.of(concept));

        List<DomainKnowledgeConcept> result = retriever.retrieve(query);

        assertThat(result).containsExactly(concept);
    }

    @Test
    void ignoresVectorHitsWithoutCanonicalConceptRows() {
        AgenticAuthoringProjectKnowledgeQuery query = query(5);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.search(any(), any(Integer.class), any()))
                .thenReturn(List.of(document("knowledge:missing")));
        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKeyIn(
                eq("tenant-a"),
                eq("dev"),
                eq(List.of("knowledge:missing"))))
                .thenReturn(List.of());

        List<DomainKnowledgeConcept> result = retriever.retrieve(query);

        assertThat(result).isEmpty();
    }

    private AgenticAuthoringProjectKnowledgeQuery query(int limit) {
        return new AgenticAuthoringProjectKnowledgeQuery(
                "tenant-a",
                "dev",
                "human-resources",
                "human-resources.funcionarios",
                List.of("project_preference", "governance_constraint"),
                "concept",
                limit);
    }

    private Document document(String conceptKey) {
        return Document.builder()
                .id("doc:" + conceptKey)
                .text("Project Knowledge " + conceptKey)
                .metadata(Map.of(
                        RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE,
                        RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_KEY, conceptKey,
                        RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_STATUS, "active",
                        RagMetadataKeys.TENANT_ID, "tenant-a",
                        RagMetadataKeys.ENVIRONMENT, "dev"))
                .build();
    }

    private DomainKnowledgeConcept concept(String conceptKey) {
        return DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .conceptKey(conceptKey)
                .contextKey("human-resources")
                .resourceKey("human-resources.funcionarios")
                .nodeType("concept")
                .lifecycle("active")
                .curationStatus("approved")
                .aiVisibility("allow")
                .payload("{\"kind\":\"project_preference\",\"summary\":\"Safe\"}")
                .build();
    }
}
