package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UiCompositionGoldenCorpusRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void javaCompilerMatchesTheNeutralGoldenCorpus() throws Exception {
        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS,
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA,
                UiCompositionGoldenCorpusRunner.DEFAULT_REPORT);

        assertThat(report.path("globalFailures")).isEmpty();
        assertThat(report.path("cases")).hasSize(18);
        assertThat(report.path("corpusSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(report.path("schemaSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(report.path("corpusSha256").asText())
                .isEqualTo(fileSha256(UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS));
        assertThat(report.path("schemaSha256").asText())
                .isEqualTo(fileSha256(UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA));
        assertThat(report.at("/compilerIdentity/id").asText())
                .isEqualTo("config-ui-composition-plan-compiler");
        assertThat(report.at("/compilerIdentity/builderVersion").asText())
                .isEqualTo("config-ui-composition-plan-compiler@1.2.0");
        assertThat(report.at("/compilerIdentity/implementationSha256").asText())
                .matches("[a-f0-9]{64}");
        assertThat(report.path("passed").asBoolean())
                .withFailMessage("Java golden report: %s", report)
                .isTrue();
    }

    private String fileSha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    @Test
    void deliberateProjectionDivergenceFailsTheGateBeforeApply() throws Exception {
        JsonNode corpus = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS.toFile());
        JsonNode schema = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA.toFile());
        ObjectNode firstExpected = (ObjectNode) corpus.path("cases")
                .get(0)
                .path("expected")
                .path("compilerParity");
        firstExpected.put("projectionSha256", "0".repeat(64));

        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                corpus,
                schema,
                Path.of("target", "ui-composition-golden", "java-deliberate-drift-report.json"));

        assertThat(report.path("passed").asBoolean()).isFalse();
        assertThat(report.path("cases").get(0).path("failures"))
                .extracting(JsonNode::asText)
                .containsExactly("projection-sha-mismatch");
    }

    @Test
    void corpusCoversCompilerAndTargetFailurePhasesWithoutDuplicateCaseIds() throws Exception {
        JsonNode corpus = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS.toFile());
        Set<String> ids = new HashSet<>();
        Set<String> compilerPhases = new HashSet<>();
        Set<String> compilerOutcomes = new HashSet<>();
        Set<String> targetOutcomes = new HashSet<>();

        for (JsonNode testCase : corpus.path("cases")) {
            assertThat(ids.add(testCase.path("id").asText())).isTrue();
            compilerPhases.add(testCase.at("/expected/compilerParity/phase").asText());
            compilerOutcomes.add(testCase.at("/expected/compilerParity/outcome").asText());
            targetOutcomes.add(testCase.at("/expected/targetAttestation/outcome").asText());
        }

        assertThat(compilerPhases).containsExactlyInAnyOrder(
                "compiler-parity", "template-attestation");
        assertThat(compilerOutcomes).containsExactlyInAnyOrder("pass", "warning", "block");
        assertThat(targetOutcomes).containsExactlyInAnyOrder("pass", "block", "skipped");
    }
}
