package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "api_metadata_indexing_state", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "environment", "service_key", "release_id"})
})
public class ApiMetadataIndexingState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String environment;

    @Column(name = "service_key", nullable = false)
    private String serviceKey;

    @Column(name = "release_id", nullable = false)
    private String releaseId;

    @Column(nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ApiMetadataIndexingStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "expected_document_count", nullable = false)
    private long expectedDocumentCount;

    @Column(name = "legacy_indexed_document_count", nullable = false)
    private long legacyIndexedDocumentCount;

    @Column(name = "published_document_count", nullable = false)
    private long publishedDocumentCount;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_message", length = 320)
    private String failureMessage;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public long getLockVersion() { return lockVersion; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public ApiMetadataIndexingStatus getStatus() { return status; }
    public void setStatus(ApiMetadataIndexingStatus status) { this.status = status; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public long getExpectedDocumentCount() { return expectedDocumentCount; }
    public void setExpectedDocumentCount(long expectedDocumentCount) { this.expectedDocumentCount = expectedDocumentCount; }
    public long getLegacyIndexedDocumentCount() { return legacyIndexedDocumentCount; }
    public void setLegacyIndexedDocumentCount(long legacyIndexedDocumentCount) { this.legacyIndexedDocumentCount = legacyIndexedDocumentCount; }
    public long getPublishedDocumentCount() { return publishedDocumentCount; }
    public void setPublishedDocumentCount(long publishedDocumentCount) { this.publishedDocumentCount = publishedDocumentCount; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
