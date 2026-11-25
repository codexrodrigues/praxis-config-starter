package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.praxisplatform.config.service.EffectiveUiAudienceProvider;
import org.praxisplatform.config.service.KpiGovernanceUserConfigGuard;
import org.praxisplatform.config.service.PublishedUiLayoutBaselineProvider;
import org.praxisplatform.config.service.ReleaseReaderPublishedUiLayoutBaselineProvider;
import org.praxisplatform.config.service.SensitiveCriteriaDiscoverySource;
import org.praxisplatform.config.service.SensitiveTenantConfigGuard;
import org.praxisplatform.config.service.UiConfigWriteAuthorizer;
import org.praxisplatform.config.service.UiLayoutCompositionReleaseReader;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configura a autorização padrão de escrita para {@code ui_user_config}, mantendo
 * compatibilidade com preferências de usuário enquanto bloqueia critérios ou valores sensíveis
 * em configurações compartilhadas de escopo {@code tenant}.
 */
@AutoConfiguration(after = UiLayoutResolutionAutoConfiguration.class)
@EnableConfigurationProperties(UiConfigWriteGuardProperties.class)
@Import(UiConfigWriteAuthorizationAutoConfiguration.MetadataGovernanceDiscoveryConfiguration.class)
public class UiConfigWriteAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SensitiveTenantConfigGuard.class)
    SensitiveTenantConfigGuard sensitiveTenantConfigGuard(
            UiConfigWriteGuardProperties properties,
            ObjectProvider<SensitiveCriteriaDiscoverySource> discoverySources) {
        return new SensitiveTenantConfigGuard(
                properties.isBlockSensitiveTenantCriteria(),
                properties.getAdditionalSensitiveKeys(),
                discoverySources.orderedStream().collect(Collectors.toList()));
    }

    @Bean
    @ConditionalOnMissingBean(UiConfigWriteAuthorizer.class)
    UiConfigWriteAuthorizer permitUnclassifiedUiConfigWrites(SensitiveTenantConfigGuard guard) {
        return guard::assertAllowed;
    }

    @Bean
    @ConditionalOnBean(UiLayoutCompositionReleaseReader.class)
    @ConditionalOnMissingBean(PublishedUiLayoutBaselineProvider.class)
    PublishedUiLayoutBaselineProvider publishedUiLayoutBaselineProvider(
            UiLayoutCompositionReleaseReader releaseReader,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<EffectiveUiAudienceProvider> audienceProvider) {
        return new ReleaseReaderPublishedUiLayoutBaselineProvider(
                releaseReader,
                objectMapperProvider.getIfAvailable(
                        () -> new ObjectMapper().findAndRegisterModules()),
                audienceProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(KpiGovernanceUserConfigGuard.class)
    KpiGovernanceUserConfigGuard kpiGovernanceUserConfigGuard(
            ObjectProvider<PublishedUiLayoutBaselineProvider> baselineProvider) {
        return new KpiGovernanceUserConfigGuard(baselineProvider.getIfAvailable());
    }

    @ConditionalOnClass(name = {
        "org.praxisplatform.uischema.annotation.DomainGovernance",
        "org.praxisplatform.uischema.filter.annotation.Filterable"
    })
    static class MetadataGovernanceDiscoveryConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "metadataGovernanceSensitiveCriteriaDiscoverer")
        SensitiveCriteriaDiscoverySource metadataGovernanceSensitiveCriteriaDiscoverer(
                UiConfigWriteGuardProperties properties, BeanFactory beanFactory) {
            List<String> basePackages = new ArrayList<>(properties.getDtoBasePackages());
            if (properties.isScanAutoConfigurationPackages()
                    && AutoConfigurationPackages.has(beanFactory)) {
                basePackages.addAll(AutoConfigurationPackages.get(beanFactory));
            }
            return new org.praxisplatform.config.service.MetadataGovernanceSensitiveCriteriaDiscoverer(
                    basePackages);
        }
    }
}
