package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.EnterpriseRuntimeContextController;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeContextChoiceProvider;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeContextSwitchProvider;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeSecurityEventProvider;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeTenantProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextChoiceProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextSwitchProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeSecurityEventProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class EnterpriseRuntimeAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EnterpriseRuntimeAutoConfiguration.class));

    @Test
    void registersFailClosedDefaultsAndRetainsAiResolverForItsIndependentConsumers() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiPrincipalContextResolver.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeContextProvider.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeContextSwitchProvider.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeContextChoiceProvider.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeTenantProvider.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeNavigationProvider.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeSecurityEventProvider.class);
            assertThat(context.getBean(EnterpriseRuntimeContextProvider.class)).isInstanceOf(DefaultEnterpriseRuntimeContextProvider.class);
            assertThat(context.getBean(EnterpriseRuntimeContextSwitchProvider.class)).isInstanceOf(DefaultEnterpriseRuntimeContextSwitchProvider.class);
            assertThat(context.getBean(EnterpriseRuntimeContextChoiceProvider.class)).isInstanceOf(DefaultEnterpriseRuntimeContextChoiceProvider.class);
            assertThat(context.getBean(EnterpriseRuntimeTenantProvider.class)).isInstanceOf(DefaultEnterpriseRuntimeTenantProvider.class);
            assertThat(context.getBean(EnterpriseRuntimeNavigationProvider.class)).isInstanceOf(DefaultEnterpriseRuntimeNavigationProvider.class);
            assertThat(context.getBean(EnterpriseRuntimeSecurityEventProvider.class)).isInstanceOf(DefaultEnterpriseRuntimeSecurityEventProvider.class);
        });
    }

    @Test
    void assembledDefaultControllerNeverInventsBootstrapReadiness() {
        contextRunner.run(context -> {
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(EnterpriseRuntimeContextController.class)).build();
            mvc.perform(get("/api/praxis/runtime/context")).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
            mvc.perform(get("/api/praxis/runtime/context").principal(() -> "synthetic-user"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RUNTIME_PROVIDER_UNAVAILABLE"));
        });
    }

    @Test
    void hostCanOverrideTheOnlyContextAndChoicePolicies() {
        EnterpriseRuntimeContextProvider contextProvider = request -> new EnterpriseRuntimeContextResponse(
                "runtime", "selection-required", "selection", new EnterpriseRuntimeUser("safe", null, true),
                null, null, Instant.now());
        EnterpriseRuntimeContextChoiceProvider choiceProvider = (request, cursor, pageSize) -> null;
        contextRunner
                .withBean(EnterpriseRuntimeContextProvider.class, () -> contextProvider)
                .withBean(EnterpriseRuntimeContextChoiceProvider.class, () -> choiceProvider)
                .run(context -> {
                    assertThat(context).hasSingleBean(EnterpriseRuntimeContextProvider.class);
                    assertThat(context).hasSingleBean(EnterpriseRuntimeContextChoiceProvider.class);
                    assertThat(context.getBean(EnterpriseRuntimeContextProvider.class)).isSameAs(contextProvider);
                    assertThat(context.getBean(EnterpriseRuntimeContextChoiceProvider.class)).isSameAs(choiceProvider);
                });
    }
}
