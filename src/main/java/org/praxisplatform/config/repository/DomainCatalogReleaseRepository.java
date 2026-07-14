package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainCatalogReleaseRepository extends JpaRepository<DomainCatalogRelease, UUID> {

    @Query("""
        select r from DomainCatalogRelease r
        where r.releaseKey = :releaseKey
          and ((:tenantId is null and r.tenantId is null) or r.tenantId = :tenantId)
          and ((:environment is null and r.environment is null) or r.environment = :environment)
    """)
    Optional<DomainCatalogRelease> findByReleaseKeyAndScope(
            @Param("releaseKey") String releaseKey,
            @Param("tenantId") String tenantId,
            @Param("environment") String environment);

    @Query("""
        select r from DomainCatalogRelease r
        where (:serviceKey is null or :serviceKey = '' or r.serviceKey = :serviceKey)
          and (:resourceKey is null or :resourceKey = '' or r.resourceKey = :resourceKey)
          and ((:tenantId is null and r.tenantId is null) or r.tenantId = :tenantId)
          and ((:environment is null and r.environment is null) or r.environment = :environment)
        order by coalesce(r.generatedAt, r.createdAt) desc, r.createdAt desc
    """)
    List<DomainCatalogRelease> findLatest(
            @Param("serviceKey") String serviceKey,
            @Param("resourceKey") String resourceKey,
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            Pageable pageable);
}
