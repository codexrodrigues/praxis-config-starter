package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainContract;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainContractRepository extends JpaRepository<DomainContract, UUID> {

    Optional<DomainContract> findByFederationRelease_IdAndContractKey(UUID federationReleaseId, String contractKey);

    List<DomainContract> findByFederationRelease_IdAndProviderContextKeyOrderByContractKey(
            UUID federationReleaseId,
            String providerContextKey);

    List<DomainContract> findByFederationRelease_IdAndConsumerContextKeyOrderByContractKey(
            UUID federationReleaseId,
            String consumerContextKey);
}
