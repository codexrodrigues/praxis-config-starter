package org.praxisplatform.config.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringValidatorRegistry;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.springframework.core.io.ClassPathResource;

@Tag("unit")
class AiRegistrySnapshotContractTest {

    private static final String EXPECTED_SNAPSHOT_HASH =
            "5567f61ffb09b71e0ca049f79e848b12165ea3a3581981241ce18687729da114";
    private static final String EXPECTED_VERSION = "1.0.0";
    private static final String EXPECTED_GENERATED_AT = "2026-08-11T02:58:59.306Z";
    private static final int EXPECTED_COMPONENT_COUNT = 105;
    private static final int EXPECTED_AUTHORING_MANIFEST_COUNT = 95;
    private static final int EXPECTED_CHUNKED_COMPONENT_COUNT = 105;
    private static final int EXPECTED_CHUNK_COUNT = 2445;

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

        assertThat(new CanonicalJsonHashService(objectMapper).sha256(snapshot))
                .isEqualTo(EXPECTED_SNAPSHOT_HASH);
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
    void relatedResourceOutletPublishesParentIdentityAsGovernedViewContext() throws IOException {
        JsonNode outlet = readSnapshot()
                .path("components")
                .path("praxis-related-resource-outlet");

        JsonNode parentIdentityInput = findByName(outlet.path("inputs"), "parentIdentity");
        assertThat(parentIdentityInput).isNotNull();
        assertThat(parentIdentityInput.path("type").asText())
                .isEqualTo("MaterializedResourceIdentity | null");

        JsonNode parentIdentityPort = findById(outlet.path("ports"), "parentIdentity");
        assertThat(parentIdentityPort).isNotNull();
        assertThat(parentIdentityPort.path("semanticKind").asText()).isEqualTo("view-context");
        assertThat(parentIdentityPort.path("exposure").path("public").asBoolean()).isTrue();
        assertThat(parentIdentityPort.path("schema").path("ref").asText())
                .isEqualTo("MaterializedResourceIdentity | null");
    }

    @Test
    void tableComposeRendererKeepsItsExecutableNestedSchemaInThePublishedSnapshot() throws IOException {
        JsonNode operations = readSnapshot()
                .path("components")
                .path("praxis-table")
                .path("authoringManifest")
                .path("operations");
        JsonNode rendererOperation = null;
        for (JsonNode operation : operations) {
            if ("column.renderer.set".equals(operation.path("operationId").asText())) {
                rendererOperation = operation;
                break;
            }
        }

        assertThat(rendererOperation)
                .as("praxis-table must publish column.renderer.set")
                .isNotNull();
        JsonNode composeItem = rendererOperation
                .path("inputSchema")
                .path("properties")
                .path("compose")
                .path("properties")
                .path("items")
                .path("items");
        assertThat(composeItem.path("properties").path("type").path("enum"))
                .contains(
                        objectMapper.valueToTree("value"),
                        objectMapper.valueToTree("image"),
                        objectMapper.valueToTree("avatar"));
        assertThat(composeItem.path("properties").path("field").path("type").asText())
                .isEqualTo("string");
        assertThat(composeItem.path("properties").path("image").path("properties").path("srcField").path("type").asText())
                .isEqualTo("string");
        assertThat(composeItem.path("properties").path("avatar").path("properties").path("srcField").path("type").asText())
                .isEqualTo("string");
        assertThat(composeItem.path("properties").path("avatar").path("properties").path("size").path("type").asText())
                .isEqualTo("number");
        assertThat(composeItem.path("allOf").isArray()).isTrue();
        assertThat(composeItem.path("allOf")).isNotEmpty();
    }

