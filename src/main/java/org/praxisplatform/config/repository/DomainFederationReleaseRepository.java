package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainFederationReleaseRepository extends JpaRepository<DomainFederationRelease, UUID> {

    Optional<DomainFederationRelease> findByTenantIdAndEnvironmentAndReleaseKey(
            String tenantId,
            String environment,
            String releaseKey);

    Optional<DomainFederationRelease> findFirstByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
            String tenantId,
            String environment,
            String status);

    List<DomainFederationRelease> findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
            String tenantId,
            String environment,
            String status);
}
