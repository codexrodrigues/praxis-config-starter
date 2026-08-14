package org.praxisplatform.config.controller;

import org.junit.jupiter.api.Tag;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamService;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleTestRunService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:agentic_authoring_stream_security_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
                "praxis.domain-360.enabled=false",
                "praxis.domain-federation.enabled=false",
                "praxis.domain-knowledge.change-sets.enabled=false",
                "praxis.ai.authoring.http-enabled=true",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false",
                "praxis.ai.security.corporate-mode=true",
                "praxis.ai.security.allow-header-identity-in-local=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AgenticAuthoringTurnStreamSecurityChainIntegrationTest.SecurityTestConfig.class)
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
class AgenticAuthoringTurnStreamSecurityChainIntegrationTest {

    private static final String BASIC_AUTH = "Basic c3RyZWFtLXVzZXI6c2VjcmV0";
    private static final String READER_BASIC_AUTH = "Basic c3RyZWFtLXJlYWRlcjpzZWNyZXQ=";
    private static final String TURN_WITH_SELECTED_DOMAIN_DECISION = """
            {
              "userPrompt": "Explique a decisão selecionada",
              "targetApp": "praxis-policy-studio",
              "targetComponentId": "praxis-policy-studio",
              "currentRoute": "/catalog",
              "currentPage": {},
              "provider": "openai",
              "model": "gpt-5.4-mini",
              "sessionId": "session-1",
              "clientTurnId": "turn-1",
              "conversationMessages": [],
              "attachmentSummaries": [],
              "contextHints": {
                "selectedDomainDecisionRef": {
                  "schemaVersion": "praxis.ai.context-hints.domain-decision/v1",
                  "definitionId": "00000000-0000-0000-0000-000000000010",
                  "ruleKey": "quickstart.extraordinary-benefit.eligibility",
                  "version": 1,
                  "source": "policy-studio-selection"
                }
              }
            }
            """;
    private static final String TURN_WITHOUT_SELECTED_DOMAIN_DECISION = """
            {
              "userPrompt": "Explique o componente selecionado",
              "targetApp": "praxis-ui-angular",
              "targetComponentId": "praxis-table",
              "currentRoute": "/catalog",
              "currentPage": {},
              "provider": "openai",
              "model": "gpt-5.4-mini",
              "sessionId": "session-2",
              "clientTurnId": "turn-2",
              "conversationMessages": [],
              "attachmentSummaries": [],
              "contextHints": {}
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgenticAuthoringTurnStreamService turnStreamService;

    @MockBean
    private DomainRuleChangeWorkspaceService domainRuleChangeWorkspaceService;

    @MockBean
    private DomainRuleGovernancePrincipalResolver domainRuleGovernancePrincipalResolver;

    @MockBean
    private DomainRuleRolloutPolicyService domainRuleRolloutPolicyService;

    @MockBean
    private DomainRuleRolloutService domainRuleRolloutService;

    @MockBean
    private DomainRuleTestRunService domainRuleTestRunService;

    @Test
    void shouldReturnUnauthorizedBeforeEnqueueWhenSelectedDomainDecisionHasNoAuthentication() throws Exception {
        mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TURN_WITH_SELECTED_DOMAIN_DECISION))
                .andExpect(status().isUnauthorized());

        verify(turnStreamService, never()).start(any(), any(), any());
    }

    @Test
    void shouldReturnForbiddenBeforeEnqueueWhenSelectedDomainDecisionReaderRoleIsMissing() throws Exception {
        mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/start")
                        .header("Authorization", BASIC_AUTH)
                        .requestAttr("tenantId", "tenant-a")
                        .requestAttr("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TURN_WITH_SELECTED_DOMAIN_DECISION))
                .andExpect(status().isForbidden());

        verify(turnStreamService, never()).start(any(), any(), any());
    }

    @Test
    void shouldAllowSelectedDomainDecisionReaderBeforeEnqueue() throws Exception {
        stubCreatedTurn();

        mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/start")
                        .header("Authorization", READER_BASIC_AUTH)
                        .requestAttr("tenantId", "tenant-a")
                        .requestAttr("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TURN_WITH_SELECTED_DOMAIN_DECISION))
                .andExpect(status().isCreated());

        verify(turnStreamService).start(any(), eq("http://localhost"), argThat(context ->
                context != null
                        && "tenant-a".equals(context.tenantId())
                        && "stream-reader".equals(context.userId())
                        && "dev".equals(context.environment())
                        && context.resolvedFromServerPrincipal()));
    }

    @Test
    void shouldKeepTurnWithoutSelectedDomainDecisionAvailableToAuthenticatedNonReader() throws Exception {
        stubCreatedTurn();

        mockMvc.perform(post("/api/praxis/config/ai/authoring/turn/stream/start")
                        .header("Authorization", BASIC_AUTH)
                        .requestAttr("tenantId", "tenant-a")
                        .requestAttr("environment", "dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TURN_WITHOUT_SELECTED_DOMAIN_DECISION))
                .andExpect(status().isCreated());

        verify(turnStreamService).start(any(), eq("http://localhost"), argThat(context ->
                context != null
                        && "tenant-a".equals(context.tenantId())
                        && "stream-user".equals(context.userId())
                        && "dev".equals(context.environment())));
    }

    @Test
    void shouldReturnUnauthorizedWhenAuthenticationIsMissing() throws Exception {
        UUID streamId = UUID.randomUUID();

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", streamId)
                        .header("X-Tenant-ID", "tenant-a")
                        .header("X-Env", "dev"))
                .andExpect(status().isUnauthorized());

        verify(turnStreamService, never()).probe(any(), any());
    }

    @Test
    void shouldReturnForbiddenWhenCorporateTenantIsNotProjectedServerSide() throws Exception {
        UUID streamId = UUID.randomUUID();

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", streamId)
                        .header("Authorization", BASIC_AUTH))
                .andExpect(status().isForbidden());

        verify(turnStreamService, never()).probe(any(), any());
    }

    @Test
    void shouldAllowAuthenticatedRequestWhenCorporateIdentityIsProjectedServerSide() throws Exception {
        UUID streamId = UUID.randomUUID();

        mockMvc.perform(get("/api/praxis/config/ai/authoring/turn/stream/{streamId}/probe", streamId)
                        .header("Authorization", BASIC_AUTH)
                        .requestAttr("tenantId", "tenant-a")
                        .requestAttr("environment", "dev"))
                .andExpect(status().isNoContent());

        verify(turnStreamService).probe(eq(streamId), argThat(context ->
                context != null
                        && "tenant-a".equals(context.tenantId())
                        && "stream-user".equals(context.userId())
                        && "dev".equals(context.environment())
                        && context.resolvedFromServerPrincipal()));
    }

    @TestConfiguration
    static class SecurityTestConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .httpBasic(Customizer.withDefaults())
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET, "/api/praxis/config/ai/**").authenticated()
                            .anyRequest().permitAll());
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("stream-user")
                            .password("{noop}secret")
                            .roles("USER")
                            .build(),
                    User.withUsername("stream-reader")
                            .password("{noop}secret")
                            .roles("RULE_DEFINITION_READER")
                            .build());
        }
    }

    private void stubCreatedTurn() {
        AgenticAuthoringTurnStreamStartResponse response = AgenticAuthoringTurnStreamStartResponse.builder()
                .streamId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .threadId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .turnId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .eventSchemaVersion("v1")
                .streamAuthMode("cookie")
                .expiresAt(Instant.parse("2026-08-14T23:59:00Z"))
                .fallbackAuthoringUrl("http://localhost/api/praxis/config/ai/authoring/page-preview")
                .build();
        org.mockito.Mockito.when(turnStreamService.start(any(), any(), any()))
                .thenReturn(new AgenticAuthoringTurnStreamService.StartResult(response, true));
    }
}
