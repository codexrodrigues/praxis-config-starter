package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnId;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiTurnRepository extends JpaRepository<AiTurn, AiTurnId> {

    Optional<AiTurn> findByThreadIdAndTurnId(UUID threadId, UUID turnId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
            insert into ai_turn (thread_id, turn_id, status, created_at, updated_at, expires_at)
            values (:threadId, :turnId, :status, :now, :now, :expiresAt)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("threadId") UUID threadId,
            @Param("turnId") UUID turnId,
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("update AiTurn t set t.status = :status, t.expiresAt = :expiresAt where t.threadId = :threadId and t.turnId = :turnId")
    int updateStatus(
            @Param("threadId") UUID threadId,
            @Param("turnId") UUID turnId,
            @Param("status") AiTurnStatus status,
            @Param("expiresAt") Instant expiresAt);
}
