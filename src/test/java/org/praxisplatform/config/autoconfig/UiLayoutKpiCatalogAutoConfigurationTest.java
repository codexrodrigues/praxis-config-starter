package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.CorporatePageCatalogContributor;
import org.praxisplatform.config.service.MetadataStarterUiLayoutKpiCatalogOracle;
import org.praxisplatform.config.service.UiLayoutKpiCatalogOracle;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("unit")
class UiLayoutKpiCatalogAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(UiLayoutKpiCatalogAutoConfiguration.class));

    @Test
    void registersMetadataStarterKpiCatalogEvenWithoutCorporatePageCatalogContributor() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CorporatePageCatalogContributor.class);
                    assertThat(context).hasSingleBean(UiLayoutKpiCatalogOracle.class);
                    assertThat(context.getBean(UiLayoutKpiCatalogOracle.class))
                            .isInstanceOf(MetadataStarterUiLayoutKpiCatalogOracle.class);
                });
    }

    @Test
    void respectsCustomHostKpiCatalogBeanViaConditionalOnMissingBean() {
        runner.withUserConfiguration(CustomKpiCatalogConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(UiLayoutKpiCatalogOracle.class);
                            UiLayoutKpiCatalogOracle bean =
                                    context.getBean(UiLayoutKpiCatalogOracle.class);
                            assertThat(bean.isResourcePathAllowed("custom/resource")).isTrue();
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomKpiCatalogConfiguration {

        @Bean
        UiLayoutKpiCatalogOracle customUiLayoutKpiCatalog() {
            return UiLayoutKpiCatalogOracle.builder().allowResource("custom/resource").build();
        }
    }
}
