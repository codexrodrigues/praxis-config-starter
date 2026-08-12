package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesRefreshListener;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentEditPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringConsultativeAnswerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringConsultativeApiCatalogProjectionService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringCurrentPageAnalyzer;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunReportService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApiMetadataCandidateCatalog;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDomainCatalogCandidateEnhancer;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringLlmIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringLlmPreIntentToolPlanningService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringGenericUiCompositionPlanProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPresentationAffordanceCatalogService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPresentationAffordanceDiscoveryService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPresentationAffordanceProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceBackedPresentationAffordanceProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewMessageSynthesizerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreIntentToolPlanningService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringProjectKnowledgeService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceDiscoveryService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReplayAuditService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringEffectCompilerRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTargetResolverRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringToolRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnEngine;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDefaultToolLoopPlanner;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringOrchestrator;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringToolLoopExecutor;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringToolLoopPlanner;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringUiCompositionPlanProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringUiCompositionTemplateResolver;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringValidatorRegistry;
import org.praxisplatform.config.controller.AgenticAuthoringManifestController;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.service.DomainCatalogIngestionService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;
import org.praxisplatform.config.service.GovernedPlatformRequestAuthorizationProvider;
import org.praxisplatform.config.service.LiveOptionValueRetrievalService;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.ResourceSurfaceCatalogRetrievalService;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

