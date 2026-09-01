package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "domain_catalog_rag_publication_state")
public class DomainCatalogRagPublicationState {

    @Id
    @Column(name = "release_id", nullable = false)
    private UUID releaseId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DomainCatalogRagPublicationStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "expected_document_count", nullable = false)
    private long expectedDocumentCount;

    @Column(name = "published_document_count", nullable = false)
    private long publishedDocumentCount;

    @Column(name = "failure_kind", length = 80)
    private String failureKind;

    @Column
    private Boolean retryable;

    @Column(name = "retry_after")
    private Instant retryAfter;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID releaseId) { this.releaseId = releaseId; }
    public long getLockVersion() { return lockVersion; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public DomainCatalogRagPublicationStatus getStatus() { return status; }
    public void setStatus(DomainCatalogRagPublicationStatus status) { this.status = status; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public long getExpectedDocumentCount() { return expectedDocumentCount; }
    public void setExpectedDocumentCount(long expectedDocumentCount) { this.expectedDocumentCount = expectedDocumentCount; }
    public long getPublishedDocumentCount() { return publishedDocumentCount; }
    public void setPublishedDocumentCount(long publishedDocumentCount) { this.publishedDocumentCount = publishedDocumentCount; }
    public String getFailureKind() { return failureKind; }
    public void setFailureKind(String failureKind) { this.failureKind = failureKind; }
    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }
    public Instant getRetryAfter() { return retryAfter; }
    public void setRetryAfter(Instant retryAfter) { this.retryAfter = retryAfter; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
