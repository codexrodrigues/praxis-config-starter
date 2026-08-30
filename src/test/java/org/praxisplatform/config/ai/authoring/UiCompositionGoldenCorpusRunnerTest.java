package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class UiCompositionGoldenCorpusRunnerTest {

    @TempDir
    Path tempDir;

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
        assertThat(report.path("reportSchemaSha256").asText())
                .isEqualTo(fileSha256(UiCompositionGoldenCorpusRunner.DEFAULT_REPORT_SCHEMA));
        assertThat(report.at("/compilerIdentity/id").asText())
                .isEqualTo("config-ui-composition-plan-compiler");
        assertThat(report.at("/compilerIdentity/builderVersion").asText())
                .isEqualTo("config-ui-composition-plan-compiler@1.2.0");
        assertThat(report.at("/compilerIdentity/implementationSha256").asText())
                .matches("[a-f0-9]{64}");
        assertThat(report.at("/compilerIdentity/implementationArtifact/kind").asText())
                .isEqualTo("jvm-class");
        Path implementationArtifact = Path.of(
                report.at("/compilerIdentity/implementationArtifact/path").asText());
        assertThat(implementationArtifact).exists();
        assertThat(report.at("/compilerIdentity/implementationArtifact/sha256").asText())
                .isEqualTo(fileSha256(implementationArtifact));
        assertThat(report.at("/compilerIdentity/sourceReceipt/scope").asText())
                .isEqualTo("ordered-praxis-source-and-class-byte-closure");
        assertThat(report.at("/compilerIdentity/sourceReceipt/sources")).hasSize(11);
        assertThat(report.at("/compilerIdentity/sourceReceipt/sources/3/id").asText())
                .isEqualTo("compiled-page-patch-validator");
        assertThat(report.at("/compilerIdentity/sourceReceipt/dependencyGraph")).isNotEmpty();
        assertThat(report.at("/compilerIdentity/sourceReceipt/closureSha256").asText())
                .matches("[a-f0-9]{64}");
        Set<String> coveredClasses = new HashSet<>();
        report.at("/compilerIdentity/sourceReceipt/sources").forEach(source ->
                source.path("artifacts").forEach(artifact -> coveredClasses.add(
                        binaryClassName(artifact.path("path").asText()))));
        report.at("/compilerIdentity/sourceReceipt/dependencyGraph").forEach(edge -> {
            String value = edge.asText();
            assertThat(coveredClasses).contains(value.substring(value.indexOf(" -> ") + 4));
        });
        assertThat(report.path("cases").get(0).path("canonicalDiagnostics").isArray()).isTrue();
        assertThat(report.path("passed").asBoolean())
                .withFailMessage("Java golden report: %s", report)
                .isTrue();
    }

    @Test
    void malformedCorpusPersistsReadableFailClosedReport() throws Exception {
        Path malformedCorpus = tempDir.resolve("malformed-corpus.json");
        Path reportPath = tempDir.resolve("malformed-report.json");
        Files.writeString(malformedCorpus, "{ not-json");

        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                malformedCorpus,
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA,
                reportPath);

        assertThat(report.path("passed").asBoolean()).isFalse();
        assertThat(report.path("globalFailures").get(0).asText()).startsWith("json-parse:");
        assertThat(report.path("cases")).isEmpty();
        assertThat(report.path("corpusSha256").asText()).isEqualTo(fileSha256(malformedCorpus));
        assertThat(objectMapper.readTree(reportPath.toFile())).isEqualTo(report);
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

    @Test
    void skippedTargetRequiresCompilerBlockAndEmptyAttestationEvidence() throws Exception {
        JsonNode corpus = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS.toFile());
        JsonNode schema = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA.toFile());
        ObjectNode blockedCase = null;
        for (JsonNode testCase : corpus.path("cases")) {
            if ("block".equals(testCase.at("/expected/compilerParity/outcome").asText())) {
                blockedCase = (ObjectNode) testCase;
                break;
            }
        }
        assertThat(blockedCase).isNotNull();
        ((ObjectNode) blockedCase.at("/expected/targetAttestation/requirements"))
                .withArray("actions")
                .add("trackEvent");

        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                corpus,
                schema,
                tempDir.resolve("invalid-skipped-report.json"));

        assertThat(report.path("passed").asBoolean()).isFalse();
        assertThat(report.path("globalFailures"))
                .extracting(JsonNode::asText)
                .anyMatch(message -> message.startsWith("schema:"));
    }

    @Test
    void mismatchedExternalCompilerReceiptFailsClosed() throws Exception {
        JsonNode corpus = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS.toFile());
        JsonNode schema = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA.toFile());
        ((ObjectNode) corpus.at("/compilerReceipts/java/sources/3/artifacts/0"))
                .put("sha256", "0".repeat(64));

        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                corpus,
                schema,
                tempDir.resolve("invalid-receipt-report.json"));

        assertThat(report.path("passed").asBoolean()).isFalse();
        assertThat(report.path("globalFailures"))
                .extracting(JsonNode::asText)
                .contains("java-compiler-receipt-artifact-sha-mismatch:"
                        + "target/classes/org/praxisplatform/config/ai/authoring/"
                        + "AgenticAuthoringCompiledPagePatchValidator.class");
    }

    @Test
    void missingPraxisDependencyEdgeFailsClosed() throws Exception {
        JsonNode corpus = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS.toFile());
        JsonNode schema = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA.toFile());
        ((com.fasterxml.jackson.databind.node.ArrayNode) corpus.at("/compilerReceipts/java/dependencyGraph"))
                .remove(0);

        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                corpus,
                schema,
                tempDir.resolve("missing-java-dependency-edge-report.json"));

        assertThat(report.path("passed").asBoolean()).isFalse();
        assertThat(report.path("globalFailures"))
                .extracting(JsonNode::asText)
                .contains("java-compiler-receipt-dependency-graph-mismatch");
    }

    @Test
    void publishedReportSchemaRejectsMalformedPeerShape() throws Exception {
        ObjectNode report = new UiCompositionGoldenCorpusRunner().run(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS,
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA,
                tempDir.resolve("valid-report.json"));
        JsonNode reportSchema = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_REPORT_SCHEMA.toFile());
        ObjectNode malformedPeer = report.deepCopy();
        ((ObjectNode) malformedPeer.path("compilerIdentity")).remove("sourceReceipt");

        assertThat(JsonSchemaFactory
                        .getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(reportSchema)
                        .validate(malformedPeer))
                .isNotEmpty();
    }

    @Test
    void targetProbeRequiresOnlyTheAuthoredDispatchPayload() throws Exception {
        JsonNode corpus = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_CORPUS.toFile());
        JsonNode schema = objectMapper.readTree(
                UiCompositionGoldenCorpusRunner.DEFAULT_SCHEMA.toFile());
        ObjectNode probe = (ObjectNode) corpus.path("cases").get(2).path("input").path("targetProbe");
        JsonNode dispatchPayload = probe.remove("dispatchPayload");

        ObjectNode missingPayloadReport = new UiCompositionGoldenCorpusRunner().run(
                corpus,
                schema,
                tempDir.resolve("target-probe-missing-payload-report.json"));
        assertThat(missingPayloadReport.path("globalFailures"))
                .extracting(JsonNode::asText)
                .anyMatch(message -> message.startsWith("schema:"));

        probe.set("dispatchPayload", dispatchPayload);
        probe.put("actionId", "trackEvent");
        ObjectNode extraAuthorityReport = new UiCompositionGoldenCorpusRunner().run(
                corpus,
                schema,
                tempDir.resolve("target-probe-extra-authority-report.json"));
        assertThat(extraAuthorityReport.path("globalFailures"))
                .extracting(JsonNode::asText)
                .anyMatch(message -> message.startsWith("schema:"));
    }

    private String binaryClassName(String artifactPath) {
        int classesMarker = artifactPath.indexOf("classes/");
        return artifactPath
                .substring(classesMarker + "classes/".length(), artifactPath.length() - ".class".length())
                .replace('/', '.');
    }
}