@AutoConfiguration
@ConditionalOnClass(AgenticAuthoringDryRunService.class)
@EnableConfigurationProperties({
        AgenticAuthoringArtifactProperties.class,
        AgenticAuthoringComponentCapabilitiesProperties.class
})
public class AgenticAuthoringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringArtifactSource agenticAuthoringArtifactSource(
            AgenticAuthoringArtifactProperties properties) {
        return new AgenticAuthoringArtifactSource(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringDryRunService agenticAuthoringDryRunService(ObjectMapper objectMapper) {
        return new AgenticAuthoringDryRunService(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringApiMetadataCandidateCatalog agenticAuthoringApiMetadataCandidateCatalog(
            ObjectProvider<ApiMetadataRepository> apiMetadataRepository,
            ObjectProvider<ContextRetrievalService> contextRetrievalService) {
        return new AgenticAuthoringApiMetadataCandidateCatalog(
                apiMetadataRepository.getIfAvailable(),
                contextRetrievalService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderManagementService.class)
    public AgenticAuthoringLlmIntentResolverService agenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            ObjectProvider<DomainCatalogPromptContextService> domainCatalogPromptContextService,
            @Value("${praxis.ai.authoring.intent-resolution.fast-timeout-seconds:12}") int fastIntentTimeoutSeconds,
            @Value("${praxis.ai.authoring.intent-resolution.full-timeout-seconds:30}") int fullIntentTimeoutSeconds,
            @Value("${praxis.ai.authoring.intent-resolution.live-option.openai-model:gpt-5.6-luna}")
                    String liveOptionRefinementOpenAiModel) {
        return new AgenticAuthoringLlmIntentResolverService(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService.getIfAvailable(),
                fastIntentTimeoutSeconds,
                fullIntentTimeoutSeconds,
                liveOptionRefinementOpenAiModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringDomainCatalogCandidateEnhancer agenticAuthoringDomainCatalogCandidateEnhancer(
            ObjectProvider<DomainCatalogIngestionService> domainCatalogIngestionService,
            @Value("${praxis.domain-catalog.service-key:praxis-service}") String domainCatalogServiceKey) {
        return new AgenticAuthoringDomainCatalogCandidateEnhancer(
                domainCatalogIngestionService::getIfAvailable,
                domainCatalogServiceKey);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringConsultativeApiCatalogProjectionService agenticAuthoringConsultativeApiCatalogProjectionService(
            ObjectProvider<DomainCatalogIngestionService> domainCatalogIngestionService,
            ObjectProvider<ApiMetadataRepository> apiMetadataRepository,
            ObjectProvider<SchemaRetrievalService> schemaRetrievalService,
            @Value("${praxis.domain-catalog.service-key:praxis-service}") String domainCatalogServiceKey,
            @Value("${praxis.ai.authoring.consultative.api-catalog.compact-cache-ttl-ms:60000}") long compactProjectionCacheTtlMs,
            @Value("${praxis.ai.authoring.consultative.api-catalog.compact-cache-max-entries:256}") int compactProjectionCacheMaxEntries,
            @Value("${praxis.ai.authoring.consultative.api-catalog.api-metadata-cache-ttl-ms:60000}") long apiMetadataCacheTtlMs) {
        return new AgenticAuthoringConsultativeApiCatalogProjectionService(
                domainCatalogIngestionService::getIfAvailable,
                apiMetadataRepository.getIfAvailable(),
                domainCatalogServiceKey,
                compactProjectionCacheTtlMs,
                compactProjectionCacheMaxEntries,
                apiMetadataCacheTtlMs,
                schemaRetrievalService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringIntentResolverService agenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            ObjectProvider<AgenticAuthoringLlmIntentResolverService> llmIntentResolverService,
            ObjectProvider<AgenticAuthoringDomainCatalogCandidateEnhancer> domainCatalogCandidateEnhancer,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            @Value("${praxis.domain-catalog.service-key:praxis-service}") String domainCatalogServiceKey,
            @Value("${praxis.ai.authoring.legacy-keyword-fallback-enabled:false}")
            boolean legacyKeywordFallbackEnabled) {
        return new AgenticAuthoringIntentResolverService(
                objectMapper,
                apiMetadataCandidateCatalog,
                llmIntentResolverService.getIfAvailable(),
                componentCapabilitiesService,
                domainCatalogServiceKey,
                domainCatalogCandidateEnhancer.getIfAvailable(),
                legacyKeywordFallbackEnabled);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringComponentCapabilitiesService agenticAuthoringComponentCapabilitiesService(
            ObjectProvider<AiRegistryRepository> aiRegistryRepository,
            ObjectMapper objectMapper,
            AgenticAuthoringComponentCapabilitiesProperties properties) {
        return new AgenticAuthoringComponentCapabilitiesService(
                aiRegistryRepository.getIfAvailable(),
                objectMapper,
                properties.getCacheTtlMs(),
                properties.getRegistryLoadTimeoutMs(),
                properties.getDegradedRetryMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringComponentCapabilitiesRefreshListener agenticAuthoringComponentCapabilitiesRefreshListener(
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        return new AgenticAuthoringComponentCapabilitiesRefreshListener(componentCapabilitiesService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringManifestContractValidator agenticAuthoringManifestContractValidator() {
        return new AgenticAuthoringManifestContractValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringTargetResolverRegistry agenticAuthoringTargetResolverRegistry() {
        return new AgenticAuthoringTargetResolverRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringValidatorRegistry agenticAuthoringValidatorRegistry(
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry) {
        return new AgenticAuthoringValidatorRegistry(targetResolverRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringEffectCompilerRegistry agenticAuthoringEffectCompilerRegistry(
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry) {
        return new AgenticAuthoringEffectCompilerRegistry(objectMapper, targetResolverRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiRegistryRepository.class)
    public AgenticAuthoringManifestService agenticAuthoringManifestService(
            AiRegistryRepository aiRegistryRepository,
            ObjectMapper objectMapper,
            AgenticAuthoringTargetResolverRegistry targetResolverRegistry,
            AgenticAuthoringValidatorRegistry validatorRegistry,
            AgenticAuthoringEffectCompilerRegistry effectCompilerRegistry,
            AgenticAuthoringManifestContractValidator manifestContractValidator,
            ObjectProvider<AgenticAuthoringPresentationAffordanceCatalogService> presentationAffordanceCatalogService) {
        return new AgenticAuthoringManifestService(
                aiRegistryRepository,
                objectMapper,
                targetResolverRegistry,
                validatorRegistry,
                effectCompilerRegistry,
                manifestContractValidator,
                presentationAffordanceCatalogService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AiProviderManagementService.class, AgenticAuthoringManifestService.class})
    public AgenticAuthoringComponentEditPlanService agenticAuthoringComponentEditPlanService(
            AiProviderManagementService providerManagementService,
            AgenticAuthoringManifestService manifestService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringComponentEditPlanService(
                providerManagementService,
                manifestService,
                objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgenticAuthoringManifestService.class)
    @ConditionalOnProperty(prefix = "praxis.ai.authoring", name = "http-enabled", havingValue = "true")
    public AgenticAuthoringManifestController agenticAuthoringManifestController(
            AgenticAuthoringManifestService manifestService) {
        return new AgenticAuthoringManifestController(manifestService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringResourceDiscoveryService agenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            ObjectProvider<AgenticAuthoringDomainCatalogCandidateEnhancer> domainCatalogCandidateEnhancer,
            ObjectProvider<AgenticAuthoringConsultativeApiCatalogProjectionService> consultativeApiCatalogProjectionService,
            ObjectProvider<ResourceCapabilitiesRetrievalService> resourceCapabilitiesRetrievalService,
            ObjectMapper objectMapper,
            @Value("${praxis.domain-catalog.service-key:praxis-service}") String domainCatalogServiceKey) {
        return new AgenticAuthoringResourceDiscoveryService(
                apiMetadataCandidateCatalog,
                objectMapper,
                domainCatalogServiceKey,
                domainCatalogCandidateEnhancer.getIfAvailable(),
                consultativeApiCatalogProjectionService.getIfAvailable(),
                resourceCapabilitiesRetrievalService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderManagementService.class)
    public AgenticAuthoringConsultativeAnswerService agenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            ObjectProvider<AgenticAuthoringConsultativeApiCatalogProjectionService> consultativeApiCatalogProjectionService,
            ObjectProvider<AgenticAuthoringToolRegistry> toolRegistry,
            @Value("${praxis.ai.authoring.runtime-tool.policy-ref:runtime-tool-policy:single-read-beta}")
            String runtimeToolPolicyRef,
            @Value("${praxis.ai.authoring.runtime-related-surface.intent-policy-ref:runtime-related-surface-intent-policy:llm}")
            String runtimeRelatedSurfaceIntentPolicyRef,
            @Value("${praxis.ai.authoring.runtime-related-surface.temporal-comparison-field-ref:ocorridoEm}")
            String temporalComparisonFieldRef) {
        return new AgenticAuthoringConsultativeAnswerService(
                providerManagementService,
                objectMapper,
                consultativeApiCatalogProjectionService.getIfAvailable(),
                toolRegistry.getIfAvailable(),
                runtimeToolPolicyRef,
                runtimeRelatedSurfaceIntentPolicyRef,
                temporalComparisonFieldRef);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringPresentationAffordanceCatalogService agenticAuthoringPresentationAffordanceCatalogService(
            ObjectMapper objectMapper,
            ObjectProvider<AiRegistryRepository> aiRegistryRepository) {
        return AgenticAuthoringPresentationAffordanceCatalogService.defaultService(
                objectMapper,
                aiRegistryRepository.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringResourceBackedPresentationAffordanceProvider agenticAuthoringPresentationAffordanceProvider(
            ObjectMapper objectMapper,
            AgenticAuthoringPresentationAffordanceCatalogService catalogService) {
        return new AgenticAuthoringResourceBackedPresentationAffordanceProvider(objectMapper, catalogService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringPresentationAffordanceDiscoveryService agenticAuthoringPresentationAffordanceDiscoveryService(
            ObjectProvider<AgenticAuthoringPresentationAffordanceProvider> providers) {
        List<AgenticAuthoringPresentationAffordanceProvider> availableProviders = providers.orderedStream().toList();
        return new AgenticAuthoringPresentationAffordanceDiscoveryService(availableProviders);
    }

    @Bean
    @ConditionalOnMissingBean
    public org.praxisplatform.config.ai.authoring.AgenticAuthoringDomainBindingService
            agenticAuthoringDomainBindingService(
                    ObjectProvider<org.praxisplatform.config.repository.DomainKnowledgeBindingRepository>
                            bindingRepositoryProvider,
                    ObjectProvider<org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository>
                            evidenceRepositoryProvider) {
        var bindingRepository = bindingRepositoryProvider.getIfAvailable();
        var evidenceRepository = evidenceRepositoryProvider.getIfAvailable();
        if (bindingRepository == null || evidenceRepository == null) {
            return null;
        }
        return new org.praxisplatform.config.ai.authoring.AgenticAuthoringDomainBindingService(
                bindingRepository, evidenceRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SchemaRetrievalService.class)
    public LiveOptionValueRetrievalService liveOptionValueRetrievalService(
            ObjectMapper objectMapper,
            SchemaRetrievalService schemaRetrievalService,
            ObjectProvider<GovernedPlatformRequestAuthorizationProvider> authorizationProviders) {
        return new LiveOptionValueRetrievalService(
                objectMapper,
                schemaRetrievalService,
                authorizationProviders.getIfAvailable(
                        GovernedPlatformRequestAuthorizationProvider::none));
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringToolRegistry agenticAuthoringToolRegistry(
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService,
            ObjectProvider<ContextRetrievalService> contextRetrievalService,
            ObjectProvider<AgenticAuthoringManifestService> manifestService,
            ObjectProvider<SchemaRetrievalService> schemaRetrievalService,
            ObjectProvider<AgenticAuthoringPresentationAffordanceDiscoveryService> presentationAffordanceDiscoveryService,
            ObjectProvider<org.praxisplatform.config.ai.authoring.AgenticAuthoringProjectKnowledgeService> projectKnowledgeService,
            ObjectProvider<org.praxisplatform.config.ai.authoring.AgenticAuthoringDomainBindingService> domainBindingService,
            ObjectProvider<org.praxisplatform.config.ai.authoring.AgenticAuthoringOperationalBindingVerificationService> operationalVerificationService,
            ObjectProvider<DomainCatalogIngestionService> domainCatalogIngestionService,
            ObjectProvider<LiveOptionValueRetrievalService> liveOptionValueRetrievalService,
            @Value("${praxis.domain-catalog.service-key:praxis-service}") String domainCatalogServiceKey,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringToolRegistry(
                resourceDiscoveryService,
                contextRetrievalService.getIfAvailable(),
                manifestService.getIfAvailable(),
                schemaRetrievalService.getIfAvailable(),
                objectMapper,
                presentationAffordanceDiscoveryService.getIfAvailable(),
                projectKnowledgeService.getIfAvailable(),
                domainBindingService.getIfAvailable(),
                operationalVerificationService.getIfAvailable(),
                domainCatalogIngestionService.getIfAvailable(),
                domainCatalogServiceKey,
                liveOptionValueRetrievalService.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringToolLoopPlanner agenticAuthoringToolLoopPlanner() {
        return new AgenticAuthoringDefaultToolLoopPlanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringToolLoopExecutor agenticAuthoringToolLoopExecutor(
            AgenticAuthoringToolRegistry toolRegistry,
            AgenticAuthoringToolLoopPlanner planner) {
        return new AgenticAuthoringToolLoopExecutor(toolRegistry, planner);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringOrchestrator agenticAuthoringOrchestrator(
            AgenticAuthoringToolLoopExecutor toolLoopExecutor) {
        return new AgenticAuthoringOrchestrator(toolLoopExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringPatchCompilerService agenticAuthoringPatchCompilerService(
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringPatchCompilerService(properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderManagementService.class)
    public AgenticAuthoringPlanService agenticAuthoringPlanService(
            AiProviderManagementService providerManagementService,
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringPlanService(providerManagementService, properties, objectMapper);
    }

    @Bean
    @Order(1000)
    @ConditionalOnMissingBean(name = "agenticAuthoringGenericUiCompositionPlanProvider")
    public AgenticAuthoringUiCompositionPlanProvider agenticAuthoringGenericUiCompositionPlanProvider(
            ObjectMapper objectMapper) {
        return new AgenticAuthoringGenericUiCompositionPlanProvider(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderManagementService.class)
    public AgenticAuthoringPreviewMessageSynthesizerService agenticAuthoringPreviewMessageSynthesizerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringPreviewMessageSynthesizerService(providerManagementService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderManagementService.class)
    public AgenticAuthoringPreIntentToolPlanningService agenticAuthoringPreIntentToolPlanningService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            ObjectProvider<DomainCatalogPromptContextService> domainCatalogPromptContextService,
            @Value("${praxis.ai.authoring.pre-intent.openai-model:gpt-5.6-luna}")
            String openAiPlanningModel) {
        return new AgenticAuthoringLlmPreIntentToolPlanningService(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService.getIfAvailable(),
                openAiPlanningModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService(
            ObjectMapper objectMapper,
            ObjectProvider<GovernedPlatformRequestAuthorizationProvider> authorizationProviders,
            @Value("${praxis.ai.capabilities.base-url:}") String capabilitiesBaseUrl,
            @Value("${praxis.ai.capabilities.timeout-ms:15000}") long capabilitiesTimeoutMs) {
        return new ResourceCapabilitiesRetrievalService(
                objectMapper,
                capabilitiesBaseUrl,
                capabilitiesTimeoutMs,
                authorizationProviders.getIfAvailable(
                        GovernedPlatformRequestAuthorizationProvider::none));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            org.praxisplatform.config.ai.authoring.AgenticAuthoringDomainBindingService.class,
            SchemaRetrievalService.class,
            ResourceCapabilitiesRetrievalService.class
    })
    public org.praxisplatform.config.ai.authoring.AgenticAuthoringOperationalBindingVerificationService
            agenticAuthoringOperationalBindingVerificationService(
                    org.praxisplatform.config.ai.authoring.AgenticAuthoringDomainBindingService bindingService,
                    SchemaRetrievalService schemaRetrievalService,
                    ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService) {
        return new org.praxisplatform.config.ai.authoring.AgenticAuthoringOperationalBindingVerificationService(
                bindingService,
                schemaRetrievalService,
                resourceCapabilitiesRetrievalService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceSurfaceCatalogRetrievalService resourceSurfaceCatalogRetrievalService(
            ObjectMapper objectMapper,
            ObjectProvider<GovernedPlatformRequestAuthorizationProvider> authorizationProviders,
            @Value("${praxis.ai.capabilities.base-url:}") String metadataBaseUrl,
            @Value("${praxis.ai.capabilities.timeout-ms:15000}") long metadataTimeoutMs) {
        return new ResourceSurfaceCatalogRetrievalService(
                objectMapper,
                metadataBaseUrl,
                metadataTimeoutMs,
                authorizationProviders.getIfAvailable(
                        GovernedPlatformRequestAuthorizationProvider::none));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiRegistryTemplateService.class)
    public AgenticAuthoringUiCompositionTemplateResolver agenticAuthoringUiCompositionTemplateResolver(
            AiRegistryTemplateService templateService) {
        return new AgenticAuthoringUiCompositionTemplateResolver(templateService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AgenticAuthoringPlanService.class, AgenticAuthoringPatchCompilerService.class})
    public AgenticAuthoringPreviewService agenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            ObjectProvider<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders,
            ObjectProvider<AgenticAuthoringPreviewMessageSynthesizerService> messageSynthesizer,
            ObjectProvider<SchemaRetrievalService> schemaRetrievalService,
            ObjectProvider<ResourceCapabilitiesRetrievalService> resourceCapabilitiesRetrievalService,
            ObjectProvider<ResourceSurfaceCatalogRetrievalService> resourceSurfaceCatalogRetrievalService,
            ObjectProvider<AgenticAuthoringComponentEditPlanService> componentEditPlanService,
            ObjectProvider<AgenticAuthoringUiCompositionTemplateResolver> uiCompositionTemplateResolver) {
        return new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders.orderedStream().toList(),
                messageSynthesizer.getIfAvailable(),
                schemaRetrievalService.getIfAvailable(),
                resourceCapabilitiesRetrievalService.getIfAvailable(),
                resourceSurfaceCatalogRetrievalService.getIfAvailable(),
                componentEditPlanService.getIfAvailable(),
                uiCompositionTemplateResolver.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({UserConfigService.class, AiTurnEventService.class})
    public AgenticAuthoringApplyService agenticAuthoringApplyService(
            UserConfigService userConfigService,
            AiApiKeyProtectionService apiKeyProtectionService,
            AiTurnEventService turnEventService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringApplyService(
                userConfigService,
                apiKeyProtectionService,
                turnEventService,
                objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringDryRunReportService agenticAuthoringDryRunReportService(
            AgenticAuthoringDryRunService dryRunService,
            AgenticAuthoringArtifactSource artifactSource,
            AgenticAuthoringArtifactProperties properties,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringDryRunReportService(dryRunService, artifactSource, properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiTurnEventService.class)
    @ConditionalOnClass(AiTurnEventService.class)
    public AgenticAuthoringReplayAuditService agenticAuthoringReplayAuditService(
            AiTurnEventService turnEventService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringReplayAuditService(turnEventService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            AgenticAuthoringIntentResolverService.class,
            AgenticAuthoringPreviewService.class
    })
    public AgenticAuthoringTurnEngine agenticAuthoringTurnEngine(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            AgenticAuthoringToolRegistry toolRegistry,
            ObjectProvider<AgenticAuthoringOrchestrator> orchestrator,
            ObjectProvider<AgenticAuthoringProjectKnowledgeService> projectKnowledgeService,
            ObjectProvider<SchemaRetrievalService> schemaRetrievalService,
            ObjectProvider<AgenticAuthoringConsultativeAnswerService> consultativeAnswerService,
            ObjectProvider<AgenticAuthoringPreIntentToolPlanningService> preIntentToolPlanningService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService,
            AgenticAuthoringComponentCapabilitiesProperties componentCapabilitiesProperties,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringTurnEngine(
                intentResolverService,
                previewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                toolRegistry,
                projectKnowledgeService.getIfAvailable(),
                orchestrator.getIfAvailable(),
                schemaRetrievalService.getIfAvailable(),
                componentCapabilitiesService,
                consultativeAnswerService.getIfAvailable(),
                preIntentToolPlanningService.getIfAvailable(),
                componentCapabilitiesProperties.effectivePreloadTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            AgenticAuthoringTurnEngine.class,
            AiThreadService.class,
            AiTurnService.class,
            AiTurnEventService.class,
            AiStreamAccessTokenService.class
    })
    public AgenticAuthoringTurnStreamService agenticAuthoringTurnStreamService(
            AgenticAuthoringTurnEngine turnEngine,
            AiThreadService threadService,
            AiTurnService turnService,
            AiTurnEventService turnEventService,
            AiStreamAccessTokenService streamAccessTokenService) {
        return new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "praxis.ai.authoring",
            name = "dry-run-enabled",
            havingValue = "true"
    )
    public ApplicationRunner agenticAuthoringDryRunRunner(
            AgenticAuthoringDryRunReportService reportService) {
        return args -> reportService.runAndWriteReport();
    }
}
