package org.praxisplatform.config.repository;

import org.praxisplatform.config.domain.ComponentDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComponentDefinitionRepository extends JpaRepository<ComponentDefinition, String> {

    /**
     * Finds components similar to the given vector.
     * Uses PostgreSQL pgvector operator <-> (L2 distance).
     * 
     * @param embedding The query vector.
     * @param limit Max results.
     * @return List of matching definitions.
     */
    @Query(nativeQuery = true, value = "SELECT * FROM component_definition ORDER BY embedding <-> cast(:embedding as vector) LIMIT :limit")
    List<ComponentDefinition> findByVectorSimilarity(@Param("embedding") String embedding, @Param("limit") int limit);
}
