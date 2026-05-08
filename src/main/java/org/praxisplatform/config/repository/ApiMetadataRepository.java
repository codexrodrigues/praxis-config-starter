package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;

import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.projection.ApiMetadataProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiMetadataRepository extends JpaRepository<ApiMetadata, Long> {

    Optional<ApiMetadata> findByPathAndMethod(String path, String method);

    List<ApiMetadata> findAllByOperationIdAndMethod(String operationId, String method);

    @Query(value = """
        SELECT
            e.id,
            e.path,
            e.method,
            e.summary,
            e.tags,
            e.description,
            e.operation_id as operationId,
            e.request_schema as requestSchema,
            e.response_schema as responseSchema,
            e.parameters,
            (1 - (e.embedding <=> CAST(:vector AS vector))) as similarityScore
        FROM api_metadata e
        WHERE (:method IS NULL OR :method = '' OR e.method ILIKE :method)
          AND (:tags IS NULL OR :tags = '' OR e.tags ILIKE CONCAT('%', :tags, '%'))
        ORDER BY e.embedding <=> CAST(:vector AS vector)
        LIMIT :limit
    """, nativeQuery = true)
    List<ApiMetadataProjection> findByVectorSimilarity(@Param("vector") String vector,
                                                       @Param("method") String method,
                                                       @Param("tags") String tags,
                                                       @Param("limit") int limit);

}
