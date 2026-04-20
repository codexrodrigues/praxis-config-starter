package org.praxisplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
    GoogleGenAiTextEmbeddingAutoConfiguration.class,
    GoogleGenAiChatAutoConfiguration.class,
    OpenAiAudioSpeechAutoConfiguration.class
})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
        "praxis.ai.rag.vector-store.enabled=false",
        "spring.ai.openai.api-key=dummy",
        "praxis.ai.registry.bootstrap.enabled=false"
})
@Tag("integration")
class RegistryIngestionServiceTest {

    @Autowired
    private RegistryIngestionService registryIngestionService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiRegistryRepository repository;

    @MockBean
    private UiUserConfigRepository uiUserConfigRepository;

    @MockBean
    private org.praxisplatform.config.repository.ApiMetadataRepository apiMetadataRepository;

    @MockBean
    private org.praxisplatform.config.repository.ConfigEntryRepository configEntryRepository;

    @MockBean
    private org.praxisplatform.config.repository.AiThreadRepository aiThreadRepository;

    @MockBean
    private org.praxisplatform.config.repository.AiTurnRepository aiTurnRepository;

    @MockBean
    private org.praxisplatform.config.repository.AiTurnEventRepository aiTurnEventRepository;

    @MockBean
    private org.praxisplatform.config.repository.AiMessageRepository aiMessageRepository;

    @MockBean
    private org.praxisplatform.config.repository.AiActionRepository aiActionRepository;

    @MockBean(name = "transactionManager")
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    // Use MockBean for the model to ensure we control it fully, assuming it's not already mocked by TestAiConfig
    @MockBean
    private GoogleGenAiTextEmbeddingModel googleGenAiTextEmbeddingModel;

    private static final String COMPONENT_ID = "demo-component";
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";

    @BeforeEach
    void cleanBefore() {
        // deleteExisting(); // No DB to clean
        setupMocks();
    }

    private void setupMocks() {
        float[] dummyEmbedding = new float[768];
        for (int i = 0; i < 768; i++) {
            dummyEmbedding[i] = 0.1f;
        }
        Embedding embedding = new Embedding(dummyEmbedding, 0);
        EmbeddingResponse response = new EmbeddingResponse(List.of(embedding));
        when(googleGenAiTextEmbeddingModel.call(any(org.springframework.ai.embedding.EmbeddingRequest.class))).thenReturn(response);
    }

    @AfterEach
    void cleanAfter() {
        // deleteExisting(); // No DB to clean
    }

    // private void deleteExisting() { ... } // Removed

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

        registryIngestionService.ingestRegistry(request, null, null); // Chamada atualizada

        // Verify save was called
        verify(repository, times(1)).save(any(AiRegistry.class));
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
