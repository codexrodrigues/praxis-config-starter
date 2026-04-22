package org.praxisplatform.config.rag;

import org.praxisplatform.config.service.EmbeddingService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIdType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnClass(PgVectorStore.class)
@ConditionalOnProperty(name = "spring.ai.enabled", havingValue = "true", matchIfMissing = true)
@Conditional(RagVectorStoreEnabledCondition.class)
@EnableConfigurationProperties(RagChatAdvisorProperties.class)
public class RagVectorStoreConfiguration {

    @Value("${praxis.ai.rag.vector-store.table:vector_store}")
    private String tableName;

    @Value("${praxis.ai.rag.vector-store.schema:public}")
    private String schemaName;

    @Value("${praxis.ai.rag.vector-store.dimensions:0}")
    private int ragDimensions;

    @Value("${praxis.ai.rag.vector-store.initialize-schema:${spring.ai.vectorstore.pgvector.initialize-schema:false}}")
    private boolean initializeSchema;

    @Value("${praxis.ai.rag.vector-store.validate-schema:${spring.ai.vectorstore.pgvector.vector-table-validations-enabled:false}}")
    private boolean validateSchema;

    @Value("${spring.ai.embedding.provider:gemini}")
    private String embeddingProvider;

    @Value("${spring.ai.openai.embedding.options.dimensions:768}")
    private int openaiDimensions;

    @Value("${spring.ai.google.genai.embedding.text.options.dimensions:768}")
    private int geminiDimensions;

    @Bean
    public VectorStore ragVectorStore(ApplicationContext context, EmbeddingService embeddingService) {
        JdbcTemplate jdbcTemplate;
        if (context.containsBean("configJdbcTemplate")) {
            jdbcTemplate = context.getBean("configJdbcTemplate", JdbcTemplate.class);
        } else {
            jdbcTemplate = context.getBean(JdbcTemplate.class);
        }

        int dimensions = resolveDimensions();
        return PgVectorStore.builder(jdbcTemplate, new PraxisEmbeddingModel(embeddingService, dimensions))
                .schemaName(schemaName)
                .vectorTableName(tableName)
                .idType(PgIdType.TEXT)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.IVFFLAT)
                .dimensions(dimensions)
                .initializeSchema(initializeSchema)
                .vectorTableValidationsEnabled(validateSchema)
                .build();
    }

    private int resolveDimensions() {
        if (ragDimensions > 0) {
            return ragDimensions;
        }
        String provider = embeddingProvider != null ? embeddingProvider.trim().toLowerCase() : "";
        if ("openai".equals(provider)) {
            return openaiDimensions;
        }
        return geminiDimensions;
    }
}
