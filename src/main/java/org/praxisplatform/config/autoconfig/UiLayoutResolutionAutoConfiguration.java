package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.praxisplatform.config.controller.EffectiveUiLayoutController;
import org.praxisplatform.config.controller.EffectiveUiLayoutCompositionController;
import org.praxisplatform.config.controller.UiLayoutResolutionExceptionHandler;
import org.praxisplatform.config.controller.UiLayoutLifecycleController;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.praxisplatform.config.service.EffectiveUiAudienceProvider;
import org.praxisplatform.config.service.EffectiveUiLayoutResolver;
import org.praxisplatform.config.service.UiLayoutCandidateSource;
import org.praxisplatform.config.service.UiLayoutResolutionCandidate;
import org.praxisplatform.config.service.UiLayoutResolutionException;
import org.praxisplatform.config.service.UiLayoutResolutionService;
import org.praxisplatform.config.service.UiLayoutCompositionCandidateSnapshotSource;
import org.praxisplatform.config.service.UiLayoutCompositionReadService;
import org.praxisplatform.config.service.UiLayoutCompositionRegistry;
import org.praxisplatform.config.service.UiLayoutCompositionReleaseReader;
import org.praxisplatform.config.service.JpaUiLayoutCompositionReleaseReader;
import org.praxisplatform.config.service.UiLayoutLifecycleAdmission;
import org.praxisplatform.config.service.UiLayoutHistoricalEvidenceAccess;
import org.praxisplatform.config.service.UiLayoutHistoricalEvidenceService;
import org.praxisplatform.config.service.UiLayoutEvolutionReadinessService;
import org.praxisplatform.config.service.UiLayoutCurrentEvolutionEvidenceAccess;
import org.praxisplatform.config.service.UiLayoutLifecycleCommandService;
import org.praxisplatform.config.service.UiLayoutLifecycleInvocationProvider;
import org.praxisplatform.config.service.UiLayoutLifecycleReadService;
import org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy;
import org.praxisplatform.config.service.UiLayoutLifecycleRequestDecoder;
import org.praxisplatform.config.service.UiLayoutLifecycleStructureValidator;
import org.praxisplatform.config.service.UiLayoutReleaseValidator;
import org.praxisplatform.config.service.CanonicalUiLayoutReleaseValidator;
import org.praxisplatform.config.service.UiLayoutRevisionAllocationLock;
import org.praxisplatform.config.service.JdbcUiLayoutRevisionAllocationLock;
import org.praxisplatform.config.service.UiLayoutDraftCreationLock;
import org.praxisplatform.config.service.JdbcUiLayoutDraftCreationLock;
import org.praxisplatform.config.service.UiLayoutDraftWorkspaceSource;
import org.praxisplatform.config.service.ClasspathUiLayoutDraftWorkspaceSource;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataAdmission;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@AutoConfiguration
@ConditionalOnClass(UiLayoutResolutionService.class)
public class UiLayoutResolutionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public CanonicalJsonHashService canonicalJsonHashService(ObjectMapper objectMapper) {
        return new CanonicalJsonHashService(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public EffectiveUiAudienceProvider effectiveUiAudienceProvider() {
        return (principal, expectedContextVersion) -> {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.AUDIENCE_SOURCE_UNAVAILABLE,
                    "Authoritative UI audience is unavailable.",
                    null);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public UiLayoutCandidateSource uiLayoutCandidateSource() {
        return new UiLayoutCandidateSource() {
            @Override
            public List<UiLayoutResolutionCandidate> findActive(
                    String tenant, String environment, UiLayoutTarget target) {
                throw new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE,
                        "Authoritative UI layout source is unavailable.",
                        null);
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
            public void validatePatch(UiLayoutTarget target, com.fasterxml.jackson.databind.JsonNode patch) {
                throw new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE,
                        "Authoritative UI layout source is unavailable.", null);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(EffectiveUiLayoutResolver.class)
    public UiLayoutResolutionService uiLayoutResolutionService(
            ObjectMapper objectMapper,
            CanonicalJsonHashService hashes,
            EffectiveUiAudienceProvider audienceProvider,
            UiLayoutCandidateSource candidateSource) {
        return new UiLayoutResolutionService(
                objectMapper,
                hashes,
                audienceProvider,
                candidateSource,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    public EffectiveUiLayoutController effectiveUiLayoutController(EffectiveUiLayoutResolver service) {
        return new EffectiveUiLayoutController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public UiLayoutResolutionExceptionHandler uiLayoutResolutionExceptionHandler() {
        return new UiLayoutResolutionExceptionHandler();
    }

    @Bean
    @ConditionalOnBean({UiLayoutReleaseHeadRepository.class, UiLayoutReleaseRepository.class,
            UiLayoutReleaseMemberRepository.class, UiLayoutAssignmentRevisionRepository.class,
            UiLayoutRevisionRepository.class, UiLayoutDefinitionRepository.class})
    @ConditionalOnMissingBean
    public UiLayoutCompositionReleaseReader uiLayoutCompositionReleaseReader(
            UiLayoutReleaseHeadRepository heads, UiLayoutReleaseRepository releases,
            UiLayoutReleaseMemberRepository members, UiLayoutAssignmentRevisionRepository assignments,
            UiLayoutRevisionRepository revisions, UiLayoutDefinitionRepository definitions, ObjectMapper objectMapper,
            CanonicalJsonHashService hashes) {
        return new JpaUiLayoutCompositionReleaseReader(heads, releases, members, assignments, revisions, definitions, objectMapper, hashes);
    }

    @Bean
    @ConditionalOnBean({UiLayoutCompositionRegistry.class, UiLayoutCompositionCandidateSnapshotSource.class,
            UiLayoutCompositionReleaseReader.class, UiLayoutResolutionService.class})
    @ConditionalOnMissingBean
    public UiLayoutCompositionReadService uiLayoutCompositionReadService(
            EffectiveUiAudienceProvider audiences, UiLayoutCompositionRegistry registry,
            UiLayoutCompositionReleaseReader releases, UiLayoutCompositionCandidateSnapshotSource snapshots,
            UiLayoutResolutionService engine, CanonicalJsonHashService hashes) {
        return new UiLayoutCompositionReadService(audiences, registry, releases, snapshots, engine,
                hashes, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(UiLayoutCompositionReadService.class)
    @ConditionalOnMissingBean
    public EffectiveUiLayoutCompositionController effectiveUiLayoutCompositionController(UiLayoutCompositionReadService service) {
        return new EffectiveUiLayoutCompositionController(service);
    }

    @Bean
    @ConditionalOnBean(name = ConfigTransactionManagerNames.CONFIG)
    @ConditionalOnMissingBean(name = "uiLayoutLifecycleConfigTransactionOperations")
    public TransactionOperations uiLayoutLifecycleConfigTransactionOperations(
            @Qualifier(ConfigTransactionManagerNames.CONFIG) PlatformTransactionManager configTransactionManager) {
        return new TransactionTemplate(configTransactionManager);
    }

    @Bean
    @ConditionalOnBean(name = "configNamedParameterJdbcTemplate")
    @ConditionalOnMissingBean(UiLayoutDraftCreationLock.class)
    public UiLayoutDraftCreationLock uiLayoutDraftCreationLock(
            @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configJdbcTemplate) {
        return new JdbcUiLayoutDraftCreationLock(configJdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(name = "configNamedParameterJdbcTemplate")
    @ConditionalOnMissingBean(UiLayoutRevisionAllocationLock.class)
    public UiLayoutRevisionAllocationLock uiLayoutRevisionAllocationLock(
            @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configJdbcTemplate) {
        return new JdbcUiLayoutRevisionAllocationLock(configJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutValidationBudgetPolicy.class)
    public UiLayoutValidationBudgetPolicy uiLayoutValidationBudgetPolicy() {
        return (operation, invocation) -> java.time.Duration.ofMinutes(1);
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutReleaseValidator.class)
    public UiLayoutReleaseValidator uiLayoutReleaseValidator(
            @Autowired(required = false) UiLayoutCompositionRegistry compositionRegistry) {
        return new CanonicalUiLayoutReleaseValidator(compositionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutDraftWorkspaceSource.class)
    public ClasspathUiLayoutDraftWorkspaceSource classpathUiLayoutDraftWorkspaceSource(
            @Autowired(required = false) UiLayoutLifecycleAdmission operations,
            @Autowired(required = false) UiLayoutBaselineMetadataAdmission contentAdmission,
            @Autowired(required = false) UiLayoutLifecycleStructureValidator structure,
            @Autowired(required = false) List<ClasspathUiLayoutDraftWorkspaceSource.BaselinePublication> publications,
            ObjectMapper objectMapper) {
        ClasspathUiLayoutDraftWorkspaceSource source = new ClasspathUiLayoutDraftWorkspaceSource(
                operations, contentAdmission, structure, objectMapper, Clock.systemUTC());
        if (publications != null) {
            source.registerAll(publications);
        }
        return source;
    }


    @Bean
    @ConditionalOnBean(value = {org.praxisplatform.config.service.PageCreationHost.class,
            UiLayoutLifecycleInvocationProvider.class, UiLayoutLifecycleAdmission.class,
            UiLayoutDraftWorkspaceSource.class, UiLayoutBaselineMetadataAdmission.class,
            UiLayoutLifecycleStructureValidator.class, UiLayoutValidationBudgetPolicy.class,
            UiLayoutReleaseValidator.class, UiLayoutRevisionAllocationLock.class, UiLayoutDraftCreationLock.class,
            UiLayoutReleaseReviewRepository.class, UiLayoutReleaseApprovalRepository.class,
            UiLayoutReleaseHeadRepository.class, UiLayoutReleaseEventRepository.class,
            UiLayoutDraftRepository.class, UiLayoutReleaseRepository.class, UiLayoutReleaseMemberRepository.class,
            UiLayoutDefinitionRepository.class, UiLayoutRevisionRepository.class, UiLayoutAssignmentRevisionRepository.class},
            name = {"uiLayoutLifecycleConfigTransactionOperations", "configNamedParameterJdbcTemplate"})
    @ConditionalOnMissingBean
    public org.praxisplatform.config.service.PageCreationService pageCreationService(
            org.praxisplatform.config.service.PageCreationHost host,
            @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("uiLayoutLifecycleConfigTransactionOperations") TransactionOperations transactions,
            CanonicalJsonHashService hashes, ObjectMapper mapper, UiLayoutDraftWorkspaceSource source,
            UiLayoutBaselineMetadataAdmission metadata,
            UiLayoutLifecycleStructureValidator structure, UiLayoutValidationBudgetPolicy budgets,
            UiLayoutDraftRepository drafts, UiLayoutReleaseRepository releases, UiLayoutReleaseMemberRepository members,
            UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions, UiLayoutAssignmentRevisionRepository assignments,
            org.springframework.beans.factory.ObjectProvider<UiLayoutLifecycleCommandService> commands,
            org.springframework.beans.factory.ObjectProvider<UiLayoutLifecycleReadService> reads) {
        return new org.praxisplatform.config.service.PageCreationService(host, jdbc, transactions, hashes, mapper,
                source, metadata, structure, budgets, drafts, releases, members, definitions, revisions,
                assignments, commands::getObject, reads::getObject);
    }

    @Bean
    @ConditionalOnBean(org.praxisplatform.config.service.PageCreationService.class)
    @ConditionalOnMissingBean
    public org.praxisplatform.config.service.PageRuntimeReadService pageRuntimeReadService(
            org.praxisplatform.config.service.PageCreationService pages,
            org.praxisplatform.config.service.PageCreationHost host, EffectiveUiAudienceProvider audiences,
            UiLayoutLifecycleAdmission admission, UiLayoutLifecycleStructureValidator structure,
            @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbc,
            @Qualifier("uiLayoutLifecycleConfigTransactionOperations") TransactionOperations transactions,
            UiLayoutValidationBudgetPolicy budgets, UiLayoutReleaseRepository releases,
            UiLayoutDraftRepository drafts, UiLayoutReleaseMemberRepository members,
            UiLayoutRevisionRepository revisions, UiLayoutDefinitionRepository definitions,
            UiLayoutAssignmentRevisionRepository assignments, ObjectMapper mapper, CanonicalJsonHashService hashes) {
        return new org.praxisplatform.config.service.PageRuntimeReadService(pages, host, audiences,
                pages.lifecycleAdmission(admission), structure, pages.workspaceSource(), pages.metadataAdmission(),
                jdbc, transactions, budgets, releases, drafts, members, revisions, definitions, assignments,
                mapper, hashes, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(org.praxisplatform.config.service.PageRuntimeReadService.class)
    @ConditionalOnMissingBean
    public org.praxisplatform.config.controller.PageRuntimeController pageRuntimeController(
            org.praxisplatform.config.service.PageRuntimeReadService pages) {
        return new org.praxisplatform.config.controller.PageRuntimeController(pages);
    }

    @Bean
    @ConditionalOnBean(org.praxisplatform.config.service.PageCreationService.class)
    @ConditionalOnMissingBean
    public org.praxisplatform.config.controller.PageCreationController pageCreationController(
            org.praxisplatform.config.service.PageCreationService pages) {
        return new org.praxisplatform.config.controller.PageCreationController(pages);
    }

    @Bean
    @ConditionalOnBean(value = {UiLayoutValidationBudgetPolicy.class, UiLayoutLifecycleInvocationProvider.class, UiLayoutLifecycleAdmission.class,
            UiLayoutLifecycleStructureValidator.class, UiLayoutReleaseValidator.class, UiLayoutRevisionAllocationLock.class,
            UiLayoutDraftWorkspaceSource.class, UiLayoutDraftCreationLock.class, UiLayoutBaselineMetadataAdmission.class,
            UiLayoutDefinitionRepository.class, UiLayoutRevisionRepository.class, UiLayoutAssignmentRevisionRepository.class,
            UiLayoutDraftRepository.class, UiLayoutReleaseRepository.class, UiLayoutReleaseMemberRepository.class,
            UiLayoutReleaseReviewRepository.class, UiLayoutReleaseApprovalRepository.class, UiLayoutReleaseHeadRepository.class,
            UiLayoutReleaseEventRepository.class}, name = {"uiLayoutLifecycleConfigTransactionOperations", "configNamedParameterJdbcTemplate"})
    @ConditionalOnMissingBean
    public UiLayoutLifecycleCommandService uiLayoutLifecycleCommandService(UiLayoutDefinitionRepository definitions,
            UiLayoutRevisionRepository revisions, UiLayoutAssignmentRevisionRepository assignments, UiLayoutDraftRepository drafts,
            UiLayoutReleaseRepository releases, UiLayoutReleaseMemberRepository members, UiLayoutReleaseReviewRepository reviews,
            UiLayoutReleaseApprovalRepository approvals, UiLayoutReleaseHeadRepository heads, UiLayoutReleaseEventRepository events,
            UiLayoutRevisionAllocationLock allocationLock, CanonicalJsonHashService hashes, ObjectMapper objectMapper,
            UiLayoutReleaseValidator releaseValidator,
            UiLayoutLifecycleInvocationProvider invocations, UiLayoutLifecycleAdmission admission,
            UiLayoutLifecycleStructureValidator structure, UiLayoutDraftWorkspaceSource workspaceSource,
            UiLayoutDraftCreationLock creationLock,
            UiLayoutBaselineMetadataAdmission metadataAdmission,
            @Qualifier("configNamedParameterJdbcTemplate") NamedParameterJdbcTemplate configJdbc,
            @Qualifier("uiLayoutLifecycleConfigTransactionOperations") TransactionOperations transactions, UiLayoutValidationBudgetPolicy budgetPolicy,
            @Autowired(required = false) org.praxisplatform.config.service.PageCreationService pages,
            @Autowired(required = false) org.praxisplatform.config.service.PageCreationHost pageHost) {
        if (pages != null) {
            invocations = pages.invocationProvider(invocations);
            admission = pages.lifecycleAdmission(admission);
        }

        if (pages != null) {
            workspaceSource = pages.workspaceSource();
            metadataAdmission = pages.metadataAdmission();
        }
        return UiLayoutLifecycleCommandService.create(definitions, revisions, assignments, drafts, releases, members, reviews,
                approvals, heads, events, allocationLock, hashes, objectMapper,
                releaseValidator, invocations, admission, structure, workspaceSource, creationLock, configJdbc, metadataAdmission, transactions, budgetPolicy, pageHost);
    }

    @Bean
    @ConditionalOnBean({UiLayoutValidationBudgetPolicy.class, UiLayoutLifecycleInvocationProvider.class, UiLayoutLifecycleAdmission.class,
            UiLayoutDraftRepository.class, UiLayoutReleaseRepository.class, UiLayoutReleaseReviewRepository.class,
            UiLayoutReleaseHeadRepository.class, UiLayoutDefinitionRepository.class, UiLayoutRevisionRepository.class,
            UiLayoutAssignmentRevisionRepository.class, UiLayoutLifecycleStructureValidator.class})
    @ConditionalOnMissingBean
    public UiLayoutLifecycleReadService uiLayoutLifecycleReadService(UiLayoutLifecycleInvocationProvider invocations,
            UiLayoutLifecycleAdmission admission, UiLayoutDraftRepository drafts, UiLayoutReleaseRepository releases,
            UiLayoutReleaseReviewRepository reviews, UiLayoutReleaseHeadRepository heads,
            UiLayoutDefinitionRepository definitions, UiLayoutRevisionRepository revisions,
            UiLayoutAssignmentRevisionRepository assignments, ObjectMapper objectMapper,
            CanonicalJsonHashService hashes, UiLayoutLifecycleStructureValidator structure, UiLayoutValidationBudgetPolicy budgetPolicy,
            @Autowired(required = false) org.praxisplatform.config.service.PageCreationService pages) {
        if (pages != null) {
            invocations = pages.invocationProvider(invocations);
            admission = pages.lifecycleAdmission(admission);
        }

        return new UiLayoutLifecycleReadService(invocations, admission, drafts, releases, reviews, heads,
                definitions, revisions, assignments, objectMapper, hashes, structure, budgetPolicy);
    }

    @Bean
    @ConditionalOnBean({UiLayoutValidationBudgetPolicy.class, UiLayoutLifecycleInvocationProvider.class, UiLayoutLifecycleAdmission.class,
            UiLayoutHistoricalEvidenceAccess.class, UiLayoutReleaseRepository.class, UiLayoutDraftRepository.class,
            UiLayoutReleaseMemberRepository.class, UiLayoutDefinitionRepository.class,
            UiLayoutRevisionRepository.class, UiLayoutAssignmentRevisionRepository.class})
    @ConditionalOnMissingBean
    public UiLayoutHistoricalEvidenceService uiLayoutHistoricalEvidenceService(UiLayoutLifecycleInvocationProvider invocations,
            UiLayoutLifecycleAdmission admission, UiLayoutHistoricalEvidenceAccess access, UiLayoutReleaseRepository releases,
            UiLayoutDraftRepository drafts, UiLayoutReleaseMemberRepository members, UiLayoutDefinitionRepository definitions,
            UiLayoutRevisionRepository revisions, UiLayoutAssignmentRevisionRepository assignments, ObjectMapper mapper,
            CanonicalJsonHashService hashes, UiLayoutValidationBudgetPolicy budgetPolicy,
            @Autowired(required = false) org.praxisplatform.config.service.PageCreationService pages) {
        if (pages != null) {
            invocations = pages.invocationProvider(invocations);
            admission = pages.lifecycleAdmission(admission);
        }

        return new UiLayoutHistoricalEvidenceService(invocations, admission, access, releases, drafts, members,
                definitions, revisions, assignments, mapper, hashes, budgetPolicy);
    }

    @Bean
    @ConditionalOnBean({UiLayoutValidationBudgetPolicy.class, UiLayoutHistoricalEvidenceService.class, UiLayoutLifecycleInvocationProvider.class,
            UiLayoutLifecycleAdmission.class, UiLayoutDraftWorkspaceSource.class, UiLayoutCurrentEvolutionEvidenceAccess.class,
            UiLayoutLifecycleStructureValidator.class})
    @ConditionalOnMissingBean
    public UiLayoutEvolutionReadinessService uiLayoutEvolutionReadinessService(UiLayoutHistoricalEvidenceService history,
            UiLayoutLifecycleInvocationProvider invocations, UiLayoutLifecycleAdmission admission,
            UiLayoutDraftWorkspaceSource source, UiLayoutCurrentEvolutionEvidenceAccess access,
            UiLayoutLifecycleStructureValidator structure, ObjectMapper mapper, CanonicalJsonHashService hashes, UiLayoutValidationBudgetPolicy budgetPolicy,
            @Autowired(required = false) org.praxisplatform.config.service.PageCreationService pages) {
        if (pages != null) {
            invocations = pages.invocationProvider(invocations);
            admission = pages.lifecycleAdmission(admission);
        }

        if (pages != null) source = pages.workspaceSource();
        return new UiLayoutEvolutionReadinessService(history, invocations, admission, source, access, structure, mapper, hashes, budgetPolicy);
    }

    @Bean
    @ConditionalOnBean({UiLayoutLifecycleCommandService.class, UiLayoutLifecycleReadService.class})
    @ConditionalOnMissingBean
    public UiLayoutLifecycleRequestDecoder uiLayoutLifecycleRequestDecoder(ObjectMapper objectMapper) {
        return new UiLayoutLifecycleRequestDecoder(objectMapper);
    }

    @Bean
    @ConditionalOnBean({UiLayoutLifecycleCommandService.class, UiLayoutLifecycleReadService.class,
            UiLayoutLifecycleRequestDecoder.class})
    @ConditionalOnMissingBean
    public UiLayoutLifecycleController uiLayoutLifecycleController(UiLayoutLifecycleReadService reads,
            UiLayoutLifecycleCommandService commands, UiLayoutLifecycleRequestDecoder decoder) {
        return new UiLayoutLifecycleController(reads, commands, decoder);
    }

    @Bean
    @ConditionalOnBean({UiLayoutLifecycleCommandService.class, UiLayoutLifecycleReadService.class,
            UiLayoutLifecycleRequestDecoder.class})
    @ConditionalOnMissingBean
    public org.praxisplatform.config.controller.UiLayoutRevisionRequestBodyAdvice uiLayoutRevisionRequestBodyAdvice() {
        return new org.praxisplatform.config.controller.UiLayoutRevisionRequestBodyAdvice();
    }
}
