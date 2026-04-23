package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationIngestDryRunResponse;
import org.praxisplatform.config.dto.DomainFederationIngestResponse;
import org.praxisplatform.config.dto.DomainFederationIngestPreviewItemResponse;
import org.praxisplatform.config.dto.DomainFederationReleaseResponse;
import org.praxisplatform.config.dto.DomainFederationReleaseValidationResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.service.DomainFederationContractValidator;
import org.praxisplatform.config.service.DomainFederationIngestDryRunService;
import org.praxisplatform.config.service.DomainFederationIngestPersistenceService;
import org.praxisplatform.config.service.DomainFederationQueryService;
import org.praxisplatform.config.service.DomainFederationReleaseService;

@Tag("unit")
class DomainFederationControllerTest {

    @Test
    void returnsValidationReportForDryRun() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationValidationRequest request = validRequest("tenant-a", "dev");
        DomainFederationValidationReport report = new DomainFederationValidationReport(true, 0, 0, List.of());
        when(validator.validate(request)).thenReturn(report);

        var response = controller.dryRun(request, null, null);

        assertThat(response.getBody()).isSameAs(report);
        verify(validator).validate(request);
    }

    @Test
    void usesTenantAndEnvironmentHeadersWhenRequestOmitsScope() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationValidationReport report = new DomainFederationValidationReport(true, 0, 0, List.of());
        when(validator.validate(argThat(request ->
                request != null
                        && "tenant-a".equals(request.tenantId())
                        && "dev".equals(request.environment())))).thenReturn(report);

        var response = controller.dryRun(validRequest(null, null), "tenant-a", "dev");

        assertThat(response.getBody()).isSameAs(report);
        verify(validator).validate(argThat(request ->
                request != null
                        && "tenant-a".equals(request.tenantId())
                        && "dev".equals(request.environment())));
    }

    @Test
    void delegatesFederatedContextQueryToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationContextQueryResponse response = new DomainFederationContextQueryResponse(
                "praxis.domain-federation-context/v0.1",
                "tenant-a",
                "dev",
                null,
                null,
                "veiculo",
                "operations",
                "node",
                null,
                "depends_on",
                20,
                true,
                "catalog_projection_fallback",
                List.of("Use only explicit relationships."),
                null,
                null,
                List.of(),
                List.of(),
                List.of());
        when(queryService.context(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "depends_on",
                "veiculo",
                20,
                new DomainFederationRetrievalPolicyOptions("authoring", 0.85d, null, null))).thenReturn(response);

        var entity = controller.context(
                null,
                null,
                "node",
                "operations",
                null,
                "depends_on",
                "veiculo",
                20,
                "authoring",
                0.85d,
                null,
                null,
                "tenant-a",
                "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(queryService).context(
                null,
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                "depends_on",
                "veiculo",
                20,
                new DomainFederationRetrievalPolicyOptions("authoring", 0.85d, null, null));
    }

    @Test
    void delegatesDryRunIngestToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationValidationRequest request = validRequest(null, null);
        DomainFederationIngestDryRunResponse response = new DomainFederationIngestDryRunResponse(
                "praxis.domain-federation-ingest-dry-run/v0.1",
                true,
                true,
                1,
                new DomainFederationValidationReport(true, 0, 0, List.of()),
                List.of(new DomainFederationIngestPreviewItemResponse(
                        "operations",
                        "operations-service",
                        "operations-service",
                        "Operations",
                        true,
                        null,
                        null)));
        when(ingestDryRunService.dryRun(argThat(effective ->
                effective != null
                        && "tenant-a".equals(effective.tenantId())
                        && "dev".equals(effective.environment())))).thenReturn(response);

        var entity = controller.ingest(request, true, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(ingestDryRunService).dryRun(argThat(effective ->
                effective != null
                        && "tenant-a".equals(effective.tenantId())
                        && "dev".equals(effective.environment())));
    }

    @Test
    void delegatesPersistentIngestToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationIngestResponse response = new DomainFederationIngestResponse(
                "praxis.domain-federation-ingest/v0.1",
                false,
                true,
                null,
                "domain-federation:tenant-a:dev:v1",
                "candidate",
                "abc",
                null,
                new DomainFederationValidationReport(true, 0, 0, List.of()));
        when(ingestPersistenceService.ingestCandidate(argThat(effective ->
                effective != null
                        && "tenant-a".equals(effective.tenantId())
                        && "dev".equals(effective.environment())))).thenReturn(response);

        var entity = controller.ingest(validRequest("tenant-a", "dev"), false, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(ingestPersistenceService).ingestCandidate(argThat(effective ->
                effective != null
                        && "tenant-a".equals(effective.tenantId())
                        && "dev".equals(effective.environment())));
    }

    @Test
    void delegatesReleaseListToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        List<DomainFederationReleaseResponse> releases = List.of(new DomainFederationReleaseResponse(
                null,
                "domain-federation:tenant-a:dev:v1",
                "tenant-a",
                "dev",
                "candidate",
                "abc",
                "system",
                null,
                null));
        when(releaseService.releases("tenant-a", "dev", "candidate", 10)).thenReturn(releases);

        var entity = controller.releases("candidate", 10, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(releases);
        verify(releaseService).releases("tenant-a", "dev", "candidate", 10);
    }

    @Test
    void delegatesReleaseValidationToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationReleaseValidationResponse validation = new DomainFederationReleaseValidationResponse(
                null,
                "domain-federation:tenant-a:dev:v1",
                "tenant-a",
                "dev",
                "candidate",
                "abc",
                null,
                null);
        when(releaseService.validation("domain-federation:tenant-a:dev:v1", "tenant-a", "dev")).thenReturn(validation);

        var entity = controller.validation("domain-federation:tenant-a:dev:v1", "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(validation);
        verify(releaseService).validation("domain-federation:tenant-a:dev:v1", "tenant-a", "dev");
    }

    @Test
    void delegatesReleaseActivationToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestPersistenceService ingestPersistenceService = mock(DomainFederationIngestPersistenceService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationReleaseService releaseService = mock(DomainFederationReleaseService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestPersistenceService, queryService, releaseService);
        DomainFederationReleaseResponse activated = new DomainFederationReleaseResponse(
                null,
                "domain-federation:tenant-a:dev:v1",
                "tenant-a",
                "dev",
                "active",
                "abc",
                "system",
                null,
                null);
        when(releaseService.activate("domain-federation:tenant-a:dev:v1", "tenant-a", "dev")).thenReturn(activated);

        var entity = controller.activate("domain-federation:tenant-a:dev:v1", "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(activated);
        verify(releaseService).activate("domain-federation:tenant-a:dev:v1", "tenant-a", "dev");
    }

    private DomainFederationValidationRequest validRequest(String tenantId, String environment) {
        return new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                tenantId,
                environment,
                List.of(new DomainFederationSource(
                        "operations-service",
                        "microservice",
                        "operations-service",
                        "Operations",
                        tenantId,
                        environment,
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
                        tenantId,
                        environment,
                        "active",
                        "domain-catalog:operations:v1",
                        null)),
                List.of(),
                List.of(),
                List.of());
    }
}
