package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidade persistida que representa um turno individual dentro de um {@link AiThread}.
 *
 * <p>O registro controla o ciclo de vida de uma geracao ou operacao AI, incluindo status,
 * timestamps de criacao/atualizacao e expiracao, usando identificador composto
 * {@code (threadId, turnId)}.
 */
@Entity
@Table(name = "ai_turn")
@IdClass(AiTurnId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTurn {

    @Id
    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @Id
    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AiTurnStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Proxima sequencia persistivel do turno. A linha de {@code ai_turn} e bloqueada durante a
     * reserva para manter ordenacao monotônica e segura entre instancias sem consultar
     * {@code max(seq)} a cada evento de progresso.
     */
    @Column(name = "next_event_seq", nullable = false)
    private long nextEventSeq;

    /**
     * Tipo terminal que venceu a corrida do turno, ou {@code null} enquanto novos eventos podem
     * ser anexados. O payload terminal continua pertencendo a {@code ai_turn_event}.
     */
    @Column(name = "terminal_event_type", length = 64)
    private String terminalEventType;

    @PrePersist
    public void onInsert() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.nextEventSeq < 1L) {
            this.nextEventSeq = 1L;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
