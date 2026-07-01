package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.EnterpriseRuntimeContextController;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextResponse;
import org.praxisplatform.config.dto.EnterpriseRuntimeTenant;
import org.praxisplatform.config.dto.EnterpriseRuntimeUser;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class EnterpriseRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EnterpriseRuntimeAutoConfiguration.class));

    @Test
    void shouldRegisterDefaultRuntimeContextBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiPrincipalContextResolver.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeContextProvider.class);
            assertThat(context).hasSingleBean(EnterpriseRuntimeContextController.class);
            assertThat(context.getBean(EnterpriseRuntimeContextProvider.class))
                    .isInstanceOf(DefaultEnterpriseRuntimeContextProvider.class);
        });
    }

    @Test
    void shouldBackOffWhenHostProvidesRuntimeContextProvider() {
        EnterpriseRuntimeContextProvider customProvider = request -> new EnterpriseRuntimeContextResponse(
                "custom",
                new EnterpriseRuntimeUser("user-a", "User A", true),
                new EnterpriseRuntimeTenant("tenant-a", "Tenant A", true),
                "prod",
                "pt-BR",
                "America/Sao_Paulo",
                null,
                null,
                List.of("custom"),
                Instant.parse("2026-07-01T12:00:00Z"));

        contextRunner
                .withBean(EnterpriseRuntimeContextProvider.class, () -> customProvider)
                .run(context -> {
                    assertThat(context).hasSingleBean(EnterpriseRuntimeContextProvider.class);
                    assertThat(context.getBean(EnterpriseRuntimeContextProvider.class)).isSameAs(customProvider);
                    assertThat(context).hasSingleBean(EnterpriseRuntimeContextController.class);
                });
    }
}
