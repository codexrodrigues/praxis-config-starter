package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnTransformer;
import org.praxisplatform.config.projection.ApiMetadataCandidateEvidence;

import java.util.List;

/**
 * Fonte canonica persistida para metadados operacionais de endpoints publicados pela plataforma.
 *
 * <p>O registro consolida metodo, path, schemas, parametros, payload bruto e embedding vetorial
 * do endpoint, servindo tanto para busca estruturada quanto para indexacao e recuperacao semantica
 * em fluxos RAG.
 */
@Entity
@Table(name = "api_metadata", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "environment", "service_key", "release_id", "path", "method"})
})
public class ApiMetadata implements ApiMetadataCandidateEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private String method;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId = "GLOBAL";

    @Column(nullable = false)
    private String environment = "default";

    @Column(name = "service_key", nullable = false)
    private String serviceKey = "default";

    @Column(name = "release_id", nullable = false)
    private String releaseId = "v1";

    @Column(name = "release_version")
    private String releaseVersion;

    @Column(name = "generated_at")
    private String generatedAt;

    @Column
    private String tags;

    @Column
    private String summary;

    @Column
    private String description;

    @Column(name = "operation_id")
    private String operationId;

    @Column(name = "request_schema", columnDefinition = "TEXT")
    private String requestSchema;

    @Column(name = "response_schema", columnDefinition = "TEXT")
    private String responseSchema;

    @Column(name = "parameters", columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    @Column(columnDefinition = "vector(768)")
    @ColumnTransformer(write = "?::vector")
    @Convert(converter = VectorConverter.class)
    private List<Float> embedding;

    public ApiMetadata() {
    }

    public ApiMetadata(String path, String method, String tags, String summary, String description, String operationId,
                       String requestSchema, String responseSchema, String parameters, String rawJson, List<Float> embedding) {
        this.path = path;
        this.method = method;
        this.tags = tags;
        this.summary = summary;
        this.description = description;
        this.operationId = operationId;
        this.requestSchema = requestSchema;
        this.responseSchema = responseSchema;
        this.parameters = parameters;
        this.rawJson = rawJson;
        this.embedding = embedding;
    }

    public ApiMetadata(String path, String method, String tenantId, String environment, String serviceKey,
                       String releaseId, String releaseVersion, String generatedAt, String tags, String summary,
                       String description, String operationId, String requestSchema, String responseSchema,
                       String parameters, String rawJson, List<Float> embedding) {
        this(path, method, tags, summary, description, operationId, requestSchema, responseSchema, parameters, rawJson, embedding);
        this.tenantId = tenantId;
        this.environment = environment;
        this.serviceKey = serviceKey;
        this.releaseId = releaseId;
        this.releaseVersion = releaseVersion;
        this.generatedAt = generatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(String releaseId) {
        this.releaseId = releaseId;
    }

    public String getReleaseVersion() {
        return releaseVersion;
    }

    public void setReleaseVersion(String releaseVersion) {
        this.releaseVersion = releaseVersion;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getRequestSchema() {
        return requestSchema;
    }

    public void setRequestSchema(String requestSchema) {
        this.requestSchema = requestSchema;
    }

    public String getResponseSchema() {
        return responseSchema;
    }

    public void setResponseSchema(String responseSchema) {
        this.responseSchema = responseSchema;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public List<Float> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Float> embedding) {
        this.embedding = embedding;
    }
}
