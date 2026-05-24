package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Linha agregada e segura para triagem governada de uma tentativa de uso do assistente AI.
 */
@Entity
@Table(name = "ai_assistant_observation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAssistantObservation {

    @Id
    @Column(name = "observation_id", nullable = false)
    private UUID observationId;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(length = 128)
    private String environment;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(nullable = false, length = 64)
    private String surface;

    @Column(name = "component_id", length = 255)
    private String componentId;

    @Column(name = "component_type", length = 64)
    private String componentType;

    @Column(name = "route_key", length = 255)
    private String routeKey;

    @Column(name = "variant_id", length = 128)
    private String variantId;

    @Column(name = "schema_hash", length = 128)
    private String schemaHash;

    @Column(name = "contract_version", length = 64)
    private String contractVersion;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "client_turn_id")
    private UUID clientTurnId;

    @Column(name = "thread_id")
    private UUID threadId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "stream_id")
    private UUID streamId;

    @Column(name = "prompt_hash", nullable = false, length = 128)
    private String promptHash;

    @Column(name = "prompt_preview", columnDefinition = "text")
    private String promptPreview;

    @Column(name = "prompt_length")
    private Integer promptLength;

    @Column(name = "prompt_redacted", nullable = false)
    private boolean promptRedacted;

    @Column(name = "admission_outcome", nullable = false, length = 64)
    private String admissionOutcome;

    @Column(name = "terminal_outcome", length = 64)
    private String terminalOutcome;

    @Column(name = "quality_outcome", nullable = false, length = 64)
    private String qualityOutcome;

    @Column(name = "error_category", length = 64)
    private String errorCategory;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message_preview", columnDefinition = "text")
    private String errorMessagePreview;

    @Column(length = 64)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(name = "llm_call_count", nullable = false)
    private int llmCallCount;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "token_estimate")
    private Integer tokenEstimate;

    @Column(name = "cost_estimate_micros")
    private Long costEstimateMicros;

    @Column(name = "safe_metadata", columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String safeMetadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onInsert() {
        Instant now = Instant.now();
        if (observationId == null) {
            observationId = UUID.randomUUID();
        }
        if (admissionOutcome == null || admissionOutcome.isBlank()) {
            admissionOutcome = "captured";
        }
        if (qualityOutcome == null || qualityOutcome.isBlank()) {
            qualityOutcome = "unresolved";
        }
        if (safeMetadata == null || safeMetadata.isBlank()) {
            safeMetadata = "{}";
        }
        promptRedacted = true;
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
