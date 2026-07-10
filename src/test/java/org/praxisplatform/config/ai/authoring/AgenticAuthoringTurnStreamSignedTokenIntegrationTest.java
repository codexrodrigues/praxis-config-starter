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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiPatchStreamCancelResponse;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agentic_authoring_stream_signed_token_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
                "praxis.ai.stream.auth.mode=signed-url-token",
                "praxis.ai.stream.auth.allow-legacy-signed-token=true",
                "praxis.ai.stream.auth.token-secret=agentic-authoring-stream-secret-1234567890",
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
class AgenticAuthoringTurnStreamSignedTokenIntegrationTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-a";
    private static final String ENV = "prod";
    private static final String TOKEN_SECRET = "agentic-authoring-stream-secret-1234567890";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AgenticAuthoringTurnEngine turnEngine;

    @BeforeEach
    void resetTables() throws Exception {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
        stubSuccessfulTurn();
    }

    @Test
    void shouldUseSignedTokenForConnectProbeCancelAndReplayWithoutCookieIdentity() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("signed-token-happy-path");

        assertThat(start.getStreamAuthMode()).isEqualTo("signed_url_token");
        assertThat(start.getStreamAccessToken()).isNotBlank();

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", start.getStreamId())
                        .param("accessToken", start.getStreamAccessToken()))
                .andExpect(status().isNoContent());

        List<AiTurnEventEnvelope> firstRead = readSseEvents(start.getStreamId(), start.getStreamAccessToken(), null);
        String firstEventId = firstRead.get(0).getEventId().toString();
        List<AiTurnEventEnvelope> replay = readSseEvents(start.getStreamId(), start.getStreamAccessToken(), firstEventId);
        assertThat(replay.stream().map(AiTurnEventEnvelope::getType).toList()).contains("result");

        MvcResult cancelResponse = mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel", start.getStreamId())
                        .param("accessToken", start.getStreamAccessToken()))
                .andExpect(status().isOk())
                .andReturn();
        AiPatchStreamCancelResponse cancel = objectMapper.readValue(
                cancelResponse.getResponse().getContentAsByteArray(),
                AiPatchStreamCancelResponse.class);
        assertThat(cancel.getTerminalState()).isEqualTo("completed");
    }

    @Test
    void shouldRejectInvalidSignedToken() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("invalid-token");

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", start.getStreamId())
                        .param("accessToken", "not-a-valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectSignedTokenOutsideStreamScope() throws Exception {
        AgenticAuthoringTurnStreamStartResponse streamA = startStream("token-stream-a");
        AgenticAuthoringTurnStreamStartResponse streamB = startStream("token-stream-b");

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", streamB.getStreamId())
                        .param("accessToken", streamA.getStreamAccessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectSignedTokenWhenExplicitIdentityMismatches() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("token-identity-mismatch");

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", start.getStreamId())
                        .param("accessToken", start.getStreamAccessToken())
                        .header("X-Tenant-ID", "tenant-b")
                        .header("X-User-ID", USER)
                        .header("X-Env", ENV)
                        .requestAttr("tenantId", "tenant-b")
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectExpiredSignedToken() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("expired-token");
        String expiredToken = legacyToken(
                start.getStreamId(),
                TENANT,
                USER,
                ENV,
                Instant.now().minusSeconds(60).getEpochSecond());

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", start.getStreamId())
                        .param("accessToken", expiredToken))
                .andExpect(status().isForbidden());
    }

    private AgenticAuthoringTurnStreamStartResponse startStream(String clientTurnId) throws Exception {
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
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                response.getResponse().getContentAsByteArray(),
                AgenticAuthoringTurnStreamStartResponse.class);
    }

    private List<AiTurnEventEnvelope> readSseEvents(UUID streamId, String accessToken, String lastEventId)
            throws Exception {
        var connectRequest = get("/api/praxis/config/ai/authoring/turn/stream/{streamId}", streamId)
                .param("accessToken", accessToken)
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

    private void stubSuccessfulTurn() {
        doAnswer(invocation -> {
                    AgenticAuthoringTurnEventSink sink = invocation.getArgument(2);
                    sink.append("result", Map.of(
                            "message", "ok",
                            "routeClass", "component_authoring"));
                    return AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome.completed(
                            new AgenticAuthoringTurnEngine.AgenticAuthoringTurnState(
                                    "component_authoring",
                                    null,
                                    null));
                })
                .when(turnEngine)
                .execute(any(), any(), any(), anyString());
    }

    private String legacyToken(UUID streamId, String tenant, String user, String environment, long expiresAt)
            throws Exception {
        String payload = String.join(
                "|",
                streamId.toString(),
                tenant,
                user,
                environment,
                Long.toString(expiresAt));
        String payloadPart = URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = URL_ENCODER.encodeToString(mac.doFinal(payloadPart.getBytes(StandardCharsets.UTF_8)));
        return payloadPart + "." + signature;
    }
}
