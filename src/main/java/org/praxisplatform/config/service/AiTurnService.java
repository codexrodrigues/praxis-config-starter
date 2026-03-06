package org.praxisplatform.config.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.repository.AiTurnRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiTurnService {

    @Value("${praxis.ai.memory.turn.timeout-seconds:120}")
    private int turnTimeoutSeconds;

    private final AiTurnRepository turnRepository;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.REQUIRES_NEW)
    public TurnDecision beginTurn(UUID threadId, UUID turnId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(resolveTimeout());
        try {
            int inserted = turnRepository.insertIfAbsent(
                    threadId,
                    turnId,
                    AiTurnStatus.PROCESSING.name(),
                    now,
                    expiresAt);
            if (inserted > 0) {
                return TurnDecision.PROCESS;
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(AiTurnService.class)
                    .warn("[AiTurnService] Failed to reserve turn: {}", ex.getMessage());
        }
        AiTurn existing = turnRepository.findByThreadIdAndTurnId(threadId, turnId).orElse(null);
        if (existing == null) {
            return TurnDecision.PROCESS;
        }
        if (existing.getStatus() == AiTurnStatus.DONE || existing.getStatus() == AiTurnStatus.CANCELLED) {
            return TurnDecision.DONE;
        }
        Instant currentExpiry = existing.getExpiresAt();
        if (currentExpiry != null && currentExpiry.isAfter(now)) {
            return TurnDecision.IN_PROGRESS;
        }
        int claimed = turnRepository.claimIfExpired(
                threadId,
                turnId,
                now,
                AiTurnStatus.PROCESSING,
                expiresAt,
                AiTurnStatus.DONE,
                AiTurnStatus.CANCELLED);
        if (claimed > 0) {
            return TurnDecision.PROCESS;
        }
        AiTurn refreshed = turnRepository.findByThreadIdAndTurnId(threadId, turnId).orElse(null);
        if (refreshed == null) {
            return TurnDecision.PROCESS;
        }
        if (refreshed.getStatus() == AiTurnStatus.DONE || refreshed.getStatus() == AiTurnStatus.CANCELLED) {
            return TurnDecision.DONE;
        }
        Instant refreshedExpiry = refreshed.getExpiresAt();
        if (refreshedExpiry != null && refreshedExpiry.isAfter(Instant.now())) {
            return TurnDecision.IN_PROGRESS;
        }
        return TurnDecision.PROCESS;
    }

    /**
     * Reserves the turn row for stream event FK integrity without marking it as actively in progress.
     * The immediate expiry allows the first orchestrator beginTurn() call to acquire processing ownership.
     */
    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.REQUIRES_NEW)
    public void reserveTurnForStreaming(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        Instant now = Instant.now();
        turnRepository.insertIfAbsent(
                threadId,
                turnId,
                AiTurnStatus.PROCESSING.name(),
                now,
                now);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.REQUIRES_NEW)
    public void completeTurn(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        AiTurn existing = turnRepository.findByThreadIdAndTurnId(threadId, turnId).orElse(null);
        if (existing != null && existing.getStatus() == AiTurnStatus.CANCELLED) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(resolveTimeout());
        turnRepository.updateStatus(threadId, turnId, AiTurnStatus.DONE, expiresAt);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.REQUIRES_NEW)
    public void expireTurn(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        AiTurn existing = turnRepository.findByThreadIdAndTurnId(threadId, turnId).orElse(null);
        if (existing != null && existing.getStatus() == AiTurnStatus.CANCELLED) {
            return;
        }
        Instant now = Instant.now();
        turnRepository.updateStatus(threadId, turnId, AiTurnStatus.PROCESSING, now);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, propagation = Propagation.REQUIRES_NEW)
    public void cancelTurn(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        Instant now = Instant.now();
        turnRepository.updateStatus(threadId, turnId, AiTurnStatus.CANCELLED, now);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public AiTurnStatus findTurnStatus(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return null;
        }
        return turnRepository.findByThreadIdAndTurnId(threadId, turnId)
                .map(AiTurn::getStatus)
                .orElse(null);
    }

    private int resolveTimeout() {
        return Math.max(turnTimeoutSeconds, 15);
    }

    public enum TurnDecision {
        PROCESS,
        IN_PROGRESS,
        DONE
    }
}
