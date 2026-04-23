package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainSourceRepository extends JpaRepository<DomainSource, UUID> {

    Optional<DomainSource> findByFederationRelease_IdAndSourceKey(UUID federationReleaseId, String sourceKey);

    List<DomainSource> findByFederationRelease_IdOrderBySourceKey(UUID federationReleaseId);

    List<DomainSource> findByTenantIdAndEnvironmentAndStatusOrderByUpdatedAtDesc(
            String tenantId,
            String environment,
            String status);
}
