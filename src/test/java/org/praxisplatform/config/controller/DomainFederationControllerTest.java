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
import org.praxisplatform.config.dto.DomainFederationIngestPreviewItemResponse;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.service.DomainFederationContractValidator;
import org.praxisplatform.config.service.DomainFederationIngestDryRunService;
import org.praxisplatform.config.service.DomainFederationQueryService;

@Tag("unit")
class DomainFederationControllerTest {

    @Test
    void returnsValidationReportForDryRun() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller = new DomainFederationController(validator, ingestDryRunService, queryService);
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
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller = new DomainFederationController(validator, ingestDryRunService, queryService);
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
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller = new DomainFederationController(validator, ingestDryRunService, queryService);
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
                20)).thenReturn(response);

        var entity = controller.context(
                null,
                null,
                "node",
                "operations",
                null,
                "depends_on",
                "veiculo",
                20,
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
                20);
    }

    @Test
    void delegatesDryRunIngestToService() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller = new DomainFederationController(validator, ingestDryRunService, queryService);
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
    void rejectsPersistentIngestForNow() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationIngestDryRunService ingestDryRunService = mock(DomainFederationIngestDryRunService.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationController controller = new DomainFederationController(validator, ingestDryRunService, queryService);

        var entity = controller.ingest(validRequest("tenant-a", "dev"), false, "tenant-a", "dev");

        assertThat(entity.getStatusCode().is4xxClientError()).isTrue();
        assertThat(entity.getBody()).isEqualTo("Persistent federation ingest is not implemented yet. Use dryRun=true.");
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
