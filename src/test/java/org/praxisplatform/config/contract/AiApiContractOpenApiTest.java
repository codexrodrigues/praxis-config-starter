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

class AiApiContractOpenApiTest {

    private static final String CONTRACT_FILE = "praxis-ai-api-contract-v1.1.openapi.yaml";

    @Test
    @SuppressWarnings("unchecked")
    void shouldLoadAndValidateAiOpenApiContract() throws IOException {
        Path contractPath = resolveContractPath();
        assertThat(Files.exists(contractPath))
                .as("OpenAPI contract file must exist")
                .isTrue();

        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(contractPath)) {
            document = new Yaml().load(in);
        }

        assertThat(document).isNotNull();
        assertThat(document.get("openapi")).isEqualTo("3.0.3");

        Map<String, Object> info = (Map<String, Object>) document.get("info");
        assertThat(info).isNotNull();
        assertThat(info.get("version")).isEqualTo("v1.1");

        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths).containsKeys(
                "/api/praxis/config/ai/patch",
                "/api/praxis/config/ai/patch/stream/start",
                "/api/praxis/config/ai/patch/stream/{streamId}",
                "/api/praxis/config/ai/patch/stream/{streamId}/probe",
                "/api/praxis/config/ai/patch/stream/{streamId}/cancel");

        Map<String, Object> components = (Map<String, Object>) document.get("components");
        assertThat(components).isNotNull();

        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).isNotNull();
        assertThat(schemas).containsKeys(
                "AiOrchestratorRequest",
                "AiOrchestratorResponse",
                "AiPatchStreamStartResponse",
                "AiPatchStreamCancelResponse",
                "AiTurnEventEnvelope");

        Map<String, Object> parameters = (Map<String, Object>) components.get("parameters");
        assertThat(parameters).isNotNull();
        assertThat(parameters).containsKeys(
                "ContractVersionHeader",
                "ContractSchemaHashHeader",
                "LastEventIdHeader",
                "AccessTokenQuery");
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
