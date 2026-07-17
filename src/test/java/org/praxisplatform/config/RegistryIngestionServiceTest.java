package org.praxisplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.registry.AiRegistryComponentDefinitionsChangedEvent;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.EmbeddingService;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(java.util.Optional.empty());
        when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));
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

    private JsonNode authoringManifest(AiRegistry definition) throws Exception {
        return objectMapper.readTree(definition.getPayload())
                .path("componentDefinition")
                .path("jsonSchema")
                .path("authoringManifest");
    }
}
