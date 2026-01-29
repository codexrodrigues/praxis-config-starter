package org.praxisplatform.config.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.repository.AiTurnRepository;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        if (existing.getStatus() == AiTurnStatus.DONE) {
            return TurnDecision.DONE;
        }
        Instant currentExpiry = existing.getExpiresAt();
        if (currentExpiry != null && currentExpiry.isAfter(now)) {
            return TurnDecision.IN_PROGRESS;
        }
        turnRepository.updateStatus(threadId, turnId, AiTurnStatus.PROCESSING, expiresAt);
        return TurnDecision.PROCESS;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTurn(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(resolveTimeout());
        turnRepository.updateStatus(threadId, turnId, AiTurnStatus.DONE, expiresAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireTurn(UUID threadId, UUID turnId) {
        if (threadId == null || turnId == null) {
            return;
        }
        Instant now = Instant.now();
        turnRepository.updateStatus(threadId, turnId, AiTurnStatus.PROCESSING, now);
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
