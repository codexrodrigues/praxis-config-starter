package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UiLayoutReleaseMemberRepository extends JpaRepository<UiLayoutReleaseMember, UUID> {
  List<UiLayoutReleaseMember> findByReleaseIdOrderByMemberOrder(UUID releaseId);
}
