package org.praxisplatform.config.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

@Tag("unit")
class AiRegistryBootstrapServiceTest {

    private static final String SNAPSHOT = """
            {
              "version": "snapshot-v1",
              "generatedAt": "2026-04-23T00:00:00Z",
              "components": {
                "praxis-table": {
                  "description": "Table",
                  "authoringManifest": {
                    "operations": [
                      {
                        "operationId": "columns.add"
                      }
                    ]
                  },
                  "chunks": [
                    {
                      "chunkIndex": 0,
                      "chunkKind": "summary",
                      "content": "Table summary"
                    },
                    {
                      "chunkIndex": 1,
                      "chunkKind": "authoring_manifest",
                      "content": "Table authoring manifest"
                    }
                  ]
                }
              }
            }
            """;

    private final RegistryIngestionService ingestionService = org.mockito.Mockito.mock(RegistryIngestionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResourceLoader resourceLoader = org.mockito.Mockito.mock(ResourceLoader.class);
    private final AiRegistryBootstrapProperties properties = new AiRegistryBootstrapProperties();
    private final AiRegistryStatusService statusService = org.mockito.Mockito.mock(AiRegistryStatusService.class);
    private final AiRegistryBootstrapState state = new AiRegistryBootstrapState();
    private final AiRegistryRepository repository = org.mockito.Mockito.mock(AiRegistryRepository.class);
    private final RagVectorStoreService ragVectorStoreService = org.mockito.Mockito.mock(RagVectorStoreService.class);

    @Test
    void skipsWhenRegistryIsReadyAndSnapshotHashMatches() throws Exception {
        String snapshotHash = sha256(SNAPSHOT);
        when(resourceLoader.getResource(anyString())).thenReturn(snapshotResource());
        when(statusService.getStatus()).thenReturn(readyStatus());
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                anyString(), anyString(), anyString(), any(Scope.class), anyString()))
                .thenReturn(Optional.of(snapshotMetadata(snapshotHash)));

        service().bootstrapIfNeeded();

