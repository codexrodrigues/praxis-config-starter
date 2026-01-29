package org.praxisplatform.config.repository;

import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiAction;
import org.praxisplatform.config.domain.AiActionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiActionRepository extends JpaRepository<AiAction, AiActionId> {
    Optional<AiAction> findFirstByThreadIdAndTurnId(UUID threadId, UUID turnId);
}
