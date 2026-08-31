package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainCatalogReleaseChangedEvent;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainCatalogItemRepository;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class DomainCatalogIngestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesScopedProjectionInvalidationAfterCatalogPersistence() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository, itemRepository, objectMapper, ragVectorStoreService, validationService(),
                (DomainKnowledgeProjectionService) null, false, false, 100, eventPublisher);
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingest(sampleCatalog(), "tenant-a", "dev");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOfSatisfying(
                DomainCatalogReleaseChangedEvent.class,
                event -> assertThat(event)
                        .extracting(
                                DomainCatalogReleaseChangedEvent::tenantId,
                                DomainCatalogReleaseChangedEvent::environment,
                                DomainCatalogReleaseChangedEvent::resourceKey,
                                DomainCatalogReleaseChangedEvent::releaseKey)
                        .containsExactly(
                                "tenant-a", "dev", "human-resources.folhas-pagamento",
                                "praxis-api-quickstart:test"));
    }

    @Test
    void ingestsDomainCatalogReleaseAndMaterializesSearchableItems() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        assertThat(response.itemCount()).isEqualTo(13);

        ArgumentCaptor<DomainCatalogRelease> releaseCaptor = ArgumentCaptor.forClass(DomainCatalogRelease.class);
        verify(releaseRepository).save(releaseCaptor.capture());
        assertThat(releaseCaptor.getValue())
                .satisfies(release -> {
                    assertThat(release.getSchemaVersion()).isEqualTo("praxis.domain-catalog/v0.2");
                    assertThat(release.getServiceKey()).isEqualTo("praxis-api-quickstart");
                    assertThat(release.getResourceKey()).isEqualTo("human-resources.folhas-pagamento");
                    assertThat(release.getTenantId()).isEqualTo("tenant-a");
                    assertThat(release.getEnvironment()).isEqualTo("dev");
                    assertThat(release.getRawPayload()).contains("Folha de pagamento");
                });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainCatalogItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(itemsCaptor.capture());
        List<DomainCatalogItem> items = itemsCaptor.getValue();

        assertThat(items).extracting(DomainCatalogItem::getItemType)
                .contains("context", "node", "edge", "binding", "alias", "evidence", "governance");
        assertThat(items).filteredOn(item -> "node".equals(item.getItemType()))
                .extracting(DomainCatalogItem::getNodeType)
                .contains("concept", "field", "policy_hint");
        assertThat(items).filteredOn(item -> "human-resources.folhas-pagamento.field.valor-liquido".equals(item.getItemKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getContextKey()).isEqualTo("human-resources");
                    assertThat(item.getSearchableText()).contains("Valor liquido");
                    assertThat(item.getPayload()).contains("\"fieldName\":\"valorLiquido\"");
                });
        assertThat(items).filteredOn(item -> "human-resources.folhas-pagamento.policy.supplier.selection".equals(item.getItemKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getNodeType()).isEqualTo("policy_hint");
                    assertThat(item.getSearchableText()).contains("Supplier selecionavel");
                    assertThat(item.getPayload()).contains("ACTIVE", "BLOCKED");
                });
        assertThat(items).filteredOn(item -> "human-resources.folhas-pagamento.stats.group-by".equals(item.getItemKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getNodeType()).isEqualTo("stats");
                    assertThat(item.getSearchableText()).contains("Group By", "stats.groupBy");
                    assertThat(item.getPayload()).contains("/api/human-resources/folhas-pagamento/stats/group-by");
                });
        assertThat(items).filteredOn(item -> "governance:human-resources.folhas-pagamento.field.valor-liquido:privacy".equals(item.getItemKey()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getItemType()).isEqualTo("governance");
                    assertThat(item.getSearchableText())
                            .contains("confidential", "financial", "LGPD", "INTERNAL_POLICY", "mask", "deny");
                    assertThat(item.getPayload()).contains("\"classification\":\"confidential\"");
                });
    }

    @Test
    void skipsDomainCatalogReingestionWhenSourceHashAlreadyExists() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        DomainCatalogRelease existingRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:test")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .resourceKey("human-resources.folhas-pagamento")
                .sourceHash("sha256:test")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.of(existingRelease));
        when(itemRepository.countByRelease(existingRelease)).thenReturn(13L);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        assertThat(response.itemCount()).isEqualTo(13);
        verify(releaseRepository, never()).save(any(DomainCatalogRelease.class));
    }

    @Test
    void reconcilesPartialRagCorpusWhenAnExistingReleaseIsReingested() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService());
        DomainCatalogRelease existingRelease = existingRelease();
        DomainCatalogItem existingItem = existingIndexableItem(existingRelease);
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.of(existingRelease));
        when(itemRepository.countByRelease(existingRelease)).thenReturn(1L);
        when(itemRepository.findByRelease(existingRelease)).thenReturn(List.of(existingItem));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.corpusReleaseStatus(
                "tenant-a",
                "dev",
                "praxis-api-quickstart:test",
                RagResourceTypes.DOMAIN_CATALOG,
                1L))
                .thenReturn(ragStatus(false, 0L, 1L));

        service.ingest(sampleCatalog(), "tenant-a", "dev");

        verify(ragVectorStoreService).upsertDocuments(any());
        verify(ragVectorStoreService).deleteDocumentsByCanonicalScopeExceptRelease(
                eq("tenant-a"),
                eq("dev"),
                eq("praxis-api-quickstart"),
                eq("human-resources.folhas-pagamento"),
                eq("praxis-api-quickstart_test"),
                eq(RagResourceTypes.DOMAIN_CATALOG));
    }

    @Test
    void skipsRagRepublishWhenAnExistingReleaseIsAlreadyReconciled() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService());
        DomainCatalogRelease existingRelease = existingRelease();
        DomainCatalogItem existingItem = existingIndexableItem(existingRelease);
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.of(existingRelease));
        when(itemRepository.countByRelease(existingRelease)).thenReturn(1L);
        when(itemRepository.findByRelease(existingRelease)).thenReturn(List.of(existingItem));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.corpusReleaseStatus(
                "tenant-a",
                "dev",
                "praxis-api-quickstart:test",
                RagResourceTypes.DOMAIN_CATALOG,
                1L))
                .thenReturn(ragStatus(true, 1L, 1L));

        service.ingest(sampleCatalog(), "tenant-a", "dev");

        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void retriesRagBatchAfterTransientFailureWithoutWaitingForReingestion() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                (DomainKnowledgeProjectionService) null,
                true,
                false,
                100,
                3,
                0L,
                event -> { });
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        doThrow(AiProviderCallException.transport(
                        "gemini", new java.net.ConnectException("embedding endpoint unavailable")))
                .doNothing()
                .when(ragVectorStoreService)
                .upsertDocuments(any());

        service.ingest(sampleCatalog(), "tenant-a", "dev");

        verify(ragVectorStoreService, times(2)).upsertDocuments(any());
        verify(ragVectorStoreService, times(1)).deleteDocumentsByCanonicalScopeExceptRelease(
                eq("tenant-a"),
                eq("dev"),
                eq("praxis-api-quickstart"),
                eq("human-resources.folhas-pagamento"),
                eq("praxis-api-quickstart_test"),
                eq(RagResourceTypes.DOMAIN_CATALOG));
    }

    @Test
    void doesNotRetryRagBatchAfterProviderQuotaIsExhausted() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                (DomainKnowledgeProjectionService) null,
                true,
                false,
                100,
                3,
                0L,
                event -> { });
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        doThrow(AiProviderCallException.fromHttpStatus(
                        "gemini", 429, "You exceeded your current quota."))
                .when(ragVectorStoreService)
                .upsertDocuments(any());

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        verify(ragVectorStoreService, times(1)).upsertDocuments(any());
        verify(ragVectorStoreService, never()).deleteDocumentsByCanonicalScopeExceptRelease(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void reconcilesDerivedKnowledgeProjectionWhenAnExistingReleaseIsReingested() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainKnowledgeProjectionService projectionService = mock(DomainKnowledgeProjectionService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                projectionService,
                false,
                false,
                100,
                eventPublisher);
        DomainCatalogRelease existingRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:test")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .resourceKey("human-resources.folhas-pagamento")
                .sourceHash("sha256:test")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
        DomainCatalogItem existingItem = DomainCatalogItem.builder()
                .release(existingRelease)
                .itemType("node")
                .itemKey("human-resources.folhas-pagamento")
                .payload("{\"nodeKey\":\"human-resources.folhas-pagamento\"}")
                .build();
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.of(existingRelease));
        when(itemRepository.countByRelease(existingRelease)).thenReturn(1L);
        when(itemRepository.findByRelease(existingRelease)).thenReturn(List.of(existingItem));

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        assertThat(response.itemCount()).isEqualTo(1);
        verify(projectionService).project(existingRelease, List.of(existingItem));
        verify(eventPublisher).publishEvent(any(DomainCatalogReleaseChangedEvent.class));
        verify(itemRepository, never()).deleteByRelease(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void ingestsTheSameContentAddressedReleaseKeyIndependentlyAcrossScopes() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-b", "prod"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        service.ingest(sampleCatalog(), "tenant-a", "dev");
        service.ingest(sampleCatalog(), "tenant-b", "prod");

        ArgumentCaptor<DomainCatalogRelease> releases = ArgumentCaptor.forClass(DomainCatalogRelease.class);
        verify(releaseRepository, times(2)).save(releases.capture());
        assertThat(releases.getAllValues())
                .extracting(DomainCatalogRelease::getTenantId, DomainCatalogRelease::getEnvironment)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("tenant-a", "dev"),
                        org.assertj.core.groups.Tuple.tuple("tenant-b", "prod"));
    }

    @Test
    void rejectsDifferentImmutableContentForTheSameScopedReleaseKey() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        DomainCatalogRelease existingRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:test")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .sourceHash("sha256:different")
                .tenantId("tenant-a")
                .environment("dev")
                .rawPayload("{}")
                .build();
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.of(existingRelease));

        assertThatThrownBy(() -> service.ingest(sampleCatalog(), "tenant-a", "dev"))
                .isInstanceOf(org.praxisplatform.config.exception.ConfigurationIngestionException.class)
                .hasMessageContaining("different immutable content")
                .hasMessageContaining("praxis-api-quickstart:test");
        verify(releaseRepository, never()).save(any());
        verify(itemRepository, never()).deleteByRelease(any());
        verify(itemRepository, never()).saveAll(any());
    }

    @Test
    void searchesItemsOnlyInsideTheRequestedReleaseScope() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        DomainCatalogRelease release = DomainCatalogRelease.builder()
                .releaseKey("shared-release")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
        when(releaseRepository.findByReleaseKeyAndScope("shared-release", "tenant-a", "dev"))
                .thenReturn(Optional.of(release));
        when(itemRepository.search(eq(release), eq("node"), eq(null), eq(null), eq("salary"), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.search("shared-release", "tenant-a", "dev", "node", null, null, "salary", 5))
                .isEmpty();

        verify(releaseRepository).findByReleaseKeyAndScope("shared-release", "tenant-a", "dev");
        verify(itemRepository).search(eq(release), eq("node"), eq(null), eq(null), eq("salary"), any(Pageable.class));
    }

    @Test
    void repairsMissingResourceKeyWhenAnIdempotentReleaseIsReingested() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        DomainCatalogRelease existingRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:test")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .sourceHash("sha256:test")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.of(existingRelease));
        when(releaseRepository.save(existingRelease)).thenReturn(existingRelease);
        when(itemRepository.countByRelease(existingRelease)).thenReturn(13L);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.itemCount()).isEqualTo(13);
        assertThat(existingRelease.getResourceKey()).isEqualTo("human-resources.folhas-pagamento");
        verify(releaseRepository).save(existingRelease);
        verify(itemRepository, never()).deleteByRelease(any(DomainCatalogRelease.class));
        verify(itemRepository, never()).saveAll(any());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void deduplicatesCatalogItemsByCanonicalTypeAndKeyBeforePersistence() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        JsonNode catalog = sampleCatalog().deepCopy();
        ((ArrayNode) catalog.path("contexts")).add(objectMapper.readTree("""
            {
              "contextKey": "human-resources",
              "label": "Recursos Humanos duplicado",
              "status": "active"
            }
            """));

        DomainCatalogIngestionResponse response = service.ingest(catalog, "tenant-a", "dev");

        assertThat(response.itemCount()).isEqualTo(13);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainCatalogItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue())
                .extracting(item -> item.getItemType() + "|" + item.getItemKey())
                .hasSize(13)
                .doesNotHaveDuplicates();
    }

    @Test
    void publishesDomainCatalogRagDocumentsInConfiguredBatches() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                true,
                false,
                4
        );

        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        service.ingest(sampleCatalog(), "tenant-a", "dev");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService, times(4)).upsertDocuments(documentsCaptor.capture());
        assertThat(documentsCaptor.getAllValues())
                .extracting(List::size)
                .containsExactly(4, 4, 4, 1);
        assertThat(documentsCaptor.getAllValues().stream().flatMap(List::stream).toList())
                .anySatisfy(document -> assertThat(document.getMetadata())
                        .containsEntry(RagMetadataKeys.RESOURCE_ID, "human-resources.folhas-pagamento.field.valor-liquido")
                        .containsEntry(RagMetadataKeys.RESOURCE_KEY, "human-resources.folhas-pagamento")
                        .containsEntry(RagMetadataKeys.SERVICE_KEY, "praxis-api-quickstart")
                        .containsEntry(RagMetadataKeys.RELEASE_ID, "praxis-api-quickstart_test")
                        .containsEntry(RagMetadataKeys.CONTEXT_KEY, "human-resources")
                        .containsEntry(RagMetadataKeys.NODE_TYPE, "field"));
    }

    @Test
    void retrievesLatestTenantScopedDomainContextSemanticallyInVectorRankOrder() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                true,
                false,
                100
        );
        String releaseKey = "praxis-service:human-resources.vw-analytics-afastamentos:sourcehash";
        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .releaseKey(releaseKey)
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .resourceKey("human-resources.vw-analytics-afastamentos")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
        DomainCatalogItem absence = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("node")
                .itemKey("human-resources.vw-analytics-afastamentos.concept")
                .contextKey("human-resources")
                .nodeType("concept")
                .payload("{\"nodeKey\":\"human-resources.vw-analytics-afastamentos.concept\",\"label\":\"Afastamentos\"}")
                .searchableText("afastamentos ausencias por departamento")
                .build();
        DomainCatalogItem department = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("node")
                .itemKey("human-resources.vw-analytics-afastamentos.field.departamento")
                .contextKey("human-resources")
                .nodeType("field")
                .payload("{\"nodeKey\":\"human-resources.vw-analytics-afastamentos.field.departamento\",\"label\":\"Departamento\"}")
                .searchableText("departamento agrupamento afastamentos")
                .build();
        String ragReleaseId = RagDocumentIdentity.resolveReleaseId(releaseKey, null, null);
        Document departmentDocument = domainCatalogDocument(
                ragReleaseId,
                department.getItemType(),
                department.getItemKey(),
                department.getSearchableText());
        Document absenceDocument = domainCatalogDocument(
                ragReleaseId,
                absence.getItemType(),
                absence.getItemKey(),
                absence.getSearchableText());

        when(releaseRepository.findLatest(
                eq("praxis-service"),
                eq("human-resources.vw-analytics-afastamentos"),
                eq("tenant-a"),
                eq("dev"),
                any(Pageable.class))).thenReturn(List.of(latestRelease));
        when(itemRepository.findByRelease(latestRelease)).thenReturn(List.of(absence, department));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(ragVectorStoreService.search(
                eq("compare afastamentos entre departamentos"),
                eq(2),
                any(Filter.Expression.class))).thenReturn(List.of(departmentDocument, absenceDocument));

        var context = service.contextLatestSemantic(
                "praxis-service",
                "human-resources.vw-analytics-afastamentos",
                "tenant-a",
                "dev",
                "node",
                null,
                null,
                "compare afastamentos entre departamentos",
                2);

        assertThat(context.release().releaseKey()).isEqualTo(releaseKey);
        assertThat(context.items())
                .extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly(department.getItemKey(), absence.getItemKey());
        verify(ragVectorStoreService).search(
                eq("compare afastamentos entre departamentos"),
                eq(2),
                any(Filter.Expression.class));
    }

    @Test
    void reportsDomainCatalogRagStatusForLatestRelease() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogRagPublicationStateService publicationStateService =
                mock(DomainCatalogRagPublicationStateService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                null,
                publicationStateService,
                true,
                false,
                100,
                3,
                0L,
                event -> { }
        );

        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .id(UUID.fromString("d070c524-b67a-4cc0-b754-d652d7424e14"))
                .releaseKey("praxis-service:human-resources.funcionarios:sourcehash")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogItem indexed = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("node")
                .itemKey("human-resources.funcionarios.field.cpf")
                .payload("{\"nodeKey\":\"human-resources.funcionarios.field.cpf\"}")
                .searchableText("node | cpf")
                .build();
        DomainCatalogItem denied = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("governance")
                .itemKey("governance:human-resources.funcionarios.field.cpf:privacy")
                .payload("""
                    {
                      "governanceKey": "governance:human-resources.funcionarios.field.cpf:privacy",
                      "aiUsage": {"visibility": "deny"}
                    }
                    """)
                .searchableText("governance | cpf")
                .build();
        DomainCatalogItem blank = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("alias")
                .itemKey("alias:cpf")
                .payload("{\"aliasKey\":\"alias:cpf\"}")
                .searchableText("")
                .build();
        RagVectorStoreService.RagCorpusReleaseStatus corpusStatus = new RagVectorStoreService.RagCorpusReleaseStatus(
                true,
                true,
                "tenant-a",
                "dev",
                latestRelease.getReleaseKey(),
                1,
                1,
                1,
                java.util.Map.of("summary", 1L),
                java.util.Map.of("allow", 1L),
                List.of(new RagVectorStoreService.SourceStatus(
                        "human-resources.funcionarios.field.cpf",
                        "node",
                        1,
                        List.of("summary"),
                        List.of("praxis.domain-catalog/v0.2"),
                        "2026-04-21T12:00:02Z")),
                "2026-04-21T12:00:02Z",
                List.of());

        when(releaseRepository.findLatest(eq("praxis-service"), eq("human-resources.funcionarios"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.findByRelease(latestRelease)).thenReturn(List.of(indexed, denied, blank));
        when(ragVectorStoreService.corpusReleaseStatus(
                eq("tenant-a"),
                eq("dev"),
                eq(latestRelease.getReleaseKey()),
                eq(RagResourceTypes.DOMAIN_CATALOG),
                eq(1L)))
                .thenReturn(corpusStatus);
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(publicationStateService.snapshot(latestRelease.getId())).thenReturn(Optional.of(
                new DomainCatalogRagPublicationStateService.StateSnapshot(
                        org.praxisplatform.config.domain.DomainCatalogRagPublicationStatus.PUBLISHED,
                        4L,
                        2,
                        1L,
                        1L,
                        null,
                        null,
                        null,
                        Instant.parse("2026-04-21T12:00:00Z"),
                        Instant.parse("2026-04-21T12:00:01Z"),
                        Instant.parse("2026-04-21T12:00:02Z"),
                        Instant.parse("2026-04-21T12:00:02Z"))));

        var response = service.ragStatus(
                "praxis-service",
                "human-resources.funcionarios",
                "tenant-a",
                "dev");

        assertThat(response.schemaVersion()).isEqualTo("praxis.domain-catalog-rag-status/v0.1");
        assertThat(response.release().releaseKey()).isEqualTo(latestRelease.getReleaseKey());
        assertThat(response.resourceType()).isEqualTo(RagResourceTypes.DOMAIN_CATALOG);
        assertThat(response.ragPublicationEnabled()).isTrue();
        assertThat(response.vectorStoreAvailable()).isTrue();
        assertThat(response.reconciled()).isTrue();
        assertThat(response.expectedDocumentCount()).isEqualTo(1);
        assertThat(response.actualDocumentCount()).isEqualTo(1);
        assertThat(response.publication().status()).isEqualTo("PUBLISHED");
        assertThat(response.publication().revision()).isEqualTo(4L);
        assertThat(response.publication().attempt()).isEqualTo(2);
        assertThat(response.publication().publishedDocumentCount()).isEqualTo(1);
        assertThat(response.sources()).singleElement()
                .satisfies(source -> {
                    assertThat(source.sourceId()).isEqualTo("human-resources.funcionarios.field.cpf");
                    assertThat(source.sourceKind()).isEqualTo("node");
                });
    }

    @Test
    void searchesLatestReleaseForRuntimeClients() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogItem salaryField = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("node")
                .itemKey("human-resources.folhas-pagamento.field.salario-liquido")
                .contextKey("human-resources")
                .nodeType("field")
                .payload("""
                    {"nodeKey":"human-resources.folhas-pagamento.field.salario-liquido","nodeType":"field","label":"Salario Liquido"}
                    """)
                .build();

        when(releaseRepository.findLatest(eq("praxis-api-quickstart"), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.search(
                eq(latestRelease),
                eq("node"),
                eq("human-resources"),
                eq("field"),
                eq("salario"),
                any(Pageable.class)))
                .thenReturn(List.of(salaryField));

        var responses = service.searchLatest(
                "praxis-api-quickstart",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "field",
                "salario",
                10);

        assertThat(responses).singleElement()
                .satisfies(response -> {
                    assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:latest");
                    assertThat(response.itemType()).isEqualTo("node");
                    assertThat(response.nodeType()).isEqualTo("field");
                    assertThat(response.payload().path("label").asText()).isEqualTo("Salario Liquido");
                });
    }

    @Test
    void federatesSearchAcrossLatestReleaseOfEachServiceWhenServiceKeyIsOmitted() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease hrLatest = DomainCatalogRelease.builder()
                .releaseKey("hr:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("hr-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogRelease financeLatest = DomainCatalogRelease.builder()
                .releaseKey("finance:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("finance-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T11:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T11:00:01Z"))
                .build();
        DomainCatalogRelease hrOlder = DomainCatalogRelease.builder()
                .releaseKey("hr:older")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("hr-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-20T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-20T12:00:01Z"))
                .build();
        DomainCatalogItem hrField = DomainCatalogItem.builder()
                .release(hrLatest)
                .itemType("node")
                .itemKey("human-resources.employee.field.name")
                .contextKey("human-resources")
                .nodeType("field")
                .payload("{\"label\":\"Employee name\"}")
                .build();
        DomainCatalogItem financeField = DomainCatalogItem.builder()
                .release(financeLatest)
                .itemType("node")
                .itemKey("finance.invoice.field.total")
                .contextKey("finance")
                .nodeType("field")
                .payload("{\"label\":\"Invoice total\"}")
                .build();

        when(releaseRepository.findLatest(eq(null), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest, financeLatest, hrOlder));
        when(itemRepository.searchAcrossReleases(
                eq(List.of(hrLatest, financeLatest)),
                eq("node"),
                eq(null),
                eq("field"),
                eq("field"),
                any(Pageable.class)))
                .thenReturn(List.of(hrField, financeField));

        var responses = service.searchLatest(
                null,
                "tenant-a",
                "dev",
                "node",
                null,
                "field",
                "field",
                10);

        assertThat(responses).extracting(DomainCatalogItemResponse::releaseKey)
                .containsExactly("hr:latest", "finance:latest");
        assertThat(responses).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("human-resources.employee.field.name", "finance.invoice.field.total");
    }

    @Test
    void buildsFederatedContextWhenServiceKeyIsOmitted() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease hrLatest = DomainCatalogRelease.builder()
                .releaseKey("hr:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("hr-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogItem hrPolicy = DomainCatalogItem.builder()
                .release(hrLatest)
                .itemType("node")
                .itemKey("human-resources.policy.salary-visibility")
                .contextKey("human-resources")
                .nodeType("policy_hint")
                .payload("{\"label\":\"Salary visibility\"}")
                .build();

        when(releaseRepository.findLatest(eq(null), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest));
        when(itemRepository.search(
                eq(hrLatest),
                eq("node"),
                eq("human-resources"),
                eq("policy_hint"),
                eq("salary"),
                any(Pageable.class)))
                .thenReturn(List.of(hrPolicy));

        var context = service.contextLatest(
                null,
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "policy_hint",
                "salary",
                5);

        assertThat(context.release()).isNull();
        assertThat(context.retrievalGuidance())
                .contains("This context may include items from multiple latest releases or services; keep boundaries explicit when citing or applying it.");
        assertThat(context.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.releaseKey()).isEqualTo("hr:latest");
                    assertThat(item.itemKey()).isEqualTo("human-resources.policy.salary-visibility");
                });
    }

    @Test
    void resolvesExplicitRelationshipsAcrossLatestReleases() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease hrLatest = DomainCatalogRelease.builder()
                .releaseKey("hr:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("hr-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogRelease financeLatest = DomainCatalogRelease.builder()
                .releaseKey("finance:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("finance-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T11:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T11:00:01Z"))
                .build();
        DomainCatalogItem crossServiceReference = DomainCatalogItem.builder()
                .release(hrLatest)
                .itemType("edge")
                .itemKey("edge:hr.employee.references.finance.cost-center")
                .edgeType("references")
                .payload("""
                    {
                      "edgeKey": "edge:hr.employee.references.finance.cost-center",
                      "sourceNodeKey": "human-resources.employee.field.costCenterId",
                      "targetNodeKey": "finance.cost-center",
                      "edgeType": "references"
                    }
                    """)
                .build();
        DomainCatalogItem sameAsEdge = DomainCatalogItem.builder()
                .release(financeLatest)
                .itemType("edge")
                .itemKey("edge:finance.cost-center.same-as.accounting.center")
                .edgeType("same_as")
                .payload("""
                    {
                      "edgeKey": "edge:finance.cost-center.same-as.accounting.center",
                      "sourceNodeKey": "finance.cost-center",
                      "targetNodeKey": "accounting.cost-center",
                      "edgeType": "same_as"
                    }
                    """)
                .build();

        when(releaseRepository.findLatest(eq(null), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest, financeLatest));
        when(itemRepository.searchAcrossReleases(
                eq(List.of(hrLatest, financeLatest)),
                eq("edge"),
                eq(null),
                eq(null),
                eq(null),
                any(Pageable.class)))
                .thenReturn(List.of(crossServiceReference, sameAsEdge));

        var responses = service.relationshipsLatest(
                null,
                "tenant-a",
                "dev",
                "human-resources.employee.field.costCenterId",
                "finance.cost-center",
                "references",
                null,
                10);

        assertThat(responses).singleElement()
                .satisfies(edge -> {
                    assertThat(edge.releaseKey()).isEqualTo("hr:latest");
                    assertThat(edge.edgeType()).isEqualTo("references");
                    assertThat(edge.payload().path("sourceNodeKey").asText())
                            .isEqualTo("human-resources.employee.field.costCenterId");
                    assertThat(edge.payload().path("targetNodeKey").asText()).isEqualTo("finance.cost-center");
                });
    }

    @Test
    void resolvesRelationshipsForSingleServiceWhenServiceKeyIsProvided() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease hrLatest = DomainCatalogRelease.builder()
                .releaseKey("hr:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("hr-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogItem governedByEdge = DomainCatalogItem.builder()
                .release(hrLatest)
                .itemType("edge")
                .itemKey("edge:hr.salary.governed-by.policy")
                .edgeType("governed_by")
                .payload("""
                    {
                      "edgeKey": "edge:hr.salary.governed-by.policy",
                      "sourceNodeKey": "human-resources.salary",
                      "targetNodeKey": "human-resources.policy.salary-visibility",
                      "edgeType": "governed_by"
                    }
                    """)
                .build();

        when(releaseRepository.findLatest(eq("hr-service"), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest));
        when(itemRepository.search(eq(hrLatest), eq("edge"), eq(null), eq(null), eq("salary"), any(Pageable.class)))
                .thenReturn(List.of(governedByEdge));

        var responses = service.relationshipsLatest(
                "hr-service",
                "tenant-a",
                "dev",
                "human-resources.salary",
                null,
                "governed_by",
                "salary",
                10);

        assertThat(responses).singleElement()
                .satisfies(edge -> {
                    assertThat(edge.releaseKey()).isEqualTo("hr:latest");
                    assertThat(edge.edgeType()).isEqualTo("governed_by");
                    assertThat(edge.payload().path("targetNodeKey").asText())
                            .isEqualTo("human-resources.policy.salary-visibility");
                });
    }

    @Test
    void buildsLlmReadyContextFromLatestRelease() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogItem policyHint = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("node")
                .itemKey("human-resources.folhas-pagamento.policy.payment")
                .contextKey("human-resources")
                .nodeType("policy_hint")
                .payload("""
                    {"nodeKey":"human-resources.folhas-pagamento.policy.payment","nodeType":"policy_hint","label":"Pagamento permitido"}
                    """)
                .build();

        when(releaseRepository.findLatest(eq("praxis-api-quickstart"), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.search(
                eq(latestRelease),
                eq("node"),
                eq("human-resources"),
                eq("policy_hint"),
                eq("pagamento"),
                any(Pageable.class)))
                .thenReturn(List.of(policyHint));

        var context = service.contextLatest(
                "praxis-api-quickstart",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "policy_hint",
                "pagamento",
                5);

        assertThat(context.schemaVersion()).isEqualTo("praxis.domain-catalog-context/v0.1");
        assertThat(context.release().releaseKey()).isEqualTo("praxis-api-quickstart:latest");
        assertThat(context.retrievalGuidance()).contains("Use binding and evidence items to cite runtime/API/schema sources.");
        assertThat(context.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.itemType()).isEqualTo("node");
                    assertThat(item.nodeType()).isEqualTo("policy_hint");
                    assertThat(item.payload().path("label").asText()).isEqualTo("Pagamento permitido");
                });
    }

    @Test
    void contextLatestSelectsLatestReleaseForRequestedResourceKey() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease operationsRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-service:operations.missoes:2026-04-22T11:02:22Z")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-22T11:02:22Z"))
                .createdAt(Instant.parse("2026-04-22T11:02:23Z"))
                .build();
        DomainCatalogRelease funcionariosRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-service:human-resources.funcionarios:2026-04-22T11:01:23Z")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-22T11:01:23Z"))
                .createdAt(Instant.parse("2026-04-22T11:01:24Z"))
                .build();
        DomainCatalogItem cpfGovernance = DomainCatalogItem.builder()
                .release(funcionariosRelease)
                .itemType("governance")
                .itemKey("governance:human-resources.funcionarios.field.cpf:privacy")
                .payload("""
                    {
                      "governanceKey": "governance:human-resources.funcionarios.field.cpf:privacy",
                      "nodeKey": "human-resources.funcionarios.field.cpf",
                      "annotationType": "privacy",
                      "classification": "confidential",
                      "dataCategory": "personal",
                      "complianceTags": ["LGPD"],
                      "aiUsage": {
                        "visibility": "mask",
                        "trainingUse": "deny",
                        "reasoningUse": "review_required"
                      }
                    }
                    """)
                .build();

        when(releaseRepository.findLatest(eq("praxis-service"), eq("human-resources.funcionarios"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(funcionariosRelease));
        when(itemRepository.search(
                eq(funcionariosRelease),
                eq("governance"),
                eq(null),
                eq(null),
                eq("cpf"),
                any(Pageable.class)))
                .thenReturn(List.of(cpfGovernance));

        var context = service.contextLatest(
                "praxis-service",
                "human-resources.funcionarios",
                "tenant-a",
                "dev",
                "governance",
                null,
                null,
                "cpf",
                5);

        assertThat(context.release().releaseKey())
                .isEqualTo("praxis-service:human-resources.funcionarios:2026-04-22T11:01:23Z");
        assertThat(context.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.itemKey()).isEqualTo("governance:human-resources.funcionarios.field.cpf:privacy");
                    assertThat(item.payload().path("payloadMode").asText()).isEqualTo("governed-summary");
                });
    }

    @Test
    void contextLatestWithServiceScopeSearchesLatestStructuredResourceReleases() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease folhaRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-service:human-resources.folhas-pagamento:2026-04-22T11:00:00Z")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-22T11:00:00Z"))
                .createdAt(Instant.parse("2026-04-22T11:00:01Z"))
                .build();
        DomainCatalogRelease funcionariosRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-service:human-resources.funcionarios:2026-04-22T11:01:00Z")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-22T11:01:00Z"))
                .createdAt(Instant.parse("2026-04-22T11:01:01Z"))
                .build();
        DomainCatalogRelease cargosRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-service:human-resources.cargos:2026-04-22T11:02:00Z")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-service")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-22T11:02:00Z"))
                .createdAt(Instant.parse("2026-04-22T11:02:01Z"))
                .build();
        DomainCatalogItem folhaNode = nodeItem(folhaRelease, "human-resources.folhas-pagamento", "Folha de pagamento");
        DomainCatalogItem funcionariosNode = nodeItem(funcionariosRelease, "human-resources.funcionarios", "Funcionarios");
        DomainCatalogItem cargosNode = nodeItem(cargosRelease, "human-resources.cargos", "Cargos");

        when(releaseRepository.findLatest(eq("praxis-service"), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(folhaRelease, funcionariosRelease, cargosRelease));
        when(itemRepository.searchAcrossReleases(
                eq(List.of(folhaRelease, funcionariosRelease, cargosRelease)),
                eq("node"),
                eq(null),
                eq(null),
                eq("pessoas"),
                any(Pageable.class)))
                .thenReturn(List.of(folhaNode, funcionariosNode, cargosNode));

        var context = service.contextLatest(
                "praxis-service",
                null,
                "tenant-a",
                "dev",
                "node",
                null,
                null,
                "pessoas",
                10);

        assertThat(context.release()).isNull();
        assertThat(context.retrievalGuidance()).contains("This context may include items from multiple latest releases or services; keep boundaries explicit when citing or applying it.");
        assertThat(context.items())
                .extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly(
                        "human-resources.folhas-pagamento.concept",
                        "human-resources.funcionarios.concept",
                        "human-resources.cargos.concept");
    }

    @Test
    void contextLatestAppliesAiVisibilityBeforeReturningLlmContext() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:latest")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .tenantId("tenant-a")
                .environment("dev")
                .generatedAt(Instant.parse("2026-04-21T12:00:00Z"))
                .createdAt(Instant.parse("2026-04-21T12:00:01Z"))
                .build();
        DomainCatalogItem masked = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("governance")
                .itemKey("governance:human-resources.funcionarios.field.cpf:privacy")
                .payload("""
                    {
                      "governanceKey": "governance:human-resources.funcionarios.field.cpf:privacy",
                      "nodeKey": "human-resources.funcionarios.field.cpf",
                      "annotationType": "privacy",
                      "classification": "confidential",
                      "dataCategory": "personal",
                      "complianceTags": ["LGPD"],
                      "retentionPolicy": "raw-values-never-for-prompts",
                      "aiUsage": {
                        "visibility": "mask",
                        "trainingUse": "deny",
                        "reasoningUse": "review_required"
                      },
                      "source": "manual-sensitive-review"
                    }
                    """)
                .build();
        DomainCatalogItem denied = DomainCatalogItem.builder()
                .release(latestRelease)
                .itemType("governance")
                .itemKey("governance:human-resources.funcionarios.field.private-token:privacy")
                .payload("""
                    {
                      "governanceKey": "governance:human-resources.funcionarios.field.private-token:privacy",
                      "nodeKey": "human-resources.funcionarios.field.private-token",
                      "annotationType": "privacy",
                      "classification": "restricted",
                      "dataCategory": "credential",
                      "aiUsage": {
                        "visibility": "deny",
                        "trainingUse": "deny",
                        "reasoningUse": "deny"
                      }
                    }
                    """)
                .build();

        when(releaseRepository.findLatest(eq("praxis-api-quickstart"), eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.search(
                eq(latestRelease),
                eq("governance"),
                eq("human-resources"),
                eq(null),
                eq("LGPD"),
                any(Pageable.class)))
                .thenReturn(List.of(masked, denied));

        var context = service.contextLatest(
                "praxis-api-quickstart",
                "tenant-a",
                "dev",
                "governance",
                "human-resources",
                null,
                "LGPD",
                5);

        assertThat(context.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.itemKey()).isEqualTo("governance:human-resources.funcionarios.field.cpf:privacy");
                    assertThat(item.payload().path("contextVisibility").asText()).isEqualTo("mask");
                    assertThat(item.payload().path("payloadMode").asText()).isEqualTo("governed-summary");
                    assertThat(item.payload().path("retentionPolicy").isMissingNode()).isTrue();
                    assertThat(item.payload().path("source").isMissingNode()).isTrue();
                    assertThat(item.payload().path("aiUsage").path("visibility").asText()).isEqualTo("mask");
                });
    }

    @Test
    void ragPublicationSkipsAiDeniedDomainCatalogItems() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalogWithDeniedGovernance(), "tenant-a", "dev");

        assertThat(response.itemCount()).isEqualTo(14);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(documentsCaptor.capture());
        List<Document> documents = documentsCaptor.getValue();

        assertThat(documents).hasSize(13);
        assertThat(documents)
                .noneSatisfy(document -> assertThat(document.getMetadata())
                        .containsEntry("resourceId", "governance:human-resources.folhas-pagamento.field.secret-token:privacy"));
        assertThat(documents)
                .anySatisfy(document -> {
                    assertThat(document.getMetadata())
                            .containsEntry("resourceId", "governance:human-resources.folhas-pagamento.field.valor-liquido:privacy");
                    assertThat(document.getText()).contains("confidential", "financial", "LGPD");
                });
    }

    @Test
    void persistsNonRetryableQuotaFailureForOperationalConsumers() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogRagPublicationStateService publicationStateService =
                mock(DomainCatalogRagPublicationStateService.class);
        UUID releaseId = UUID.fromString("d070c524-b67a-4cc0-b754-d652d7424e14");
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                null,
                publicationStateService,
                true,
                false,
                100,
                3,
                0L,
                event -> { });
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> {
            DomainCatalogRelease release = invocation.getArgument(0);
            release.setId(releaseId);
            return release;
        });
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);
        when(publicationStateService.request(releaseId, 13L)).thenReturn(7L);
        when(publicationStateService.markPublishing(releaseId, 7L)).thenReturn(true);
        doThrow(AiProviderCallException.fromHttpStatus(
                "gemini", 429, "quota exhausted for embedding model"))
                .when(ragVectorStoreService).upsertDocuments(any());

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseId()).isEqualTo(releaseId);
        verify(releaseRepository).flush();
        verify(ragVectorStoreService, times(1)).upsertDocuments(any());
        verify(publicationStateService).markFailed(
                releaseId, 7L, "quota_exhausted", false, null);
        verify(publicationStateService, never()).markPublished(any(), anyLong(), anyLong());
    }

    @Test
    void canDisableDomainCatalogRagPublicationWithoutBlockingCatalogPersistence() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService(),
                false
        );
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:test", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        assertThat(response.itemCount()).isEqualTo(13);
        verify(releaseRepository).save(any(DomainCatalogRelease.class));
        verify(itemRepository).saveAll(any());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void rejectsInvalidDomainCatalogBeforePersistence() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );

        JsonNode invalid = objectMapper.readTree("""
            {
              "schemaVersion": "praxis.domain-catalog/v0.2",
              "service": {"serviceKey": "praxis-api-quickstart"},
              "release": {"releaseKey": "invalid:test", "generatedAt": "2026-04-21T10:30:00Z"},
              "contexts": [],
              "nodes": [
                {
                  "nodeKey": "human-resources.invalid",
                  "contextKey": "human-resources",
                  "nodeType": "field",
                  "label": "Invalid",
                  "status": "active",
                  "unexpected": true
                }
              ],
              "edges": [],
              "bindings": [],
              "aliases": [],
              "evidence": [],
              "governance": []
            }
            """);

        assertThatThrownBy(() -> service.ingest(invalid, "tenant-a", "dev"))
                .isInstanceOf(org.praxisplatform.config.exception.ConfigurationIngestionException.class)
                .hasMessageContaining("does not match praxis.domain-catalog/v0.2");
    }

    @Test
    void stillValidatesAndIngestsPublishedV1Catalogs() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                validationService()
        );
        when(releaseRepository.findByReleaseKeyAndScope("praxis-api-quickstart:v1", "tenant-a", "dev"))
                .thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        JsonNode v1 = objectMapper.readTree("""
            {
              "schemaVersion": "praxis.domain-catalog/v0.1",
              "service": {"serviceKey": "praxis-api-quickstart"},
              "release": {"releaseKey": "praxis-api-quickstart:v1", "generatedAt": "2026-04-21T10:30:00Z"},
              "contexts": [
                {"contextKey": "human-resources", "label": "Human Resources", "status": "active"}
              ],
              "nodes": [],
              "edges": [],
              "bindings": [],
              "aliases": [],
              "evidence": [],
              "governance": []
            }
            """);

        DomainCatalogIngestionResponse response = service.ingest(v1, "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:v1");
        assertThat(response.itemCount()).isEqualTo(1);
    }

    private DomainCatalogRelease existingRelease() {
        return DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:test")
                .schemaVersion("praxis.domain-catalog/v0.2")
                .serviceKey("praxis-api-quickstart")
                .resourceKey("human-resources.folhas-pagamento")
                .sourceHash("sha256:test")
                .tenantId("tenant-a")
                .environment("dev")
                .build();
    }

    private DomainCatalogItem existingIndexableItem(DomainCatalogRelease release) {
        return DomainCatalogItem.builder()
                .release(release)
                .itemType("node")
                .itemKey("human-resources.folhas-pagamento")
                .contextKey("human-resources")
                .nodeType("concept")
                .payload("{\"nodeKey\":\"human-resources.folhas-pagamento\"}")
                .searchableText("Folha de pagamento")
                .build();
    }

    private RagVectorStoreService.RagCorpusReleaseStatus ragStatus(
            boolean reconciled,
            long documentCount,
            long expectedDocumentCount) {
        return new RagVectorStoreService.RagCorpusReleaseStatus(
                true,
                reconciled,
                "tenant-a",
                "dev",
                "praxis-api-quickstart_test",
                expectedDocumentCount,
                documentCount,
                Math.toIntExact(documentCount),
                documentCount > 0 ? Map.of("summary", documentCount) : Map.of(),
                documentCount > 0 ? Map.of("allow", documentCount) : Map.of(),
                List.of(),
                "",
                reconciled ? List.of() : List.of("corpus-chunk-count-mismatch"));
    }

    private DomainCatalogItem nodeItem(DomainCatalogRelease release, String resourceKey, String label) {
        return DomainCatalogItem.builder()
                .release(release)
                .itemType("node")
                .itemKey(resourceKey + ".concept")
                .contextKey(resourceKey.substring(0, resourceKey.lastIndexOf('.')))
                .nodeType("concept")
                .payload("""
                    {
                      "nodeKey": "%s.concept",
                      "nodeType": "concept",
                      "resourceKey": "%s",
                      "label": "%s"
                    }
                    """.formatted(resourceKey, resourceKey, label))
                .build();
    }

    private Document domainCatalogDocument(
            String releaseKey,
            String itemType,
            String itemKey,
            String text) {
        return Document.builder()
                .id(releaseKey + ":" + itemKey)
                .text(text)
                .metadata(java.util.Map.of(
                        RagMetadataKeys.RELEASE_ID, releaseKey,
                        RagMetadataKeys.DOC_TYPE, itemType,
                        RagMetadataKeys.RESOURCE_ID, itemKey))
                .build();
    }

    private JsonNode sampleCatalog() throws Exception {
        return objectMapper.readTree("""
            {
              "schemaVersion": "praxis.domain-catalog/v0.2",
              "service": {
                "serviceKey": "praxis-api-quickstart",
                "name": "Praxis API Quickstart",
                "version": "test"
              },
              "release": {
                "releaseKey": "praxis-api-quickstart:test",
                "generatedAt": "2026-04-21T10:30:00Z",
                "sourceHash": "sha256:test"
              },
              "resourceKey": "human-resources.folhas-pagamento",
              "contexts": [
                {
                  "contextKey": "human-resources",
                  "label": "Recursos Humanos",
                  "status": "active",
                  "semanticOwner": "human-resources",
                  "lifecycle": "active",
                  "businessGlossary": {
                    "preferredTerm": "Recursos Humanos",
                    "examples": ["bounded-context"]
                  }
                }
              ],
              "nodes": [
                {
                  "nodeKey": "human-resources.folhas-pagamento",
                  "contextKey": "human-resources",
                  "nodeType": "concept",
                  "label": "Folha de pagamento",
                  "status": "active",
                  "semanticOwner": "human-resources",
                  "lifecycle": "active",
                  "businessGlossary": {
                    "preferredTerm": "Folha de pagamento"
                  },
                  "resolution": {
                    "canonicalKey": "human-resources.folhas-pagamento",
                    "matchKeys": ["human-resources.folhas-pagamento"],
                    "ambiguityPolicy": "exact-key-or-alias"
                  },
                  "sourceEvidenceKeys": []
                },
                {
                  "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                  "contextKey": "human-resources",
                  "nodeType": "field",
                  "label": "Valor liquido",
                  "description": "Valor liquido da folha",
                  "status": "active",
                  "metadata": {
                    "fieldName": "valorLiquido",
                    "schemaId": "WorkflowResponse",
                    "type": "number",
                    "format": "double"
                  },
                  "semanticOwner": "human-resources",
                  "lifecycle": "active",
                  "businessGlossary": {
                    "preferredTerm": "Valor liquido",
                    "description": "Valor liquido da folha",
                    "examples": ["dto-field", "response"]
                  },
                  "resolution": {
                    "canonicalKey": "human-resources.folhas-pagamento.field.valor-liquido",
                    "matchKeys": ["valorLiquido", "Valor liquido", "WorkflowResponse"],
                    "ambiguityPolicy": "exact-key-or-alias"
                  },
                  "sourceEvidenceKeys": ["evidence:human-resources.folhas-pagamento.field.valor-liquido:WorkflowResponse"]
                },
                {
                  "nodeKey": "human-resources.folhas-pagamento.policy.supplier.selection",
                  "contextKey": "human-resources",
                  "nodeType": "policy_hint",
                  "label": "Supplier selecionavel",
                  "description": "Supplier selecionavel por status",
                  "status": "active",
                  "metadata": {
                    "allowedStatuses": ["ACTIVE", "APPROVED"],
                    "blockedStatuses": ["INACTIVE", "BLOCKED"]
                  },
                  "semanticOwner": "human-resources",
                  "lifecycle": "active",
                  "businessGlossary": {
                    "preferredTerm": "Supplier selecionavel",
                    "description": "Supplier selecionavel por status",
                    "examples": ["option-source", "selection-policy"]
                  },
                  "resolution": {
                    "canonicalKey": "human-resources.folhas-pagamento.policy.supplier.selection",
                    "matchKeys": ["supplier"],
                    "ambiguityPolicy": "exact-key-or-alias"
                  },
                  "sourceEvidenceKeys": ["evidence:human-resources.folhas-pagamento.policy.supplier.selection:option-source"]
                },
                {
                  "nodeKey": "human-resources.folhas-pagamento.stats.group-by",
                  "contextKey": "human-resources",
                  "nodeType": "stats",
                  "label": "Group By",
                  "description": "Capability analitica publicada pelo endpoint /api/human-resources/folhas-pagamento/stats/group-by.",
                  "status": "active",
                  "source": "openapi-stats",
                  "metadata": {
                    "capabilityKey": "stats.groupBy",
                    "resourceKey": "human-resources.folhas-pagamento",
                    "path": "/api/human-resources/folhas-pagamento/stats/group-by",
                    "method": "POST"
                  },
                  "semanticOwner": "human-resources",
                  "lifecycle": "active",
                  "businessGlossary": {
                    "preferredTerm": "Group By",
                    "description": "Capability analitica publicada pelo endpoint /api/human-resources/folhas-pagamento/stats/group-by.",
                    "examples": ["stats", "analytics"]
                  },
                  "resolution": {
                    "canonicalKey": "human-resources.folhas-pagamento.stats.group-by",
                    "matchKeys": ["groupBy", "stats", "/api/human-resources/folhas-pagamento/stats/group-by"],
                    "ambiguityPolicy": "exact-key-or-alias"
                  },
                  "sourceEvidenceKeys": ["evidence:human-resources.folhas-pagamento.stats.group-by:openapi-stats"]
                }
              ],
              "edges": [
                {
                  "edgeKey": "human-resources.folhas-pagamento.has-field.valor-liquido",
                  "sourceNodeKey": "human-resources.folhas-pagamento",
                  "targetNodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                  "edgeType": "has_field"
                },
                {
                  "edgeKey": "human-resources.folhas-pagamento.has-stats.group-by",
                  "sourceNodeKey": "human-resources.folhas-pagamento",
                  "targetNodeKey": "human-resources.folhas-pagamento.stats.group-by",
                  "edgeType": "has_stats"
                }
              ],
              "bindings": [
                {
                  "bindingKey": "binding:human-resources.folhas-pagamento.field.valor-liquido:dto-field",
                  "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                  "bindingType": "dto_field",
                  "target": {
                    "schemaId": "WorkflowResponse",
                    "fieldName": "valorLiquido"
                  }
                },
                {
                  "bindingKey": "binding:human-resources.folhas-pagamento.stats.group-by:openapi-stats",
                  "nodeKey": "human-resources.folhas-pagamento.stats.group-by",
                  "bindingType": "stats_endpoint",
                  "target": {
                    "capabilityKey": "stats.groupBy",
                    "resourceKey": "human-resources.folhas-pagamento",
                    "path": "/api/human-resources/folhas-pagamento/stats/group-by",
                    "method": "POST"
                  }
                }
              ],
              "aliases": [
                {
                  "aliasKey": "alias:human-resources.folhas-pagamento.field.valor-liquido:schema-field-name:valor-liquido",
                  "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                  "alias": "valorLiquido",
                  "source": "schema-field-name",
                  "confidence": 0.85
                }
              ],
              "evidence": [
                {
                  "evidenceKey": "evidence:human-resources.folhas-pagamento.field.valor-liquido:WorkflowResponse",
                  "evidenceType": "dto_schema",
                  "sourceRef": {
                    "schemaId": "WorkflowResponse",
                    "fieldName": "valorLiquido"
                  },
                  "summary": "Campo derivado do schema OpenAPI WorkflowResponse."
                },
                {
                  "evidenceKey": "evidence:human-resources.folhas-pagamento.stats.group-by:openapi-stats",
                  "evidenceType": "openapi_stats",
                  "sourceRef": {
                    "kind": "openapi.operation",
                    "capabilityKey": "stats.groupBy",
                    "resourceKey": "human-resources.folhas-pagamento",
                    "path": "/api/human-resources/folhas-pagamento/stats/group-by",
                    "method": "POST"
                  },
                  "summary": "Capability stats derivada do endpoint OpenAPI /api/human-resources/folhas-pagamento/stats/group-by."
                }
              ],
              "governance": [
                {
                  "governanceKey": "governance:human-resources.folhas-pagamento.field.valor-liquido:privacy",
                  "nodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                  "annotationType": "privacy",
                  "classification": "confidential",
                  "dataCategory": "financial",
                  "complianceTags": ["LGPD", "INTERNAL_POLICY"],
                  "aiUsage": {
                    "visibility": "mask",
                    "trainingUse": "deny",
                    "ruleAuthoring": "review_required",
                    "reasoningUse": "allow"
                  },
                  "source": "dto-field-heuristic",
                  "confidence": 0.72
                }
              ]
            }
            """);
    }

    private JsonNode sampleCatalogWithDeniedGovernance() throws Exception {
        JsonNode catalog = sampleCatalog().deepCopy();
        ((ArrayNode) catalog.path("governance")).add(objectMapper.readTree("""
            {
              "governanceKey": "governance:human-resources.folhas-pagamento.field.secret-token:privacy",
              "nodeKey": "human-resources.folhas-pagamento.field.secret-token",
              "annotationType": "privacy",
              "classification": "restricted",
              "dataCategory": "credential",
              "complianceTags": ["INTERNAL_POLICY"],
              "aiUsage": {
                "visibility": "deny",
                "trainingUse": "deny",
                "ruleAuthoring": "deny",
                "reasoningUse": "deny"
              },
              "source": "manual-sensitive-review",
              "confidence": 1.0
            }
            """));
        return catalog;
    }

    private DomainCatalogSchemaValidationService validationService() {
        return new DomainCatalogSchemaValidationService(objectMapper);
    }
}
