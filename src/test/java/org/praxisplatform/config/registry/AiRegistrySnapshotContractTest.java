package org.praxisplatform.config.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@Tag("unit")
class AiRegistrySnapshotContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void classpathSnapshotIncludesExecutableAuthoringManifests() throws IOException {
        JsonNode snapshot = readSnapshot();
        JsonNode components = snapshot.path("components");

        assertThat(components.isObject()).isTrue();
        assertThat(components.size()).isGreaterThanOrEqualTo(100);
        assertThat(authoringManifestCount(components)).isGreaterThanOrEqualTo(90);

        for (String componentId : requiredAuthoringComponents()) {
            JsonNode manifest = components.path(componentId).path("authoringManifest");
            assertThat(manifest.isObject())
                    .as("%s must expose authoringManifest in registry snapshot", componentId)
                    .isTrue();
            assertThat(manifest.path("operations").size())
                    .as("%s authoringManifest operations", componentId)
                    .isGreaterThan(0);
            assertThat(manifest.path("editableTargets").size())
                    .as("%s authoringManifest editableTargets", componentId)
                    .isGreaterThan(0);
            assertThat(manifest.path("validators").size())
                    .as("%s authoringManifest validators", componentId)
                    .isGreaterThan(0);
        }
    }

    private JsonNode readSnapshot() throws IOException {
        ClassPathResource resource = new ClassPathResource("ai-registry/registry-snapshot.json");
        assertThat(resource.exists()).isTrue();
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    private long authoringManifestCount(JsonNode components) {
        long count = 0;
        var fields = components.fields();
        while (fields.hasNext()) {
            if (fields.next().getValue().path("authoringManifest").isObject()) {
                count++;
            }
        }
        return count;
    }

    private List<String> requiredAuthoringComponents() {
        return List.of(
                "praxis-table",
                "praxis-dynamic-form",
                "praxis-list",
                "praxis-dynamic-fields",
                "praxis-tabs",
                "praxis-stepper",
                "praxis-expansion",
                "pdx-cron-builder",
                "praxis-files-upload",
                "praxis-rich-content",
                "praxis-chart",
                "praxis-dialog",
                "praxis-settings-panel",
                "praxis-metadata-editor",
                "praxis-editorial-forms",
                "praxis-manual-form",
                "praxis-visual-builder",
                "praxis-table-rule-builder",
                "praxis-crud",
                "praxis-page-builder");
    }
}
