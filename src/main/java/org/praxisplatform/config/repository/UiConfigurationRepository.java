package org.praxisplatform.config.repository;

import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.domain.UiConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UiConfigurationRepository extends JpaRepository<UiConfiguration, Long> {

    Optional<UiConfiguration> findByTenantIdAndAppIdAndComponentIdAndScopeAndScopeKey(
            String tenantId, String appId, String componentId, Scope scope, String scopeKey);

    List<UiConfiguration> findByTenantIdAndAppIdAndResourcePath(
            String tenantId, String appId, String resourcePath);
            
    List<UiConfiguration> findByTenantIdAndAppIdAndComponentId(
            String tenantId, String appId, String componentId);

    /**
     * RAG Search: Find configurations semantically similar to the query vector.
     * Filters by Tenant to ensure isolation.
     */
    @Query(nativeQuery = true, value = "SELECT * FROM ui_configuration WHERE tenant_id = :tenantId ORDER BY embedding <-> cast(:embedding as vector) LIMIT :limit")
    List<UiConfiguration> findByVectorSimilarity(@Param("tenantId") String tenantId, @Param("embedding") String embedding, @Param("limit") int limit);
}
