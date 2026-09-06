package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("smoke")
class GovernedColorPaletteMigrationConstraintTest {

    @Test
    void paletteMigrationExtendsTheCurrentRuleAndMaterializationConstraints() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V62__add_governed_color_palette_materialization.sql"));

        assertThat(migration).contains("'design_token_palette'");
        assertThat(migration).contains("'design_token_catalog'");
        assertThat(migration).contains("'governed-color-palette'");
        assertThat(migration).contains("'backend_determination'");
        assertThat(migration).contains("ck_domain_rule_materialization_color_palette_type");
        assertThat(migration).contains("ck_domain_rule_materialization_color_palette_payload");
        assertThat(migration).contains("jsonb_array_length(materialized_payload -> 'entries') > 0");
        assertThat(migration).contains("materialized_payload ->> 'familyKey'");
        assertThat(migration).contains("uq_domain_rule_materialization_applied_palette_variant");
    }
}
