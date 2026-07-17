package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiThreadStatus;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.repository.AiThreadRepository;
import org.praxisplatform.config.repository.AiTurnRepository;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.DomainFederationQueryService;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetService;
import org.praxisplatform.config.service.UserConfigService;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = TestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agentic_authoring_apply_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:agentic-apply-it-schema.sql",
                "spring.ai.openai.api-key=dummy",
                "spring.ai.vectorstore.pgvector.initialize-schema=false",
                "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                "praxis.domain-360.enabled=false",
                "praxis.domain-federation.enabled=false",
                "praxis.domain-knowledge.change-sets.enabled=false",
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
class AgenticAuthoringTerminalApplyPersistenceIntegrationTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-a";
    private static final String ENVIRONMENT = "dev";
    private static final String COMPONENT_ID = "absence-dashboard";

    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AiThreadRepository threadRepository;
    @Autowired private AiTurnRepository turnRepository;
    @Autowired private AiTurnEventService turnEventService;
    @Autowired private UserConfigService userConfigService;
    @Autowired private AiApiKeyProtectionService apiKeyProtectionService;

    @MockBean private DomainFederationQueryService domainFederationQueryService;
    @MockBean private DomainKnowledgeChangeSetService domainKnowledgeChangeSetService;

    @BeforeEach
    void resetPersistence() {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("delete from ui_user_config");
        jdbcTemplate.execute("set referential_integrity true");
    }

    @Test
    void shouldReplayPersistAndRejectReuseOfTheSameTerminalCreateResult() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext(TENANT, USER, ENVIRONMENT, true);
        persistTurn(threadId, turnId);

        JsonNode compiledPatch = compiledPatch();
        AgenticAuthoringSemanticDecision semanticDecision = semanticDecision();
        ObjectNode resultPayload = terminalPayload(compiledPatch, semanticDecision, "create", null);
        AiTurnEventEnvelope terminal = turnEventService.appendEvent(
                principal,
                streamId,
                threadId,
                turnId,
                "result",
                resultPayload);

        AiTurnEventService.ReplayResult replay = turnEventService.replay(streamId, null, principal);
        assertThat(replay.events()).hasSize(1);
        assertThat(replay.events().getFirst().getEventId()).isEqualTo(terminal.getEventId());
        assertThat(replay.events().getFirst().getPayload().path("preview").path("compiledFormPatch"))
                .isEqualTo(compiledPatch);

        AgenticAuthoringApplyRequest request = new AgenticAuthoringApplyRequest(
                compiledPatch,
                "praxis-dynamic-page",
                COMPONENT_ID,
                "user",
                null,
                semanticDecision,
                streamId,
                terminal.getEventId());
        AgenticAuthoringApplyService applyService = new AgenticAuthoringApplyService(
                userConfigService,
                apiKeyProtectionService,
                turnEventService,
                objectMapper);

        AgenticAuthoringApplyResult applied = applyService.apply(request, principal, USER, null);

        assertThat(applied.applied()).isTrue();
        assertThat(applied.version()).isEqualTo(1L);
        assertThat(applied.payload()).isEqualTo(compiledPatch.path("patch").path("page"));
        UserConfigService.ResolvedConfig persisted = userConfigService.getByScope(
                        UserConfigService.Scope.USER,
                        TENANT,
                        USER,
                        "praxis-dynamic-page",
                        COMPONENT_ID,
                        ENVIRONMENT)
                .orElseThrow();
        assertThat(objectMapper.readTree(persisted.config().getPayload()))
                .isEqualTo(compiledPatch.path("patch").path("page"));
        JsonNode persistedTags = objectMapper.readTree(persisted.config().getTags());
        assertThat(persistedTags.path("authoringResultEventId").asText())
                .isEqualTo(terminal.getEventId().toString());

        assertThatThrownBy(() -> applyService.apply(request, principal, USER, null))
                .isInstanceOf(UserConfigService.PreconditionFailedException.class)
                .hasMessageContaining("configuration already exists");

        JsonNode updatedPatch = compiledPatch.deepCopy();
        ((ObjectNode) updatedPatch.path("patch").path("page"))
                .put("title", "Absences by department");
        UUID updateStreamId = UUID.randomUUID();
        UUID updateThreadId = UUID.randomUUID();
        UUID updateTurnId = UUID.randomUUID();
        persistTurn(updateThreadId, updateTurnId);
        AiTurnEventEnvelope updateTerminal = turnEventService.appendEvent(
                principal,
                updateStreamId,
                updateThreadId,
                updateTurnId,
                "result",
                terminalPayload(updatedPatch, semanticDecision, "update", applied.etag()));
        AgenticAuthoringApplyRequest updateRequest = new AgenticAuthoringApplyRequest(
                updatedPatch,
                "praxis-dynamic-page",
                COMPONENT_ID,
                "user",
                null,
                semanticDecision,
                updateStreamId,
                updateTerminal.getEventId());

        AgenticAuthoringApplyResult updated = applyService.apply(
                updateRequest,
                principal,
                USER,
                "\"" + applied.etag() + "\"");

        assertThat(updated.version()).isEqualTo(2L);
        assertThat(updated.etag()).isNotEqualTo(applied.etag());
        assertThat(updated.payload()).isEqualTo(updatedPatch.path("patch").path("page"));
        assertThatThrownBy(() -> applyService.apply(
                        updateRequest,
                        principal,
                        USER,
                        "\"" + applied.etag() + "\""))
                .isInstanceOf(UserConfigService.PreconditionFailedException.class)
                .hasMessageContaining("stale configuration version");
        assertThat(userConfigService.getByScope(
                        UserConfigService.Scope.USER,
                        TENANT,
                        USER,
                        "praxis-dynamic-page",
                        COMPONENT_ID,
                        ENVIRONMENT)
                .orElseThrow()
                .config()
                .getVersion())
                .isEqualTo(2L);
    }

    private void persistTurn(UUID threadId, UUID turnId) {
        Instant now = Instant.now();
        threadRepository.saveAndFlush(AiThread.builder()
                .threadId(threadId)
                .tenantId(TENANT)
                .environment(ENVIRONMENT)
                .userId(USER)
                .componentType("praxis-dynamic-page-builder")
                .componentId(COMPONENT_ID)
                .status(AiThreadStatus.ACTIVE)
                .summary("")
                .schemaHash("contract-test")
                .createdAt(now)
                .lastUsedAt(now)
                .build());
        turnRepository.saveAndFlush(AiTurn.builder()
                .threadId(threadId)
                .turnId(turnId)
                .status(AiTurnStatus.PROCESSING)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
    }

    private ObjectNode terminalPayload(
            JsonNode compiledPatch,
            AgenticAuthoringSemanticDecision semanticDecision,
            String mode,
            String baseEtag) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canApply", true);
        payload.putObject("preview").set("compiledFormPatch", compiledPatch);
        payload.putObject("intentResolution")
                .set("semanticDecision", objectMapper.valueToTree(semanticDecision));
        ObjectNode target = payload.putObject("applyTarget");
        target.put("schemaVersion", AgenticAuthoringApplyTarget.SCHEMA_VERSION);
        target.put("componentType", "praxis-dynamic-page");
        target.put("componentId", COMPONENT_ID);
        target.put("scope", "user");
        target.put("environment", ENVIRONMENT);
        target.put("mode", mode);
        if (baseEtag != null) {
            target.put("baseEtag", baseEtag);
        }
        return payload;
    }

    private JsonNode compiledPatch() throws Exception {
        return objectMapper.readTree("""
                {
                  "profileId": "ui-composition-plan@0.1.0",
                  "catalogReleaseId": "catalog-terminal-apply-test",
                  "builderVersion": "0.1.0",
                  "patch": {
                    "page": {
                      "widgets": [
                        {
                          "key": "absence-by-department",
                          "definition": {
                            "id": "praxis-chart",
                            "inputs": {
                              "dataSource": {
                                "kind": "remote",
                                "url": "/api/human-resources/absences/stats"
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """);
    }

    private AgenticAuthoringSemanticDecision semanticDecision() {
        return new AgenticAuthoringSemanticDecision(
                "praxis-agentic-authoring-semantic-decision.v1",
                "decision-absence-dashboard",
                "create",
                "page",
                "create_artifact",
                null,
                null,
                null,
                false,
                "",
                "",
                "");
    }
}
