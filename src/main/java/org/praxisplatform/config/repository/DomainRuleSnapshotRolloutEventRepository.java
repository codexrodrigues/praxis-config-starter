package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshotRolloutEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only rollout timeline persistence. */
public interface DomainRuleSnapshotRolloutEventRepository
    extends JpaRepository<DomainRuleSnapshotRolloutEvent, UUID> {
  List<DomainRuleSnapshotRolloutEvent> findByRolloutIdOrderByCreatedAtAsc(UUID rolloutId);
}
