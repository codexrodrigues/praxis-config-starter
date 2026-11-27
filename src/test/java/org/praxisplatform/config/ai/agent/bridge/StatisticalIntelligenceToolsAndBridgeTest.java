package org.praxisplatform.config.ai.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.model.AgentModelTurnResponse;
import org.praxisplatform.config.ai.agent.model.AgentTokenUsage;
import org.praxisplatform.config.ai.agent.model.AgentToolCall;
import org.praxisplatform.config.ai.agent.model.AiToolCallingProvider;
import org.praxisplatform.config.ai.agent.provider.ProfileSovereigntyAiProviderEligibilityPolicy;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.PraxisOperationalAgentLoop;
import org.praxisplatform.config.ai.agent.runtime.StatisticalIntelligenceBlockContributor;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentEpistemicEnvelope;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalAnalysisResult;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool;
import org.praxisplatform.config.ai.agent.tools.RunResourceCounterfactualSearchTool;
import org.praxisplatform.config.ai.agent.tools.RunResourceStatisticalInferenceTool;
import org.praxisplatform.config.ai.agent.tools.RunResourceStochasticSimulationTool;
import org.praxisplatform.config.ai.agent.tools.ScanResourceAnomaliesTool;
import org.praxisplatform.config.autoconfig.OperationalAgentAutoConfiguration;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;
import org.praxisplatform.intelligence.contracts.IntelligenceContractVersion;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyDecompositionStep;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyFinding;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyMethod;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanSummary;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScope;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalySeverity;
import org.praxisplatform.intelligence.contracts.anomaly.DecompositionStepRole;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualCandidate;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualDirection;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualFeasibilityStatus;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverDelta;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualRequest;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualResponse;
import org.praxisplatform.intelligence.contracts.error.IntelligenceErrorCode;
import org.praxisplatform.intelligence.contracts.error.IntelligenceExecutionException;
import org.praxisplatform.intelligence.contracts.epistemic.EpistemicKind;
import org.praxisplatform.intelligence.contracts.inference.InferenceGroupResult;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.inference.InferenceTestKind;
import org.praxisplatform.intelligence.contracts.jobs.IntelligenceJobReceipt;
import org.praxisplatform.intelligence.contracts.modelcard.ModelCardDescriptor;
import org.praxisplatform.intelligence.contracts.provenance.AnalyticalProvenance;
import org.praxisplatform.intelligence.contracts.provenance.SuppressionStatus;
import org.praxisplatform.intelligence.contracts.simulation.AnchorRevalidationStatus;
import org.praxisplatform.intelligence.contracts.simulation.AnchorScenarioPoint;
import org.praxisplatform.intelligence.contracts.simulation.FanChartPoint;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationRequest;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationResponse;
import org.praxisplatform.intelligence.contracts.simulation.ThresholdBreachOutcome;
import org.praxisplatform.intelligence.contracts.simulation.ThresholdDirection;
import org.praxisplatform.intelligence.contracts.simulation.TornadoSensitivityDriver;
import org.praxisplatform.intelligence.contracts.spi.AnchorScenarioRevalidator;
import org.praxisplatform.intelligence.contracts.spi.CounterfactualRuleValidator;
import org.praxisplatform.intelligence.contracts.spi.CounterfactualSearchOrchestrator;
import org.praxisplatform.intelligence.contracts.spi.ResourceIntelligenceExecutor;
import org.praxisplatform.intelligence.contracts.spi.StochasticSimulationOrchestrator;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.praxisplatform.uischema.annotation.AiUsagePolicy;
import org.praxisplatform.uischema.annotation.AiVisibilityMode;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceFilterRequest;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.dto.AnomalyScanStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsRequest;
import org.praxisplatform.uischema.stats.dto.ComparisonStatsResponse;
import org.praxisplatform.uischema.stats.dto.CounterfactualStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.InferenceStatsRequest;
import org.praxisplatform.uischema.stats.dto.StochasticSimulationStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("unit")
class StatisticalIntelligenceToolsAndBridgeTest {

    private static final String SHA_SNAPSHOT = "b".repeat(64);
    private static final String SHA_LOCK = "a".repeat(64);
    private static final String SHA_ASSUMPTIONS = "c".repeat(64);

    public static class PayrollSlipDto {
        private Long id;
        @UISchema(label = "Coorte")
        private String cohortKey;
        @UISchema(label = "Valor esperado")
        private BigDecimal expectedValue;
        @UISchema(label = "Valor observado")
        private BigDecimal observedValue;
        @DomainGovernance(
                classification = DomainClassification.RESTRICTED,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.DENY))
        private BigDecimal confidentialBonus;

        public Long getId() {
            return id;
        }

        public String getCohortKey() {
            return cohortKey;
        }

        public BigDecimal getExpectedValue() {
            return expectedValue;
        }

        public BigDecimal getObservedValue() {
            return observedValue;
        }

