package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Validator;
import java.util.List;
import org.praxisplatform.config.ai.agent.bridge.ConfigDomainRuleKnowledgeContributor;
import org.praxisplatform.config.ai.agent.bridge.DefaultMetadataGovernanceAgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.bridge.MetadataStarterInProcessAgentBridge;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.provider.AiProviderEligibilityPolicy;
import org.praxisplatform.config.ai.agent.provider.GoogleGenAiAgentToolGateway;
import org.praxisplatform.config.ai.agent.provider.OpenAiResponsesAgentToolGateway;
import org.praxisplatform.config.ai.agent.provider.ProfileSovereigntyAiProviderEligibilityPolicy;
import org.praxisplatform.config.ai.agent.provider.RoutingAiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.AgentSystemInstructionComposer;
import org.praxisplatform.config.ai.agent.runtime.AnalyticalRecipeAndEpistemicInstructionContributor;
import org.praxisplatform.config.ai.agent.runtime.CoreOperationalRulesInstructionContributor;
import org.praxisplatform.config.ai.agent.runtime.HostContextSystemInstructionContributor;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentProposalService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.runtime.ResourceDiscoverySummaryInstructionContributor;
import org.praxisplatform.config.ai.agent.runtime.SecurityBoundaryInstructionContributor;
import org.praxisplatform.config.ai.agent.runtime.SecurityContextAgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.runtime.StatisticalIntelligenceBlockContributor;
import org.praxisplatform.config.ai.agent.security.AgentSecurityGuard;
import org.praxisplatform.config.ai.agent.security.AgentSecurityMode;
import org.praxisplatform.config.ai.agent.security.CompositeAgentSecurityGuard;
import org.praxisplatform.config.ai.agent.security.DefaultDeterministicAgentSecurityGuard;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordanceContributor;
import org.praxisplatform.config.ai.agent.spi.AgentActionPreparationContributor;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContextCustomizer;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeSearchContributor;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionContributor;
import org.praxisplatform.config.ai.agent.spi.AgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.spi.AgentTopicContributor;
import org.praxisplatform.config.ai.agent.spi.CompositeAgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.spi.OperationalAssistantBlockContributor;
import org.praxisplatform.config.ai.agent.telemetry.AgentTurnTelemetryRecorder;
import org.praxisplatform.config.ai.agent.telemetry.MicrometerGenAiAgentTelemetryRecorder;
import org.praxisplatform.config.ai.agent.tools.AggregateResourceStatsTool;
import org.praxisplatform.config.ai.agent.tools.DiscoverTopicResourcesAndSchemasTool;
import org.praxisplatform.config.ai.agent.tools.ExecuteVerifiedAnalyticalRecipeTool;
import org.praxisplatform.config.ai.agent.tools.ExplainCalculationMemoryTool;
import org.praxisplatform.config.ai.agent.tools.GetEntity360DossierTool;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool;
import org.praxisplatform.config.ai.agent.tools.PrepareActionOrMutationTool;
import org.praxisplatform.config.ai.agent.tools.PrepareBulkOperationTool;
import org.praxisplatform.config.ai.agent.tools.QueryResourceRecordsTool;
import org.praxisplatform.config.ai.agent.tools.RunResourceCounterfactualSearchTool;
import org.praxisplatform.config.ai.agent.tools.RunResourceStatisticalInferenceTool;
import org.praxisplatform.config.ai.agent.tools.RunResourceStochasticSimulationTool;
import org.praxisplatform.config.ai.agent.tools.ScanResourceAnomaliesTool;
import org.praxisplatform.config.ai.agent.tools.SearchAndResolveEntitiesTool;
import org.praxisplatform.config.ai.agent.tools.SearchDomainKnowledgeAndRulesTool;
import org.praxisplatform.config.ai.agent.tools.SimulateDeterminationOrRulesTool;
import org.praxisplatform.config.controller.OperationalAgentController;
import org.praxisplatform.config.repository.AiAgentActionProposalRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiProviderRateLimitStore;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.fact.ResourceFactCatalogProvider;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configuracao do assistente operacional com tool calling explicito via SDKs oficiais,
 * ponte em processo {@code praxis-metadata-starter}, cofre efemero de PII Mod-11 e SSE idempotente.
 */
