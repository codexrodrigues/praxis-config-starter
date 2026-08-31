package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DomainCatalogRagPublicationMigrationContractTest {

    @Test
    void persistsOneRecoverablePublicationLifecyclePerImmutableRelease() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V60__create_domain_catalog_rag_publication_state.sql"));

        assertThat(migration)
                .contains("release_id UUID PRIMARY KEY REFERENCES domain_catalog_release(id) ON DELETE CASCADE")
                .contains("revision BIGINT NOT NULL DEFAULT 0")
                .contains("status VARCHAR(24) NOT NULL")
                .contains("failure_kind VARCHAR(80)")
                .contains("retryable BOOLEAN")
                .contains("'PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'")
                .contains("idx_domain_catalog_rag_publication_recovery");
    }
}
