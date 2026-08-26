package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.praxisplatform.config.domain.ApiMetadataIndexingState;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiMetadataIndexingStateRepository extends JpaRepository<ApiMetadataIndexingState, Long> {

    @Modifying
    @Query(value = """
        insert into api_metadata_indexing_state (
            tenant_id, environment, service_key, release_id, revision, status, attempt,
            expected_document_count, legacy_indexed_document_count, published_document_count,
            requested_at, updated_at, lock_version
        ) values (
            :tenantId, :environment, :serviceKey, :releaseId, 0, 'PENDING', 0,
            0, 0, 0, current_timestamp, current_timestamp, 0
        ) on conflict (tenant_id, environment, service_key, release_id) do nothing
    """, nativeQuery = true)
    int ensureState(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("serviceKey") String serviceKey,
            @Param("releaseId") String releaseId);

    Optional<ApiMetadataIndexingState> findByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s from ApiMetadataIndexingState s
        where s.tenantId = :tenantId
          and s.environment = :environment
          and s.serviceKey = :serviceKey
          and s.releaseId = :releaseId
    """)
    Optional<ApiMetadataIndexingState> findForUpdate(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("serviceKey") String serviceKey,
            @Param("releaseId") String releaseId);

    List<ApiMetadataIndexingState> findAllByStatusIn(Collection<ApiMetadataIndexingStatus> statuses);
}
