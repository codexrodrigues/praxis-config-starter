package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader;
import org.praxisplatform.config.controller.GovernedRulesStudioCompositionController;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor;
import org.praxisplatform.config.runtime.GovernedRuleFamilyRegistry;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionService;
import org.praxisplatform.config.runtime.GovernedRulesStudioDefinitionSeeder;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * Auto-configuração zero-boilerplate da superfície de governança do {@code praxis-rules-studio} e
 * da ponte semântica {@code /schemas/domain} ({@code PLAT-EVO-08}).
 *
 * <p>Habilita automaticamente para qualquer host que registre {@link GovernedRuleFamilyDescriptor}s:
 * <ol>
 *   <li>Serviço e endpoints HTTP canônicos de composição 4-olhos Maker-Checker, publicação e probe
 *       de rollout em {@code /api/praxis/rules-studio/rule-sets/**} (recuando automaticamente se o
 *       host declarar um {@link GovernedRulesStudioCompositionEndpointOwner});</li>
 *   <li>Seeder idempotente opcional ({@code praxis.config.rules-studio.seed.enabled=true}) de slots
 *       e bindings no {@link DomainRuleService};</li>
 *   <li>Projeção automática de {@code DomainRuleSetCatalogProvider} para {@code /schemas/domain}
 *       quando {@code praxis-metadata-starter} estiver presente no classpath e o host não declarar
 *       seu próprio provider.</li>
 * </ol>
 */
@AutoConfiguration(after = DomainRuleSnapshotAutoConfiguration.class)
public class GovernedRulesStudioAutoConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor governedRulesStudioEndpointOwnerDeduplicator() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                if (!registry.containsBeanDefinition("governedRulesStudioCompositionController")) {
                    return;
                }
                ClassLoader loader = GovernedRulesStudioAutoConfiguration.class.getClassLoader();
                for (String name : registry.getBeanDefinitionNames()) {
                    if ("governedRulesStudioCompositionController".equals(name)) {
                        continue;
                    }
                    BeanDefinition bd = registry.getBeanDefinition(name);
                    if (isCustomEndpointOwner(bd, loader)) {
                        registry.removeBeanDefinition("governedRulesStudioCompositionController");
                        return;
                    }
                }
            }

            private boolean isCustomEndpointOwner(BeanDefinition bd, ClassLoader loader) {
                Class<?> resolved = bd.getResolvableType().resolve();
                if (resolved != null
                        && resolved != GovernedRulesStudioCompositionController.class
                        && GovernedRulesStudioCompositionEndpointOwner.class.isAssignableFrom(
                                resolved)) {
                    return true;
                }
                String candidateClassName = bd.getBeanClassName();
                if (candidateClassName == null
                        && bd instanceof AnnotatedBeanDefinition abd
                        && abd.getFactoryMethodMetadata() != null) {
                    candidateClassName = abd.getFactoryMethodMetadata().getReturnTypeName();
                }
                if (candidateClassName == null
                        || GovernedRulesStudioCompositionController.class
                                .getName()
                                .equals(candidateClassName)) {
                    return false;
                }
                try {
                    Class<?> clazz = ClassUtils.forName(candidateClassName, loader);
                    return GovernedRulesStudioCompositionEndpointOwner.class.isAssignableFrom(
                            clazz);
                } catch (Throwable ignored) {
                    return false;
                }
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        GovernedRuleFamilyRegistry.class,
        DomainRuleService.class,
        DomainRuleSnapshotService.class
    })
    GovernedRulesStudioCompositionService governedRulesStudioCompositionService(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleService domainRuleService,
            DomainRuleSnapshotService snapshotService,
            ObjectProvider<DomainRuleRolloutService> rolloutService,
            ObjectProvider<DomainRuleImplementationCatalog> implementationCatalog,
            ObjectMapper objectMapper) {
        return new GovernedRulesStudioCompositionService(
                familyRegistry,
                domainRuleService,
                snapshotService,
                Optional.ofNullable(rolloutService.getIfAvailable()),
                implementationCatalog.getIfAvailable(DomainRuleImplementationCatalog::denyAll),
                objectMapper,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(GovernedRulesStudioCompositionEndpointOwner.class)
    @ConditionalOnBean({
        GovernedRulesStudioCompositionService.class,
        DomainRuleGovernancePrincipalResolver.class
    })
    @ConditionalOnProperty(
            prefix = "praxis.config.rules-studio.composition-http",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    GovernedRulesStudioCompositionController governedRulesStudioCompositionController(
            GovernedRulesStudioCompositionService compositionService,
            DomainRuleGovernancePrincipalResolver principalResolver) {
        return new GovernedRulesStudioCompositionController(compositionService, principalResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({GovernedRuleFamilyRegistry.class, DomainRuleService.class})
    @ConditionalOnProperty(
            prefix = "praxis.config.rules-studio.seed",
            name = "enabled",
            havingValue = "true")
    GovernedRulesStudioDefinitionSeeder governedRulesStudioDefinitionSeeder(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleService domainRuleService,
            ObjectMapper objectMapper,
            @Value("${praxis.config.rules-studio.seed.tenant-ids:1}") List<String> tenantIds,
            @Value("${praxis.config.rules-studio.seed.environment:local}") String environment) {
        return new GovernedRulesStudioDefinitionSeeder(
                familyRegistry, domainRuleService, objectMapper, tenantIds, environment);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider")
    @ConditionalOnBean(GovernedRuleFamilyRegistry.class)
    static class MetadataDomainRuleSetCatalogBridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean(
                type = "org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider")
        org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider
                governedFamilyDomainRuleSetCatalogProvider(
                        GovernedRuleFamilyRegistry familyRegistry,
                        ObjectProvider<PublishedRuleSnapshotHeadSetReader> headSetReaderProvider,
                        @Value("${praxis.config.domain-rules.default-environment:local}")
                                String defaultEnvironment) {
            return MetadataDomainRuleSetCatalogBridgeSupport.createProvider(
                    familyRegistry, headSetReaderProvider, defaultEnvironment);
        }
    }
}
