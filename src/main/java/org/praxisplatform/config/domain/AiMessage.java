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

@Entity
@Table(name = "ai_message")
@IdClass(AiMessageId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMessage {

    @Id
    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @Id
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Column(name = "token_est")
    private Integer tokenEst;

    @Column(name = "redacted", nullable = false)
    private boolean redacted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onInsert() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
