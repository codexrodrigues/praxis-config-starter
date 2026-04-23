package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainContextRelationship;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainContextRelationshipRepository extends JpaRepository<DomainContextRelationship, UUID> {

    Optional<DomainContextRelationship> findByFederationRelease_IdAndRelationshipKey(
            UUID federationReleaseId,
            String relationshipKey);

    List<DomainContextRelationship> findByFederationRelease_IdAndSourceContextKeyOrderByRelationshipKey(
            UUID federationReleaseId,
            String sourceContextKey);

    List<DomainContextRelationship> findByFederationRelease_IdAndTargetContextKeyOrderByRelationshipKey(
            UUID federationReleaseId,
            String targetContextKey);
}
