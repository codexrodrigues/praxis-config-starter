package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainCatalogItemRepository;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                ragVectorStoreService,
                validationService()
        );

        when(releaseRepository.findByReleaseKey("praxis-api-quickstart:test")).thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(false);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalog(), "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("praxis-api-quickstart:test");
        assertThat(response.itemCount()).isEqualTo(9);

        ArgumentCaptor<DomainCatalogRelease> releaseCaptor = ArgumentCaptor.forClass(DomainCatalogRelease.class);
        verify(releaseRepository).save(releaseCaptor.capture());
        assertThat(releaseCaptor.getValue())
                .satisfies(release -> {
                    assertThat(release.getSchemaVersion()).isEqualTo("praxis.domain-catalog/v0.2");
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

        when(releaseRepository.findLatest(eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest, financeLatest, hrOlder));
        when(itemRepository.search(eq("hr:latest"), eq("node"), eq(null), eq("field"), eq("field"), any(Pageable.class)))
                .thenReturn(List.of(hrField));
        when(itemRepository.search(eq("finance:latest"), eq("node"), eq(null), eq("field"), eq("field"), any(Pageable.class)))
                .thenReturn(List.of(financeField));

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

        when(releaseRepository.findLatest(eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest));
        when(itemRepository.search(
                eq("hr:latest"),
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
                .contains("This context may include items from the latest releases of multiple services; keep service boundaries explicit when citing or applying it.");
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

        when(releaseRepository.findLatest(eq(null), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest, financeLatest));
        when(itemRepository.search(eq("hr:latest"), eq("edge"), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(crossServiceReference));
        when(itemRepository.search(eq("finance:latest"), eq("edge"), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(sameAsEdge));

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

        when(releaseRepository.findLatest(eq("hr-service"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(hrLatest));
        when(itemRepository.search(eq("hr:latest"), eq("edge"), eq(null), eq(null), eq("salary"), any(Pageable.class)))
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

        when(releaseRepository.findLatest(eq("praxis-service"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(operationsRelease, funcionariosRelease));
        when(itemRepository.search(
                eq("praxis-service:human-resources.funcionarios:2026-04-22T11:01:23Z"),
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

        when(releaseRepository.findLatest(eq("praxis-api-quickstart"), eq("tenant-a"), eq("dev"), any(Pageable.class)))
                .thenReturn(List.of(latestRelease));
        when(itemRepository.search(
                eq("praxis-api-quickstart:latest"),
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
        when(releaseRepository.findByReleaseKey("praxis-api-quickstart:test")).thenReturn(Optional.empty());
        when(releaseRepository.save(any(DomainCatalogRelease.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ragVectorStoreService.isAvailable()).thenReturn(true);

        DomainCatalogIngestionResponse response = service.ingest(sampleCatalogWithDeniedGovernance(), "tenant-a", "dev");

        assertThat(response.itemCount()).isEqualTo(10);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(documentsCaptor.capture());
        List<Document> documents = documentsCaptor.getValue();

        assertThat(documents).hasSize(9);
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
        when(releaseRepository.findByReleaseKey("praxis-api-quickstart:v1")).thenReturn(Optional.empty());
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
