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
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "ai_turn_event")
@IdClass(AiTurnEventId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTurnEvent {

    @Id
    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @Id
    @Column(name = "turn_id", nullable = false)
    private UUID turnId;

    @Id
    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "environment", length = 64)
    private String environment;

    @Column(name = "stream_id", nullable = false)
    private UUID streamId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
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
