package org.praxisplatform.config.autoconfig;

import org.praxisplatform.config.service.MetadataStarterUiLayoutKpiCatalogOracle;
import org.praxisplatform.config.service.UiLayoutKpiCatalogOracle;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configura o {@link UiLayoutKpiCatalogOracle} apoiado no catálogo do
 * {@code praxis-metadata-starter} independentemente de {@code CorporatePageCatalogContributor},
 * garantindo que validações de páginas/KPIs em {@code ui-layouts} possuam introspecção real de
 * controladores, campos {@code stats}, {@code FilterDTO}, receitas verificadas e projeções
 * {@code @UiAnalytics}.
 */
@AutoConfiguration(
        before = {
            UiLayoutCorporatePagesAutoConfiguration.class,
            UiLayoutResolutionAutoConfiguration.class
        })
@ConditionalOnClass(
        name = {
            "org.praxisplatform.uischema.controller.base.AbstractResourceQueryController",
            "org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver"
        })
public class UiLayoutKpiCatalogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(UiLayoutKpiCatalogOracle.class)
    public UiLayoutKpiCatalogOracle metadataStarterUiLayoutKpiCatalogOracle(
            ApplicationContext applicationContext) {
        return MetadataStarterUiLayoutKpiCatalogOracle.fromApplicationContext(applicationContext);
    }
}