        verify(ingestionService, never()).ingestRegistry(any(), isNull(), isNull());
        assertThat(state.isSkipped()).isTrue();
        assertThat(state.getSkipReason()).isEqualTo("snapshot-current");
        assertThat(state.getSnapshotHash()).isEqualTo(snapshotHash);
        assertThat(state.getPreviousSnapshotHash()).isEqualTo(snapshotHash);
        assertThat(state.getSnapshotComponentCount()).isEqualTo(1);
    }

    @Test
    void refreshesWhenRegistryIsReadyButSnapshotHashDiffers() throws Exception {
        when(resourceLoader.getResource(anyString())).thenReturn(snapshotResource());
        when(statusService.getStatus()).thenReturn(readyStatus());
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                anyString(), anyString(), anyString(), any(Scope.class), anyString()))
                .thenReturn(Optional.of(snapshotMetadata("old-hash")), Optional.empty());

        service().bootstrapIfNeeded();

        verify(ingestionService).ingestRegistry(any(RegistryIngestionRequest.class), isNull(), isNull());
        ArgumentCaptor<AiRegistry> metadataCaptor = ArgumentCaptor.forClass(AiRegistry.class);
        verify(repository).save(metadataCaptor.capture());
        AiRegistry metadata = metadataCaptor.getValue();
        assertThat(metadata.getRegistryType()).isEqualTo("registry_snapshot_metadata");
        assertThat(metadata.getRegistryKey()).isEqualTo("component-definitions");
        assertThat(objectMapper.readTree(metadata.getPayload()).path("snapshotHash").asText())
                .isEqualTo(sha256(SNAPSHOT));
        assertThat(objectMapper.readTree(metadata.getPayload()).path("componentCount").asLong())
                .isEqualTo(1);
        assertThat(objectMapper.readTree(metadata.getPayload()).path("authoringManifestCount").asLong())
                .isEqualTo(1);
        assertThat(objectMapper.readTree(metadata.getPayload()).path("chunkedComponentCount").asLong())
                .isEqualTo(1);
        assertThat(objectMapper.readTree(metadata.getPayload()).path("chunkCount").asLong())
                .isEqualTo(2);
        assertThat(state.isSucceeded()).isTrue();
        assertThat(state.getPreviousSnapshotHash()).isEqualTo("old-hash");
    }

    @Test
    void rotatesSnapshotMetadataVersionAndEtagOnlyWhenPayloadChanges() throws Exception {
        String originalHash = "old-hash";
        UUID originalEtag = UUID.fromString("123e4567-e89b-12d3-a456-426614174020");
        AiRegistry existingMetadata = snapshotMetadata(originalHash);
        existingMetadata.setVersion(8L);
        existingMetadata.setEtag(originalEtag);

        when(resourceLoader.getResource(anyString())).thenReturn(snapshotResource());
        when(statusService.getStatus()).thenReturn(readyStatus());
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                anyString(), anyString(), anyString(), any(Scope.class), anyString()))
                .thenReturn(Optional.of(existingMetadata), Optional.of(existingMetadata));

        service().bootstrapIfNeeded();

        ArgumentCaptor<AiRegistry> metadataCaptor = ArgumentCaptor.forClass(AiRegistry.class);
        verify(repository).save(metadataCaptor.capture());
        AiRegistry saved = metadataCaptor.getValue();
        assertThat(saved).isSameAs(existingMetadata);
        assertThat(saved.getVersion()).isEqualTo(9L);
        assertThat(saved.getEtag()).isNotEqualTo(originalEtag);
        assertThat(objectMapper.readTree(saved.getPayload()).path("snapshotHash").asText())
                .isEqualTo(sha256(SNAPSHOT));
    }

    @Test
    void prunesObsoleteDefinitionsAndDeletesDerivedRagDocuments() throws Exception {
        String previousVersion = "snapshot-v0";
        String previousGeneratedAt = "2026-04-22T00:00:00Z";
        when(resourceLoader.getResource(anyString())).thenReturn(snapshotResource());
        when(statusService.getStatus()).thenReturn(readyStatus());
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                anyString(), anyString(), anyString(), any(Scope.class), anyString()))
                .thenReturn(Optional.of(snapshotMetadata("old-hash", previousVersion, previousGeneratedAt)), Optional.empty());
        AiRegistry obsolete = AiRegistry.builder()
                .registryType("component_definition")
                .registryKey("legacy-component")
                .componentType("component-definition")
                .scope(Scope.SYSTEM)
                .scopeKey("GLOBAL")
                .payload("{\"componentDefinition\":{\"id\":\"legacy-component\"}}")
                .build();
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition", "component-definition", Scope.SYSTEM, "GLOBAL"))
                .thenReturn(List.of(obsolete));

        service().bootstrapIfNeeded();

        verify(repository).deleteAllInBatch(List.of(obsolete));
        ArgumentCaptor<List<String>> deletedIds = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).deleteDocuments(deletedIds.capture());
        String expectedReleaseId = org.praxisplatform.config.rag.RagDocumentIdentity.resolveReleaseId(
                null, previousVersion, previousGeneratedAt);
        String expectedId = org.praxisplatform.config.rag.RagDocumentIdentity.buildDocumentId(
                null,
                null,
                "legacy-component",
                expectedReleaseId,
                RagResourceTypes.COMPONENT_DEFINITION,
                org.praxisplatform.config.rag.RagDocumentIdentity.sha256(obsolete.getPayload()),
                0);
        assertThat(deletedIds.getValue()).containsExactly(expectedId);
    }

    @Test
    void failsClosedWhenSnapshotPreflightRejectsCorpusAndDoesNotPublishMetadata() {
        when(resourceLoader.getResource(anyString())).thenReturn(snapshotResource());
        doThrow(new org.praxisplatform.config.exception.ConfigurationIngestionException(
                "Registry chunk exceeds UTF-8 byte limit: componentId=praxis-table, chunkIndex=1, "
                        + "chunkKind=authoring_manifest, observedBytes=8001, maxBytes=8000"))
                .when(ingestionService).preflight(any(RegistryIngestionRequest.class));

        service().bootstrapIfNeeded();

        verify(ingestionService, never()).ingestRegistry(any(), isNull(), isNull());
        verify(statusService, never()).getStatus();
        verify(repository, never()).save(any());
        verify(repository, never()).deleteAllInBatch(any());
        verify(ragVectorStoreService, never()).deleteDocuments(any());
        assertThat(state.isSucceeded()).isFalse();
        assertThat(state.getError())
                .contains("componentId=praxis-table")
                .contains("observedBytes=8001")
                .doesNotContain("Table authoring manifest");
    }

    private AiRegistryBootstrapService service() {
        return new AiRegistryBootstrapService(
                ingestionService,
                objectMapper,
                resourceLoader,
                properties,
                statusService,
                state,
                repository,
                ragVectorStoreService);
    }

    private ByteArrayResource snapshotResource() {
        return new ByteArrayResource(SNAPSHOT.getBytes(StandardCharsets.UTF_8));
    }

    private AiRegistryStatusReport readyStatus() {
        return AiRegistryStatusReport.builder()
                .ready(true)
                .componentDefinitionCount(104)
                .templateCount(95)
                .build();
    }

    private AiRegistry snapshotMetadata(String snapshotHash) {
        return snapshotMetadata(snapshotHash, null, null);
    }

    private AiRegistry snapshotMetadata(String snapshotHash, String version, String generatedAt) {
        StringBuilder payload = new StringBuilder("{\"snapshotHash\":\"").append(snapshotHash).append("\"");
        if (version != null) {
            payload.append(",\"version\":\"").append(version).append("\"");
        }
        if (generatedAt != null) {
            payload.append(",\"generatedAt\":\"").append(generatedAt).append("\"");
        }
        payload.append("}");
        return AiRegistry.builder()
                .registryType("registry_snapshot_metadata")
                .registryKey("component-definitions")
                .componentType("registry-snapshot")
                .scope(Scope.SYSTEM)
                .scopeKey("GLOBAL")
                .payload(payload.toString())
                .build();
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
