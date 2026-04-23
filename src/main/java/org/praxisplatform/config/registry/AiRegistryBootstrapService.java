package org.praxisplatform.config.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Bootstrap opcional do AI registry a partir de um snapshot versionado.
 *
 * <p>
 * O serviço tenta carregar um snapshot externo ou de classpath no startup e o injeta via
 * {@link RegistryIngestionService} apenas quando o registry ainda não é considerado pronto.
 * Também registra estado operacional em {@link AiRegistryBootstrapState} para troubleshooting.
 * </p>
 */
@RequiredArgsConstructor
@Slf4j
public class AiRegistryBootstrapService {

    private static final String DEFAULT_SNAPSHOT_LOCATION = "classpath:ai-registry/registry-snapshot.json";
    private static final String SNAPSHOT_METADATA_REGISTRY_TYPE = "registry_snapshot_metadata";
    private static final String SNAPSHOT_METADATA_REGISTRY_KEY = "component-definitions";
    private static final String SNAPSHOT_METADATA_COMPONENT_TYPE = "registry-snapshot";
    private static final String SNAPSHOT_METADATA_SCOPE_KEY = "GLOBAL";
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";

    private final RegistryIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final AiRegistryBootstrapProperties bootstrapProperties;
    private final AiRegistryStatusService statusService;
    private final AiRegistryBootstrapState bootstrapState;
    private final AiRegistryRepository repository;
    private final RagVectorStoreService ragVectorStoreService;

    public void bootstrapIfNeeded() {
        if (bootstrapProperties != null && !bootstrapProperties.isEnabled()) {
            bootstrapState.setSkipped(true);
            bootstrapState.setSkipReason("disabled");
            return;
        }

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

        try {
            byte[] snapshotBytes = resolvedSnapshot.resource.getContentAsByteArray();
            String snapshotHash = sha256(snapshotBytes);
            RegistryIngestionRequest request = objectMapper.readValue(snapshotBytes, RegistryIngestionRequest.class);
            long componentCount = request.getComponents() != null ? request.getComponents().size() : 0;
            bootstrapState.setSnapshotHash(snapshotHash);
            bootstrapState.setSnapshotComponentCount(componentCount);

            AiRegistryStatusReport statusReport = statusService.getStatus();
            SnapshotMetadata previousSnapshotMetadata = previousSnapshotMetadata();
            String previousSnapshotHash = previousSnapshotMetadata != null
                    ? previousSnapshotMetadata.snapshotHash()
                    : null;
            bootstrapState.setPreviousSnapshotHash(previousSnapshotHash);
            if (shouldSkip(statusReport, previousSnapshotHash, snapshotHash)) {
                bootstrapState.setSkipped(true);
                bootstrapState.setSkipReason("snapshot-current");
                bootstrapState.setCompletedAt(Instant.now());
                log.info("AI registry already current (componentDefinitions={}, templates={}, snapshotHash={}).",
                        statusReport.getComponentDefinitionCount(),
                        statusReport.getTemplateCount(),
                        snapshotHash);
                return;
            }

            bootstrapState.setAttempted(true);
            ingestionService.ingestRegistry(request, null, null);
            pruneObsoleteDefinitions(request, previousSnapshotMetadata);
            upsertSnapshotMetadata(request, resolvedSnapshot, snapshotHash, componentCount);
            bootstrapState.setSucceeded(true);
            log.info("AI registry bootstrap completed from {} (components={}, snapshotHash={}).",
                    resolvedSnapshot.location,
                    componentCount,
                    snapshotHash);
        } catch (Exception ex) {
            bootstrapState.setError(ex.getMessage());
            log.error("AI registry bootstrap failed.", ex);
        } finally {
            bootstrapState.setCompletedAt(Instant.now());
        }
    }

    private boolean shouldSkip(
            AiRegistryStatusReport statusReport,
            String previousSnapshotHash,
            String snapshotHash) {
        if (statusReport == null || !statusReport.isReady()) {
            return false;
        }
        if (bootstrapProperties != null && bootstrapProperties.isForce()) {
            return false;
        }
        if (bootstrapProperties != null && !bootstrapProperties.isRefreshOnSnapshotDrift()) {
            return true;
        }
        return snapshotHash != null && snapshotHash.equals(previousSnapshotHash);
    }

