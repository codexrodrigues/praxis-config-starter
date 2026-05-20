package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.ai.document.Document;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class RegistryIngestionServiceIdentityTest {

    @Mock
    private AiRegistryRepository repository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private RagVectorStoreService ragVectorStoreService;

    private RegistryIngestionService service;

    @BeforeEach
    void setUp() {
        service = new RegistryIngestionService(
                repository,
                new ObjectMapper(),
                embeddingService,
                ragVectorStoreService,
                new AgenticAuthoringManifestContractValidator());
    }

    @Test
    void shouldBuildReleaseScopedDocumentIdAndMetadata() {
        RegistryIngestionRequest.IoEntry input = RegistryIngestionRequest.IoEntry.builder()
                .name("value")
                .type("string")
                .required(true)
                .build();
        RegistryIngestionRequest.ComponentEntry component = RegistryIngestionRequest.ComponentEntry.builder()
                .description("Table component")
                .inputs(List.of(input))
                .outputs(List.of())
                .build();
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("registry-v2")
                .generatedAt("2026-02-22T12:00:00Z")
                .components(Map.of("table", component))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestRegistry(request, "tenant-a", "prod");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        Document document = captor.getValue().get(0);

        assertThat(document.getId()).startsWith("tenant-a/prod/table/registry-v2/component_definition/");
        assertThat(document.getId()).endsWith("/0");

        Object contentHash = document.getMetadata().get(RagMetadataKeys.CONTENT_HASH);
        assertThat(contentHash).isInstanceOf(String.class);
        assertThat(((String) contentHash)).hasSize(64);
        assertThat(document.getId()).contains("/" + contentHash + "/0");

        assertThat(document.getMetadata().get(RagMetadataKeys.RELEASE_ID)).isEqualTo("registry-v2");
        assertThat(document.getMetadata().get(RagMetadataKeys.COMPONENT_ID)).isEqualTo("table");
        assertThat(document.getMetadata().get(RagMetadataKeys.DOC_TYPE)).isEqualTo("component_definition");
        assertThat(document.getMetadata().get(RagMetadataKeys.CHUNK_INDEX)).isEqualTo(0);
        assertThat(document.getMetadata().get(RagMetadataKeys.TENANT_ID)).isEqualTo("tenant-a");
        assertThat(document.getMetadata().get(RagMetadataKeys.ENVIRONMENT)).isEqualTo("prod");
        assertThat(document.getMetadata().get(RagMetadataKeys.VERSION)).isEqualTo("registry-v2");
        assertThat(document.getMetadata().get(RagMetadataKeys.AI_VISIBILITY)).isEqualTo("allow");
    }

    @Test
    void shouldProjectAuthoringManifestIntoRagMetadataAndContent() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", "1.0.0");
        manifest.put("componentId", "table");
        manifest.put("manifestVersion", "2.0.0");
        manifest.putArray("editableTargets")
                .addObject()
                .put("kind", "column")
                .put("resolver", "column-by-field");
        manifest.putArray("validators")
                .addObject()
                .put("validatorId", "target-column-exists");
        ObjectNode operation = manifest.putArray("operations").addObject();
        operation.put("operationId", "column.header.set");
        operation.putObject("target")
                .put("kind", "column")
                .put("resolver", "column-by-field");
        operation.putObject("inputSchema")
                .put("type", "object")
                .putArray("required")
                .add("header");
        operation.putArray("preconditions").add("config-initialized");
        operation.putArray("validators").add("target-column-exists");
        operation.putArray("effects")
                .addObject()
                .put("kind", "merge-by-key")
                .put("path", "columns[]")
                .put("key", "field");
        operation.putArray("affectedPaths").add("columns[].header");
        operation.put("submissionImpact", "visual-only");

        RegistryIngestionRequest.ComponentEntry component = RegistryIngestionRequest.ComponentEntry.builder()
                .description("Table component")
                .inputs(List.of())
                .outputs(List.of())
                .build();
        component.addAdditionalProperty("authoringManifest", manifest);
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("registry-v2")
                .components(Map.of("table", component))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestRegistry(request, null, null);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        Document document = captor.getValue().get(0);

        assertThat(document.getText()).contains("AuthoringManifest:");
        assertThat(document.getText()).contains("column.header.set");
        assertThat(document.getMetadata().get(RagMetadataKeys.AUTHORING_MANIFEST_VERSION)).isEqualTo("2.0.0");
        assertThat(document.getMetadata().get(RagMetadataKeys.AUTHORING_OPERATION_COUNT)).isEqualTo(1);
        assertThat(document.getMetadata().get(RagMetadataKeys.AUTHORING_TARGET_COUNT)).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidAuthoringManifestShapeOnIngestion() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", "1.0.0");
        manifest.put("componentId", "table");
        manifest.putArray("editableTargets");
        manifest.putArray("validators");
        ObjectNode operation = manifest.putArray("operations").addObject();
        operation.put("operationId", "toolbar.visibility.set");
        operation.putObject("target")
                .put("kind", "toolbar")
                .put("resolver", "toolbar-config");
        operation.putArray("effects").addObject().put("kind", "set-value").put("path", "toolbar.visible");
        operation.putArray("affectedPaths").add("toolbar.visible");
        operation.putArray("preconditions").add("config-initialized");
        operation.putArray("validators").add("missing-validator");
        operation.put("submissionImpact", "visual-only");

        RegistryIngestionRequest.ComponentEntry component = RegistryIngestionRequest.ComponentEntry.builder()
                .description("Table component")
                .inputs(List.of())
                .outputs(List.of())
                .build();
        component.addAdditionalProperty("authoringManifest", manifest);
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .components(Map.of("table", component))
                .build();

        assertThatThrownBy(() -> service.ingestRegistry(request, null, null))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Error processing component: table")
                .hasCauseInstanceOf(ConfigurationIngestionException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).hasMessageContaining("Invalid authoringManifest");
                    assertThat(error.getCause()).hasMessageContaining("target.kind is not declared");
                    assertThat(error.getCause()).hasMessageContaining("unknown validator");
                });
    }

    @Test
    void shouldIngestMultiChunksAndInvokePurge() {
        RegistryIngestionRequest.ChunkEntry chunk1 = RegistryIngestionRequest.ChunkEntry.builder()
                .chunkIndex(0)
                .chunkKind("intro")
                .content("Introduction content")
                .sourcePointer("/path/to/intro")
                .contentHash("hash1")
                .sourceKind("component_definition")
                .sourceId("component-x")
                .corpusVersion("1.0.0")
                .aiVisibility("mask")
                .embeddingProfile("gemini-768")
                .build();

        RegistryIngestionRequest.ChunkEntry chunk2 = RegistryIngestionRequest.ChunkEntry.builder()
                .chunkIndex(1)
                .chunkKind("methods")
                .content("Methods content")
                .sourcePointer("/path/to/methods")
                .contentHash("hash2")
                .sourceKind("component_definition")
                .sourceId("component-x")
                .corpusVersion("1.0.0")
                .build();

        RegistryIngestionRequest.ComponentEntry component = RegistryIngestionRequest.ComponentEntry.builder()
                .description("Multi-chunk component")
                .inputs(List.of())
                .outputs(List.of())
                .chunks(List.of(chunk1, chunk2))
                .build();

        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("registry-v2")
                .components(Map.of("component-x", component))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(ragVectorStoreService.corpusReleaseStatus("tenant-b", "staging", "registry-v2", 2))
                .thenReturn(new RagVectorStoreService.RagCorpusReleaseStatus(
                        true,
                        true,
                        "tenant-b",
                        "staging",
                        "registry-v2",
                        2,
                        2,
                        1,
                        Map.of("intro", 1L, "methods", 1L),
                        Map.of("allow", 1L, "mask", 1L),
                        List.of(),
                        "2026-05-19T10:00:00Z",
                        List.of()));

        RegistryIngestionService.RegistryReindexResult result =
                service.reindexRegistry(request, "tenant-b", "staging");

        InOrder inOrder = inOrder(ragVectorStoreService);
        inOrder.verify(ragVectorStoreService)
                .deleteDocumentsByScope("tenant-b", "staging", "registry-v2", "component-x", "component_definition");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        inOrder.verify(ragVectorStoreService).upsertDocuments(captor.capture());
        List<Document> docs = captor.getValue();
        assertThat(docs).hasSize(2);

        Document doc1 = docs.get(0);
        assertThat(doc1.getId()).isEqualTo("tenant-b/staging/component-x/registry-v2/component_definition/intro/hash1/0");
        assertThat(doc1.getText()).isEqualTo("Introduction content");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.SOURCE_KIND)).isEqualTo("component_definition");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.SOURCE_ID)).isEqualTo("component-x");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.SOURCE_POINTER)).isEqualTo("/path/to/intro");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.CHUNK_KIND)).isEqualTo("intro");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.CORPUS_VERSION)).isEqualTo("1.0.0");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.AI_VISIBILITY)).isEqualTo("mask");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.EMBEDDING_PROFILE)).isEqualTo("gemini-768");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.TENANT_ID)).isEqualTo("tenant-b");
        assertThat(doc1.getMetadata().get(RagMetadataKeys.ENVIRONMENT)).isEqualTo("staging");

        Document doc2 = docs.get(1);
        assertThat(doc2.getId()).isEqualTo("tenant-b/staging/component-x/registry-v2/component_definition/methods/hash2/1");
        assertThat(doc2.getText()).isEqualTo("Methods content");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.SOURCE_KIND)).isEqualTo("component_definition");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.SOURCE_ID)).isEqualTo("component-x");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.SOURCE_POINTER)).isEqualTo("/path/to/methods");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.CHUNK_KIND)).isEqualTo("methods");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.CORPUS_VERSION)).isEqualTo("1.0.0");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.TENANT_ID)).isEqualTo("tenant-b");
        assertThat(doc2.getMetadata().get(RagMetadataKeys.ENVIRONMENT)).isEqualTo("staging");

        assertThat(result.releaseId()).isEqualTo("registry-v2");
        assertThat(result.componentCount()).isEqualTo(1);
        assertThat(result.expectedChunkCount()).isEqualTo(2);
        assertThat(result.publishedChunkCount()).isEqualTo(2);
        assertThat(result.components()).hasSize(1);
        assertThat(result.components().get(0).chunkKinds()).containsExactly("intro", "methods");
        assertThat(result.corpusStatus()).isNotNull();
        assertThat(result.corpusStatus().reconciled()).isTrue();
    }

    @Test
    void shouldPurgeByChunkSourceScopeWhenSourceIdDiffersFromComponentMapKey() {
        RegistryIngestionRequest.ChunkEntry chunk = RegistryIngestionRequest.ChunkEntry.builder()
                .chunkIndex(0)
                .chunkKind("summary")
                .content("Source scoped content")
                .sourcePointer("praxis-ui-angular/components/source-component.ts")
                .contentHash("hash3")
                .sourceKind("component_definition")
                .sourceId("canonical-source-id")
                .corpusVersion("1.0.0")
                .build();

        RegistryIngestionRequest.ComponentEntry component = RegistryIngestionRequest.ComponentEntry.builder()
                .description("Component with canonical source id")
                .inputs(List.of())
                .outputs(List.of())
                .chunks(List.of(chunk))
                .build();

        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("registry-v3")
                .components(Map.of("map-key-component", component))
                .build();

        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestRegistry(request, "tenant-c", "prod");

        verify(ragVectorStoreService)
                .deleteDocumentsByScope("tenant-c", "prod", "registry-v3", "canonical-source-id", "component_definition");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(ragVectorStoreService).upsertDocuments(captor.capture());
        Document document = captor.getValue().get(0);
        assertThat(document.getId())
                .isEqualTo("tenant-c/prod/canonical-source-id/registry-v3/component_definition/summary/hash3/0");
        assertThat(document.getMetadata().get(RagMetadataKeys.COMPONENT_ID)).isEqualTo("canonical-source-id");
        assertThat(document.getMetadata().get(RagMetadataKeys.RESOURCE_ID)).isEqualTo("canonical-source-id");
    }
}
