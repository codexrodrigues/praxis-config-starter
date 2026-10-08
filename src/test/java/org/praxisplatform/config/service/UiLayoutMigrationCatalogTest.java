package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Static catalogue check only: no datasource, connection or migration execution. */
@Tag("unit")
class UiLayoutMigrationCatalogTest {
  @Test
  void layoutMigrationsDoNotCollideWithExistingMainHistory() throws Exception {
    Path directory = Path.of("src/main/resources/db/migration");
    var versions = new HashMap<MigrationVersion, String>();
    try (var paths = Files.list(directory)) {
      for (Path path : paths.toList()) {
        String name = path.getFileName().toString();
        if (!name.startsWith("V") || !name.endsWith(".sql") || !name.contains("__")) continue;
        var version = MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
        assertThat(versions.putIfAbsent(version, name))
            .as("Duplicate Flyway version %s from %s", version, name).isNull();
      }
    }
    assertThat(versions.get(MigrationVersion.fromVersion("63")))
        .isEqualTo("V63__preserve_domain_rule_application_history.sql");
    assertThat(versions.get(MigrationVersion.fromVersion("64")))
        .isEqualTo("V64__create_ui_layout_lifecycle.sql");
    assertThat(versions.get(MigrationVersion.fromVersion("65")))
        .isEqualTo("V65__add_ui_layout_workspace_idempotency.sql");
  }
}
