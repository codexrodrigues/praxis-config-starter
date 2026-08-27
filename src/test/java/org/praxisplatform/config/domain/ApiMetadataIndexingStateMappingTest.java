package org.praxisplatform.config.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApiMetadataIndexingStateMappingTest {

    @Test
    void mapsOptimisticLockToCanonicalMigrationColumn() throws Exception {
        Field field = ApiMetadataIndexingState.class.getDeclaredField("lockVersion");

        assertThat(field.isAnnotationPresent(Version.class)).isTrue();
        assertThat(field.getAnnotation(Column.class).name()).isEqualTo("lock_version");
    }
}
