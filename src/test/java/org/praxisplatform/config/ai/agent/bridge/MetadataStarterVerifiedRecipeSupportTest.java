package org.praxisplatform.config.ai.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.spi.AgentAnalyticalRecipeSummary;
import org.praxisplatform.config.ai.agent.spi.AgentVerifiedRecipeExecutionRequest;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;

@Tag("unit")
class MetadataStarterVerifiedRecipeSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public static class SampleDto {
        private Long id;
        private String status;
        private BigDecimal restrictedBonus;

        public Long getId() { return id; }
        public String getStatus() { return status; }
        public BigDecimal getRestrictedBonus() { return restrictedBonus; }
    }

    public static class SampleFilterDto implements GenericFilterDTO {
        private String status;
        @Filterable(operation = Filterable.FilterOperation.GREATER_OR_EQUAL, relation = "restrictedBonus")
        private BigDecimal minRestricted;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public BigDecimal getMinRestricted() { return minRestricted; }
        public void setMinRestricted(BigDecimal minRestricted) { this.minRestricted = minRestricted; }
    }

    @ApiResource(
            value = "/api/workforce/assignments",
            resourceKey = "workforce.assignments",
            title = "Assignments",
            identity = @ResourceIdentity(keyField = "id", titleField = "status"))
    public static class StubController extends AbstractResourceQueryController<SampleDto, Long, SampleFilterDto> {
        private final BaseResourceQueryService<SampleDto, Long, SampleFilterDto> service;

        StubController(BaseResourceQueryService<SampleDto, Long, SampleFilterDto> service) {
            this.service = service;
        }

        @Override protected BaseResourceQueryService<SampleDto, Long, SampleFilterDto> getService() { return service; }
        @Override protected Long getResponseId(SampleDto dto) { return dto.getId(); }

        @Override
        protected List<VerifiedAnalyticalRecipeDescriptor<SampleFilterDto>> defineAnalyticalRecipes() {
            return List.of(
                    VerifiedAnalyticalRecipeDescriptor.<SampleFilterDto>builder("monthly-hires", "Admissoes Mensais")
                            .operation(AnalyticsOperation.TIMESERIES)
                            .field("referenceDate")
                            .granularity(TimeSeriesGranularity.MONTH)
                            .metric(StatsMetric.COUNT, null)
                            .kpiHint(new VerifiedAnalyticalRecipeDescriptor.KpiHint(
                                    "higher-is-better",
                                    "admissao",
                                    "admissoes",
                                    "scalar",
                                    "sparkline",
                                    BigDecimal.valueOf(10),
                                    List.of(Map.of("max", 10, "tone", "success"))))
                            .build(),
                    VerifiedAnalyticalRecipeDescriptor.<SampleFilterDto>builder("bonus-summary", "Bonus Restrito")
                            .operation(AnalyticsOperation.GROUP_BY)
                            .field("restrictedBonus")
                            .metric(StatsMetric.SUM, "restrictedBonus")
                            .build());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsKpiHintHandlesNullableSuppressedCountAndFiltersBlockedFieldUseRecipes() {
        BaseResourceQueryService<SampleDto, Long, SampleFilterDto> service = mock(BaseResourceQueryService.class);
        StatsFieldRegistry registry = StatsFieldRegistry.builder()
                .add(new StatsFieldDescriptor(
                        "referenceDate",
                        "referenceDate",
                        "referenceDate",
                        Set.of(StatsMetric.COUNT),
                        false,
                        true,
                        false,
                        false,
                        false,
                        null,
                        null))
                .add(new StatsFieldDescriptor(
                        "restrictedBonus",
                        "restrictedBonus",
                        "restrictedBonus",
                        Set.of(StatsMetric.SUM),
                        true,
                        false,
                        false,
                        false,
                        true,
                        null,
                        null))
                .build();
        when(service.getStatsFieldRegistry()).thenReturn(registry);
        when(service.getTimeSeriesStatsSupportMode()).thenReturn(StatsSupportMode.AUTO);
        when(service.timeSeriesStats(any())).thenReturn(new TimeSeriesStatsResponse(
                "referenceDate",
                TimeSeriesGranularity.MONTH,
                null,
                List.of(
                        new TimeSeriesPoint(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "Jan/26", 12, 12L, null, false),
                        new TimeSeriesPoint(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "Fev/26", null, (Long) null, null, true))));

        StubController controller = new StubController(service);
        VerifiedAnalyticalRecipeResolver resolver = new VerifiedAnalyticalRecipeResolver(null, objectMapper, null, List.of());

        List<AgentAnalyticalRecipeSummary> all = MetadataStarterVerifiedRecipeSupport.extractRecipes(
                controller, resolver, objectMapper);
        assertThat(all).hasSize(2);
        AgentAnalyticalRecipeSummary hires = all.stream()
                .filter(r -> "monthly-hires".equals(r.recipeId()))
                .findFirst()
                .orElseThrow();
        assertThat(hires.kpiHint())
                .containsEntry("polarity", "higher-is-better")
                .containsEntry("suggestedVisualizationKind", "sparkline")
                .containsEntry("referenceTarget", BigDecimal.valueOf(10));

        // Filter out recipes referencing blocked FIELD_USE fields
        List<AgentAnalyticalRecipeSummary> allowed = MetadataStarterVerifiedRecipeSupport.extractRecipes(
                controller, resolver, objectMapper, SampleFilterDto.class, Set.of("restrictedBonus"));
        assertThat(allowed).extracting(AgentAnalyticalRecipeSummary::recipeId)
                .containsExactly("monthly-hires");

        // TimeSeries execution with suppressed null count uses pt.countOrZero() safely without NPE
        MetadataStarterVerifiedRecipeSupport.RecipeExecutionOutcome outcome =
                MetadataStarterVerifiedRecipeSupport.executeRecipe(
                        controller,
                        service,
                        SampleFilterDto.class,
                        registry,
                        Set.of("restrictedBonus"),
                        resolver,
                        objectMapper,
                        new AgentVerifiedRecipeExecutionRequest("workforce/assignments", "monthly-hires", null, null, null, null));
        assertThat(outcome.rawBuckets()).hasSize(2);
        assertThat(outcome.rawBuckets().get(1).count()).isZero();

        // Fail-closed execution refusal for blocked FIELD_USE recipe
        assertThatThrownBy(() -> MetadataStarterVerifiedRecipeSupport.executeRecipe(
                controller,
                service,
                SampleFilterDto.class,
                registry,
                Set.of("restrictedBonus"),
                resolver,
                objectMapper,
                new AgentVerifiedRecipeExecutionRequest("workforce/assignments", "bonus-summary", null, null, null, null)))
                .isInstanceOf(SecurityException.class);
    }
}
