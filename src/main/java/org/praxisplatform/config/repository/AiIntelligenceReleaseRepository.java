package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiIntelligenceRelease;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiIntelligenceReleaseRepository extends JpaRepository<AiIntelligenceRelease, UUID> {
    Optional<AiIntelligenceRelease> findByTenantIdAndEnvironmentAndReleaseId(
            String tenantId, String environment, String releaseId);
    Optional<AiIntelligenceRelease> findByTenantIdAndEnvironmentAndStatus(
            String tenantId, String environment, String status);
    List<AiIntelligenceRelease> findAllByTenantIdAndEnvironmentAndStatus(
            String tenantId, String environment, String status);
}

