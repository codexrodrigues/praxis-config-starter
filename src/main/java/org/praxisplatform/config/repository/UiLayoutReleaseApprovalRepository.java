package org.praxisplatform.config.repository;

import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutReleaseApproval;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiLayoutReleaseApprovalRepository extends JpaRepository<UiLayoutReleaseApproval, UUID> {}
