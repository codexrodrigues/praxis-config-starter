package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.projection.AiRegistryTemplateSearchProjection;
import org.praxisplatform.config.projection.ComponentDefinitionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiRegistryRepository extends JpaRepository<AiRegistry, UUID> {

  Optional<AiRegistry> findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
      String registryType, String registryKey, String componentType, Scope scope, String scopeKey);

  @Query(
      value =
          """
      SELECT
          registry_key as id,
          payload #>> '{componentDefinition,description}' as description,
          payload #>> '{componentDefinition,jsonSchema}' as jsonSchema,
          (1 - (embedding <=> CAST(:vector AS vector))) as similarityScore
      FROM ai_registry
      WHERE registry_type = :registryType
        AND embedding IS NOT NULL
      ORDER BY embedding <=> CAST(:vector AS vector)
      LIMIT :limit
      """,
      nativeQuery = true)
  List<ComponentDefinitionProjection> findComponentDefinitionsByVectorSimilarity(
      @Param("registryType") String registryType,
      @Param("vector") String vector,
      @Param("limit") int limit);

  @Query(
      value =
          """
      SELECT
          registry_key as componentId,
          payload ->> 'aiDescription' as aiDescription,
          payload ->> 'configJson' as configJson,
          (1 - (embedding <=> CAST(:vector AS vector))) as similarityScore
      FROM ai_registry
      WHERE registry_type = :registryType
        AND embedding IS NOT NULL
        AND (:componentId IS NULL OR :componentId = '' OR registry_key = :componentId)
      ORDER BY embedding <=> CAST(:vector AS vector)
      LIMIT :limit
      """,
      nativeQuery = true)
  List<AiRegistryTemplateSearchProjection> findTemplatesByVectorSimilarity(
      @Param("registryType") String registryType,
      @Param("vector") String vector,
      @Param("componentId") String componentId,
      @Param("limit") int limit);

  @Query(
      value =
          """
      SELECT
          registry_key as componentId,
          payload ->> 'aiDescription' as aiDescription,
          payload ->> 'configJson' as configJson,
          (1 - (embedding <=> CAST(:vector AS vector))) as similarityScore
      FROM ai_registry
      WHERE registry_type = :registryType
        AND embedding IS NOT NULL
        AND registry_key LIKE :registryKeyPrefix
      ORDER BY embedding <=> CAST(:vector AS vector)
      LIMIT :limit
      """,
      nativeQuery = true)
  List<AiRegistryTemplateSearchProjection> findTemplatesByVectorSimilarityAndPrefix(
      @Param("registryType") String registryType,
      @Param("vector") String vector,
      @Param("registryKeyPrefix") String registryKeyPrefix,
      @Param("limit") int limit);

  long countByRegistryType(String registryType);

  boolean existsByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
      String registryType, String registryKey, String componentType, Scope scope, String scopeKey);
}
