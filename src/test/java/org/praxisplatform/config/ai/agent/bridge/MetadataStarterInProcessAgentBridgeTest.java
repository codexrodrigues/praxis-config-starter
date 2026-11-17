package org.praxisplatform.config.ai.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.ai.agent.tools.DiscoverTopicResourcesAndSchemasTool;
import org.praxisplatform.config.ai.agent.tools.GetEntity360DossierTool;
import org.praxisplatform.config.ai.agent.tools.QueryResourceRecordsTool;
import org.praxisplatform.config.ai.agent.tools.SearchAndResolveEntitiesTool;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
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
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
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
            this.id = id;
            this.fullName = fullName;
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
        private final NullableEmployeeDto row = new NullableEmployeeDto(2002L, "Carlos Alberto");

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
}
