package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.EffectiveUiLayoutController;
import org.praxisplatform.config.controller.EffectiveUiLayoutCompositionController;
import org.praxisplatform.config.controller.UiLayoutResolutionExceptionHandler;
import org.praxisplatform.config.controller.UiLayoutLifecycleController;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseApprovalRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseEventRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseReviewRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.EffectiveUiAudience;
import org.praxisplatform.config.service.EffectiveUiAudienceProvider;
import org.praxisplatform.config.service.EffectiveUiLayoutResolver;
import org.praxisplatform.config.service.UiLayoutCandidateSource;
import org.praxisplatform.config.service.UiLayoutCompositionCandidateSnapshotSource;
import org.praxisplatform.config.service.UiLayoutCompositionReadService;
import org.praxisplatform.config.service.UiLayoutCompositionRegistry;
import org.praxisplatform.config.service.UiLayoutCompositionReleaseReader;
import org.praxisplatform.config.service.UiLayoutLifecycleAdmission;
import org.praxisplatform.config.service.UiLayoutHistoricalEvidenceService;
import org.praxisplatform.config.service.UiLayoutHistoricalEvidenceAccess;
import org.praxisplatform.config.service.UiLayoutEvolutionReadinessService;
import org.praxisplatform.config.service.UiLayoutResolutionCandidate;
import org.praxisplatform.config.service.UiLayoutResolutionService;
import org.praxisplatform.config.service.UiLayoutLifecycleCommandService;
import org.praxisplatform.config.service.UiLayoutLifecycleInvocationProvider;
import org.praxisplatform.config.service.UiLayoutLifecycleReadService;
import org.praxisplatform.config.service.UiLayoutLifecycleRequestDecoder;
import org.praxisplatform.config.service.UiLayoutLifecycleStructureValidator;
import org.praxisplatform.config.service.UiLayoutReleaseValidator;
import org.praxisplatform.config.service.UiLayoutRevisionAllocationLock;
import org.praxisplatform.config.service.UiLayoutDraftCreationLock;
import org.praxisplatform.config.service.UiLayoutDraftWorkspaceSource;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataAdmission;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

