package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;

@Tag("unit")
class DomainFederationReleaseServiceTest {

    @Test
    void listsReleasesByScopeStatusAndLimit() {
        DomainFederationReleaseRepository repository = mock(DomainFederationReleaseRepository.class);
        DomainFederationReleaseService service = new DomainFederationReleaseService(repository, new ObjectMapper());
        DomainFederationRelease newer = release("release-new", "tenant-a", "dev", "candidate", "2026-04-23T16:00:00Z");
        DomainFederationRelease older = release("release-old", "tenant-a", "dev", "candidate", "2026-04-22T16:00:00Z");
        when(repository.findByFilters("tenant-a", "dev", "candidate")).thenReturn(List.of(newer, older));

        var response = service.releases("tenant-a", "dev", "candidate", 1);

        assertThat(response).singleElement().satisfies(release -> {
            assertThat(release.releaseKey()).isEqualTo("release-new");
            assertThat(release.status()).isEqualTo("candidate");
        });
    }

    @Test
    void returnsValidationReportForReleaseKeyAndScope() {
        DomainFederationReleaseRepository repository = mock(DomainFederationReleaseRepository.class);
        DomainFederationReleaseService service = new DomainFederationReleaseService(repository, new ObjectMapper());
        DomainFederationRelease release = release("release-1", "tenant-a", "dev", "candidate", "2026-04-23T16:00:00Z");
        release.setValidationReport("{\"valid\":true,\"errorCount\":0}");
        when(repository.findByReleaseKeyAndOptionalScope("release-1", "tenant-a", "dev"))
                .thenReturn(java.util.Optional.of(release));

        var response = service.validation("release-1", "tenant-a", "dev");

        assertThat(response.releaseKey()).isEqualTo("release-1");
        assertThat(response.validationReport().path("valid").asBoolean()).isTrue();
        assertThat(response.validationReport().path("errorCount").asInt()).isZero();
    }

    @Test
    void rejectsMissingValidationRelease() {
        DomainFederationReleaseRepository repository = mock(DomainFederationReleaseRepository.class);
        DomainFederationReleaseService service = new DomainFederationReleaseService(repository, new ObjectMapper());
        when(repository.findByReleaseKeyAndOptionalScope("missing", "tenant-a", "dev"))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.validation("missing", "tenant-a", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Domain federation release not found: missing");
    }

    @Test
    void activatesCandidateAndSupersedesPreviousActiveRelease() {
        DomainFederationReleaseRepository repository = mock(DomainFederationReleaseRepository.class);
        DomainFederationReleaseService service = new DomainFederationReleaseService(repository, new ObjectMapper());
        DomainFederationRelease candidate = release("release-candidate", "tenant-a", "dev", "candidate", "2026-04-23T16:00:00Z");
        DomainFederationRelease active = release("release-active", "tenant-a", "dev", "active", "2026-04-22T16:00:00Z");
        when(repository.findByReleaseKeyAndOptionalScope("release-candidate", "tenant-a", "dev"))
                .thenReturn(java.util.Optional.of(candidate));
        when(repository.findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc("tenant-a", "dev", "active"))
                .thenReturn(List.of(active));
        when(repository.save(argThat(release -> release != null && "release-active".equals(release.getReleaseKey()))))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(argThat(release -> release != null && "release-candidate".equals(release.getReleaseKey()))))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.activate("release-candidate", "tenant-a", "dev");

        assertThat(response.status()).isEqualTo("active");
        assertThat(response.activatedAt()).isNotNull();
        assertThat(active.getStatus()).isEqualTo("superseded");
        verify(repository).save(active);
        verify(repository).save(candidate);
    }

    @Test
    void rejectsActivationWhenReleaseIsNotCandidate() {
        DomainFederationReleaseRepository repository = mock(DomainFederationReleaseRepository.class);
        DomainFederationReleaseService service = new DomainFederationReleaseService(repository, new ObjectMapper());
        DomainFederationRelease active = release("release-active", "tenant-a", "dev", "active", "2026-04-23T16:00:00Z");
        when(repository.findByReleaseKeyAndOptionalScope("release-active", "tenant-a", "dev"))
                .thenReturn(java.util.Optional.of(active));

        assertThatThrownBy(() -> service.activate("release-active", "tenant-a", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Only candidate federation releases can be activated");
    }

    private DomainFederationRelease release(
            String releaseKey,
            String tenantId,
            String environment,
            String status,
            String createdAt) {
        return DomainFederationRelease.builder()
                .id(UUID.randomUUID())
                .releaseKey(releaseKey)
                .tenantId(tenantId)
                .environment(environment)
                .status(status)
                .payloadHash("hash-" + releaseKey)
                .createdBy("system")
                .createdAt(Instant.parse(createdAt))
                .validationReport("{}")
                .sourceReleaseIds("[]")
                .build();
    }
}
