package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.AiMessage;
import org.praxisplatform.config.domain.AiMessageId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiMessageRepository extends JpaRepository<AiMessage, AiMessageId> {

    boolean existsByThreadIdAndTurnIdAndRole(UUID threadId, UUID turnId, String role);

    List<AiMessage> findByThreadIdAndTurnIdAndRoleOrderBySeqDesc(UUID threadId, UUID turnId, String role);

    boolean existsByThreadIdAndTurnId(UUID threadId, UUID turnId);

    long countByThreadId(UUID threadId);

    List<AiMessage> findByThreadIdAndSeqLessThanEqualOrderBySeqAsc(UUID threadId, Integer seq);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update AiMessage m set m.redacted = true, m.content = null where m.threadId = :threadId and m.seq <= :seq")
    int redactMessagesUpToSeq(@Param("threadId") UUID threadId, @Param("seq") Integer seq);

    @Query("select max(m.seq) from AiMessage m where m.threadId = :threadId")
    Integer findMaxSeqByThreadId(@Param("threadId") UUID threadId);

    @Query("select m from AiMessage m where m.threadId = :threadId order by m.seq desc")
    List<AiMessage> findRecentByThreadId(@Param("threadId") UUID threadId, Pageable pageable);
}
