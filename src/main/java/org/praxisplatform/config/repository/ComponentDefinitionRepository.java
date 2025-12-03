package org.praxisplatform.config.repository;

import java.util.List;

import org.praxisplatform.config.domain.ComponentDefinition;
import org.praxisplatform.config.projection.ComponentDefinitionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ComponentDefinitionRepository extends JpaRepository<ComponentDefinition, String> {

    @Query(value = """
        SELECT
            e.id,
            e.description,
            e.json_schema as jsonSchema,
            (1 - (e.embedding <=> CAST(:vector AS vector))) as similarityScore
        FROM component_definition e
        ORDER BY e.embedding <=> CAST(:vector AS vector)
        LIMIT :limit
    """, nativeQuery = true)
    List<ComponentDefinitionProjection> findByVectorSimilarity(@Param("vector") String vector,
                                                               @Param("limit") int limit);
}
