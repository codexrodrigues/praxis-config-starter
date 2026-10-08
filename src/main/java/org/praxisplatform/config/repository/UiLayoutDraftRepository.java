package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiLayoutDraftRepository extends JpaRepository<UiLayoutDraft, UUID> {
  Optional<UiLayoutDraft> findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(
      UUID id, String tenantId, String environment, String rootComponentType, String rootComponentId);

  Optional<UiLayoutDraft> findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
      String tenantId, String environment, String rootComponentType, String rootComponentId,
      String createdBy, String creationIdempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select draft from UiLayoutDraft draft where draft.id = :id")
  Optional<UiLayoutDraft> findForUpdateById(@Param("id") UUID id);
}
