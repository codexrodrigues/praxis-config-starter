package org.praxisplatform.config.persistence;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class ConfigMigrationInventoryTest {
  @TempDir Path directory;
  @Test void rejectsAnExistingArtifactDifferentFromTheLoadedConfigArtifact()throws Exception {
    Path other=Files.writeString(directory.resolve("unrelated.jar"),"not-the-loaded-config");
    assertThatThrownBy(()->ConfigMigrationInventory.fromSelectedArtifact(other))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact selected Config JAR");
    assertThat(Files.readString(other)).isEqualTo("not-the-loaded-config");
  }
  @Test void missingArtifactCannotFallBackToTheCurrentClasspath() {
    assertThatThrownBy(()->ConfigMigrationInventory.fromSelectedArtifact(directory.resolve("absent.jar")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("selected Config artifact");
  }
}
