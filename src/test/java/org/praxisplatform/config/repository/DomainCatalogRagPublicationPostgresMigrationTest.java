package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DomainCatalogRagPublicationPostgresMigrationTest {

    @Test
    void installsPublicationLifecycleConstraintsAndReleaseCascadeOnRealPostgres() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .start();
                Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table domain_catalog_release (id uuid primary key)");
            statement.execute(Files.readString(Path.of(
                    "src/main/resources/db/migration/V60__create_domain_catalog_rag_publication_state.sql")));

            UUID releaseId = UUID.randomUUID();
            statement.executeUpdate("insert into domain_catalog_release (id) values ('" + releaseId + "')");
            statement.executeUpdate("""
                    insert into domain_catalog_rag_publication_state (
                        release_id, status, requested_at, updated_at
                    ) values (
                        '%s', 'PENDING', current_timestamp, current_timestamp
                    )
                    """.formatted(releaseId));

            assertThat(count(statement, "domain_catalog_rag_publication_state")).isEqualTo(1L);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    update domain_catalog_rag_publication_state
                    set status = 'UNKNOWN'
                    where release_id = '%s'
                    """.formatted(releaseId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_domain_catalog_rag_publication_status");

            statement.executeUpdate("delete from domain_catalog_release where id = '" + releaseId + "'");
            assertThat(count(statement, "domain_catalog_rag_publication_state")).isZero();
        }
    }

    private long count(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
