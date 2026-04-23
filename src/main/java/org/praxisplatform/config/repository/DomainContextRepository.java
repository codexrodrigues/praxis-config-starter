package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainContext;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainContextRepository extends JpaRepository<DomainContext, UUID> {

    Optional<DomainContext> findByFederationRelease_IdAndContextKey(UUID federationReleaseId, String contextKey);

    List<DomainContext> findByFederationRelease_IdOrderByContextKey(UUID federationReleaseId);

    List<DomainContext> findByFederationRelease_IdAndSourceKeyOrderByContextKey(
            UUID federationReleaseId,
            String sourceKey);
}
