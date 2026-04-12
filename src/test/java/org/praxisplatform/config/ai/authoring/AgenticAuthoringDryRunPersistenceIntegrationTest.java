package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiThreadStatus;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnEvent;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.repository.AiThreadRepository;
import org.praxisplatform.config.repository.AiTurnEventRepository;
import org.praxisplatform.config.repository.AiTurnRepository;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;
import org.praxisplatform.config.service.AiTurnEventService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
        classes = TestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agentic_authoring_dry_run_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:ai-stream-it-schema.sql",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                "spring.ai.openai.api-key=dummy",
                "spring.ai.vectorstore.pgvector.initialize-schema=false",
                "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false"
        })
@ActiveProfiles("test")
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
@Tag("integration")
class AgenticAuthoringDryRunPersistenceIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiThreadRepository threadRepository;

    @Autowired
    private AiTurnRepository turnRepository;

    @Autowired
    private AiTurnEventRepository turnEventRepository;

    private AgenticAuthoringDryRunService dryRunService;
    private AgenticAuthoringReplayAuditService replayAuditService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");

        dryRunService = new AgenticAuthoringDryRunService(objectMapper);
        AiTurnEventService turnEventService = new AiTurnEventService(
                turnEventRepository,
                turnRepository,
                objectMapper,
                new AiSensitiveDataRedactor());
        ReflectionTestUtils.setField(turnEventService, "eventSchemaVersion", "v1");
        ReflectionTestUtils.setField(turnEventService, "streamExpirySeconds", 900L);
        replayAuditService = new AgenticAuthoringReplayAuditService(turnEventService, objectMapper);
    }

    @Test
    void shouldPersistDryRunReplayAsTurnEventWithoutCallingLlm() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        Instant now = Instant.now();
        threadRepository.save(AiThread.builder()
                .threadId(threadId)
                .tenantId("tenant-a")
                .environment("dev")
                .userId("user-a")
                .componentType("praxis-dynamic-page-builder")
                .componentId("praxis-helpdesk-ui")
                .status(AiThreadStatus.ACTIVE)
                .summary("")
                .schemaHash("ae6e69c0d65cc4d0fd92631a82e4c0a86924f7539ee3645decafb66ca72c99ce")
                .createdAt(now)
                .lastUsedAt(now)
                .build());
        turnRepository.save(AiTurn.builder()
                .threadId(threadId)
                .turnId(turnId)
                .status(AiTurnStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());

        AgenticAuthoringDryRunResult dryRun = dryRunService.run(new AgenticAuthoringArtifactSource(configuredProperties()));
        JsonNode replayBundle = objectMapper.readTree(proofsDir()
                .resolve("authoring-replay-bundle.helpdesk-create-ticket.v0.json")
                .toFile());
        replayAuditService.appendReplayEvent(new AgenticAuthoringReplayAuditRequest(
                new AiPrincipalContext("tenant-a", "user-a", "dev", true),
                streamId,
                threadId,
                turnId,
                replayBundle,
                dryRun
        ));

        AiTurnEvent event = turnEventRepository.findFirstByStreamIdOrderBySeqAsc(streamId).orElseThrow();
        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(event.getEventType()).isEqualTo("authoring.replay");
        assertThat(event.getTenantId()).isEqualTo("tenant-a");
        assertThat(event.getUserId()).isEqualTo("user-a");
        assertThat(payload.path("replayBundle").path("profileId").asText()).isEqualTo("create-minimal-form");
        assertThat(payload.path("dryRun").path("valid").asBoolean()).isTrue();
        assertThat(payload.path("dryRun").path("gates")).hasSize(4);
        assertThat(payload.path("dryRun").has("patch")).isFalse();
    }

    private AgenticAuthoringArtifactProperties configuredProperties() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(proofsDir());
        return properties;
    }

    private Path proofsDir() {
        Path fromModuleDir = Path.of("..", "docs", "ai", "agentic-authoring", "proofs");
        if (Files.exists(fromModuleDir)) {
            return fromModuleDir;
        }
        return Path.of("docs", "ai", "agentic-authoring", "proofs");
    }
}
