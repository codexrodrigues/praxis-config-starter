package org.praxisplatform.config.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.EmbeddingService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("unit")
class RagVectorStoreConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RagVectorStoreConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EmbeddingService.class, () -> mock(EmbeddingService.class))
            .withPropertyValues(
                    "spring.ai.embedding.provider=mock",
                    "praxis.ai.rag.vector-store.validate-schema=false",
                    "praxis.ai.rag.vector-store.initialize-schema=false");

    @Test
    void shouldNotCreateVectorStoreWhenPraxisRagVectorStoreIsDisabled() {
        contextRunner
                .withPropertyValues("praxis.ai.rag.vector-store.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(VectorStore.class));
    }

    @Test
    void shouldNotCreateVectorStoreWhenEnvironmentAliasIsDisabled() {
        contextRunner
                .withPropertyValues("PRAXIS_AI_RAG_VECTOR_STORE_ENABLED=false")
                .run(context -> assertThat(context).doesNotHaveBean(VectorStore.class));
    }

    @Test
    void shouldCreateVectorStoreWhenPraxisRagVectorStoreIsEnabled() {
        contextRunner
                .withPropertyValues("praxis.ai.rag.vector-store.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(VectorStore.class));
    }
}
