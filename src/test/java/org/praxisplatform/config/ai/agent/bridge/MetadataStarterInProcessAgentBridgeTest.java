package org.praxisplatform.config.ai.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordance;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordanceContributor;
import org.praxisplatform.config.ai.agent.spi.AgentActionExecutionOutcome;
import org.praxisplatform.config.ai.agent.spi.AgentActionPreparationContributor;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentDeterminationRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecuteActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentPrepareActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedAction;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeDocument;
import org.praxisplatform.config.ai.agent.spi.AgentSimulationPreview;
import org.praxisplatform.config.ai.agent.spi.AgentStatsAggregationRequest;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;
import org.praxisplatform.config.ai.agent.tools.AggregateResourceStatsTool;
import org.praxisplatform.config.ai.agent.tools.DiscoverTopicResourcesAndSchemasTool;
import org.praxisplatform.config.ai.agent.tools.ExplainCalculationMemoryTool;
import org.praxisplatform.config.ai.agent.tools.GetEntity360DossierTool;
import org.praxisplatform.config.ai.agent.tools.QueryResourceRecordsTool;
import org.praxisplatform.config.ai.agent.tools.SearchAndResolveEntitiesTool;
import org.praxisplatform.config.ai.agent.tools.SearchDomainKnowledgeAndRulesTool;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.violation.PraxisRuleViolationException;
import org.praxisplatform.rules.violation.SlotViolationDetail;
import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionRequirement;
import org.praxisplatform.uischema.action.ActionRiskLevel;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.praxisplatform.uischema.annotation.FilterCriterion;
import org.praxisplatform.uischema.annotation.QuickFilter;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.fact.ResourceFact;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditions;
import org.praxisplatform.uischema.concurrency.ResourceVersionScope;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.DistributionBucket;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;

@Tag("unit")
class MetadataStarterInProcessAgentBridgeTest {

    public record CompoundContractId(Long personId, Integer assignmentSeq)
            implements org.praxisplatform.uischema.id.CompositeResourceId {
    }

