package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiLayoutDefinitionRepository extends JpaRepository<UiLayoutDefinition, UUID> {
  Optional<UiLayoutDefinition> findByTenantIdAndEnvironmentAndComponentTypeAndComponentId(
      String tenantId, String environment, String componentType, String componentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select definition from UiLayoutDefinition definition
      where definition.tenantId = :tenantId and definition.environment = :environment
        and definition.componentType = :componentType and definition.componentId = :componentId
      """)
  Optional<UiLayoutDefinition> findForUpdateByTarget(
      @Param("tenantId") String tenantId, @Param("environment") String environment,
      @Param("componentType") String componentType, @Param("componentId") String componentId);
}
