package org.praxisplatform.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Entity
@Table(name = "component_definition")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComponentDefinition {

    /**
     * Unique selector/ID of the component (e.g., 'praxis-table').
     */
    @Id
    private String id;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "json_schema", columnDefinition = "TEXT")
    private String jsonSchema;

    /**
     * Semantic embedding vector for RAG.
     * Mapped to PostgreSQL 'vector' type.
     */
    @Column(columnDefinition = "vector(768)")
    @ColumnTransformer(write = "?::vector")
    @Convert(converter = VectorConverter.class)
    private List<Float> embedding;
}
