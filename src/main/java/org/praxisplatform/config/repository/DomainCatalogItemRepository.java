package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainCatalogItemRepository extends JpaRepository<DomainCatalogItem, UUID> {

    @Modifying
    void deleteByRelease(DomainCatalogRelease release);

    long countByRelease(DomainCatalogRelease release);

    @Query("""
        select i from DomainCatalogItem i
        where i.release.releaseKey = :releaseKey
          and (:itemType is null or :itemType = '' or i.itemType = :itemType)
          and (:contextKey is null or :contextKey = '' or i.contextKey = :contextKey)
          and (:nodeType is null or :nodeType = '' or i.nodeType = :nodeType)
          and (:query is null or :query = '' or lower(i.searchableText) like lower(concat('%', :query, '%')))
        order by i.itemType asc, i.itemKey asc
    """)
    List<DomainCatalogItem> search(
            @Param("releaseKey") String releaseKey,
            @Param("itemType") String itemType,
            @Param("contextKey") String contextKey,
            @Param("nodeType") String nodeType,
            @Param("query") String query,
            Pageable pageable);
}
