package org.praxisplatform.config.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringValidatorRegistry;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.springframework.core.io.ClassPathResource;

@Tag("unit")
class AiRegistrySnapshotContractTest {

    private static final String EXPECTED_SNAPSHOT_HASH =
            "c03a37fbcf332f00f63b27ea93230b9d9968d9ed738ce8c27a5a142f8b013f55";
    private static final String EXPECTED_VERSION = "1.0.0";
    private static final String EXPECTED_GENERATED_AT = "2026-07-22T13:53:29.145Z";
    private static final int EXPECTED_COMPONENT_COUNT = 105;
    private static final int EXPECTED_AUTHORING_MANIFEST_COUNT = 95;
    private static final int EXPECTED_CHUNKED_COMPONENT_COUNT = 105;
    private static final int EXPECTED_CHUNK_COUNT = 2395;

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

    @Test
    void classpathSnapshotMatchesCanonicalAngularIngestionRelease() throws Exception {
        byte[] snapshotBytes = readSnapshotBytes();
        JsonNode snapshot = objectMapper.readTree(snapshotBytes);
        JsonNode components = snapshot.path("components");

        assertThat(sha256(snapshotBytes)).isEqualTo(EXPECTED_SNAPSHOT_HASH);
        assertThat(snapshot.path("version").asText()).isEqualTo(EXPECTED_VERSION);
        assertThat(snapshot.path("generatedAt").asText()).isEqualTo(EXPECTED_GENERATED_AT);
        assertThat(RagDocumentIdentity.resolveReleaseId(
                null,
                snapshot.path("version").asText(null),
                snapshot.path("generatedAt").asText(null)))
                .isEqualTo(EXPECTED_VERSION);
        assertThat(components.isObject()).isTrue();
        assertThat(components.size()).isEqualTo(EXPECTED_COMPONENT_COUNT);
        assertThat(authoringManifestCount(components)).isEqualTo(EXPECTED_AUTHORING_MANIFEST_COUNT);
        assertThat(chunkedComponentCount(components)).isEqualTo(EXPECTED_CHUNKED_COMPONENT_COUNT);
        assertThat(chunkCount(components)).isEqualTo(EXPECTED_CHUNK_COUNT);
    }

    @Test
    void classpathSnapshotDoesNotReferenceUnsupportedBackendValidators() throws IOException {
        JsonNode components = readSnapshot().path("components");
        List<String> unsupportedValidators = new ArrayList<>();
        var componentFields = components.fields();
        while (componentFields.hasNext()) {
            var component = componentFields.next();
            String componentId = component.getKey();
            JsonNode manifest = component.getValue().path("authoringManifest");
            if (!manifest.isObject()) {
                continue;
            }
            for (JsonNode operation : manifest.path("operations")) {
                String operationId = operation.path("operationId").asText("");
                for (JsonNode validator : operation.path("validators")) {
                    String validatorId = validator.asText("");
                    if (!validatorId.isBlank() && !AgenticAuthoringValidatorRegistry.supportsValidator(validatorId)) {
                        unsupportedValidators.add(componentId + ":" + operationId + " -> " + validatorId);
                    }
                }
            }
        }

        assertThat(unsupportedValidators).isEmpty();
    }

    @Test
    void classpathSnapshotRespectsCanonicalUtf8ChunkLimit() throws IOException {
        JsonNode components = readSnapshot().path("components");
        List<String> oversized = new ArrayList<>();
        var componentFields = components.fields();
        while (componentFields.hasNext()) {
            var component = componentFields.next();
            for (JsonNode chunk : component.getValue().path("chunks")) {
                int utf8Bytes = chunk.path("content").asText("")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (utf8Bytes > org.praxisplatform.config.service.RegistryIngestionService.MAX_CHUNK_UTF8_BYTES) {
                    oversized.add(component.getKey() + "/" + chunk.path("chunkIndex").asInt()
                            + "=" + utf8Bytes);
                }
            }
        }

        assertThat(oversized).isEmpty();
    }

    private JsonNode readSnapshot() throws IOException {
        return objectMapper.readTree(readSnapshotBytes());
    }

    private byte[] readSnapshotBytes() throws IOException {
        ClassPathResource resource = new ClassPathResource("ai-registry/registry-snapshot.json");
        assertThat(resource.exists()).isTrue();
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
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

    private long chunkedComponentCount(JsonNode components) {
        long count = 0;
        var fields = components.fields();
        while (fields.hasNext()) {
            JsonNode chunks = fields.next().getValue().path("chunks");
            if (chunks.isArray() && !chunks.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long chunkCount(JsonNode components) {
        long count = 0;
        var fields = components.fields();
        while (fields.hasNext()) {
            JsonNode chunks = fields.next().getValue().path("chunks");
            if (chunks.isArray()) {
                count += chunks.size();
            }
        }
        return count;
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return HexFormat.of().formatHex(digest);
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
