package org.praxisplatform.config.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainCatalogRagPublicationState;
import org.praxisplatform.config.domain.DomainCatalogRagPublicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainCatalogRagPublicationStateRepository
        extends JpaRepository<DomainCatalogRagPublicationState, UUID> {

    @Modifying
    @Query(value = """
        insert into domain_catalog_rag_publication_state (
            release_id, lock_version, revision, status, attempt,
            expected_document_count, published_document_count,
            requested_at, updated_at
        ) values (
            :releaseId, 0, 0, 'PENDING', 0, 0, 0,
            current_timestamp, current_timestamp
        ) on conflict (release_id) do nothing
    """, nativeQuery = true)
    int ensureState(@Param("releaseId") UUID releaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DomainCatalogRagPublicationState s where s.releaseId = :releaseId")
    Optional<DomainCatalogRagPublicationState> findForUpdate(@Param("releaseId") UUID releaseId);

    List<DomainCatalogRagPublicationState> findAllByStatusIn(
            Collection<DomainCatalogRagPublicationStatus> statuses);
}