        public BigDecimal getConfidentialBonus() {
            return confidentialBonus;
        }
    }

    public static class PayrollSlipFilterDto implements GenericFilterDTO {
        private String cohortKey;
        @Filterable(operation = Filterable.FilterOperation.EQUAL, relation = "confidentialBonus")
        private BigDecimal minConfidentialBonus;
        private BigDecimal confidentialBonus;

        public String getCohortKey() {
            return cohortKey;
        }

        public void setCohortKey(String cohortKey) {
            this.cohortKey = cohortKey;
        }

        public BigDecimal getMinConfidentialBonus() {
            return minConfidentialBonus;
        }

        public void setMinConfidentialBonus(BigDecimal minConfidentialBonus) {
            this.minConfidentialBonus = minConfidentialBonus;
        }

        public BigDecimal getConfidentialBonus() {
            return confidentialBonus;
        }

        public void setConfidentialBonus(BigDecimal confidentialBonus) {
            this.confidentialBonus = confidentialBonus;
        }
    }

    public static class StubPayrollService implements BaseResourceQueryService<PayrollSlipDto, Long, PayrollSlipFilterDto> {
        Function<InferenceStatsRequest<PayrollSlipFilterDto>, InferenceResponse> inferenceHandler;
        Function<AnomalyScanStatsRequest<PayrollSlipFilterDto>, AnomalyScanResponse> anomalyHandler;
        Function<StochasticSimulationStatsRequest<PayrollSlipFilterDto>, StochasticSimulationResponse> simulationHandler;
        Function<CounterfactualStatsRequest<PayrollSlipFilterDto>, CounterfactualResponse> counterfactualHandler;

        @Override public String getIdFieldName() { return "id"; }
        @Override public Sort getDefaultSort() { return Sort.by("id"); }
        @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
        @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getInferenceStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getAnomalyStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getSimulationStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getCounterfactualStatsSupportMode() { return StatsSupportMode.AUTO; }

        @Override
        public StatsFieldRegistry getStatsFieldRegistry() {
            return StatsFieldRegistry.builder()
                    .groupByBucket("cohortKey", "cohortKey", Set.of(StatsMetric.COUNT))
                    .numericHistogramMeasureField("expectedValue", "expectedValue")
                    .numericHistogramMeasureField("observedValue", "observedValue")
                    .numericHistogramMeasureField("confidentialBonus", "confidentialBonus")
                    .build();
        }

        @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
        @Override public boolean hasOptionSource(String sourceKey) { return false; }
        @Override public OptionSourceDescriptor resolveOptionSource(String sourceKey) { return null; }
        @Override public ResourceRepresentationResult<PayrollSlipDto> findById(Long id) { return null; }
        @Override public List<PayrollSlipDto> findAll() { return List.of(); }
        @Override public List<PayrollSlipDto> findAllById(Collection<Long> ids) { return List.of(); }
        @Override public Page<PayrollSlipDto> filter(PayrollSlipFilterDto filter, Pageable pageable, Collection<Long> includeIds) { return Page.empty(); }
        @Override public CursorPage<PayrollSlipDto> filterByCursor(PayrollSlipFilterDto filter, Sort sort, String after, String before, int size) { return null; }
        @Override public OptionalLong locate(PayrollSlipFilterDto filter, Sort sort, Long id) { return OptionalLong.empty(); }
        @Override public Page<OptionDTO<Long>> filterOptions(PayrollSlipFilterDto filter, Pageable pageable) { return Page.empty(); }
        @Override public List<OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
        @Override public Page<OptionDTO<Object>> filterOptionSourceOptions(String sourceKey, OptionSourceFilterRequest<PayrollSlipFilterDto> request, Pageable pageable) { return Page.empty(); }
        @Override public List<OptionDTO<Object>> byIdsOptionSourceOptions(String sourceKey, Collection<Object> ids) { return List.of(); }
        @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<PayrollSlipFilterDto> request) { return null; }
        @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<PayrollSlipFilterDto> request) { return null; }
        @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<PayrollSlipFilterDto> request) { return null; }
        @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<PayrollSlipFilterDto> request) { return null; }

        @Override
        public InferenceResponse inferenceStats(InferenceStatsRequest<PayrollSlipFilterDto> request) {
            return inferenceHandler.apply(request);
        }

        @Override
        public AnomalyScanResponse anomalyScanStats(AnomalyScanStatsRequest<PayrollSlipFilterDto> request) {
            return anomalyHandler.apply(request);
        }

        @Override
        public StochasticSimulationResponse stochasticSimulationStats(
                StochasticSimulationStatsRequest<PayrollSlipFilterDto> request) {
            return simulationHandler.apply(request);
        }

        @Override
        public CounterfactualResponse counterfactualStats(
                CounterfactualStatsRequest<PayrollSlipFilterDto> request) {
            return counterfactualHandler.apply(request);
        }
    }

    @ApiResource(
            value = "/api/workforce/payroll-slips",
            resourceKey = "workforce.payroll-slips",
            title = "Payroll Slips",
            description = "Payroll slips analytics resource",
            identity = @ResourceIdentity(keyField = "id", titleField = "cohortKey"))
    public static class StubPayrollController extends AbstractResourceQueryController<PayrollSlipDto, Long, PayrollSlipFilterDto> {
        private final StubPayrollService service;

        StubPayrollController(StubPayrollService service) {
            this.service = service;
        }

        @Override
        protected BaseResourceQueryService<PayrollSlipDto, Long, PayrollSlipFilterDto> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(PayrollSlipDto dto) {
            return dto.getId();
        }
    }

    @Test
    void enforcesNonSignificanceSuppressionMaskingAndFailClosedEngineUnavailable() {
        StubPayrollService service = new StubPayrollService();
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubPayrollController(service)),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "workforce.payroll-slips", "workforce.payroll-slips", "/api/workforce/payroll-slips",
                        "Payroll Slips", "Workforce payroll slips", "payments",
                        "id", "cohortKey", "cohortKey", List.of("cohortKey"),
                        null, "cohortKey", null, "/payroll/{id}", "payroll", 100))),
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
                "tenant-a", "auditor", "prod", "auditor",
                Set.of("ROLE_READ"), null, null, Map.of(), Map.of());

        AnalyticalProvenance provenance = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-CARD-01", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.CALIBRATED_PROBABILITY,
                320L, 1, "corr-1", "job-1", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor card = new ModelCardDescriptor(
                "WF-CARD-01", "1.0.0", "Workforce Payroll Statistical Audit", "workforce.payroll",
                "ROBUST_MAD_FDR", EpistemicKind.CALIBRATED_PROBABILITY,
                "Detect calibrated payroll deviations.", List.of("No auto withhold"),
                15, List.of("Independent cohorts"), "Human review required");

        // 1. Inference: non-significant exact group + k-anonymity suppressed group
        service.inferenceHandler = req -> new InferenceResponse(
                IntelligenceContractVersion.V1,
                provenance,
                card,
                List.of(
                        new InferenceGroupResult(
                                "cohort_admin",
                                InferenceTestKind.WILCOXON_SIGNED_RANK,
                                0.31d, 0.72d, 0.85d, 0.04d, "rank_biserial", -0.05d, 0.12d,
                                120L, 120L, new BigDecimal("50.00"),
                                false, false, SuppressionStatus.EXACT_RELEASE, "No shift"),
                        new InferenceGroupResult(
                                "cohort_tiny",
                                InferenceTestKind.MANN_WHITNEY_U,
                                0.0d, 1.0d, 1.0d, 0.0d, "rank_biserial", null, null,
                                3L, 3L, new BigDecimal("777.77"),
                                false, false, SuppressionStatus.SUPPRESSED_PRIMARY_K_ANONYMITY, "Hidden counts")));

        RunResourceStatisticalInferenceTool inferenceTool = new RunResourceStatisticalInferenceTool(bridge);
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("Execute inference");

        Map<String, Object> inferenceOut = inferenceTool.execute(
                ctx,
                vault,
                artifacts,
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "tests", List.of("WILCOXON_SIGNED_RANK"),
                        "groupByField", "cohortKey",
                        "expectedField", "expectedValue",
                        "observedField", "observedValue"));

        @SuppressWarnings("unchecked")
        Map<String, Object> envMap = (Map<String, Object>) inferenceOut.get("epistemicEnvelope");
        assertThat(envMap.get("statisticallySignificant")).isEqualTo(Boolean.FALSE);
        assertThat(envMap.get("suppressedCellCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) inferenceOut.get("groups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("nonAnomalyNote").toString()).contains("nao classificar como anomalia");
        Map<String, Object> suppressedGroup = groups.get(1);
        assertThat(suppressedGroup).containsEntry("suppressed", Boolean.TRUE);
        assertThat(suppressedGroup).doesNotContainKeys("sampleSizeA", "sampleSizeB", "aggregateImpact", "statistic");
        assertThat(artifacts.extensionArtifacts()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> inferenceCounters = (Map<String, Object>)
                artifacts.extensionArtifacts().get(0).uiPayload().get("scanCounters");
        assertThat(inferenceCounters).containsEntry("groupsEvaluated", 2);

        // 2. Anomaly scan with one exact finding and one k-anonymity suppressed finding
        AnomalyFinding exactFinding = new AnomalyFinding(
                "f-exact", AnomalyMethod.ROBUST_MAD_ZSCORE, AnomalyScope.COHORT_GROUP,
                null, "cohort_tech", "observedValue",
                new BigDecimal("10450.00"), new BigDecimal("10000.00"), new BigDecimal("450.00"),
                new BigDecimal("10.00"), new BigDecimal("5.00"), 1.2d, 0.40d, 0.55d, 0.05d,
                AnomalySeverity.INFO, new BigDecimal("450.00"), 45, SuppressionStatus.EXACT_RELEASE,
                List.of(new AnomalyDecompositionStep(
                        "residual", "Residual delta", new BigDecimal("450.00"), "BRL", DecompositionStepRole.RESULT)));
        AnomalyFinding suppressedFinding = new AnomalyFinding(
                "f-sup", AnomalyMethod.ROBUST_MAD_ZSCORE, AnomalyScope.INDIVIDUAL_ENTITY,
                "opaque-1234", "cohort_micro", "observedValue",
                new BigDecimal("9999.00"), new BigDecimal("1000.00"), new BigDecimal("8999.00"),
                new BigDecimal("0.00"), new BigDecimal("1.00"), 8.0d, 0.0001d, 0.001d, 0.90d,
                AnomalySeverity.CRITICAL, new BigDecimal("8999.00"), 2, SuppressionStatus.SUPPRESSED_PRIMARY_K_ANONYMITY,
                List.of());

        service.anomalyHandler = req -> new AnomalyScanResponse(
                IntelligenceContractVersion.V1,
                provenance,
                card,
                List.of(exactFinding, suppressedFinding),
                new AnomalyScanSummary(320L, 4, 2, 1, 0, 1, new BigDecimal("450.00"), false));

        ScanResourceAnomaliesTool anomalyTool = new ScanResourceAnomaliesTool(bridge);
        Map<String, Object> anomalyOut = anomalyTool.execute(
                ctx,
                vault,
                artifacts,
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "methods", List.of("ROBUST_MAD_ZSCORE"),
                        "columnMapping", Map.of(
                                "cohortColumn", "cohortKey",
                                "expectedColumn", "expectedValue",
                                "observedColumn", "observedValue",
                                "residualColumn", "observedValue")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findingsOut = (List<Map<String, Object>>) anomalyOut.get("findings");
        assertThat(findingsOut).hasSize(2);
        assertThat(findingsOut.get(0)).containsKey("nonAnomalyNote");
        assertThat(findingsOut.get(1))
                .containsEntry("suppressed", Boolean.TRUE)
                .doesNotContainKeys("observedValue", "expectedValue", "residualValue", "financialImpact", "affectedCount", "opaqueEntityKey");
        assertThat(artifacts.extensionArtifacts()).hasSize(2);

        // 3. Engine unavailable (503) returns fail-closed without throwing and records epistemic badge artifact
        service.anomalyHandler = req -> {
            throw new IntelligenceExecutionException(
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE,
                    "Python intelligence service offline");
        };
        Map<String, Object> unavailableOut = anomalyTool.execute(
                ctx,
                vault,
                artifacts,
                Map.of("entityKey", "workforce.payroll-slips", "methods", List.of("ROBUST_MAD_ZSCORE")));
        assertThat(unavailableOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE);
        assertThat(unavailableOut).containsEntry("errorCode", "INTELLIGENCE_ENGINE_UNAVAILABLE");
        assertThat(artifacts.extensionArtifacts()).hasSize(3);
        assertThat(artifacts.extensionArtifacts().get(2).epistemicEnvelope().availabilityState())
                .isEqualTo(AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE);
        assertThat(artifacts.extensionArtifacts().get(2).epistemicEnvelope().statisticallySignificant()).isFalse();
    }

    @Test
    void autoConfigurationBootsCleanlyWithoutOptionalIntelligenceExecutorAndRegistersSovereigntyPolicy() {
        // 1. Without ResourceIntelligenceExecutor bean (even with classes on classpath), tools are hidden by default
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OperationalAgentAutoConfiguration.class))
                .withBean(AiToolCallingProvider.class, () -> mock(AiToolCallingProvider.class))
                .withBean(AiProviderManagementService.class, () -> mock(AiProviderManagementService.class))
                .withBean(AgentResourceExecutionBridge.class, () -> mock(AgentResourceExecutionBridge.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OperationalAssistantBlockComposer.class);
                    assertThat(context).hasSingleBean(StatisticalIntelligenceBlockContributor.class);
                    assertThat(context).hasSingleBean(ProfileSovereigntyAiProviderEligibilityPolicy.class);
                    assertThat(context).hasSingleBean(PraxisOperationalAgentLoop.class);
                    assertThat(context).doesNotHaveBean(RunResourceStatisticalInferenceTool.class);
                    assertThat(context).doesNotHaveBean(ScanResourceAnomaliesTool.class);
                    assertThat(context).doesNotHaveBean(RunResourceStochasticSimulationTool.class);
                    assertThat(context).doesNotHaveBean(RunResourceCounterfactualSearchTool.class);
                });

        // 2. With opt-in property expose-when-unavailable=true, tools register in fail-closed mode
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OperationalAgentAutoConfiguration.class))
                .withPropertyValues("praxis.ai.agent.statistical-tools.expose-when-unavailable=true")
                .withBean(AiToolCallingProvider.class, () -> mock(AiToolCallingProvider.class))
                .withBean(AiProviderManagementService.class, () -> mock(AiProviderManagementService.class))
                .withBean(AgentResourceExecutionBridge.class, () -> mock(AgentResourceExecutionBridge.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RunResourceStatisticalInferenceTool.class);
                    assertThat(context).hasSingleBean(ScanResourceAnomaliesTool.class);
                    assertThat(context).hasSingleBean(RunResourceStochasticSimulationTool.class);
                    assertThat(context).hasSingleBean(RunResourceCounterfactualSearchTool.class);
                });

        // 3. With ResourceIntelligenceExecutor bean active, tools register automatically
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OperationalAgentAutoConfiguration.class))
                .withBean(AiToolCallingProvider.class, () -> mock(AiToolCallingProvider.class))
                .withBean(AiProviderManagementService.class, () -> mock(AiProviderManagementService.class))
                .withBean(AgentResourceExecutionBridge.class, () -> mock(AgentResourceExecutionBridge.class))
                .withBean(ResourceIntelligenceExecutor.class, () -> mock(ResourceIntelligenceExecutor.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RunResourceStatisticalInferenceTool.class);
                    assertThat(context).hasSingleBean(ScanResourceAnomaliesTool.class);
                    assertThat(context).hasSingleBean(RunResourceStochasticSimulationTool.class);
                    assertThat(context).hasSingleBean(RunResourceCounterfactualSearchTool.class);
                });

        // 4. With intelligence classes absent via FilteredClassLoader, auto-config still boots cleanly
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OperationalAgentAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "org.praxisplatform.intelligence.contracts",
                        "org.praxisplatform.uischema"))
                .withBean(AiToolCallingProvider.class, () -> mock(AiToolCallingProvider.class))
                .withBean(AiProviderManagementService.class, () -> mock(AiProviderManagementService.class))
                .withBean(AgentResourceExecutionBridge.class, () -> mock(AgentResourceExecutionBridge.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RunResourceStatisticalInferenceTool.class);
                    assertThat(context).doesNotHaveBean(ScanResourceAnomaliesTool.class);
                    assertThat(context).doesNotHaveBean(RunResourceStochasticSimulationTool.class);
                    assertThat(context).doesNotHaveBean(RunResourceCounterfactualSearchTool.class);
                });
    }

    @Test
    void enforcesBlockedColumnsFiltersCanonicalDefaultsAndReleasesJdbcTransaction() {
        StubPayrollService service = new StubPayrollService();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubPayrollController(service)),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "workforce.payroll-slips", "workforce.payroll-slips", "/api/workforce/payroll-slips",
                        "Payroll Slips", "Workforce payroll slips", "payments",
                        "id", "cohortKey", "cohortKey", List.of("cohortKey"),
                        null, "cohortKey", null, "/payroll/{id}", "payroll", 100))),
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
                "tenant-a", "auditor", "prod", "auditor",
                Set.of("ROLE_READ"), null, null, Map.of(), Map.of());

        var digest = bridge.describeEntitySchema(ctx, "workforce.payroll-slips").orElseThrow();
        assertThat(digest.statsCapability().inferenceMode()).isEqualTo("AUTO");
        assertThat(digest.statsCapability().anomalyMode()).isEqualTo("AUTO");
        org.mockito.Mockito.reset(txManager);

        AtomicBoolean txActiveDuringCall = new AtomicBoolean(true);
        AnalyticalProvenance provenance = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-CARD-01", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.CALIBRATED_PROBABILITY,
                100L, 0, "corr-tx", "job-tx", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor card = new ModelCardDescriptor(
                "WF-CARD-01", "1.0.0", "Workforce Card", "workforce.payroll",
                "ROBUST_MAD_FDR", EpistemicKind.CALIBRATED_PROBABILITY,
                "Audit payroll", List.of(), 15, List.of(), "Review required");
        service.inferenceHandler = req -> {
            txActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new InferenceResponse(IntelligenceContractVersion.V1, provenance, card, List.of());
        };
        service.anomalyHandler = req -> {
            txActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new AnomalyScanResponse(
                    IntelligenceContractVersion.V1, provenance, card, List.of(),
                    new AnomalyScanSummary(100L, 2, 0, 0, 0, 0, BigDecimal.ZERO, false));
        };

        bridge.runStatisticalInference(ctx, new org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest(
                "workforce.payroll-slips", List.of("WILCOXON_SIGNED_RANK"),
                "cohortKey", "expectedValue", "observedValue", null, null, 0.05d, null, null, null, null, Map.of()));
        assertThat(txActiveDuringCall.get()).isFalse();

        txActiveDuringCall.set(true);
        bridge.scanResourceAnomalies(ctx, new org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest(
                "workforce.payroll-slips", List.of("ROBUST_MAD_ZSCORE"),
                Map.of(), 0.05d, null, null, null, null, false, null, null, null, null, null, Map.of()));
        assertThat(txActiveDuringCall.get()).isFalse();
        verifyNoInteractions(txManager);

        // Governance denials on AiVisibilityMode.DENY field in columns and filters (direct & @Filterable relation)
        assertThatThrownBy(() -> bridge.runStatisticalInference(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest(
                        "workforce.payroll-slips", List.of("WELCH_T"),
                        "cohortKey", "expectedValue", "confidentialBonus", null, null, 0.05d, null, null, null, null, Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("confidentialBonus");

        assertThatThrownBy(() -> bridge.runStatisticalInference(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest(
                        "workforce.payroll-slips", List.of("WELCH_T"),
                        "cohortKey", "expectedValue", "observedValue", null, null, 0.05d, null, null, null, null,
                        Map.of("confidentialBonus", 100))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("confidentialBonus");

        assertThatThrownBy(() -> bridge.runStatisticalInference(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest(
                        "workforce.payroll-slips", List.of("WELCH_T"),
                        "cohortKey", "expectedValue", "observedValue", null, null, 0.05d, null, null, null, null,
                        Map.of("minConfidentialBonus", 100))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("minConfidentialBonus");

        assertThatThrownBy(() -> bridge.scanResourceAnomalies(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest(
                        "workforce.payroll-slips", List.of("ROBUST_MAD_ZSCORE"),
                        Map.of("residualColumn", "confidentialBonus"), 0.05d,
                        null, null, null, null, false, null, null, null, null, null, Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("confidentialBonus");

        // Canonical default column name must also be rejected when DTO field with that canonical name is DENY
        StubLockedObservedController lockedObsCtrl = new StubLockedObservedController();
        MetadataStarterInProcessAgentBridge lockedBridge = new MetadataStarterInProcessAgentBridge(
                List.of(lockedObsCtrl),
                List.of(),
                List.of(),
                List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);
        assertThatThrownBy(() -> lockedBridge.scanResourceAnomalies(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest(
                        "workforce.locked-observed", List.of("ROBUST_MAD_ZSCORE"),
                        Map.of(), 0.05d, null, null, null, null, false, null, null, null, null, null, Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("observedValue");
    }

    @Test
    void endToEndLoopExecutesAnomalyScanProducesFallbackNarrativeAndSurvivesRedactor() {
        StubPayrollService service = new StubPayrollService();
        AnalyticalProvenance provenance = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-CARD-01", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.CALIBRATED_PROBABILITY,
                220L, 0, "corr-loop", "job-loop", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor card = new ModelCardDescriptor(
                "WF-CARD-01", "1.0.0", "Workforce Card", "workforce.payroll",
                "ROBUST_MAD_FDR", EpistemicKind.CALIBRATED_PROBABILITY,
                "Audit payroll", List.of(), 15, List.of(), "Review required");
        service.anomalyHandler = req -> new AnomalyScanResponse(
                IntelligenceContractVersion.V1,
                provenance,
                card,
                List.of(
                        new AnomalyFinding(
                                "f-1", AnomalyMethod.ROBUST_MAD_ZSCORE, AnomalyScope.COHORT_GROUP,
                                null, "cohort_ops", "observedValue",
                                new BigDecimal("5400.00"), new BigDecimal("1200.00"), new BigDecimal("4200.00"),
                                new BigDecimal("1100.00"), new BigDecimal("50.00"), 6.2d, 0.0001d, 0.0005d, 0.72d,
                                AnomalySeverity.CRITICAL, new BigDecimal("4200.00"), 30,
                                SuppressionStatus.EXACT_RELEASE, List.of(
                                        new AnomalyDecompositionStep("cohort_min", "Min", new BigDecimal("900.00"), null, DecompositionStepRole.BASE),
                                        new AnomalyDecompositionStep("cohort_q1", "Q1", new BigDecimal("1020.00"), null, DecompositionStepRole.BASE),
                                        new AnomalyDecompositionStep("cohort_median", "Mediana", new BigDecimal("1100.00"), null, DecompositionStepRole.BASE),
                                        new AnomalyDecompositionStep("cohort_q3", "Q3", new BigDecimal("1220.00"), null, DecompositionStepRole.BASE),
                                        new AnomalyDecompositionStep("cohort_max", "Max", new BigDecimal("1400.00"), null, DecompositionStepRole.CAP),
                                        new AnomalyDecompositionStep("cohort_sample_size", "Amostra", new BigDecimal("30"), null, DecompositionStepRole.RESULT))),
                        new AnomalyFinding(
                                "f-2", AnomalyMethod.NIGRINI_BENFORD, AnomalyScope.DISTRIBUTION_FORENSIC,
                                null, "retro_entries", "observedValue",
                                new BigDecimal("0.320"), new BigDecimal("0.301"), new BigDecimal("0.019"),
                                null, null, 2.8d, 0.003d, 0.006d, 0.30d,
                                AnomalySeverity.WARNING, new BigDecimal("900.00"), 40,
                                SuppressionStatus.EXACT_RELEASE, List.of(
                                        new AnomalyDecompositionStep("nigrini_mad", "Nigrini MAD", new BigDecimal("0.018"), null, DecompositionStepRole.RESULT),
                                        new AnomalyDecompositionStep("benford_digit_1_observed", "Digit 1 Obs", new BigDecimal("0.320"), null, DecompositionStepRole.BASE),
                                        new AnomalyDecompositionStep("benford_digit_1_expected", "Digit 1 Exp", new BigDecimal("0.301"), null, DecompositionStepRole.THRESHOLD)))),
                new AnomalyScanSummary(220L, 4, 2, 1, 1, 0, new BigDecimal("5100.00"), false));

        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubPayrollController(service)),
                List.of(),
                List.of(),
                List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        final int[] round = {0};
        AiToolCallingProvider provider = new AiToolCallingProvider() {
            @Override public String providerId() { return "scripted"; }
            @Override public boolean hasConfiguredCredentials(AiCallConfig c) { return true; }
            @Override
            public AgentModelTurnResponse generateTurn(
                    org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest request,
                    org.praxisplatform.config.ai.agent.model.AgentModelStreamListener listener) {
                if (round[0]++ == 0) {
                    return new AgentModelTurnResponse(
                            "scripted", "scripted-v1", "",
                            List.of(new AgentToolCall(
                                    "call-scan-1",
                                    ScanResourceAnomaliesTool.TOOL_NAME,
                                    Map.of(
                                            "entityKey", "workforce.payroll-slips",
                                            "methods", List.of("ROBUST_MAD_ZSCORE", "NIGRINI_BENFORD"),
                                            "enableBenford", true),
                                    null, null)),
                            new AgentTokenUsage(80, 20, 100), "tool_calls", "r1", Map.of());
                }
                return new AgentModelTurnResponse(
                        "scripted", "scripted-v1", "",
                        List.of(),
                        new AgentTokenUsage(90, 10, 100), "stop", "r2", Map.of());
            }
        };

        PraxisOperationalAgentLoop loop = new PraxisOperationalAgentLoop(
                provider,
                List.of(new ScanResourceAnomaliesTool(bridge)),
                new OperationalAssistantBlockComposer(
                        new ObjectMapper().findAndRegisterModules(),
                        List.of(new StatisticalIntelligenceBlockContributor())),
                4,
                bridge);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "auditor", "prod", "auditor",
                Set.of("ROLE_READ"), null, null, Map.of(), Map.of());

        PraxisOperationalAgentLoop.AgentTurnOutcome outcome = loop.execute(
                ctx,
                "Execute varredura estatistica de anomalias nas folhas.",
                null,
                List.of(),
                AiCallConfig.builder().provider("scripted").model("scripted-v1").build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);

        assertThat(outcome.assistantMessage()).contains("Análise estatística consolidada com selo epistêmico abaixo.");
        assertThat(outcome.traces()).hasSize(1);
        assertThat(outcome.traces().get(0).itemCount()).isEqualTo(2);
        JsonNode blocks = outcome.assistantContent().path("blocks");
        assertThat(blocks).hasSize(4); // paragraph + epistemic-badge + boxplot + combo
        assertThat(new AiSensitiveDataRedactor().sanitizeEventPayload(outcome.assistantContent()).toString())
                .doesNotContain("[REDACTED]");
    }

    public static class LockedObservedDto {
        private Long id;
        @DomainGovernance(classification = DomainClassification.RESTRICTED,
                aiUsage = @AiUsagePolicy(visibility = AiVisibilityMode.DENY))
        private BigDecimal observedValue;
        public Long getId() { return id; }
        public BigDecimal getObservedValue() { return observedValue; }
    }

    @ApiResource(
            value = "/api/workforce/locked-observed",
            resourceKey = "workforce.locked-observed",
            title = "Locked Observed",
            identity = @ResourceIdentity(keyField = "id", titleField = "id"))
    public static class StubLockedObservedController
            extends AbstractResourceQueryController<LockedObservedDto, Long, PayrollSlipFilterDto> {
        private final StubLockedObservedService service = new StubLockedObservedService();
        @Override protected BaseResourceQueryService<LockedObservedDto, Long, PayrollSlipFilterDto> getService() { return service; }
        @Override protected Long getResponseId(LockedObservedDto dto) { return dto.getId(); }
    }

    public static class StubLockedObservedService
            implements BaseResourceQueryService<LockedObservedDto, Long, PayrollSlipFilterDto> {
        @Override public String getIdFieldName() { return "id"; }
        @Override public Sort getDefaultSort() { return Sort.by("id"); }
        @Override public Optional<String> getDatasetVersion() { return Optional.empty(); }
        @Override public StatsSupportMode getGroupByStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getTimeSeriesStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getDistributionStatsSupportMode() { return StatsSupportMode.DISABLED; }
        @Override public StatsSupportMode getInferenceStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsSupportMode getAnomalyStatsSupportMode() { return StatsSupportMode.AUTO; }
        @Override public StatsFieldRegistry getStatsFieldRegistry() { return StatsFieldRegistry.empty(); }
        @Override public OptionSourceRegistry getOptionSourceRegistry() { return null; }
        @Override public boolean hasOptionSource(String sourceKey) { return false; }
        @Override public OptionSourceDescriptor resolveOptionSource(String sourceKey) { return null; }
        @Override public ResourceRepresentationResult<LockedObservedDto> findById(Long id) { return null; }
        @Override public List<LockedObservedDto> findAll() { return List.of(); }
        @Override public List<LockedObservedDto> findAllById(Collection<Long> ids) { return List.of(); }
        @Override public Page<LockedObservedDto> filter(PayrollSlipFilterDto filter, Pageable pageable, Collection<Long> includeIds) { return Page.empty(); }
        @Override public CursorPage<LockedObservedDto> filterByCursor(PayrollSlipFilterDto filter, Sort sort, String after, String before, int size) { return null; }
        @Override public OptionalLong locate(PayrollSlipFilterDto filter, Sort sort, Long id) { return OptionalLong.empty(); }
        @Override public Page<OptionDTO<Long>> filterOptions(PayrollSlipFilterDto filter, Pageable pageable) { return Page.empty(); }
        @Override public List<OptionDTO<Long>> byIdsOptions(Collection<Long> ids) { return List.of(); }
        @Override public Page<OptionDTO<Object>> filterOptionSourceOptions(String sourceKey, OptionSourceFilterRequest<PayrollSlipFilterDto> request, Pageable pageable) { return Page.empty(); }
        @Override public List<OptionDTO<Object>> byIdsOptionSourceOptions(String sourceKey, Collection<Object> ids) { return List.of(); }
        @Override public GroupByStatsResponse groupByStats(GroupByStatsRequest<PayrollSlipFilterDto> request) { return null; }
        @Override public TimeSeriesStatsResponse timeSeriesStats(TimeSeriesStatsRequest<PayrollSlipFilterDto> request) { return null; }
        @Override public DistributionStatsResponse distributionStats(DistributionStatsRequest<PayrollSlipFilterDto> request) { return null; }
        @Override public ComparisonStatsResponse comparisonStats(ComparisonStatsRequest<PayrollSlipFilterDto> request) { return null; }
    }

    public static class PluralBlockedFilterDto extends PayrollSlipFilterDto {
        @Filterable(operation = Filterable.FilterOperation.LIKE, relations = {"confidentialBonus", "cohortKey"})
        private String broadRestrictedFilter;

        public String getBroadRestrictedFilter() {
            return broadRestrictedFilter;
        }

        public void setBroadRestrictedFilter(String broadRestrictedFilter) {
            this.broadRestrictedFilter = broadRestrictedFilter;
        }
    }

    @ApiResource(
            value = "/api/workforce/plural-recipe",
            resourceKey = "workforce.plural-recipe",
            title = "Plural Recipe",
            identity = @ResourceIdentity(keyField = "id", titleField = "cohortKey"))
    public static class StubPluralRecipeController
            extends AbstractResourceQueryController<PayrollSlipDto, Long, PluralBlockedFilterDto> {
        @Override
        protected BaseResourceQueryService<PayrollSlipDto, Long, PluralBlockedFilterDto> getService() {
            return null;
        }

        @Override
        protected Long getResponseId(PayrollSlipDto dto) {
            return dto.getId();
        }

        @Override
        protected List<org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor<PluralBlockedFilterDto>>
                defineAnalyticalRecipes() {
            return List.of(
                    org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor
                            .<PluralBlockedFilterDto>builder("restricted-rel-recipe", "Restricted Relation Recipe")
                            .operation(org.praxisplatform.uischema.annotation.AnalyticsOperation.GROUP_BY)
                            .field("cohortKey")
                            .metric(StatsMetric.COUNT, null)
                            .adjustableParam(new org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor
                                    .ParameterSpec("broadRestrictedFilter", "broadRestrictedFilter",
                                    "Filter", "string", false, null, List.of(), null, null))
                            .build()
            );
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesNigriniMadPriorityRecipeResourcePathAndPluralRelationGovernance() {
        StubPayrollService service = new StubPayrollService();
        AnalyticalProvenance provenance = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-CARD-NIGRINI", "1.0.0", 50L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.CALIBRATED_PROBABILITY,
                150L, 0, "corr-nig", "job-nig", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor card = new ModelCardDescriptor(
                "WF-CARD-NIGRINI", "1.0.0", "Benford Card", "workforce.payroll-slips",
                "NIGRINI_BENFORD", EpistemicKind.CALIBRATED_PROBABILITY,
                "Benford audit", List.of(), 15, List.of(), "Human review");
        service.anomalyHandler = req -> new AnomalyScanResponse(
                IntelligenceContractVersion.V1,
                provenance,
                card,
                List.of(new AnomalyFinding(
                        "f-ben", AnomalyMethod.NIGRINI_BENFORD, AnomalyScope.DISTRIBUTION_FORENSIC,
                        null, "retro_entries", "observedValue",
                        new BigDecimal("0.3010"), new BigDecimal("0.3010"), new BigDecimal("0.0000"),
                        null, null, 2.1d, 0.01d, 0.02d, 0.40d,
                        AnomalySeverity.INFO, BigDecimal.ZERO, 150, SuppressionStatus.EXACT_RELEASE,
                        List.of(new AnomalyDecompositionStep(
                                "nigrini_mad", "MAD global", new BigDecimal("0.0042"),
                                null, DecompositionStepRole.RESULT)))),
                new AnomalyScanSummary(150L, 1, 1, 0, 0, 0, BigDecimal.ZERO, false));

        var analysisResult = MetadataStarterStatisticalIntelligenceSupport.scanAnomalies(
                service,
                new PayrollSlipFilterDto(),
                "workforce.payroll-slips",
                "workforce.payroll-slips",
                "/api/workforce/payroll-slips",
                "Payroll Slips",
                Set.of(),
                new org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest(
                        "workforce.payroll-slips", List.of("NIGRINI_BENFORD"),
                        Map.of(), 0.05d, null, null, null, null, true, null, null, null, null, null, Map.of()));
        List<Map<String, Object>> benfordRows =
                (List<Map<String, Object>>) analysisResult.uiPayload().get("benfordFindings");
        assertThat(benfordRows).hasSize(1);
        assertThat(new BigDecimal(benfordRows.get(0).get("nigriniMad").toString()))
                .isEqualByComparingTo("0.0042");
        assertThat(benfordRows.get(0)).containsEntry("nigriniConformity", "CONFORMIDADE_ESTREITA");

        AgentResourceExecutionBridge recipeBridge = mock(AgentResourceExecutionBridge.class);
        org.mockito.Mockito.when(recipeBridge.executeVerifiedAnalyticalRecipe(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.praxisplatform.config.ai.agent.spi.AgentStatsResult(
                        "workforce.payroll-slips",
                        "workforce.payroll-slips",
                        "/api/workforce/payroll-slips",
                        "Payroll Recipe",
                        "Subtitulo",
                        "GROUP_BY",
                        "bar",
                        "cohortKey",
                        "Coorte",
                        "COUNT",
                        null,
                        null,
                        10L,
                        List.of(),
                        null));
        org.praxisplatform.config.ai.agent.tools.ExecuteVerifiedAnalyticalRecipeTool recipeTool =
                new org.praxisplatform.config.ai.agent.tools.ExecuteVerifiedAnalyticalRecipeTool(recipeBridge);
        Map<String, Object> toolOut = recipeTool.execute(
                new AgentExecutionContext(null, null, null, "t1", "u1", "prod", "ana",
                        Set.of(), null, null, Map.of(), Map.of()),
                TurnScopedPiiVault.tokenize("executar"),
                new AgentTurnArtifactStore(),
                Map.of("entityKey", "workforce.payroll-slips", "recipeId", "r1"));
        assertThat(toolOut).containsEntry("resourcePath", "/api/workforce/payroll-slips");

        StubPluralRecipeController pluralCtrl = new StubPluralRecipeController();
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .add(StatsFieldDescriptor.groupByBucket("cohortKey", "Coorte", Set.of(StatsMetric.COUNT)))
                .build();
        assertThatThrownBy(() -> MetadataStarterVerifiedRecipeSupport.executeRecipe(
                pluralCtrl,
                service,
                PluralBlockedFilterDto.class,
                registry,
                Set.of("confidentialBonus"),
                null,
                new ObjectMapper().findAndRegisterModules(),
                new org.praxisplatform.config.ai.agent.spi.AgentVerifiedRecipeExecutionRequest(
                        "workforce.plural-recipe", "restricted-rel-recipe", Map.of(), null, null, null)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("broadRestrictedFilter");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runResourceStochasticSimulationToolAndBridgeEnforceNonTxCapabilityMaskingAndAnchorDriftDegradation() {
        StubPayrollService service = new StubPayrollService();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubPayrollController(service)),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "workforce.payroll-slips", "workforce.payroll-slips", "/api/workforce/payroll-slips",
                        "Payroll Slips", "Workforce payroll slips", "payments",
                        "id", "cohortKey", "cohortKey", List.of("cohortKey"),
                        null, "cohortKey", null, "/payroll/{id}", "payroll", 100))),
                List.of(),
                List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                txManager);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "auditor", "prod", "auditor",
                Set.of("ROLE_READ"), null, null, Map.of(), Map.of());

        var digest = bridge.describeEntitySchema(ctx, "workforce.payroll-slips").orElseThrow();
        assertThat(digest.statsCapability().simulationMode()).isEqualTo("AUTO");
        assertThat(digest.statsCapability().counterfactualMode()).isEqualTo("AUTO");
        org.mockito.Mockito.reset(txManager);

        RunResourceStochasticSimulationTool simTool = new RunResourceStochasticSimulationTool(bridge);
        assertThat(simTool.safetyClass()).isEqualTo(OperationalAgentTool.AgentToolSafetyClass.SIMULATION);

        AnalyticalProvenance simProv = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-SIM-01", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.STOCHASTIC_SIMULATION,
                2000L, 0, "corr-sim", "job-sim", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor simCard = new ModelCardDescriptor(
                "WF-SIM-01", "1.0.0", "Stochastic Budget Simulation", "workforce.payroll",
                "QMC_SOBOL_IMAN_CONOVER", EpistemicKind.STOCHASTIC_SIMULATION,
                "Calibrated scenario simulation.", List.of("Not deterministic law"),
                15, List.of("Stationary correlations"), "Human governance review required");

        AtomicBoolean txActiveDuringCall = new AtomicBoolean(true);
        service.simulationHandler = req -> {
            txActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new StochasticSimulationResponse(
                    IntelligenceContractVersion.V1,
                    simProv,
                    simCard,
                    2000,
                    2,
                    List.of(
                            new FanChartPoint(
                                    1, new BigDecimal("100500.00"), new BigDecimal("2100.00"),
                                    Map.of(
                                            "p05", new BigDecimal("96000.00"),
                                            "p10", new BigDecimal("97500.00"),
                                            "p25", new BigDecimal("99000.00"),
                                            "p50", new BigDecimal("100500.00"),
                                            "p75", new BigDecimal("102000.00"),
                                            "p90", new BigDecimal("103800.00"),
                                            "p95", new BigDecimal("105200.00")),
                                    Map.of()),
                            new FanChartPoint(
                                    2, new BigDecimal("102500.00"), new BigDecimal("2400.00"),
                                    Map.of(
                                            "p05", new BigDecimal("97000.00"),
                                            "p10", new BigDecimal("98800.00"),
                                            "p25", new BigDecimal("100600.00"),
                                            "p50", new BigDecimal("102500.00"),
                                            "p75", new BigDecimal("104200.00"),
                                            "p90", new BigDecimal("106000.00"),
                                            "p95", new BigDecimal("108000.00")),
                                    Map.of())),
                    new BigDecimal("102500.00"),
                    new BigDecimal("2400.00"),
                    new BigDecimal("108900.00"),
                    Map.of("p50", new BigDecimal("102500.00"), "p95", new BigDecimal("108000.00")),
                    List.of(new ThresholdBreachOutcome(
                            "cap_warning", ThresholdDirection.UPPER_CAP, "general_fund",
                            0.18d, 0.16d, 0.20d, new BigDecimal("1200.00"), null)),
                    List.of(
                            new TornadoSensitivityDriver(
                                    "overtimeFactor", 0.35d, new BigDecimal("99000.00"), new BigDecimal("102000.00"),
                                    new BigDecimal("3000.00"), 2),
                            new TornadoSensitivityDriver(
                                    "baseRateFactor", 0.85d, new BigDecimal("96000.00"), new BigDecimal("106000.00"),
                                    new BigDecimal("10000.00"), 1)),
                    List.of(new AnchorScenarioPoint(
                            "P50", 0.50d, 1000, new BigDecimal("100500.00"), Map.of("baseRateFactor", 1.03d),
                            new BigDecimal("100502.00"), 0.002d, AnchorRevalidationStatus.REVALIDATED_WITHIN_TOLERANCE)),
                    0,
                    SuppressionStatus.EXACT_RELEASE);
        };

        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("Simulate workforce projection");
        Map<String, Object> simArgs = Map.of(
                "entityKey", "workforce.payroll-slips",
                "scenarioCount", 2000,
                "horizonPeriods", 2,
                "modelPoints", List.of(Map.of(
                        "pointKey", "cohort_ops",
                        "weightCount", 40,
                        "baseAmount", 2500.0d,
                        "factorSensitivities", Map.of("baseRateFactor", 1.0d))),
                "stochasticFactors", List.of(Map.of(
                        "factorKey", "baseRateFactor",
                        "distribution", "NORMAL",
                        "parameters", Map.of("mean", 1.0d, "stdDev", 0.03d))),
                "thresholds", List.of(Map.of(
                        "thresholdId", "cap_warning",
                        "label", "Operating Cap",
                        "direction", "UPPER_CAP",
                        "limitValue", 104000.0d)));

        Map<String, Object> simOut = simTool.execute(ctx, vault, artifacts, simArgs);
        assertThat(txActiveDuringCall.get()).isFalse();
        verifyNoInteractions(txManager);
        assertThat(simOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE);
        assertThat(simOut).containsEntry("allAnchorsWithinTolerance", Boolean.TRUE);
        assertThat(artifacts.extensionArtifacts()).hasSize(1);
        AgentExtensionArtifact simArtifact = artifacts.extensionArtifacts().get(0);
        assertThat(simArtifact.kind()).isEqualTo(AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION);
        assertThat(simArtifact.epistemicEnvelope().epistemicKind())
                .isEqualTo(AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION);
        List<Map<String, Object>> tornadoOut = (List<Map<String, Object>>) simOut.get("tornadoDrivers");
        assertThat(tornadoOut).hasSize(2);
        assertThat(tornadoOut.get(0)).containsEntry("factorKey", "baseRateFactor");

        // Anchor drift exceeded degrades fail-closed
        service.simulationHandler = req -> new StochasticSimulationResponse(
                IntelligenceContractVersion.V1,
                simProv,
                simCard,
                2000,
                2,
                List.of(
                        new FanChartPoint(1, new BigDecimal("100500.00"), new BigDecimal("2100.00"),
                                Map.of("p50", new BigDecimal("100500.00")), Map.of()),
                        new FanChartPoint(2, new BigDecimal("102500.00"), new BigDecimal("2400.00"),
                                Map.of("p50", new BigDecimal("102500.00")), Map.of())),
                new BigDecimal("100500.00"),
                new BigDecimal("2100.00"),
                new BigDecimal("106100.00"),
                Map.of("p50", new BigDecimal("100500.00")),
                List.of(),
                List.of(),
                List.of(new AnchorScenarioPoint(
                        "P90", 0.90d, 1800, new BigDecimal("104000.00"), Map.of("baseRateFactor", 1.07d),
                        new BigDecimal("112000.00"), 7.69d, AnchorRevalidationStatus.REVALIDATED_DRIFT_EXCEEDED)),
                0,
                SuppressionStatus.EXACT_RELEASE);

        Map<String, Object> driftedOut = simTool.execute(ctx, vault, artifacts, simArgs);
        assertThat(driftedOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_DEGRADED);
        assertThat(driftedOut).containsEntry("allAnchorsWithinTolerance", Boolean.FALSE);
        assertThat((List<?>) driftedOut.get("fanChart")).isEmpty();

        // Drift circuit open exception maps to DEGRADED without throwing
        service.simulationHandler = req -> {
            throw new IntelligenceExecutionException(
                    IntelligenceErrorCode.DRIFT_CIRCUIT_OPEN,
                    "Anchor drift circuit breaker open");
        };
        Map<String, Object> circuitOut = simTool.execute(ctx, vault, artifacts, simArgs);
        assertThat(circuitOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_DEGRADED);
        assertThat(circuitOut).containsEntry("errorCode", "DRIFT_CIRCUIT_OPEN");

        // Blocked DENY column in factorKey must throw SecurityException
        assertThatThrownBy(() -> simTool.execute(
                ctx,
                vault,
                artifacts,
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "stochasticFactors", List.of(Map.of(
                                "factorKey", "confidentialBonus",
                                "distribution", "NORMAL",
                                "parameters", Map.of("mean", 1.0d, "stdDev", 0.1d))))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("confidentialBonus");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runResourceCounterfactualSearchToolAndBridgeEnforceDoubleRuleValidationFilterNonTxAndGovernance() {
        StubPayrollService service = new StubPayrollService();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubPayrollController(service)),
                List.of(ctx -> List.of(new AgentEntityDescriptor(
                        "workforce.payroll-slips", "workforce.payroll-slips", "/api/workforce/payroll-slips",
                        "Payroll Slips", "Workforce payroll slips", "payments",
                        "id", "cohortKey", "cohortKey", List.of("cohortKey"),
                        null, "cohortKey", null, "/payroll/{id}", "payroll", 100))),
                List.of(),
                List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                txManager);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "auditor", "prod", "auditor",
                Set.of("ROLE_READ"), null, null, Map.of(), Map.of());

        RunResourceCounterfactualSearchTool cfTool = new RunResourceCounterfactualSearchTool(bridge);
        assertThat(cfTool.safetyClass()).isEqualTo(OperationalAgentTool.AgentToolSafetyClass.SIMULATION);

        AnalyticalProvenance cfProv = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-CF-01", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.OPTIMIZATION_RESULT,
                120L, 0, "corr-cf", "job-cf", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor cfCard = new ModelCardDescriptor(
                "WF-CF-01", "1.0.0", "Counterfactual Recourse", "workforce.payroll",
                "MILP_RULE_VALIDATED", EpistemicKind.OPTIMIZATION_RESULT,
                "Advisory recourse search.", List.of("Advisory only; human approval required"),
                15, List.of("Fixed policy constraints"), "Human review mandatory");

        AtomicBoolean txActiveDuringCall = new AtomicBoolean(true);
        service.counterfactualHandler = req -> {
            txActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new CounterfactualResponse(
                    IntelligenceContractVersion.V1,
                    cfProv,
                    cfCard,
                    new BigDecimal("51.30"),
                    new BigDecimal("48.50"),
                    true,
                    List.of(new CounterfactualCandidate(
                            "cf-valid-1",
                            1,
                            List.of(new CounterfactualLeverDelta(
                                    "overtimeCapPct", 15.0d, 10.0d, -0.33d, new BigDecimal("-2.90"))),
                            new BigDecimal("48.40"),
                            new BigDecimal("-2.90"),
                            0.42d,
                            CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE,
                            List.of("Regra deterministica aprovada"))),
                    2);
        };

        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("Search counterfactual recourse");
        Map<String, Object> cfArgs = Map.of(
                "entityKey", "workforce.payroll-slips",
                "targetMetricKey", "observedValue",
                "direction", "DECREASE_TO_TARGET",
                "baselineValue", 51.30d,
                "targetValue", 48.50d,
                "mutableLevers", List.of(Map.of(
                        "leverKey", "overtimeCapPct",
                        "leverType", "CONTINUOUS",
                        "currentValue", 15.0d,
                        "minValue", 5.0d,
                        "maxValue", 20.0d,
                        "linearSensitivity", 0.58d)));

        Map<String, Object> cfOut = cfTool.execute(ctx, vault, artifacts, cfArgs);
        assertThat(txActiveDuringCall.get()).isFalse();
        verifyNoInteractions(txManager);
        assertThat(cfOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE);
        assertThat(cfOut).containsEntry("advisoryOnly", Boolean.TRUE);
        assertThat(cfOut).containsEntry("requiresHumanGovernanceApproval", Boolean.TRUE);
        assertThat(cfOut).containsEntry("validatedCandidateCount", 1);
        assertThat(cfOut).containsEntry("excludedCandidateCount", 2);
        assertThat((List<Map<String, Object>>) cfOut.get("candidates")).hasSize(1);
        assertThat(artifacts.extensionArtifacts()).hasSize(1);
        assertThat(artifacts.extensionArtifacts().get(0).kind())
                .isEqualTo(AgentExtensionArtifact.KIND_COUNTERFACTUAL_SEARCH);

        // Unverified candidate leaked into response triggers fail-closed DEGRADED & strips all candidates
        service.counterfactualHandler = req -> new CounterfactualResponse(
                IntelligenceContractVersion.V1,
                cfProv,
                cfCard,
                new BigDecimal("51.30"),
                new BigDecimal("48.50"),
                true,
                List.of(
                        new CounterfactualCandidate(
                                "cf-valid-ok", 1,
                                List.of(new CounterfactualLeverDelta(
                                        "overtimeCapPct", 15.0d, 10.0d, -0.33d, new BigDecimal("-2.90"))),
                                new BigDecimal("48.40"), new BigDecimal("-2.90"),
                                0.4d, CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE, List.of()),
                        new CounterfactualCandidate(
                                "cf-unverified", 2,
                                List.of(new CounterfactualLeverDelta(
                                        "overtimeCapPct", 15.0d, 9.0d, -0.40d, new BigDecimal("-3.30"))),
                                new BigDecimal("48.00"), new BigDecimal("-3.30"),
                                0.3d, CounterfactualFeasibilityStatus.CANDIDATE_UNVERIFIED, List.of())),
                1);
        Map<String, Object> leakedOut = cfTool.execute(ctx, vault, artifacts, cfArgs);
        assertThat(leakedOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_DEGRADED);
        assertThat(leakedOut).containsEntry("validatedCandidateCount", 0);
        assertThat((List<?>) leakedOut.get("candidates")).isEmpty();

        // Non-convergent optimizer maps to DEGRADED
        service.counterfactualHandler = req -> {
            throw new IntelligenceExecutionException(
                    IntelligenceErrorCode.NON_CONVERGENT_OPTIMIZER,
                    "Infeasible lever bounds");
        };
        Map<String, Object> nonConvOut = cfTool.execute(ctx, vault, artifacts, cfArgs);
        assertThat(nonConvOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_DEGRADED);
        assertThat(nonConvOut).containsEntry("errorCode", "NON_CONVERGENT_OPTIMIZER");

        // Engine unavailable maps to ENGINE_UNAVAILABLE
        service.counterfactualHandler = req -> {
            throw new IntelligenceExecutionException(
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE,
                    "Python worker down");
        };
        Map<String, Object> downOut = cfTool.execute(ctx, vault, artifacts, cfArgs);
        assertThat(downOut).containsEntry("availabilityState", AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE);
        assertThat(downOut).containsEntry("errorCode", "INTELLIGENCE_ENGINE_UNAVAILABLE");

        // Blocked DENY column in targetMetricKey or mutableLevers throws SecurityException
        assertThatThrownBy(() -> cfTool.execute(
                ctx,
                vault,
                artifacts,
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "targetMetricKey", "confidentialBonus",
                        "direction", "DECREASE_TO_TARGET",
                        "baselineValue", 100.0d,
                        "targetValue", 80.0d,
                        "mutableLevers", List.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("confidentialBonus");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onda2HardeningCoversSchemasDatasetStampingOrchestratorAutoConfigNestedGovernanceAndLoop() {
        StubPayrollService service = new StubPayrollService();
        MetadataStarterInProcessAgentBridge bridge = new MetadataStarterInProcessAgentBridge(
                List.of(new StubPayrollController(service)),
                List.of(), List.of(), List.of(),
                null, null, null,
                new DefaultMetadataGovernanceAgentAuthorizationBridge(),
                new DefaultConversionService(),
                new ObjectMapper().findAndRegisterModules(),
                null);

        AgentExecutionContext ctx = new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "tenant-a", "auditor", "prod", "auditor",
                Set.of("ROLE_READ"), null, null, Map.of(), Map.of());
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("Test Onda 2 hardening");

        // 1. Tool declarations omit datasetKey, require stochasticFactors, and publish enum/nested item schemas
        RunResourceStochasticSimulationTool simTool = new RunResourceStochasticSimulationTool(bridge);
        Map<String, Object> simProps = (Map<String, Object>) simTool.declaration().parametersJsonSchema().get("properties");
        assertThat(simProps).doesNotContainKey("datasetKey");
        assertThat(simTool.declaration().requiredParameters()).containsExactly("entityKey", "stochasticFactors");
        assertThat((Map<String, Object>) simProps.get("samplingStrategy")).containsKey("enum");
        Map<String, Object> factorSchema = (Map<String, Object>) ((Map<String, Object>) simProps.get("stochasticFactors")).get("items");
        assertThat((Map<String, Object>) ((Map<String, Object>) factorSchema.get("properties")).get("distribution")).containsKey("enum");

        RunResourceCounterfactualSearchTool cfTool = new RunResourceCounterfactualSearchTool(bridge);
        Map<String, Object> cfProps = (Map<String, Object>) cfTool.declaration().parametersJsonSchema().get("properties");
        assertThat(cfProps).doesNotContainKey("datasetKey");
        assertThat((Map<String, Object>) cfProps.get("direction")).containsKey("enum");
        Map<String, Object> leverSchema = (Map<String, Object>) ((Map<String, Object>) cfProps.get("mutableLevers")).get("items");
        assertThat((Map<String, Object>) ((Map<String, Object>) leverSchema.get("properties")).get("leverType")).containsKey("enum");

        // 2. Malformed numeric/date arguments throw IllegalArgumentException cleanly
        assertThatThrownBy(() -> simTool.execute(ctx, vault, new AgentTurnArtifactStore(), Map.of(
                "entityKey", "workforce.payroll-slips",
                "horizonPeriods", "not-an-int")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horizonPeriods");
        assertThatThrownBy(() -> simTool.execute(ctx, vault, new AgentTurnArtifactStore(), Map.of(
                "entityKey", "workforce.payroll-slips",
                "asOfDate", "2026-99-99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asOfDate");
        assertThatThrownBy(() -> cfTool.execute(ctx, vault, new AgentTurnArtifactStore(), Map.of(
                "entityKey", "workforce.payroll-slips",
                "maxCandidates", "bad-int")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCandidates");

        // 3. Canonical resourceKey is stamped as datasetKey & byFundingSource/realizedFactorValues filter blocked keys
        AnalyticalProvenance simProv = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-SIM-02", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.STOCHASTIC_SIMULATION,
                500L, 0, "corr-h", "job-h", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor simCard = new ModelCardDescriptor(
                "WF-SIM-02", "1.0.0", "Sim", "workforce.payroll", "QMC", EpistemicKind.STOCHASTIC_SIMULATION,
                "Calibrated", List.of("Advisory"), 15, List.of("Assumptions"), "Human approval");
        AtomicReference<String> seenSimDataset = new AtomicReference<>();
        service.simulationHandler = req -> {
            seenSimDataset.set(req.datasetKey());
            return new StochasticSimulationResponse(
                    IntelligenceContractVersion.V1, simProv, simCard, 500, 1,
                    List.of(new FanChartPoint(
                            1, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                            Map.of("p50", new BigDecimal("1000.00")),
                            Map.of("GENERAL_FUND", new BigDecimal("900.00"), "confidentialBonus", new BigDecimal("100.00")))),
                    new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1100.00"),
                    Map.of("p50", new BigDecimal("1000.00")),
                    List.of(new ThresholdBreachOutcome(
                            "cap_warning", ThresholdDirection.UPPER_CAP, null, 0.12d, 0.09d, 0.15d,
                            new BigDecimal("40.00"), null)),
                    List.of(new TornadoSensitivityDriver(
                            "wageIndex", 0.7d, new BigDecimal("950.00"), new BigDecimal("1050.00"),
                            new BigDecimal("100.00"), 1)),
                    List.of(new AnchorScenarioPoint(
                            "P50", 0.50d, 250, new BigDecimal("1000.00"),
                            Map.of("wageIndex", 0.04d, "confidentialBonus", 50.0d),
                            new BigDecimal("1001.00"), 0.001d, AnchorRevalidationStatus.REVALIDATED_WITHIN_TOLERANCE)),
                    0, SuppressionStatus.EXACT_RELEASE);
        };
        AgentStatisticalAnalysisResult spoofedSimRes = bridge.runStochasticSimulation(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentStochasticSimulationRequest(
                        "workforce.payroll-slips", "spoofed.dataset", 500, 1, 42L, null,
                        null, null,
                        List.of(Map.of("pointKey", "pt1", "weightCount", 10, "baseAmount", 100.0d, "factorSensitivities", Map.of("wageIndex", 1.0d))),
                        List.of(Map.of("factorKey", "wageIndex", "distribution", "NORMAL", "parameters", Map.of("mean", 0.04d, "stdDev", 0.01d))),
                        List.of(),
                        List.of(Map.of("thresholdId", "cap_warning", "label", "Teto Operacional", "direction", "UPPER_CAP", "limitValue", 1050.0d)),
                        null, null, null, Map.of()));
        assertThat(seenSimDataset.get()).isEqualTo("workforce.payroll-slips");
        List<Map<String, Object>> fanRows = (List<Map<String, Object>>) spoofedSimRes.uiPayload().get("fanChart");
        Map<String, Object> bySource = (Map<String, Object>) fanRows.get(0).get("byFundingSource");
        assertThat(bySource).containsKey("GENERAL_FUND").doesNotContainKey("confidentialBonus");
        List<Map<String, Object>> anchorRows = (List<Map<String, Object>>) spoofedSimRes.uiPayload().get("anchorScenarios");
        Map<String, Object> realized = (Map<String, Object>) anchorRows.get(0).get("realizedFactorValues");
        assertThat(realized).containsKey("wageIndex").doesNotContainKey("confidentialBonus");
        List<Map<String, Object>> thresholdRows = (List<Map<String, Object>>) spoofedSimRes.uiPayload().get("thresholdOutcomes");
        assertThat(thresholdRows.get(0)).containsEntry("label", "Teto Operacional");

        // INVALID_REQUEST rethrows IllegalArgumentException + INSUFFICIENT_SAMPLE_SIZE maps accurately
        service.simulationHandler = req -> {
            throw new IntelligenceExecutionException(IntelligenceErrorCode.INVALID_REQUEST, "Bad sim factor");
        };
        assertThatThrownBy(() -> simTool.execute(ctx, vault, new AgentTurnArtifactStore(), Map.of(
                "entityKey", "workforce.payroll-slips",
                "stochasticFactors", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bad sim factor");

        service.simulationHandler = req -> {
            throw new IntelligenceExecutionException(IntelligenceErrorCode.INSUFFICIENT_SAMPLE_SIZE, "No rows");
        };
        Map<String, Object> insuffOut = simTool.execute(ctx, vault, new AgentTurnArtifactStore(), Map.of(
                "entityKey", "workforce.payroll-slips",
                "stochasticFactors", List.of(Map.of("factorKey", "wageIndex", "distribution", "NORMAL", "parameters", Map.of("mean", 0.04d, "stdDev", 0.01d)))));
        assertThat(insuffOut).containsEntry("errorCode", "INSUFFICIENT_SAMPLE_SIZE");

        // Counterfactual ALL_CANDIDATES_REJECTED_BY_RULES & datasetKey override
        AnalyticalProvenance cfProv = new AnalyticalProvenance(
                IntelligenceContractVersion.V1, "praxis-intelligence-python", "0.1.0", "3.12",
                SHA_LOCK, "WF-CF-02", "1.0.0", 42L, LocalDate.of(2026, 11, 1),
                SHA_SNAPSHOT, SHA_ASSUMPTIONS, EpistemicKind.OPTIMIZATION_RESULT,
                50L, 0, "corr-cf2", "job-cf2", Instant.parse("2026-11-24T00:00:00Z"));
        ModelCardDescriptor cfCard = new ModelCardDescriptor(
                "WF-CF-02", "1.0.0", "CF", "workforce.payroll", "MILP", EpistemicKind.OPTIMIZATION_RESULT,
                "Recourse", List.of("Advisory"), 15, List.of("Constraints"), "Human approval");
        AtomicReference<String> seenCfDataset = new AtomicReference<>();
        service.counterfactualHandler = req -> {
            seenCfDataset.set(req.datasetKey());
            return new CounterfactualResponse(
                    IntelligenceContractVersion.V1, cfProv, cfCard,
                    new BigDecimal("51.00"), new BigDecimal("48.00"), true, List.of(), 3);
        };
        AgentStatisticalAnalysisResult rejectedAllRes = bridge.runCounterfactualSearch(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentCounterfactualSearchRequest(
                        "workforce.payroll-slips", "spoofed.dataset", "observedValue", "DECREASE_TO_TARGET",
                        new BigDecimal("51.00"), new BigDecimal("48.00"), null,
                        List.of(Map.of(
                                "leverKey", "overtimeCapPct",
                                "leverType", "CONTINUOUS",
                                "currentValue", 15.0d,
                                "minValue", 5.0d,
                                "maxValue", 20.0d)),
                        5, 1, 42L, null, Map.of()));
        assertThat(seenCfDataset.get()).isEqualTo("workforce.payroll-slips");
        assertThat(rejectedAllRes.modelSafePayload()).containsEntry("errorCode", "ALL_CANDIDATES_REJECTED_BY_RULES");

        // 4. Dotted/nested blocked filter keys & nested lever governance checks
        assertThatThrownBy(() -> bridge.runStochasticSimulation(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentStochasticSimulationRequest(
                        "workforce.payroll-slips", null, null, null, null, null, null, null,
                        List.of(), List.of(), List.of(), List.of(), null, null, null,
                        Map.of("nested", Map.of("unit.confidentialBonus", 100)))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("confidentialBonus");
        assertThatThrownBy(() -> bridge.runStochasticSimulation(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentStochasticSimulationRequest(
                        "workforce.payroll-slips", null, null, null, null, null, null, null,
                        List.of(Map.of("pointKey", "pt1", "segmentLabel", "confidentialBonus", "weightCount", 10, "baseAmount", 100.0d)),
                        List.of(), List.of(), List.of(), null, null, null, Map.of())))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> bridge.runCounterfactualSearch(
                ctx,
                new org.praxisplatform.config.ai.agent.spi.AgentCounterfactualSearchRequest(
                        "workforce.payroll-slips", null, "observedValue", "DECREASE_TO_TARGET",
                        new BigDecimal("51.00"), new BigDecimal("48.00"), null,
                        List.of(Map.of("leverKey", "roleChoice", "leverType", "CATEGORICAL", "currentValue", "A",
                                "allowedCategories", List.of("confidentialBonus"))),
                        5, 1, 42L, null, Map.of())))
                .isInstanceOf(SecurityException.class);

        // 5. StatisticalToolsEnabledCondition registers tools when only StochasticSimulationOrchestrator or CounterfactualSearchOrchestrator is registered
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(
                        OperationalAgentAutoConfiguration.StatisticalIntelligenceToolsConfiguration.class))
                .withBean(AgentResourceExecutionBridge.class, () -> bridge);
        runner.run(ctxWithoutStats -> assertThat(ctxWithoutStats).doesNotHaveBean(RunResourceStochasticSimulationTool.class));
        runner.withBean(StochasticSimulationOrchestrator.class, () -> new StochasticSimulationOrchestrator() {
            @Override public StochasticSimulationResponse executeStochasticSimulation(
                    String rk, StochasticSimulationRequest req, AnchorScenarioRevalidator rev) { return null; }
            @Override public IntelligenceJobReceipt submitStochasticSimulationJob(
                    String rk, StochasticSimulationRequest req, AnchorScenarioRevalidator rev) { return null; }
        }).run(ctxSimOrch -> assertThat(ctxSimOrch)
                .hasSingleBean(RunResourceStochasticSimulationTool.class)
                .hasSingleBean(RunResourceCounterfactualSearchTool.class));
        runner.withBean(CounterfactualSearchOrchestrator.class, () -> new CounterfactualSearchOrchestrator() {
            @Override public CounterfactualResponse executeCounterfactualSearch(
                    String rk, CounterfactualRequest req, CounterfactualRuleValidator val) { return null; }
            @Override public IntelligenceJobReceipt submitCounterfactualJob(
                    String rk, CounterfactualRequest req, CounterfactualRuleValidator val) { return null; }
        }).run(ctxCfOrch -> assertThat(ctxCfOrch)
                .hasSingleBean(RunResourceStochasticSimulationTool.class)
                .hasSingleBean(RunResourceCounterfactualSearchTool.class));

        // 6. PraxisOperationalAgentLoop tailors fallback narrative and estimates count cleanly for Onda 2
        service.simulationHandler = req -> new StochasticSimulationResponse(
                IntelligenceContractVersion.V1, simProv, simCard, 500, 1,
                List.of(new FanChartPoint(1, new BigDecimal("1000.00"), new BigDecimal("50.00"),
                        Map.of("p50", new BigDecimal("1000.00")), Map.of())),
                new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("1100.00"),
                Map.of("p50", new BigDecimal("1000.00")), List.of(), List.of(), List.of(),
                0, SuppressionStatus.EXACT_RELEASE);
        final int[] simRound = {0};
        AiToolCallingProvider simProvider = new AiToolCallingProvider() {
            @Override public String providerId() { return "scripted-sim"; }
            @Override public boolean hasConfiguredCredentials(AiCallConfig c) { return true; }
            @Override public AgentModelTurnResponse generateTurn(
                    org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest request,
                    org.praxisplatform.config.ai.agent.model.AgentModelStreamListener listener) {
                if (simRound[0]++ == 0) {
                    return new AgentModelTurnResponse("scripted-sim", "v1", "",
                            List.of(new AgentToolCall("call-sim-1", RunResourceStochasticSimulationTool.TOOL_NAME,
                                    Map.of("entityKey", "workforce.payroll-slips",
                                            "stochasticFactors", List.of(Map.of(
                                                    "factorKey", "wageIndex", "distribution", "NORMAL",
                                                    "parameters", Map.of("mean", 0.04d, "stdDev", 0.01d)))),
                                    null, null)),
                            new AgentTokenUsage(50, 10, 60), "tool_calls", "s1", Map.of());
                }
                return new AgentModelTurnResponse("scripted-sim", "v1", "", List.of(),
                        new AgentTokenUsage(60, 10, 70), "stop", "s2", Map.of());
            }
        };
        PraxisOperationalAgentLoop simLoop = new PraxisOperationalAgentLoop(
                simProvider, List.of(simTool),
                new OperationalAssistantBlockComposer(new ObjectMapper().findAndRegisterModules(),
                        List.of(new StatisticalIntelligenceBlockContributor())),
                4, bridge);
        PraxisOperationalAgentLoop.AgentTurnOutcome simOutcome = simLoop.execute(
                ctx, "Simule a folha", null, List.of(),
                AiCallConfig.builder().provider("scripted-sim").model("v1").build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);
        assertThat(simOutcome.traces().get(0).itemCount()).isEqualTo(1);
        assertThat(simOutcome.assistantMessage())
                .contains("Projeção estocástica consolidada com selo epistêmico e revalidação de âncoras abaixo.");

        service.counterfactualHandler = req -> new CounterfactualResponse(
                IntelligenceContractVersion.V1, cfProv, cfCard,
                new BigDecimal("51.00"), new BigDecimal("48.00"), true,
                List.of(new CounterfactualCandidate(
                        "cf-loop-1", 1,
                        List.of(new CounterfactualLeverDelta("overtimeCapPct", 15.0d, 10.0d, -0.33d, new BigDecimal("-3.00"))),
                        new BigDecimal("48.00"), new BigDecimal("-3.00"), 0.5d,
                        CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE, List.of("OK"))),
                0);
        final int[] cfRound = {0};
        AiToolCallingProvider cfProvider = new AiToolCallingProvider() {
            @Override public String providerId() { return "scripted-cf"; }
            @Override public boolean hasConfiguredCredentials(AiCallConfig c) { return true; }
            @Override public AgentModelTurnResponse generateTurn(
                    org.praxisplatform.config.ai.agent.model.AgentModelTurnRequest request,
                    org.praxisplatform.config.ai.agent.model.AgentModelStreamListener listener) {
                if (cfRound[0]++ == 0) {
                    return new AgentModelTurnResponse("scripted-cf", "v1", "",
                            List.of(new AgentToolCall("call-cf-1", RunResourceCounterfactualSearchTool.TOOL_NAME,
                                    Map.of("entityKey", "workforce.payroll-slips",
                                            "targetMetricKey", "observedValue",
                                            "direction", "DECREASE_TO_TARGET",
                                            "baselineValue", 51.0d,
                                            "targetValue", 48.0d,
                                            "mutableLevers", List.of(Map.of(
                                                    "leverKey", "overtimeCapPct",
                                                    "leverType", "CONTINUOUS",
                                                    "currentValue", 15.0d,
                                                    "minValue", 5.0d,
                                                    "maxValue", 20.0d))),
                                    null, null)),
                            new AgentTokenUsage(50, 10, 60), "tool_calls", "c1", Map.of());
                }
                return new AgentModelTurnResponse("scripted-cf", "v1", "", List.of(),
                        new AgentTokenUsage(60, 10, 70), "stop", "c2", Map.of());
            }
        };
        PraxisOperationalAgentLoop cfLoop = new PraxisOperationalAgentLoop(
                cfProvider, List.of(cfTool),
                new OperationalAssistantBlockComposer(new ObjectMapper().findAndRegisterModules(),
                        List.of(new StatisticalIntelligenceBlockContributor())),
                4, bridge);
        PraxisOperationalAgentLoop.AgentTurnOutcome cfOutcome = cfLoop.execute(
                ctx, "Busque contrafactual", null, List.of(),
                AiCallConfig.builder().provider("scripted-cf").model("v1").build(),
                PraxisOperationalAgentLoop.TurnProgressObserver.NOOP);
        assertThat(cfOutcome.traces().get(0).itemCount()).isEqualTo(1);
        assertThat(cfOutcome.assistantMessage())
                .contains("Alternativas contrafactuais validadas pelo motor de regras disponíveis abaixo");
    }
}
