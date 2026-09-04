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
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.DomainFederationQueryService;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetService;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleTestRunService;
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
    @MockBean private DomainRuleChangeWorkspaceService domainRuleChangeWorkspaceService;
    @MockBean private DomainRuleGovernancePrincipalResolver domainRuleGovernancePrincipalResolver;
    @MockBean private DomainRuleRolloutPolicyService domainRuleRolloutPolicyService;
    @MockBean private DomainRuleRolloutService domainRuleRolloutService;
    @MockBean private DomainRuleTestRunService domainRuleTestRunService;

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
    void shouldPersistReopenRefineAndKeepTheWinningVersionAfterStaleApply() throws Exception {
        UUID streamId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext(TENANT, USER, ENVIRONMENT, true);
        persistTurn(threadId, turnId);

        JsonNode firstPlan = compositionPlan();
        JsonNode compiledPatch = compiledPatch(firstPlan);
        AgenticAuthoringSemanticDecision semanticDecision = semanticDecision();
        ObjectNode resultPayload = terminalPayload(
                compiledPatch,
                firstPlan,
                semanticDecision,
                "create",
                null);
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
                objectMapper,
                new CanonicalJsonHashService(objectMapper));

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
        JsonNode persistedAuthoringSource = objectMapper.readTree(
                persisted.config().getAuthoringSource());
        assertThat(persistedAuthoringSource.path("schemaVersion").asText())
                .isEqualTo("praxis.ui-authoring-source/v1");
        assertThat(persistedAuthoringSource.path("source").has("diagnostics")).isFalse();
        assertThat(persistedAuthoringSource.path("sourceSha256").asText()).hasSize(64);
        assertThat(persistedAuthoringSource.at("/materialization/sha256").asText()).hasSize(64);
        assertThat(applied.authoringSource()).isEqualTo(persistedAuthoringSource);
        JsonNode persistedPage = objectMapper.readTree(persisted.config().getPayload());
        assertThat(persistedPage.path("widgets").findValuesAsText("id"))
                .contains("praxis-table", "praxis-dynamic-form");
        assertThat(persistedPage.at("/composition/links/0/from/ref/port").asText())
                .isEqualTo("selectionChange");
        assertThat(persistedPage.at("/composition/links/1/to/ref/port").asText())
                .isEqualTo("initialValue");
        assertThat(persistedPage.at("/widgets/0/definition/inputs/config/actions/row/discovery/enabled").asBoolean())
                .isTrue();
        assertThat(persistedPage.at("/widgets/0/definition/inputs/config/actions/collection/discovery/enabled").asBoolean())
                .isFalse();
        JsonNode persistedTags = objectMapper.readTree(persisted.config().getTags());
        assertThat(persistedTags.path("authoringResultEventId").asText())
                .isEqualTo(terminal.getEventId().toString());

        assertThatThrownBy(() -> applyService.apply(request, principal, USER, null))
                .isInstanceOf(UserConfigService.PreconditionFailedException.class)
                .hasMessageContaining("configuration already exists");

        ObjectNode reopenedPlan = (ObjectNode) persistedAuthoringSource.path("source").deepCopy();
        ((ObjectNode) reopenedPlan.at("/widgets/1/inputs"))
                .put("formId", "missions-detail-reviewed");
        JsonNode updatedPatch = compiledPatch(reopenedPlan);
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
                terminalPayload(
                        updatedPatch,
                        reopenedPlan,
                        semanticDecision,
                        "update",
                        applied.etag()));
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
        assertThat(updated.authoringSource().path("sourceSha256").asText())
                .isNotEqualTo(applied.authoringSource().path("sourceSha256").asText());
        assertThat(updated.authoringSource().at("/materialization/sha256").asText())
                .isNotEqualTo(applied.authoringSource().at("/materialization/sha256").asText());
        assertThat(updated.authoringSource().at("/provenance/resultEventId").asText())
                .isEqualTo(updateTerminal.getEventId().toString());
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
        JsonNode winningPayload = objectMapper.readTree(userConfigService.getByScope(
                        UserConfigService.Scope.USER,
                        TENANT,
                        USER,
                        "praxis-dynamic-page",
                        COMPONENT_ID,
                        ENVIRONMENT)
                .orElseThrow()
                .config()
                .getPayload());
        assertThat(winningPayload.at("/widgets/1/definition/inputs/formId").asText())
                .isEqualTo("missions-detail-reviewed");
        assertThat(winningPayload.at("/composition/links/0/from/ref/port").asText())
                .isEqualTo("selectionChange");
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
            JsonNode uiCompositionPlan,
            AgenticAuthoringSemanticDecision semanticDecision,
            String mode,
            String baseEtag) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canApply", true);
        ObjectNode preview = payload.putObject("preview");
        preview.set("compiledFormPatch", compiledPatch);
        ObjectNode authoringPlan = (ObjectNode) uiCompositionPlan.deepCopy();
        authoringPlan.withObject("/diagnostics/resourceWorkspaceGrounding")
                .put("status", "verified");
        preview.set("uiCompositionPlan", authoringPlan);
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

    private JsonNode compositionPlan() throws Exception {
        return objectMapper.readTree("""
                {
                  "version": "1.0",
                  "kind": "praxis.ui-composition-plan",
                  "layoutPreset": "resource-master-detail",
                  "state": { "values": { "selectedItem": null } },
                  "canvas": {
                    "mode": "grid",
                    "columns": 12,
                    "rowUnit": "80px",
                    "gap": "16px",
                    "autoRows": "fixed",
                    "items": {
                      "missions-master": { "col": 1, "row": 1, "colSpan": 7, "rowSpan": 8 },
                      "missions-detail": { "col": 8, "row": 1, "colSpan": 5, "rowSpan": 8 }
                    }
                  },
                  "widgets": [
                    {
                      "key": "missions-master",
                      "componentId": "praxis-table",
                      "inputs": {
                        "resourcePath": "/api/operations/missoes",
                        "tableId": "missions-master",
                        "config": {
                          "actions": {
                            "collection": { "discovery": { "enabled": false } },
                            "row": { "enabled": true, "discovery": { "enabled": true } }
                          },
                          "behavior": { "selection": { "enabled": true, "type": "single" } }
                        }
                      },
                      "outputs": { "selectionChange": "emit" }
                    },
                    {
                      "key": "missions-detail",
                      "componentId": "praxis-dynamic-form",
                      "inputs": {
                        "resourcePath": "/api/operations/missoes",
                        "schemaSource": "resource",
                        "mode": "view",
                        "formId": "missions-detail"
                      }
                    }
                  ],
                  "bindings": [
                    {
                      "id": "missions-master.selectionChange->state.selectedItem",
                      "intent": "state-write",
                      "from": { "kind": "component-port", "widget": "missions-master", "port": "selectionChange", "direction": "output" },
                      "to": { "kind": "state", "path": "selectedItem" },
                      "transform": { "kind": "pick-path", "id": "pick-selected-row", "path": "payload.row" },
                      "metadata": { "source": "ui-composition-plan", "tags": ["master-detail"] }
                    },
                    {
                      "id": "state.selectedItem->missions-detail.initialValue",
                      "intent": "state-read",
                      "from": { "kind": "state", "path": "selectedItem" },
                      "to": { "kind": "component-port", "widget": "missions-detail", "port": "initialValue", "direction": "input" },
                      "condition": { "!!": [{ "var": "state.selectedItem" }] },
                      "metadata": { "source": "ui-composition-plan", "tags": ["master-detail"] }
                    }
                  ]
                }
                """);
    }

    private JsonNode compiledPatch(JsonNode plan) {
        ObjectNode basePatch = objectMapper.createObjectNode();
        basePatch.put("profileId", "ui-composition-plan@0.1.0");
        basePatch.put("catalogReleaseId", "catalog-terminal-apply-test");
        basePatch.put("builderVersion", "0.1.0");
        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                new AgenticAuthoringUiCompositionPlanCompiler(objectMapper).compile(plan, basePatch);
        assertThat(result.valid()).withFailMessage("Compilation failures: %s", result.failureCodes()).isTrue();
        return result.compiledFormPatch();
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
