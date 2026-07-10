package org.praxisplatform.config.ai.authoring;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agentic_authoring_stream_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
                "praxis.domain-360.enabled=false",
                "praxis.domain-federation.enabled=false",
                "praxis.domain-knowledge.change-sets.enabled=false",
                "praxis.ai.authoring.http-enabled=true",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false",
                "praxis.ai.security.corporate-mode=true",
                "praxis.ai.stream.heartbeat-seconds=1",
                "praxis.ai.stream.processing-poll-seconds=1",
                "praxis.ai.stream.processing-max-polls=3"
        })
@AutoConfigureMockMvc
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
class AgenticAuthoringTurnStreamHttpSseIntegrationTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-a";
    private static final String ENV = "prod";
    private static final Set<String> TERMINAL_TYPES = Set.of("result", "error", "cancelled");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiTurnEventService turnEventService;

    @MockBean
    private AgenticAuthoringTurnEngine turnEngine;

    @BeforeEach
    void resetTables() throws Exception {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
        ReflectionTestUtils.setField(unwrapProxy(turnEventService), "streamExpirySeconds", 900L);
        stubSuccessfulTurn("ok");
    }

    private <T> T unwrapProxy(T bean) {
        try {
            return AopTestUtils.getTargetObject(bean);
        } catch (Exception ignored) {
            return bean;
        }
    }

    @Test
    void shouldEmitAuthoringTurnEventsWithStableEnvelopeAndTerminalResult() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("turn-client-1");

        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId(), null);

        assertThat(start.getEventSchemaVersion()).isEqualTo("v1");
        assertThat(start.getStreamAuthMode()).isEqualTo("cookie");
        assertThat(start.getStreamAccessToken()).isNull();
        assertThat(events).isNotEmpty();
        assertThat(events.stream().map(AiTurnEventEnvelope::getType).toList())
                .contains("status", "result");
        assertSingleTerminal(events);
        assertThat(events.stream().map(AiTurnEventEnvelope::getEventSchemaVersion).distinct().toList())
                .containsOnly("v1");
        assertThat(events.stream().map(AiTurnEventEnvelope::getSeq).filter(Objects::nonNull).toList())
                .isSorted();

        AiTurnEventEnvelope first = events.get(0);
        assertThat(first.getStreamId()).isEqualTo(start.getStreamId());
        assertThat(first.getThreadId()).isEqualTo(start.getThreadId());
        assertThat(first.getTurnId()).isEqualTo(start.getTurnId());
        assertThat(first.getPayload().path("requestHash").asText()).startsWith("sha256:");

        AiTurnEventEnvelope result = events.stream()
                .filter(event -> "result".equals(event.getType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.getPayload().path("message").asText()).isEqualTo("ok");
        assertThat(result.getPayload().path("routeClass").asText()).isEqualTo("component_authoring");
    }

    @Test
    void shouldReplayOnlyEventsAfterLastEventId() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("turn-client-replay");
        List<AiTurnEventEnvelope> firstRead = readSseEvents(start.getStreamId(), null);
        String firstEventId = firstRead.get(0).getEventId().toString();

        List<AiTurnEventEnvelope> replay = readSseEvents(start.getStreamId(), firstEventId);

        assertThat(replay).isNotEmpty();
        assertThat(replay)
                .allMatch(event -> event.getEventId() == null || !firstEventId.equals(event.getEventId().toString()));
        assertThat(replay.stream().map(AiTurnEventEnvelope::getType).toList()).contains("result");
    }

    @Test
    void shouldRejectInvalidOrForeignLastEventId() throws Exception {
        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}", startStream("invalid-last-id").getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("Last-Event-ID", "not-a-uuid")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest());

        AgenticAuthoringTurnStreamStartResponse streamA = startStream("foreign-a");
        String foreignEventId = readSseEvents(streamA.getStreamId(), null).get(0).getEventId().toString();
        AgenticAuthoringTurnStreamStartResponse streamB = startStreamAs(
                "foreign-b",
                "tenant-b",
                "user-b",
                ENV);

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}", streamB.getStreamId())
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV)
                        .header("Last-Event-ID", foreignEventId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldEnforceOwnershipOnConnectProbeAndCancel() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("ownership");

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", "qa"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnGoneForExpiredAuthoringStream() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("expired");
        jdbcTemplate.update(
                "update ai_turn_event set created_at = dateadd('SECOND', -7200, current_timestamp()) where stream_id = ? and seq = 1",
                start.getStreamId());

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isGone());
    }

    @Test
    void shouldKeepSingleTerminalWhenCancelRacesWithAuthoringResult() throws Exception {
        CountDownLatch enteredEngine = new CountDownLatch(1);
        CountDownLatch releaseEngine = new CountDownLatch(1);
        doAnswer(invocation -> {
                    AgenticAuthoringTurnEventSink sink = invocation.getArgument(2);
                    enteredEngine.countDown();
                    releaseEngine.await(5, TimeUnit.SECONDS);
                    sink.append("result", java.util.Map.of(
                            "message", "late-result",
                            "routeClass", "component_authoring"));
                    return AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome.completed(
                            new AgenticAuthoringTurnEngine.AgenticAuthoringTurnState(
                                    "component_authoring",
                                    null,
                                    null));
                })
                .when(turnEngine)
                .execute(any(), any(), any(), anyString());

        AgenticAuthoringTurnStreamStartResponse start = startStream("cancel-race");
        assertThat(enteredEngine.await(3, TimeUnit.SECONDS)).isTrue();

        MvcResult cancelResponse = mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV))
                .andExpect(status().isOk())
                .andReturn();
        releaseEngine.countDown();

        AiPatchStreamCancelResponse cancel = objectMapper.readValue(
                cancelResponse.getResponse().getContentAsByteArray(),
                AiPatchStreamCancelResponse.class);
        assertThat(cancel.getTerminalState()).isEqualTo("cancelled");
        List<String> terminalTypes = readSseEvents(start.getStreamId(), null).stream()
                .map(AiTurnEventEnvelope::getType)
                .filter(TERMINAL_TYPES::contains)
                .toList();
        assertThat(terminalTypes).containsExactly("cancelled");
    }

    private AgenticAuthoringTurnStreamStartResponse startStream(String clientTurnId) throws Exception {
        return startStreamAs(clientTurnId, TENANT, USER, ENV);
    }

    private AgenticAuthoringTurnStreamStartResponse startStreamAs(
            String clientTurnId,
            String tenant,
            String user,
            String environment)
            throws Exception {
        AgenticAuthoringTurnStreamRequest request = new AgenticAuthoringTurnStreamRequest(
                "Crie um painel de acompanhamento",
                "praxis-ui-angular",
                "praxis-dynamic-page-builder",
                "/page-builder-ia",
                objectMapper.createObjectNode(),
                null,
                "openai",
                "gpt-test",
                null,
                "session-1",
                clientTurnId,
                List.of(),
                null,
                List.of(),
                null,
                null);
        MvcResult response = mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/start")
                        .requestAttr("tenantId", tenant)
                        .requestAttr("userId", user)
                        .requestAttr("environment", environment)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                response.getResponse().getContentAsByteArray(),
                AgenticAuthoringTurnStreamStartResponse.class);
    }

    private List<AiTurnEventEnvelope> readSseEvents(UUID streamId, String lastEventId) throws Exception {
        var connectRequest = get("/api/praxis/config/ai/authoring/turn/stream/{streamId}", streamId)
                .requestAttr("tenantId", TENANT)
                .requestAttr("userId", USER)
                .requestAttr("environment", ENV)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null && !lastEventId.isBlank()) {
            connectRequest.header("Last-Event-ID", lastEventId);
        }
        MvcResult asyncResult = mockMvc.perform(connectRequest)
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();
        return parseSseBody(completed.getResponse().getContentAsString());
    }

    private List<AiTurnEventEnvelope> parseSseBody(String rawBody) throws Exception {
        List<AiTurnEventEnvelope> events = new ArrayList<>();
        if (rawBody == null || rawBody.isBlank()) {
            return events;
        }
        String[] lines = rawBody.split("\\R");
        StringBuilder dataBuffer = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("data:")) {
                if (dataBuffer.length() > 0) {
                    dataBuffer.append('\n');
                }
                dataBuffer.append(line.substring(5).trim());
                continue;
            }
            if (line.isBlank() && dataBuffer.length() > 0) {
                events.add(objectMapper.readValue(dataBuffer.toString(), AiTurnEventEnvelope.class));
                dataBuffer.setLength(0);
            }
        }
        if (dataBuffer.length() > 0) {
            events.add(objectMapper.readValue(dataBuffer.toString(), AiTurnEventEnvelope.class));
        }
        return events;
    }

    private void stubSuccessfulTurn(String message) {
        doAnswer(invocation -> {
                    AgenticAuthoringTurnEventSink sink = invocation.getArgument(2);
                    sink.append("thought.step", java.util.Map.of(
                            "step", 1,
                            "phase", "intent.resolution",
                            "title", "Resolving semantic intent",
                            "state", "completed"));
                    sink.append("result", java.util.Map.of(
                            "message", message,
                            "routeClass", "component_authoring",
                            "completedAt", Instant.now().toString()));
                    return AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome.completed(
                            new AgenticAuthoringTurnEngine.AgenticAuthoringTurnState(
                                    "component_authoring",
                                    null,
                                    null));
                })
                .when(turnEngine)
                .execute(any(), any(), any(), anyString());
    }

    private void assertSingleTerminal(List<AiTurnEventEnvelope> events) {
        List<String> terminal = events.stream()
                .map(AiTurnEventEnvelope::getType)
                .filter(TERMINAL_TYPES::contains)
                .toList();
        assertThat(terminal).hasSize(1);
    }
}