@Tag("unit")
class UiLayoutResolutionAutoConfigurationTest {
    @Test void revisionBodyAdviceIsRegisteredWithLifecycleCommands() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(UiLayoutLifecycleCommandService.class, () -> mock(UiLayoutLifecycleCommandService.class))
                .withBean(UiLayoutLifecycleReadService.class, () -> mock(UiLayoutLifecycleReadService.class))
                .withConfiguration(AutoConfigurations.of(UiLayoutResolutionAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UiLayoutLifecycleController.class);
                    assertThat(context).hasSingleBean(org.praxisplatform.config.controller.UiLayoutRevisionRequestBodyAdvice.class);
                });
    }

    @Test void revisionBodyAdviceDoesNotActivateWithoutLifecycleCommands() {
        new ApplicationContextRunner().withBean(ObjectMapper.class, ObjectMapper::new)
                .withConfiguration(AutoConfigurations.of(UiLayoutResolutionAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(org.praxisplatform.config.controller.UiLayoutRevisionRequestBodyAdvice.class);
                });
    }

    private ApplicationContextRunner historicalHost() {
        return historicalHost(true);
    }

    private ApplicationContextRunner historicalHost(boolean budget) {
        var runner = new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withConfiguration(AutoConfigurations.of(UiLayoutResolutionAutoConfiguration.class))
                .withBean(UiLayoutLifecycleInvocationProvider.class, () -> (principal, version, root) -> null)
                .withBean(UiLayoutLifecycleAdmission.class, UiLayoutLifecycleAdmission::denyAll)
                .withBean(UiLayoutReleaseRepository.class, () -> mock(UiLayoutReleaseRepository.class))
                .withBean(UiLayoutDraftRepository.class, () -> mock(UiLayoutDraftRepository.class))
                .withBean(UiLayoutReleaseMemberRepository.class, () -> mock(UiLayoutReleaseMemberRepository.class))
                .withBean(UiLayoutDefinitionRepository.class, () -> mock(UiLayoutDefinitionRepository.class))
                .withBean(UiLayoutRevisionRepository.class, () -> mock(UiLayoutRevisionRepository.class))
                .withBean(UiLayoutAssignmentRevisionRepository.class, () -> mock(UiLayoutAssignmentRevisionRepository.class));
        return budget ? runner.withBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class,
                () -> (op, inv) -> java.time.Duration.ofMinutes(1)) : runner;
    }

    @Test void defaultBudgetPolicyProvidesOneMinuteDefaultAndCanBeOverridden() {
        historicalHost(false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class);
            var policy = context.getBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class);
            assertThat(policy.budget(null, null)).isEqualTo(java.time.Duration.ofMinutes(1));
        });

        var custom = (org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy) (op, inv) -> java.time.Duration.ofSeconds(15);
        historicalHost(false).withBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class);
                    assertThat(context.getBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class)).isSameAs(custom);
                });
    }

    @Test
    void historicalServiceRequiresExplicitContentAccessProvider() {
        historicalHost().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UiLayoutHistoricalEvidenceService.class);
        });
    }

    @Test
    void readinessRequiresCurrentEvidenceAccessInAdditionToHistoricalAccess() {
        historicalHost().withBean(UiLayoutHistoricalEvidenceAccess.class, UiLayoutHistoricalEvidenceAccess::denyAll)
                .withBean(UiLayoutDraftWorkspaceSource.class, () -> (invocation, validation) -> null)
                .withBean(UiLayoutLifecycleStructureValidator.class, UiLayoutLifecycleStructureValidator::denyAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UiLayoutHistoricalEvidenceService.class);
                    assertThat(context).doesNotHaveBean(org.praxisplatform.config.service.UiLayoutEvolutionReadinessService.class);
                });
    }

    @Test
    void readinessRegistersOnlyWithCompleteExplicitHostComposition() {
        historicalHost().withBean(UiLayoutHistoricalEvidenceAccess.class, UiLayoutHistoricalEvidenceAccess::denyAll)
                .withBean(UiLayoutDraftWorkspaceSource.class, () -> (invocation, validation) -> null)
                .withBean(org.praxisplatform.config.service.UiLayoutCurrentEvolutionEvidenceAccess.class,
                        org.praxisplatform.config.service.UiLayoutCurrentEvolutionEvidenceAccess::denyAll)
                .withBean(UiLayoutLifecycleStructureValidator.class, UiLayoutLifecycleStructureValidator::denyAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(org.praxisplatform.config.service.UiLayoutEvolutionReadinessService.class);
                    assertThat(context).doesNotHaveBean(UiLayoutLifecycleController.class);
                });
    }

    @Test
    void readinessRequiresNativeValidatorEvenWhenContentAccessIsConfigured() {
        historicalHost().withBean(UiLayoutHistoricalEvidenceAccess.class, UiLayoutHistoricalEvidenceAccess::denyAll)
                .withBean(UiLayoutDraftWorkspaceSource.class, () -> (invocation, validation) -> null)
                .withBean(org.praxisplatform.config.service.UiLayoutCurrentEvolutionEvidenceAccess.class,
                        org.praxisplatform.config.service.UiLayoutCurrentEvolutionEvidenceAccess::denyAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UiLayoutHistoricalEvidenceService.class);
                    assertThat(context).doesNotHaveBean(org.praxisplatform.config.service.UiLayoutEvolutionReadinessService.class);
                });
    }

    @Test
    void historicalServiceIsRegisteredWithCompleteHostButAddsNoController() {
        historicalHost().withBean(UiLayoutHistoricalEvidenceAccess.class, UiLayoutHistoricalEvidenceAccess::denyAll)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UiLayoutHistoricalEvidenceService.class);
                    assertThat(context).doesNotHaveBean(UiLayoutLifecycleController.class);
                });
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withConfiguration(AutoConfigurations.of(UiLayoutResolutionAutoConfiguration.class));

    @Test
    void hostComponentScanDoesNotInstantiateOptionalControllersWithoutServices() {
        contextRunner.withUserConfiguration(HostControllerScan.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EffectiveUiLayoutCompositionController.class);
            assertThat(context).doesNotHaveBean(UiLayoutLifecycleController.class);
            assertThat(context).hasSingleBean(EffectiveUiLayoutController.class);
        });
    }

    @Test
    void hostComponentScanAndCompleteAutoConfigurationKeepSingleControllerInstances() {
        withCompleteHostDependencies().withUserConfiguration(HostControllerScan.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EffectiveUiLayoutCompositionController.class);
            assertThat(context).hasSingleBean(UiLayoutLifecycleController.class);
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = EffectiveUiLayoutCompositionController.class,
            useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = {EffectiveUiLayoutCompositionController.class, UiLayoutLifecycleController.class}))
    static class HostControllerScan {}

    @Test
    void registersOneFailClosedReadSurface() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CanonicalJsonHashService.class);
            assertThat(context).hasSingleBean(EffectiveUiAudienceProvider.class);
            assertThat(context).hasSingleBean(UiLayoutCandidateSource.class);
            assertThat(context).hasSingleBean(UiLayoutResolutionService.class);
            assertThat(context).hasSingleBean(EffectiveUiLayoutResolver.class);
            assertThat(context).hasSingleBean(EffectiveUiLayoutController.class);
            assertThat(context).hasSingleBean(UiLayoutResolutionExceptionHandler.class);
            assertThat(context).doesNotHaveBean(UiLayoutCompositionReadService.class);
            assertThat(context).doesNotHaveBean(EffectiveUiLayoutCompositionController.class);
            assertThat(context).doesNotHaveBean(UiLayoutLifecycleCommandService.class);
            assertThat(context).doesNotHaveBean(UiLayoutLifecycleReadService.class);
            assertThat(context).doesNotHaveBean(UiLayoutLifecycleController.class);

            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(EffectiveUiLayoutController.class))
                    .setControllerAdvice(context.getBean(UiLayoutResolutionExceptionHandler.class))
                    .build();
            mvc.perform(get("/api/praxis/config/ui-layouts/effective")
                            .principal(() -> "authenticated")
                            .header("X-Praxis-Context-Version", "ctx-1")
                            .param("componentType", "praxis-table")
                            .param("componentId", "table-config:orders"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUDIENCE_SOURCE_UNAVAILABLE"));
        });
    }

    @Test
    void materializesB1bAndB1cFromRealAutoConfigurationWhenHostDependenciesAreComplete() {
        withCompleteHostDependencies().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CanonicalJsonHashService.class);
            assertThat(context).hasSingleBean(UiLayoutCompositionReleaseReader.class);
            assertThat(context).hasSingleBean(UiLayoutCompositionReadService.class);
            assertThat(context).hasSingleBean(EffectiveUiLayoutCompositionController.class);
            assertThat(context).hasSingleBean(TransactionOperations.class);
            assertThat(context).hasSingleBean(UiLayoutLifecycleCommandService.class);
            assertThat(context).hasSingleBean(UiLayoutLifecycleReadService.class);
            assertThat(context).hasSingleBean(UiLayoutLifecycleRequestDecoder.class);
            assertThat(context).hasSingleBean(UiLayoutLifecycleController.class);
        });
    }

    @Test
    void hostCanonicalHashServiceReplacesTheDefaultWithoutDuplicate() {
        CanonicalJsonHashService hostHash = new CanonicalJsonHashService(new ObjectMapper());

        withCompleteHostDependencies()
                .withBean(CanonicalJsonHashService.class, () -> hostHash)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CanonicalJsonHashService.class);
                    assertThat(context.getBean(CanonicalJsonHashService.class)).isSameAs(hostHash);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(UiLayoutResolutionService.class), "hashService")).isSameAs(hostHash);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(UiLayoutCompositionReleaseReader.class), "hashes")).isSameAs(hostHash);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(UiLayoutCompositionReadService.class), "hashes")).isSameAs(hostHash);
                    assertThat(ReflectionTestUtils.getField(
                            context.getBean(UiLayoutLifecycleCommandService.class), "hashes")).isSameAs(hostHash);
                });
    }

    @Test
    void hostProvidersReplaceDefaultsWithoutCallerAudienceFacts() {
        EffectiveUiAudienceProvider audience = (principal, expected) -> new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", null, null, null, Set.of(), "ctx-1");
        UiLayoutCandidateSource source = new UiLayoutCandidateSource() {
            @Override
            public List<UiLayoutResolutionCandidate> findActive(
                    String tenant, String environment, UiLayoutTarget target) {
                return List.of();
            }

            @Override
            public Set<String> authorablePaths(UiLayoutTarget target) {
                return Set.of();
            }

            @Override
            public Set<String> removablePaths(UiLayoutTarget target) {
                return Set.of();
            }

            @Override
            public void validatePatch(UiLayoutTarget target, com.fasterxml.jackson.databind.JsonNode patch) {}
        };

        contextRunner
                .withBean(EffectiveUiAudienceProvider.class, () -> audience)
                .withBean(UiLayoutCandidateSource.class, () -> source)
                .run(context -> {
                    assertThat(context.getBean(EffectiveUiAudienceProvider.class)).isSameAs(audience);
                    assertThat(context.getBean(UiLayoutCandidateSource.class)).isSameAs(source);
                });
    }

    @Test
    void hostCanReplaceTheSingleResolverFacadeWithoutASecondDefaultFacade() {
        EffectiveUiLayoutResolver resolver = (principal, contextVersion, target) -> java.util.Optional.empty();

        contextRunner
                .withBean(EffectiveUiLayoutResolver.class, () -> resolver)
                .run(context -> {
                    assertThat(context).hasSingleBean(EffectiveUiLayoutResolver.class);
                    assertThat(context).doesNotHaveBean(UiLayoutResolutionService.class);
                    assertThat(context.getBean(EffectiveUiLayoutController.class))
                            .isNotNull();
                });
    }

    @Test
    void publishesTheAutoConfigurationThroughTheStarterImportsResource() throws Exception {
        try (InputStream imports = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(imports).as("starter auto-configuration imports resource").isNotNull();
            String entries = new String(imports.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(entries).contains(UiLayoutResolutionAutoConfiguration.class.getName());
        }
    }

    @Test
    void autoConfiguredControllerRecoversCreationIdentityWithoutWriterExecution() {
        withCompleteHostDependencies().run(context -> {
            var root = new UiLayoutTarget("praxis-table", "lookup-root");
            java.security.Principal actor = () -> "actor";
            var invocation = new org.praxisplatform.config.service.UiLayoutLifecycleInvocation("actor", "tenant", "unit", "lab", "ctx",
                    new org.praxisplatform.config.service.UiLayoutCompositionRegistration(root, List.of(root)));
            org.mockito.Mockito.when(context.getBean(UiLayoutLifecycleInvocationProvider.class).resolve(actor, "ctx", root)).thenReturn(invocation);
            var drafts = context.getBean(UiLayoutDraftRepository.class);
            var row = new org.praxisplatform.config.domain.UiLayoutDraft();
            row.setId(java.util.UUID.randomUUID()); row.setDraftEtag(java.util.UUID.randomUUID()); row.setState("DRAFT");
            row.setRootComponentType(root.componentType()); row.setRootComponentId(root.componentId());
            row.setCreatedAt(java.time.Instant.EPOCH); row.setUpdatedAt(java.time.Instant.EPOCH);
            org.mockito.Mockito.when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
                    "tenant", "lab", root.componentType(), root.componentId(), "actor", "fixture-key")).thenReturn(java.util.Optional.of(row));
            assertThat(context).hasSingleBean(UiLayoutLifecycleController.class);
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(UiLayoutLifecycleController.class))
                    .setControllerAdvice(context.getBean(UiLayoutResolutionExceptionHandler.class)).build();
            mvc.perform(get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(actor)
                    .param("rootComponentType", root.componentType()).param("rootComponentId", root.componentId())
                    .header("X-Praxis-Context-Version", "ctx").header("Idempotency-Key", "fixture-key"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.draftRef").value(row.getId().toString()));
            org.mockito.Mockito.verify(context.getBean(UiLayoutLifecycleAdmission.class), org.mockito.Mockito.times(3))
                    .require(org.praxisplatform.config.service.UiLayoutLifecycleOperation.READ_DRAFT, invocation);
            org.mockito.Mockito.verifyNoInteractions(context.getBean(UiLayoutDraftWorkspaceSource.class),
                    context.getBean(UiLayoutDraftCreationLock.class),
                    context.getBean(UiLayoutReleaseEventRepository.class));
        });
    }

    @Test
    void autoConfiguredLookupDenialPrecedesTheRepository() {
        withCompleteHostDependencies().run(context -> {
            var root = new UiLayoutTarget("praxis-table", "lookup-root");
            java.security.Principal actor = () -> "actor";
            var invocation = new org.praxisplatform.config.service.UiLayoutLifecycleInvocation("actor", "tenant", "unit", "lab", "ctx",
                    new org.praxisplatform.config.service.UiLayoutCompositionRegistration(root, List.of(root)));
            org.mockito.Mockito.when(context.getBean(UiLayoutLifecycleInvocationProvider.class).resolve(actor, "ctx", root)).thenReturn(invocation);
            org.mockito.Mockito.doThrow(new org.praxisplatform.config.service.UiLayoutLifecycleException(
                    org.praxisplatform.config.service.UiLayoutLifecycleException.Code.DENIED, "private"))
                    .when(context.getBean(UiLayoutLifecycleAdmission.class)).require(
                            org.praxisplatform.config.service.UiLayoutLifecycleOperation.READ_DRAFT, invocation);
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(UiLayoutLifecycleController.class))
                    .setControllerAdvice(context.getBean(UiLayoutResolutionExceptionHandler.class)).build();
            mvc.perform(get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(actor)
                    .param("rootComponentType", root.componentType()).param("rootComponentId", root.componentId())
                    .header("X-Praxis-Context-Version", "ctx").header("Idempotency-Key", "fixture-key"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DENIED"));
            org.mockito.Mockito.verifyNoInteractions(context.getBean(UiLayoutDraftRepository.class));
        });
    }

    private ApplicationContextRunner withCompleteHostDependencies() {
        return withCompleteHostDependencies(true);
    }

    @Test
    void leavesWriterAndLifecycleControllerAbsentWithoutExplicitMetadataAdmission() {
        withCompleteHostDependencies(false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UiLayoutLifecycleCommandService.class);
            assertThat(context).doesNotHaveBean(UiLayoutLifecycleController.class);
        });
    }

    private ApplicationContextRunner withCompleteHostDependencies(boolean metadataAdmission) {
        return withCompleteHostDependencies(metadataAdmission, true);
    }

    @Test void materializesLifecycleWithDefaultPlatformBeansWithoutManualMocks() {
        contextRunner
                .withBean(UiLayoutCompositionRegistry.class, () -> mock(UiLayoutCompositionRegistry.class))
                .withBean(UiLayoutCompositionCandidateSnapshotSource.class,
                        () -> mock(UiLayoutCompositionCandidateSnapshotSource.class))
                .withBean(UiLayoutLifecycleInvocationProvider.class,
                        () -> mock(UiLayoutLifecycleInvocationProvider.class))
                .withBean(UiLayoutLifecycleAdmission.class, () -> mock(UiLayoutLifecycleAdmission.class))
                .withBean(UiLayoutLifecycleStructureValidator.class,
                        () -> mock(UiLayoutLifecycleStructureValidator.class))
                .withBean(UiLayoutBaselineMetadataAdmission.class, UiLayoutBaselineMetadataAdmission::denyAll)
                .withBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
                        () -> new NamedParameterJdbcTemplate(mock(javax.sql.DataSource.class)))
                .withBean(UiLayoutDefinitionRepository.class, () -> mock(UiLayoutDefinitionRepository.class))
                .withBean(UiLayoutRevisionRepository.class, () -> mock(UiLayoutRevisionRepository.class))
                .withBean(UiLayoutAssignmentRevisionRepository.class,
                        () -> mock(UiLayoutAssignmentRevisionRepository.class))
                .withBean(UiLayoutDraftRepository.class, () -> mock(UiLayoutDraftRepository.class))
                .withBean(UiLayoutReleaseRepository.class, () -> mock(UiLayoutReleaseRepository.class))
                .withBean(UiLayoutReleaseMemberRepository.class, () -> mock(UiLayoutReleaseMemberRepository.class))
                .withBean(UiLayoutReleaseReviewRepository.class, () -> mock(UiLayoutReleaseReviewRepository.class))
                .withBean(UiLayoutReleaseApprovalRepository.class,
                        () -> mock(UiLayoutReleaseApprovalRepository.class))
                .withBean(UiLayoutReleaseHeadRepository.class, () -> mock(UiLayoutReleaseHeadRepository.class))
                .withBean(UiLayoutReleaseEventRepository.class, () -> mock(UiLayoutReleaseEventRepository.class))
                .withBean(ConfigTransactionManagerNames.CONFIG, PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(UiLayoutRevisionAllocationLock.class);
                    assertThat(context).hasSingleBean(UiLayoutReleaseValidator.class);
                    assertThat(context).hasSingleBean(UiLayoutDraftWorkspaceSource.class);
                    assertThat(context).hasSingleBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class);
                    assertThat(context).hasSingleBean(UiLayoutLifecycleCommandService.class);
                    assertThat(context).hasSingleBean(UiLayoutLifecycleReadService.class);
                    assertThat(context).hasSingleBean(UiLayoutLifecycleController.class);
                });
    }

    private ApplicationContextRunner withCompleteHostDependencies(boolean metadataAdmission, boolean budget) {
        var runner = contextRunner
                .withBean(UiLayoutCompositionRegistry.class, () -> mock(UiLayoutCompositionRegistry.class))
                .withBean(UiLayoutCompositionCandidateSnapshotSource.class,
                        () -> mock(UiLayoutCompositionCandidateSnapshotSource.class))
                .withBean(UiLayoutLifecycleInvocationProvider.class,
                        () -> mock(UiLayoutLifecycleInvocationProvider.class))
                .withBean(UiLayoutLifecycleAdmission.class, () -> mock(UiLayoutLifecycleAdmission.class))
                .withBean(UiLayoutLifecycleStructureValidator.class,
                        () -> mock(UiLayoutLifecycleStructureValidator.class))
                .withBean(UiLayoutReleaseValidator.class, () -> mock(UiLayoutReleaseValidator.class))
                .withBean(UiLayoutRevisionAllocationLock.class, () -> mock(UiLayoutRevisionAllocationLock.class))
                .withBean(UiLayoutDraftCreationLock.class, () -> mock(UiLayoutDraftCreationLock.class))
                .withBean(UiLayoutDraftWorkspaceSource.class, () -> mock(UiLayoutDraftWorkspaceSource.class))
                .withBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
                        () -> new NamedParameterJdbcTemplate(mock(javax.sql.DataSource.class)))
                .withBean(UiLayoutDefinitionRepository.class, () -> mock(UiLayoutDefinitionRepository.class))
                .withBean(UiLayoutRevisionRepository.class, () -> mock(UiLayoutRevisionRepository.class))
                .withBean(UiLayoutAssignmentRevisionRepository.class,
                        () -> mock(UiLayoutAssignmentRevisionRepository.class))
                .withBean(UiLayoutDraftRepository.class, () -> mock(UiLayoutDraftRepository.class))
                .withBean(UiLayoutReleaseRepository.class, () -> mock(UiLayoutReleaseRepository.class))
                .withBean(UiLayoutReleaseMemberRepository.class, () -> mock(UiLayoutReleaseMemberRepository.class))
                .withBean(UiLayoutReleaseReviewRepository.class, () -> mock(UiLayoutReleaseReviewRepository.class))
                .withBean(UiLayoutReleaseApprovalRepository.class,
                        () -> mock(UiLayoutReleaseApprovalRepository.class))
                .withBean(UiLayoutReleaseHeadRepository.class, () -> mock(UiLayoutReleaseHeadRepository.class))
                .withBean(UiLayoutReleaseEventRepository.class, () -> mock(UiLayoutReleaseEventRepository.class))
                .withBean(ConfigTransactionManagerNames.CONFIG, PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class));
        if (budget) runner = runner.withBean(org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy.class,
                () -> (op, inv) -> java.time.Duration.ofMinutes(1));
        return metadataAdmission ? runner.withBean(UiLayoutBaselineMetadataAdmission.class, UiLayoutBaselineMetadataAdmission::denyAll) : runner;
    }
}
