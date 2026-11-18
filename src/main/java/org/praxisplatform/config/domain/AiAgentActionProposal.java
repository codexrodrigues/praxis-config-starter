package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Proposta persistida de execucao ou edicao governada preparada pelo assistente operacional antes da
 * confirmacao humana explicita (com suporte a TTL, {@code If-Match} e {@code Idempotency-Key}).
 */
@Entity
@Table(name = "ai_agent_action_proposal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentActionProposal {

    @Id
    @Column(name = "proposal_id", nullable = false)
    private UUID proposalId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "tenant_id", nullable = false, length = 160)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 160)
    private String userId;

    @Column(name = "environment", nullable = false, length = 64)
    private String environment;

    @Column(name = "thread_id")
    private UUID threadId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "entity_key", nullable = false, length = 160)
    private String entityKey;

    @Column(name = "resource_key", length = 200)
    private String resourceKey;

    @Column(name = "resource_path", nullable = false, length = 255)
    private String resourcePath;

    @Column(name = "entity_id", length = 160)
    private String entityId;

    @Column(name = "action_id", nullable = false, length = 160)
    private String actionId;

    @Column(name = "action_title", nullable = false, length = 255)
    private String actionTitle;

    @Column(name = "action_description", length = 1024)
    private String actionDescription;

    @Column(name = "operation_kind", nullable = false, length = 64)
    private String operationKind;

    @Column(name = "form_mode", nullable = false, length = 32)
    private String formMode;

    @Column(name = "http_method", nullable = false, length = 16)
    private String httpMethod;

    @Column(name = "submit_url", nullable = false, length = 512)
    private String submitUrl;

    @Column(name = "schema_url", length = 512)
    private String schemaUrl;

    @Column(name = "risk_level", nullable = false, length = 32)
    private String riskLevel;

    @Column(name = "confirmation_required", nullable = false)
    private boolean confirmationRequired;

    @Column(name = "has_input_form", nullable = false)
    private boolean hasInputForm;

    @Column(name = "organization_id", length = 64)
    private String organizationId;

    @Column(name = "organization_label", length = 160)
    private String organizationLabel;

    @Column(name = "record_title", length = 255)
    private String recordTitle;

    @Column(name = "record_subtitle", length = 255)
    private String recordSubtitle;

    @Column(name = "record_lifecycle_state", length = 120)
    private String recordLifecycleState;

    @Column(name = "expected_version_tag", length = 255)
    private String expectedVersionTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AiAgentActionProposalStatus status;

    @Column(name = "blocked_code", length = 80)
    private String blockedCode;

    @Column(name = "impediment_note", length = 1024)
    private String impedimentNote;

    @Column(name = "prefill_json", columnDefinition = "TEXT")
    private String prefillJson;

    @Column(name = "input_fields_json", columnDefinition = "TEXT")
    private String inputFieldsJson;

    @Column(name = "missing_fields_json", columnDefinition = "TEXT")
    private String missingFieldsJson;

    @Column(name = "idempotency_key", length = 160)
    private String idempotencyKey;

    @Column(name = "execution_receipt_json", columnDefinition = "TEXT")
    private String executionReceiptJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    public void onInsert() {
        if (this.proposalId == null) {
            this.proposalId = UUID.randomUUID();
        }
        if (this.version == null) {
            this.version = 0L;
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.expiresAt == null) {
            this.expiresAt = this.createdAt.plusSeconds(900L);
        }
        if (this.status == null) {
            this.status = AiAgentActionProposalStatus.PENDING;
        }
    }
}
