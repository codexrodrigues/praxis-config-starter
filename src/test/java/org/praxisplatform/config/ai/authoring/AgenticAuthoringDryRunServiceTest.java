package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class AgenticAuthoringDryRunServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringDryRunService service = new AgenticAuthoringDryRunService(objectMapper);

    @Test
    void dryRunPassesForPhaseZeroArtifacts() throws Exception {
        AgenticAuthoringDryRunResult result = service.run(defaultRequest());

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.patch().path("page").path("widgets")).hasSize(1);
        assertThat(result.gates())
                .extracting(AgenticAuthoringGateResult::gateId)
                .containsExactly(
                        "examples-governance",
                        "page-create-catalog",
                        "compiled-form-patch",
                        "authoring-replay-bundle"
                );
        assertThat(result.gates())
                .allSatisfy(gate -> assertThat(gate.status()).isEqualTo("passed"));
        assertThat(result.warnings()).contains("round-trip-not-run");
    }

    @Test
    void dryRunRejectsPatchWithBlockedFormInput(@TempDir Path tempDir) throws Exception {
        Path badPatch = tempDir.resolve("compiled-form-patch.bad.json");
        JsonNode patch = objectMapper.readTree(defaultPatch().toFile());
        ObjectNode inputs = (ObjectNode) patch.path("patch")
                .path("page")
                .path("widgets")
                .get(0)
                .path("definition")
                .path("inputs");
        inputs.putObject("config");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(badPatch.toFile(), patch);

        AgenticAuthoringDryRunRequest request = new AgenticAuthoringDryRunRequest(
                examplesManifest(),
                pageCreateCatalog(),
                badPatch,
                replayBundle(),
                "create-minimal-form"
        );

        AgenticAuthoringDryRunResult result = service.run(request);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("GATE_FAILED:compiled-form-patch");
        assertThat(result.gates())
                .filteredOn(gate -> gate.gateId().equals("compiled-form-patch"))
                .singleElement()
                .satisfies(gate -> assertThat(gate.messages()).contains("blocked input config"));
    }

    @Test
    void dryRunCanResolveArtifactsFromConfiguredDirectory() throws Exception {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(proofsDir());
        AgenticAuthoringArtifactSource source = new AgenticAuthoringArtifactSource(properties);

        AgenticAuthoringDryRunResult result = service.run(source);

        assertThat(result.valid()).isTrue();
        assertThat(result.gates()).hasSize(4);
    }

    @Test
    void artifactSourceRejectsFilesOutsideConfiguredDirectory() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(proofsDir());
        properties.setCompiledFormPatch("../compiled-form-patch.helpdesk-create-ticket.v0.json");
        AgenticAuthoringArtifactSource source = new AgenticAuthoringArtifactSource(properties);

        assertThatThrownBy(source::dryRunRequest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must resolve inside artifacts-dir");
    }

    @Test
    void reportServiceWritesDryRunReport(@TempDir Path tempDir) throws Exception {
        AgenticAuthoringArtifactProperties properties = configuredProperties();
        Path reportPath = tempDir.resolve("agentic-authoring-report.json");
        properties.setReportPath(reportPath);
        AgenticAuthoringArtifactSource source = new AgenticAuthoringArtifactSource(properties);
        AgenticAuthoringDryRunReportService reportService = new AgenticAuthoringDryRunReportService(
                service,
                source,
                properties,
                objectMapper,
                Clock.fixed(Instant.parse("2026-04-12T16:00:00Z"), ZoneOffset.UTC)
        );

        AgenticAuthoringDryRunReport report = reportService.runAndWriteReport();

        assertThat(report.result().valid()).isTrue();
        assertThat(report.generatedAt()).isEqualTo("2026-04-12T16:00:00Z");
        JsonNode written = objectMapper.readTree(reportPath.toFile());
        assertThat(written.path("result").path("valid").asBoolean()).isTrue();
        assertThat(written.path("profileId").asText()).isEqualTo("create-minimal-form");
    }

    @Test
    void reportServiceRequiresReportPath() {
        AgenticAuthoringArtifactProperties properties = configuredProperties();
        AgenticAuthoringArtifactSource source = new AgenticAuthoringArtifactSource(properties);
        AgenticAuthoringDryRunReportService reportService = new AgenticAuthoringDryRunReportService(
                service,
                source,
                properties,
                objectMapper
        );

        assertThatThrownBy(reportService::runAndWriteReport)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("report-path must be configured");
    }

    private AgenticAuthoringDryRunRequest defaultRequest() {
        return new AgenticAuthoringDryRunRequest(
                examplesManifest(),
                pageCreateCatalog(),
                defaultPatch(),
                replayBundle(),
                "create-minimal-form"
        );
    }

    private AgenticAuthoringArtifactProperties configuredProperties() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(proofsDir());
        return properties;
    }

    private Path examplesManifest() {
        return docsPath("examples-governance-manifest.v0.json");
    }

    private Path pageCreateCatalog() {
        return docsPath("page-create-catalog.v0.json");
    }

    private Path defaultPatch() {
        return docsPath("compiled-form-patch.helpdesk-create-ticket.v0.json");
    }

    private Path replayBundle() {
        return docsPath("authoring-replay-bundle.helpdesk-create-ticket.v0.json");
    }

    private Path docsPath(String fileName) {
        return AgenticAuthoringTestPaths.proof(fileName);
    }

    private Path proofsDir() {
        return AgenticAuthoringTestPaths.proofsDir();
    }
}
