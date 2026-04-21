package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainCatalogItemRepository;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class DomainCatalogIngestionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ingestsDomainCatalogReleaseAndMaterializesSearchableItems() throws Exception {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService
        );

        when(releaseRepository.findByReleaseKey("praxis-api-quickstart:test")).thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        assertThat(response.itemCount()).isEqualTo(7);

        ArgumentCaptor<DomainCatalogRelease> releaseCaptor = ArgumentCaptor.forClass(DomainCatalogRelease.class);
        verify(releaseRepository).save(releaseCaptor.capture());
        assertThat(releaseCaptor.getValue())
                .satisfies(release -> {
                    assertThat(release.getSchemaVersion()).isEqualTo("praxis.domain-catalog/v0.1");
                    assertThat(release.getServiceKey()).isEqualTo("praxis-api-quickstart");
                    assertThat(release.getTenantId()).isEqualTo("tenant-a");
                    assertThat(release.getEnvironment()).isEqualTo("dev");
                    assertThat(release.getRawPayload()).contains("Folha de pagamento");
                });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DomainCatalogItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(itemsCaptor.capture());
        List<DomainCatalogItem> items = itemsCaptor.getValue();

        assertThat(items).extracting(DomainCatalogItem::getItemType)
                .contains("context", "node", "edge", "binding", "evidence");
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
                ragVectorStoreService
        );

        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:latest")
                .schemaVersion("praxis.domain-catalog/v0.1")
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

        when(releaseRepository.findLatest(eq("praxis-api-quickstart"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.search(
                eq("praxis-api-quickstart:latest"),
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
    void buildsLlmReadyContextFromLatestRelease() {
        DomainCatalogReleaseRepository releaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainCatalogItemRepository itemRepository = mock(DomainCatalogItemRepository.class);
        RagVectorStoreService ragVectorStoreService = mock(RagVectorStoreService.class);
        DomainCatalogIngestionService service = new DomainCatalogIngestionService(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService
        );

        DomainCatalogRelease latestRelease = DomainCatalogRelease.builder()
                .releaseKey("praxis-api-quickstart:latest")
                .schemaVersion("praxis.domain-catalog/v0.1")
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

        when(releaseRepository.findLatest(eq("praxis-api-quickstart"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.search(
                eq("praxis-api-quickstart:latest"),
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

    private JsonNode sampleCatalog() throws Exception {
        return objectMapper.readTree("""
            {
              "schemaVersion": "praxis.domain-catalog/v0.1",
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
              "contexts": [
                {
                  "contextKey": "human-resources",
                  "label": "Recursos Humanos",
                  "status": "active"
                }
              ],
              "nodes": [
                {
                  "nodeKey": "human-resources.folhas-pagamento",
                  "contextKey": "human-resources",
                  "nodeType": "concept",
                  "label": "Folha de pagamento",
                  "status": "active"
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
                  }
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
                  }
                }
              ],
              "edges": [
                {
                  "edgeKey": "human-resources.folhas-pagamento.has-field.valor-liquido",
                  "sourceNodeKey": "human-resources.folhas-pagamento",
                  "targetNodeKey": "human-resources.folhas-pagamento.field.valor-liquido",
                  "edgeType": "has_field"
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
                }
              ],
              "aliases": [],
              "evidence": [
                {
                  "evidenceKey": "evidence:human-resources.folhas-pagamento.field.valor-liquido:WorkflowResponse",
                  "evidenceType": "dto_schema",
                  "summary": "Campo derivado do schema OpenAPI WorkflowResponse."
                }
              ],
              "governance": []
            }
            """);
    }
}
