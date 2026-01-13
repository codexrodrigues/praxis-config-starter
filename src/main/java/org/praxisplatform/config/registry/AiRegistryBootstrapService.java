package org.praxisplatform.config.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

@RequiredArgsConstructor
@Slf4j
public class AiRegistryBootstrapService {

    private static final String DEFAULT_SNAPSHOT_LOCATION = "classpath:ai-registry/registry-snapshot.json";

    private final RegistryIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final AiRegistryBootstrapProperties bootstrapProperties;
    private final AiRegistryStatusService statusService;
    private final AiRegistryBootstrapState bootstrapState;

    public void bootstrapIfNeeded() {
        if (bootstrapProperties != null && !bootstrapProperties.isEnabled()) {
            bootstrapState.setSkipped(true);
            return;
        }

        AiRegistryStatusReport statusReport = statusService.getStatus();
        if (statusReport.isReady()) {
            bootstrapState.setSkipped(true);
            log.info("AI registry already ready (componentDefinitions={}, templates={}).",
                    statusReport.getComponentDefinitionCount(), statusReport.getTemplateCount());
            return;
        }

        bootstrapState.setAttempted(true);
        bootstrapState.setAttemptedAt(Instant.now());
        if (bootstrapProperties != null) {
            bootstrapState.setRequestedSnapshotLocation(bootstrapProperties.getSnapshotLocation());
        }

        ResolvedSnapshot resolvedSnapshot = resolveSnapshot();
        if (resolvedSnapshot == null || resolvedSnapshot.resource == null) {
            bootstrapState.setError("Snapshot not found.");
            bootstrapState.setCompletedAt(Instant.now());
            log.warn("AI registry bootstrap skipped: snapshot not found (requested={}).",
                    bootstrapState.getRequestedSnapshotLocation());
            return;
        }

        bootstrapState.setResolvedSnapshotLocation(resolvedSnapshot.location);
        bootstrapState.setSource(resolvedSnapshot.source);
        bootstrapState.setFallbackUsed(resolvedSnapshot.fallbackUsed);

        try (InputStream input = resolvedSnapshot.resource.getInputStream()) {
            RegistryIngestionRequest request = objectMapper.readValue(input, RegistryIngestionRequest.class);
            ingestionService.ingestRegistry(request);
            bootstrapState.setSucceeded(true);
            log.info("AI registry bootstrap completed from {} (components={}).",
                    resolvedSnapshot.location,
                    request.getComponents() != null ? request.getComponents().size() : 0);
        } catch (Exception ex) {
            bootstrapState.setError(ex.getMessage());
            log.error("AI registry bootstrap failed.", ex);
        } finally {
            bootstrapState.setCompletedAt(Instant.now());
        }
    }

    private ResolvedSnapshot resolveSnapshot() {
        String requested = bootstrapProperties != null ? bootstrapProperties.getSnapshotLocation() : null;
        if (requested != null && !requested.isBlank()) {
            Resource external = resourceLoader.getResource(requested);
            if (external.exists()) {
                return new ResolvedSnapshot(external, requested, "external", false);
            }
            log.warn("Snapshot not found at {}, falling back to {}.", requested, DEFAULT_SNAPSHOT_LOCATION);
        }

        Resource classpath = resourceLoader.getResource(DEFAULT_SNAPSHOT_LOCATION);
        if (classpath.exists()) {
            boolean fallbackUsed = requested != null && !requested.isBlank();
            return new ResolvedSnapshot(classpath, DEFAULT_SNAPSHOT_LOCATION, "classpath", fallbackUsed);
        }
        return null;
    }

    private static final class ResolvedSnapshot {
        private final Resource resource;
        private final String location;
        private final String source;
        private final boolean fallbackUsed;

        private ResolvedSnapshot(Resource resource, String location, String source, boolean fallbackUsed) {
            this.resource = resource;
            this.location = location;
            this.source = source;
            this.fallbackUsed = fallbackUsed;
        }
    }
}
