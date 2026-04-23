package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;

@Tag("unit")
class DomainFederationIngestDryRunServiceTest {

    @Test
    void combinesValidationAndPreviewForEachContext() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationIngestDryRunService service = new DomainFederationIngestDryRunService(validator, queryService);
        DomainFederationValidationRequest request = request();
        DomainFederationValidationReport validation = new DomainFederationValidationReport(true, 0, 1, List.of());
        DomainFederationContextQueryResponse preview = new DomainFederationContextQueryResponse(
                "praxis.domain-federation-context/v0.1",
                "tenant-a",
                "dev",
                "operations-service",
                null,
                "Operations",
                "operations",
                "node",
                null,
                null,
                12,
                false,
                List.of("Use explicit relationships."),
                null,
                null,
                List.of());
        when(validator.validate(request)).thenReturn(validation);
        when(queryService.context(
                "operations-service",
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                null,
                "Operations",
                12)).thenReturn(preview);

        var response = service.dryRun(request);

        assertThat(response.valid()).isTrue();
        assertThat(response.previewCount()).isEqualTo(1);
        assertThat(response.validation()).isSameAs(validation);
        assertThat(response.previews()).singleElement().satisfies(item -> {
            assertThat(item.previewAvailable()).isTrue();
            assertThat(item.contextPreview()).isSameAs(preview);
            assertThat(item.serviceKey()).isEqualTo("operations-service");
        });
        verify(validator).validate(request);
        verify(queryService).context(
                "operations-service",
                null,
                "tenant-a",
                "dev",
                "node",
                "operations",
                null,
                null,
                "Operations",
                12);
    }

    @Test
    void returnsPreviewErrorWhenSourceCannotBeResolved() {
        DomainFederationContractValidator validator = mock(DomainFederationContractValidator.class);
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        DomainFederationIngestDryRunService service = new DomainFederationIngestDryRunService(validator, queryService);
        DomainFederationValidationRequest request = new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(),
                List.of(new DomainFederationContext(
                        "operations",
                        "missing-source",
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
        when(validator.validate(request)).thenReturn(new DomainFederationValidationReport(false, 1, 0, List.of()));

        var response = service.dryRun(request);

        assertThat(response.previews()).singleElement().satisfies(item -> {
            assertThat(item.previewAvailable()).isFalse();
            assertThat(item.previewError()).contains("preview requires a known source");
        });
        verify(validator).validate(request);
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
                List.of(),
                List.of(),
                List.of());
    }
}
