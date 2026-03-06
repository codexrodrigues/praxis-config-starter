package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AiContractSpecConsistencyTest {

    private static final String CONTRACT_FILE = "praxis-ai-api-contract-v1.1.openapi.yaml";

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepGeneratedAiContractSpecSyncedWithOpenApi() throws IOException {
        Path contractPath = resolveContractPath();
        assertThat(Files.exists(contractPath)).isTrue();

        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(contractPath)) {
            document = new Yaml().load(in);
        }

        Map<String, Object> info = (Map<String, Object>) document.get("info");
        assertThat(info).isNotNull();
        assertThat(AiContractSpec.CONTRACT_VERSION).isEqualTo(info.get("version"));

        Map<String, Object> components = (Map<String, Object>) document.get("components");
        assertThat(components).isNotNull();

        Map<String, Object> parameters = (Map<String, Object>) components.get("parameters");
        assertThat(parameters).isNotNull();
        Map<String, Object> schemaHashParam = (Map<String, Object>) parameters.get("ContractSchemaHashHeader");
        assertThat(schemaHashParam).isNotNull();
        Map<String, Object> schemaHashSchema = (Map<String, Object>) schemaHashParam.get("schema");
        assertThat(schemaHashSchema).isNotNull();
        assertThat(AiContractSpec.CONTRACT_SCHEMA_HASH).isEqualTo(schemaHashSchema.get("default"));

        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).isNotNull();

        Map<String, Object> startResponse = (Map<String, Object>) schemas.get("AiPatchStreamStartResponse");
        assertThat(startResponse).isNotNull();
        Map<String, Object> startProperties = (Map<String, Object>) startResponse.get("properties");
        assertThat(startProperties).isNotNull();
        Map<String, Object> eventSchemaVersion = (Map<String, Object>) startProperties.get("eventSchemaVersion");
        assertThat(eventSchemaVersion).isNotNull();
        assertThat(AiContractSpec.STREAM_EVENT_SCHEMA_VERSION).isEqualTo(eventSchemaVersion.get("example"));

        Map<String, Object> envelopeSchema = (Map<String, Object>) schemas.get("AiTurnEventEnvelope");
        assertThat(envelopeSchema).isNotNull();
        Map<String, Object> envelopeProperties = (Map<String, Object>) envelopeSchema.get("properties");
        assertThat(envelopeProperties).isNotNull();
        Map<String, Object> typeProperty = (Map<String, Object>) envelopeProperties.get("type");
        assertThat(typeProperty).isNotNull();
        List<String> eventTypes = (List<String>) typeProperty.get("enum");
        assertThat(eventTypes).isNotNull();
        assertThat(AiContractSpec.STREAM_EVENT_TYPES).containsExactlyElementsOf(eventTypes);
    }

    private Path resolveContractPath() {
        Path cwd = Paths.get("").toAbsolutePath();
        List<Path> candidates = List.of(
                cwd.resolve("docs/ai/contracts").resolve(CONTRACT_FILE).normalize(),
                cwd.resolve("../docs/ai/contracts").resolve(CONTRACT_FILE).normalize(),
                cwd.resolve("../../docs/ai/contracts").resolve(CONTRACT_FILE).normalize());
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
}
