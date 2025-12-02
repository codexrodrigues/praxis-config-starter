package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnTransformer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ui_configuration", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "app_id", "component_id", "scope", "scope_key"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String appId;

    /**
     * API resource path associated with this configuration.
     * Vital for RAG context retrieval.
     */
    @Column(nullable = false)
    private String resourcePath;

    /**
     * Unique identifier of the widget on the screen/page.
     */
    @Column(nullable = false)
    private String componentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;

    /**
     * Key for the scope (e.g., user login) or null if SYSTEM scope.
     */
    private String scopeKey;

    /**
     * The configuration JSON payload.
     */
    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String configJson;

    /**
     * Textual description for semantic search (AI/RAG).
     */
    @Column(columnDefinition = "TEXT")
    private String aiDescription;

    /**
     * Semantic embedding vector of the configuration content/description.
     */
    @Column(columnDefinition = "vector(768)")
    @ColumnTransformer(write = "?::vector")
    @Convert(converter = VectorConverter.class)
    private java.util.List<Float> embedding;
}
