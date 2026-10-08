package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutReleaseReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiLayoutReleaseReviewRepository extends JpaRepository<UiLayoutReleaseReview, UUID> {
  Optional<UiLayoutReleaseReview> findByReleaseId(UUID releaseId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select review from UiLayoutReleaseReview review where review.releaseId = :releaseId")
  Optional<UiLayoutReleaseReview> findForUpdateByReleaseId(@Param("releaseId") UUID releaseId);
}
