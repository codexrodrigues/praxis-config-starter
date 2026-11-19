package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.TestApplication;
import org.praxisplatform.config.ai.agent.bridge.MetadataStarterInProcessAgentBridge;
import org.praxisplatform.config.ai.agent.model.AgentModelStreamListener;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeDocument;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeSearchContributor;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.dto.AgenticAuthoringTurnStreamStartResponse;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.praxisplatform.uischema.action.ActionCollectionAtomicity;
import org.praxisplatform.uischema.action.ActionRequirement;
import org.praxisplatform.uischema.action.ActionRiskLevel;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.BulkEditable;
import org.praxisplatform.uischema.annotation.BulkOperation;
import org.praxisplatform.uischema.annotation.WorkflowAction;
import org.praxisplatform.uischema.bulk.BulkDomainResourceAdapter;
import org.praxisplatform.uischema.bulk.BulkExecutionMode;
import org.praxisplatform.uischema.bulk.BulkIdentityCodecs;
import org.praxisplatform.uischema.bulk.BulkItemEvaluationRequest;
import org.praxisplatform.uischema.bulk.BulkMode;
import org.praxisplatform.uischema.bulk.BulkProposal;
import org.praxisplatform.uischema.bulk.BulkProposalItemResult;
import org.praxisplatform.uischema.bulk.BulkProposalStatus;
import org.praxisplatform.uischema.bulk.BulkProposalTotals;
import org.praxisplatform.uischema.bulk.BulkResourceOperations;
import org.praxisplatform.uischema.command.ResourceCommandErrorCategory;
import org.praxisplatform.uischema.command.ResourceCommandMessage;
import org.praxisplatform.uischema.controller.base.AbstractBulkGovernedResourceController;
import org.praxisplatform.uischema.bulk.BulkControllerOperationIds;
import org.praxisplatform.uischema.bulk.JpaResourceBulkEngine;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.springframework.test.util.ReflectionTestUtils;
import org.praxisplatform.uischema.capability.AvailabilityDecision;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditions;
import org.praxisplatform.uischema.concurrency.ResourceVersionScope;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
import org.praxisplatform.uischema.service.base.BaseResourceService;
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
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@MockBean(classes = {
        org.praxisplatform.config.controller.GovernedColorPaletteController.class,
        org.praxisplatform.config.controller.DomainRuleChangeWorkspaceController.class,
        org.praxisplatform.config.controller.DomainRuleRolloutController.class,
        org.praxisplatform.config.controller.DomainRuleRolloutPolicyController.class,
        org.praxisplatform.config.controller.DomainRuleSnapshotController.class,
        org.praxisplatform.config.controller.DomainRuleHostStatusController.class,
        org.praxisplatform.config.controller.DomainRuleExecutionObservationController.class,
        org.praxisplatform.config.controller.DomainRuleController.class,
})
@SpringBootTest(
        classes = {TestApplication.class, OperationalAgentEndToEndMockMvcTest.StubDomainConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:operational_agent_e2e_it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=false",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:ai-stream-it-schema.sql",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                "spring.ai.openai.api-key=dummy",
                "spring.ai.vectorstore.pgvector.initialize-schema=false",
                "spring.ai.vectorstore.pgvector.vector-table-validations-enabled=false",
                "praxis.domain-360.enabled=false",
                "praxis.domain-federation.enabled=false",
                "praxis.domain-knowledge.change-sets.enabled=false",
                "praxis.ai.agent.http-enabled=true",
                "praxis.ai.rag.vector-store.enabled=false",
                "praxis.ai.registry.bootstrap.enabled=false",
                "praxis.ai.security.corporate-mode=true",
                "praxis.ai.stream.heartbeat-seconds=0"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {
        GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
        GoogleGenAiTextEmbeddingAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
})
@Tag("integration")
class OperationalAgentEndToEndMockMvcTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-a";
    private static final String ENV = "prod";

    public static class EmployeeDto {
        public Long id;
        @UISchema(label = "Nome")
        public String fullName;
        @UISchema(label = "CPF", preset = UISchemaPreset.CPF_CNPJ)
        @DomainGovernance(classification = DomainClassification.CONFIDENTIAL, aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK))
        public String taxId;
        public String positionTitle;

        public EmployeeDto() {}

        public EmployeeDto(Long id, String fullName, String taxId, String positionTitle) {
            this.id = id;
            this.fullName = fullName;
            this.taxId = taxId;
            this.positionTitle = positionTitle;
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getTaxId() { return taxId; }
        public String getPositionTitle() { return positionTitle; }
    }

    public static class EmployeeFilter implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        @UISchema(label = "CPF", controlType = FieldControlType.CPF_CNPJ_INPUT)
        private String taxId;
        @Filterable(operation = Filterable.FilterOperation.LIKE, relations = {"fullName", "positionTitle"})
        private String searchTerm;

        public String getTaxId() { return taxId; }
        public void setTaxId(String taxId) { this.taxId = taxId; }
        public String getSearchTerm() { return searchTerm; }
        public void setSearchTerm(String searchTerm) { this.searchTerm = searchTerm; }
    }

    public static class PositionDto {
        public Long id;
        @UISchema(label = "Cargo")
        public String title;
        public String careerTrack;

        public PositionDto() {}
        public PositionDto(Long id, String title, String careerTrack) {
            this.id = id;
            this.title = title;
            this.careerTrack = careerTrack;
        }
        public Long getId() { return id; }
        public String getTitle() { return title; }
        public String getCareerTrack() { return careerTrack; }
    }

    public static class PositionFilter implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.LIKE)
        private String title;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    @ApiResource(
            value = "/api/human-resources/employees",
            resourceKey = "hr.employees",
            title = "Colaboradores",
            identity = @ResourceIdentity(keyField = "id", titleField = "fullName", subtitleField = "positionTitle"))
    public static class StubEmployeeController extends AbstractResourceQueryController<EmployeeDto, Long, EmployeeFilter> {
        private final List<EmployeeDto> data = List.of(
                new EmployeeDto(1001L, "Maria Beatriz Costa", "52998224725", "Analista de Sistemas"),
                new EmployeeDto(1002L, "Maria Clara Souza", "11144477735", "Coordenadora de RH"));

        private final StatsFieldRegistry statsRegistry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("positionTitle", "positionTitle", Set.of(StatsMetric.COUNT)))
                .build();

        private final BaseResourceQueryService<EmployeeDto, Long, EmployeeFilter> service = new SimpleQueryService<>() {
            @Override public Sort getDefaultSort() { return Sort.by("fullName"); }
            @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.AUTO; }
            @Override public StatsFieldRegistry getStatsFieldRegistry() { return statsRegistry; }
            @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<EmployeeFilter> r) {
                return new GroupByStatsResponse(r.field(), r.metric(), List.of(
                        new GroupByBucket("ANALISTA", "Analista de Sistemas", 1, 1),
                        new GroupByBucket("COORDENACAO", "Coordenadora de RH", 1, 1)));
            }
            @Override public ResourceRepresentationResult<EmployeeDto> findById(Long id) {
                return data.stream().filter(e -> e.id.equals(id)).findFirst()
                        .map(ResourceRepresentationResult::unversioned).orElse(null);
            }
            @Override public Page<EmployeeDto> filter(EmployeeFilter f, Pageable p, Collection<Long> ids) {
                if (f != null && f.getTaxId() != null) {
                    return new PageImpl<>(data.stream().filter(e -> f.getTaxId().equals(e.taxId)).toList());
                }
                if (f != null && f.getSearchTerm() != null) {
                    String q = f.getSearchTerm().toLowerCase();
                    return new PageImpl<>(data.stream().filter(e ->
                            e.fullName.toLowerCase().contains(q) || e.positionTitle.toLowerCase().contains(q)).toList());
                }
                return Page.empty();
            }
        };

        @Override protected BaseResourceQueryService<EmployeeDto, Long, EmployeeFilter> getService() { return service; }
        @Override protected Long getResponseId(EmployeeDto dto) { return dto.id; }
    }

    @ApiResource(
            value = "/api/human-resources/positions",
            resourceKey = "hr.positions",
            title = "Cargos",
            identity = @ResourceIdentity(keyField = "id", titleField = "title", subtitleField = "careerTrack"))
    public static class StubPositionController extends AbstractResourceQueryController<PositionDto, Long, PositionFilter> {
        private final List<PositionDto> data = List.of(
                new PositionDto(301L, "Analista Judiciario", "Carreira Superior"));

        private final BaseResourceQueryService<PositionDto, Long, PositionFilter> service = new SimpleQueryService<>() {
            @Override public Sort getDefaultSort() { return Sort.by("title"); }
            @Override public ResourceRepresentationResult<PositionDto> findById(Long id) {
                return data.stream().filter(e -> e.id.equals(id)).findFirst()
                        .map(ResourceRepresentationResult::unversioned).orElse(null);
            }
            @Override public Page<PositionDto> filter(PositionFilter f, Pageable p, Collection<Long> ids) {
                if (f != null && f.getTitle() != null) {
                    String q = f.getTitle().toLowerCase();
                    return new PageImpl<>(data.stream().filter(e -> e.title.toLowerCase().contains(q)).toList());
                }
                return Page.empty();
            }
        };

        @Override protected BaseResourceQueryService<PositionDto, Long, PositionFilter> getService() { return service; }
        @Override protected Long getResponseId(PositionDto dto) { return dto.id; }
    }

    @CalculationMemory(
            title = "Demonstrativo remuneratório",
            legalBasis = "Lei Trabalhista",
            trails = {
                @CalculationTrail(
                        id = "net-pay",
                        title = "Composição líquida",
                        ruleCode = "WF-PAY-NET",
                        formula = "Bruto - Descontos",
                        baseLabel = "Bruto",
                        baseExpr = "row.grossPay",
                        baseFormat = "currency:BRL",
                        deductionLabel = "Descontos",
                        deductionExpr = "row.deductions",
                        deductionFormat = "currency:BRL",
                        resultLabel = "Líquido",
                        resultExpr = "row.grossPay - row.deductions",
                        resultFormat = "currency:BRL")
            })
    public static class PayrollSlipDto {
        public Long id;
        @UISchema(label = "Competência")
        public String periodLabel;
        public BigDecimal grossPay;
        public BigDecimal deductions;
        public PayrollSlipDto() {}
        public PayrollSlipDto(Long id, String periodLabel, BigDecimal grossPay, BigDecimal deductions) {
            this.id = id;
            this.periodLabel = periodLabel;
            this.grossPay = grossPay;
            this.deductions = deductions;
        }
        public Long getId() { return id; }
        public String getPeriodLabel() { return periodLabel; }
        public BigDecimal getGrossPay() { return grossPay; }
        public BigDecimal getDeductions() { return deductions; }
    }

    public static class PayrollSlipFilter implements GenericFilterDTO {
        @Filterable(operation = Filterable.FilterOperation.EQUAL)
        private String periodLabel;
        public String getPeriodLabel() { return periodLabel; }
        public void setPeriodLabel(String periodLabel) { this.periodLabel = periodLabel; }
    }

    @ApiResource(
            value = "/api/payroll/slips",
            resourceKey = "payroll.slips",
            title = "Folha Salarial",
            identity = @ResourceIdentity(keyField = "id", titleField = "periodLabel", subtitleField = "periodLabel"))
    public static class StubPayrollController extends AbstractResourceQueryController<PayrollSlipDto, Long, PayrollSlipFilter> {
        private final PayrollSlipDto slip = new PayrollSlipDto(9001L, "Mar/2026", new BigDecimal("14000.00"), new BigDecimal("3500.00"));
        private final BaseResourceQueryService<PayrollSlipDto, Long, PayrollSlipFilter> service = new SimpleQueryService<>() {
            @Override public Sort getDefaultSort() { return Sort.by("id"); }
            @Override public ResourceRepresentationResult<PayrollSlipDto> findById(Long id) {
                return Long.valueOf(9001L).equals(id) ? ResourceRepresentationResult.unversioned(slip) : null;
            }
            @Override public Page<PayrollSlipDto> filter(PayrollSlipFilter f, Pageable p, Collection<Long> ids) {
                if (f != null && f.getPeriodLabel() != null
                        && slip.periodLabel.toLowerCase().contains(f.getPeriodLabel().toLowerCase())) {
                    return new PageImpl<>(List.of(slip));
                }
                return Page.empty();
            }
        };
        @Override protected BaseResourceQueryService<PayrollSlipDto, Long, PayrollSlipFilter> getService() { return service; }
        @Override protected Long getResponseId(PayrollSlipDto dto) { return dto.id; }
    }

    public static class LeaveDraftDto {
        @jakarta.validation.constraints.NotNull
        public Long periodId;
        public Integer days;
        public Long getPeriodId() { return periodId; }
        public void setPeriodId(Long periodId) { this.periodId = periodId; }
        public Integer getDays() { return days; }
        public void setDays(Integer days) { this.days = days; }
    }

    public static class LeavePreviewDto {
        @UISchema(label = "Dias solicitados")
        public Integer days;
        @UISchema(label = "Saldo após solicitação")
        public Integer remainingDays;
        public LeavePreviewDto() {}
        public LeavePreviewDto(Integer days, Integer remainingDays) {
            this.days = days;
            this.remainingDays = remainingDays;
        }
        public Integer getDays() { return days; }
        public Integer getRemainingDays() { return remainingDays; }
    }

    public static class ApproveLeavePayload {
        @jakarta.validation.constraints.NotBlank(message = "Justificativa obrigatória")
        @UISchema(label = "Observações", controlType = FieldControlType.TEXTAREA)
        public String notes;
        @UISchema(label = "CPF do Substituto", preset = UISchemaPreset.CPF_CNPJ)
        @DomainGovernance(classification = DomainClassification.CONFIDENTIAL, aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.MASK))
        public String substituteTaxId;
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public String getSubstituteTaxId() { return substituteTaxId; }
        public void setSubstituteTaxId(String substituteTaxId) { this.substituteTaxId = substituteTaxId; }
    }

    public static class LeaveRecordDto {
        public Long id;
        @UISchema(label = "Servidor")
        public String employeeName;
        @UISchema(label = "Situação")
        public String status;
        public LeaveRecordDto() {}
        public LeaveRecordDto(Long id, String employeeName, String status) {
            this.id = id;
            this.employeeName = employeeName;
            this.status = status;
        }
        public Long getId() { return id; }
        public String getEmployeeName() { return employeeName; }
        public String getStatus() { return status; }
    }

    @ApiResource(
            value = "/api/workforce/leave-parcels",
            resourceKey = "workforce.leave-parcels",
            title = "Parcelas de Afastamento",
            identity = @ResourceIdentity(keyField = "id", titleField = "employeeName", subtitleField = "status"))
    public static class StubLeaveParcelController
            extends AbstractResourceQueryController<LeaveRecordDto, Long, PositionFilter> {
        private final ResourceVersionEtagService etagService;
        private final AtomicInteger version = new AtomicInteger(1);
        private final AtomicInteger approveInvocations = new AtomicInteger(0);
        private final AtomicReference<String> lastReceivedSubstituteTaxId = new AtomicReference<>();
        private volatile LeaveRecordDto record = new LeaveRecordDto(501L, "Lucas Andrade", "SCHEDULED");

        StubLeaveParcelController(ResourceVersionEtagService etagService, ResourceVersionScopeProvider scopes) {
            this.etagService = etagService;
            configureResourceVersioning(etagService, scopes);
        }

        void reset() {
            version.set(1);
            approveInvocations.set(0);
            lastReceivedSubstituteTaxId.set(null);
            record = new LeaveRecordDto(501L, "Lucas Andrade", "SCHEDULED");
        }

        void bumpConcurrentVersion() {
            version.incrementAndGet();
        }

        private final BaseResourceQueryService<LeaveRecordDto, Long, PositionFilter> service = new SimpleQueryService<>() {
            @Override public Sort getDefaultSort() { return Sort.by("id"); }
            @Override public ResourceRepresentationResult<LeaveRecordDto> findById(Long id) {
                return Long.valueOf(501L).equals(id)
                        ? ResourceRepresentationResult.versioned(record, version.get())
                        : null;
            }
            @Override public Page<LeaveRecordDto> filter(PositionFilter f, Pageable p, Collection<Long> ids) {
                return new PageImpl<>(List.of(record));
            }
        };

        @PostMapping("/determinations/preview")
        public ResponseEntity<LeavePreviewDto> preview(@jakarta.validation.Valid @RequestBody LeaveDraftDto draft) {
            int days = draft.days != null ? draft.days : 10;
            return ResponseEntity.ok(new LeavePreviewDto(days, 30 - days));
        }

        @PostMapping("/{id}/actions/approve-parcel")
        @WorkflowAction(
                id = "approve-parcel",
                title = "Homologar parcela",
                description = "Homologa a parcela programada",
                scope = ActionScope.ITEM,
                allowedStates = {"SCHEDULED"},
                riskLevel = ActionRiskLevel.HIGH,
                confirmationRequired = true,
                resourceVersion = ActionRequirement.REQUIRED)
        public ResponseEntity<LeaveRecordDto> approveParcel(
                @PathVariable Long id,
                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                @jakarta.validation.Valid @RequestBody ApproveLeavePayload payload) {
            ResourceVersionPreconditions.requireMatch(
                    etagService, ifMatch, ResourceVersionScope.GLOBAL, "workforce.leave-parcels", id, version.get());
            approveInvocations.incrementAndGet();
            lastReceivedSubstituteTaxId.set(payload.getSubstituteTaxId());
            long nextVer = version.incrementAndGet();
            record = new LeaveRecordDto(id, record.employeeName, "APPROVED");
            return ResponseEntity.ok()
                    .eTag(etagService.create(ResourceVersionScope.GLOBAL, "workforce.leave-parcels", id, nextVer))
                    .body(record);
        }

        @Override protected BaseResourceQueryService<LeaveRecordDto, Long, PositionFilter> getService() { return service; }
        @Override protected Long getResponseId(LeaveRecordDto dto) { return dto.id; }
    }

    public static class BulkParcelUpdateDto {
        @UISchema(label = "Situação")
        @BulkEditable(modes = {BulkMode.UNIFORM_UPDATE, BulkMode.PER_ITEM_UPDATE})
        public String status;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    @ApiResource(
            value = "/api/workforce/bulk-leave-parcels",
            resourceKey = "workforce.bulk-leave-parcels",
            title = "Parcelas em Lote",
            identity = @ResourceIdentity(keyField = "id", titleField = "employeeName", subtitleField = "status"))
    @BulkResourceOperations(updateSourceOperationId = "workforce.bulk-leave-parcels.update")
    public static class StubBulkLeaveParcelController
            extends AbstractBulkGovernedResourceController<LeaveRecordDto, Long, PositionFilter, LeaveRecordDto, BulkParcelUpdateDto>
            implements MetadataStarterInProcessAgentBridge.BulkEnginePort {
        private final LeaveRecordDto r1 = new LeaveRecordDto(501L, "Lucas Andrade", "SCHEDULED");
        private final LeaveRecordDto r2 = new LeaveRecordDto(502L, "Ana Maria", "CLOSED");
        private final MetadataStarterInProcessAgentBridge.BulkEnginePort engine;

        @SuppressWarnings("unchecked")
        StubBulkLeaveParcelController(ResourceVersionEtagService etagService, ResourceVersionScopeProvider scopes) {
            configureResourceVersioning(etagService, scopes);
            this.engine = mock(MetadataStarterInProcessAgentBridge.BulkEnginePort.class);
            when(this.engine.encodeWireId(any())).thenAnswer(inv -> String.valueOf((Object) inv.getArgument(0)));
            when(this.engine.currentVersion(501L)).thenReturn("\"v-501\"");
            when(this.engine.currentVersion(502L)).thenReturn("\"v-502\"");
            String itemsConfirmOp = BulkControllerOperationIds.updateConfirmationId(StubBulkLeaveParcelController.class, true);
            String mixedPropId = "11111111-1111-4111-8111-111111111111";
            ObjectMapper om = new ObjectMapper().findAndRegisterModules();
            when(this.engine.evaluateItems(eq(itemsConfirmOp), any(BulkItemEvaluationRequest.class)))
                    .thenReturn(new BulkProposal(
                            mixedPropId,
                            new CanonicalOperationRef("workforce", itemsConfirmOp, "/api/workforce/bulk-leave-parcels/bulk/update-items", "POST"),
                            BulkMode.PER_ITEM_UPDATE, BulkExecutionMode.SYNC, ActionCollectionAtomicity.PER_ITEM,
                            BulkProposalStatus.BLOCKED, Instant.now().minusSeconds(1), Instant.now().plusSeconds(300),
                            new BulkProposalTotals(2, 2, 1, 1), om.createObjectNode(),
                            List.of(new ResourceCommandMessage(ResourceCommandErrorCategory.VALIDATION, "RULE_CLOSED", "Parcela 502 fechada.", null, Map.of())),
                            List.of()));
            when(this.engine.proposalResults(eq(itemsConfirmOp), eq(UUID.fromString(mixedPropId)), eq(50), eq(null)))
                    .thenReturn(new CursorPage<>(List.of(
                            new BulkProposalItemResult<>("501", BulkProposalItemResult.Decision.EXECUTABLE, List.of()),
                            new BulkProposalItemResult<>("502", BulkProposalItemResult.Decision.BLOCKED,
                                    List.of(new ResourceCommandMessage(ResourceCommandErrorCategory.VALIDATION, "RULE_CLOSED", "Parcela 502 fechada para alteração.", null, Map.of())))),
                            null, null, 50));
        }

        @SuppressWarnings("unchecked")
        private final BaseResourceService<LeaveRecordDto, Long, PositionFilter, LeaveRecordDto, BulkParcelUpdateDto> service = createBulkService();

        @SuppressWarnings("unchecked")
        private BaseResourceService<LeaveRecordDto, Long, PositionFilter, LeaveRecordDto, BulkParcelUpdateDto> createBulkService() {
            BaseResourceService<LeaveRecordDto, Long, PositionFilter, LeaveRecordDto, BulkParcelUpdateDto> svc = mock(BaseResourceService.class);
            when(svc.getIdFieldName()).thenReturn("id");
            when(svc.getDefaultSort()).thenReturn(Sort.by("id"));
            when(svc.findById(501L)).thenReturn(ResourceRepresentationResult.versioned(r1, 1L));
            when(svc.findById(502L)).thenReturn(ResourceRepresentationResult.versioned(r2, 2L));
            when(svc.filter(any(), any(), any())).thenReturn(new PageImpl<>(List.of(r1, r2)));
            return svc;
        }

        @Override protected BaseResourceService<LeaveRecordDto, Long, PositionFilter, LeaveRecordDto, BulkParcelUpdateDto> getService() { return service; }
        @Override protected Long getResponseId(LeaveRecordDto dto) { return dto.id; }
        @Override protected JpaResourceBulkEngine<Long, PositionFilter> getBulkEngine() { return null; }

        @Override public Object encodeWireId(Object convertedId) { return engine.encodeWireId(convertedId); }
        @Override public String currentVersion(Object convertedId) { return engine.currentVersion(convertedId); }
        @Override public BulkProposal evaluateUniform(String opId, org.praxisplatform.uischema.bulk.BulkUniformEvaluationRequest<?, Object> req) { return engine.evaluateUniform(opId, req); }
        @Override public BulkProposal evaluateItems(String opId, BulkItemEvaluationRequest<?> req) { return engine.evaluateItems(opId, req); }
        @Override public BulkProposal evaluateDomainCommand(String opId, org.praxisplatform.uischema.bulk.BulkCommandEvaluationRequest<Object, ?, Object> req) { return engine.evaluateDomainCommand(opId, req); }
        @Override public CursorPage<BulkProposalItemResult<Object>> proposalResults(String opId, UUID propId, int size, String after) { return engine.proposalResults(opId, propId, size, after); }
    }

    abstract static class SimpleQueryService<R, ID, F extends GenericFilterDTO> implements BaseResourceQueryService<R, ID, F> {
        @Override public String getIdFieldName() { return "id"; }
        @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
        @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsFieldRegistry getStatsFieldRegistry() { return null; }
        @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
        @Override public boolean hasOptionSource(String k) { return false; }
        @Override public OptionSourceDescriptor resolveOptionSource(String k) { return null; }
        @Override public List<R> findAll() { return List.of(); }
        @Override public List<R> findAllById(Collection<ID> ids) { return List.of(); }
        @Override public CursorPage<R> filterByCursor(F f, Sort s, String a, String c, int l) { return null; }
        @Override public java.util.OptionalLong locate(F f, Sort s, ID id) { return java.util.OptionalLong.empty(); }
        @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<ID>> filterOptions(F f, Pageable p) { return Page.empty(); }
        @Override public List<org.praxisplatform.uischema.dto.OptionDTO<ID>> byIdsOptions(Collection<ID> ids) { return List.of(); }
        @Override public Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> filterOptionSourceOptions(String k, OptionSourceFilterRequest<F> r, Pageable p) { return Page.empty(); }
        @Override public List<org.praxisplatform.uischema.dto.OptionDTO<Object>> byIdsOptionSourceOptions(String k, Collection<Object> ids) { return List.of(); }
        @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<F> r) { return null; }
        @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<F> r) { return null; }
        @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<F> r) { return null; }
        @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<F> r) { return null; }
    }

    @TestConfiguration
    static class StubDomainConfig {

        @Bean
        StubEmployeeController stubEmployeeController() {
            return new StubEmployeeController();
        }

        @Bean
        StubPositionController stubPositionController() {
            return new StubPositionController();
        }

        @Bean
        StubPayrollController stubPayrollController() {
            return new StubPayrollController();
        }

        @Bean
        ResourceVersionEtagService resourceVersionEtagService() {
            return new ResourceVersionEtagService("e2e-test-etag-secret-0123456789abcdef0123");
        }

        @Bean
        ResourceVersionScopeProvider resourceVersionScopeProvider() {
            return () -> ResourceVersionScope.GLOBAL;
        }

        @Bean
        StubLeaveParcelController stubLeaveParcelController(
                ResourceVersionEtagService etagService,
                ResourceVersionScopeProvider scopes) {
            return new StubLeaveParcelController(etagService, scopes);
        }

        @Bean
        StubBulkLeaveParcelController stubBulkLeaveParcelController(
                ResourceVersionEtagService etagService,
                ResourceVersionScopeProvider scopes) {
            return new StubBulkLeaveParcelController(etagService, scopes);
        }

        @Bean
        AgentKnowledgeSearchContributor stubKnowledgeContributor() {
            return (ctx, query, topicKey, sourceFilter, limit) -> List.of(
                    new AgentKnowledgeDocument(
                            "wf-art-37",
                            "STATUTE",
                            "Regra geral de teto remuneratório",
                            "Art. 37, XI",
                            "Tenant padrão",
                            "O teto aplica-se aos proventos e subsídios acumulados na competência.",
                            List.of("teto", "remuneracao"),
                            0.94d,
                            null,
                            Map.of()));
        }

        @Bean
        AgentEntityCatalogContributor stubEntityCatalogContributor() {
            return ctx -> List.of(
                    new AgentEntityDescriptor("hr.employees", "hr.employees", "/api/human-resources/employees",
                            "Colaboradores", "Cadastro de colaboradores", "badge", "id", "fullName", "positionTitle",
                            List.of("positionTitle"), "taxId", "searchTerm", null, "/employees/{id}", "hr", 100),
                    new AgentEntityDescriptor("hr.positions", "hr.positions", "/api/human-resources/positions",
                            "Cargos", "Estrutura de cargos", "work", "id", "title", "careerTrack",
                            List.of("careerTrack"), null, "title", null, "/positions/{id}", "hr", 50),
                    new AgentEntityDescriptor("payroll.slips", "payroll.slips", "/api/payroll/slips",
                            "Folha Salarial", "Demonstrativos financeiros", "payments", "id", "periodLabel", "periodLabel",
                            List.of("periodLabel"), null, "periodLabel", null, "/payroll/{id}", "payroll", 40, null, false),
                    new AgentEntityDescriptor("workforce.bulk-leave-parcels", "workforce.bulk-leave-parcels", "/api/workforce/bulk-leave-parcels",
                            "Parcelas em Lote", "Parcelas com suporte bulk", "event", "id", "employeeName", "status",
                            List.of("status"), null, "employeeName", null, "/bulk-leave-parcels/{id}", "workforce", 30, null, false));
        }

        @Bean
        AgentProactiveAlertContributor vacationAlertContributor() {
            return new AgentProactiveAlertContributor() {
                @Override
                public boolean supportsEntity(String entityKey) {
                    return "hr.employees".equals(entityKey);
                }

                @Override
                public List<AgentProactiveAlert> evaluateAlerts(
                        AgentExecutionContext context, String entityKey, String entityId, Map<String, Object> record) {
                    if ("1001".equals(entityId)) {
                        return List.of(new AgentProactiveAlert(
                                "LEAVE_EXPIRING", "warning",
                                "Periodo de ferias prestes a vencer",
                                "Existe saldo pendente com vencimento nos proximos 30 dias.",
                                entityKey, "/api/human-resources/employees", entityId,
                                "schedule-leave", "Agendar ferias", Map.of()));
                    }
                    return List.of();
                }
            };
        }

        @Bean
        @Primary
        AiToolCallingProvider aiToolCallingProvider() {
            return new AiToolCallingProvider() {
                @Override
                public String providerId() {
                    return "gemini";
                }

                @Override
                public AgentModelTurnResponse generateTurn(
                        AgentModelTurnRequest request, AgentModelStreamListener listener) {
                    String firstUserPrompt = request.conversation().get(0).text();
                    boolean hasToolResult = request.conversation().stream()
                            .anyMatch(item -> !item.toolResults().isEmpty());
                    if (!hasToolResult) {
                        if (firstUserPrompt.startsWith("chart stats")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-stats-1", "aggregate_resource_stats",
                                            Map.of("entityKey", "hr.employees", "operationKind", "GROUP_BY",
                                                    "field", "positionTitle"),
                                            "sig-s", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-s1", Map.of());
                        }
                        if (firstUserPrompt.startsWith("explain calc")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-calc-1", "explain_calculation_memory",
                                            Map.of("entityKey", "payroll.slips", "entityId", "9001"),
                                            "sig-c", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-c1", Map.of());
                        }
                        if (firstUserPrompt.startsWith("knowledge rule")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-kb-1", "search_domain_knowledge_and_rules",
                                            Map.of("query", "teto", "limit", 3),
                                            "sig-k", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-k1", Map.of());
                        }
                        if (firstUserPrompt.startsWith("simulate leave")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-sim-1", "simulate_determination_or_rules",
                                            Map.of("entityKey", "workforce.leave-parcels",
                                                    "determinationId", "preview",
                                                    "draftFields", Map.of("periodId", 10, "days", 12)),
                                            "sig-sim", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-sim1", Map.of());
                        }
                        if (firstUserPrompt.startsWith("prepare approve")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-prep-1", "prepare_action_or_mutation",
                                            Map.of("entityKey", "workforce.leave-parcels",
                                                    "entityId", "501",
                                                    "actionId", "approve-parcel",
                                                    "proposedInputs", Map.of("notes", "Escala confirmada")),
                                            "sig-prep", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-prep1", Map.of());
                        }
                        if (firstUserPrompt.startsWith("prepare masked-cpf")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-prep-cpf", "prepare_action_or_mutation",
                                            Map.of("entityKey", "workforce.leave-parcels",
                                                    "entityId", "501",
                                                    "actionId", "approve-parcel",
                                                    "proposedInputs", Map.of(
                                                            "notes", "Escala confirmada",
                                                            "substituteTaxId", "52998224725")),
                                            "sig-prep-cpf", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-prep-cpf1", Map.of());
                        }
                        if (firstUserPrompt.startsWith("prepare incomplete")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-prep-2", "prepare_action_or_mutation",
                                            Map.of("entityKey", "workforce.leave-parcels",
                                                    "entityId", "501",
                                                    "actionId", "approve-parcel"),
                                            "sig-prep2", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-prep2", Map.of());
                        }
                        if (firstUserPrompt.startsWith("prepare bulk mixed")) {
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-bulk-1", "prepare_bulk_operation",
                                            Map.of("entityKey", "workforce.bulk-leave-parcels",
                                                    "bulkMode", "PER_ITEM_UPDATE",
                                                    "items", List.of(
                                                            Map.of("id", "501", "changes", List.of(Map.of("field", "status", "operator", "SET", "value", "APPROVED"))),
                                                            Map.of("id", "502", "changes", List.of(Map.of("field", "status", "operator", "SET", "value", "APPROVED"))))),
                                            "sig-bulk1", null)),
                                    new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-bulk1", Map.of());
                        }
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                List.of(new AgentToolCall("call-1", "search_and_resolve_entities",
                                        Map.of("query", firstUserPrompt), "c2ln", null)),
                                new AgentTokenUsage(30, 15, 45), "tool_calls", "resp-1", Map.of());
                    }
                    var lastToolResults = request.conversation().get(request.conversation().size() - 1).toolResults();
                    var firstResult = lastToolResults.get(0);
                    if ("aggregate_resource_stats".equals(firstResult.name())) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Gráfico de distribuição por cargo gerado abaixo.",
                                List.of(), new AgentTokenUsage(45, 20, 65), "stop", "resp-s2", Map.of());
                    }
                    if ("explain_calculation_memory".equals(firstResult.name())) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Demonstrativo de cálculo líquido detalhado abaixo.",
                                List.of(), new AgentTokenUsage(45, 20, 65), "stop", "resp-c2", Map.of());
                    }
                    if ("search_domain_knowledge_and_rules".equals(firstResult.name())) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Citações normativas aplicáveis apresentadas abaixo.",
                                List.of(), new AgentTokenUsage(45, 20, 65), "stop", "resp-k2", Map.of());
                    }
                    if ("simulate_determination_or_rules".equals(firstResult.name())) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Simulação prévia não persistida calculada abaixo.",
                                List.of(), new AgentTokenUsage(45, 20, 65), "stop", "resp-sim2", Map.of());
                    }
                    if ("prepare_action_or_mutation".equals(firstResult.name())) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Proposta governada pronta para revisão e confirmação humana.",
                                List.of(), new AgentTokenUsage(45, 20, 65), "stop", "resp-prep2", Map.of());
                    }
                    if ("prepare_bulk_operation".equals(firstResult.name())) {
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Proposta em lote avaliada abaixo; abra o modal dedicado para revisar e confirmar.",
                                List.of(), new AgentTokenUsage(45, 20, 65), "stop", "resp-bulk2", Map.of());
                    }
                    if ("search_and_resolve_entities".equals(firstResult.name())) {
                        int count = ((Number) firstResult.output().getOrDefault("matchCount", 0)).intValue();
                        if (count == 1) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> candidates = (List<Map<String, Object>>) firstResult.output().get("candidates");
                            Map<String, Object> only = candidates.get(0);
                            return new AgentModelTurnResponse("gemini", "gemini-2.5-flash", "",
                                    List.of(new AgentToolCall("call-2", "get_entity_360_dossier",
                                            Map.of("entityKey", only.get("entityKey"), "entityId", only.get("entityId")),
                                            "c2lnMg==", null)),
                                    new AgentTokenUsage(40, 20, 60), "tool_calls", "resp-2", Map.of());
                        }
                        return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                                "Encontrei mais de um registro correspondente. Selecione qual deseja abrir:",
                                List.of(), new AgentTokenUsage(35, 25, 60), "stop", "resp-2", Map.of());
                    }
                    return new AgentModelTurnResponse("gemini", "gemini-2.5-flash",
                            "Localizei o colaborador e consolidei o dossie operacional.",
                            List.of(), new AgentTokenUsage(60, 30, 90), "stop", "resp-3", Map.of());
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StubLeaveParcelController stubLeaveParcelController;

    @MockBean
    private ActionCatalogService actionCatalogService;

    @BeforeEach
    void cleanDatabase() {
        stubLeaveParcelController.reset();
        ActionCatalogItem scheduleLeave = new ActionCatalogItem(
                "schedule-leave", "hr.employees", ActionScope.ITEM,
                "Agendar ferias", "Inicia o agendamento de ferias para o colaborador",
                "scheduleLeave", "/api/human-resources/employees/{id}/actions/schedule-leave", "POST",
                "ScheduleLeaveRequest", "/schemas/filtered?path=/api/human-resources/employees/{id}/actions/schedule-leave&schemaType=request",
                null, null, List.of("ACTIVE"), AvailabilityDecision.allowAll(), 1,
                "Ferias agendadas com sucesso.", List.of("ferias"), ActionExecutionContract.defaults(ActionScope.ITEM));
        when(actionCatalogService.findItemActions("hr.employees", 1001L)).thenReturn(new ActionCatalogResponse(
                "hr.employees", "/api/human-resources/employees", "hr", 1001L, List.of(scheduleLeave)));

        jdbcTemplate.execute("set referential_integrity false");
        jdbcTemplate.execute("delete from ai_agent_action_proposal");
        jdbcTemplate.execute("delete from ai_turn_event");
        jdbcTemplate.execute("delete from ai_turn");
        jdbcTemplate.execute("delete from ai_thread");
        jdbcTemplate.execute("set referential_integrity true");
    }

    @Test
    void scenarioA_exactCpfResolvesDossierAlertAndActionsWithoutPersistingRawTaxId() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("529.982.247-25", "turn-cpf-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        List<String> storedPrompts = jdbcTemplate.queryForList("select title from ai_thread", String.class);
        assertThat(storedPrompts).hasSize(1);
        assertThat(storedPrompts.get(0)).contains("[TAX_ID_1]").doesNotContain("52998224725").doesNotContain("529.982.247-25");

        List<String> storedPayloads = jdbcTemplate.queryForList("select cast(payload as varchar) from ai_turn_event", String.class);
        assertThat(storedPayloads).isNotEmpty();
        assertThat(storedPayloads).allSatisfy(rawPayload ->
                assertThat(rawPayload).doesNotContain("52998224725").doesNotContain("529.982.247-25"));

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode payload = resultEvent.getPayload();
        assertThat(payload.toString()).doesNotContain("[REDACTED]");
        assertThat(payload.path("assistantContent").path("version").asText()).isEqualTo("praxis.assistant-content/v2");

        JsonNode blocks = payload.path("assistantContent").path("blocks");
        List<String> blockTypes = new ArrayList<>();
        blocks.forEach(b -> blockTypes.add(b.path("type").asText()));
        assertThat(blockTypes).containsExactly("paragraph", "alert-list", "widget-block", "action-bar");
        assertThat(blocks.get(1).path("items").get(0).path("code").asText()).isEqualTo("LEAVE_EXPIRING");
        assertThat(blocks.get(2).path("widgetType").asText()).isEqualTo("praxis-rich-content");
        assertThat(blocks.get(2).path("widget").path("id").asText()).isEqualTo("praxis-rich-content");
        assertThat(blocks.get(2).path("widget").path("inputs").path("layout").asText()).isEqualTo("block");
        JsonNode richDoc = blocks.get(2).path("widget").path("inputs").path("document");
        assertThat(richDoc.path("kind").asText()).isEqualTo("praxis.rich-content");
        assertThat(richDoc.path("version").asText()).isEqualTo("1.0.0");
        assertThat(richDoc.path("nodes").get(0).path("type").asText()).isEqualTo("recordSummary");
        JsonNode summaryFields = richDoc.path("nodes").get(0).path("fields");
        boolean foundMaskedCpf = false;
        for (JsonNode f : summaryFields) {
            if ("taxId".equals(f.path("id").asText())) {
                assertThat(f.path("label").asText()).isEqualTo("CPF");
                assertThat(f.path("value").asText()).isEqualTo("***.982.247-**");
                foundMaskedCpf = true;
            }
        }
        assertThat(foundMaskedCpf).isTrue();
        assertThat(blocks.get(3).path("items").get(0).path("actionId").asText()).isEqualTo("schedule-leave");
        assertThat(blocks.get(3).path("items").get(0).path("formMode").asText()).isEqualTo("dialog");
    }

    @Test
    void scenarioB_homonymCollisionEmitsDisambiguationAndQuickReplies() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("Maria", "turn-maria-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode payload = resultEvent.getPayload();
        assertThat(payload.toString()).doesNotContain("[REDACTED]");
        JsonNode blocks = payload.path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("compact-collection");
        assertThat(blocks.get(1).path("variant").asText()).isEqualTo("disambiguation");
        assertThat(blocks.get(1).path("rows")).hasSize(2);
        assertThat(payload.path("quickReplies")).hasSize(2);
        assertThat(payload.path("quickReplies").get(0).path("contextHints").path("candidateRef").asText())
                .startsWith("hr.employees:");
    }

    @Test
    void scenarioC_polymorphicCollisionGroupsAcrossEmployeesAndPositions() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("Analista", "turn-poly-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode rows = resultEvent.getPayload().path("assistantContent").path("blocks").get(1).path("rows");
        List<String> entityKeys = new ArrayList<>();
        rows.forEach(r -> entityKeys.add(r.path("entityKey").asText()));
        assertThat(entityKeys).containsExactlyInAnyOrder("hr.employees", "hr.positions");
    }

    @Test
    void scenarioD_aggregateStatsEmitsChartWidgetWithoutRedaction() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("chart stats colaboradores", "turn-stats-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        String rawJson = objectMapper.writeValueAsString(resultEvent.getPayload());
        assertThat(rawJson).doesNotContain("[REDACTED]");

        JsonNode blocks = resultEvent.getPayload().path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("widget-block");
        assertThat(blocks.get(1).path("widgetType").asText()).isEqualTo("praxis-chart");
        assertThat(blocks.get(1).path("widget").path("id").asText()).isEqualTo("praxis-chart");
        assertThat(blocks.get(1).path("widget").path("inputs").path("data")).hasSize(2);
    }

    @Test
    void scenarioE_calculationMemoryEmitsAuditTrailsWithoutRedaction() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("explain calc folha 9001", "turn-calc-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        String rawJson = objectMapper.writeValueAsString(resultEvent.getPayload());
        assertThat(rawJson).doesNotContain("[REDACTED]");

        JsonNode blocks = resultEvent.getPayload().path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("calculation-memory");
        assertThat(blocks.get(1).path("trails").get(0).path("resultFormatted").asText()).isEqualTo("R$ 10.500,00");
    }

    @Test
    void scenarioF_knowledgeSearchEmitsCitationsBlockWithoutRedaction() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("knowledge rule teto", "turn-kb-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        String rawJson = objectMapper.writeValueAsString(resultEvent.getPayload());
        assertThat(rawJson).doesNotContain("[REDACTED]");

        JsonNode blocks = resultEvent.getPayload().path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("knowledge-citations");
        assertThat(blocks.get(1).path("items").get(0).path("normRef").asText()).isEqualTo("Art. 37, XI");
    }

    @Test
    void scenarioG_simulateDeterminationEmitsSimulationPreviewWithoutRedaction() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("simulate leave 12", "turn-sim-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        String rawJson = objectMapper.writeValueAsString(resultEvent.getPayload());
        assertThat(rawJson).doesNotContain("[REDACTED]");

        JsonNode blocks = resultEvent.getPayload().path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("simulation-preview");
        assertThat(blocks.get(1).path("nonPersistent").asBoolean()).isTrue();
        assertThat(blocks.get(1).path("determinationId").asText()).isEqualTo("preview");
    }

    @Test
    void scenarioH_prepareAndConfirmActionWithIdempotencyAndCrossUserIsolation() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("prepare approve 501", "turn-prep-confirm-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        String rawJson = objectMapper.writeValueAsString(resultEvent.getPayload());
        assertThat(rawJson).doesNotContain("[REDACTED]");

        JsonNode blocks = resultEvent.getPayload().path("assistantContent").path("blocks");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("action-proposals");
        JsonNode proposalItem = blocks.get(1).path("items").get(0);
        String proposalId = proposalItem.path("proposalId").asText();
        String expectedEtag = proposalItem.path("expectedVersionTag").asText();
        assertThat(proposalId).isNotBlank();
        assertThat(expectedEtag).isNotBlank();

        // Cross-user cannot confirm another user's proposal
        mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", "other-user")
                        .requestAttr("environment", ENV)
                        .header("If-Match", expectedEtag)
                        .header("Idempotency-Key", "idem-cross")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        // Authorized confirmation executes command once and returns updated ETag
        MvcResult confirmRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("If-Match", expectedEtag)
                        .header("Idempotency-Key", "idem-approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode confirmBody = objectMapper.readTree(confirmRes.getResponse().getContentAsByteArray());
        assertThat(confirmBody.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmBody.path("succeeded").asBoolean()).isTrue();
        assertThat(confirmBody.path("updatedLifecycleState").asText()).isEqualTo("APPROVED");
        assertThat(stubLeaveParcelController.approveInvocations.get()).isEqualTo(1);

        // Idempotent replay with same Idempotency-Key returns 200 without re-invoking controller
        MvcResult replayRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("If-Match", expectedEtag)
                        .header("Idempotency-Key", "idem-approve-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode replayBody = objectMapper.readTree(replayRes.getResponse().getContentAsByteArray());
        assertThat(replayBody.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(stubLeaveParcelController.approveInvocations.get()).isEqualTo(1);
    }

    @Test
    void scenarioI_validationErrorKeepsPendingStaleReturns412AndRejectMarksRejected() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("prepare incomplete 501", "turn-prep-err-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());
        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();

        JsonNode proposalItem = null;
        for (JsonNode block : resultEvent.getPayload().path("assistantContent").path("blocks")) {
            if ("action-proposals".equals(block.path("type").asText())) {
                proposalItem = block.path("items").get(0);
                break;
            }
        }
        assertThat(proposalItem).isNotNull();
        String proposalId = proposalItem.path("proposalId").asText();
        String expectedEtag = proposalItem.path("expectedVersionTag").asText();
        assertThat(proposalItem.path("missingRequiredFields")).hasSize(1);

        // 422 missing required field keeps proposal PENDING
        MvcResult valRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("If-Match", expectedEtag)
                        .header("Idempotency-Key", "idem-val-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        JsonNode valBody = objectMapper.readTree(valRes.getResponse().getContentAsByteArray());
        assertThat(valBody.path("status").asText()).isEqualTo("PENDING");
        assertThat(valBody.path("fieldErrors").has("notes")).isTrue();

        // Concurrent bump -> 412 STALE with refreshedSnapshot
        stubLeaveParcelController.bumpConcurrentVersion();
        MvcResult staleRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("If-Match", expectedEtag)
                        .header("Idempotency-Key", "idem-stale-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{\"notes\":\"Justificativa válida\"}}"))
                .andExpect(status().isPreconditionFailed())
                .andReturn();
        JsonNode staleBody = objectMapper.readTree(staleRes.getResponse().getContentAsByteArray());
        assertThat(staleBody.path("status").asText()).isEqualTo("STALE");
        assertThat(staleBody.path("refreshedSnapshot").has("record")).isTrue();

        // Reject updates proposal to REJECTED
        MvcResult rejectRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/reject", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(rejectRes.getResponse().getContentAsByteArray()).path("status").asText())
                .isEqualTo("REJECTED");
    }

    @Test
    void scenarioJ_concurrentConfirmExecutesActionOnceAcrossParallelRequests() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("prepare approve 501", "turn-prep-conc-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());
        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();
        JsonNode proposalItem = null;
        for (JsonNode block : resultEvent.getPayload().path("assistantContent").path("blocks")) {
            if ("action-proposals".equals(block.path("type").asText())) {
                proposalItem = block.path("items").get(0);
                break;
            }
        }
        assertThat(proposalItem).isNotNull();
        String proposalId = proposalItem.path("proposalId").asText();
        String expectedEtag = proposalItem.path("expectedVersionTag").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(3, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                                .requestAttr("tenantId", TENANT)
                                .requestAttr("userId", USER)
                                .requestAttr("environment", ENV)
                                .header("If-Match", expectedEtag)
                                .header("Idempotency-Key", "idem-conc-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
            Future<Integer> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(3, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                                .requestAttr("tenantId", TENANT)
                                .requestAttr("userId", USER)
                                .requestAttr("environment", ENV)
                                .header("If-Match", expectedEtag)
                                .header("Idempotency-Key", "idem-conc-2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            });
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = List.of(f1.get(5, TimeUnit.SECONDS), f2.get(5, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(stubLeaveParcelController.approveInvocations.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void scenarioK_maskedCpfPrefillPreservedOnConfirmAndExecutionReceiptIsMinimized() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("prepare masked-cpf 501", "turn-prep-cpf-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());
        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();
        JsonNode proposalItem = null;
        for (JsonNode block : resultEvent.getPayload().path("assistantContent").path("blocks")) {
            if ("action-proposals".equals(block.path("type").asText())) {
                proposalItem = block.path("items").get(0);
                break;
            }
        }
        assertThat(proposalItem).isNotNull();
        String proposalId = proposalItem.path("proposalId").asText();
        String expectedEtag = proposalItem.path("expectedVersionTag").asText();
        assertThat(proposalItem.path("prefill").path("substituteTaxId").asText()).isEqualTo("***.982.247-**");

        MvcResult confirmRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("If-Match", expectedEtag)
                        .header("Idempotency-Key", "idem-cpf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\":{\"notes\":\"Escala confirmada\",\"substituteTaxId\":\"***.982.247-**\"}}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode confirmBody = objectMapper.readTree(confirmRes.getResponse().getContentAsByteArray());
        assertThat(confirmBody.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(stubLeaveParcelController.lastReceivedSubstituteTaxId.get()).isEqualTo("52998224725");

        String receiptJson = jdbcTemplate.queryForObject(
                "select execution_receipt_json from ai_agent_action_proposal where proposal_id = ?",
                String.class,
                UUID.fromString(proposalId));
        assertThat(receiptJson)
                .isNotBlank()
                .doesNotContain("52998224725")
                .doesNotContain("Lucas Andrade");
        JsonNode receiptNode = objectMapper.readTree(receiptJson);
        assertThat(receiptNode.path("uiResult").isEmpty()).isTrue();
    }

    @Test
    void scenarioL_completeFormConfirmsProposalAndExpiredRejectReturns409() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start1 = startStream("prepare approve 501", "turn-prep-form-1");
        List<AiTurnEventEnvelope> events1 = readSseEvents(start1.getStreamId());
        String proposalId1 = events1.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst().orElseThrow()
                .getPayload().path("assistantContent").path("blocks").get(1).path("items").get(0)
                .path("proposalId").asText();

        MvcResult completeRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/complete-form", proposalId1)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updatedVersionTag\":\"\\\"v-form-2\\\"\",\"updatedLifecycleState\":\"APPROVED\",\"idempotencyKey\":\"idem-form-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode completeBody = objectMapper.readTree(completeRes.getResponse().getContentAsByteArray());
        assertThat(completeBody.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(completeBody.path("outcomeCode").asText()).isEqualTo("CONFIRMED_VIA_FORM");
        assertThat(completeBody.path("updatedLifecycleState").asText()).isEqualTo("APPROVED");

        mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId1)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("Idempotency-Key", "idem-after-form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        AgenticAuthoringTurnStreamStartResponse start2 = startStream("prepare approve 501", "turn-prep-exp-1");
        List<AiTurnEventEnvelope> events2 = readSseEvents(start2.getStreamId());
        String proposalId2 = events2.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst().orElseThrow()
                .getPayload().path("assistantContent").path("blocks").get(1).path("items").get(0)
                .path("proposalId").asText();

        jdbcTemplate.update(
                "update ai_agent_action_proposal set expires_at = ? where proposal_id = ?",
                Timestamp.from(Instant.now().minusSeconds(300)),
                UUID.fromString(proposalId2));

        MvcResult expiredRejectRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/reject", proposalId2)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode expiredRejectBody = objectMapper.readTree(expiredRejectRes.getResponse().getContentAsByteArray());
        assertThat(expiredRejectBody.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(expiredRejectBody.path("outcomeCode").asText()).isEqualTo("PROPOSAL_EXPIRED");
    }

    @Test
    void scenarioM_multiPodSseDbReplayStreamsEventsFromAnotherPod() throws Exception {
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expires = Timestamp.from(Instant.now().plusSeconds(600));

        jdbcTemplate.update(
                "insert into ai_thread (thread_id, tenant_id, environment, user_id, component_type, component_id, status, created_at, last_used_at, version) "
                        + "values (?, ?, ?, ?, 'operational-agent', 'workspace', 'ACTIVE', ?, ?, 0)",
                threadId, TENANT, ENV, USER, now, now);
        jdbcTemplate.update(
                "insert into ai_turn (thread_id, turn_id, status, created_at, updated_at, expires_at, next_event_seq) "
                        + "values (?, ?, 'RUNNING', ?, ?, ?, 2)",
                threadId, turnId, now, now, expires);
        jdbcTemplate.update(
                "insert into ai_turn_event (tenant_id, user_id, environment, stream_id, thread_id, turn_id, seq, event_id, event_type, payload, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, 1, ?, 'progress', ?, ?)",
                TENANT, USER, ENV, streamId, threadId, turnId, UUID.randomUUID(),
                "{\"phase\":\"thinking\",\"statusText\":\"Consultando...\"}", now);

        MvcResult asyncResult = mockMvc.perform(get("/api/praxis/config/ai/agent/turn/stream/{streamId}", streamId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        Thread writerPod = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(150);
                Timestamp doneTs = Timestamp.from(Instant.now());
                jdbcTemplate.update(
                        "insert into ai_turn_event (tenant_id, user_id, environment, stream_id, thread_id, turn_id, seq, event_id, event_type, payload, created_at) "
                                + "values (?, ?, ?, ?, ?, ?, 2, ?, 'result', ?, ?)",
                        TENANT, USER, ENV, streamId, threadId, turnId, UUID.randomUUID(),
                        "{\"state\":\"completed\",\"assistantMessage\":\"Resposta gravada por outro pod.\"}",
                        doneTs);
                jdbcTemplate.update(
                        "update ai_turn set status = 'COMPLETED', terminal_event_type = 'result', next_event_seq = 3, updated_at = ? where thread_id = ? and turn_id = ?",
                        doneTs, threadId, turnId);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        writerPod.start();

        MvcResult completed = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();
        writerPod.join(5000);

        List<AiTurnEventEnvelope> events = parseSseBody(completed.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(events).extracting(AiTurnEventEnvelope::getType).containsExactly("progress", "result");
        assertThat(events.get(1).getPayload().path("assistantMessage").asText())
                .isEqualTo("Resposta gravada por outro pod.");
    }

    @Test
    void scenarioN_prepareBulkOperationEmitsSafeSseBlocksBlocksChatConfirmAndCompletesViaModal() throws Exception {
        AgenticAuthoringTurnStreamStartResponse start = startStream("prepare bulk mixed", "turn-bulk-mixed-1");
        List<AiTurnEventEnvelope> events = readSseEvents(start.getStreamId());

        AiTurnEventEnvelope resultEvent = events.stream()
                .filter(e -> "result".equals(e.getType()))
                .findFirst()
                .orElseThrow();
        String rawJson = objectMapper.writeValueAsString(resultEvent.getPayload());
        assertThat(rawJson).doesNotContain("[REDACTED]");

        JsonNode proposalItem = null;
        for (JsonNode block : resultEvent.getPayload().path("assistantContent").path("blocks")) {
            if ("action-proposals".equals(block.path("type").asText())) {
                proposalItem = block.path("items").get(0);
                break;
            }
        }
        assertThat(proposalItem).isNotNull();
        String proposalId = proposalItem.path("proposalId").asText();
        assertThat(proposalItem.path("proposalStatus").asText()).isEqualTo("PENDING");
        JsonNode bulkNode = proposalItem.path("bulk");
        assertThat(bulkNode.path("domainProposalId").asText()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(bulkNode.path("domainProposalStatus").asText()).isEqualTo("BLOCKED");
        assertThat(bulkNode.path("totals").path("executable").asLong()).isEqualTo(1L);
        assertThat(bulkNode.path("totals").path("blocked").asLong()).isEqualTo(1L);
        assertThat(bulkNode.path("blockedItems").get(0).path("diagnostics").get(0).path("issueNote").asText())
                .isEqualTo("Parcela 502 fechada para alteração.");
        assertThat(bulkNode.path("bulkContract").path("confirmUrl").asText())
                .isEqualTo("/api/workforce/bulk-leave-parcels/bulk/update-items");

        // Inline /confirm on a bulk proposal must return 409 BULK_REQUIRES_MODAL_REVIEW
        MvcResult confirmErr = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/confirm", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .header("Idempotency-Key", "idem-bulk-inline-block")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(objectMapper.readTree(confirmErr.getResponse().getContentAsByteArray()).path("outcomeCode").asText())
                .isEqualTo("BULK_REQUIRES_MODAL_REVIEW");

        // /complete-form reconciles modal execution counts and outcomeCode into ai_agent_action_proposal
        MvcResult completeRes = mockMvc.perform(post("/api/praxis/config/ai/agent/proposals/{proposalId}/complete-form", proposalId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domainProposalId\":\"22222222-2222-4222-8222-222222222222\",\"bulkExecutionId\":\"exec-bulk-1\",\"bulkExecutionStatus\":\"COMPLETED_WITH_ERRORS\",\"confirmedCount\":1,\"unchangedCount\":0,\"failedCount\":1,\"idempotencyKey\":\"idem-modal-bulk-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode completeBody = objectMapper.readTree(completeRes.getResponse().getContentAsByteArray());
        assertThat(completeBody.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(completeBody.path("outcomeCode").asText()).isEqualTo("BULK_COMPLETED_WITH_ERRORS");

        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                "select bulk_proposal_id, bulk_execution_id, bulk_mode, bulk_totals_json from ai_agent_action_proposal where proposal_id = ?",
                UUID.fromString(proposalId));
        assertThat(dbRow.get("bulk_proposal_id")).isEqualTo("22222222-2222-4222-8222-222222222222");
        assertThat(dbRow.get("bulk_execution_id")).isEqualTo("exec-bulk-1");
        assertThat(dbRow.get("bulk_mode")).isEqualTo("PER_ITEM_UPDATE");
        assertThat(String.valueOf(dbRow.get("bulk_totals_json"))).contains("\"confirmedCount\":1").contains("\"failedCount\":1");
    }

    private List<AiTurnEventEnvelope> parseSseBody(String body) throws Exception {
        List<AiTurnEventEnvelope> events = new ArrayList<>();
        StringBuilder dataBuf = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                if (dataBuf.length() > 0) {
                    dataBuf.append('\n');
                }
                dataBuf.append(line.substring(5).trim());
            } else if (line.isBlank() && dataBuf.length() > 0) {
                events.add(objectMapper.readValue(dataBuf.toString(), AiTurnEventEnvelope.class));
                dataBuf.setLength(0);
            }
        }
        if (dataBuf.length() > 0) {
            events.add(objectMapper.readValue(dataBuf.toString(), AiTurnEventEnvelope.class));
        }
        return events;
    }

    private AgenticAuthoringTurnStreamStartResponse startStream(String prompt, String clientTurnId) throws Exception {
        OperationalAgentTurnStreamRequest req = new OperationalAgentTurnStreamRequest(
                prompt, null, clientTurnId, null, null, null, List.of(), "gemini", "gemini-2.5-flash", null, null);
        MvcResult result = mockMvc.perform(post("/api/praxis/config/ai/agent/turn/stream/start")
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsByteArray(),
                AgenticAuthoringTurnStreamStartResponse.class);
    }

    private List<AiTurnEventEnvelope> readSseEvents(UUID streamId) throws Exception {
        awaitTerminalEvent(streamId);
        MvcResult asyncResult = mockMvc.perform(get("/api/praxis/config/ai/agent/turn/stream/{streamId}", streamId)
                        .requestAttr("tenantId", TENANT)
                        .requestAttr("userId", USER)
                        .requestAttr("environment", ENV)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<AiTurnEventEnvelope> events = new ArrayList<>();
        StringBuilder dataBuf = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                if (dataBuf.length() > 0) {
                    dataBuf.append('\n');
                }
                dataBuf.append(line.substring(5).trim());
            } else if (line.isBlank() && dataBuf.length() > 0) {
                events.add(objectMapper.readValue(dataBuf.toString(), AiTurnEventEnvelope.class));
                dataBuf.setLength(0);
            }
        }
        if (dataBuf.length() > 0) {
            events.add(objectMapper.readValue(dataBuf.toString(), AiTurnEventEnvelope.class));
        }
        return events;
    }

    private void awaitTerminalEvent(UUID streamId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from ai_turn_event where stream_id = ? and event_type in ('result', 'error', 'cancelled')",
                    Integer.class,
                    streamId);
            if (count != null && count > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Timed out waiting for terminal operational agent event for stream " + streamId);
    }
}
