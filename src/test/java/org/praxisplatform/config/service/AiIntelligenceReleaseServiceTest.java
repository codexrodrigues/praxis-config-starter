package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiIntelligenceRelease;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.AiIntelligenceReleaseRepository;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AiIntelligenceReleaseServiceTest {
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    @Mock AiIntelligenceReleaseRepository repository;
    @Mock RagVectorStoreService ragVectorStoreService;

    @Test
    void stagesObservesAndActivatesOnlyReconciledRelease() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AiIntelligenceReleaseService service = new AiIntelligenceReleaseService(repository, ragVectorStoreService);
        AiIntelligenceRelease staged = service.stage(" tenant ", " prod ",
                new AiIntelligenceReleaseRequest("release-1", 105, HASH_A, 165, HASH_B, 2409,
                        "rag-v1__openai__text-embedding-3-large__768", "commit-1"));
        assertThat(staged.getStatus()).isEqualTo("STAGING");
        assertThat(staged.getTenantId()).isEqualTo("tenant");

        when(repository.findByTenantIdAndEnvironmentAndReleaseId("tenant", "prod", "release-1"))
                .thenReturn(Optional.of(staged));
        service.observeComponents("tenant", "prod", "release-1", 105, HASH_A, 2409, "corpus-v1");
        service.observeTemplates("tenant", "prod", "release-1", 165, HASH_B);
        AiIntelligenceRelease active = service.activate("tenant", "prod", "release-1");
        assertThat(active.getStatus()).isEqualTo("ACTIVE");
        assertThat(active.getActivatedAt()).isNotNull();
    }

    @Test
    void activationFailsClosedOnAnyMismatch() {
        AiIntelligenceRelease release = AiIntelligenceRelease.builder()
                .tenantId("global").environment("global").releaseId("release-2").status("STAGING")
                .expectedComponentCount(2).expectedComponentHash(HASH_A)
                .expectedTemplateCount(3).expectedTemplateHash(HASH_B).expectedChunkCount(5)
                .observedComponentCount(2).observedComponentHash(HASH_A)
                .observedTemplateCount(2).observedTemplateHash(HASH_B).observedChunkCount(5L)
                .embeddingProfile("profile").build();
        when(repository.findByTenantIdAndEnvironmentAndReleaseId("global", "global", "release-2"))
                .thenReturn(Optional.of(release));
        AiIntelligenceReleaseService service = new AiIntelligenceReleaseService(repository, ragVectorStoreService);
        assertThatThrownBy(() -> service.activate(null, null, "release-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("template-count");
        verify(repository, never()).save(any());
    }

    @Test
    void activationSupersedesPreviousActiveRelease() {
        AiIntelligenceRelease previous = AiIntelligenceRelease.builder()
                .tenantId("global").environment("prod").releaseId("old").status("ACTIVE").build();
        AiIntelligenceRelease next = AiIntelligenceRelease.builder()
                .tenantId("global").environment("prod").releaseId("new").status("STAGING")
                .expectedComponentCount(0).expectedComponentHash(HASH_A)
                .expectedTemplateCount(0).expectedTemplateHash(HASH_B).expectedChunkCount(0)
                .observedComponentCount(0).observedComponentHash(HASH_A)
                .observedTemplateCount(0).observedTemplateHash(HASH_B).observedChunkCount(0L)
                .embeddingProfile("profile").build();
        when(repository.findByTenantIdAndEnvironmentAndReleaseId("global", "prod", "new"))
                .thenReturn(Optional.of(next));
        when(repository.findByTenantIdAndEnvironmentAndStatus("global", "prod", "ACTIVE"))
                .thenReturn(Optional.of(previous));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AiIntelligenceReleaseService service = new AiIntelligenceReleaseService(repository, ragVectorStoreService);
        service.activate(null, "prod", "new");
        assertThat(previous.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(next.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void cleanupPlanIsReadOnlyAndRestrictedToTheActiveRelease() {
        AiIntelligenceRelease active = AiIntelligenceRelease.builder()
                .tenantId("tenant").environment("prod").releaseId("release-3").status("ACTIVE")
                .componentCorpusReleaseId("corpus-v3")
                .build();
        var expected = new RagVectorStoreService.SupersededReleaseCleanupPlan(
                "tenant", "prod", "corpus-v3", RagResourceTypes.COMPONENT_DEFINITION, 12,
                java.util.List.of(new RagVectorStoreService.SupersededReleaseDocuments("release-2", 12)));
        when(repository.findByTenantIdAndEnvironmentAndReleaseId("tenant", "prod", "release-3"))
                .thenReturn(Optional.of(active));
        when(ragVectorStoreService.planDocumentsByResourceTypeExceptRelease(
                "tenant", "prod", "corpus-v3", RagResourceTypes.COMPONENT_DEFINITION))
                .thenReturn(expected);

        AiIntelligenceReleaseService service = new AiIntelligenceReleaseService(repository, ragVectorStoreService);

        assertThat(service.cleanupPlan("tenant", "prod", "release-3")).isSameAs(expected);
        verify(ragVectorStoreService, never()).deleteDocumentsByResourceTypeExceptRelease(any(), any(), any(), any());
    }

    @Test
    void cleanupPlanFailsClosedForNonActiveRelease() {
        AiIntelligenceRelease staging = AiIntelligenceRelease.builder()
                .tenantId("tenant").environment("prod").releaseId("release-4").status("STAGING")
                .build();
        when(repository.findByTenantIdAndEnvironmentAndReleaseId("tenant", "prod", "release-4"))
                .thenReturn(Optional.of(staging));
        AiIntelligenceReleaseService service = new AiIntelligenceReleaseService(repository, ragVectorStoreService);

        assertThatThrownBy(() -> service.cleanupPlan("tenant", "prod", "release-4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Release is not ACTIVE");
        verifyNoInteractions(ragVectorStoreService);
    }

    @Test
    void cleanupPlanFailsClosedWhenActiveReleasePredatesCorpusIdentityObservation() {
        AiIntelligenceRelease active = AiIntelligenceRelease.builder()
                .tenantId("tenant").environment("prod").releaseId("legacy-active").status("ACTIVE")
                .build();
        when(repository.findByTenantIdAndEnvironmentAndReleaseId("tenant", "prod", "legacy-active"))
                .thenReturn(Optional.of(active));
        AiIntelligenceReleaseService service = new AiIntelligenceReleaseService(repository, ragVectorStoreService);

        assertThatThrownBy(() -> service.cleanupPlan("tenant", "prod", "legacy-active"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no observed component corpus release id");
        verifyNoInteractions(ragVectorStoreService);
    }
}
