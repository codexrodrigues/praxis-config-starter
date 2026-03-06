package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
import org.praxisplatform.config.dto.AiPatchStreamStartResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiOrchestratorService;
import org.praxisplatform.config.service.AiStreamService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:ai_stream_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiPatchStreamHttpSseIntegrationTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-a";
    private static final String ENV = "prod";
    private static final String SECURITY_AUTO_CONFIG_EXCLUDES =
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                    + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration";
    private static final String SECONDARY_AUTO_CONFIG_EXCLUDES = SECURITY_AUTO_CONFIG_EXCLUDES
            + ",org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration"
            + ",org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration"
            + ",org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration"
            + ",org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration"
            + ",org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration"
            + ",org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration"
            + ",org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration"
            + ",org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration"
            + ",org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration";
    private static final Set<String> TERMINAL_TYPES = Set.of("result", "error", "cancelled");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiStreamService primaryStreamService;

    @Autowired
    private AiTurnEventService turnEventService;

    @MockBean
    private AiOrchestratorService orchestratorService;

    private ConfigurableApplicationContext secondaryContext;
    private AiTurnEventService secondaryTurnEventService;
    private AiStreamService secondaryStreamService;
    private MockMvc secondaryMockMvc;

    @BeforeAll
    void initSecondaryNodeFacade() {
        secondaryContext = new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .properties(
                        "spring.main.web-application-type=servlet",
                        "server.port=0",
                        "spring.datasource.url=jdbc:h2:mem:ai_stream_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.flyway.enabled=false",
                        "spring.sql.init.mode=always",
                        "spring.sql.init.schema-locations=classpath:ai-stream-it-schema.sql",
                        "spring.autoconfigure.exclude=" + SECONDARY_AUTO_CONFIG_EXCLUDES,
                        "spring.ai.openai.api-key=dummy",
                        "spring.ai.vectorstore.pgvector.initialize-schema=false",
                        "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                        "praxis.ai.rag.vector-store.enabled=false",
                        "praxis.ai.registry.bootstrap.enabled=false",
                        "praxis.ai.security.corporate-mode=true",
                        "praxis.ai.stream.heartbeat-seconds=1",
                        "praxis.ai.stream.processing-poll-seconds=1",
                        "praxis.ai.stream.processing-max-polls=3")
                .run();
        assertThat(secondaryContext).isInstanceOf(WebApplicationContext.class);
        WebApplicationContext secondaryWebContext = (WebApplicationContext) secondaryContext;
        secondaryStreamService = secondaryContext.getBean(AiStreamService.class);
        secondaryTurnEventService = secondaryContext.getBean(AiTurnEventService.class);
        assertThat(AopUtils.isAopProxy(secondaryTurnEventService)).isTrue();
        this.secondaryMockMvc = MockMvcBuilders.webAppContextSetup(secondaryWebContext).build();
    }

    @AfterAll
    void shutdownSecondaryNodeFacade() {
        if (secondaryStreamService != null) {
            secondaryStreamService.shutdown();
        }
        if (secondaryContext != null) {
            secondaryContext.close();
        }
    }

    @BeforeEach
    void resetTables() throws Exception {
        awaitProcessingDrained(primaryStreamService);
        awaitProcessingDrained(secondaryStreamService);
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
        ReflectionTestUtils.setField(unwrapProxy(primaryStreamService), "streamExpiresSeconds", 900L);
        ReflectionTestUtils.setField(unwrapProxy(secondaryStreamService), "streamExpiresSeconds", 900L);
        ReflectionTestUtils.setField(unwrapProxy(turnEventService), "streamExpirySeconds", 900L);
        ReflectionTestUtils.setField(unwrapProxy(secondaryTurnEventService), "streamExpirySeconds", 900L);
    }

    @SuppressWarnings("unchecked")
    private void awaitProcessingDrained(AiStreamService service) throws InterruptedException {
        if (service == null) {
            return;
        }
        Set<UUID> activeStreams = (Set<UUID>) ReflectionTestUtils.getField(
                unwrapProxy(service),
                "activeProcessingStreams");
        long deadline = System.currentTimeMillis() + 3_000L;
        while (activeStreams != null && !activeStreams.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
    }

    private <T> T unwrapProxy(T bean) {
        try {
            return AopTestUtils.getTargetObject(bean);
        } catch (Exception ignored) {
            return bean;
        }
    }

    @Test
    void shouldReplayFromLastEventIdAcrossDifferentHttpNodes() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok-node-a"));
        UUID turnId = UUID.randomUUID();
        AiPatchStreamStartResponse start = startStream(turnId);

        List<AiTurnEventEnvelope> firstRead = readSseEvents(mockMvc, start.getStreamId(), null);
        assertThat(firstRead).isNotEmpty();
        assertThat(secondaryMockMvc).isNotSameAs(mockMvc);
        String firstEventId = firstRead.stream()
                .map(AiTurnEventEnvelope::getEventId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow()
                .toString();

        List<AiTurnEventEnvelope> replayOnSecondNode = readSseEvents(secondaryMockMvc, start.getStreamId(), firstEventId);
        assertThat(replayOnSecondNode).isNotEmpty();
        assertThat(replayOnSecondNode)
                .allMatch(evt -> evt.getEventId() == null || !firstEventId.equals(evt.getEventId().toString()));
        assertSingleTerminal(replayOnSecondNode);
    }

    @Test
    void shouldDeliverIncrementalEventsWhenReconnectsOnDifferentNodeDuringInProgress() throws Exception {
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch secondaryAsyncStarted = new CountDownLatch(1);
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    enteredProvider.countDown();
                    releaseProvider.await(5, TimeUnit.SECONDS);
                    return successResponse("done-after-reconnect");
                });

        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());
        assertThat(enteredProvider.await(3, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<List<AiTurnEventEnvelope>> reconnectResult = CompletableFuture.supplyAsync(() -> {
            try {
                var connectRequest = get("/api/praxis/config/ai/patch/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM);
                MvcResult asyncResult = secondaryMockMvc.perform(connectRequest)
                        .andExpect(request().asyncStarted())
                        .andReturn();
                secondaryAsyncStarted.countDown();
                MvcResult completed = secondaryMockMvc.perform(asyncDispatch(asyncResult))
                        .andExpect(status().isOk())
                        .andReturn();
                return parseSseBody(completed.getResponse().getContentAsString());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        assertThat(secondaryAsyncStarted.await(3, TimeUnit.SECONDS)).isTrue();
        releaseProvider.countDown();

        List<AiTurnEventEnvelope> replayOnSecondary = reconnectResult.get(8, TimeUnit.SECONDS);
        assertThat(replayOnSecondary).isNotEmpty();
        assertThat(replayOnSecondary.stream().map(AiTurnEventEnvelope::getType).toList())
                .contains("result");
        assertSingleTerminal(replayOnSecondary);
    }

    @Test
    void shouldEmitCoarseGrainedSequenceWithThoughtStepAndStableSchema() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());

        List<AiTurnEventEnvelope> events = readSseEvents(mockMvc, start.getStreamId(), null);
        assertThat(events).isNotEmpty();
        List<String> types = events.stream().map(AiTurnEventEnvelope::getType).toList();
        assertThat(types).contains("status", "thought.step", "result");
        assertThat(types.get(0)).isEqualTo("status");
        assertThat(types.get(types.size() - 1)).isIn(TERMINAL_TYPES);
        assertSingleTerminal(events);

        int firstThoughtIndex = indexOfType(types, "thought.step");
        int firstResultIndex = indexOfType(types, "result");
        assertThat(firstThoughtIndex).isGreaterThan(0);
        assertThat(firstResultIndex).isGreaterThan(firstThoughtIndex);

        AiTurnEventEnvelope firstStatus = events.get(0);
        assertThat(firstStatus.getPayload().path("state").asText("")).isEqualTo("started");
        assertThat(firstStatus.getPayload().path("requestHash").asText("")).isNotBlank();
        assertThat(firstStatus.getPayload().path("expiresAt").asText("")).isNotBlank();

        AiTurnEventEnvelope firstThought = events.get(firstThoughtIndex);
        assertThat(firstThought.getPayload().path("step").asInt()).isGreaterThan(0);
        assertThat(firstThought.getPayload().path("phase").asText("")).isNotBlank();
        assertThat(firstThought.getPayload().path("title").asText("")).isNotBlank();
        assertThat(firstThought.getPayload().path("state").asText("")).isNotBlank();

        AiTurnEventEnvelope resultEvent = events.get(firstResultIndex);
        assertThat(resultEvent.getPayload().has("response")).isTrue();

        assertThat(events.stream().map(AiTurnEventEnvelope::getEventSchemaVersion).distinct().toList())
                .containsOnly("v1");
        List<Long> persistedSeq = events.stream()
                .map(AiTurnEventEnvelope::getSeq)
                .filter(seq -> seq != null && seq > 0)
                .toList();
        assertThat(persistedSeq).isSorted();
        Integer heartbeatRows = jdbcTemplate.queryForObject(
                "select count(*) from ai_turn_event where stream_id = ? and event_type = 'heartbeat'",
                Integer.class,
                start.getStreamId());
        assertThat(heartbeatRows).isZero();
    }

    @Test
    void shouldTreatNullLiteralLastEventIdAsAbsent() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());

        List<AiTurnEventEnvelope> replayWithNullHeader = readSseEvents(mockMvc, start.getStreamId(), "null");

        assertThat(replayWithNullHeader).isNotEmpty();
        assertThat(replayWithNullHeader.get(0).getType()).isEqualTo("status");
    }

    @Test
    void shouldNotEmitNullIdLineWhenHeartbeatIsSent() throws Exception {
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch streamAsyncStarted = new CountDownLatch(1);
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    enteredProvider.countDown();
                    releaseProvider.await(5, TimeUnit.SECONDS);
                    return successResponse("done-after-heartbeat");
                });

        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());
        assertThat(enteredProvider.await(3, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<String> rawBodyFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return readSseRawBody(mockMvc, start.getStreamId(), null, streamAsyncStarted);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        assertThat(streamAsyncStarted.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(2_200L);

        cancelStream(start.getStreamId());
        releaseProvider.countDown();

        String rawBody = rawBodyFuture.get(8, TimeUnit.SECONDS);
        assertThat(rawBody).contains("\"type\":\"heartbeat\"");
        assertThat(rawBody).doesNotContain("id:null");
    }

    @Test
    void shouldReturnNotFoundForUnknownStream() throws Exception {
        mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}", UUID.randomUUID())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnForbiddenForCrossScopeAccess() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());

        mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnForbiddenForCrossScopeProbe() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());

        mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}/probe", start.getStreamId())
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnForbiddenForCrossScopeCancel() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());

        mockMvc.perform(post("/api/praxis/config/ai/patch/stream/{streamId}/cancel", start.getStreamId())
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnBadRequestForInvalidLastEventId() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());

        mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("Last-Event-ID", "not-a-uuid")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnForbiddenWhenLastEventIdBelongsToForeignScope() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse streamA = startStream(UUID.randomUUID());
        List<AiTurnEventEnvelope> streamAEvents = readSseEvents(mockMvc, streamA.getStreamId(), null);
        String foreignEventId = streamAEvents.stream()
                .map(AiTurnEventEnvelope::getEventId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow()
                .toString();

        AiOrchestratorRequest requestB = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Prompt tenant B")
                .clientTurnId(UUID.randomUUID())
                .currentState(objectMapper.createObjectNode())
                .build();
        MvcResult startB = mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(requestB)))
                .andExpect(status().isCreated())
                .andReturn();
        AiPatchStreamStartResponse streamB = objectMapper.readValue(
                startB.getResponse().getContentAsByteArray(),
                AiPatchStreamStartResponse.class);

        mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}", streamB.getStreamId())
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", "user-b")
                        .requestAttr("environment", ENV)
                        .header("Last-Event-ID", foreignEventId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnConflictForDivergentIdempotentStart() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        UUID turnId = UUID.randomUUID();
        AiOrchestratorRequest first = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Prompt A")
                .clientTurnId(turnId)
                .currentState(objectMapper.createObjectNode())
                .build();
        AiOrchestratorRequest second = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Prompt B")
                .clientTurnId(turnId)
                .currentState(objectMapper.createObjectNode())
                .build();

        mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(first)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(second)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldReturnGoneForExpiredStream() throws Exception {
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(successResponse("ok"));
        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());
        jdbcTemplate.update(
                "update ai_turn_event set created_at = dateadd('SECOND', -7200, current_timestamp()) where stream_id = ? and seq = 1",
                start.getStreamId());

        mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isGone());
    }

    @Test
    void shouldKeepSingleTerminalWhenCancelRacesWithResult() throws Exception {
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    enteredProvider.countDown();
                    releaseProvider.await(5, TimeUnit.SECONDS);
                    return successResponse("late-result");
                });

        AiPatchStreamStartResponse start = startStream(UUID.randomUUID());
        assertThat(enteredProvider.await(3, TimeUnit.SECONDS)).isTrue();

        AiPatchStreamCancelResponse cancelResponse = cancelStream(start.getStreamId());
        releaseProvider.countDown();

        assertThat(cancelResponse.getTerminalState()).isEqualTo("cancelled");
        List<AiTurnEventEnvelope> replay = readSseEvents(mockMvc, start.getStreamId(), null);
        List<String> terminalTypes = replay.stream()
                .map(AiTurnEventEnvelope::getType)
                .filter(TERMINAL_TYPES::contains)
                .toList();
        assertThat(terminalTypes).containsExactly("cancelled");
    }

    private AiPatchStreamStartResponse startStream(UUID turnId) throws Exception {
        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(turnId)
                .currentState(objectMapper.createObjectNode())
                .build();
        MvcResult response = mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(response.getResponse().getContentAsByteArray(), AiPatchStreamStartResponse.class);
    }

    private AiPatchStreamCancelResponse cancelStream(UUID streamId) throws Exception {
        MvcResult response = mockMvc.perform(post("/api/praxis/config/ai/patch/stream/{streamId}/cancel", streamId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(response.getResponse().getContentAsByteArray(), AiPatchStreamCancelResponse.class);
    }

    private List<AiTurnEventEnvelope> readSseEvents(MockMvc nodeMockMvc, UUID streamId, String lastEventId)
            throws Exception {
        String rawBody = readSseRawBody(nodeMockMvc, streamId, lastEventId, null);
        return parseSseBody(rawBody);
    }

    private String readSseRawBody(
            MockMvc nodeMockMvc,
            UUID streamId,
            String lastEventId,
            CountDownLatch asyncStartedSignal)
            throws Exception {
        var connectRequest = get("/api/praxis/config/ai/patch/stream/{streamId}", streamId)
                .requestAttr("tenantId", TENANT)
                .requestAttr("userId", USER)
                .requestAttr("environment", ENV)
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null && !lastEventId.isBlank()) {
            connectRequest.header("Last-Event-ID", lastEventId);
        }
        MvcResult asyncResult = nodeMockMvc.perform(connectRequest)
                .andExpect(request().asyncStarted())
                .andReturn();
        if (asyncStartedSignal != null) {
            asyncStartedSignal.countDown();
        }
        MvcResult completed = nodeMockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();
        return completed.getResponse().getContentAsString();
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

    private AiOrchestratorResponse successResponse(String explanation) {
        return AiOrchestratorResponse.builder()
                .type("patch")
                .explanation(explanation)
                .patch(objectMapper.createObjectNode())
                .build();
    }

    private void assertSingleTerminal(List<AiTurnEventEnvelope> events) {
        List<String> terminal = events.stream()
                .map(AiTurnEventEnvelope::getType)
                .filter(TERMINAL_TYPES::contains)
                .toList();
        assertThat(terminal).hasSize(1);
    }

    private int indexOfType(List<String> types, String target) {
        for (int i = 0; i < types.size(); i++) {
            if (target.equals(types.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
