package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidade persistida que ancora uma conversa AI por tenant, usuario e alvo funcional.
 *
 * <p>O thread identifica o contexto macro da interacao, incluindo componente, rota, variante,
 * hash de schema e ultimo ETag de configuracao observado, permitindo retomar sessoes e detectar
 * drift entre conversa e configuracao vigente.
 */
@Entity
@Table(name = "ai_thread")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiThread {

    @Id
    @Column(name = "thread_id", nullable = false)
    private UUID threadId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "environment", length = 64)
    private String environment;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "component_type", nullable = false, length = 64)
    private String componentType;

    @Column(name = "component_id", nullable = false, length = 255)
    private String componentId;

    @Column(name = "route_key", length = 255)
    private String routeKey;

    @Column(name = "title", length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AiThreadStatus status;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "schema_hash", length = 128)
    private String schemaHash;

    @Column(name = "variant_id", length = 128)
    private String variantId;

    @Column(name = "last_config_etag", length = 128)
    private String lastConfigEtag;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    public void onInsert() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastUsedAt = now;
        if (this.status == null) {
            this.status = AiThreadStatus.ACTIVE;
        }
        if (this.summary == null) {
            this.summary = "";
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.lastUsedAt = Instant.now();
    }
}
