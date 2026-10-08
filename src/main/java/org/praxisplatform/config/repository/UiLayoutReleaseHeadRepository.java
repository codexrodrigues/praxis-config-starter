package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiLayoutReleaseHeadRepository extends JpaRepository<UiLayoutReleaseHead, UUID> {
  Optional<UiLayoutReleaseHead> findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(
      String tenantId, String environment, String rootComponentType, String rootComponentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select head from UiLayoutReleaseHead head
      where head.tenantId = :tenantId
        and head.environment = :environment
        and head.rootComponentType = :rootComponentType
        and head.rootComponentId = :rootComponentId
      """)
  Optional<UiLayoutReleaseHead> findForUpdate(
      @Param("tenantId") String tenantId,
      @Param("environment") String environment,
      @Param("rootComponentType") String rootComponentType,
      @Param("rootComponentId") String rootComponentId);
}
