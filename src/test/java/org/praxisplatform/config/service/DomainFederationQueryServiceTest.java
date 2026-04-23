package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainContext;
import org.praxisplatform.config.domain.DomainContextRelationship;
import org.praxisplatform.config.domain.DomainContract;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.domain.DomainSource;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.repository.DomainContextRelationshipRepository;
import org.praxisplatform.config.repository.DomainContextRepository;
import org.praxisplatform.config.repository.DomainContractRepository;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.repository.DomainSourceRepository;

@Tag("unit")
class DomainFederationQueryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsFederatedContextFromCatalogProjection() throws Exception {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        Fixture fixture = fixture(catalogService);
        DomainFederationQueryService service = fixture.service();
        DomainCatalogContextResponse catalogContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                "veiculo",
                "node",
                "operations",
                null,
                List.of("Prefer explicit context keys."),
                List.of(new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "node",
                        "operations.mission",
                        "operations",
                        "aggregate",
                        null,
                        null,
                        null)));
        List<DomainCatalogItemResponse> relationships = List.of(
                new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "edge",
                        "operations.mission.depends_on.assets.vehicle",
                        "operations",
                        null,
                        null,
                        "depends_on",
                        objectMapper.readTree("{\"confidence\":0.64}")),
                new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "edge",
                        "operations.mission.depends_on.security.token",
                        "operations",
                        null,
                        null,
                        "depends_on",
                        objectMapper.readTree("{\"aiUsage\":{\"visibility\":\"deny\"}}")));
        when(catalogService.contextLatest(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "veiculo",
                20)).thenReturn(catalogContext);
        when(catalogService.relationshipsLatest(
                null,
                null,
                "tenant-a",
                "dev",
                null,
                null,
                "depends_on",
                "veiculo",
                20)).thenReturn(relationships);

        var response = service.context(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "depends_on",
                "veiculo",
                20);

        assertThat(response.federated()).isTrue();
        assertThat(response.sourceMode()).isEqualTo("catalog_projection_fallback");
        assertThat(response.context().items()).containsExactlyElementsOf(catalogContext.items());
        assertThat(response.relationships()).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("operations.mission.depends_on.assets.vehicle");
        assertThat(response.policyReport().inputItemCount()).isEqualTo(3);
        assertThat(response.policyReport().returnedItemCount()).isEqualTo(2);
        assertThat(response.policyReport().policyProfile()).isEqualTo("explanation");
        assertThat(response.policyReport().minConfidence()).isEqualTo(0.7d);
        assertThat(response.policyReport().deniedItemCount()).isEqualTo(1);
        assertThat(response.policyReport().lowConfidenceItemCount()).isEqualTo(1);
        assertThat(response.retrievalGuidance())
                .contains("Prefer explicit context keys.")
                .contains("Federated context is currently projected from domain catalog releases and edge rows.")
                .anySatisfy(guidance -> assertThat(guidance).contains("Excluded operations.mission.depends_on.security.token"))
                .anySatisfy(guidance -> assertThat(guidance).contains("Flagged operations.mission.depends_on.assets.vehicle"));
        verify(catalogService).contextLatest(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "veiculo",
                20);
        verify(catalogService).relationshipsLatest(
                null,
                null,
                "tenant-a",
                "dev",
                null,
                null,
                "depends_on",
                "veiculo",
                20);
    }

    @Test
    void appliesRuntimeRetrievalPolicyOptions() throws Exception {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        Fixture fixture = fixture(catalogService);
        DomainFederationQueryService service = fixture.service();
        DomainCatalogContextResponse catalogContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                "veiculo",
                "node",
                "operations",
                null,
                List.of(),
                List.of(new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "domain-catalog:operations:v1",
                        "node",
                        "operations.low-confidence",
                        "operations",
                        "entity",
                        null,
                        null,
                        objectMapper.readTree("{\"confidence\":0.72}"))));
        when(catalogService.contextLatest(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "veiculo",
                20)).thenReturn(catalogContext);
        when(catalogService.relationshipsLatest(
                null,
                null,
                "tenant-a",
                "dev",
                null,
                null,
                null,
                "veiculo",
                20)).thenReturn(List.of(new DomainCatalogItemResponse(
                UUID.randomUUID(),
                "domain-catalog:operations:v1",
                "edge",
                "operations.denied",
                "operations",
                null,
                null,
                null,
                objectMapper.readTree("{\"aiUsage\":{\"visibility\":\"deny\"},\"confidence\":0.95}"))));

        var response = service.context(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                null,
                "veiculo",
                20,
                new DomainFederationRetrievalPolicyOptions("diagnostics", 0.8d, true, false));

        assertThat(response.context().items()).isEmpty();
        assertThat(response.relationships()).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("operations.denied");
        assertThat(response.policyReport().policyProfile()).isEqualTo("diagnostics");
        assertThat(response.policyReport().minConfidence()).isEqualTo(0.8d);
        assertThat(response.policyReport().includeDenied()).isTrue();
        assertThat(response.policyReport().includeLowConfidence()).isFalse();
    }

    @Test
    void clampsInvalidLimitAndPreservesScopedQuery() {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        Fixture fixture = fixture(catalogService);
        DomainFederationQueryService service = fixture.service();
        DomainCatalogContextResponse catalogContext = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                null,
                "employee",
                "node",
                "human-resources",
                "entity",
                List.of(),
                List.of());
        when(catalogService.contextLatest(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "entity",
                "employee",
                20)).thenReturn(catalogContext);
        when(catalogService.relationshipsLatest(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                null,
                null,
                null,
                "employee",
                20)).thenReturn(List.of());

        var response = service.context(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "entity",
                null,
                "employee",
                -1);

        assertThat(response.federated()).isFalse();
        assertThat(response.sourceMode()).isEqualTo("catalog_projection_fallback");
        assertThat(response.limit()).isEqualTo(20);
        verify(catalogService).contextLatest(
                "praxis-api-quickstart",
                "human-resources.folhas-pagamento",
                "tenant-a",
                "dev",
                "node",
                "human-resources",
                "entity",
                "employee",
                20);
    }

    @Test
    void prefersActivePersistedFederationReleaseWhenAvailable() throws Exception {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        Fixture fixture = fixture(catalogService);
        DomainFederationRelease release = DomainFederationRelease.builder()
                .id(UUID.randomUUID())
                .releaseKey("domain-federation:tenant-a:dev:v1")
                .tenantId("tenant-a")
                .environment("dev")
                .status("active")
                .payloadHash("abc")
                .createdAt(Instant.parse("2026-04-23T10:00:00Z"))
                .activatedAt(Instant.parse("2026-04-23T10:01:00Z"))
                .build();
        DomainSource source = DomainSource.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .sourceKey("operations-source")
                .serviceKey("operations-service")
                .serviceName("Operations")
                .status("active")
                .evidence("{}")
                .build();
        DomainContext context = DomainContext.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .contextKey("operations")
                .sourceKey("operations-source")
                .contextType("bounded_context")
                .label("Operations")
                .description("Mission execution concepts.")
                .semanticOwner("Operacoes")
                .technicalOwner("Platform")
                .status("active")
                .evidence("{\"confidence\":0.91}")
                .build();
        DomainContract contract = DomainContract.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .contractKey("assets.vehicle-allocation.v1")
                .contractType("lookup_option_source")
                .providerSourceKey("assets-source")
                .providerContextKey("assets")
                .consumerContextKey("operations")
                .resourceKey("assets.veiculos")
                .operationKey("vehicleAllocationLookup")
                .compatibility("stable")
                .visibility("internal")
                .status("active")
                .evidence("{}")
                .build();
        DomainContextRelationship relationship = DomainContextRelationship.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .relationshipKey("operations.uses.assets")
                .sourceContextKey("operations")
                .targetContextKey("assets")
                .relationshipType("uses")
                .contractKey("assets.vehicle-allocation.v1")
                .direction("source_to_target")
                .ownership("target_owned")
                .confidence(0.92d)
                .status("active")
                .evidence("{}")
                .build();
        when(fixture.releaseRepository().findActiveByOptionalScope("tenant-a", "dev")).thenReturn(List.of(release));
        when(fixture.sourceRepository().findByFederationRelease_IdOrderBySourceKey(release.getId())).thenReturn(List.of(source));
        when(fixture.contextRepository().findByFederationRelease_IdOrderByContextKey(release.getId())).thenReturn(List.of(context));
        when(fixture.contractRepository().findByFederationRelease_IdOrderByContractKey(release.getId())).thenReturn(List.of(contract));
        when(fixture.relationshipRepository().findByFederationRelease_IdOrderByRelationshipKey(release.getId())).thenReturn(List.of(relationship));

        var response = fixture.service().context(
                "operations-service",
                "assets.veiculos",
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "uses",
                "operations",
                20);

        assertThat(response.sourceMode()).isEqualTo("persisted_federation");
        assertThat(response.federated()).isTrue();
        assertThat(response.context().release().releaseKey()).isEqualTo("domain-federation:tenant-a:dev:v1");
        assertThat(response.context().items()).singleElement().satisfies(item -> {
            assertThat(item.itemType()).isEqualTo("context");
            assertThat(item.itemKey()).isEqualTo("operations");
            assertThat(item.payload().path("sourceMode").asText()).isEqualTo("persisted_federation");
            assertThat(item.payload().path("serviceKey").asText()).isEqualTo("operations-service");
        });
        assertThat(response.relationships()).singleElement().satisfies(item -> {
            assertThat(item.itemType()).isEqualTo("edge");
            assertThat(item.edgeType()).isEqualTo("uses");
            assertThat(item.payload().path("contract").path("resourceKey").asText()).isEqualTo("assets.veiculos");
        });
        assertThat(response.retrievalGuidance())
                .anySatisfy(guidance -> assertThat(guidance).contains("active persisted domain federation release"));
    }

    @Test
    void redactsPersistedRelationshipsWhenLinkedContractIsNotLlmSafe() {
        DomainCatalogIngestionService catalogService = mock(DomainCatalogIngestionService.class);
        Fixture fixture = fixture(catalogService);
        DomainFederationRelease release = DomainFederationRelease.builder()
                .id(UUID.randomUUID())
                .releaseKey("domain-federation:tenant-a:dev:v2")
                .tenantId("tenant-a")
                .environment("dev")
                .status("active")
                .payloadHash("def")
                .createdAt(Instant.parse("2026-04-23T10:10:00Z"))
                .activatedAt(Instant.parse("2026-04-23T10:11:00Z"))
                .build();
        DomainSource source = DomainSource.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .sourceKey("operations-source")
                .serviceKey("operations-service")
                .serviceName("Operations")
                .status("active")
                .evidence("{}")
                .build();
        DomainContext context = DomainContext.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .contextKey("operations")
                .sourceKey("operations-source")
                .contextType("bounded_context")
                .label("Operations")
                .description("Mission execution concepts.")
                .semanticOwner("Operacoes")
                .technicalOwner("Platform")
                .status("active")
                .evidence("{}")
                .build();
        DomainContract restrictedContract = DomainContract.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .contractKey("assets.vehicle-allocation.v2")
                .contractType("lookup_option_source")
                .providerSourceKey("assets-source")
                .providerContextKey("assets")
                .consumerContextKey("operations")
                .resourceKey("assets.veiculos")
                .compatibility("stable")
                .visibility("deny_for_llm")
                .status("active")
                .evidence("{}")
                .build();
        DomainContextRelationship relationship = DomainContextRelationship.builder()
                .id(UUID.randomUUID())
                .federationRelease(release)
                .relationshipKey("operations.uses.assets.denied")
                .sourceContextKey("operations")
                .targetContextKey("assets")
                .relationshipType("uses")
                .contractKey("assets.vehicle-allocation.v2")
                .direction("source_to_target")
                .ownership("target_owned")
                .confidence(0.95d)
                .status("active")
                .evidence("{}")
                .build();
        when(fixture.releaseRepository().findActiveByOptionalScope("tenant-a", "dev")).thenReturn(List.of(release));
        when(fixture.sourceRepository().findByFederationRelease_IdOrderBySourceKey(release.getId())).thenReturn(List.of(source));
        when(fixture.contextRepository().findByFederationRelease_IdOrderByContextKey(release.getId())).thenReturn(List.of(context));
        when(fixture.contractRepository().findByFederationRelease_IdOrderByContractKey(release.getId())).thenReturn(List.of(restrictedContract));
        when(fixture.relationshipRepository().findByFederationRelease_IdOrderByRelationshipKey(release.getId())).thenReturn(List.of(relationship));

        var response = fixture.service().context(
                "operations-service",
                "assets.veiculos",
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "uses",
                "operations",
                20,
                new DomainFederationRetrievalPolicyOptions("authoring", null, null, null));

        assertThat(response.sourceMode()).isEqualTo("persisted_federation");
        assertThat(response.context().items()).hasSize(1);
        assertThat(response.relationships()).isEmpty();
        assertThat(response.policyReport().deniedItemCount()).isEqualTo(1);
        assertThat(response.retrievalGuidance())
                .anySatisfy(guidance -> assertThat(guidance).contains("contract.visibility=deny_for_llm"));
    }

    private Fixture fixture(DomainCatalogIngestionService catalogService) {
        DomainFederationReleaseRepository releaseRepository = mock(DomainFederationReleaseRepository.class);
        DomainSourceRepository sourceRepository = mock(DomainSourceRepository.class);
        DomainContextRepository contextRepository = mock(DomainContextRepository.class);
        DomainContextRelationshipRepository relationshipRepository = mock(DomainContextRelationshipRepository.class);
        DomainContractRepository contractRepository = mock(DomainContractRepository.class);
        when(releaseRepository.findActiveByOptionalScope(null, null)).thenReturn(List.of());
        when(releaseRepository.findActiveByOptionalScope("tenant-a", "dev")).thenReturn(List.of());
        return new Fixture(
                new DomainFederationQueryService(
                        catalogService,
                        new DomainFederationRetrievalPolicyService(),
                        releaseRepository,
                        sourceRepository,
                        contextRepository,
                        relationshipRepository,
                        contractRepository,
                        objectMapper),
                releaseRepository,
                sourceRepository,
                contextRepository,
                relationshipRepository,
                contractRepository);
    }

    private record Fixture(
            DomainFederationQueryService service,
            DomainFederationReleaseRepository releaseRepository,
            DomainSourceRepository sourceRepository,
            DomainContextRepository contextRepository,
            DomainContextRelationshipRepository relationshipRepository,
            DomainContractRepository contractRepository) {
    }
}
