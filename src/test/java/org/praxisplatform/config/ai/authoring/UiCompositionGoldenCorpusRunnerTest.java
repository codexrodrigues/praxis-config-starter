package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
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
        assertThat(report.path("cases")).hasSize(9);
        assertThat(report.path("passed").asBoolean())
                .withFailMessage("Java golden report: %s", report)
                .isTrue();
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