    @Test
    void entityLookupPublishesGovernedDialogSearchAuthoringSchema() throws IOException {
        JsonNode profiles = readSnapshot()
                .path("components")
                .path("pdx-entity-lookup")
                .path("authoringManifest")
                .path("controlProfiles");
        JsonNode entityLookupOperation = null;
        for (JsonNode profile : profiles) {
            if (!"entity-lookup".equals(profile.path("profileId").asText())) {
                continue;
            }
            for (JsonNode operation : profile.path("operations")) {
                if ("field.entityLookup.configure".equals(operation.path("operationId").asText())) {
                    entityLookupOperation = operation;
                    break;
                }
            }
        }

        assertThat(entityLookupOperation)
                .as("entity lookup must publish governed dialog authoring")
                .isNotNull();
        JsonNode dialog = entityLookupOperation
                .path("inputSchema")
                .path("properties")
                .path("dialog");
        assertThat(dialog.path("type").asText()).isEqualTo("object");
        assertThat(dialog.path("properties").path("size").path("enum"))
                .contains(
                        objectMapper.valueToTree("sm"),
                        objectMapper.valueToTree("md"),
                        objectMapper.valueToTree("lg"),
                        objectMapper.valueToTree("xl"),
                        objectMapper.valueToTree("full"));
        assertThat(entityLookupOperation.path("affectedPaths"))
                .contains(objectMapper.valueToTree("fieldMetadata.dialog"));
    }

    @Test
    void tableComposeItemRefinementIsExecutableInThePublishedSnapshot() throws IOException {
        JsonNode operations = readSnapshot()
                .path("components")
                .path("praxis-table")
                .path("authoringManifest")
                .path("operations");
        JsonNode refinementOperation = null;
        for (JsonNode operation : operations) {
            if ("column.renderer.composeItem.set".equals(operation.path("operationId").asText())) {
                refinementOperation = operation;
                break;
            }
        }

        assertThat(refinementOperation)
                .as("praxis-table must publish the targeted compose-item operation")
                .isNotNull();
        assertThat(refinementOperation.path("inputSchema").path("required"))
                .contains(
                        objectMapper.valueToTree("itemType"),
                        objectMapper.valueToTree("item"));
        assertThat(refinementOperation.path("effects").get(0).path("handler").asText())
                .isEqualTo("table-renderer-compose-item-merge");
        assertThat(refinementOperation.path("validators"))
                .contains(objectMapper.valueToTree("renderer-compose-item-exists"));
        assertThat(refinementOperation.path("affectedPaths"))
                .contains(objectMapper.valueToTree("columns[].renderer.compose.items[]"));
    }

    @Test
    void tableComposeLayoutRefinementIsExecutableInThePublishedSnapshot() throws IOException {
        JsonNode operations = readSnapshot()
                .path("components")
                .path("praxis-table")
                .path("authoringManifest")
                .path("operations");
        JsonNode refinementOperation = null;
        for (JsonNode operation : operations) {
            if ("column.renderer.composeLayout.set".equals(operation.path("operationId").asText())) {
                refinementOperation = operation;
                break;
            }
        }

        assertThat(refinementOperation)
                .as("praxis-table must publish the targeted compose-layout operation")
                .isNotNull();
        assertThat(refinementOperation.path("inputSchema").path("properties").path("direction").path("enum"))
                .contains(
                        objectMapper.valueToTree("row"),
                        objectMapper.valueToTree("column"));
        assertThat(refinementOperation.path("effects").get(0).path("handler").asText())
                .isEqualTo("table-renderer-compose-layout-merge");
        assertThat(refinementOperation.path("validators"))
                .contains(objectMapper.valueToTree("renderer-compose-exists"));
        assertThat(refinementOperation.path("affectedPaths"))
                .contains(objectMapper.valueToTree("columns[].renderer.compose.layout"));
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

    private JsonNode findByName(JsonNode entries, String name) {
        for (JsonNode entry : entries) {
            if (name.equals(entry.path("name").asText())) {
                return entry;
            }
        }
        return null;
    }

    private JsonNode findById(JsonNode entries, String id) {
        for (JsonNode entry : entries) {
            if (id.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        return null;
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
