package org.praxisplatform.config.repository;

import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiLayoutReleaseRepository extends JpaRepository<UiLayoutRelease, UUID> {
  Optional<UiLayoutRelease> findByIdAndTenantIdAndEnvironment(UUID id, String tenantId, String environment);
  Optional<UiLayoutRelease> findBySourceDraftId(UUID sourceDraftId);
}
