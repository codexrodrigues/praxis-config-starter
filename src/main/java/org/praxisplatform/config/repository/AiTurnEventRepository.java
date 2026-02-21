package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiTurnEvent;
import org.praxisplatform.config.domain.AiTurnEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiTurnEventRepository extends JpaRepository<AiTurnEvent, AiTurnEventId> {

    Optional<AiTurnEvent> findFirstByStreamIdOrderBySeqAsc(UUID streamId);

    Optional<AiTurnEvent> findFirstByStreamIdOrderBySeqDesc(UUID streamId);

    List<AiTurnEvent> findByStreamIdOrderBySeqAsc(UUID streamId);

    List<AiTurnEvent> findByStreamIdAndSeqGreaterThanOrderBySeqAsc(UUID streamId, long seq);

    Optional<AiTurnEvent> findByStreamIdAndEventId(UUID streamId, UUID eventId);

    Optional<AiTurnEvent> findByEventId(UUID eventId);

    Optional<AiTurnEvent> findFirstByThreadIdAndTurnIdOrderBySeqAsc(UUID threadId, UUID turnId);

    Optional<AiTurnEvent> findFirstByThreadIdAndTurnIdOrderBySeqDesc(UUID threadId, UUID turnId);

    @Query("select max(e.seq) from AiTurnEvent e where e.threadId = :threadId and e.turnId = :turnId")
    Long findMaxSeq(@Param("threadId") UUID threadId, @Param("turnId") UUID turnId);
}
