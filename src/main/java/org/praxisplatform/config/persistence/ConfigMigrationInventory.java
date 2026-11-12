package org.praxisplatform.config.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.flywaydb.core.api.migration.JavaMigration;

/** Discovers Java migrations from the exact Config artifact selected by an explicit storage installer. */
public final class ConfigMigrationInventory {
  private ConfigMigrationInventory() {}

  /** SQL-extracting installers must supply these migrations to the same Flyway configuration. */
  public static List<JavaMigration> fromSelectedArtifact(Path artifact) {
    try {
      Path selected=artifact.toRealPath();
      Path owning=Path.of(ConfigMigrationInventory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
      if(!selected.equals(owning)||!java.nio.file.Files.isRegularFile(selected))
        throw new IllegalArgumentException("Installer and migrations must use the exact selected Config JAR");
      var migrations=new ArrayList<JavaMigration>();
      try(var jar=new ZipFile(selected.toFile())) {
        for(var entry:jar.stream().filter(e->e.getName().matches("db/migration/[VR][A-Za-z0-9_]+\\.class"))
            .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
          String name=entry.getName().replace('/','.').replaceAll("\\.class$", "");
          Class<?> type=Class.forName(name,true,ConfigMigrationInventory.class.getClassLoader());
          Path origin=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
          if(!selected.equals(origin)||!JavaMigration.class.isAssignableFrom(type))
            throw new IllegalArgumentException("Migration origin differs from selected Config JAR");
          migrations.add((JavaMigration)type.getDeclaredConstructor().newInstance());
        }
      }
      return List.copyOf(migrations);
    } catch(IllegalArgumentException invalid){throw invalid;}
    catch(Exception unavailable){throw new IllegalStateException("Cannot discover migrations from selected Config artifact",unavailable);}
  }
}
