package org.praxisplatform.config.autoconfig;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.praxisplatform.config.service.SensitiveCriteriaDiscoverySource;
import org.praxisplatform.config.service.SensitiveTenantConfigGuard;
import org.praxisplatform.config.service.UiConfigWriteAuthorizer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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
@AutoConfiguration
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