    public static class EmployeeDossierDto {
        private CompoundContractId id;
        @UISchema(label = "Nome completo")
        private String fullName;
        @UISchema(label = "CPF", preset = UISchemaPreset.CPF_CNPJ)
        @DomainGovernance(
                classification = DomainClassification.CONFIDENTIAL,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK))
        private String taxId;
        @UISchema(label = "Salario confidencial")
        @DomainGovernance(
                classification = DomainClassification.RESTRICTED,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.DENY))
        private Double salary;
        @UISchema(label = "Notas auditoria", visibleForAuthorities = {"ROLE_HR_AUDITOR"})
        private String auditNotes;
        private String departmentName;

        public EmployeeDossierDto() {
        }

        public EmployeeDossierDto(
                CompoundContractId id,
                String fullName,
                String taxId,
                Double salary,
                String auditNotes,
                String departmentName) {
            this.id = id;
            this.fullName = fullName;
            this.taxId = taxId;
            this.salary = salary;
            this.auditNotes = auditNotes;
            this.departmentName = departmentName;
        }

        public CompoundContractId getId() {
            return id;
        }

        public String getFullName() {
            return fullName;
        }

        public String getTaxId() {
            return taxId;
        }

        public Double getSalary() {
            return salary;
        }

        public String getAuditNotes() {
            return auditNotes;
        }

        public String getDepartmentName() {
            return departmentName;
        }
    }

    public static class EmployeeFilterDto implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "CPF", controlType = FieldControlType.CPF_CNPJ_INPUT, preset = UISchemaPreset.CPF_CNPJ)
        private String taxId;
        @Filterable(operation = Filterable.FilterOperation.LIKE, relations = {"fullName", "departmentName"})
        @UISchema(label = "Nome")
        private String fullName;

        public String getTaxId() {
            return taxId;
        }

        public void setTaxId(String taxId) {
            this.taxId = taxId;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }
    }

    public static class StubEmployeeService implements BaseResourceQueryService<EmployeeDossierDto, CompoundContractId, EmployeeFilterDto> {
        private final EmployeeDossierDto fixture = new EmployeeDossierDto(
                new CompoundContractId(1001L, 1),
                "Ana Beatriz Costa",
                "52998224725",
                14800.00d,
                "Revisao anual pendente",
                "Engenharia de Plataforma");

        @Override public String getIdFieldName() { return "id"; }
        @Override public Sort getDefaultSort() { return Sort.by("fullName"); }
        @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
        @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
        @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
        @Override public boolean hasOptionSource(String key) { return false; }
        @Override public OptionSourceDescriptor resolveOptionSource(String key) { return null; }
        @Override public List<EmployeeDossierDto> findAll() { return List.of(fixture); }
        @Override public List<EmployeeDossierDto> findAllById(Collection<CompoundContractId> ids) { return List.of(fixture); }

        @Override
        public ResourceRepresentationResult<EmployeeDossierDto> findById(CompoundContractId id) {
            if (fixture.getId().equals(id)) {
                return ResourceRepresentationResult.unversioned(fixture);
            }
            return null;
        }

        @Override
        public Page<EmployeeDossierDto> filter(EmployeeFilterDto f, Pageable p, Collection<CompoundContractId> ids) {
            if (f != null && "52998224725".equals(f.getTaxId())) {
                return new PageImpl<>(List.of(fixture));
            }
            if (f != null && f.getFullName() != null && "ana".equalsIgnoreCase(f.getFullName())) {
                return new PageImpl<>(List.of(fixture));
            }
            return Page.empty();
        }

        @Override public CursorPage<EmployeeDossierDto> filterByCursor(EmployeeFilterDto f, Sort s, String a, String c, int l) { return null; }
        @Override public java.util.OptionalLong locate(EmployeeFilterDto f, Sort s, CompoundContractId id) { return java.util.OptionalLong.empty(); }
        @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<CompoundContractId>> filterOptions(EmployeeFilterDto f, Pageable p) { return Page.empty(); }
        @Override public List<org.praxisplatform.uischema.dto.OptionDTO<CompoundContractId>> byIdsOptions(Collection<CompoundContractId> ids) { return List.of(); }
        @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<EmployeeFilterDto> r, Pageable p) { return Page.empty(); }
        @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
        @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<EmployeeFilterDto> r) { return null; }
        @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<EmployeeFilterDto> r) { return null; }
        @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<EmployeeFilterDto> r) { return null; }
        @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<EmployeeFilterDto> r) { return null; }
    }

    @ApiResource(
            value = "/api/human-resources/employees",
            resourceKey = "hr.employees",
            title = "Colaboradores",
            description = "Cadastro funcional",
            identity = @ResourceIdentity(
                    keyField = "id",
                    titleField = "fullName",
                    subtitleField = "departmentName",
                    metadataFields = {"departmentName"}))
    public static class StubEmployeeController extends AbstractResourceQueryController<EmployeeDossierDto, CompoundContractId, EmployeeFilterDto> {
        private final StubEmployeeService service = new StubEmployeeService();

        @Override
        protected BaseResourceQueryService<EmployeeDossierDto, CompoundContractId, EmployeeFilterDto> getService() {
            return service;
        }

        @Override
        protected CompoundContractId getResponseId(EmployeeDossierDto dto) {
            return dto.getId();
        }
    }

    @Test
    void supportsCompoundIdsDualProjectionAlertsAndZeroActiveTransactionDuringLlmIo() {
        DefaultConversionService conversionService = new DefaultConversionService();

        AtomicBoolean txOpenedForTool = new AtomicBoolean(false);
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                txOpenedForTool.set(true);
                TransactionSynchronizationManager.setActualTransactionActive(true);
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            @Override
            public void rollback(TransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        };

        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubEmployeeController()),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "hr.employees", "hr.employees", "/api/human-resources/employees",
                        "Colaboradores", "Cadastro de pessoal", "badge",
                        "id", "fullName", "departmentName", List.of("departmentName"),
                        "taxId", "fullName", null, "/employees/{id}", "hr", 100))),
                List.of(),
                List.of(new org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor() {
                    @Override
                    public boolean supportsEntity(String entityKey) {
                        return "hr.employees".equals(entityKey);
                    }

                    @Override
                    public List<AgentProactiveAlert> evaluateAlerts(
                            AgentExecutionContext ctx, String key, String id, Map<String, Object> record) {
                        return List.of(new AgentProactiveAlert(
                                "VACATION_EXPIRING", "warning",
                                "Ferias a vencer em 20 dias",
                                "Agende o periodo concessivo antes do vencimento legal.",
                                key, "/api/human-resources/employees", id,
                                "schedule-leave", "Agendar ferias", Map.of()));
                    }
                }),
                null,
                null,
                null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                conversionService,
                new ObjectMapper().findAndRegisterModules(),
                txManager);

        AgentExecutionContext standardUserContext = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-1", "user-1", "prod", "user-1",
                Set.of("ROLE_USER"), null, null, Map.of(), Map.of());

        List<AgentEntityMatch> matches = bridge.searchEntities(
                standardUserContext,
                new AgentEntitySearchRequest("52998224725", "52998224725", true, List.of(), 5));
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).entityId()).isEqualTo("1001:1");
        assertThat(txOpenedForTool.get()).isTrue();

        AgentEntityDossier dossier = bridge.loadEntityDossier(
                        standardUserContext, "hr.employees", "1001:1", List.of())
                .orElseThrow();
        assertThat(dossier.uiRecord())
                .containsKey("salary")
                .doesNotContainKey("auditNotes");
        assertThat(dossier.modelSafeRecord())
                .doesNotContainKey("salary")
                .doesNotContainKey("auditNotes")
                .containsEntry("taxId", "***.982.247-**");
        assertThat(dossier.alerts()).hasSize(1);
        assertThat(dossier.alerts().get(0).code()).isEqualTo("VACATION_EXPIRING");
        assertThat(dossier.actions()).anyMatch(a -> "open-route".equals(a.actionId())
                && "/employees/1001:1".equals(a.route()));

        AtomicBoolean txActiveDuringLlm = new AtomicBoolean(true);
        AtomicInteger llmRound = new AtomicInteger(0);
        AiToolCallingProvider spyProvider = new AiToolCallingProvider() {
            @Override
            public String providerId() {
                return "gemini";
            }

            @Override
            public AgentModelTurnResponse generateTurn(
                    org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest request,
                    org.praxisplatform.config.ai.agent.model.AgentModelStreamListener listener) {
                txActiveDuringLlm.set(TransactionSynchronizationManager.isActualTransactionActive());
                int step = llmRound.getAndIncrement();
                if (step == 0) {
                    return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                            List.of(new AgentToolCall("c1", "search_and_resolve_entities",
                                    Map.of("query", "[TAX_ID_1]"), null, null)),
                            AgentTokenUsage.ZERO, "tool_calls", "r1", Map.of());
                }
                if (step == 1) {
                    return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                            List.of(new AgentToolCall("c2", "get_entity_360_dossier",
                                    Map.of("entityKey", "hr.employees", "entityId", "1001:1"), null, null)),
                            AgentTokenUsage.ZERO, "tool_calls", "r2", Map.of());
                }
                return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                        "Dossie carregado com sucesso.", List.of(),
                        new AgentTokenUsage(50, 20, 70), "stop", "r3", Map.of());
            }
        };

        PraxisOperationalAgentLoop loop = new PraxisOperationalAgentLoop(
                spyProvider,
                List.of(
                        new SearchAndResolveEntitiesTool(bridge),
                        new DiscoverTopicResourcesAndSchemasTool(bridge),
                        new QueryResourceRecordsTool(bridge),
                        new GetEntity360DossierTool(bridge)),
                new OperationalAssistantBlockComposer(new ObjectMapper().findAndRegisterModules()));

        PraxisOperationalAgentLoop.AgentTurnOutcome outcome = loop.execute(
                standardUserContext,
                "529.982.247-25",
                null,
                List.of(),
                AiCallConfig.builder().provider("gemini").build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);

        assertThat(txActiveDuringLlm.get()).isFalse();
        assertThat(outcome.roundsExecuted()).isEqualTo(3);
        assertThat(outcome.assistantContent().path("blocks").toString())
                .contains("alert-list")
                .contains("praxis-rich-content")
                .contains("VACATION_EXPIRING");

        MetadataStarterInProcessAgentBridge bridgeWithActionContributor = new MetadataStarterInProcessAgentBridge(
                List.of(new StubEmployeeController()),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "hr.employees", "hr.employees", "/api/human-resources/employees",
                        "Colaboradores", "Cadastro de pessoal", "badge",
                        "id", "fullName", "departmentName", List.of("departmentName"),
                        "taxId", "fullName", null, "/employees/{id}", "hr", 100))),
                List.of(),
                List.of(),
                null,
                null,
                null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                conversionService,
                new ObjectMapper().findAndRegisterModules(),
                txManager,
                List.of((AgentActionAffordanceContributor) (ctx, entity, convertedId, idStr, uiRecord, discovered) -> {
                    var mutable = new java.util.ArrayList<>(discovered);
                    mutable.add(new AgentActionAffordance(
                            "pin-scope", "Fixar registro", "Fixa contexto ativo", "push_pin", "primary",
                            entity.resourceKey(), entity.resourcePath(), idStr,
                            "apply-host-scope", "inline", null, null, null, null,
                            Map.of(), Map.of("employeeId", idStr), false, false, null, 10));
                    return mutable;
                }));
        AgentEntityDossier enrichedDossier = bridgeWithActionContributor
                .loadEntityDossier(standardUserContext, "hr.employees", "1001:1", List.of())
                .orElseThrow();
        assertThat(enrichedDossier.actions())
                .extracting(AgentActionAffordance::actionId)
                .contains("pin-scope", "open-route");
    }

    public static class NullableEmployeeDto {
        private Long id;
        @UISchema(label = "Nome completo")
        private String fullName;
        @UISchema(label = "Departamento")
        private String departmentName;
        @UISchema(label = "Observacao opcional")
        private String optionalNote;
        @DomainGovernance(aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK))
        private String secondaryTaxId;
        @DomainGovernance(aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.SUMMARIZE_ONLY))
        private String medicalSummary;

        public NullableEmployeeDto() {
        }

        public NullableEmployeeDto(Long id, String fullName) {
            this(id, fullName, null);
        }

        public NullableEmployeeDto(Long id, String fullName, String departmentName) {
            this.id = id;
            this.fullName = fullName;
            this.departmentName = departmentName;
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getDepartmentName() { return departmentName; }
        public String getOptionalNote() { return optionalNote; }
        public String getSecondaryTaxId() { return secondaryTaxId; }
        public String getMedicalSummary() { return medicalSummary; }
    }

    @ApiResource(
            value = "/api/human-resources/nullable-employees",
            resourceKey = "hr.nullable-employees",
            title = "Colaboradores Opcionais",
            identity = @ResourceIdentity(
                    keyField = "id",
                    titleField = "fullName",
                    subtitleField = "departmentName",
                    metadataFields = {"departmentName", "optionalNote"}))
    public static class NullableEmployeeController
            extends AbstractResourceQueryController<NullableEmployeeDto, Long, EmployeeFilterDto> {
        private final NullableEmployeeDto row;

        public NullableEmployeeController() {
            this(new NullableEmployeeDto(2002L, "Carlos Alberto"));
        }

        public NullableEmployeeController(NullableEmployeeDto row) {
            this.row = row;
        }

        private final BaseResourceQueryService<NullableEmployeeDto, Long, EmployeeFilterDto> service =
                new BaseResourceQueryService<>() {
                    @Override public String getIdFieldName() { return "id"; }
                    @Override public Sort getDefaultSort() { return Sort.by("fullName"); }
                    @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
                    @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
                    @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
                    @Override public boolean hasOptionSource(String key) { return false; }
                    @Override public OptionSourceDescriptor resolveOptionSource(String key) { return null; }
                    @Override public List<NullableEmployeeDto> findAll() { return List.of(row); }
                    @Override public List<NullableEmployeeDto> findAllById(Collection<Long> ids) { return List.of(row); }
                    @Override public ResourceRepresentationResult<NullableEmployeeDto> findById(Long id) {
                        return Long.valueOf(2002L).equals(id)
                                ? ResourceRepresentationResult.unversioned(row)
                                : null;
                    }
                    @Override public Page<NullableEmployeeDto> filter(EmployeeFilterDto f, Pageable p, Collection<Long> ids) {
                        if (f != null && f.getFullName() != null && f.getFullName().toLowerCase().contains("carlos")) {
                            return new PageImpl<>(List.of(row));
                        }
                        return Page.empty();
                    }
                    @Override public CursorPage<NullableEmployeeDto> filterByCursor(EmployeeFilterDto f, Sort s, String a, String c, int l) { return null; }
                    @Override public java.util.OptionalLong locate(EmployeeFilterDto f, Sort s, Long id) { return java.util.OptionalLong.empty(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Long>> filterOptions(EmployeeFilterDto f, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<EmployeeFilterDto> r, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
                    @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<EmployeeFilterDto> r) { return null; }
                };

        @Override
        protected BaseResourceQueryService<NullableEmployeeDto, Long, EmployeeFilterDto> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(NullableEmployeeDto dto) {
            return dto.getId();
        }
    }

    @Test
    void supportsNullOptionalDtoFieldsAndNullToolArgumentsWithoutNullPointerException() {
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new NullableEmployeeController()),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "hr.nullable-employees", "hr.nullable-employees", "/api/human-resources/nullable-employees",
                        "Colaboradores Opcionais", "Cadastro com campos nulos", "badge",
                        "id", "fullName", "departmentName", List.of("departmentName", "optionalNote"),
                        null, "fullName", null, "/nullable-employees/{id}", "hr", 100))),
                List.of(),
                List.of(),
                null,
                null,
                null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-1", "user-1", "prod", "user-1",
                Set.of("ROLE_USER"), null, null, Map.of(), Map.of());

        List<AgentEntityMatch> matches = bridge.searchEntities(
                ctx, new AgentEntitySearchRequest("Carlos", null, false, List.of(), 5));
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).subtitle()).isEmpty();
        assertThat(matches.get(0).uiSummary()).containsKey("departmentName");
        assertThat(matches.get(0).modelSafeSummary()).containsEntry("fullName", "Carlos Alberto");

        AgentEntityDossier dossier = bridge.loadEntityDossier(ctx, "hr.nullable-employees", "2002", List.of())
                .orElseThrow();
        assertThat(dossier.uiRecord()).containsKey("optionalNote");
        assertThat(dossier.modelSafeRecord())
                .containsEntry("fullName", "Carlos Alberto")
                .containsEntry("medicalSummary", "[empty]")
                .doesNotContainKey("optionalNote")
                .doesNotContainKey("secondaryTaxId");
        assertThat(dossier.maskedForAiFields()).containsExactlyInAnyOrder("secondaryTaxId", "medicalSummary");

        java.util.Map<String, Object> argsWithNull = new java.util.LinkedHashMap<>();
        argsWithNull.put("query", "Carlos");
        argsWithNull.put("optionalFilter", null);
        AgentToolCall callWithNullArg = new AgentToolCall("call-null-arg", "search_and_resolve_entities", argsWithNull, null, null);
        assertThat(callWithNullArg.arguments()).containsEntry("query", "Carlos").containsEntry("optionalFilter", null);
    }

    @Test
    void supersedesParentMatchesOmitsDisallowedStateActionsAutoLoadsDossierAndDeduplicatesBlocks() {
        NullableEmployeeController personsController = new NullableEmployeeController();
        StubEmployeeController assignmentsController = new StubEmployeeController();

        ActionCatalogService actionCatalog = mock(ActionCatalogService.class);
        ActionCatalogItem allowedAction = new ActionCatalogItem(
                "schedule-leave", "hr.assignments", ActionScope.ITEM,
                "Agendar ferias", "Agenda periodo concessivo", "event",
                "/api/human-resources/assignments/{id}/actions/schedule-leave", "POST",
                null, null, null, null, List.of("ACTIVE"),
                AvailabilityDecision.allowAll(), 10, null, List.of(),
                ActionExecutionContract.defaults(ActionScope.ITEM));
        ActionCatalogItem disallowedByState = new ActionCatalogItem(
                "close-secondment", "hr.assignments", ActionScope.ITEM,
                "Encerrar cessao", "Aplicavel apenas quando cedido", "swap_horiz",
                "/api/human-resources/assignments/{id}/actions/close-secondment", "POST",
                null, null, null, null, List.of("SECONDED"),
                AvailabilityDecision.deny("Registro nao esta em cessao.", Map.of()), 20, null, List.of(),
                ActionExecutionContract.defaults(ActionScope.ITEM));
        ActionCatalogItem rbacRestrictedAction = new ActionCatalogItem(
                "approve-termination", "hr.assignments", ActionScope.ITEM,
                "Homologar desligamento", "Restrito a gestores", "gavel",
                "/api/human-resources/assignments/{id}/actions/approve-termination", "POST",
                null, null, null, null, List.of("ACTIVE"),
                AvailabilityDecision.allowAll(), 30, null, List.of(),
                ActionExecutionContract.defaults(ActionScope.ITEM));
        when(actionCatalog.findItemActions(eq("hr.assignments"), any()))
                .thenReturn(new ActionCatalogResponse(
                        "hr.assignments", "/api/human-resources/assignments", "hr",
                        new CompoundContractId(1001L, 1),
                        List.of(allowedAction, disallowedByState, rbacRestrictedAction)));

        AgentAuthorizationBridge baseAuth = new DefaultMetadataGovernanceAgentAuthorizationBridge();
        AgentAuthorizationBridge customAuth = new AgentAuthorizationBridge() {
            @Override
            public boolean canReadResource(AgentExecutionContext ctx, String resourcePath) {
                return true;
            }

            @Override
            public boolean canInvokeAction(AgentExecutionContext ctx, String resourcePath, String actionId, Object resourceId) {
                return !"approve-termination".equals(actionId);
            }

            @Override
            public org.praxisplatform.config.ai.agent.spi.DualProjectedPayload projectRecord(
                    AgentExecutionContext ctx,
                    String resourcePath,
                    Class<?> responseDtoType,
                    Map<String, Object> rawRecord) {
                return baseAuth.projectRecord(ctx, resourcePath, responseDtoType, rawRecord);
            }
        };

        AgentEntityCatalogContributor contributor = ctx -> List.of(
                new AgentEntityDescriptor(
                        "hr.assignments", "hr.assignments", "/api/human-resources/employees",
                        "Assignments", "Workforce assignments", "badge",
                        "id", "fullName", "departmentName", List.of("departmentName"),
                        "taxId", "fullName", null, "/assignments/{id}", "hr", 100,
                        "hr.persons", true),
                new AgentEntityDescriptor(
                        "hr.persons", "hr.persons", "/api/human-resources/nullable-employees",
                        "Persons", "Civil registry", "person",
                        "id", "fullName", "departmentName", List.of("departmentName"),
                        null, "fullName", null, "/persons/{id}", "hr", 90,
                        null, true));

        AgentProactiveAlertContributor alertContributor = new AgentProactiveAlertContributor() {
            @Override
            public boolean supportsEntity(String entityKey) {
                return "hr.assignments".equals(entityKey);
            }

            @Override
            public List<AgentProactiveAlert> evaluateAlerts(
                    AgentExecutionContext ctx, String entityKey, String entityId, Map<String, Object> record) {
                return List.of(new AgentProactiveAlert(
                        "LEAVE_PENDING", "warning", "Ferias pendentes", "Saldo disponivel",
                        entityKey, "/api/human-resources/employees", entityId,
                        "schedule-leave", "Agendar ferias", Map.of()));
            }
        };

        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(assignmentsController, personsController),
                List.of(contributor),
                List.of(),
                List.of(alertContributor),
                null,
                actionCatalog,
                null,
                customAuth,
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-1", "user-1", "prod", "user-1",
                Set.of("ROLE_USER"), null, null, Map.of(), Map.of());

        // When both hr.assignments and hr.persons match "ana"/"Carlos" wait: use TaxID -> only assignments matches; test shared name query via filter match!
        // Override filter test: search "Carlos" matches hr.persons; when hr.assignments does NOT match, hr.persons is preserved!
        List<AgentEntityMatch> personFallback = bridge.searchEntities(
                ctx, new AgentEntitySearchRequest("Carlos", null, false, List.of(), 5));
        assertThat(personFallback).extracting(AgentEntityMatch::entityKey).containsExactly("hr.persons");

        // Now test CPF Mod-11 match + autoLoadedDossier + state action filtering:
        SearchAndResolveEntitiesTool searchTool = new SearchAndResolveEntitiesTool(bridge);
        GetEntity360DossierTool dossierTool = new GetEntity360DossierTool(bridge);
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("529.982.247-25");
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();

        Map<String, Object> searchOutput = searchTool.execute(
                ctx, vault, artifacts, Map.of("query", vault.sanitizedPrompt()));
        assertThat(searchOutput)
                .containsEntry("status", "SINGLE_MATCH")
                .containsEntry("matchCount", 1)
                .containsKey("autoLoadedDossier");
        assertThat(artifacts.dossiers()).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> autoDossier = (Map<String, Object>) searchOutput.get("autoLoadedDossier");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actionsOut = (List<Map<String, Object>>) autoDossier.get("actions");
        assertThat(actionsOut)
                .extracting(a -> a.get("actionId"))
                .contains("schedule-leave", "approve-termination", "open-route")
                .doesNotContain("close-secondment");
        assertThat(actionsOut)
                .filteredOn(a -> "approve-termination".equals(a.get("actionId")))
                .singleElement()
                .extracting(a -> a.get("disabled"))
                .isEqualTo(true);

        // Calling get_entity_360_dossier again replaces rather than duplicates in AgentTurnArtifactStore,
        // and adding a superseded hr.persons dossier still composes single consolidated alert-list, widget-block, action-bar.
        dossierTool.execute(ctx, vault, artifacts, Map.of("entityKey", "hr.assignments", "entityId", "1001:1"));
        assertThat(artifacts.dossiers()).hasSize(1);
        dossierTool.execute(ctx, vault, artifacts, Map.of("entityKey", "hr.persons", "entityId", "2002"));
        assertThat(artifacts.dossiers()).hasSize(2);

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(new ObjectMapper().findAndRegisterModules());
        var composed = composer.compose("Resumo consolidado", artifacts);
        List<String> blockTypes = new java.util.ArrayList<>();
        composed.assistantContent().path("blocks").forEach(b -> blockTypes.add(b.path("type").asText()));
        assertThat(blockTypes).containsExactly("paragraph", "alert-list", "widget-block", "action-bar");
    }

    @ApiResource(
            value = "/api/human-resources/shared-persons",
            resourceKey = "hr.persons",
            title = "Persons",
            identity = @ResourceIdentity(
                    keyField = "id",
                    titleField = "fullName",
                    subtitleField = "departmentName",
                    metadataFields = {"departmentName"}))
    public static class SharedPersonsController
            extends AbstractResourceQueryController<NullableEmployeeDto, Long, EmployeeFilterDto> {
        private final NullableEmployeeController delegate = new NullableEmployeeController();
        private final BaseResourceQueryService<NullableEmployeeDto, Long, EmployeeFilterDto> service =
                new BaseResourceQueryService<>() {
                    @Override public String getIdFieldName() { return "id"; }
                    @Override public Sort getDefaultSort() { return Sort.by("fullName"); }
                    @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
                    @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
                    @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
                    @Override public boolean hasOptionSource(String k) { return false; }
                    @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
                    @Override public List<NullableEmployeeDto> findAll() { return delegate.getService().findAll(); }
                    @Override public List<NullableEmployeeDto> findAllById(Collection<Long> ids) { return delegate.getService().findAllById(ids); }
                    @Override public ResourceRepresentationResult<NullableEmployeeDto> findById(Long id) { return delegate.getService().findById(id); }
                    @Override public Page<NullableEmployeeDto> filter(EmployeeFilterDto f, Pageable p, Collection<Long> ids) {
                        if (f != null && f.getFullName() != null
                                && ("ana".equalsIgnoreCase(f.getFullName()) || "carlos".equalsIgnoreCase(f.getFullName()))) {
                            return new PageImpl<>(List.of(new NullableEmployeeDto(2002L, "Ana Carlos Civil")));
                        }
                        if (f != null && ("52998224725".equals(f.getTaxId()) || "529.982.247-25".equals(f.getTaxId()))) {
                            return new PageImpl<>(List.of(new NullableEmployeeDto(1001L, "Ana Beatriz Costa")));
                        }
                        return Page.empty();
                    }
                    @Override public CursorPage<NullableEmployeeDto> filterByCursor(EmployeeFilterDto f, Sort s, String a, String c, int l) { return null; }
                    @Override public java.util.OptionalLong locate(EmployeeFilterDto f, Sort s, Long id) { return java.util.OptionalLong.empty(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Long>> filterOptions(EmployeeFilterDto f, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<EmployeeFilterDto> r, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
                    @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<EmployeeFilterDto> r) { return null; }
                };

        @Override protected BaseResourceQueryService<NullableEmployeeDto, Long, EmployeeFilterDto> getService() { return service; }
        @Override protected Long getResponseId(NullableEmployeeDto dto) { return dto.getId(); }
    }

    @Test
    void suppressesParentMatchDuringSearchWhenSupersedingChildMatchesSameQuery() {
        StubEmployeeController assignmentsController = new StubEmployeeController();
        SharedPersonsController personsController = new SharedPersonsController();

        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(assignmentsController, personsController),
                List.of(ctx -> List.of(
                        new AgentEntityDescriptor(
                                "hr.assignments", "hr.assignments", "/api/human-resources/employees",
                                "Assignments", "Workforce assignments", "badge",
                                "id", "fullName", "departmentName", List.of("departmentName"),
                                "taxId", "fullName", null, "/assignments/{id}", "hr", 100,
                                "hr.persons", true),
                        new AgentEntityDescriptor(
                                "hr.persons", "hr.persons", "/api/human-resources/shared-persons",
                                "Persons", "Civil registry", "person",
                                "id", "fullName", "departmentName", List.of("departmentName"),
                                "taxId", "fullName", null, "/persons/{id}", "hr", 90,
                                null, true))),
                List.of(),
                List.of(),
                null,
                null,
                null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-1", "user-1", "prod", "user-1",
                Set.of("ROLE_USER"), null, null, Map.of(), Map.of());

        // Both hr.assignments and hr.persons match CPF Mod-11 -> hr.assignments supersedes hr.persons cleanly into SINGLE_MATCH
        List<AgentEntityMatch> taxMatches = bridge.searchEntities(
                ctx, new AgentEntitySearchRequest("529.982.247-25", "52998224725", true, List.of(), 5));
        assertThat(taxMatches)
                .hasSize(1)
                .extracting(AgentEntityMatch::entityKey)
                .containsExactly("hr.assignments");

        // Explicitly requesting hr.persons preserves it even when hr.assignments exists
        List<AgentEntityMatch> explicitPerson = bridge.searchEntities(
                ctx, new AgentEntitySearchRequest("529.982.247-25", "52998224725", true, List.of("hr.persons"), 5));
        assertThat(explicitPerson)
                .hasSize(1)
                .extracting(AgentEntityMatch::entityKey)
                .containsExactly("hr.persons");

        // When only hr.persons matches ("Carlos"), hr.persons is returned as fallback
        List<AgentEntityMatch> personOnly = bridge.searchEntities(
                ctx, new AgentEntitySearchRequest("Carlos", null, false, List.of(), 5));
        assertThat(personOnly)
                .hasSize(1)
                .extracting(AgentEntityMatch::entityKey)
                .containsExactly("hr.persons");
    }

    @Test
    void isolatesRollbackOnlyCandidateFailureDuringPolymorphicSearchAndPreservesActiveScreenOrder() {
        List<Integer> propagations = new java.util.ArrayList<>();
        java.util.Deque<java.util.concurrent.atomic.AtomicBoolean> txStack = new java.util.ArrayDeque<>();
        org.springframework.transaction.PlatformTransactionManager txManager =
                new org.springframework.transaction.PlatformTransactionManager() {
                    @Override
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        int prop = definition != null
                                ? definition.getPropagationBehavior()
                                : org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED;
                        propagations.add(prop);
                        boolean createNew = txStack.isEmpty()
                                || prop == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;
                        if (createNew) {
                            txStack.push(new java.util.concurrent.atomic.AtomicBoolean(false));
                        }
                        return new org.springframework.transaction.support.SimpleTransactionStatus(createNew) {
                            @Override
                            public void setRollbackOnly() {
                                super.setRollbackOnly();
                                if (!txStack.isEmpty()) {
                                    txStack.peek().set(true);
                                }
                            }

                            @Override
                            public boolean isRollbackOnly() {
                                return super.isRollbackOnly() || (!txStack.isEmpty() && txStack.peek().get());
                            }
                        };
                    }

                    @Override
                    public void commit(org.springframework.transaction.TransactionStatus status) {
                        boolean rollbackOnly = (!txStack.isEmpty() && txStack.peek().get()) || status.isRollbackOnly();
                        if (status.isNewTransaction() && !txStack.isEmpty()) {
                            txStack.pop();
                        }
                        if (rollbackOnly) {
                            throw new org.springframework.transaction.UnexpectedRollbackException(
                                    "Simulated shared TX rollback-only");
                        }
                    }

                    @Override
                    public void rollback(org.springframework.transaction.TransactionStatus status) {
                        if (status.isNewTransaction() && !txStack.isEmpty()) {
                            txStack.pop();
                        }
                    }
                };

        StubEmployeeController assignmentsController = new StubEmployeeController();
        SharedPersonsController personsController = new SharedPersonsController();
        AbstractResourceQueryController<NullableEmployeeDto, String, EmployeeFilterDto> failingUnitController =
                createStringIdFailingController(txStack);

        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(failingUnitController, assignmentsController, personsController),
                List.of(ctx -> List.of(
                        new AgentEntityDescriptor(
                                "hr.failing-units", "hr.failing-units", "/api/human-resources/failing-units",
                                "Failing Units", "Simulates rollback-only failure", "domain",
                                "id", "fullName", "departmentName", List.of("departmentName"),
                                null, "fullName", null, "/units/{id}", "hr", 95,
                                null, true),
                        new AgentEntityDescriptor(
                                "hr.assignments", "hr.assignments", "/api/human-resources/employees",
                                "Assignments", "Workforce assignments", "badge",
                                "id", "fullName", "departmentName", List.of("departmentName"),
                                "taxId", "fullName", null, "/assignments/{id}", "hr", 90,
                                null, true),
                        new AgentEntityDescriptor(
                                "hr.persons", "hr.persons", "/api/human-resources/shared-persons",
                                "Persons", "Civil registry", "person",
                                "id", "fullName", "departmentName", List.of("departmentName"),
                                "taxId", "fullName", null, "/persons/{id}", "hr", 50,
                                null, true))),
                List.of(),
                List.of(),
                null,
                null,
                null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                txManager);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-1", "user-1", "prod", "user-1",
                Set.of("ROLE_USER"), "/api/human-resources/shared-persons", null, Map.of(), Map.of());

        List<AgentEntityMatch> matches = bridge.searchEntities(
                ctx, new AgentEntitySearchRequest("Ana", null, false, List.of(), 5));

        assertThat(matches)
                .hasSize(2)
                .extracting(AgentEntityMatch::entityKey)
                .containsExactly("hr.persons", "hr.assignments");
        assertThat(matches.get(0).fromActiveScreenScope()).isTrue();
        assertThat(propagations)
                .contains(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(txStack).isEmpty();
    }

    @ApiResource(
            value = "/api/human-resources/failing-units",
            resourceKey = "hr.failing-units",
            title = "Failing Units",
            identity = @ResourceIdentity(
                    keyField = "id",
                    titleField = "fullName",
                    subtitleField = "departmentName",
                    metadataFields = {"departmentName"}))
    public static class FailingUnitController
            extends AbstractResourceQueryController<NullableEmployeeDto, String, EmployeeFilterDto> {
        private final BaseResourceQueryService<NullableEmployeeDto, String, EmployeeFilterDto> service;

        public FailingUnitController(java.util.Deque<java.util.concurrent.atomic.AtomicBoolean> txStack) {
            this.service = new BaseResourceQueryService<>() {
                @Override public String getIdFieldName() { return "id"; }
                @Override public Sort getDefaultSort() { return Sort.by("fullName"); }
                @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
                @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
                @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
                @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
                @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
                @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
                @Override public boolean hasOptionSource(String k) { return false; }
                @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
                @Override public List<NullableEmployeeDto> findAll() { return List.of(); }
                @Override public List<NullableEmployeeDto> findAllById(Collection<String> ids) { return List.of(); }
                @Override public ResourceRepresentationResult<NullableEmployeeDto> findById(String id) {
                    if (!txStack.isEmpty()) {
                        txStack.peek().set(true);
                    }
                    throw new IllegalStateException("Simulated String ID lookup failure");
                }
                @Override public Page<NullableEmployeeDto> filter(EmployeeFilterDto f, Pageable p, Collection<String> ids) {
                    if (!txStack.isEmpty()) {
                        txStack.peek().set(true);
                    }
                    throw new IllegalStateException("Simulated candidate filter failure");
                }
                @Override public CursorPage<NullableEmployeeDto> filterByCursor(EmployeeFilterDto f, Sort s, String a, String c, int l) { return null; }
                @Override public java.util.OptionalLong locate(EmployeeFilterDto f, Sort s, String id) { return java.util.OptionalLong.empty(); }
                @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<String>> filterOptions(EmployeeFilterDto f, Pageable p) { return Page.empty(); }
                @Override public List<org.praxisplatform.uischema.dto.OptionDTO<String>> byIdsOptions(Collection<String> ids) { return List.of(); }
                @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<EmployeeFilterDto> r, Pageable p) { return Page.empty(); }
                @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
                @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<EmployeeFilterDto> r) { return null; }
                @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<EmployeeFilterDto> r) { return null; }
                @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<EmployeeFilterDto> r) { return null; }
                @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<EmployeeFilterDto> r) { return null; }
            };
        }

        @Override protected BaseResourceQueryService<NullableEmployeeDto, String, EmployeeFilterDto> getService() { return service; }
        @Override protected String getResponseId(NullableEmployeeDto dto) { return dto.getId() != null ? String.valueOf(dto.getId()) : null; }
    }

    private static AbstractResourceQueryController<NullableEmployeeDto, String, EmployeeFilterDto> createStringIdFailingController(
            java.util.Deque<java.util.concurrent.atomic.AtomicBoolean> txStack) {
        return new FailingUnitController(txStack);
    }

    public static class CuratedRestrictedDto {
        public Long id;
        public String title;

        @UISchema(
                label = "Status funcional",
                quickViewVisible = true,
                quickViewOrder = 10,
                editableForAuthorities = {"HR_WRITE"},
                optionItems = {@org.praxisplatform.uischema.annotation.UiOption(value = "AT", label = "Ativo no órgão")})
        public String statusCode;

        @UISchema(
                label = "Em exercício",
                quickViewVisible = true,
                quickViewOrder = 20,
                labelTrue = "Sim, em exercício",
                labelFalse = "Afastado",
                editableForAuthorities = {"HR_WRITE"})
        public Boolean active;

        @UISchema(label = "Segredo interno", hidden = true, editableForAuthorities = {"HR_WRITE"})
        public String internalCode;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('HR_SECRET')")
    @ApiResource(
            value = "/api/human-resources/protected-curated",
            resourceKey = "hr.protected-curated",
            title = "Protected Curated")
    public static class ProtectedCuratedController
            extends AbstractResourceQueryController<CuratedRestrictedDto, Long, EmployeeFilterDto> {
        private final BaseResourceQueryService<CuratedRestrictedDto, Long, EmployeeFilterDto> service =
                new BaseResourceQueryService<>() {
                    @Override public String getIdFieldName() { return "id"; }
                    @Override public Sort getDefaultSort() { return Sort.by("title"); }
                    @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
                    @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
                    @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
                    @Override public boolean hasOptionSource(String k) { return false; }
                    @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
                    @Override public List<CuratedRestrictedDto> findAll() { return List.of(); }
                    @Override public List<CuratedRestrictedDto> findAllById(Collection<Long> ids) { return List.of(); }
                    @Override public ResourceRepresentationResult<CuratedRestrictedDto> findById(Long id) { return null; }
                    @Override public Page<CuratedRestrictedDto> filter(EmployeeFilterDto f, Pageable p, Collection<Long> ids) { return Page.empty(); }
                    @Override public CursorPage<CuratedRestrictedDto> filterByCursor(EmployeeFilterDto f, Sort s, String a, String c, int l) { return null; }
                    @Override public java.util.OptionalLong locate(EmployeeFilterDto f, Sort s, Long id) { return java.util.OptionalLong.empty(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Long>> filterOptions(EmployeeFilterDto f, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<EmployeeFilterDto> r, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
                    @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<EmployeeFilterDto> r) { return null; }
                    @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<EmployeeFilterDto> r) { return null; }
                };

        @Override protected BaseResourceQueryService<CuratedRestrictedDto, Long, EmployeeFilterDto> getService() { return service; }
        @Override protected Long getResponseId(CuratedRestrictedDto dto) { return dto.id; }
    }

    @Test
    void enforcesControllerPreAuthorizeEditableForAuthoritiesCuratedUiSchemaLabelsAndDropsEmptyRelatedDatasets() {
        ProtectedCuratedController controller = new ProtectedCuratedController();
        DefaultMetadataGovernanceAgentAuthorizationBridge authBridge =
                new DefaultMetadataGovernanceAgentAuthorizationBridge(List.of(controller));

        AgentExecutionContext readerOnly = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "t", "u", "prod", "u", Set.of("HR_READ"), null, null, Map.of(), Map.of());
        AgentExecutionContext secretNoWrite = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "t", "u", "prod", "u", Set.of("HR_SECRET"), null, null, Map.of(), Map.of());
        AgentExecutionContext secretWithWrite = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "t", "u", "prod", "u", Set.of("HR_SECRET", "HR_WRITE"), null, null, Map.of(), Map.of());

        assertThat(authBridge.canReadResource(readerOnly, "/api/human-resources/protected-curated")).isFalse();
        assertThat(authBridge.canReadResource(secretNoWrite, "/api/human-resources/protected-curated")).isTrue();
        assertThat(authBridge.canInvokeAction(secretNoWrite, "/api/human-resources/protected-curated", "update", 1L)).isFalse();
        assertThat(authBridge.canInvokeAction(secretWithWrite, "/api/human-resources/protected-curated", "update", 1L)).isTrue();

        AgentEntityDescriptor descriptor = new AgentEntityDescriptor(
                "hr.curated", "hr.protected-curated", "/api/human-resources/protected-curated",
                "Curated", "Curated entity", "badge", "id", "title", null, List.of(),
                null, null, null, "/curated/{id}", "hr", 100);
        List<org.praxisplatform.config.ai.agent.spi.AgentFieldDescriptor> fieldDescriptors = List.of(
                new org.praxisplatform.config.ai.agent.spi.AgentFieldDescriptor(
                        "statusCode", "Status funcional", 10, true, 10, false, false, "", "", Map.of("AT", "Ativo no órgão")),
                new org.praxisplatform.config.ai.agent.spi.AgentFieldDescriptor(
                        "active", "Em exercício", 20, true, 20, false, false, "Sim, em exercício", "Afastado", Map.of()),
                new org.praxisplatform.config.ai.agent.spi.AgentFieldDescriptor(
                        "internalCode", "Segredo interno", 30, false, 30, true, true, "", "", Map.of()));
        AgentEntityDossier dossier = new AgentEntityDossier(
                descriptor, "1", "Helena", "Cargo",
                Map.of("id", 1L, "title", "Helena", "statusCode", "AT", "active", true, "internalCode", "XYZ"),
                Map.of("id", 1L, "title", "Helena", "statusCode", "AT", "active", true, "internalCode", "XYZ"),
                List.of(), List.of(),
                List.of(new org.praxisplatform.config.ai.agent.spi.AgentRelatedDataset(
                        "empty-child", "Vazia", "hr.child", "/api/child", "parentId", 0L, List.of(), List.of())),
                List.of(), List.of(), List.of(), fieldDescriptors);

        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        artifacts.recordDossier(dossier);

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                new ObjectMapper().findAndRegisterModules());
        var composed = composer.compose("Síntese executiva.", artifacts);
        com.fasterxml.jackson.databind.JsonNode blocks = composed.assistantContent().path("blocks");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("widget-block");

        com.fasterxml.jackson.databind.JsonNode fields =
                blocks.get(1).path("widget").path("inputs").path("document").path("nodes").get(0).path("fields");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).path("label").asText()).isEqualTo("Status funcional");
        assertThat(fields.get(0).path("value").asText()).isEqualTo("Ativo no órgão");
        assertThat(fields.get(1).path("label").asText()).isEqualTo("Em exercício");
        assertThat(fields.get(1).path("value").asText()).isEqualTo("Sim, em exercício");
    }

    @CalculationMemory(
            title = "Composição financeira",
            trails = {
                @CalculationTrail(
                        id = "net-pay",
                        title = "Salário líquido",
                        ruleCode = "WF-PAY-NET",
                        formula = "Bruto - Descontos",
                        baseLabel = "Bruto",
                        baseExpr = "row.grossAmount",
                        baseFormat = "currency:BRL",
                        deductionLabel = "Descontos",
                        deductionExpr = "row.deductionAmount",
                        deductionFormat = "currency:BRL",
                        resultLabel = "Líquido",
                        resultExpr = "row.grossAmount - row.deductionAmount",
                        resultFormat = "currency:BRL")
            })
    public static class PayrollSlipDto {
        public Long id = 77L;
        @UISchema(label = "Folha")
        public String label = "Competência 2026-03";
        @UISchema(label = "Situação", optionItems = {@org.praxisplatform.uischema.annotation.UiOption(value = "OPEN", label = "Aberta")})
        public String status = "OPEN";
        public java.math.BigDecimal grossAmount = new java.math.BigDecimal("8000.00");
        public java.math.BigDecimal deductionAmount = new java.math.BigDecimal("1200.00");
        public Long getId() { return id; }
        public String getLabel() { return label; }
        public String getStatus() { return status; }
        public java.math.BigDecimal getGrossAmount() { return grossAmount; }
        public java.math.BigDecimal getDeductionAmount() { return deductionAmount; }
    }

    public static class PayrollSlipFilter implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        private String status;
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    @ApiResource(
            value = "/api/payroll/slips",
            resourceKey = "payroll.slips",
            title = "Lançamentos financeiros",
            identity = @ResourceIdentity(keyField = "id", titleField = "label", subtitleField = "status"),
            quickFilters = {
                @QuickFilter(id = "open-slips", label = "Abertas", description = "Somente abertas",
                        criteria = {@FilterCriterion(field = "status", values = {"OPEN"})})
            })
    @ResourceFact(key = "netPayFact", label = "Valor líquido", dataType = "currency", description = "Líquido apurado")
    public static class PayrollSlipController extends AbstractResourceQueryController<PayrollSlipDto, Long, PayrollSlipFilter> {
        private final PayrollSlipDto fixture = new PayrollSlipDto();
        private final StatsFieldRegistry statsRegistry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("status", "status", Set.of(StatsMetric.COUNT, StatsMetric.SUM)))
                .add(StatsFieldDescriptor.timeSeriesField("payDate", "payDate"))
                .add(StatsFieldDescriptor.metricField("grossAmount", "grossAmount", Set.of(StatsMetric.SUM, StatsMetric.AVG)))
                .build();

        private final BaseResourceQueryService<PayrollSlipDto, Long, PayrollSlipFilter> service = new BaseResourceQueryService<>() {
            @Override public String getIdFieldName() { return "id"; }
            @Override public Sort getDefaultSort() { return Sort.by("id"); }
            @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
            @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.AUTO; }
            @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.AUTO; }
            @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.AUTO; }
            @Override public StatsFieldRegistry getStatsFieldRegistry() { return statsRegistry; }
            @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
            @Override public boolean hasOptionSource(String k) { return false; }
            @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
            @Override public List<PayrollSlipDto> findAll() { return List.of(fixture); }
            @Override public List<PayrollSlipDto> findAllById(Collection<Long> ids) { return List.of(fixture); }
            @Override public ResourceRepresentationResult<PayrollSlipDto> findById(Long id) {
                return Long.valueOf(77L).equals(id) ? ResourceRepresentationResult.unversioned(fixture) : null;
            }
            @Override public Page<PayrollSlipDto> filter(PayrollSlipFilter f, Pageable p, Collection<Long> ids) {
                return new PageImpl<>(List.of(fixture));
            }
            @Override public CursorPage<PayrollSlipDto> filterByCursor(PayrollSlipFilter f, Sort s, String a, String c, int l) { return null; }
            @Override public java.util.OptionalLong locate(PayrollSlipFilter f, Sort s, Long id) { return java.util.OptionalLong.empty(); }
            @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Long>> filterOptions(PayrollSlipFilter f, Pageable p) { return Page.empty(); }
            @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
            @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<PayrollSlipFilter> r, Pageable p) { return Page.empty(); }
            @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
            @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<PayrollSlipFilter> r) {
                return new GroupByStatsResponse(r.field(), r.metric(),
                        List.of(new GroupByBucket("OPEN", "OPEN", 12, 12)));
            }
            @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<PayrollSlipFilter> r) {
                return new TimeSeriesStatsResponse(r.field(), r.granularity(), r.metric(),
                        List.of(new TimeSeriesPoint(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), "2026-03", new java.math.BigDecimal("8000"), 1)));
            }
            @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<PayrollSlipFilter> r) {
                return new DistributionStatsResponse(r.field(), r.mode(), r.metric(),
                        List.of(new DistributionBucket(null, null, "OPEN", "OPEN", 12, 12)));
            }
            @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<PayrollSlipFilter> r) { return null; }
        };

        @Override protected BaseResourceQueryService<PayrollSlipDto, Long, PayrollSlipFilter> getService() { return service; }
        @Override protected Long getResponseId(PayrollSlipDto dto) { return dto.id; }
    }

    @Test
    void enrichesDigestExecutesPhase2ToolsAndComposesChartCalcAndKnowledgeBlocks() {
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new PayrollSlipController()),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "payroll.slips", "payroll.slips", "/api/payroll/slips",
                        "Lançamentos financeiros", "Folha salarial", "payments",
                        "id", "label", "status", List.of("status"),
                        null, "label", null, "/payroll/{id}", "payroll", 90))),
                List.of(),
                List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(List.of(new PayrollSlipController())),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "user-a", "prod", "user-a", Set.of("READ"), null, null, Map.of(), Map.of());

        var digest = bridge.describeEntitySchema(ctx, "payroll.slips").orElseThrow();
        assertThat(digest.statsCapability()).isNotNull();
        assertThat(digest.statsCapability().fields()).hasSize(3);
        assertThat(digest.quickFilters()).extracting(q -> q.id()).containsExactly("open-slips");
        assertThat(digest.resourceFacts()).extracting(f -> f.key()).containsExactly("netPayFact");
        assertThat(digest.calculationMemoryAvailable()).isTrue();
        assertThat(digest.calculationTrailIds()).containsExactly("net-pay");

        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("Analise a folha");

        var statsTool = new AggregateResourceStatsTool(bridge);
        Map<String, Object> statsOut = statsTool.execute(ctx, vault, artifacts, Map.of(
                "entityKey", "payroll.slips",
                "operationKind", "GROUP_BY",
                "field", "status"));
        assertThat(statsOut.get("chartKind")).isEqualTo("donut");
        assertThat(statsOut.get("totalElements")).isEqualTo(12L);

        var tsResult = bridge.aggregateStats(ctx, new AgentStatsAggregationRequest(
                "payroll.slips", "TIMESERIES", "payDate", "SUM", "grossAmount", "MONTH", null, null, null, Map.of(), 10));
        assertThat(tsResult.chartKind()).isEqualTo("line");
        assertThat(tsResult.buckets()).hasSize(1);

        var calcTool = new ExplainCalculationMemoryTool(bridge);
        Map<String, Object> calcOut = calcTool.execute(ctx, vault, artifacts, Map.of(
                "entityKey", "payroll.slips",
                "entityId", "77"));
        assertThat(calcOut.get("found")).isEqualTo(true);

        var kbTool = new SearchDomainKnowledgeAndRulesTool(List.of((c, q, t, s, l) -> List.of(
                new AgentKnowledgeDocument("statute-1", "STATUTE", "Teto Remuneratório", "Art. 37, XI, CF/88",
                        "Geral", "Nenhum subsídio ou remuneração excederá o teto constitucional.", List.of("teto"), 0.95d, null, Map.of()))));
        Map<String, Object> kbOut = kbTool.execute(ctx, vault, artifacts, Map.of("query", "teto"));
        assertThat(kbOut.get("count")).isEqualTo(1);

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(new ObjectMapper().findAndRegisterModules());
        var composed = composer.compose("Análise concluída.", artifacts);
        com.fasterxml.jackson.databind.JsonNode blocks = composed.assistantContent().path("blocks");
        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("calculation-memory");
        assertThat(blocks.get(1).path("trails").get(0).path("resultFormatted").asText()).isEqualTo("R$ 6.800,00");
        assertThat(blocks.get(2).path("type").asText()).isEqualTo("widget-block");
        assertThat(blocks.get(2).path("widgetType").asText()).isEqualTo("praxis-chart");
        assertThat(blocks.get(2).path("widget").path("inputs").path("chartDocument").path("source").path("kind").asText()).isEqualTo("derived");
        assertThat(blocks.get(2).path("widget").path("inputs").path("chartDocument").has("title")).isFalse();
        assertThat(blocks.get(2).path("widget").path("inputs").path("config").has("title")).isFalse();
        assertThat(blocks.get(2).path("subtitle").asText()).startsWith("Quantidade por ");
        assertThat(blocks.get(3).path("type").asText()).isEqualTo("knowledge-citations");
    }

    public static class GovernedStatsDto {
        public Long id = 1L;
        @UISchema(label = "Situação")
        public String status = "ACTIVE";
        @UISchema(label = "Remuneração Restrita", visibleForAuthorities = {"FINANCE_ADMIN"})
        public java.math.BigDecimal restrictedSalary = new java.math.BigDecimal("10000.00");
        @DomainGovernance(classification = DomainClassification.RESTRICTED,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.DENY))
        @UISchema(label = "Fator Confidencial")
        public java.math.BigDecimal aiDeniedFactor = new java.math.BigDecimal("1.25");

        public Long getId() { return id; }
        public String getStatus() { return status; }
        public java.math.BigDecimal getRestrictedSalary() { return restrictedSalary; }
        public java.math.BigDecimal getAiDeniedFactor() { return aiDeniedFactor; }
    }

    @ApiResource(
            value = "/api/payroll/governed-stats",
            resourceKey = "payroll.governed-stats",
            title = "Estatísticas Governadas",
            identity = @ResourceIdentity(keyField = "id", titleField = "status"))
    public static class GovernedStatsController extends AbstractResourceQueryController<GovernedStatsDto, Long, PayrollSlipFilter> {
        private final StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("status", "status", Set.of(StatsMetric.COUNT)))
                .add(StatsFieldDescriptor.metricField("restrictedSalary", "restrictedSalary", Set.of(StatsMetric.SUM)))
                .add(StatsFieldDescriptor.metricField("aiDeniedFactor", "aiDeniedFactor", Set.of(StatsMetric.AVG)))
                .build();
        private final BaseResourceQueryService<GovernedStatsDto, Long, PayrollSlipFilter> service = new BaseResourceQueryService<>() {
            @Override public String getIdFieldName() { return "id"; }
            @Override public Sort getDefaultSort() { return Sort.by("id"); }
            @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
            @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.AUTO; }
            @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
            @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
            @Override public StatsFieldRegistry getStatsFieldRegistry() { return registry; }
            @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
            @Override public boolean hasOptionSource(String k) { return false; }
            @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
            @Override public List<GovernedStatsDto> findAll() { return List.of(); }
            @Override public List<GovernedStatsDto> findAllById(Collection<Long> ids) { return List.of(); }
            @Override public ResourceRepresentationResult<GovernedStatsDto> findById(Long id) { return null; }
            @Override public Page<GovernedStatsDto> filter(PayrollSlipFilter f, Pageable p, Collection<Long> ids) { return Page.empty(); }
            @Override public CursorPage<GovernedStatsDto> filterByCursor(PayrollSlipFilter f, Sort s, String a, String c, int l) { return null; }
            @Override public java.util.OptionalLong locate(PayrollSlipFilter f, Sort s, Long id) { return java.util.OptionalLong.empty(); }
            @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Long>> filterOptions(PayrollSlipFilter f, Pageable p) { return Page.empty(); }
            @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
            @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<PayrollSlipFilter> r, Pageable p) { return Page.empty(); }
            @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
            @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<PayrollSlipFilter> r) {
                return new GroupByStatsResponse(r.field(), r.metric(),
                        List.of(new GroupByBucket("ACTIVE", "ACTIVE", 3, 3)));
            }
            @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<PayrollSlipFilter> r) { return null; }
            @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<PayrollSlipFilter> r) { return null; }
            @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<PayrollSlipFilter> r) { return null; }
        };
        @Override protected BaseResourceQueryService<GovernedStatsDto, Long, PayrollSlipFilter> getService() { return service; }
        @Override protected Long getResponseId(GovernedStatsDto dto) { return dto.id; }
    }

    @Test
    void enforcesStatsFieldGovernanceScopesDiscoveryAndTokenizesDomainRules() {
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new GovernedStatsController(), new PayrollSlipController()),
                List.of(),
                List.of(),
                List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(
                        List.of(new GovernedStatsController(), new PayrollSlipController())),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        AgentExecutionContext standardCtx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "user-a", "prod", "user-a", Set.of("READ"), null, null, Map.of(), Map.of());

        var digest = bridge.describeEntitySchema(standardCtx, "payroll.governed-stats").orElseThrow();
        assertThat(digest.statsCapability().fields())
                .extracting(f -> f.field())
                .containsExactly("status");

        assertThatThrownBy(() -> bridge.aggregateStats(standardCtx, new AgentStatsAggregationRequest(
                "payroll.governed-stats", "GROUP_BY", "restrictedSalary", "COUNT", null, null, null, null, null, Map.of(), 10)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> bridge.aggregateStats(standardCtx, new AgentStatsAggregationRequest(
                "payroll.governed-stats", "GROUP_BY", "status", "AVG", "aiDeniedFactor", null, null, null, null, Map.of(), 10)))
                .isInstanceOf(SecurityException.class);

        var okStats = bridge.aggregateStats(standardCtx, new AgentStatsAggregationRequest(
                "payroll.governed-stats", "GROUP_BY", "status", "COUNT", null, null, null, null, null, Map.of(), 10));
        assertThat(okStats.subtitle()).isEqualTo("Quantidade por Situação");

        // Discover tool with entityKey and blank topicOrQuery scopes entities list cleanly
        var discoverTool = new DiscoverTopicResourcesAndSchemasTool(bridge);
        Map<String, Object> discoveryOut = discoverTool.execute(
                standardCtx,
                null,
                new AgentTurnArtifactStore(),
                Map.of("entityKey", "payroll.slips"));
        @SuppressWarnings("unchecked")
        List<AgentEntityDescriptor> scopedEntities = (List<AgentEntityDescriptor>) discoveryOut.get("entities");
        assertThat(scopedEntities).extracting(AgentEntityDescriptor::resourceKey).containsExactly("payroll.slips");

        // ConfigDomainRuleKnowledgeContributor tokenized fallback for natural-language query
        DomainRuleDefinitionRepository ruleRepo = mock(DomainRuleDefinitionRepository.class);
        DomainRuleDefinition marginRule = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("prod")
                .ruleKey("wf-payroll-consignable-margin")
                .version(2)
                .ruleType("business_constraint")
                .status("PUBLISHED")
                .contextKey("payroll.margin.ceiling")
                .resourceKey("payroll.slips")
                .semanticOwner("HR Core")
                .build();
        when(ruleRepo.searchCatalogCandidates(eq("tenant-a"), eq("prod"), eq("consignable margin"), any(), any(), any(), any()))
                .thenReturn(Page.empty());
        when(ruleRepo.searchCatalogCandidates(eq("tenant-a"), eq("prod"), eq(""), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(marginRule)));
        ConfigDomainRuleKnowledgeContributor kbContributor = new ConfigDomainRuleKnowledgeContributor(ruleRepo, null);
        List<AgentKnowledgeDocument> matched = kbContributor.searchKnowledge(
                standardCtx, "consignable margin", null, "DOMAIN_RULE", 5);
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).title()).isEqualTo("wf-payroll-consignable-margin");

        // Tokenized match should also work even when partial query tokens match hyphenated ruleKey
        when(ruleRepo.searchCatalogCandidates(eq("tenant-a"), eq("prod"), eq("payroll limit margin"), any(), any(), any(), any()))
                .thenReturn(Page.empty());
        List<AgentKnowledgeDocument> tokenMatched = kbContributor.searchKnowledge(
                standardCtx, "payroll limit margin", null, "DOMAIN_RULE", 5);
        assertThat(tokenMatched).hasSize(1);
        assertThat(tokenMatched.get(0).score()).isGreaterThan(0.55d);
    }

    public record LeaveRequestDto(
            Long id,
            @UISchema(label = "Colaborador") String employeeName,
            @UISchema(label = "Situação") String status,
            @UISchema(label = "Dias solicitados") Integer daysRequested
    ) {
    }

    public static class LeavePreviewDraft {
        @jakarta.validation.constraints.NotNull
        private Long periodId;
        private Integer daysRequested;
        public Long getPeriodId() { return periodId; }
        public void setPeriodId(Long periodId) { this.periodId = periodId; }
        public Integer getDaysRequested() { return daysRequested; }
        public void setDaysRequested(Integer daysRequested) { this.daysRequested = daysRequested; }
    }

    public static class LeavePreviewResult {
        @UISchema(label = "Saldo remanescente")
        private Integer remainingBalance;
        @DomainGovernance(classification = DomainClassification.RESTRICTED, aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.DENY))
        private Integer internalAuditScore;
        public LeavePreviewResult() {}
        public LeavePreviewResult(Integer remainingBalance, Integer internalAuditScore) {
            this.remainingBalance = remainingBalance;
            this.internalAuditScore = internalAuditScore;
        }
        public Integer getRemainingBalance() { return remainingBalance; }
        public Integer getInternalAuditScore() { return internalAuditScore; }
    }

    public static class ApproveLeaveInput {
        @NotBlank(message = "Justificativa é obrigatória")
        @UISchema(label = "Observações", controlType = FieldControlType.TEXTAREA)
        private String notes;
        @UISchema(label = "Alçada especial", editableForAuthorities = {"HR_ADMIN"})
        private String specialApprovalCode;
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public String getSpecialApprovalCode() { return specialApprovalCode; }
        public void setSpecialApprovalCode(String specialApprovalCode) { this.specialApprovalCode = specialApprovalCode; }
    }

    @ApiResource(
            value = "/api/workforce/leave-requests",
            resourceKey = "workforce.leave-requests",
            title = "Solicitações de Afastamento",
            identity = @ResourceIdentity(keyField = "id", titleField = "employeeName", subtitleField = "status"))
    public static class LeaveRequestWorkflowController
            extends AbstractResourceQueryController<LeaveRequestDto, Long, PayrollSlipFilter> {

        private final ResourceVersionEtagService etags;
        private final ResourceVersionScopeProvider scopes = () -> ResourceVersionScope.GLOBAL;
        private final AtomicInteger version = new AtomicInteger(1);
        private final AtomicBoolean simulatedRollbackObserved = new AtomicBoolean(false);
        private volatile LeaveRequestDto current = new LeaveRequestDto(701L, "Ana Paula Martins", "REQUESTED", 10);

        LeaveRequestWorkflowController(ResourceVersionEtagService etags) {
            this.etags = etags;
            configureResourceVersioning(etags, scopes);
        }

        private final BaseResourceQueryService<LeaveRequestDto, Long, PayrollSlipFilter> service =
                new BaseResourceQueryService<>() {
                    @Override public String getIdFieldName() { return "id"; }
                    @Override public Sort getDefaultSort() { return Sort.by("id"); }
                    @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
                    @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
                    @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
                    @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
                    @Override public boolean hasOptionSource(String k) { return false; }
                    @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
                    @Override public List<LeaveRequestDto> findAll() { return List.of(current); }
                    @Override public List<LeaveRequestDto> findAllById(Collection<Long> ids) { return List.of(current); }
                    @Override public ResourceRepresentationResult<LeaveRequestDto> findById(Long id) {
                        return Long.valueOf(701L).equals(id)
                                ? ResourceRepresentationResult.versioned(current, version.get())
                                : null;
                    }
                    @Override public Page<LeaveRequestDto> filter(PayrollSlipFilter f, Pageable p, Collection<Long> ids) {
                        return new PageImpl<>(List.of(current));
                    }
                    @Override public CursorPage<LeaveRequestDto> filterByCursor(PayrollSlipFilter f, Sort s, String a, String c, int l) { return null; }
                    @Override public java.util.OptionalLong locate(PayrollSlipFilter f, Sort s, Long id) { return java.util.OptionalLong.empty(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Long>> filterOptions(PayrollSlipFilter f, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
                    @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<PayrollSlipFilter> r, Pageable p) { return Page.empty(); }
                    @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
                    @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<PayrollSlipFilter> r) { return null; }
                    @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<PayrollSlipFilter> r) { return null; }
                    @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<PayrollSlipFilter> r) { return null; }
                    @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<PayrollSlipFilter> r) { return null; }
                };

        @PostMapping("/determinations/preview")
        public ResponseEntity<LeavePreviewResult> preview(@jakarta.validation.Valid @RequestBody LeavePreviewDraft draft) {
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                simulatedRollbackObserved.set(true);
            }
            int remaining = 30 - (draft.getDaysRequested() != null ? draft.getDaysRequested() : 0);
            return ResponseEntity.ok(new LeavePreviewResult(remaining, 999));
        }

        @PostMapping("/{id}/actions/approve-leave")
        @WorkflowAction(
                id = "approve-leave",
                title = "Aprovar afastamento",
                description = "Aprova formalmente a parcela programada",
                scope = ActionScope.ITEM,
                requiredAuthorities = {"WORKFORCE_WRITE"},
                allowedStates = {"REQUESTED"},
                riskLevel = ActionRiskLevel.HIGH,
                confirmationRequired = true,
                resourceVersion = ActionRequirement.REQUIRED)
        public ResponseEntity<LeaveRequestDto> approveLeave(
                @PathVariable Long id,
                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                @jakarta.validation.Valid @RequestBody ApproveLeaveInput input) {
            ResourceVersionPreconditions.requireMatch(
                    etags, ifMatch, ResourceVersionScope.GLOBAL, "workforce.leave-requests", id, version.get());
            if ("RULE_BLOCK".equalsIgnoreCase(input.getNotes())) {
                throw new PraxisRuleViolationException(List.of(new SlotViolationDetail(
                        "WF-LEAVE-001", "approval.guard", "default", DecisionSource.PRODUCT,
                        DecisionStage.DOMAIN_DECISION, RuleDecision.DENY, "notes",
                        "Parcela conflita com escala do setor.", null, "BUSINESS_LOGIC")));
            }
            long nextVer = version.incrementAndGet();
            current = new LeaveRequestDto(id, current.employeeName(), "APPROVED", current.daysRequested());
            return ResponseEntity.ok()
                    .eTag(etags.create(ResourceVersionScope.GLOBAL, "workforce.leave-requests", id, nextVer))
                    .body(current);
        }

        @Override protected BaseResourceQueryService<LeaveRequestDto, Long, PayrollSlipFilter> getService() { return service; }
        @Override protected Long getResponseId(LeaveRequestDto dto) { return dto.id(); }
    }

    @Test
    void simulatesDeterminationRollbackOnlyAndPreparesExecutesActionWithVersioningAndRules() {
        ResourceVersionEtagService etags = new ResourceVersionEtagService("unit-test-secret-0123456789abcdef012345");
        ResourceVersionScopeProvider scopeProvider = () -> ResourceVersionScope.GLOBAL;
        LeaveRequestWorkflowController controller = new LeaveRequestWorkflowController(etags);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        AtomicBoolean lastStatusRollback = new AtomicBoolean(false);
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition != null && definition.isReadOnly());
                return new SimpleTransactionStatus();
            }
            @Override
            public void commit(TransactionStatus status) {
                lastStatusRollback.set(status.isRollbackOnly());
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
            @Override
            public void rollback(TransactionStatus status) {
                lastStatusRollback.set(true);
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
        };

        AgentActionPreparationContributor orgContributor = (ctx, entity, entityId, actionId, raw, inputs) -> {
            if (Boolean.TRUE.equals(ctx.propagatedClaims().get("readOnlyScope"))) {
                return AgentActionPreparationContributor.ActionGuardAssessment.block(
                        "READ_ONLY_CONSOLIDATION_SCOPE",
                        "Grupo consolidado em modo somente relatórios.",
                        "02",
                        "Org 02");
            }
            return AgentActionPreparationContributor.ActionGuardAssessment.allow("02", "Org 02", Map.of());
        };

        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(controller),
                List.of(), List.of(), List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(List.of(controller)),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                txManager,
                List.of(),
                null,
                List.of(),
                null,
                etags,
                scopeProvider,
                validator,
                List.of(orgContributor));

        AgentExecutionContext writerCtx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "op-1", "prod", "op-1",
                Set.of("WORKFORCE_READ", "WORKFORCE_WRITE"),
                null, null, Map.of(), Map.of());
        AgentExecutionContext readerCtx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "reader-1", "prod", "reader-1",
                Set.of("WORKFORCE_READ"),
                null, null, Map.of(), Map.of());

        var digest = bridge.describeEntitySchema(writerCtx, "workforce.leave-requests").orElseThrow();
        assertThat(digest.determinations()).hasSize(1);
        assertThat(digest.determinations().get(0).id()).isEqualTo("preview");

        // 1. Simulate determination (non-persistent rollback-only)
        AgentSimulationPreview sim = bridge.simulateDetermination(
                writerCtx,
                new AgentDeterminationRequest("workforce.leave-requests", "preview", Map.of("periodId", 10L, "daysRequested", 12)));
        assertThat(controller.simulatedRollbackObserved.get()).isTrue();
        assertThat(lastStatusRollback.get()).isTrue();
        assertThat(sim.uiPreview()).containsEntry("remainingBalance", 18).containsEntry("internalAuditScore", 999);
        assertThat(sim.modelSafePreview()).containsEntry("remainingBalance", 18).doesNotContainKey("internalAuditScore");

        // 2. Prepare action with read-only consolidation claim -> blocked
        AgentExecutionContext readOnlyCtx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "op-1", "prod", "op-1",
                Set.of("WORKFORCE_WRITE"),
                null, null, Map.of(), Map.of("readOnlyScope", true));
        AgentPreparedAction blockedPrep = bridge.prepareAction(
                readOnlyCtx,
                new AgentPrepareActionRequest("workforce.leave-requests", "701", "approve-leave", Map.of()));
        assertThat(blockedPrep.allowed()).isFalse();
        assertThat(blockedPrep.blockedCode()).isEqualTo("READ_ONLY_CONSOLIDATION_SCOPE");
        assertThat(blockedPrep.organizationId()).isEqualTo("02");

        // 3. Prepare action for authorized writer -> captures ETag & missing required field
        AgentPreparedAction prepared = bridge.prepareAction(
                writerCtx,
                new AgentPrepareActionRequest("workforce.leave-requests", "701", "approve-leave", Map.of()));
        assertThat(prepared.allowed()).isTrue();
        assertThat(prepared.expectedVersionTag()).isNotBlank();
        assertThat(prepared.recordLifecycleState()).isEqualTo("REQUESTED");
        assertThat(prepared.missingRequiredFields()).containsExactly("notes");

        // 4. Reader cannot confirm
        AgentActionExecutionOutcome denied = bridge.executeConfirmedAction(
                readerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-1", "02", Map.of("notes", "OK")));
        assertThat(denied.httpStatus()).isEqualTo(403);

        // 5. Missing @NotBlank notes -> 422 fieldErrors
        AgentActionExecutionOutcome validationErr = bridge.executeConfirmedAction(
                writerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-2", "02", Map.of()));
        assertThat(validationErr.httpStatus()).isEqualTo(422);
        assertThat(validationErr.fieldErrors()).containsKey("notes");

        // 6. Rule violation -> 422 ruleViolations
        AgentActionExecutionOutcome ruleErr = bridge.executeConfirmedAction(
                writerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-3", "02", Map.of("notes", "RULE_BLOCK")));
        assertThat(ruleErr.httpStatus()).isEqualTo(422);
        assertThat(ruleErr.ruleViolations()).hasSize(1);

        // 7. Concurrent version bump -> 412 STALE with refreshedSnapshot
        controller.version.incrementAndGet();
        AgentActionExecutionOutcome stale = bridge.executeConfirmedAction(
                writerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-4", "02", Map.of("notes", "Aprovado")));
        assertThat(stale.httpStatus()).isEqualTo(412);
        assertThat(stale.outcomeCode()).isEqualTo("STALE_RESOURCE_VERSION");
        assertThat(stale.updatedVersionTag()).isNotBlank().isNotEqualTo(prepared.expectedVersionTag());
        assertThat(stale.refreshedSnapshot()).containsKey("record");

        // 8. Execute with current ETag -> 200 CONFIRMED & state APPROVED
        AgentActionExecutionOutcome ok = bridge.executeConfirmedAction(
                writerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        stale.updatedVersionTag(), "k-5", "02", Map.of("notes", "Escala homologada")));
        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(ok.updatedLifecycleState()).isEqualTo("APPROVED");
    }

    @Test
    void enforcesFailClosedSimulationNestedTxReuseOrgScopeFieldEditAndOutboundMod11Scrubbing() {
        ResourceVersionEtagService etags = new ResourceVersionEtagService("unit-test-secret-0123456789abcdef012345");
        ResourceVersionScopeProvider scopeProvider = () -> ResourceVersionScope.GLOBAL;
        LeaveRequestWorkflowController workflowController = new LeaveRequestWorkflowController(etags);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        AtomicInteger txOpenCount = new AtomicInteger(0);
        PlatformTransactionManager countingTxManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                txOpenCount.incrementAndGet();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition != null && definition.isReadOnly());
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }

            @Override
            public void rollback(TransactionStatus status) {
                TransactionSynchronizationManager.setActualTransactionActive(false);
                TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            }
        };

        AgentActionPreparationContributor orgContributor = (ctx, entity, entityId, actionId, raw, inputs) ->
                AgentActionPreparationContributor.ActionGuardAssessment.allow("02", "Org 02", Map.of());

        MetadataStarterInProcessAgentBridge noTxBridge = new MetadataStarterInProcessAgentBridge(
                List.of(workflowController),
                List.of(), List.of(), List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(List.of(workflowController)),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);
        AgentExecutionContext writerCtx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "op-1", "prod", "op-1",
                Set.of("WORKFORCE_READ", "WORKFORCE_WRITE"),
                null, null, Map.of(), Map.of());
        AgentExecutionContext hrAdminCtx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "op-admin", "prod", "op-admin",
                Set.of("WORKFORCE_READ", "WORKFORCE_WRITE", "HR_ADMIN"),
                null, null, Map.of(), Map.of());

        // 1. simulateDetermination without PlatformTransactionManager must fail closed
        assertThatThrownBy(() -> noTxBridge.simulateDetermination(
                writerCtx,
                new AgentDeterminationRequest("workforce.leave-requests", "preview", Map.of("periodId", 10L, "daysRequested", 12))))
                .isInstanceOf(IllegalStateException.class);

        NullableEmployeeController embeddedCpfController = new NullableEmployeeController(
                new NullableEmployeeDto(2002L, "Carlos 529.982.247-25 Alberto", "Setor 11144477735"));
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(workflowController, embeddedCpfController),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "hr.nullable-employees", "hr.nullable-employees", "/api/human-resources/nullable-employees",
                        "Colaboradores Opcionais", "Cadastro", "badge",
                        "id", "fullName", "departmentName", List.of("departmentName"),
                        null, "fullName", null, "/nullable-employees/{id}", "hr", 100))),
                List.of(), List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(List.of(workflowController, embeddedCpfController)),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                countingTxManager,
                List.of(),
                null,
                List.of(),
                null,
                etags,
                scopeProvider,
                validator,
                List.of(orgContributor));

        // 2. prepareAction calls executeIsolatedReadOnly -> resolveItemStateSnapshot also calls executeIsolatedReadOnly;
        //    must reuse the already active read-only tx (txOpenCount == 1, no nested REQUIRES_NEW).
        txOpenCount.set(0);
        AgentPreparedAction restrictedPrep = bridge.prepareAction(
                writerCtx,
                new AgentPrepareActionRequest("workforce.leave-requests", "701", "approve-leave",
                        Map.of("specialApprovalCode", "OVERRIDE-99")));
        assertThat(txOpenCount.get()).isEqualTo(1);
        assertThat(restrictedPrep.allowed()).isFalse();
        assertThat(restrictedPrep.blockedCode()).isEqualTo("FIELD_EDIT_DENIED");

        // 3. Valid preparation for writer without specialApprovalCode, then confirm checks:
        AgentPreparedAction prepared = bridge.prepareAction(
                writerCtx,
                new AgentPrepareActionRequest("workforce.leave-requests", "701", "approve-leave", Map.of()));
        assertThat(prepared.allowed()).isTrue();

        // 3a. Organization scope mismatch ("01" != "02") -> 409 ORGANIZATION_SCOPE_CHANGED
        AgentActionExecutionOutcome orgMismatch = bridge.executeConfirmedAction(
                writerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-org", "01", Map.of("notes", "OK")));
        assertThat(orgMismatch.httpStatus()).isEqualTo(409);
        assertThat(orgMismatch.outcomeCode()).isEqualTo("ORGANIZATION_SCOPE_CHANGED");

        // 3b. Restricted field edit without HR_ADMIN authority -> 403 FIELD_EDIT_DENIED
        AgentActionExecutionOutcome fieldDenied = bridge.executeConfirmedAction(
                writerCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-fld", "02",
                        Map.of("notes", "OK", "specialApprovalCode", "OVERRIDE-99")));
        assertThat(fieldDenied.httpStatus()).isEqualTo(403);
        assertThat(fieldDenied.outcomeCode()).isEqualTo("FIELD_EDIT_DENIED");

        // 3c. With HR_ADMIN authority, restricted field is allowed -> 200 CONFIRMED
        AgentActionExecutionOutcome fieldAllowed = bridge.executeConfirmedAction(
                hrAdminCtx,
                new AgentExecuteActionRequest("workforce.leave-requests", "701", "approve-leave",
                        prepared.expectedVersionTag(), "k-admin", "02",
                        Map.of("notes", "OK", "specialApprovalCode", "OVERRIDE-99")));
        assertThat(fieldAllowed.succeeded()).isTrue();
        assertThat(fieldAllowed.httpStatus()).isEqualTo(200);

        // 4. Outbound Mod-11 scrubbing on name-search & auto-loaded dossier model output
        SearchAndResolveEntitiesTool searchTool = new SearchAndResolveEntitiesTool(bridge);
        GetEntity360DossierTool dossierTool = new GetEntity360DossierTool(bridge);
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();

        Map<String, Object> searchOut = searchTool.execute(
                writerCtx, null, artifacts, Map.of("query", "Carlos"));
        String searchOutJson = searchOut.toString();
        assertThat(searchOutJson)
                .doesNotContain("529.982.247-25")
                .doesNotContain("52998224725")
                .doesNotContain("11144477735");
        assertThat(artifacts.dossiers()).hasSize(1);
        assertThat(artifacts.dossiers().get(0).uiRecord().get("fullName"))
                .isEqualTo("Carlos 529.982.247-25 Alberto");

        Map<String, Object> dossierOut = dossierTool.execute(
                writerCtx, null, artifacts, Map.of("entityKey", "hr.nullable-employees", "entityId", "2002"));
        assertThat(dossierOut.toString())
                .doesNotContain("529.982.247-25")
                .doesNotContain("52998224725")
                .doesNotContain("11144477735");
    }
}
