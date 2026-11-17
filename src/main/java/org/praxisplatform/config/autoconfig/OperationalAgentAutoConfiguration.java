package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.praxisplatform.config.ai.agent.bridge.DefaultMetadataGovernanceAgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.bridge.MetadataStarterInProcessAgentBridge;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.provider.GoogleGenAiAgentToolGateway;
import org.praxisplatform.config.ai.agent.provider.OpenAiResponsesAgentToolGateway;
import org.praxisplatform.config.ai.agent.provider.RoutingAiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamService;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.runtime.SecurityContextAgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordanceContributor;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContextCustomizer;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.spi.AgentTopicContributor;
import org.praxisplatform.config.ai.agent.spi.CompositeAgentThreadContextPropagator;
import org.praxisplatform.config.ai.agent.tools.DiscoverTopicResourcesAndSchemasTool;
import org.praxisplatform.config.ai.agent.tools.GetEntity360DossierTool;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool;
import org.praxisplatform.config.ai.agent.tools.QueryResourceRecordsTool;
import org.praxisplatform.config.ai.agent.tools.SearchAndResolveEntitiesTool;
import org.praxisplatform.config.controller.OperationalAgentController;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configuracao do assistente operacional com tool calling explicito via SDKs oficiais,
 * ponte em processo {@code praxis-metadata-starter}, cofre efemero de PII Mod-11 e SSE idempotente.
 */
@AutoConfiguration
@AutoConfigureAfter(AgenticAuthoringAutoConfiguration.class)
@ConditionalOnProperty(prefix = "praxis.ai.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OperationalAgentAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityContextHolder.class)
    static class SecurityPropagationConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityContextAgentThreadContextPropagator.class)
        public SecurityContextAgentThreadContextPropagator securityContextAgentThreadContextPropagator() {
            return new SecurityContextAgentThreadContextPropagator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AbstractResourceQueryController.class)
    static class MetadataStarterBridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean(AgentAuthorizationBridge.class)
        public AgentAuthorizationBridge defaultMetadataGovernanceAgentAuthorizationBridge() {
            return new DefaultMetadataGovernanceAgentAuthorizationBridge();
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
                AgentAuthorizationBridge authorizationBridge,
                @Qualifier("mvcConversionService") ObjectProvider<ConversionService> mvcConversionService,
                ObjectProvider<ConversionService> fallbackConversionService,
                ObjectProvider<ObjectMapper> objectMapperProvider,
                ObjectProvider<PlatformTransactionManager> transactionManager) {
            ConversionService conversionService = mvcConversionService.getIfAvailable();
            if (conversionService == null) {
                conversionService = fallbackConversionService.getIfAvailable();
            }
            return new MetadataStarterInProcessAgentBridge(
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
                    actionContributors.orderedStream().toList());
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
            @Value("${spring.ai.openai.api-key:}") String apiKey,
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
            List<AiToolCallingProvider> gateways) {
        return new RoutingAiToolCallingProvider(providerManagementService.getIfAvailable(), gateways);
    }

    @Bean
    @ConditionalOnMissingBean
    public CompositeAgentThreadContextPropagator compositeAgentThreadContextPropagator(
            ObjectProvider<AgentThreadContextPropagator> propagators) {
        return new CompositeAgentThreadContextPropagator(propagators.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public OperationalAssistantBlockComposer operationalAssistantBlockComposer(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new OperationalAssistantBlockComposer(objectMapperProvider.getIfAvailable());
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
    @ConditionalOnMissingBean
    public PraxisOperationalAgentLoop praxisOperationalAgentLoop(
            AiToolCallingProvider aiToolCallingProvider,
            ObjectProvider<OperationalAgentTool> tools,
            OperationalAssistantBlockComposer blockComposer,
            @Value("${praxis.ai.agent.max-iterations:6}") int maxIterations) {
        return new PraxisOperationalAgentLoop(
                aiToolCallingProvider,
                tools.orderedStream().toList(),
                blockComposer,
                maxIterations);
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
            AiStreamAccessTokenService streamAccessTokenService) {
        return new OperationalAgentController(
                turnStreamService, principalContextResolver, streamAccessTokenService);
    }
}