@AutoConfiguration(afterName = {
        "org.praxisplatform.intelligence.starter.autoconfigure.PraxisIntelligenceAutoConfiguration"
})
@AutoConfigureAfter({
        AgenticAuthoringAutoConfiguration.class,
        ConfigDataSourceIsolationAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        ConfigTransactionManagerAliasAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "praxis.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
        OperationalAgentAutoConfiguration.SecurityPropagationConfiguration.class,
        OperationalAgentAutoConfiguration.MetadataStarterBridgeConfiguration.class,
        OperationalAgentAutoConfiguration.StatisticalIntelligenceToolsConfiguration.class
})
public class OperationalAgentAutoConfiguration {

    @ConditionalOnClass(SecurityContextHolder.class)
    static class SecurityPropagationConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityContextAgentThreadContextPropagator.class)
        public SecurityContextAgentThreadContextPropagator securityContextAgentThreadContextPropagator() {
            return new SecurityContextAgentThreadContextPropagator();
        }
    }

    @ConditionalOnClass(AbstractResourceQueryController.class)
    static class MetadataStarterBridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean(DefaultMetadataGovernanceAgentAuthorizationBridge.class)
        public DefaultMetadataGovernanceAgentAuthorizationBridge defaultMetadataGovernanceAgentAuthorizationBridge(
                ObjectProvider<AbstractResourceQueryController<?, ?, ?>> controllers) {
            return new DefaultMetadataGovernanceAgentAuthorizationBridge(controllers.orderedStream().toList());
        }

        @Bean
        @ConditionalOnMissingBean(AgentResourceExecutionBridge.class)
        public AgentResourceExecutionBridge metadataStarterInProcessAgentBridge(
                ObjectProvider<AbstractResourceQueryController<?, ?, ?>> controllers,
                ObjectProvider<AgentEntityCatalogContributor> entityContributors,
                ObjectProvider<AgentTopicContributor> topicContributors,
                ObjectProvider<AgentProactiveAlertContributor> alertContributors,
                ObjectProvider<AgentActionAffordanceContributor> actionContributors,
                ObjectProvider<CapabilityService> capabilityService,
                ObjectProvider<ActionCatalogService> actionCatalogService,
                ObjectProvider<SurfaceCatalogService> surfaceCatalogService,
                ObjectProvider<SurfaceDefinitionRegistry> surfaceDefinitionRegistry,
                ObjectProvider<ResourceFactCatalogProvider> factCatalogProviders,
                ObjectProvider<ActionDefinitionRegistry> actionDefinitionRegistry,
                ObjectProvider<ResourceVersionEtagService> resourceVersionEtagService,
                ObjectProvider<ResourceVersionScopeProvider> resourceVersionScopeProvider,
                ObjectProvider<Validator> validatorProvider,
                ObjectProvider<AgentActionPreparationContributor> preparationContributors,
                AgentAuthorizationBridge authorizationBridge,
                @Qualifier("mvcConversionService") ObjectProvider<ConversionService> mvcConversionService,
                ObjectProvider<ConversionService> fallbackConversionService,
                ObjectProvider<ObjectMapper> objectMapperProvider,
                ObjectProvider<PlatformTransactionManager> transactionManager,
                ObjectProvider<VerifiedAnalyticalRecipeResolver> verifiedAnalyticalRecipeResolverProvider,
                @Value("${praxis.ai.agent.analytics.require-verified-recipes:false}") boolean requireVerifiedRecipesForAi,
                @Value("${praxis.ai.agent.analytics.policy:}") String analyticsPolicy) {
            ConversionService conversionService = mvcConversionService.getIfAvailable();
            if (conversionService == null) {
                conversionService = fallbackConversionService.getIfAvailable();
            }
            MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                    controllers.orderedStream().toList(),
                    entityContributors.orderedStream().toList(),
                    topicContributors.orderedStream().toList(),
                    alertContributors.orderedStream().toList(),
                    capabilityService.getIfAvailable(),
                    actionCatalogService.getIfAvailable(),
                    surfaceCatalogService.getIfAvailable(),
                    authorizationBridge,
                    conversionService,
                    objectMapperProvider.getIfAvailable(),
                    transactionManager.getIfAvailable(),
                    actionContributors.orderedStream().toList(),
                    surfaceDefinitionRegistry.getIfAvailable(),
                    factCatalogProviders.orderedStream().toList(),
                    actionDefinitionRegistry.getIfAvailable(),
                    resourceVersionEtagService.getIfAvailable(),
                    resourceVersionScopeProvider.getIfAvailable(),
                    validatorProvider.getIfAvailable(),
                    preparationContributors.orderedStream().toList());
            MetadataStarterInProcessAgentBridge.VerifiedRecipePolicy resolvedPolicy =
                    resolveAnalyticsPolicy(analyticsPolicy, requireVerifiedRecipesForAi);
            bridge.configureVerifiedRecipes(verifiedAnalyticalRecipeResolverProvider.getIfAvailable(), resolvedPolicy);
            return bridge;
        }

        private static MetadataStarterInProcessAgentBridge.VerifiedRecipePolicy resolveAnalyticsPolicy(
                String rawPolicy,
                boolean requireVerifiedRecipesForAi) {
            if (rawPolicy != null && !rawPolicy.isBlank()) {
                return MetadataStarterInProcessAgentBridge.VerifiedRecipePolicy.valueOf(
                        rawPolicy.trim().toUpperCase(java.util.Locale.ROOT));
            }
            return requireVerifiedRecipesForAi
                    ? MetadataStarterInProcessAgentBridge.VerifiedRecipePolicy.PREFER_VERIFIED_WHEN_AVAILABLE
                    : MetadataStarterInProcessAgentBridge.VerifiedRecipePolicy.ALLOW_EXPLORATORY;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public GoogleGenAiAgentToolGateway googleGenAiAgentToolGateway(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${spring.ai.google.genai.api-key:${GEMINI_API_KEY:${GOOGLE_API_KEY:}}}") String apiKey,
            @Value("${spring.ai.google.genai.chat.options.model:gemini-flash-latest}") String model) {
        return new GoogleGenAiAgentToolGateway(
                objectMapperProvider.getIfAvailable(),
                apiKey,
                model);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAiResponsesAgentToolGateway openAiResponsesAgentToolGateway(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${spring.ai.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model) {
        return new OpenAiResponsesAgentToolGateway(
                objectMapperProvider.getIfAvailable(),
                apiKey,
                baseUrl,
                model);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "aiToolCallingProvider")
    public AiToolCallingProvider aiToolCallingProvider(
            ObjectProvider<AiProviderManagementService> providerManagementService,
            List<AiToolCallingProvider> gateways,
            ObjectProvider<AiProviderRateLimitStore> rateLimitStoreProvider,
            ObjectProvider<AiProviderEligibilityPolicy> eligibilityPolicies) {
        return new RoutingAiToolCallingProvider(
                providerManagementService.getIfAvailable(),
                gateways,
                rateLimitStoreProvider.getIfAvailable(),
                eligibilityPolicies.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public CompositeAgentThreadContextPropagator compositeAgentThreadContextPropagator(
            ObjectProvider<AgentThreadContextPropagator> propagators) {
        return new CompositeAgentThreadContextPropagator(propagators.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProfileSovereigntyAiProviderEligibilityPolicy profileSovereigntyAiProviderEligibilityPolicy(
            @Value("${praxis.ai.agent.sovereignty.restricted-profiles:}") List<String> restrictedProfiles,
            @Value("${praxis.ai.agent.sovereignty.restricted-resource-prefixes:}") List<String> restrictedResourcePrefixes,
            @Value("${praxis.ai.agent.sovereignty.restricted-topics:}") List<String> restrictedTopics,
            @Value("${praxis.ai.agent.sovereignty.restricted-path-segments:}") List<String> restrictedPathSegments,
            @Value("${praxis.ai.agent.sovereignty.sovereign-providers:}") List<String> sovereignProviders,
            @Value("${praxis.ai.agent.sovereignty.strict-fail-closed:true}") boolean strictFailClosed) {
        return new ProfileSovereigntyAiProviderEligibilityPolicy(
                restrictedProfiles,
                restrictedResourcePrefixes,
                restrictedTopics,
                restrictedPathSegments,
                sovereignProviders,
                strictFailClosed);
    }

    @Bean
    @ConditionalOnMissingBean
    public StatisticalIntelligenceBlockContributor statisticalIntelligenceBlockContributor() {
        return new StatisticalIntelligenceBlockContributor();
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationalAssistantBlockComposer operationalAssistantBlockComposer(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<OperationalAssistantBlockContributor> blockContributors) {
        return new OperationalAssistantBlockComposer(
                objectMapperProvider.getIfAvailable(),
                blockContributors.orderedStream().toList());
    }

    static final class StatisticalToolsEnabledCondition extends AnyNestedCondition {

        StatisticalToolsEnabledCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBean(type = "org.praxisplatform.intelligence.contracts.spi.ResourceIntelligenceExecutor")
        static class OnExecutorBean {
        }

        @ConditionalOnBean(type = "org.praxisplatform.intelligence.contracts.spi.StochasticSimulationOrchestrator")
        static class OnSimulationOrchestratorBean {
        }

        @ConditionalOnBean(type = "org.praxisplatform.intelligence.contracts.spi.CounterfactualSearchOrchestrator")
        static class OnCounterfactualOrchestratorBean {
        }

        @ConditionalOnProperty(
                prefix = "praxis.ai.agent.statistical-tools",
                name = "expose-when-unavailable",
                havingValue = "true")
        static class OnExposeWhenUnavailableProperty {
        }
    }

    @ConditionalOnClass(name = "org.praxisplatform.intelligence.contracts.spi.ResourceIntelligenceExecutor")
    public static class StatisticalIntelligenceToolsConfiguration {

        @Bean
        @Conditional(StatisticalToolsEnabledCondition.class)
        @ConditionalOnBean(AgentResourceExecutionBridge.class)
        @ConditionalOnMissingBean(RunResourceStatisticalInferenceTool.class)
        public RunResourceStatisticalInferenceTool runResourceStatisticalInferenceTool(
                AgentResourceExecutionBridge bridge) {
            return new RunResourceStatisticalInferenceTool(bridge);
        }

        @Bean
        @Conditional(StatisticalToolsEnabledCondition.class)
        @ConditionalOnBean(AgentResourceExecutionBridge.class)
        @ConditionalOnMissingBean(ScanResourceAnomaliesTool.class)
        public ScanResourceAnomaliesTool scanResourceAnomaliesTool(
                AgentResourceExecutionBridge bridge) {
            return new ScanResourceAnomaliesTool(bridge);
        }

        @Bean
        @Conditional(StatisticalToolsEnabledCondition.class)
        @ConditionalOnBean(AgentResourceExecutionBridge.class)
        @ConditionalOnMissingBean(RunResourceStochasticSimulationTool.class)
        public RunResourceStochasticSimulationTool runResourceStochasticSimulationTool(
                AgentResourceExecutionBridge bridge) {
            return new RunResourceStochasticSimulationTool(bridge);
        }

        @Bean
        @Conditional(StatisticalToolsEnabledCondition.class)
        @ConditionalOnBean(AgentResourceExecutionBridge.class)
        @ConditionalOnMissingBean(RunResourceCounterfactualSearchTool.class)
        public RunResourceCounterfactualSearchTool runResourceCounterfactualSearchTool(
                AgentResourceExecutionBridge bridge) {
            return new RunResourceCounterfactualSearchTool(bridge);
        }
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(SearchAndResolveEntitiesTool.class)
    public SearchAndResolveEntitiesTool searchAndResolveEntitiesTool(AgentResourceExecutionBridge bridge) {
        return new SearchAndResolveEntitiesTool(bridge);
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(DiscoverTopicResourcesAndSchemasTool.class)
    public DiscoverTopicResourcesAndSchemasTool discoverTopicResourcesAndSchemasTool(AgentResourceExecutionBridge bridge) {
        return new DiscoverTopicResourcesAndSchemasTool(bridge);
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(QueryResourceRecordsTool.class)
    public QueryResourceRecordsTool queryResourceRecordsTool(AgentResourceExecutionBridge bridge) {
        return new QueryResourceRecordsTool(bridge);
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(GetEntity360DossierTool.class)
    public GetEntity360DossierTool getEntity360DossierTool(AgentResourceExecutionBridge bridge) {
        return new GetEntity360DossierTool(bridge);
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(AggregateResourceStatsTool.class)
    public AggregateResourceStatsTool aggregateResourceStatsTool(AgentResourceExecutionBridge bridge) {
        return new AggregateResourceStatsTool(bridge);
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(ExecuteVerifiedAnalyticalRecipeTool.class)
    public ExecuteVerifiedAnalyticalRecipeTool executeVerifiedAnalyticalRecipeTool(AgentResourceExecutionBridge bridge) {
        return new ExecuteVerifiedAnalyticalRecipeTool(bridge);
    }

    @Bean
    @ConditionalOnMissingBean(CoreOperationalRulesInstructionContributor.class)
    public CoreOperationalRulesInstructionContributor coreOperationalRulesInstructionContributor() {
        return new CoreOperationalRulesInstructionContributor();
    }

    @Bean
    @ConditionalOnMissingBean(SecurityBoundaryInstructionContributor.class)
    public SecurityBoundaryInstructionContributor securityBoundaryInstructionContributor() {
        return new SecurityBoundaryInstructionContributor();
    }

    @Bean
    @ConditionalOnMissingBean(AnalyticalRecipeAndEpistemicInstructionContributor.class)
    public AnalyticalRecipeAndEpistemicInstructionContributor analyticalRecipeAndEpistemicInstructionContributor() {
        return new AnalyticalRecipeAndEpistemicInstructionContributor();
    }

    @Bean
    @ConditionalOnMissingBean(ResourceDiscoverySummaryInstructionContributor.class)
    public ResourceDiscoverySummaryInstructionContributor resourceDiscoverySummaryInstructionContributor() {
        return new ResourceDiscoverySummaryInstructionContributor();
    }

    @Bean
    @ConditionalOnMissingBean(HostContextSystemInstructionContributor.class)
    public HostContextSystemInstructionContributor hostContextSystemInstructionContributor() {
        return new HostContextSystemInstructionContributor();
    }

    @Bean
    @ConditionalOnMissingBean(AgentSystemInstructionComposer.class)
    public AgentSystemInstructionComposer agentSystemInstructionComposer(
            ObjectProvider<AgentSystemInstructionContributor> contributors) {
        List<AgentSystemInstructionContributor> resolved = contributors.orderedStream().toList();
        return resolved.isEmpty()
                ? AgentSystemInstructionComposer.defaultComposer()
                : new AgentSystemInstructionComposer(resolved);
    }

    @Bean
    @ConditionalOnMissingBean(DefaultDeterministicAgentSecurityGuard.class)
    public DefaultDeterministicAgentSecurityGuard defaultDeterministicAgentSecurityGuard() {
        return new DefaultDeterministicAgentSecurityGuard();
    }

    @Bean
    @ConditionalOnMissingBean(AgentTurnTelemetryRecorder.class)
    public AgentTurnTelemetryRecorder agentTurnTelemetryRecorder(
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        ObservationRegistry registry = observationRegistryProvider.getIfAvailable();
        return registry != null
                ? new MicrometerGenAiAgentTelemetryRecorder(registry)
                : AgentTurnTelemetryRecorder.NOOP;
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(ExplainCalculationMemoryTool.class)
    public ExplainCalculationMemoryTool explainCalculationMemoryTool(AgentResourceExecutionBridge bridge) {
        return new ExplainCalculationMemoryTool(bridge);
    }

    @Bean
    @ConditionalOnBean(DomainRuleDefinitionRepository.class)
    @ConditionalOnMissingBean(ConfigDomainRuleKnowledgeContributor.class)
    public ConfigDomainRuleKnowledgeContributor configDomainRuleKnowledgeContributor(
            DomainRuleDefinitionRepository repository,
            @Qualifier(ConfigTransactionManagerNames.CONFIG) ObjectProvider<PlatformTransactionManager> configTxManager,
            ObjectProvider<PlatformTransactionManager> fallbackTxManager) {
        PlatformTransactionManager txManager = configTxManager.getIfAvailable();
        if (txManager == null) {
            txManager = fallbackTxManager.getIfAvailable();
        }
        return new ConfigDomainRuleKnowledgeContributor(repository, txManager);
    }

    @Bean
    @ConditionalOnMissingBean(SearchDomainKnowledgeAndRulesTool.class)
    public SearchDomainKnowledgeAndRulesTool searchDomainKnowledgeAndRulesTool(
            ObjectProvider<AgentKnowledgeSearchContributor> knowledgeContributors) {
        return new SearchDomainKnowledgeAndRulesTool(knowledgeContributors.orderedStream().toList());
    }

    @Bean
    @ConditionalOnBean(AgentResourceExecutionBridge.class)
    @ConditionalOnMissingBean(SimulateDeterminationOrRulesTool.class)
    public SimulateDeterminationOrRulesTool simulateDeterminationOrRulesTool(AgentResourceExecutionBridge bridge) {
        return new SimulateDeterminationOrRulesTool(bridge);
    }

    @Bean
    @ConditionalOnBean({AiAgentActionProposalRepository.class, AgentResourceExecutionBridge.class})
    @ConditionalOnMissingBean(OperationalAgentProposalService.class)
    public OperationalAgentProposalService operationalAgentProposalService(
            AiAgentActionProposalRepository proposalRepository,
            AgentResourceExecutionBridge bridge,
            ObjectProvider<AgentExecutionContextCustomizer> contextCustomizers,
            @Qualifier(ConfigTransactionManagerNames.CONFIG) ObjectProvider<PlatformTransactionManager> configTxManager,
            ObjectProvider<PlatformTransactionManager> fallbackTxManager,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${praxis.ai.agent.proposal-ttl-seconds:900}") long proposalTtlSeconds,
            @Value("${praxis.ai.agent.proposal-retention-seconds:86400}") long proposalRetentionSeconds) {
        PlatformTransactionManager txManager = configTxManager.getIfAvailable();
        if (txManager == null) {
            txManager = fallbackTxManager.getIfAvailable();
        }
        return new OperationalAgentProposalService(
                proposalRepository,
                bridge,
                contextCustomizers.orderedStream().toList(),
                txManager,
                objectMapperProvider.getIfAvailable(),
                proposalTtlSeconds,
                proposalRetentionSeconds);
    }

    @Bean
    @ConditionalOnBean(OperationalAgentProposalService.class)
    @ConditionalOnMissingBean(PrepareActionOrMutationTool.class)
    public PrepareActionOrMutationTool prepareActionOrMutationTool(OperationalAgentProposalService proposalService) {
        return new PrepareActionOrMutationTool(proposalService);
    }

    @Bean
    @ConditionalOnBean(OperationalAgentProposalService.class)
    @ConditionalOnMissingBean(PrepareBulkOperationTool.class)
    public PrepareBulkOperationTool prepareBulkOperationTool(OperationalAgentProposalService proposalService) {
        return new PrepareBulkOperationTool(proposalService);
    }

    @Bean
    @ConditionalOnMissingBean
    public PraxisOperationalAgentLoop praxisOperationalAgentLoop(
            AiToolCallingProvider aiToolCallingProvider,
            ObjectProvider<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer,
            @Value("${praxis.ai.agent.max-iterations:6}") int maxIterations,
            ObjectProvider<AgentResourceExecutionBridge> bridgeProvider,
            ObjectProvider<AgentSystemInstructionComposer> instructionComposerProvider,
            ObjectProvider<AgentSecurityGuard> securityGuardsProvider,
            @Value("${praxis.ai.agent.security.mode:ENFORCE}") AgentSecurityMode securityMode,
            ObjectProvider<AgentTurnTelemetryRecorder> telemetryRecorderProvider) {
        List<AgentSecurityGuard> guards = securityGuardsProvider.orderedStream().toList();
        AgentSecurityGuard effectiveGuard = guards.isEmpty()
                ? new DefaultDeterministicAgentSecurityGuard()
                : (guards.size() == 1 ? guards.get(0) : new CompositeAgentSecurityGuard(guards));
        return new PraxisOperationalAgentLoop(
                aiToolCallingProvider,
                tools.orderedStream().toList(),
                blockComposer,
                maxIterations,
                bridgeProvider.getIfAvailable(),
                instructionComposerProvider.getIfAvailable(AgentSystemInstructionComposer::defaultComposer),
                effectiveGuard,
                securityMode,
                telemetryRecorderProvider.getIfAvailable(() -> AgentTurnTelemetryRecorder.NOOP));
    }

    @Bean
    @ConditionalOnBean({AiThreadService.class, AiTurnService.class, AiTurnEventService.class, AiStreamAccessTokenService.class})
    @ConditionalOnMissingBean
    public OperationalAgentTurnStreamService operationalAgentTurnStreamService(
            PraxisOperationalAgentLoop agentLoop,
            AiThreadService threadService,
            AiTurnService turnService,
            AiTurnEventService turnEventService,
            AiStreamAccessTokenService streamAccessTokenService,
            ObjectProvider<AiProviderManagementService> providerManagementService,
            CompositeAgentThreadContextPropagator contextPropagator,
            ObjectProvider<AgentExecutionContextCustomizer> contextCustomizers,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new OperationalAgentTurnStreamService(
                agentLoop,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService,
                providerManagementService.getIfAvailable(),
                contextPropagator,
                contextCustomizers.orderedStream().toList(),
                objectMapperProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean({OperationalAgentTurnStreamService.class, AiPrincipalContextResolver.class, AiStreamAccessTokenService.class})
    @ConditionalOnProperty(prefix = "praxis.ai.agent", name = "http-enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public OperationalAgentController operationalAgentController(
            OperationalAgentTurnStreamService turnStreamService,
            AiPrincipalContextResolver principalContextResolver,
            AiStreamAccessTokenService streamAccessTokenService,
            ObjectProvider<OperationalAgentProposalService> proposalServiceProvider) {
        return new OperationalAgentController(
                turnStreamService,
                principalContextResolver,
                streamAccessTokenService,
                proposalServiceProvider.getIfAvailable());
    }
}
