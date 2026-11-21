package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader;
import org.praxisplatform.config.controller.GovernedRulesStudioCompositionController;
import org.praxisplatform.config.controller.GovernedRulesStudioSandboxController;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotHeadRepository;
import org.praxisplatform.config.repository.DomainRuleSnapshotRepository;
import org.praxisplatform.config.runtime.DtoRuleFactContractFactory;
import org.praxisplatform.config.runtime.GovernedMultiTenantSnapshotBootstrapper;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor;
import org.praxisplatform.config.runtime.GovernedRuleFamilyRegistry;
import org.praxisplatform.config.runtime.GovernedRulesStudioActionRegistryPostProcessor;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionService;
import org.praxisplatform.config.runtime.GovernedRulesStudioDefinitionSeeder;
import org.praxisplatform.config.runtime.GovernedRulesStudioSandboxEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioSandboxService;
import org.praxisplatform.config.runtime.MetadataDtoRuleFactContractFactory;
import org.praxisplatform.config.service.DomainRuleChangeWorkspaceService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleImplementationCatalog;
import org.praxisplatform.config.service.DomainRuleRolloutPolicyService;
import org.praxisplatform.config.service.DomainRuleRolloutService;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.config.service.DomainRuleTestRunService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * Auto-configuração zero-boilerplate da superfície de governança do {@code praxis-policy-studio} e
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
@AutoConfiguration(
        after = DomainRuleSnapshotAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration",
        beforeName = "org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration")
public class GovernedRulesStudioAutoConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor governedRulesStudioEndpointOwnerDeduplicator() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                ClassLoader loader = GovernedRulesStudioAutoConfiguration.class.getClassLoader();
                deduplicateOwner(
                        registry,
                        "governedRulesStudioCompositionController",
                        GovernedRulesStudioCompositionController.class,
                        GovernedRulesStudioCompositionEndpointOwner.class,
                        loader);
                deduplicateOwner(
                        registry,
                        "governedRulesStudioSandboxController",
                        GovernedRulesStudioSandboxController.class,
                        GovernedRulesStudioSandboxEndpointOwner.class,
                        loader);
            }

            private void deduplicateOwner(
                    BeanDefinitionRegistry registry,
                    String starterBeanName,
                    Class<?> starterBeanClass,
                    Class<?> ownerInterface,
                    ClassLoader loader) {
                if (!registry.containsBeanDefinition(starterBeanName)) {
                    return;
                }
                for (String name : registry.getBeanDefinitionNames()) {
                    if (starterBeanName.equals(name)) {
                        continue;
                    }
                    BeanDefinition bd = registry.getBeanDefinition(name);
                    if (isCustomEndpointOwner(bd, starterBeanClass, ownerInterface, loader)) {
                        registry.removeBeanDefinition(starterBeanName);
                        return;
                    }
                }
            }

            private boolean isCustomEndpointOwner(
                    BeanDefinition bd,
                    Class<?> starterBeanClass,
                    Class<?> ownerInterface,
                    ClassLoader loader) {
                Class<?> resolved = bd.getResolvableType().resolve();
                if (resolved != null
                        && resolved != starterBeanClass
                        && ownerInterface.isAssignableFrom(resolved)) {
                    return true;
                }
                String candidateClassName = bd.getBeanClassName();
                if (candidateClassName == null
                        && bd instanceof AnnotatedBeanDefinition abd
                        && abd.getFactoryMethodMetadata() != null) {
                    candidateClassName = abd.getFactoryMethodMetadata().getReturnTypeName();
                }
                if (candidateClassName == null
                        || starterBeanClass.getName().equals(candidateClassName)) {
                    return false;
                }
                try {
                    Class<?> clazz = ClassUtils.forName(candidateClassName, loader);
                    return ownerInterface.isAssignableFrom(clazz);
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
    @ConditionalOnBean({
        GovernedRuleFamilyRegistry.class,
        DomainRuleService.class,
        DomainRuleChangeWorkspaceService.class,
        DomainRuleTestRunService.class,
        DomainRuleSnapshotService.class
    })
    GovernedRulesStudioSandboxService governedRulesStudioSandboxService(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleService domainRuleService,
            DomainRuleChangeWorkspaceService workspaceService,
            DomainRuleTestRunService testRunService,
            DomainRuleSnapshotService snapshotService,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clockProvider) {
        return new GovernedRulesStudioSandboxService(
                familyRegistry,
                domainRuleService,
                workspaceService,
                testRunService,
                snapshotService,
                objectMapper,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(GovernedRulesStudioSandboxEndpointOwner.class)
    @ConditionalOnBean({
        GovernedRulesStudioSandboxService.class,
        DomainRuleGovernancePrincipalResolver.class
    })
    @ConditionalOnProperty(
            prefix = "praxis.config.rules-studio.sandbox-http",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    GovernedRulesStudioSandboxController governedRulesStudioSandboxController(
            GovernedRulesStudioSandboxService sandboxService,
            DomainRuleGovernancePrincipalResolver principalResolver) {
        return new GovernedRulesStudioSandboxController(sandboxService, principalResolver);
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
            @Value("${praxis.config.rules-studio.seed.environment:local}") String environment,
            ObjectProvider<DtoRuleFactContractFactory> dtoRuleFactContractFactoryProvider) {
        return new GovernedRulesStudioDefinitionSeeder(
                familyRegistry,
                domainRuleService,
                objectMapper,
                tenantIds,
                environment,
                dtoRuleFactContractFactoryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
        GovernedRuleFamilyRegistry.class,
        DomainRuleSnapshotHeadRepository.class,
        DomainRuleSnapshotRepository.class,
        DomainRuleSnapshotService.class,
        DomainRuleRolloutPolicyService.class,
        DomainRuleRolloutService.class
    })
    @ConditionalOnProperty(
            prefix = "praxis.config.domain-rules.multi-tenant-bootstrap",
            name = "enabled",
            havingValue = "true")
    GovernedMultiTenantSnapshotBootstrapper governedMultiTenantSnapshotBootstrapper(
            GovernedRuleFamilyRegistry familyRegistry,
            DomainRuleSnapshotHeadRepository headRepository,
            DomainRuleSnapshotRepository snapshotRepository,
            DomainRuleSnapshotService snapshotService,
            DomainRuleRolloutPolicyService rolloutPolicyService,
            DomainRuleRolloutService rolloutService,
            ObjectProvider<DomainRuleImplementationCatalog> implementationCatalogProvider,
            ObjectProvider<GovernedRulesStudioDefinitionSeeder> definitionSeederProvider,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clockProvider,
            @Value("${praxis.config.domain-rules.multi-tenant-bootstrap.tenant-ids:default}")
                    List<String> tenantIds,
            @Value("${praxis.config.domain-rules.multi-tenant-bootstrap.environments:local}")
                    List<String> environments,
            @Value(
                            "${praxis.config.domain-rules.multi-tenant-bootstrap.valid-from-utc:2026-01-01T00:00:00Z}")
                    String validFromUtc,
            @Value("${praxis.config.domain-rules.multi-tenant-bootstrap.seed-definitions:true}")
                    boolean seedDefinitions,
            @Value("${praxis.config.domain-rules.multi-tenant-bootstrap.fail-fast:false}")
                    boolean failFast) {
        return new GovernedMultiTenantSnapshotBootstrapper(
                familyRegistry,
                headRepository,
                snapshotRepository,
                snapshotService,
                rolloutPolicyService,
                rolloutService,
                implementationCatalogProvider.getIfAvailable(
                        DomainRuleImplementationCatalog::denyAll),
                definitionSeederProvider.getIfAvailable(),
                objectMapper,
                clockProvider.getIfAvailable(Clock::systemUTC),
                tenantIds,
                environments,
                validFromUtc,
                seedDefinitions,
                failFast);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name = {
                "org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher",
                "org.praxisplatform.rules.facts.RuleFactContract"
            })
    static class MetadataFactContractConfiguration {

        @Bean
        @ConditionalOnMissingBean(DtoRuleFactContractFactory.class)
        DtoRuleFactContractFactory dtoRuleFactContractFactory(ObjectMapper objectMapper) {
            return new MetadataDtoRuleFactContractFactory(objectMapper);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.praxisplatform.uischema.action.ActionDefinitionRegistry")
    @ConditionalOnBean(GovernedRuleFamilyRegistry.class)
    static class MetadataActionRegistryConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "governedRulesStudioActionRegistryPostProcessor")
        @ConditionalOnProperty(
                prefix = "praxis.config.rules-studio.action-registry",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        static BeanPostProcessor governedRulesStudioActionRegistryPostProcessor(
                ObjectProvider<GovernedRuleFamilyRegistry> familyRegistryProvider,
                ApplicationContext applicationContext,
                @Value(
                                "${praxis.config.rules-studio.action-registry.additional-resource-keys:}")
                        List<String> additionalResourceKeys) {
            return new GovernedRulesStudioActionRegistryPostProcessor(
                    familyRegistryProvider,
                    applicationContext,
                    GovernedRulesStudioSandboxController.RESOURCE_KEY,
                    GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_ID,
                    GovernedRulesStudioSandboxController.OPERATIONAL_TEST_ACTION_PATH,
                    additionalResourceKeys);
        }
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
                        ObjectProvider<DomainRuleDefinitionRepository> definitionRepositoryProvider,
                        ObjectProvider<ObjectMapper> objectMapperProvider,
                        @Value("${praxis.config.domain-rules.default-environment:local}")
                                String defaultEnvironment) {
            return MetadataDomainRuleSetCatalogBridgeSupport.createProvider(
                    familyRegistry,
                    headSetReaderProvider,
                    definitionRepositoryProvider,
                    objectMapperProvider,
                    defaultEnvironment);
        }
    }
}
