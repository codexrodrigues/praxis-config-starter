package org.praxisplatform.config.ai.agent.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.model.AgentConversationItem;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.InMemoryAiProviderRateLimitStore;

@Tag("unit")
class RoutingAiToolCallingProviderTest {

    @Test
    void forwardsDisableToolCallingAndResponseJsonSchemaToDelegate() {
        AtomicReference<AgentModelTurnRequest> captured = new AtomicReference<>();
        AiToolCallingProvider gemini = stubProvider("gemini", req -> {
            captured.set(req);
            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "ok", List.of(), AgentTokenUsage.ZERO, "stop", "id1", Map.of());
        });

        RoutingAiToolCallingProvider router = new RoutingAiToolCallingProvider(null, List.of(gemini));
        Map<String, Object> schema = Map.of("type", "object");

        router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("hi")),
                        List.of(),
                        AiCallConfig.builder().provider("gemini").model("gemini-2.5-flash").build(),
                        true,
                        schema),
                AgentModelStreamListener.NOOP);

        assertThat(captured.get().disableToolCalling()).isTrue();
        assertThat(captured.get().responseJsonSchema()).isEqualTo(schema);
    }

    @Test
    void skipsCoolingDownProviderAndFailsOverOnRateLimit() {
        InMemoryAiProviderRateLimitStore store = new InMemoryAiProviderRateLimitStore();
        AtomicBoolean geminiCalled = new AtomicBoolean(false);

        AiToolCallingProvider gemini = stubProvider("gemini", req -> {
            geminiCalled.set(true);
            throw AiProviderCallException.fromHttpStatus("gemini", 429, "rate limited");
        });
        AiToolCallingProvider openai = stubProvider("openai", req ->
                new AgentModelTurnResponse("openai", "gpt-4o-mini", "fallback ok", List.of(), AgentTokenUsage.ZERO, "stop", "id2", Map.of()));

        RoutingAiToolCallingProvider router = new RoutingAiToolCallingProvider(
                null,
                List.of(gemini, openai),
                store,
                List.of());

        // First call tries gemini -> gets 429 -> records cooldown -> fails over to openai
        AgentModelTurnResponse first = router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("hi")),
                        List.of(),
                        AiCallConfig.builder().provider("gemini").timeoutSeconds(30).build()),
                AgentModelStreamListener.NOOP);
        assertThat(first.provider()).isEqualTo("openai");
        assertThat(geminiCalled.get()).isTrue();

        // Manually re-mark gemini cooling down to verify cooldown ordering skips gemini upfront
        store.recordCooldown("gemini", null, Instant.now().plusSeconds(60), "rate-limit");
        geminiCalled.set(false);
        AgentModelTurnResponse second = router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("hi again")),
                        List.of(),
                        AiCallConfig.builder().provider("gemini").timeoutSeconds(30).build()),
                AgentModelStreamListener.NOOP);
        assertThat(second.provider()).isEqualTo("openai");
        assertThat(geminiCalled.get()).isFalse();
    }

    @Test
    void enforcesEligibilityPolicyOnCandidates() {
        AiProviderEligibilityPolicy noExternalVendor = (providerId, model, ctx) ->
                "openai".equals(providerId)
                        ? AiProviderEligibilityPolicy.EligibilityResult.deny("Sovereignty policy blocks OpenAI")
                        : AiProviderEligibilityPolicy.EligibilityResult.allow();

        AiToolCallingProvider openai = stubProvider("openai", req ->
                new AgentModelTurnResponse("openai", "gpt-4o", "ok", List.of(), AgentTokenUsage.ZERO, "stop", "id", Map.of()));

        RoutingAiToolCallingProvider router = new RoutingAiToolCallingProvider(
                null,
                List.of(openai),
                null,
                List.of(noExternalVendor));

        assertThatThrownBy(() -> router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("hi")),
                        List.of(),
                        AiCallConfig.builder().provider("openai").build()),
                AgentModelStreamListener.NOOP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sovereignty policy blocks OpenAI");
    }

    @Test
    void forwardsFullAgentExecutionContextToEligibilityPolicyAndDelegate() {
        AtomicReference<AgentExecutionContext> policyCtx = new AtomicReference<>();
        AtomicReference<AgentExecutionContext> delegateCtx = new AtomicReference<>();

        AiProviderEligibilityPolicy payrollSovereignty = (providerId, model, ctx) -> {
            policyCtx.set(ctx);
            if ("openai".equals(providerId) && ctx != null && "payroll".equals(ctx.activeTopicKey())) {
                return AiProviderEligibilityPolicy.EligibilityResult.deny("Payroll topic requires sovereign gateway");
            }
            return AiProviderEligibilityPolicy.EligibilityResult.allow();
        };

        AiToolCallingProvider openai = stubProvider("openai", req -> {
            delegateCtx.set(req.executionContext());
            return new AgentModelTurnResponse("openai", "gpt-4o", "ok", List.of(), AgentTokenUsage.ZERO, "stop", "id1", Map.of());
        });
        AiToolCallingProvider gemini = stubProvider("gemini", req -> {
            delegateCtx.set(req.executionContext());
            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "ok", List.of(), AgentTokenUsage.ZERO, "stop", "id2", Map.of());
        });

        RoutingAiToolCallingProvider router = new RoutingAiToolCallingProvider(
                null, List.of(openai, gemini), null, List.of(payrollSovereignty));

        AgentExecutionContext richCtx = new AgentExecutionContext(
                null, null, null, "tenant-gov", "u-42", "prod", "ana",
                java.util.Set.of("ROLE_PAYROLL_AUDIT"), "/api/payroll/slips", "payroll",
                Map.of(), Map.of("region", "BR"));

        AgentModelTurnResponse resp = router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("hi")),
                        List.of(),
                        AiCallConfig.builder().provider("openai").build(),
                        false,
                        Map.of(),
                        richCtx),
                AgentModelStreamListener.NOOP);

        assertThat(resp.provider()).isEqualTo("gemini");
        assertThat(policyCtx.get()).isEqualTo(richCtx);
        assertThat(delegateCtx.get()).isEqualTo(richCtx);
    }

    @Test
    void preservesFallbackVendorApiKeyWhenResolvedMatchesFallbackProvider() {
        org.praxisplatform.config.service.AiProviderManagementService mgmt =
                org.mockito.Mockito.mock(org.praxisplatform.config.service.AiProviderManagementService.class);
        org.mockito.Mockito.when(mgmt.resolveEffectiveCallConfig(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    AiCallConfig seed = inv.getArgument(0);
                    if ("openai".equals(seed.getProvider())) {
                        return seed.toBuilder().apiKey("openai-fallback-secret").build();
                    }
                    return seed.toBuilder().apiKey("gemini-primary-secret").build();
                });

        AtomicReference<AiCallConfig> fallbackConfigCaptured = new AtomicReference<>();
        AiToolCallingProvider gemini = stubProvider("gemini", req -> {
            throw AiProviderCallException.fromHttpStatus("gemini", 429, "rate limited");
        });
        AiToolCallingProvider openai = stubProvider("openai", req -> {
            fallbackConfigCaptured.set(req.callConfig());
            return new AgentModelTurnResponse("openai", "gpt-4o-mini", "ok", List.of(), AgentTokenUsage.ZERO, "stop", "id2", Map.of());
        });

        RoutingAiToolCallingProvider router = new RoutingAiToolCallingProvider(
                mgmt, List.of(gemini, openai), null, List.of());

        router.generateTurn(
                new AgentModelTurnRequest(
                        "sys",
                        List.of(AgentConversationItem.userText("hi")),
                        List.of(),
                        AiCallConfig.builder().provider("gemini").tenantId("t1").environment("prod").build()),
                AgentModelStreamListener.NOOP);

        assertThat(fallbackConfigCaptured.get()).isNotNull();
        assertThat(fallbackConfigCaptured.get().getApiKey()).isEqualTo("openai-fallback-secret");
    }

    private static AiToolCallingProvider stubProvider(
            String id,
            java.util.function.Function<AgentModelTurnRequest, AgentModelTurnResponse> fn) {
        return new AiToolCallingProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public AgentModelTurnResponse generateTurn(
                    AgentModelTurnRequest request,
                    AgentModelStreamListener listener) {
                return fn.apply(request);
            }
        };
    }
}
