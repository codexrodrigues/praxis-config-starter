package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApiCatalogConversationService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunReportService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApiMetadataCandidateCatalog;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringLlmIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReferenceUiCompositionPlanProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResourceDiscoveryService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReplayAuditService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringEffectCompilerRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTargetResolverRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringUiCompositionPlanProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringValidatorRegistry;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(AgenticAuthoringDryRunService.class)
@EnableConfigurationProperties(AgenticAuthoringArtifactProperties.class)
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
    public AgenticAuthoringApiCatalogConversationService agenticAuthoringApiCatalogConversationService(
            ObjectMapper objectMapper,
            ObjectProvider<ApiMetadataRepository> apiMetadataRepository) {
        return new AgenticAuthoringApiCatalogConversationService(objectMapper, apiMetadataRepository.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderManagementService.class)
    public AgenticAuthoringLlmIntentResolverService agenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringLlmIntentResolverService(providerManagementService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringIntentResolverService agenticAuthoringIntentResolverService(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringApiCatalogConversationService apiCatalogConversationService,
            ObjectProvider<AgenticAuthoringLlmIntentResolverService> llmIntentResolverService,
            AgenticAuthoringComponentCapabilitiesService componentCapabilitiesService) {
        return new AgenticAuthoringIntentResolverService(
                objectMapper,
                apiMetadataCandidateCatalog,
                apiCatalogConversationService,
                llmIntentResolverService.getIfAvailable(),
                componentCapabilitiesService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringComponentCapabilitiesService agenticAuthoringComponentCapabilitiesService() {
        return new AgenticAuthoringComponentCapabilitiesService();
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
            AgenticAuthoringManifestContractValidator manifestContractValidator) {
        return new AgenticAuthoringManifestService(
                aiRegistryRepository,
                objectMapper,
                targetResolverRegistry,
                validatorRegistry,
                effectCompilerRegistry,
                manifestContractValidator);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgenticAuthoringResourceDiscoveryService agenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog) {
        return new AgenticAuthoringResourceDiscoveryService(apiMetadataCandidateCatalog);
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
    @ConditionalOnMissingBean(name = "agenticAuthoringReferenceUiCompositionPlanProvider")
    public AgenticAuthoringUiCompositionPlanProvider agenticAuthoringReferenceUiCompositionPlanProvider(
            ObjectMapper objectMapper) {
        return new AgenticAuthoringReferenceUiCompositionPlanProvider(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AgenticAuthoringPlanService.class, AgenticAuthoringPatchCompilerService.class})
    public AgenticAuthoringPreviewService agenticAuthoringPreviewService(
            AgenticAuthoringPlanService planService,
            AgenticAuthoringPatchCompilerService patchCompilerService,
            ObjectMapper objectMapper,
            ObjectProvider<AgenticAuthoringUiCompositionPlanProvider> uiCompositionPlanProviders) {
        return new AgenticAuthoringPreviewService(
                planService,
                patchCompilerService,
                objectMapper,
                uiCompositionPlanProviders.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(UserConfigService.class)
    public AgenticAuthoringApplyService agenticAuthoringApplyService(
            UserConfigService userConfigService,
            AiApiKeyProtectionService apiKeyProtectionService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringApplyService(userConfigService, apiKeyProtectionService, objectMapper);
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
            AgenticAuthoringPreviewService.class,
            AiThreadService.class,
            AiTurnService.class,
            AiTurnEventService.class,
            AiStreamAccessTokenService.class
    })
    public AgenticAuthoringTurnStreamService agenticAuthoringTurnStreamService(
            AgenticAuthoringIntentResolverService intentResolverService,
            AgenticAuthoringPreviewService previewService,
            AiThreadService threadService,
            AiTurnService turnService,
            AiTurnEventService turnEventService,
            AiStreamAccessTokenService streamAccessTokenService,
            ObjectMapper objectMapper) {
        return new AgenticAuthoringTurnStreamService(
                intentResolverService,
                previewService,
                threadService,
                turnService,
                turnEventService,
                streamAccessTokenService,
                objectMapper);
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
