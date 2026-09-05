package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentCapabilitiesProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringComponentEditPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringConsultativeAnswerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunReportService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringGenericUiCompositionPlanProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringEffectCompilerRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPersistedUiCompositionSourceResolver;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReplayAuditService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTargetResolverRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringUiCompositionPlanProvider;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringUiCompositionTemplateResolver;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringValidatorRegistry;
import org.praxisplatform.config.controller.DomainRuleCatalogController;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleEventRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.DomainRuleDefinitionFingerprint;
import org.praxisplatform.config.service.DomainRuleExplanationProjectionService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class AgenticAuthoringAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DomainRuleExplanationAutoConfiguration.class,
                    AgenticAuthoringAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void shouldRegisterInternalAuthoringBeansWithoutStartupRunnerByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgenticAuthoringArtifactProperties.class);
            assertThat(context.getBean(AgenticAuthoringArtifactProperties.class).isHttpEnabled()).isFalse();
            assertThat(context).hasSingleBean(AgenticAuthoringArtifactSource.class);
            assertThat(context).hasSingleBean(AgenticAuthoringDryRunService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringIntentResolverService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringComponentCapabilitiesService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringPatchCompilerService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringUiCompositionPlanProvider.class);
            assertThat(context).hasSingleBean(AgenticAuthoringGenericUiCompositionPlanProvider.class);
            assertThat(context).hasSingleBean(AgenticAuthoringDryRunReportService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringManifestContractValidator.class);
            assertThat(context).hasSingleBean(AgenticAuthoringTargetResolverRegistry.class);
            assertThat(context).hasSingleBean(AgenticAuthoringValidatorRegistry.class);
            assertThat(context).hasSingleBean(AgenticAuthoringEffectCompilerRegistry.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringManifestService.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringPlanService.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringPreviewService.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringReplayAuditService.class);
            assertThat(context).doesNotHaveBean(ApplicationRunner.class);
        });
    }

    @Test
    void shouldRegisterDomainDecisionExplanationProjectionWhenRuleControlPlaneIsAvailable() {
        DomainRuleService domainRuleService = org.mockito.Mockito.mock(DomainRuleService.class);
        DomainRuleDefinitionFingerprint fingerprint =
                org.mockito.Mockito.mock(DomainRuleDefinitionFingerprint.class);
        DomainRuleGovernancePrincipalResolver principalResolver =
                org.mockito.Mockito.mock(DomainRuleGovernancePrincipalResolver.class);

        contextRunner
                .withBean(DomainRuleDefinitionRepository.class,
                        () -> org.mockito.Mockito.mock(DomainRuleDefinitionRepository.class))
                .withBean(DomainRuleMaterializationRepository.class,
                        () -> org.mockito.Mockito.mock(DomainRuleMaterializationRepository.class))
                .withBean(DomainRuleEventRepository.class,
                        () -> org.mockito.Mockito.mock(DomainRuleEventRepository.class))
                .withBean(DomainRuleService.class, () -> domainRuleService)
                .withBean(DomainRuleDefinitionFingerprint.class, () -> fingerprint)
                .withBean(DomainRuleGovernancePrincipalResolver.class, () -> principalResolver)
                .run(context -> {
                    assertThat(context).hasSingleBean(DomainRuleService.class);
                    assertThat(context).hasSingleBean(DomainRuleDefinitionFingerprint.class);
                    assertThat(context).hasSingleBean(DomainRuleExplanationProjectionService.class);
                    assertThat(context).hasSingleBean(DomainRuleCatalogController.class);
                });
    }

    @Test
    void shouldRegisterExactUiCompositionTemplateResolverWhenRegistryTemplatesAreAvailable() {
        AiRegistryTemplateService templateService = org.mockito.Mockito.mock(AiRegistryTemplateService.class);

        contextRunner
                .withBean(AiRegistryTemplateService.class, () -> templateService)
                .run(context -> assertThat(context)
                        .hasSingleBean(AgenticAuthoringUiCompositionTemplateResolver.class));
    }

    @Test
    void shouldUseDocumentedDefaultComponentCapabilitiesBudgets() {
        contextRunner.run(context -> {
            AgenticAuthoringComponentCapabilitiesService service =
                    context.getBean(AgenticAuthoringComponentCapabilitiesService.class);

            assertThat(ReflectionTestUtils.getField(service, "cacheTtlMs"))
                    .isEqualTo(600_000L);
            assertThat(ReflectionTestUtils.getField(service, "registryLoadTimeoutMs"))
                    .isEqualTo(30_000L);
            assertThat(ReflectionTestUtils.getField(service, "degradedRetryMs"))
                    .isEqualTo(5_000L);
            assertThat(context.getBean(AgenticAuthoringComponentCapabilitiesProperties.class)
                    .effectivePreloadTimeoutMs()).isEqualTo(35_000L);
        });
    }

    @Test
    void shouldAllowComponentCapabilitiesCacheTtlOverride() {
        contextRunner
                .withPropertyValues(
                        "praxis.ai.authoring.component-capabilities.cache-ttl-ms=1234",
                        "praxis.ai.authoring.component-capabilities.registry-load-timeout-ms=4321",
                        "praxis.ai.authoring.component-capabilities.degraded-retry-ms=321",
                        "praxis.ai.authoring.component-capabilities.preload-timeout-ms=1000")
                .run(context -> {
                    AgenticAuthoringComponentCapabilitiesService service =
                            context.getBean(AgenticAuthoringComponentCapabilitiesService.class);

                    assertThat(ReflectionTestUtils.getField(service, "cacheTtlMs"))
                            .isEqualTo(1_234L);
                    assertThat(ReflectionTestUtils.getField(service, "registryLoadTimeoutMs"))
                            .isEqualTo(4_321L);
                    assertThat(ReflectionTestUtils.getField(service, "degradedRetryMs"))
                            .isEqualTo(321L);
                    assertThat(context.getBean(AgenticAuthoringComponentCapabilitiesProperties.class)
                            .effectivePreloadTimeoutMs()).isEqualTo(5_321L);
                });
    }

    @Test
    void shouldNotRegisterReferenceUiCompositionPlanProviderFromCanonicalStarter() {
        contextRunner
                .withPropertyValues("praxis.ai.authoring.reference-ui-composition-provider-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticAuthoringArtifactProperties.class);
                    assertThat(context.getBeansOfType(AgenticAuthoringUiCompositionPlanProvider.class)).hasSize(1);
                    assertThat(context).hasSingleBean(AgenticAuthoringGenericUiCompositionPlanProvider.class);
                    assertThat(context).doesNotHaveBean("agenticAuthoringReferenceUiCompositionPlanProvider");
                });
    }

    @Test
    void shouldRegisterManifestServiceWithInjectedAuthoringRegistriesWhenRepositoryExists() {
        AiRegistryRepository aiRegistryRepository = org.mockito.Mockito.mock(AiRegistryRepository.class);
        contextRunner
                .withBean(AiRegistryRepository.class, () -> aiRegistryRepository)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticAuthoringTargetResolverRegistry.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringValidatorRegistry.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringEffectCompilerRegistry.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringManifestContractValidator.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringManifestService.class);
                });
    }

    @Test
    void shouldRespectUserProvidedAuthoringRegistryBeans() {
        AgenticAuthoringTargetResolverRegistry customTargetResolver = new AgenticAuthoringTargetResolverRegistry();
        AgenticAuthoringValidatorRegistry customValidator = new AgenticAuthoringValidatorRegistry(customTargetResolver);
        AgenticAuthoringEffectCompilerRegistry customCompiler =
                new AgenticAuthoringEffectCompilerRegistry(new ObjectMapper(), customTargetResolver);
        AgenticAuthoringManifestContractValidator customContractValidator =
                new AgenticAuthoringManifestContractValidator();
        contextRunner
                .withBean(AgenticAuthoringTargetResolverRegistry.class, () -> customTargetResolver)
                .withBean(AgenticAuthoringValidatorRegistry.class, () -> customValidator)
                .withBean(AgenticAuthoringEffectCompilerRegistry.class, () -> customCompiler)
                .withBean(AgenticAuthoringManifestContractValidator.class, () -> customContractValidator)
                .run(context -> {
                    assertThat(context.getBean(AgenticAuthoringTargetResolverRegistry.class)).isSameAs(customTargetResolver);
                    assertThat(context.getBean(AgenticAuthoringValidatorRegistry.class)).isSameAs(customValidator);
                    assertThat(context.getBean(AgenticAuthoringEffectCompilerRegistry.class)).isSameAs(customCompiler);
                    assertThat(context.getBean(AgenticAuthoringManifestContractValidator.class)).isSameAs(customContractValidator);
                });
    }

    @Test
    void shouldRegisterDryRunRunnerOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "praxis.ai.authoring.dry-run-enabled=true",
                        "praxis.ai.authoring.artifacts-dir=D:/Developer/praxis-plataform/docs/ai/agentic-authoring/proofs",
                        "praxis.ai.authoring.report-path=build/agentic-authoring/dry-run-report.json"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                    AgenticAuthoringArtifactProperties properties = context.getBean(AgenticAuthoringArtifactProperties.class);
                    assertThat(properties.isDryRunEnabled()).isTrue();
                    assertThat(properties.isHttpEnabled()).isFalse();
                    assertThat(properties.getArtifactsDir()).isNotNull();
                    assertThat(properties.getReportPath()).isNotNull();
                });
    }

    @Test
    void shouldBindHttpEndpointFlagWithoutEnablingStartupRunner() {
        contextRunner
                .withPropertyValues("praxis.ai.authoring.http-enabled=true")
                .run(context -> {
                    AgenticAuthoringArtifactProperties properties = context.getBean(AgenticAuthoringArtifactProperties.class);
                    assertThat(properties.isHttpEnabled()).isTrue();
                    assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                });
    }

    @Test
    void shouldRespectUserProvidedDryRunService() {
        AgenticAuthoringDryRunService customService = new AgenticAuthoringDryRunService(new ObjectMapper());
        contextRunner
                .withBean(AgenticAuthoringDryRunService.class, () -> customService)
                .run(context -> assertThat(context.getBean(AgenticAuthoringDryRunService.class)).isSameAs(customService));
    }

    @Test
    void shouldRegisterReplayAuditServiceWhenTurnEventServiceExists() {
        AiTurnEventService turnEventService = org.mockito.Mockito.mock(AiTurnEventService.class);
        contextRunner
                .withBean(AiTurnEventService.class, () -> turnEventService)
                .run(context -> assertThat(context).hasSingleBean(AgenticAuthoringReplayAuditService.class));
    }

    @Test
    void shouldRegisterPlanServiceWhenProviderManagementExists() {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticAuthoringPlanService.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringPreviewService.class);
                    assertThat(context).doesNotHaveBean(AgenticAuthoringComponentEditPlanService.class);
                });
    }

    @Test
    void shouldRegisterComponentEditPlannerWhenProviderAndManifestRegistryExist() {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        AiRegistryRepository aiRegistryRepository = org.mockito.Mockito.mock(AiRegistryRepository.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .withBean(AiRegistryRepository.class, () -> aiRegistryRepository)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticAuthoringManifestService.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringComponentEditPlanService.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringPreviewService.class);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(AgenticAuthoringPreviewService.class),
                            "componentEditPlanService"))
                            .isSameAs(context.getBean(AgenticAuthoringComponentEditPlanService.class));
                });
    }

    @Test
    void shouldBindRuntimeToolPlannerPolicyFromBackendOwnedProperty() throws Exception {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .withPropertyValues(
                        "praxis.ai.authoring.runtime-tool.policy-ref=runtime-tool-policy:multi-tool-dry-run-beta")
                .run(context -> {
                    AgenticAuthoringConsultativeAnswerService service =
                            context.getBean(AgenticAuthoringConsultativeAnswerService.class);
                    assertThat(runtimeToolPlannerPolicyRef(service))
                            .isEqualTo("runtime-tool-policy:multi-tool-dry-run-beta");
                });
    }

    @Test
    void shouldBindReadonlyRuntimeToolPlannerPolicyFromBackendOwnedProperty() throws Exception {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .withPropertyValues(
                        "praxis.ai.authoring.runtime-tool.policy-ref=runtime-tool-policy:multi-tool-readonly-beta")
                .run(context -> {
                    AgenticAuthoringConsultativeAnswerService service =
                            context.getBean(AgenticAuthoringConsultativeAnswerService.class);
                    assertThat(runtimeToolPlannerPolicyRef(service))
                            .isEqualTo("runtime-tool-policy:multi-tool-readonly-beta");
                });
    }

    @Test
    void shouldFailClosedRuntimeToolPlannerPolicyForUnknownBackendValue() throws Exception {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .withPropertyValues("praxis.ai.authoring.runtime-tool.policy-ref=frontend-says-multi-tool")
                .run(context -> {
                    AgenticAuthoringConsultativeAnswerService service =
                            context.getBean(AgenticAuthoringConsultativeAnswerService.class);
                    assertThat(runtimeToolPlannerPolicyRef(service))
                            .isEqualTo("runtime-tool-policy:single-read-beta");
                });
    }

    @Test
    void shouldBindRuntimeRelatedSurfaceIntentPolicyFromBackendOwnedProperty() throws Exception {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .withPropertyValues(
                        "praxis.ai.authoring.runtime-related-surface.intent-policy-ref=runtime-related-surface-intent-policy:temporal-compare-smoke",
                        "praxis.ai.authoring.runtime-related-surface.temporal-comparison-field-ref=ocorridoEm")
                .run(context -> {
                    AgenticAuthoringConsultativeAnswerService service =
                            context.getBean(AgenticAuthoringConsultativeAnswerService.class);
                    assertThat(runtimeRelatedSurfaceIntentPolicyRef(service))
                            .isEqualTo("runtime-related-surface-intent-policy:temporal-compare-smoke");
                    assertThat(runtimeRelatedSurfaceTemporalComparisonFieldRef(service))
                            .isEqualTo("ocorridoEm");
                });
    }

    @Test
    void shouldFailClosedRuntimeRelatedSurfaceIntentPolicyForUnknownBackendValue() throws Exception {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .withPropertyValues(
                        "praxis.ai.authoring.runtime-related-surface.intent-policy-ref=frontend-says-compare",
                        "praxis.ai.authoring.runtime-related-surface.temporal-comparison-field-ref=ocorridoEm")
                .run(context -> {
                    AgenticAuthoringConsultativeAnswerService service =
                            context.getBean(AgenticAuthoringConsultativeAnswerService.class);
                    assertThat(runtimeRelatedSurfaceIntentPolicyRef(service))
                            .isEqualTo("runtime-related-surface-intent-policy:llm");
                    assertThat(runtimeRelatedSurfaceTemporalComparisonFieldRef(service))
                            .isEmpty();
                });
    }

    @Test
    void shouldRegisterApplyServiceWhenPersistenceAndTurnEventServicesExist() {
        UserConfigService userConfigService = org.mockito.Mockito.mock(UserConfigService.class);
        AiApiKeyProtectionService apiKeyProtectionService = org.mockito.Mockito.mock(AiApiKeyProtectionService.class);
        AiTurnEventService turnEventService = org.mockito.Mockito.mock(AiTurnEventService.class);
        contextRunner
                .withBean(UserConfigService.class, () -> userConfigService)
                .withBean(AiApiKeyProtectionService.class, () -> apiKeyProtectionService)
                .withBean(AiTurnEventService.class, () -> turnEventService)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticAuthoringApplyService.class);
                    assertThat(context)
                            .hasSingleBean(AgenticAuthoringPersistedUiCompositionSourceResolver.class);
                });
    }

    private String runtimeToolPlannerPolicyRef(AgenticAuthoringConsultativeAnswerService service) {
        try {
            java.lang.reflect.Field field = AgenticAuthoringConsultativeAnswerService.class
                    .getDeclaredField("runtimeToolPlannerPolicy");
            field.setAccessible(true);
            Object policy = field.get(service);
            java.lang.reflect.Method policyRef = policy.getClass().getDeclaredMethod("policyRef");
            policyRef.setAccessible(true);
            return String.valueOf(policyRef.invoke(policy));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Could not inspect runtime tool planner policy", ex);
        }
    }

    private String runtimeRelatedSurfaceIntentPolicyRef(AgenticAuthoringConsultativeAnswerService service) {
        return runtimeRelatedSurfaceIntentPolicyValue(service, "policyRef");
    }

    private String runtimeRelatedSurfaceTemporalComparisonFieldRef(AgenticAuthoringConsultativeAnswerService service) {
        return runtimeRelatedSurfaceIntentPolicyValue(service, "temporalComparisonFieldRef");
    }

    private String runtimeRelatedSurfaceIntentPolicyValue(
            AgenticAuthoringConsultativeAnswerService service,
            String methodName) {
        try {
            java.lang.reflect.Field field = AgenticAuthoringConsultativeAnswerService.class
                    .getDeclaredField("runtimeRelatedSurfaceIntentPolicy");
            field.setAccessible(true);
            Object policy = field.get(service);
            java.lang.reflect.Method accessor = policy.getClass().getDeclaredMethod(methodName);
            accessor.setAccessible(true);
            return String.valueOf(accessor.invoke(policy));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Could not inspect runtime related surface intent policy", ex);
        }
    }
}
