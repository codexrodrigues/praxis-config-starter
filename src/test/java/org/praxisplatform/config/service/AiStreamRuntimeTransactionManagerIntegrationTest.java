package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "spring.datasource.url=jdbc:h2:mem:ai_stream_runtime_tm_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:ai-stream-it-schema.sql",
                "spring.ai.openai.api-key=dummy",
                "spring.ai.vectorstore.pgvector.initialize-schema=false",
                "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false",
                "praxis.ai.security.corporate-mode=true"
        })
@ActiveProfiles("test")
@Import(AiStreamRuntimeTransactionManagerIntegrationTest.MultiTransactionManagerTestConfig.class)
@EnableAutoConfiguration(exclude = {
        GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
        GoogleGenAiTextEmbeddingAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
})
class AiStreamRuntimeTransactionManagerIntegrationTest {

    static final AtomicInteger API_TX_BEGIN_COUNT = new AtomicInteger();
    static final AtomicInteger CONFIG_TX_BEGIN_COUNT = new AtomicInteger();

    @Autowired
    private AiTurnEventService turnEventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
        API_TX_BEGIN_COUNT.set(0);
        CONFIG_TX_BEGIN_COUNT.set(0);
    }

    @Test
    void shouldUseConfigTransactionManagerAtRuntimeForAppendAndReplay() {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        seedThreadAndTurn(threadId, turnId);

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        int apiBefore = API_TX_BEGIN_COUNT.get();
        int configBefore = CONFIG_TX_BEGIN_COUNT.get();

        AiTurnEventEnvelope appended = turnEventService.appendEvent(
                principal,
                streamId,
                threadId,
                turnId,
                "status",
                Map.of("state", "started"));
        var replay = turnEventService.replay(streamId, null, principal);

        assertThat(appended.getSeq()).isEqualTo(1L);
        assertThat(replay.events()).hasSize(1);
        assertThat(CONFIG_TX_BEGIN_COUNT.get()).isGreaterThan(configBefore);
        assertThat(API_TX_BEGIN_COUNT.get()).isEqualTo(apiBefore);
    }

    private void seedThreadAndTurn(UUID threadId, UUID turnId) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                insert into ai_thread (
                    thread_id, tenant_id, environment, user_id, component_type, component_id,
                    route_key, title, status, summary, schema_hash, variant_id, last_config_etag,
                    created_at, last_used_at, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                threadId,
                "tenant-a",
                "prod",
                "user-a",
                "praxis-table",
                "component-x",
                "/",
                "Thread",
                "ACTIVE",
                "",
                null,
                null,
                null,
                Timestamp.from(now),
                Timestamp.from(now),
                0L);
        jdbcTemplate.update(
                """
                insert into ai_turn (
                    thread_id, turn_id, status, created_at, updated_at, expires_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                threadId,
                turnId,
                "PROCESSING",
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(600)));
    }

    @TestConfiguration
    static class MultiTransactionManagerTestConfig {

        @Bean(name = "transactionManager")
        @Primary
        PlatformTransactionManager apiTransactionManager(EntityManagerFactory entityManagerFactory) {
            return new CountingJpaTransactionManager(entityManagerFactory, API_TX_BEGIN_COUNT);
        }

        @Bean(name = "configTransactionManager")
        PlatformTransactionManager configTransactionManager(EntityManagerFactory entityManagerFactory) {
            return new CountingJpaTransactionManager(entityManagerFactory, CONFIG_TX_BEGIN_COUNT);
        }
    }

    static class CountingJpaTransactionManager extends JpaTransactionManager {

        private final AtomicInteger beginCounter;

        CountingJpaTransactionManager(EntityManagerFactory entityManagerFactory, AtomicInteger beginCounter) {
            super(entityManagerFactory);
            this.beginCounter = beginCounter;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            beginCounter.incrementAndGet();
            super.doBegin(transaction, definition);
        }
    }
}
