package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class DomainCatalogScopePostgresMigrationTest {

    @Test
    void shouldMigrateAnEphemeralPostgresDatabaseAndEnforceScopedReleaseIdentity() throws Exception {
        assumeTrue(
                "true".equalsIgnoreCase(System.getenv("POSTGRES_TEST_EPHEMERAL")),
                "The migration proof only runs against an explicitly ephemeral PostgreSQL database");

        String url = requiredEnvironment("POSTGRES_TEST_URL");
        String user = requiredEnvironment("POSTGRES_TEST_USER");
        String password = requiredEnvironment("POSTGRES_TEST_PASSWORD");

        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            assertMigrationApplied(connection);
            assertScopedIndexInstalled(connection);

            String releaseKey = "praxis-service:human-resources.folhas-pagamento:migration-proof-"
                    + UUID.randomUUID();
            insertRelease(connection, releaseKey, "tenant-a", "dev");
            insertRelease(connection, releaseKey, "tenant-b", "dev");

            assertThat(countReleases(connection, releaseKey)).isEqualTo(2);
            assertThatThrownBy(() -> insertRelease(connection, releaseKey, "tenant-a", "dev"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_domain_catalog_release_scope_key");
            connection.rollback();
        }
    }

    private void assertMigrationApplied(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                from flyway_schema_history
                where version = '31' and success = true
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private void assertScopedIndexInstalled(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select indexdef
                from pg_indexes
                where schemaname = current_schema()
                  and indexname = 'uk_domain_catalog_release_scope_key'
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .contains("UNIQUE INDEX")
                        .contains("COALESCE(tenant_id, ''::character varying)")
                        .contains("COALESCE(environment, ''::character varying)")
                        .contains("release_key");
            }
        }
    }

    private void insertRelease(Connection connection, String releaseKey, String tenantId, String environment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into domain_catalog_release (
                    id,
                    release_key,
                    schema_version,
                    tenant_id,
                    environment,
                    raw_payload
                ) values (?, ?, ?, ?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, releaseKey);
            statement.setString(3, "praxis.domain-catalog/v0.2");
            statement.setString(4, tenantId);
            statement.setString(5, environment);
            statement.setString(6, "{}");
            statement.executeUpdate();
        }
    }

    private long countReleases(Connection connection, String releaseKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from domain_catalog_release where release_key = ?")) {
            statement.setString(1, releaseKey);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("environment variable %s", name).isNotBlank();
        return value;
    }
}
