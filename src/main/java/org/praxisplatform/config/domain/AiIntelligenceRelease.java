package org.praxisplatform.config.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "ai_intelligence_release")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AiIntelligenceRelease {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "release_id", nullable = false) private String releaseId;
    @Column(name = "tenant_id", nullable = false) private String tenantId;
    @Column(nullable = false) private String environment;
    @Column(nullable = false) private String status;
    @Column(name = "expected_component_count", nullable = false) private int expectedComponentCount;
    @Column(name = "expected_component_hash", nullable = false) private String expectedComponentHash;
    @Column(name = "expected_template_count", nullable = false) private int expectedTemplateCount;
    @Column(name = "expected_template_hash", nullable = false) private String expectedTemplateHash;
    @Column(name = "expected_chunk_count", nullable = false) private long expectedChunkCount;
    @Column(name = "embedding_profile", nullable = false) private String embeddingProfile;
    @Column(name = "observed_component_count") private Integer observedComponentCount;
    @Column(name = "observed_component_hash") private String observedComponentHash;
    @Column(name = "component_corpus_release_id") private String componentCorpusReleaseId;
    @Column(name = "observed_template_count") private Integer observedTemplateCount;
    @Column(name = "observed_template_hash") private String observedTemplateHash;
    @Column(name = "observed_chunk_count") private Long observedChunkCount;
    @Column(name = "producer_ref") private String producerRef;
    @Column(name = "failure_reason") private String failureReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "activated_at") private Instant activatedAt;

    @PrePersist void create() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
