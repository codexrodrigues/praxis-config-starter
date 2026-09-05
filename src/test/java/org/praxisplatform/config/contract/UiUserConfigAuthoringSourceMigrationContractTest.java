package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UiUserConfigAuthoringSourceMigrationContractTest {

    @Test
    void migrationAndBaselineOwnTheSameObjectOnlyAuthoringSourceContract() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V61__add_ui_user_config_authoring_source.sql"));
        String baseline = Files.readString(Path.of(
                "src/main/resources/db/baseline/V1__baseline.sql"));

        assertThat(migration)
                .contains("ADD COLUMN IF NOT EXISTS authoring_source JSONB")
                .contains("chk_ui_user_config_authoring_source_object")
                .contains("jsonb_typeof(authoring_source) = 'object'");
        assertThat(baseline)
                .contains("authoring_source JSONB")
                .contains("chk_ui_user_config_authoring_source_object")
                .contains("jsonb_typeof(authoring_source) = 'object'");
    }
}
