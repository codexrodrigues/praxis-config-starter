package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Feedback estruturado e redigido associado a uma observação do assistente.
 */
@Entity
@Table(name = "ai_assistant_observation_feedback")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAssistantObservationFeedback {

    @Id
    @Column(name = "feedback_id", nullable = false)
    private UUID feedbackId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_id", nullable = false)
    private AiAssistantObservation observation;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(length = 128)
    private String environment;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(nullable = false, length = 32)
    private String rating;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "comment_preview", columnDefinition = "text")
    private String commentPreview;

    @Column(name = "safe_metadata", columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String safeMetadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void onInsert() {
        if (feedbackId == null) {
            feedbackId = UUID.randomUUID();
        }
        if (safeMetadata == null || safeMetadata.isBlank()) {
            safeMetadata = "{}";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
