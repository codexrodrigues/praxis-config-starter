package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationIngestResponse;
import org.praxisplatform.config.dto.DomainFederationIngestDryRunResponse;
import org.praxisplatform.config.dto.DomainFederationIngestPreviewItemResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.service.DomainFederationContractValidator;
import org.praxisplatform.config.service.DomainFederationIngestDryRunService;
import org.praxisplatform.config.service.DomainFederationIngestionService;
import org.praxisplatform.config.service.DomainFederationQueryService;

@Tag("unit")
class DomainFederationControllerTest {

    @Test
    void returnsValidationReportForDryRun() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestionService ingestionService = mock(DomainFederationIngestionService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestionService, queryService);
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
        DomainFederationIngestionService ingestionService = mock(DomainFederationIngestionService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestionService, queryService);
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
        DomainFederationIngestionService ingestionService = mock(DomainFederationIngestionService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestionService, queryService);
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
                List.of("Use only explicit relationships."),
                null,
                null,
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
        DomainFederationIngestionService ingestionService = mock(DomainFederationIngestionService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestionService, queryService);
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
        DomainFederationIngestionService ingestionService = mock(DomainFederationIngestionService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestionService, queryService);
        DomainFederationIngestResponse response = new DomainFederationIngestResponse(
                "praxis.domain-federation-ingest/v0.1",
                false,
                true,
                true,
                UUID.randomUUID(),
                "domain-federation:tenant-a:dev:abc",
                2,
                new DomainFederationValidationReport(true, 0, 0, List.of()),
                List.of());
        when(ingestionService.ingest(argThat(effective ->
                effective != null
                        && "tenant-a".equals(effective.tenantId())
                        && "dev".equals(effective.environment())))).thenReturn(response);

        var entity = controller.ingest(validRequest("tenant-a", "dev"), false, "tenant-a", "dev");

        assertThat(entity.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(entity.getBody()).isSameAs(response);
        verify(ingestionService).ingest(argThat(effective ->
                effective != null
                        && "tenant-a".equals(effective.tenantId())
                        && "dev".equals(effective.environment())));
    }

    @Test
    void returnsUnprocessableEntityWhenPersistentIngestIsInvalid() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationIngestionService ingestionService = mock(DomainFederationIngestionService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller =
                new DomainFederationController(validator, ingestDryRunService, ingestionService, queryService);
        DomainFederationIngestResponse response = new DomainFederationIngestResponse(
                "praxis.domain-federation-ingest/v0.1",
                false,
                false,
                false,
                null,
                null,
                0,
                new DomainFederationValidationReport(false, 1, 0, List.of()),
                List.of());
        when(ingestionService.ingest(validRequest("tenant-a", "dev"))).thenReturn(response);

        var entity = controller.ingest(validRequest("tenant-a", "dev"), false, null, null);

        assertThat(entity.getStatusCode().value()).isEqualTo(422);
        assertThat(entity.getBody()).isSameAs(response);
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
