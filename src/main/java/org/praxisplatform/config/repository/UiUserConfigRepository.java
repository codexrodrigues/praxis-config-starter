package org.praxisplatform.config.repository;

import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiUserConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UiUserConfigRepository extends JpaRepository<UiUserConfig, UUID> {

  Optional<UiUserConfig> findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserId(
      String tenantId, String componentType, String componentId, String environment, String userId);

  Optional<UiUserConfig> findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserId(
      String tenantId, String componentType, String componentId, String userId);

  Optional<UiUserConfig> findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdIsNull(
      String tenantId, String componentType, String componentId, String environment);

  Optional<UiUserConfig>
      findByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNull(
          String tenantId, String componentType, String componentId);
}
