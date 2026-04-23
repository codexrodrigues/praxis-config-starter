package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainContext;
import org.praxisplatform.config.domain.DomainContextRelationship;
import org.praxisplatform.config.domain.DomainContract;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.domain.DomainResolution;
import org.praxisplatform.config.domain.DomainSource;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextRelationship;
import org.praxisplatform.config.dto.DomainFederationContract;
import org.praxisplatform.config.dto.DomainFederationResolution;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainContextRelationshipRepository;
import org.praxisplatform.config.repository.DomainContextRepository;
import org.praxisplatform.config.repository.DomainContractRepository;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.repository.DomainResolutionRepository;
import org.praxisplatform.config.repository.DomainSourceRepository;

@Tag("unit")
class DomainFederationIngestPersistenceServiceTest {

    @Test
    void persistsValidRequestAsCandidateRelease() {
        Fixture fixture = fixture(true);
        DomainFederationValidationRequest request = request();
        when(fixture.validator.validate(request)).thenReturn(new DomainFederationValidationReport(true, 0, 0, List.of()));
        when(fixture.releaseRepository.save(any(DomainFederationRelease.class))).thenAnswer(invocation -> {
            DomainFederationRelease release = invocation.getArgument(0);
            release.onInsert();
            return release;
        });
        when(fixture.sourceRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.contextRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.relationshipRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.contractRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.resolutionRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = fixture.service.ingestCandidate(request);

        assertThat(response.dryRun()).isFalse();
        assertThat(response.valid()).isTrue();
        assertThat(response.releaseId()).isNotNull();
        assertThat(response.releaseKey()).startsWith("domain-federation:tenant-a:dev:");
        assertThat(response.status()).isEqualTo("candidate");
        assertThat(response.payloadHash()).hasSize(64);
        assertThat(response.persistedCounts().sources()).isEqualTo(1);
        assertThat(response.persistedCounts().contexts()).isEqualTo(1);
        assertThat(response.persistedCounts().contextRelationships()).isEqualTo(1);
        assertThat(response.persistedCounts().contracts()).isEqualTo(1);
        assertThat(response.persistedCounts().resolutions()).isEqualTo(1);

        ArgumentCaptor<DomainFederationRelease> releaseCaptor = ArgumentCaptor.forClass(DomainFederationRelease.class);
        verify(fixture.releaseRepository).save(releaseCaptor.capture());
        assertThat(releaseCaptor.getValue().getStatus()).isEqualTo("candidate");
        assertThat(releaseCaptor.getValue().getValidationReport()).contains("\"valid\":true");

        ArgumentCaptor<List<DomainSource>> sourceCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.sourceRepository).saveAll(sourceCaptor.capture());
        assertThat(sourceCaptor.getValue()).singleElement().satisfies(source -> {
            assertThat(source.getSourceKey()).isEqualTo("operations-source");
            assertThat(source.getEvidence()).isEqualTo("{}");
        });
    }

    @Test
    void rejectsInvalidRequestWithoutPersistingRows() {
        Fixture fixture = fixture(true);
        DomainFederationValidationRequest request = request();
        when(fixture.validator.validate(request)).thenReturn(new DomainFederationValidationReport(false, 1, 0, List.of()));

        var response = fixture.service.ingestCandidate(request);

        assertThat(response.valid()).isFalse();
        assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.releaseId()).isNull();
        assertThat(response.persistedCounts().sources()).isZero();
        verify(fixture.releaseRepository, never()).save(any());
        verify(fixture.sourceRepository, never()).saveAll(anyList());
    }

    @Test
    void refusesPersistenceWhenFeatureFlagIsDisabled() {
        Fixture fixture = fixture(false);

        assertThatThrownBy(() -> fixture.service.ingestCandidate(request()))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("praxis.domain-federation.persistence.enabled=true");

        verify(fixture.validator, never()).validate(any());
    }

    private Fixture fixture(boolean persistenceEnabled) {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationReleaseRepository releaseRepository = mock(DomainFederationReleaseRepository.class);
        DomainSourceRepository sourceRepository = mock(DomainSourceRepository.class);
        DomainContextRepository contextRepository = mock(DomainContextRepository.class);
        DomainContextRelationshipRepository relationshipRepository = mock(DomainContextRelationshipRepository.class);
        DomainContractRepository contractRepository = mock(DomainContractRepository.class);
        DomainResolutionRepository resolutionRepository = mock(DomainResolutionRepository.class);
        DomainCatalogReleaseRepository catalogReleaseRepository = mock(DomainCatalogReleaseRepository.class);
        DomainFederationIngestPersistenceService service = new DomainFederationIngestPersistenceService(
                validator,
                releaseRepository,
                sourceRepository,
                contextRepository,
                relationshipRepository,
                contractRepository,
                resolutionRepository,
                catalogReleaseRepository,
                new ObjectMapper(),
                persistenceEnabled);
        return new Fixture(
                service,
                validator,
                releaseRepository,
                sourceRepository,
                contextRepository,
                relationshipRepository,
                contractRepository,
                resolutionRepository);
    }

    private DomainFederationValidationRequest request() {
        return new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(new DomainFederationSource(
                        "operations-source",
                        "microservice",
                        "operations-service",
                        "Operations",
                        "tenant-a",
                        "dev",
                        "Operacoes",
                        "Operations Platform",
                        "authoritative",
                        "active",
                        "domain-catalog:operations:v1",
                        null)),
                List.of(new DomainFederationContext(
                        "operations",
                        "operations-source",
                        "bounded_context",
                        "Operations",
                        "Mission and execution concepts.",
                        "Operacoes",
                        "Operations Platform",
                        "tenant-a",
                        "dev",
                        "active",
                        "domain-catalog:operations:v1",
                        null)),
                List.of(new DomainFederationContextRelationship(
                        "operations.uses.assets",
                        "operations",
                        "assets",
                        "uses",
                        "assets.vehicle-allocation-changed.v1",
                        "source_to_target",
                        "shared",
                        0.91,
                        "active",
                        null)),
                List.of(new DomainFederationContract(
                        "assets.vehicle-allocation-changed.v1",
                        "event_schema",
                        "assets-service",
                        "assets",
                        "operations",
                        "assets.veiculos",
                        "VehicleAllocationChanged",
                        "asyncapi://assets-service/events/VehicleAllocationChanged/v1",
                        "stable",
                        "internal",
                        "active",
                        null)),
                List.of(new DomainFederationResolution(
                        "operations.driver.maps_to.hr.funcionario",
                        "operations.driver",
                        "human-resources.funcionario",
                        "operations",
                        "human-resources",
                        "maps_to",
                        0.82,
                        "review_required",
                        "Operacoes",
                        null)));
    }

    private record Fixture(
            DomainFederationIngestPersistenceService service,
            DomainFederationContractValidator validator,
            DomainFederationReleaseRepository releaseRepository,
            DomainSourceRepository sourceRepository,
            DomainContextRepository contextRepository,
            DomainContextRelationshipRepository relationshipRepository,
            DomainContractRepository contractRepository,
            DomainResolutionRepository resolutionRepository) {
    }
}
