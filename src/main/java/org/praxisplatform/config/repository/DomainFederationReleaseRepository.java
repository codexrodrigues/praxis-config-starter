package org.praxisplatform.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainFederationReleaseRepository extends JpaRepository<DomainFederationRelease, UUID> {

    Optional<DomainFederationRelease> findByTenantIdAndEnvironmentAndReleaseKey(
            String tenantId,
            String environment,
            String releaseKey);

    Optional<DomainFederationRelease> findFirstByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
            String tenantId,
            String environment,
            String status);

    List<DomainFederationRelease> findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
            String tenantId,
            String environment,
            String status);

    @Query("""
        select r from DomainFederationRelease r
        where r.status = 'active'
          and (:tenantId is null or :tenantId = '' or r.tenantId = :tenantId)
          and (:environment is null or :environment = '' or r.environment = :environment)
        order by coalesce(r.activatedAt, r.createdAt) desc, r.createdAt desc
    """)
    List<DomainFederationRelease> findActiveByOptionalScope(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment);

    @Query("""
        select r from DomainFederationRelease r
        where (:tenantId is null or :tenantId = '' or r.tenantId = :tenantId)
          and (:environment is null or :environment = '' or r.environment = :environment)
          and (:status is null or :status = '' or r.status = :status)
        order by r.createdAt desc
    """)
    List<DomainFederationRelease> findByFilters(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("status") String status);

    @Query("""
        select r from DomainFederationRelease r
        where r.releaseKey = :releaseKey
          and (:tenantId is null or :tenantId = '' or r.tenantId = :tenantId)
          and (:environment is null or :environment = '' or r.environment = :environment)
    """)
    Optional<DomainFederationRelease> findByReleaseKeyAndOptionalScope(
            @Param("releaseKey") String releaseKey,
            @Param("tenantId") String tenantId,
            @Param("environment") String environment);
}