    private SnapshotMetadata previousSnapshotMetadata() {
        return repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        SNAPSHOT_METADATA_REGISTRY_TYPE,
                        SNAPSHOT_METADATA_REGISTRY_KEY,
                        SNAPSHOT_METADATA_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SNAPSHOT_METADATA_SCOPE_KEY)
                .map(AiRegistry::getPayload)
                .map(this::readSnapshotMetadata)
                .orElse(null);
    }

    private SnapshotMetadata readSnapshotMetadata(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String version = node.path("version").asText(null);
            String generatedAt = node.path("generatedAt").asText(null);
            return new SnapshotMetadata(
                    node.path("snapshotHash").asText(null),
                    version,
                    generatedAt,
                    RagDocumentIdentity.resolveReleaseId(null, version, generatedAt));
        } catch (Exception ex) {
            log.warn("AI registry snapshot metadata payload is not readable; forcing snapshot refresh.", ex);
            return null;
        }
    }

    private void upsertSnapshotMetadata(
            RegistryIngestionRequest request,
            ResolvedSnapshot resolvedSnapshot,
            String snapshotHash,
            long componentCount) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("snapshotHash", snapshotHash);
        payload.put("componentCount", componentCount);
        payload.put("snapshotLocation", resolvedSnapshot.location);
        payload.put("source", resolvedSnapshot.source);
        payload.put("releaseId", RagDocumentIdentity.resolveReleaseId(null, request.getVersion(), request.getGeneratedAt()));
        if (request.getVersion() != null) {
            payload.put("version", request.getVersion());
        }
        if (request.getGeneratedAt() != null) {
            payload.put("generatedAt", request.getGeneratedAt());
        }

        AiRegistry metadata = repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        SNAPSHOT_METADATA_REGISTRY_TYPE,
                        SNAPSHOT_METADATA_REGISTRY_KEY,
                        SNAPSHOT_METADATA_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SNAPSHOT_METADATA_SCOPE_KEY)
                .orElseGet(() -> AiRegistry.builder()
                        .registryType(SNAPSHOT_METADATA_REGISTRY_TYPE)
                        .registryKey(SNAPSHOT_METADATA_REGISTRY_KEY)
                        .componentType(SNAPSHOT_METADATA_COMPONENT_TYPE)
                        .scope(Scope.SYSTEM)
                        .scopeKey(SNAPSHOT_METADATA_SCOPE_KEY)
                        .build());
        metadata.setPayload(payload.toString());
        metadata.setSource(resolvedSnapshot.source);
        metadata.setSourceRef(resolvedSnapshot.location);
        metadata.setEmbedding(null);
        repository.save(metadata);
    }

    private void pruneObsoleteDefinitions(
            RegistryIngestionRequest request,
            SnapshotMetadata previousSnapshotMetadata) {
        Set<String> canonicalIds = request.getComponents() != null
                ? new LinkedHashSet<>(request.getComponents().keySet())
                : Set.of();
        List<AiRegistry> existingDefinitions =
                repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                        REGISTRY_TYPE_COMPONENT_DEF,
                        COMPONENT_DEF_COMPONENT_TYPE,
                        Scope.SYSTEM,
                        SNAPSHOT_METADATA_SCOPE_KEY);
        if (existingDefinitions == null || existingDefinitions.isEmpty()) {
            return;
        }

        List<AiRegistry> obsoleteDefinitions = existingDefinitions.stream()
                .filter(definition -> !canonicalIds.contains(definition.getRegistryKey()))
                .toList();
        if (obsoleteDefinitions.isEmpty()) {
            return;
        }

        deleteObsoleteRagDocuments(obsoleteDefinitions, previousSnapshotMetadata);
        repository.deleteAllInBatch(obsoleteDefinitions);
        log.info("AI registry bootstrap pruned {} obsolete component definitions.", obsoleteDefinitions.size());
    }

    private void deleteObsoleteRagDocuments(
            List<AiRegistry> obsoleteDefinitions,
            SnapshotMetadata previousSnapshotMetadata) {
        if (previousSnapshotMetadata == null || previousSnapshotMetadata.releaseId() == null) {
            return;
        }
        List<String> documentIds = new ArrayList<>();
        for (AiRegistry definition : obsoleteDefinitions) {
            String payload = definition.getPayload();
            String contentHash = RagDocumentIdentity.sha256(payload != null ? payload : "");
            documentIds.add(RagDocumentIdentity.buildDocumentId(
                    null,
                    null,
                    definition.getRegistryKey(),
                    previousSnapshotMetadata.releaseId(),
                    RagResourceTypes.COMPONENT_DEFINITION,
                    contentHash,
                    0));
        }
        ragVectorStoreService.deleteDocuments(documentIds);
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        return HexFormat.of().formatHex(digest);
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

    private record SnapshotMetadata(
            String snapshotHash,
            String version,
            String generatedAt,
            String releaseId) {
    }
}
