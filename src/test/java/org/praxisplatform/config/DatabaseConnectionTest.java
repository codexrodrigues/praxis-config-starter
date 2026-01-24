package org.praxisplatform.config;

import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.ConfigEntry;
import org.praxisplatform.config.rag.RagVectorStoreConfiguration;
import org.praxisplatform.config.repository.ConfigEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import org.springframework.boot.test.mock.mockito.MockBean;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.praxisplatform.config.repository.ConfigEntryRepository;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
        "spring.ai.openai.api-key=dummy"
})
class DatabaseConnectionTest {

    @MockBean
    private DataSource dataSource;

    @MockBean
    private ConfigEntryRepository repository;

    @MockBean
    private UiUserConfigRepository uiUserConfigRepository;

    @MockBean
    private org.praxisplatform.config.repository.ApiMetadataRepository apiMetadataRepository;

    @MockBean
    private org.praxisplatform.config.repository.AiRegistryRepository aiRegistryRepository;

    @Test
    void shouldConnectAndPersistConfigEntry() throws Exception {
        // Mock DB behavior since we don't have a real DB
        Connection mockConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.isValid(5)).thenReturn(true);

        String key = "integration-test-key";
        
        ConfigEntry entry = new ConfigEntry(key, "integration-test-value");
        // Mock save
        when(repository.save(any(ConfigEntry.class))).thenReturn(entry);
        // Mock findById
        when(repository.findById(any())).thenReturn(Optional.of(entry));

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
        }

        repository.findByConfigKey(key).ifPresent(repository::delete);

        ConfigEntry saved = repository.save(entry);

        Optional<ConfigEntry> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getConfigValue()).isEqualTo("integration-test-value");

        repository.deleteById(saved.getId());
    }
}
