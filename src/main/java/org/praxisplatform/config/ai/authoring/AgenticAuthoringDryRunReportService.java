package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public class AgenticAuthoringDryRunReportService {

    private final AgenticAuthoringDryRunService dryRunService;
    private final AgenticAuthoringArtifactSource artifactSource;
    private final AgenticAuthoringArtifactProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgenticAuthoringDryRunReportService(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper
    ) {
        this(dryRunService, artifactSource, properties, objectMapper, Clock.systemUTC());
    }

    AgenticAuthoringDryRunReportService(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.dryRunService = Objects.requireNonNull(dryRunService, "dryRunService must not be null");
        this.artifactSource = Objects.requireNonNull(artifactSource, "artifactSource must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AgenticAuthoringDryRunReport runAndWriteReport() throws IOException {
        AgenticAuthoringDryRunResult result = dryRunService.run(artifactSource);
        AgenticAuthoringDryRunReport report = new AgenticAuthoringDryRunReport(
                Instant.now(clock).toString(),
                properties.getProfileId(),
                properties.getArtifactsDir().toAbsolutePath().normalize().toString(),
                result
        );
        writeReport(report);
        return report;
    }

    private void writeReport(AgenticAuthoringDryRunReport report) throws IOException {
        Path reportPath = properties.getReportPath();
        if (reportPath == null) {
            throw new IllegalStateException("praxis.ai.authoring.report-path must be configured when dry-run is enabled.");
        }
        Path normalized = reportPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(normalized.toFile(), report);
    }
}
