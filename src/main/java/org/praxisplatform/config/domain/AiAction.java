package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidade persistida para acoes estruturadas associadas a um turno AI.
 *
 * <p>Essas acoes registram intencoes ou operacoes derivadas da geracao, com payload jsonb ligado
 * ao par {@code (threadId, turnId)} e tipificado por {@code actionType}.
 */
@Entity
@Table(name = "ai_action")
@IdClass(AiActionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAction {

    @Id
    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @Id
    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Id
    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    @Column(name = "payload", columnDefinition = "jsonb")
    @org.hibernate.annotations.ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onInsert() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
