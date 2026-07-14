package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DomainCatalogScopeMigrationContractTest {

    @Test
    void replacesGlobalReleaseKeyUniquenessWithScopedIdentity() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V31__scope_domain_catalog_release_identity.sql"));

        assertThat(migration)
                .contains("drop constraint if exists domain_catalog_release_release_key_key")
                .contains("uk_domain_catalog_release_scope_key")
                .contains("coalesce(tenant_id, '')")
                .contains("coalesce(environment, '')")
                .contains("release_key");
    }
}
