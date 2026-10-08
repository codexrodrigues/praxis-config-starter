package org.praxisplatform.config.repository;

import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiLayoutRevisionRepository extends JpaRepository<UiLayoutRevision, UUID> {
  @Query("select max(revision.revisionNumber) from UiLayoutRevision revision where revision.definitionId = :definitionId")
  Long findMaximumRevisionNumber(@Param("definitionId") UUID definitionId);
}
