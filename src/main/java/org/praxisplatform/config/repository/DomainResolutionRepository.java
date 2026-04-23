package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainResolution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainResolutionRepository extends JpaRepository<DomainResolution, UUID> {

    Optional<DomainResolution> findByFederationRelease_IdAndResolutionKey(UUID federationReleaseId, String resolutionKey);

    List<DomainResolution> findByFederationRelease_IdAndSourceContextKeyOrderByResolutionKey(
            UUID federationReleaseId,
            String sourceContextKey);

    List<DomainResolution> findByFederationRelease_IdAndTargetContextKeyOrderByResolutionKey(
            UUID federationReleaseId,
            String targetContextKey);

    List<DomainResolution> findByFederationRelease_IdOrderByResolutionKey(UUID federationReleaseId);
}
