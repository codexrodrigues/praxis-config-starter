package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnId;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;

@Repository
public interface AiTurnRepository extends JpaRepository<AiTurn, AiTurnId> {

    Optional<AiTurn> findByThreadIdAndTurnId(UUID threadId, UUID turnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.transaction.annotation.Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    @Query("select t from AiTurn t where t.threadId = :threadId and t.turnId = :turnId")
    Optional<AiTurn> findByThreadIdAndTurnIdForUpdate(
            @Param("threadId") UUID threadId,
            @Param("turnId") UUID turnId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
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
    @org.springframework.transaction.annotation.Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    @Query("update AiTurn t set t.status = :status, t.expiresAt = :expiresAt where t.threadId = :threadId and t.turnId = :turnId")
    int updateStatus(
            @Param("threadId") UUID threadId,
            @Param("turnId") UUID turnId,
            @Param("status") AiTurnStatus status,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    @Query("""
            update AiTurn t
            set t.status = :status, t.expiresAt = :expiresAt
            where t.threadId = :threadId
              and t.turnId = :turnId
              and (t.expiresAt is null or t.expiresAt <= :now)
              and t.status <> :doneStatus
              and t.status <> :cancelledStatus
            """)
    int claimIfExpired(
            @Param("threadId") UUID threadId,
            @Param("turnId") UUID turnId,
            @Param("now") Instant now,
            @Param("status") AiTurnStatus status,
            @Param("expiresAt") Instant expiresAt,
            @Param("doneStatus") AiTurnStatus doneStatus,
            @Param("cancelledStatus") AiTurnStatus cancelledStatus);
}
