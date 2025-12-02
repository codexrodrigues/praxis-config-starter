package org.praxisplatform.config.repository;

import org.praxisplatform.config.domain.ApiMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiMetadataRepository extends JpaRepository<ApiMetadata, Long> {

    Optional<ApiMetadata> findByPathAndMethod(String path, String method);
}
