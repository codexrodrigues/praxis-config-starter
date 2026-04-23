package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainContext;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.domain.DomainSource;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationIngestDryRunResponse;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.repository.DomainContextRelationshipRepository;
import org.praxisplatform.config.repository.DomainContextRepository;
import org.praxisplatform.config.repository.DomainContractRepository;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.repository.DomainResolutionRepository;
import org.praxisplatform.config.repository.DomainSourceRepository;

@Tag("unit")
class DomainFederationIngestionServiceTest {

    @Test
    void persistsValidatedFederationEnvelopeAsReleaseAndItems() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService dryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationReleaseRepository releaseRepository = mock(DomainFederationReleaseRepository.class);
        DomainSourceRepository sourceRepository = mock(DomainSourceRepository.class);
        DomainContextRepository contextRepository = mock(DomainContextRepository.class);
        DomainContextRelationshipRepository relationshipRepository = mock(DomainContextRelationshipRepository.class);
        DomainContractRepository contractRepository = mock(DomainContractRepository.class);
        DomainResolutionRepository resolutionRepository = mock(DomainResolutionRepository.class);
        DomainFederationIngestionService service = new DomainFederationIngestionService(
                validator,
                dryRunService,
                releaseRepository,
                sourceRepository,
                contextRepository,
                relationshipRepository,
                contractRepository,
                resolutionRepository,
                new ObjectMapper());
        DomainFederationValidationRequest request = validRequest();
        when(validator.validate(request)).thenReturn(new DomainFederationValidationReport(true, 0, 0, List.of()));
        when(releaseRepository.findByTenantIdAndEnvironmentAndReleaseKey(any(), any(), any())).thenReturn(Optional.empty());
        when(releaseRepository.save(any())).thenAnswer(invocation -> {
            DomainFederationRelease release = invocation.getArgument(0);
            release.onInsert();
            return release;
        });
        when(sourceRepository.findByFederationRelease_IdOrderBySourceKey(any())).thenReturn(List.of());
        when(contextRepository.findByFederationRelease_IdOrderByContextKey(any())).thenReturn(List.of());
        when(relationshipRepository.findByFederationRelease_IdOrderByRelationshipKey(any())).thenReturn(List.of());
        when(contractRepository.findByFederationRelease_IdOrderByContractKey(any())).thenReturn(List.of());
        when(resolutionRepository.findByFederationRelease_IdOrderByResolutionKey(any())).thenReturn(List.of());
        when(dryRunService.dryRun(request)).thenReturn(new DomainFederationIngestDryRunResponse(
                "praxis.domain-federation-ingest-dry-run/v0.1",
                true,
                true,
                0,
                new DomainFederationValidationReport(true, 0, 0, List.of()),
                List.of()));

        var response = service.ingest(request);

        assertThat(response.persisted()).isTrue();
        assertThat(response.valid()).isTrue();
        assertThat(response.releaseKey()).startsWith("domain-federation:tenant-a:dev:");
        assertThat(response.itemCount()).isEqualTo(2);
        ArgumentCaptor<List<DomainSource>> sources = ArgumentCaptor.captor();
        ArgumentCaptor<List<DomainContext>> contexts = ArgumentCaptor.captor();
        verify(sourceRepository).saveAll(sources.capture());
        verify(contextRepository).saveAll(contexts.capture());
        assertThat(sources.getValue()).extracting(DomainSource::getSourceKey).containsExactly("operations-service");
        assertThat(contexts.getValue()).extracting(DomainContext::getContextKey).containsExactly("operations");
    }

    @Test
    void doesNotPersistInvalidEnvelope() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService dryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationReleaseRepository releaseRepository = mock(DomainFederationReleaseRepository.class);
        DomainSourceRepository sourceRepository = mock(DomainSourceRepository.class);
        DomainContextRepository contextRepository = mock(DomainContextRepository.class);
        DomainContextRelationshipRepository relationshipRepository = mock(DomainContextRelationshipRepository.class);
        DomainContractRepository contractRepository = mock(DomainContractRepository.class);
        DomainResolutionRepository resolutionRepository = mock(DomainResolutionRepository.class);
        DomainFederationIngestionService service = new DomainFederationIngestionService(
                validator,
                dryRunService,
                releaseRepository,
                sourceRepository,
                contextRepository,
                relationshipRepository,
                contractRepository,
                resolutionRepository,
                new ObjectMapper());
        DomainFederationValidationRequest request = validRequest();
        when(validator.validate(request)).thenReturn(new DomainFederationValidationReport(false, 1, 0, List.of()));

        var response = service.ingest(request);

        assertThat(response.persisted()).isFalse();
        assertThat(response.valid()).isFalse();
        verify(releaseRepository, never()).save(any());
        verify(sourceRepository, never()).saveAll(any());
        verify(contextRepository, never()).saveAll(any());
        verify(relationshipRepository, never()).saveAll(any());
        verify(contractRepository, never()).saveAll(any());
        verify(resolutionRepository, never()).saveAll(any());
        verify(dryRunService, never()).dryRun(any());
    }

    private DomainFederationValidationRequest validRequest() {
        return new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(new DomainFederationSource(
                        "operations-service",
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
                        "operations-service",
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
                List.of(),
                List.of(),
                List.of());
    }
}
