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

import java.util.List;

@Entity
@Table(name = "api_metadata", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"path", "method"})
})
public class ApiMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private String method;

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
