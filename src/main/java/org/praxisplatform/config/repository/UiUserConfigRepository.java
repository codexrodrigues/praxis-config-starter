package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiUserConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface UiUserConfigRepository extends JpaRepository<UiUserConfig, UUID> {

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("""
      update UiUserConfig config
         set config.payload = :payload,
             config.tags = :tags,
             config.version = :nextVersion,
             config.etag = :nextEtag,
             config.updatedAt = :updatedAt,
             config.updatedBy = :updatedBy
       where config.id = :id
         and config.etag = :expectedEtag
      """)
  int updateIfCurrent(
      @Param("id") UUID id,
      @Param("payload") String payload,
      @Param("tags") String tags,
      @Param("nextVersion") long nextVersion,
      @Param("expectedEtag") UUID expectedEtag,
      @Param("nextEtag") UUID nextEtag,
      @Param("updatedAt") Instant updatedAt,
      @Param("updatedBy") String updatedBy);

  Optional<UiUserConfig>
      findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdOrderByUpdatedAtDesc(
          String tenantId,
          String componentType,
          String componentId,
          String environment,
          String userId);

  Optional<UiUserConfig>
      findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdOrderByUpdatedAtDesc(
          String tenantId, String componentType, String componentId, String userId);

  Optional<UiUserConfig>
      findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentAndUserIdIsNullOrderByUpdatedAtDesc(
          String tenantId, String componentType, String componentId, String environment);

  Optional<UiUserConfig>
      findTopByTenantIdAndComponentTypeAndComponentIdAndEnvironmentIsNullAndUserIdIsNullOrderByUpdatedAtDesc(
          String tenantId, String componentType, String componentId);
}
