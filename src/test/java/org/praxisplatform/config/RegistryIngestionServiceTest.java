package org.praxisplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.registry.AiRegistryComponentDefinitionsChangedEvent;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.EmbeddingService;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class RegistryIngestionServiceTest {

    private RegistryIngestionService registryIngestionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AiRegistryRepository repository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private RagVectorStoreService ragVectorStoreService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static final String COMPONENT_ID = "demo-component";

    @BeforeEach
    void setUp() {
        registryIngestionService = new RegistryIngestionService(
                repository,
                objectMapper,
                embeddingService,
                ragVectorStoreService,
                new AgenticAuthoringManifestContractValidator(),
                eventPublisher);
        setupMocks();
    }

    private void setupMocks() {
        lenient().when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        lenient().when(embeddingService.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(ignored -> List.of(0.1f, 0.2f)).toList();
        });
        lenient().when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldIngestRegistryAndPersistVector() {
        // Build the DTO manually for the test
        RegistryIngestionRequest.IoEntry inputIo = RegistryIngestionRequest.IoEntry.builder()
            .name("title")
            .type("string")
            .required(true)
            .build();

        RegistryIngestionRequest.ComponentEntry componentEntry = RegistryIngestionRequest.ComponentEntry.builder()
            .description("Demo component for ingestion test")
            .inputs(List.of(inputIo))
            .outputs(List.of()) // Assuming outputs is a list, based on the DTO
            .build();

        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
            .components(Map.of(COMPONENT_ID, componentEntry))
            .build();

        registryIngestionService.ingestRegistry(request, null, null);

        verify(repository, times(1)).save(any(AiRegistry.class));
        verify(ragVectorStoreService, times(1)).upsertDocuments(any());
        verify(eventPublisher).publishEvent(any(AiRegistryComponentDefinitionsChangedEvent.class));
    }

    @Test
    void shouldIngestClasspathSnapshotWithExecutableAuthoringManifests() throws Exception {
        ClassPathResource resource = new ClassPathResource("ai-registry/registry-snapshot.json");
        RegistryIngestionRequest request;
        try (var input = resource.getInputStream()) {
            request = objectMapper.readValue(input, RegistryIngestionRequest.class);
        }

        registryIngestionService.ingestRegistry(request, null, null);

        ArgumentCaptor<AiRegistry> savedDefinitions = ArgumentCaptor.forClass(AiRegistry.class);
        verify(repository, times(request.getComponents().size())).save(savedDefinitions.capture());
        verify(ragVectorStoreService, times(request.getComponents().size())).upsertDocuments(any());

        AiRegistry tableDefinition = savedDefinitions.getAllValues().stream()
                .filter(definition -> "praxis-table".equals(definition.getRegistryKey()))
                .findFirst()
                .orElseThrow();
        AiRegistry formDefinition = savedDefinitions.getAllValues().stream()
                .filter(definition -> "praxis-dynamic-form".equals(definition.getRegistryKey()))
                .findFirst()
                .orElseThrow();

        assertThat(authoringManifest(tableDefinition).path("operations").size()).isGreaterThan(0);
        assertThat(authoringManifest(formDefinition).path("operations").size()).isGreaterThan(0);
    }

    @Test
    void acceptsChunkExactlyAtUtf8ByteLimit() {
        RegistryIngestionRequest request = requestWithChunks(Map.of(
                "at-limit", chunk(0, "docs", "a".repeat(RegistryIngestionService.MAX_CHUNK_UTF8_BYTES))));

        registryIngestionService.ingestRegistry(request, null, null);

        verify(repository).save(any(AiRegistry.class));
        verify(embeddingService).embed(anyString());
        verify(ragVectorStoreService).upsertDocuments(any());
    }

    @Test
    void rejectsOversizedChunkBeforeAnyPersistenceVectorOrEmbeddingEffect() {
        String secretPayload = "sensitive-" + "a".repeat(RegistryIngestionService.MAX_CHUNK_UTF8_BYTES);
        RegistryIngestionRequest request = requestWithChunks(Map.of(
                "oversized-component", chunk(7, "authoring_manifest", secretPayload)));

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                .hasMessageContaining("componentId=oversized-component")
                .hasMessageContaining("chunkIndex=7")
                .hasMessageContaining("chunkKind=authoring_manifest")
                .hasMessageContaining("observedBytes=8010")
                .hasMessageContaining("maxBytes=8000")
                .hasMessageNotContaining("sensitive-");

        verifyNoIngestionEffects();
    }

    @Test
    void rejectsNullChunkContentBeforeAnyEffect() {
        RegistryIngestionRequest request = requestWithChunks(Map.of(
                "null-content", chunk(4, "documentation", null)));

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                .hasMessageContaining("componentId=null-content")
                .hasMessageContaining("chunkIndex=4")
                .hasMessageContaining("chunkKind=documentation")
                .hasMessageContaining("reason=chunk content is null");

        verifyNoIngestionEffects();
    }

    @Test
    void rejectsNullChunkEntryBeforeAnyEffect() {
        List<RegistryIngestionRequest.ChunkEntry> chunks = new java.util.ArrayList<>();
        chunks.add(null);
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .components(Map.of(
                        "null-chunk",
                        RegistryIngestionRequest.ComponentEntry.builder()
                                .description("Null chunk entry")
                                .chunks(chunks)
                                .build()))
                .build();

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                .hasMessageContaining("componentId=null-chunk")
                .hasMessageContaining("chunkIndex=0")
                .hasMessageContaining("chunkKind=unknown")
                .hasMessageContaining("reason=chunk entry is null");

        verifyNoIngestionEffects();
    }

    @Test
    void rejectsEmptyChunkContentBeforeAnyEffect() {
        RegistryIngestionRequest request = requestWithChunks(Map.of(
                "empty-content", chunk(5, "runtime_contract", "")));

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                .hasMessageContaining("componentId=empty-content")
                .hasMessageContaining("chunkIndex=5")
                .hasMessageContaining("chunkKind=runtime_contract")
                .hasMessageContaining("reason=chunk content is blank");

        verifyNoIngestionEffects();
    }

    @Test
    void rejectsWhitespaceOnlyChunkContentBeforeAnyEffectWithoutLeakingIt() {
        String invalidContent = " \t\n ";
        RegistryIngestionRequest request = requestWithChunks(Map.of(
                "blank-content", chunk(6, "authoring_manifest", invalidContent)));

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                .hasMessageContaining("componentId=blank-content")
                .hasMessageContaining("chunkIndex=6")
                .hasMessageContaining("chunkKind=authoring_manifest")
                .hasMessageContaining("reason=chunk content is blank")
                .hasMessageNotContaining(invalidContent);

        verifyNoIngestionEffects();
    }

    @Test
    void rejectsWholeBatchWhenLaterComponentHasBlankContent() {
        Map<String, RegistryIngestionRequest.ComponentEntry> components = new java.util.LinkedHashMap<>();
        components.put("valid-first", component(chunk(0, "summary", "valid")));
        components.put("invalid-second", component(chunk(1, "documentation", "   ")));

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(
                RegistryIngestionRequest.builder().components(components).build(), null, null))
                .hasMessageContaining("componentId=invalid-second")
                .hasMessageContaining("reason=chunk content is blank");

        verifyNoIngestionEffects();
    }

    @Test
    void measuresMultibyteUnicodeAsUtf8Bytes() {
        String unicodeContent = "á".repeat(4_001);
        RegistryIngestionRequest request = requestWithChunks(Map.of(
                "unicode-component", chunk(3, "documentation", unicodeContent)));

        assertThat(unicodeContent).hasSize(4_001);
        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                .hasMessageContaining("observedBytes=8002")
                .hasMessageContaining("maxBytes=8000");

        verify(repository, never()).save(any());
        verify(embeddingService, never()).embed(anyString());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void rejectsWholeBatchWhenLaterComponentIsOversized() {
        Map<String, RegistryIngestionRequest.ComponentEntry> components = new java.util.LinkedHashMap<>();
        components.put("valid-first", component(chunk(0, "summary", "valid")));
        components.put("invalid-second", component(chunk(
                1,
                "runtime_contract",
                "b".repeat(RegistryIngestionService.MAX_CHUNK_UTF8_BYTES + 1))));

        assertThatThrownBy(() -> registryIngestionService.ingestRegistry(
                RegistryIngestionRequest.builder().components(components).build(), null, null))
                .hasMessageContaining("componentId=invalid-second");

        verify(repository, never()).save(any());
        verify(embeddingService, never()).embed(anyString());
        verify(ragVectorStoreService, never()).deleteDocumentsByScope(any(), any(), any(), any(), any());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void rejectsOversizedChunkAtEveryBatchPositionBeforeEffects() {
        for (int invalidPosition : List.of(0, 1, 2)) {
            List<RegistryIngestionRequest.ChunkEntry> chunks = new java.util.ArrayList<>();
            for (int index = 0; index < 3; index++) {
                chunks.add(chunk(
                        index,
                        "recipe",
                        index == invalidPosition
                                ? "x".repeat(RegistryIngestionService.MAX_CHUNK_UTF8_BYTES + 1)
                                : "valid"));
            }
            RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                    .components(Map.of(
                            "position-" + invalidPosition,
                            RegistryIngestionRequest.ComponentEntry.builder()
                                    .description("Position test")
                                    .chunks(chunks)
                                    .build()))
                    .build();

            assertThatThrownBy(() -> registryIngestionService.ingestRegistry(request, null, null))
                    .hasMessageContaining("chunkIndex=" + invalidPosition);
        }

        verify(repository, never()).save(any());
        verify(embeddingService, never()).embed(anyString());
        verify(ragVectorStoreService, never()).deleteDocumentsByScope(any(), any(), any(), any(), any());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
    }

    @Test
    void reconcilesOnlyMateriallyChangedComponentsWithinTheSameRelease() {
        Map<String, RegistryIngestionRequest.ComponentEntry> components = new java.util.LinkedHashMap<>();
        components.put("component-a", component(chunk(0, "summary", "Component A")));
        components.put("component-b", component(chunk(0, "summary", "Component B")));
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("release-v1")
                .components(components)
                .build();

        registryIngestionService.reindexRegistry(request, null, null);
        ArgumentCaptor<AiRegistry> initialDefinitions = ArgumentCaptor.forClass(AiRegistry.class);
        verify(repository, times(2)).save(initialDefinitions.capture());
        AiRegistry persistedComponentA = initialDefinitions.getAllValues().stream()
                .filter(definition -> "component-a".equals(definition.getRegistryKey()))
                .findFirst()
                .orElseThrow();
        clearInvocations(repository, embeddingService, ragVectorStoreService, eventPublisher);
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of(persistedComponentA));

        RegistryIngestionService.RegistryReindexResult result =
                registryIngestionService.reconcileRegistry(request, null, null, "release-v1");

        ArgumentCaptor<AiRegistry> reconciledDefinition = ArgumentCaptor.forClass(AiRegistry.class);
        verify(repository).save(reconciledDefinition.capture());
        assertThat(reconciledDefinition.getValue().getRegistryKey()).isEqualTo("component-b");
        verify(embeddingService).embed(anyString());
        verify(ragVectorStoreService).upsertDocuments(any());
        verify(eventPublisher).publishEvent(any(AiRegistryComponentDefinitionsChangedEvent.class));
        assertThat(result.componentCount()).isEqualTo(1);
        assertThat(result.expectedChunkCount()).isEqualTo(2);
        assertThat(result.publishedChunkCount()).isEqualTo(1);
    }

    @Test
    void performsCompleteReindexWhenReleaseIdentityChanges() {
        RegistryIngestionRequest request = RegistryIngestionRequest.builder()
                .version("release-v2")
                .components(Map.of(
                        "component-a", component(chunk(0, "summary", "Component A")),
                        "component-b", component(chunk(0, "summary", "Component B"))))
                .build();

        RegistryIngestionService.RegistryReindexResult result =
                registryIngestionService.reconcileRegistry(request, null, null, "release-v1");

        verify(repository, times(2)).save(any(AiRegistry.class));
        verify(embeddingService).embedAll(anyList());
        verify(embeddingService, never()).embed(anyString());
        verify(ragVectorStoreService, times(2)).upsertDocuments(any());
        assertThat(result.componentCount()).isEqualTo(2);
        assertThat(result.releaseId()).isEqualTo("release-v2");
    }

    @Test
    void reusesSemanticEmbeddingAndSkipsAlreadyPublishedRagContent() {
        RegistryIngestionRequest.ComponentEntry originalEntry =
                component(chunk(0, "summary", "Stable semantic content"));
        RegistryIngestionRequest originalRequest = RegistryIngestionRequest.builder()
                .version("release-v1")
                .components(Map.of("component-a", originalEntry))
                .build();
        registryIngestionService.reindexRegistry(originalRequest, null, null);
        ArgumentCaptor<AiRegistry> initialDefinition = ArgumentCaptor.forClass(AiRegistry.class);
        verify(repository).save(initialDefinition.capture());

        clearInvocations(repository, embeddingService, ragVectorStoreService, eventPublisher);
        RegistryIngestionRequest.ComponentEntry changedPayloadEntry =
                component(chunk(0, "summary", "Stable semantic content"));
        changedPayloadEntry.addAdditionalProperty(
                "editorialNote",
                objectMapper.getNodeFactory().textNode("Payload-only revision"));
        RegistryIngestionRequest changedRequest = RegistryIngestionRequest.builder()
                .version("release-v1")
                .components(Map.of("component-a", changedPayloadEntry))
                .build();
        when(repository.findAllByRegistryTypeAndComponentTypeAndScopeAndScopeKey(
                "component_definition",
                "component-definition",
                Scope.SYSTEM,
                "GLOBAL"))
                .thenReturn(List.of(initialDefinition.getValue()));
        String contentHash = RagDocumentIdentity.sha256("Stable semantic content");
        String documentId = RagDocumentIdentity.buildDocumentId(
                null,
                null,
                "component-a",
                "release-v1",
                "component_definition",
                "summary",
                contentHash,
                0);
        when(ragVectorStoreService.findDocumentIdsByRelease(
                null,
                null,
                "release-v1",
                "component_definition"))
                .thenReturn(Optional.of(Map.of(
                        new RagVectorStoreService.RagDocumentScope(
                                "component-a",
                                "component_definition"),
                        Set.of(documentId))));

        RegistryIngestionService.RegistryReindexResult result =
                registryIngestionService.reconcileRegistry(changedRequest, null, null, "release-v1");

        verify(repository).save(any(AiRegistry.class));
        verify(embeddingService, never()).embed(anyString());
        verify(ragVectorStoreService, never()).upsertDocuments(any());
        verify(ragVectorStoreService, never()).deleteDocuments(any());
        verify(ragVectorStoreService, never()).deleteDocumentsByScope(any(), any(), any(), any(), any());
        assertThat(result.componentCount()).isEqualTo(1);
        assertThat(result.publishedChunkCount()).isEqualTo(1);
    }

    private RegistryIngestionRequest requestWithChunks(
            Map<String, RegistryIngestionRequest.ChunkEntry> chunksByComponent) {
        Map<String, RegistryIngestionRequest.ComponentEntry> components = new java.util.LinkedHashMap<>();
        chunksByComponent.forEach((componentId, chunk) -> components.put(componentId, component(chunk)));
        return RegistryIngestionRequest.builder().components(components).build();
    }

    private RegistryIngestionRequest.ComponentEntry component(RegistryIngestionRequest.ChunkEntry chunk) {
        return RegistryIngestionRequest.ComponentEntry.builder()
                .description("Test component")
                .chunks(List.of(chunk))
                .build();
    }

    private RegistryIngestionRequest.ChunkEntry chunk(int index, String kind, String content) {
        return RegistryIngestionRequest.ChunkEntry.builder()
                .chunkIndex(index)
                .chunkKind(kind)
                .content(content)
                .build();
    }

    private void verifyNoIngestionEffects() {
        verifyNoInteractions(repository, embeddingService, ragVectorStoreService, eventPublisher);
    }

    private JsonNode authoringManifest(AiRegistry definition) throws Exception {
        return objectMapper.readTree(definition.getPayload())
                .path("componentDefinition")
                .path("jsonSchema")
                .path("authoringManifest");
    }
}
