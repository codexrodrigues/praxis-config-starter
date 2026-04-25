package org.praxisplatform.config.contract;

import org.junit.jupiter.api.Tag;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiPatchStreamStartResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiOrchestratorService;
import org.praxisplatform.config.service.DomainFederationQueryService;
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
                "spring.datasource.url=jdbc:h2:mem:ai_contract_v11_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
@Tag("integration")
class AiContractV11RetroCompatibilityTest {

    private static final String CONTRACT_VERSION_HEADER = "X-Praxis-Contract-Version";
    private static final String CONTRACT_SCHEMA_HASH_HEADER = "X-Praxis-Schema-Hash";
    private static final String CODE_SCHEMA_HASH_MISMATCH = "SCHEMA_HASH_MISMATCH";
    private static final String CODE_UNSUPPORTED_CONTRACT = "UNSUPPORTED_CONTRACT";
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

    @MockBean
    private AiOrchestratorService orchestratorService;

    @MockBean
    private DomainFederationQueryService domainFederationQueryService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
        when(orchestratorService.generatePatch(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(AiOrchestratorResponse.builder()
                        .type("patch")
                        .patch(objectMapper.createObjectNode())
                        .explanation("ok")
                        .build());
    }

    @Test
    void shouldKeepPatchRetroCompatibleWhenContractMetadataIsMissing() throws Exception {
        AiOrchestratorRequest request = basePatchRequest();

        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/patch")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn();

        AiOrchestratorResponse body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AiOrchestratorResponse.class);
        assertThat(result.getResponse().getHeader(CONTRACT_VERSION_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(result.getResponse().getHeader(CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
        assertThat(body.getContractVersion()).isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(body.getSchemaHash()).isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
    }

    @Test
    void shouldPrioritizeBodyMetadataOverHeaderMetadataOnPatch() throws Exception {
        AiOrchestratorRequest request = basePatchRequest();
        request.setContractVersion(AiContractSpec.CONTRACT_VERSION);
        request.setSchemaHash(AiContractSpec.CONTRACT_SCHEMA_HASH);

        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/patch")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header(CONTRACT_VERSION_HEADER, "v9.9")
                        .header(CONTRACT_SCHEMA_HASH_HEADER, "deadbeef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn();

        AiOrchestratorResponse body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AiOrchestratorResponse.class);
        assertThat(result.getResponse().getHeader(CONTRACT_VERSION_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(result.getResponse().getHeader(CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
        assertThat(body.getContractVersion()).isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(body.getSchemaHash()).isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
    }

    @Test
    void shouldRejectPatchWithSchemaHashMismatch() throws Exception {
        AiOrchestratorRequest request = basePatchRequest();
        request.setContractVersion(AiContractSpec.CONTRACT_VERSION);
        request.setSchemaHash("deadbeef");

        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/patch")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andReturn();

        AiOrchestratorResponse body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AiOrchestratorResponse.class);
        assertThat(result.getResponse().getHeader(CONTRACT_VERSION_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(result.getResponse().getHeader(CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
        assertThat(body.getCode()).isEqualTo(CODE_SCHEMA_HASH_MISMATCH);
        assertThat(body.getContractVersion()).isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(body.getSchemaHash()).isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
    }

    @Test
    void shouldRejectStreamStartWithUnsupportedContractVersion() throws Exception {
        AiOrchestratorRequest request = baseStreamStartRequest();
        request.setContractVersion("v9.9");
        request.setSchemaHash(AiContractSpec.CONTRACT_SCHEMA_HASH);

        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andReturn();

        AiOrchestratorResponse body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AiOrchestratorResponse.class);
        assertThat(result.getResponse().getHeader(CONTRACT_VERSION_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(result.getResponse().getHeader(CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
        assertThat(body.getCode()).isEqualTo(CODE_UNSUPPORTED_CONTRACT);
    }

    @Test
    void shouldExposeContractHeadersAndStableStartEnvelopeForStreamStart() throws Exception {
        AiOrchestratorRequest request = baseStreamStartRequest();
        request.setContractVersion(AiContractSpec.CONTRACT_VERSION);
        request.setSchemaHash(AiContractSpec.CONTRACT_SCHEMA_HASH);

        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();

        AiPatchStreamStartResponse start = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AiPatchStreamStartResponse.class);
        assertThat(result.getResponse().getHeader(CONTRACT_VERSION_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_VERSION);
        assertThat(result.getResponse().getHeader(CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiContractSpec.CONTRACT_SCHEMA_HASH);
        assertThat(start.getStreamId()).isNotNull();
        assertThat(start.getThreadId()).isNotNull();
        assertThat(start.getTurnId()).isNotNull();
        assertThat(start.getEventSchemaVersion()).isEqualTo(AiContractSpec.STREAM_EVENT_SCHEMA_VERSION);
        assertThat(start.getExpiresAt()).isAfter(Instant.now());
        assertThat(start.getFallbackPatchUrl()).contains("/api/praxis/config/ai/patch");
    }

    @Test
    void shouldEmitSseEventsFollowingV11EnvelopeBoundary() throws Exception {
        AiOrchestratorRequest request = baseStreamStartRequest();
        request.setContractVersion(AiContractSpec.CONTRACT_VERSION);
        request.setSchemaHash(AiContractSpec.CONTRACT_SCHEMA_HASH);

        MvcResult startResult = mockMvc.perform(post("/api/praxis/config/ai/patch/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
        AiPatchStreamStartResponse start = objectMapper.readValue(
                startResult.getResponse().getContentAsByteArray(),
                AiPatchStreamStartResponse.class);

        MvcResult asyncResult = mockMvc.perform(get("/api/praxis/config/ai/patch/stream/{streamId}", start.getStreamId())
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();

        List<AiTurnEventEnvelope> events = parseSseBody(completed.getResponse().getContentAsString());
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getStreamId()).isEqualTo(start.getStreamId());
            assertThat(event.getThreadId()).isEqualTo(start.getThreadId());
            assertThat(event.getTurnId()).isEqualTo(start.getTurnId());
            assertThat(event.getTimestamp()).isNotNull();
            assertThat(event.getPayload()).isNotNull();
            assertThat(event.getEventSchemaVersion()).isEqualTo(AiContractSpec.STREAM_EVENT_SCHEMA_VERSION);
            assertThat(AiContractSpec.STREAM_EVENT_TYPES).contains(event.getType());
            assertThat(event.getSeq()).isNotEqualTo(0L);
        });
        assertThat(events.stream().map(AiTurnEventEnvelope::getType).filter(TERMINAL_TYPES::contains))
                .isNotEmpty();
    }

    private AiOrchestratorRequest basePatchRequest() {
        return AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .currentState(objectMapper.createObjectNode())
                .build();
    }

    private AiOrchestratorRequest baseStreamStartRequest() {
        return AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .userPrompt("Atualizar tabela")
                .clientTurnId(UUID.randomUUID())
                .currentState(objectMapper.valueToTree(Map.of("columns", List.of())))
                .build();
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
}
