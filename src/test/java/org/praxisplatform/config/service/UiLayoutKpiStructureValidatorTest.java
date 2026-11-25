package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.uischema.analytics.ResolvedVerifiedAnalyticalRecipe;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeDescriptor;
import org.praxisplatform.uischema.analytics.VerifiedAnalyticalRecipeResolver;
import org.praxisplatform.uischema.annotation.AnalyticalRecipeGovernanceTier;
import org.praxisplatform.uischema.annotation.AnalyticsOperation;
import org.praxisplatform.uischema.annotation.AnalyticsDimensionBinding;
import org.praxisplatform.uischema.annotation.AnalyticsIntent;
import org.praxisplatform.uischema.annotation.AnalyticsMetricBinding;
import org.praxisplatform.uischema.annotation.AnalyticsProjection;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.UiAnalytics;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.ComparisonPeriodMode;
import org.praxisplatform.uischema.stats.ComparisonPeriodPreset;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag("unit")
class UiLayoutKpiStructureValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-25T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Test
    void rejectsMoreThanTwelveCardsInSingleStrip() {
        ObjectNode stripDoc = buildStripPageDocument(13, false);

        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validateStructureOnly(
                                        "praxis.dynamic-page.editor", stripDoc))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }

    @Test
    void enforcesTwentyFourDistinctQueryQuotaWithDeduplication() {
        ObjectNode withinLimit = buildMultiStripPage(List.of(12, 12), false);
        assertThatCode(
                        () ->
                                UiLayoutKpiStructureValidator.validateStructureOnly(
                                        "praxis.dynamic-page.editor", withinLimit))
                .doesNotThrowAnyException();

        // Add a 3rd strip of 6 cards whose queries duplicate the first strip with leading slash differences
        ObjectNode duplicatedPage = buildMultiStripPage(List.of(12, 12, 6), true);
        assertThatCode(
                        () ->
                                UiLayoutKpiStructureValidator.validateStructureOnly(
                                        "praxis.dynamic-page.editor", duplicatedPage))
                .doesNotThrowAnyException();

        // 25 distinct queries must fail closed
        ObjectNode overQuota = buildMultiStripPage(List.of(12, 12, 1), false);
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validateStructureOnly(
                                        "praxis.dynamic-page.editor", overQuota))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .hasMessageContaining("24");
    }

    @Test
    void rejectsSsrfAndUnsafePathsAcrossSourceCrossFilterAndDrillDown() {
        for (String badPath :
                List.of(
                        "https://evil.example/api/workforce",
                        "//evil.example/workforce",
                        "workforce/../payroll/salaries",
                        "workforce/%2e%2e/secret",
                        "workforce/assignments?tenant=other",
                        "workforce/assignments#frag",
                        "workforce\\assignments")) {
            ObjectNode badSource = buildSingleCardPage(badPath, null, null);
            assertThatThrownBy(
                            () ->
                                    UiLayoutKpiStructureValidator.validateStructureOnly(
                                            "praxis.dynamic-page.editor", badSource))
                    .isInstanceOf(UiLayoutLifecycleException.class);

            ObjectNode badCross = buildSingleCardPage("workforce/assignments", badPath, null);
            assertThatThrownBy(
                            () ->
                                    UiLayoutKpiStructureValidator.validateStructureOnly(
                                            "praxis.dynamic-page.editor", badCross))
                    .isInstanceOf(UiLayoutLifecycleException.class);

            ObjectNode badDrill = buildSingleCardPage("workforce/assignments", null, badPath);
            assertThatThrownBy(
                            () ->
                                    UiLayoutKpiStructureValidator.validateStructureOnly(
                                            "praxis.dynamic-page.editor", badDrill))
                    .isInstanceOf(UiLayoutLifecycleException.class);
        }
    }

    @Test
    void enforcesCatalogAllowlistVerifiedRecipesAndStatsFieldContracts() {
        UiLayoutKpiCatalogOracle catalog =
                UiLayoutKpiCatalogOracle.builder()
                        .registerStatsContract(
                                "workforce/assignments",
                                Set.of("status", "departmentCode", "effectiveDate", "headcount"),
                                Set.of("status", "departmentId", "active"))
                        .registerVerifiedRecipe(
                                "workforce/assignments",
                                "active-rate",
                                Set.of("departmentId"))
                        .allowResource("workforce/employees")
                        .build();

        // Valid stats-query + crossFilter + drillDown
        ObjectNode validStatsPage = buildSingleCardPage("workforce/assignments", "workforce/employees", "workforce/employees");
        assertThatCode(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", validStatsPage, catalog))
                .doesNotThrowAnyException();

        // Unregistered resourcePath fails
        ObjectNode unlistedPage = buildSingleCardPage("payroll/salaries", null, null);
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", unlistedPage, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class);

        // Invalid stats field fails
        ObjectNode badStatsField = validStatsPage.deepCopy();
        ((ObjectNode) badStatsField.at("/config/definition/widgets/0/definition/inputs/config/source/request"))
                .put("field", "salary");
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", badStatsField, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class);

        // Invalid baseFilter key fails
        ObjectNode badBaseFilter = validStatsPage.deepCopy();
        ((ObjectNode) badBaseFilter.at("/config/definition/widgets/0/definition/inputs/config/source/request"))
                .putObject("baseFilter")
                .put("unlistedFilterKey", "x");
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", badBaseFilter, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class);

        // Valid verified-recipe
        ObjectNode recipePage = buildVerifiedRecipePage("workforce/assignments", "active-rate", Map.of("departmentId", "ENG"));
        assertThatCode(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", recipePage, catalog))
                .doesNotThrowAnyException();

        // Unknown recipeId fails
        ObjectNode unknownRecipe = buildVerifiedRecipePage("workforce/assignments", "unregistered-recipe", Map.of());
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", unknownRecipe, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class);

        // Unadjustable parameter fails
        ObjectNode lockedParam = buildVerifiedRecipePage("workforce/assignments", "active-rate", Map.of("secretParam", "1"));
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", lockedParam, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class);
    }

    @Test
    void metadataStarterOracleIntrospectsControllersStatsFieldsFilterDtoAndVerifiedRecipes() {
        SampleAssignmentsController controller = new SampleAssignmentsController();
        VerifiedAnalyticalRecipeResolver resolver = mock(VerifiedAnalyticalRecipeResolver.class);
        ResolvedVerifiedAnalyticalRecipe resolvedRecipe =
                new ResolvedVerifiedAnalyticalRecipe(
                        "active-ratio",
                        "Taxa de Vínculos Ativos",
                        "Proporção ativa",
                        "Monitorar ativos",
                        "workforce.assignments",
                        "/api/workforce/assignments",
                        "/api/workforce/assignments/stats/recipes/active-ratio",
                        AnalyticsOperation.GROUP_BY,
                        "status",
                        List.of(),
                        List.of(),
                        StatsBucketOrder.VALUE_DESC,
                        10,
                        TimeSeriesGranularity.MONTH,
                        false,
                        DistributionMode.TERMS,
                        null,
                        BigDecimal.ONE,
                        "UTC",
                        "effectiveDate",
                        ComparisonPeriodPreset.LAST_30_DAYS,
                        ComparisonPeriodMode.PREVIOUS_ALIGNED,
                        Map.of(),
                        List.of(
                                new VerifiedAnalyticalRecipeDescriptor.ParameterSpec(
                                        "departmentCode",
                                        "departmentCode",
                                        "Código do departamento",
                                        "string",
                                        false,
                                        null,
                                        List.of(),
                                        null,
                                        null)),
                        AnalyticalRecipeGovernanceTier.CERTIFIED,
                        List.of("workforce"),
                        "1.0.0",
                        1,
                        "digest-1");
        when(resolver.resolveByResourcePath("/api/workforce/assignments"))
                .thenReturn(List.of(resolvedRecipe));

        var appContext = new org.springframework.context.support.StaticApplicationContext();
        appContext.getBeanFactory().registerSingleton("assignmentsController", controller);
        appContext.getBeanFactory().registerSingleton("recipeResolver", resolver);
        appContext.refresh();

        MetadataStarterUiLayoutKpiCatalogOracle catalog =
                MetadataStarterUiLayoutKpiCatalogOracle.fromApplicationContext(appContext);

        assertThat(catalog.isResourcePathAllowed("workforce/assignments")).isTrue();
        assertThat(catalog.isResourcePathAllowed("api/workforce/assignments")).isTrue();
        assertThat(catalog.isResourcePathAllowed("payroll/salaries")).isFalse();

        assertThat(catalog.findResourceStatsContract("workforce/assignments"))
                .isPresent()
                .get()
                .satisfies(
                        contract -> {
                            assertThat(contract.statsFields())
                                    .containsExactlyInAnyOrder(
                                            "status", "effectiveDate", "headcountMeasure");
                            assertThat(contract.filterDtoFields())
                                    .containsExactlyInAnyOrder("status", "departmentCode");
                            assertThat(contract.fieldCapabilities().get("effectiveDate").timeSeriesEligible())
                                    .isTrue();
                            assertThat(contract.fieldCapabilities().get("effectiveDate").groupByEligible())
                                    .isFalse();
                        });

        assertThat(catalog.findAnalyticsProjectionIds("workforce/assignments"))
                .isPresent()
                .get()
                .satisfies(ids -> assertThat(ids).containsExactly("status-breakdown"));

        assertThat(catalog.findVerifiedRecipe("workforce/assignments", "active-ratio"))
                .isPresent()
                .get()
                .satisfies(
                        spec -> assertThat(spec.adjustableParams()).containsExactly("departmentCode"));

        // Per-operation stats field eligibility & @UiAnalytics projection validation via introspected catalog
        ObjectNode tsOnGroupByField = buildSingleCardPage("workforce/assignments", null, null);
        ObjectNode tsSource =
                (ObjectNode) tsOnGroupByField.at("/config/definition/widgets/0/definition/inputs/config/source");
        tsSource.put("operation", "timeseries");
        ObjectNode tsReq = tsSource.putObject("request");
        tsReq.put("field", "status");
        tsReq.put("granularity", "MONTH");
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", tsOnGroupByField, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .hasMessageContaining("timeSeriesEligible");

        ObjectNode badMetricOp = buildSingleCardPage("workforce/assignments", null, null);
        ObjectNode badMetricReq =
                (ObjectNode) badMetricOp.at("/config/definition/widgets/0/definition/inputs/config/source/request");
        badMetricReq.putObject("metric").put("operation", "AVG").put("field", "headcountMeasure");
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", badMetricOp, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .hasMessageContaining("is not allowed on metric field");

        ObjectNode badProjection = buildSingleCardPage("workforce/assignments", null, null);
        ObjectNode badProjSrc =
                (ObjectNode) badProjection.at("/config/definition/widgets/0/definition/inputs/config/source");
        badProjSrc.removeAll();
        badProjSrc.put("kind", "analytics-projection");
        badProjSrc.put("resourcePath", "workforce/assignments");
        badProjSrc.put("projectionId", "unknown-projection");
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", badProjection, catalog))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .hasMessageContaining("is not registered in @UiAnalytics");

        ObjectNode validProjection = badProjection.deepCopy();
        ((ObjectNode) validProjection.at("/config/definition/widgets/0/definition/inputs/config/source"))
                .put("projectionId", "status-breakdown");
        assertThatCode(
                        () ->
                                UiLayoutKpiStructureValidator.validate(
                                        "praxis.dynamic-page.editor", validProjection, catalog))
                .doesNotThrowAnyException();

        // Nested tabs/panels quota & SSRF enforcement
        ObjectNode nestedTabSsrf = MAPPER.createObjectNode();
        nestedTabSsrf.put("kind", "praxis.dynamic-page.editor");
        nestedTabSsrf.put("version", 1);
        ObjectNode cfg = nestedTabSsrf.putObject("config");
        ObjectNode tabWidget = cfg.putObject("definition").putArray("widgets").addObject();
        tabWidget.put("key", "tabs-1");
        ObjectNode tabDef = tabWidget.putObject("definition");
        tabDef.put("id", "praxis-tabs");
        ObjectNode nestedCard =
                tabDef.putObject("inputs")
                        .putObject("config")
                        .putArray("tabs")
                        .addObject()
                        .put("id", "tab-1")
                        .putArray("widgets")
                        .addObject();
        nestedCard.put("id", "praxis-kpi-card");
        nestedCard.put("childWidgetKey", "nested-kpi-1");
        nestedCard.putObject("inputs")
                .set("config", cardNode(99, "https://evil.example/payroll", "status", null, null));
        assertThatThrownBy(
                        () ->
                                UiLayoutKpiStructureValidator.validateStructureOnly(
                                        "praxis.dynamic-page.editor", nestedTabSsrf))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .hasMessageContaining("anti-SSRF");
    }

    @Test
    void corporatePageRuntimeSupportStructureValidatorEnforcesKpiOracleFailClosed() {
        UiLayoutKpiCatalogOracle denyAll = UiLayoutKpiCatalogOracle.denyAll();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("kpiOracle", denyAll);

        CorporatePageCatalogContributor contributor =
                new CorporatePageCatalogContributor() {
                    @Override
                    public String applicationId() {
                        return "workforce-suite";
                    }
                };
        CorporatePageRuntimeSupport support =
                new CorporatePageRuntimeSupport(
                        contributor,
                        MAPPER,
                        FIXED_CLOCK,
                        beans.getBeanProvider(PageRuntimeReadService.class),
                        beans.getBeanProvider(UiLayoutKpiCatalogOracle.class));

        UiLayoutTarget pageTarget =
                new UiLayoutTarget("praxis-dynamic-page", "11111111-1111-4111-8111-111111111111");
        UiLayoutLifecycleInvocation invocation =
                new UiLayoutLifecycleInvocation(
                        "hr.analyst",
                        "default",
                        "corporate",
                        "prod",
                        "v1",
                        new UiLayoutCompositionRegistration(pageTarget, List.of(pageTarget)));
        UiLayoutAuthoringDocumentDescriptor descriptor =
                new UiLayoutAuthoringDocumentDescriptor(
                        "praxis.dynamic-page.editor", "workforce-suite:page", "1");
        UiLayoutValidationContext ctx =
                UiLayoutValidationContext.begin(
                        (op, inv) -> java.time.Duration.ofSeconds(30),
                        invocation,
                        UiLayoutLifecycleOperation.CREATE_DRAFT,
                        UiLayoutValidationPurpose.AUTHORING);

        ObjectNode page = buildSingleCardPage("workforce/assignments", null, null);
        assertThatThrownBy(
                        () ->
                                support.structureValidator()
                                        .validateAuthoringBaseline(
                                                invocation, pageTarget, descriptor, page, ctx))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .hasMessageContaining("not admitted");
    }

    private static ObjectNode buildStripPageDocument(int cardCount, boolean sameQuery) {
        return buildMultiStripPage(List.of(cardCount), sameQuery);
    }

    private static ObjectNode buildMultiStripPage(List<Integer> stripSizes, boolean sameQuery) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "praxis.dynamic-page.editor");
        root.put("version", 1);
        ObjectNode config = root.putObject("config");
        ObjectNode definition = config.putObject("definition");
        ArrayNode widgets = definition.putArray("widgets");

        int globalIdx = 0;
        for (int s = 0; s < stripSizes.size(); s++) {
            ObjectNode widget = widgets.addObject();
            widget.put(
                    "key",
                    String.format("10000000-0000-4000-8000-%012d", s + 1));
            ObjectNode def = widget.putObject("definition");
            def.put("id", "praxis-kpi-strip");
            ObjectNode stripCfg = def.putObject("inputs").putObject("config");
            stripCfg.put("schemaVersion", 1);
            ArrayNode cards = stripCfg.putArray("cards");
            int count = stripSizes.get(s);
            for (int c = 0; c < count; c++) {
                String path =
                        sameQuery
                                ? (s % 2 == 0 ? "workforce/assignments" : "/workforce/assignments/")
                                : "workforce/assignments";
                String field = sameQuery ? ("dim_" + c) : ("dim_" + globalIdx);
                cards.add(cardNode(globalIdx + 1, path, field, null, null));
                globalIdx++;
            }
        }
        ObjectNode presentation = config.putObject("presentation");
        presentation.put("title", "Workforce KPIs");
        presentation.put("icon", "analytics");
        config.putArray("navigation");
        return root;
    }

    private static ObjectNode buildSingleCardPage(
            String resourcePath, String crossFilterPath, String drillDownPath) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "praxis.dynamic-page.editor");
        root.put("version", 1);
        ObjectNode config = root.putObject("config");
        ArrayNode widgets = config.putObject("definition").putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "10000000-0000-4000-8000-000000000001");
        ObjectNode def = widget.putObject("definition");
        def.put("id", "praxis-kpi-card");
        def.putObject("inputs")
                .set("config", cardNode(1, resourcePath, "status", crossFilterPath, drillDownPath));
        ObjectNode presentation = config.putObject("presentation");
        presentation.put("title", "Workforce Card");
        presentation.put("icon", "analytics");
        config.putArray("navigation");
        return root;
    }

    private static ObjectNode buildVerifiedRecipePage(
            String resourcePath, String recipeId, Map<String, String> params) {
        ObjectNode page = buildSingleCardPage(resourcePath, null, null);
        ObjectNode card =
                (ObjectNode) page.at("/config/definition/widgets/0/definition/inputs/config");
        ObjectNode source = card.putObject("source");
        source.put("kind", "verified-recipe");
        source.put("resourcePath", resourcePath);
        source.put("recipeId", recipeId);
        if (!params.isEmpty()) {
            ObjectNode paramsObj = source.putObject("parameters");
            params.forEach(paramsObj::put);
        }
        return page;
    }

    private static ObjectNode cardNode(
            int index,
            String resourcePath,
            String field,
            String crossFilterTarget,
            String drillDownTarget) {
        ObjectNode card = MAPPER.createObjectNode();
        card.put("schemaVersion", 1);
        card.put("id", String.format("20000000-0000-4000-8000-%012d", index));
        ObjectNode presentation = card.putObject("presentation");
        presentation.put("label", "KPI " + index);
        presentation.put("format", "integer");
        ObjectNode source = card.putObject("source");
        source.put("kind", "stats-query");
        source.put("resourcePath", resourcePath);
        source.put("operation", "group-by");
        source.putObject("request").put("field", field);
        ObjectNode derivation = card.putObject("derivation");
        derivation.put("kind", "scalar");
        derivation.put("aggregation", "total");
        if (crossFilterTarget != null || drillDownTarget != null) {
            ObjectNode interaction = card.putObject("interaction");
            if (crossFilterTarget != null) {
                ObjectNode cf = interaction.putObject("crossFilter");
                cf.put("field", "status");
                cf.putArray("values").add("ACTIVE");
                cf.put("targetResourcePath", crossFilterTarget);
            }
            if (drillDownTarget != null) {
                interaction.putObject("drillDown").put("resourcePath", drillDownTarget);
            }
        }
        return card;
    }

    static class SampleFilterDTO implements GenericFilterDTO {
        private String status;
        private String departmentCode;
    }

    @ApiResource(value = "/api/workforce/assignments", resourceKey = "workforce.assignments")
    @RequestMapping("/api/workforce/assignments")
    @UiAnalytics(
            projections = {
                @AnalyticsProjection(
                        id = "status-breakdown",
                        intent = AnalyticsIntent.COMPOSITION,
                        sourceOperation = AnalyticsOperation.GROUP_BY,
                        primaryDimension = @AnalyticsDimensionBinding(field = "status"),
                        primaryMetrics = {
                            @AnalyticsMetricBinding(field = "id", aggregation = "count", label = "Total")
                        })
            })
    static class SampleAssignmentsController
            extends AbstractResourceQueryController<Object, Long, SampleFilterDTO> {
        private final BaseResourceQueryService<Object, Long, SampleFilterDTO> service =
                mockService();

        @SuppressWarnings("unchecked")
        private static BaseResourceQueryService<Object, Long, SampleFilterDTO> mockService() {
            BaseResourceQueryService<Object, Long, SampleFilterDTO> svc =
                    mock(BaseResourceQueryService.class);
            when(svc.getStatsFieldRegistry())
                    .thenReturn(
                            StatsFieldRegistry.builder()
                                    .categoricalGroupByBucket("status", "status")
                                    .temporalTimeSeriesField("effectiveDate", "effectiveDate")
                                    .metricField(
                                            "headcountMeasure",
                                            "headcount",
                                            Set.of(org.praxisplatform.uischema.stats.StatsMetric.SUM))
                                    .build());
            return svc;
        }

        @Override
        protected BaseResourceQueryService<Object, Long, SampleFilterDTO> getService() {
            return service;
        }

        @Override
        protected Long getResponseId(Object dto) {
            return 1L;
        }
    }
}
