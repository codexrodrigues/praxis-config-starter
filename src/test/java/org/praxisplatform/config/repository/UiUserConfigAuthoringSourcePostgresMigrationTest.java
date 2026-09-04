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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class UiUserConfigAuthoringSourcePostgresMigrationTest {

    @Test
    void installsIdempotentObjectOnlySourceContractOnRealPostgres() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V61__add_ui_user_config_authoring_source.sql"));
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setCleanDataDirectory(true)
                .setRegisterShutdownHook(false)
                .start();
                Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table ui_user_config (id uuid primary key, payload jsonb not null)");
            statement.execute(migration);
            statement.execute(migration);

            statement.executeUpdate("""
                    insert into ui_user_config (id, payload, authoring_source)
                    values (
                        '123e4567-e89b-12d3-a456-426614174060',
                        '{"widgets":[]}',
                        '{"schemaVersion":"praxis.ui-authoring-source/v1"}'
                    )
                    """);

            assertThat(authoringSourceType(statement)).isEqualTo("object");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    update ui_user_config
                    set authoring_source = '[]'
                    where id = '123e4567-e89b-12d3-a456-426614174060'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_ui_user_config_authoring_source_object");
        }
    }

    private String authoringSourceType(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "select jsonb_typeof(authoring_source) from ui_user_config")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
