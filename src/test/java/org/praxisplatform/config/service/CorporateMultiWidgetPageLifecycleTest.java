package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class CorporateMultiWidgetPageLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-20T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final UUID PAGE_ID = UUID.fromString("10000000-0000-4000-a000-000000000201");
    private static final String TABLE_WIDGET_KEY = "20000000-0000-4000-a000-000000000201";
    private static final String CHART_WIDGET_KEY = "20000000-0000-4000-a000-000000000202";
    private static final String ANALYTICS_WIDGET_KEY = "20000000-0000-4000-a000-000000000203";
    private static final String LIST_WIDGET_KEY = "20000000-0000-4000-a000-000000000204";
    private static final UiLayoutTarget PAGE_TARGET =
            new UiLayoutTarget("praxis-dynamic-page", PAGE_ID.toString());
    private static final UiLayoutLifecycleInvocation INVOCATION =
            new UiLayoutLifecycleInvocation(
                    "hr.director",
                    "default",
                    "corporate",
                    "prod",
                    "v1",
                    new UiLayoutCompositionRegistration(PAGE_TARGET, List.of(PAGE_TARGET)));

    @Test
    void rejectsComposedWidgetRevisionFailClosedWhenContributorDoesNotOptIn() {
        CorporatePageRuntimeSupport support = createSupport(baseContributor(Set.of(), false));
        CapturesFixture fixture = createInitialFixture(support);

        ObjectNode candidate = multiWidgetCandidate(false);
        List<UiLayoutPageChildSource> childSources = List.of(
                composedSource(CHART_WIDGET_KEY, "praxis-chart"),
                composedSource(ANALYTICS_WIDGET_KEY, "praxis-analytics-presentation"));

        assertThatThrownBy(
                        () ->
                                fixture.captures()
                                        .revision(
                                                INVOCATION,
                                                fixture.draftId(),
                                                fixture.state(),
                                                candidate,
                                                childSources,
                                                support.draftWorkspaceSource(),
                                                validation(UiLayoutLifecycleOperation.EDIT_DRAFT)))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    }

    @Test
    void admitsOptInMultiWidgetRevisionWithSafeCanvasShellAndAnalyticsPresentation() {
        AtomicBoolean denyPolicy = new AtomicBoolean(false);
        CorporatePageRuntimeSupport support =
                createSupport(
                        baseContributor(
                                Set.of("praxis-table", "praxis-chart", "praxis-analytics-presentation"),
                                denyPolicy));
        CapturesFixture fixture = createInitialFixture(support);
        UiLayoutDraftWorkspaceSource workspaceSource = support.draftWorkspaceSource();

        ObjectNode candidate = multiWidgetCandidate(false);
        ObjectNode patch = UiLayoutAuthoringProjection.project(
                        PAGE_TARGET,
                        fixture.seed().authoring(),
                        fixture.seed().document(),
                        candidate,
                        fixture.seed().patch(),
                        () -> {})
                .compilePatch();
        support.structureValidator()
                .validateAuthoringRevision(
                        INVOCATION,
                        PAGE_TARGET,
                        fixture.seed().authoring(),
                        fixture.seed().document(),
                        fixture.seed().authoring(),
                        candidate,
                        fixture.seed().patch(),
                        patch,
                        validation(UiLayoutLifecycleOperation.EDIT_DRAFT));

        List<UiLayoutPageChildSource> childSources = List.of(
                composedSource(CHART_WIDGET_KEY, "praxis-chart"),
                composedSource(ANALYTICS_WIDGET_KEY, "praxis-analytics-presentation"));

        List<UiLayoutPageChildEvidence> updatedEvidence =
                fixture.captures()
                        .revision(
                                INVOCATION,
                                fixture.draftId(),
                                fixture.state(),
                                candidate,
                                childSources,
                                workspaceSource,
                                validation(UiLayoutLifecycleOperation.EDIT_DRAFT));

        assertThat(updatedEvidence).hasSize(3);
        fixture.captures()
                .verify(
                        INVOCATION,
                        fixture.draftId(),
                        PAGE_TARGET,
                        fixture.seed().document(),
                        candidate,
                        updatedEvidence,
                        workspaceSource,
                        validation(UiLayoutLifecycleOperation.SUBMIT_RELEASE));

        // 4-arg direct capture without candidate must continue to fail closed for composed-widget sources
        assertThatThrownBy(
                        () ->
                                workspaceSource.capturePageChild(
                                        INVOCATION,
                                        PAGE_TARGET,
                                        composedSource(CHART_WIDGET_KEY, "praxis-chart"),
                                        validation(UiLayoutLifecycleOperation.EDIT_DRAFT)))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);

        // Policy hook denial (including ResponseStatusException) fails closed during verify
        denyPolicy.set(true);
        assertThatThrownBy(
                        () ->
                                fixture.captures()
                                        .verify(
                                                INVOCATION,
                                                fixture.draftId(),
                                                PAGE_TARGET,
                                                fixture.seed().document(),
                                                candidate,
                                                updatedEvidence,
                                                workspaceSource,
                                                validation(UiLayoutLifecycleOperation.SUBMIT_RELEASE)))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }

    @Test
    void rejectsUnadmittedWidgetTypeAndMismatchedDocumentKind() {
        CorporatePageRuntimeSupport support =
                createSupport(
                        baseContributor(
                                Set.of("praxis-table", "praxis-chart", "praxis-analytics-presentation"),
                                false));
        CapturesFixture fixture = createInitialFixture(support);
        UiLayoutDraftWorkspaceSource workspaceSource = support.draftWorkspaceSource();

        ObjectNode unadmittedCandidate = multiWidgetCandidate(true);
        List<UiLayoutPageChildSource> unadmittedSources = List.of(
                composedSource(CHART_WIDGET_KEY, "praxis-chart"),
                composedSource(ANALYTICS_WIDGET_KEY, "praxis-analytics-presentation"),
                composedSource(LIST_WIDGET_KEY, "praxis-list"));

        assertThatThrownBy(
                        () ->
                                fixture.captures()
                                        .revision(
                                                INVOCATION,
                                                fixture.draftId(),
                                                fixture.state(),
                                                unadmittedCandidate,
                                                unadmittedSources,
                                                workspaceSource,
                                                validation(UiLayoutLifecycleOperation.EDIT_DRAFT)))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.DENIED);

        ObjectNode mismatchedChildDoc = MAPPER.createObjectNode().put("kind", "praxis.list.editor").put("version", 1);
        mismatchedChildDoc.putObject("config");
        assertThatThrownBy(
                        () ->
                                workspaceSource.capturePageChild(
                                        INVOCATION,
                                        PAGE_TARGET,
                                        composedSource(CHART_WIDGET_KEY, "praxis-chart"),
                                        mismatchedChildDoc,
                                        validation(UiLayoutLifecycleOperation.EDIT_DRAFT)))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }

    @Test
    void admitsHistoricalBaselineAndComposedChildrenAcrossDifferentActorUnitAndContextVersion() {
        CorporatePageRuntimeSupport support =
                createSupport(
                        baseContributor(
                                Set.of("praxis-table", "praxis-chart", "praxis-analytics-presentation"),
                                false));
        CapturesFixture fixture = createInitialFixture(support);
        UiLayoutDraftWorkspaceSource workspaceSource = support.draftWorkspaceSource();

        ObjectNode candidate = multiWidgetCandidate(false);
        List<UiLayoutPageChildSource> childSources = List.of(
                composedSource(CHART_WIDGET_KEY, "praxis-chart"),
                composedSource(ANALYTICS_WIDGET_KEY, "praxis-analytics-presentation"));

        List<UiLayoutPageChildEvidence> updatedEvidence =
                fixture.captures()
                        .revision(
                                INVOCATION,
                                fixture.draftId(),
                                fixture.state(),
                                candidate,
                                childSources,
                                workspaceSource,
                                validation(UiLayoutLifecycleOperation.EDIT_DRAFT));

        UiLayoutBaselineMetadataAdmission metadataAdmission = support.baselineMetadataAdmission();
        UiLayoutTarget groupsRoot =
                new UiLayoutTarget(
                        UiLayoutNavigationGroupsRevisionReproduction.TYPE,
                        baseContributor(Set.of(), false)
                                .navigationGroupsRootId("default", "prod")
                                .toString());
        UiLayoutLifecycleInvocation managerGroupsInvocation =
                new UiLayoutLifecycleInvocation(
                        "hr.director",
                        "default",
                        "org-1",
                        "prod",
                        "ctx-v1",
                        new UiLayoutCompositionRegistration(groupsRoot, List.of(groupsRoot)));
        UiLayoutDraftWorkspaceSeed groupsSeed =
                workspaceSource.capture(
                        managerGroupsInvocation,
                        validationFor(managerGroupsInvocation, UiLayoutLifecycleOperation.CREATE_DRAFT));
        UiLayoutDraftWorkspaceSeed.TargetSeed groupsTargetSeed = groupsSeed.targets().getFirst();

        // 1. Dual-control approver on a new contextVersion approves/publishes the release
        UiLayoutLifecycleInvocation approverInvocation = invocationFor("hr.approver", "org-1", "ctx-v2");
        metadataAdmission.require(
                approverInvocation,
                PAGE_TARGET,
                fixture.seed().authoring(),
                fixture.seed().sourceRef(),
                fixture.seed().document(),
                fixture.seed().metadata());
        fixture.captures()
                .verify(
                        approverInvocation,
                        fixture.draftId(),
                        PAGE_TARGET,
                        fixture.seed().document(),
                        candidate,
                        updatedEvidence,
                        workspaceSource,
                        validationFor(approverInvocation, UiLayoutLifecycleOperation.PUBLISH_RELEASE));

        // 2. Original manager rotates organization/contextVersion and reads published page
        UiLayoutLifecycleInvocation managerRotatedInvocation = invocationFor("hr.director", "org-2", "ctx-v3");
        metadataAdmission.require(
                managerRotatedInvocation,
                PAGE_TARGET,
                fixture.seed().authoring(),
                fixture.seed().sourceRef(),
                fixture.seed().document(),
                fixture.seed().metadata());
        fixture.captures()
                .verify(
                        managerRotatedInvocation,
                        fixture.draftId(),
                        PAGE_TARGET,
                        fixture.seed().document(),
                        candidate,
                        updatedEvidence,
                        workspaceSource,
                        validationFor(managerRotatedInvocation, UiLayoutLifecycleOperation.READ_HEAD));

        // 3. Different reader user with its own contextVersion reads published page and groups
        UiLayoutLifecycleInvocation readerInvocation = invocationFor("hr.reader", "org-1", "ctx-v4");
        metadataAdmission.require(
                readerInvocation,
                PAGE_TARGET,
                fixture.seed().authoring(),
                fixture.seed().sourceRef(),
                fixture.seed().document(),
                fixture.seed().metadata());
        fixture.captures()
                .verify(
                        readerInvocation,
                        fixture.draftId(),
                        PAGE_TARGET,
                        fixture.seed().document(),
                        candidate,
                        updatedEvidence,
                        workspaceSource,
                        validationFor(readerInvocation, UiLayoutLifecycleOperation.READ_HEAD));

        UiLayoutLifecycleInvocation readerGroupsInvocation =
                new UiLayoutLifecycleInvocation(
                        "hr.reader",
                        "default",
                        "org-1",
                        "prod",
                        "ctx-v4",
                        new UiLayoutCompositionRegistration(groupsRoot, List.of(groupsRoot)));
        metadataAdmission.require(
                readerGroupsInvocation,
                groupsRoot,
                groupsTargetSeed.authoring(),
                groupsTargetSeed.sourceRef(),
                groupsTargetSeed.document(),
                groupsTargetSeed.metadata());
    }

    @Test
    void rejectsTamperedHistoricalMetadataProvenanceAndChildSourceMismatch() {
        CorporatePageRuntimeSupport support =
                createSupport(
                        baseContributor(
                                Set.of("praxis-table", "praxis-chart", "praxis-analytics-presentation"),
                                false));
        CapturesFixture fixture = createInitialFixture(support);
        UiLayoutBaselineMetadataSeed originalSeed = fixture.seed().metadata();
        UiLayoutBaselineMetadataSeed.NativeDocumentSource originalSource =
                (UiLayoutBaselineMetadataSeed.NativeDocumentSource) originalSeed.source();
        UiLayoutBaselineMetadataSeed.Observation originalObs = originalSeed.observation();
        UiLayoutLifecycleInvocation readerInvocation = invocationFor("hr.reader", "org-2", "ctx-v9");

        List<UiLayoutBaselineMetadataSeed> tamperedSeeds = List.of(
                new UiLayoutBaselineMetadataSeed(
                        new UiLayoutBaselineMetadataSeed.NativeDocumentSource(
                                "untrusted-service",
                                originalSource.documentRef(),
                                originalSource.documentRevision(),
                                originalSource.producerRef(),
                                originalSource.publicationRef()),
                        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                        originalObs,
                        originalSeed.rawInputText()),
                new UiLayoutBaselineMetadataSeed(
                        new UiLayoutBaselineMetadataSeed.NativeDocumentSource(
                                originalSource.serviceKey(),
                                originalSource.documentRef(),
                                originalSource.documentRevision(),
                                "tampered-producer",
                                originalSource.publicationRef()),
                        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                        originalObs,
                        originalSeed.rawInputText()),
                new UiLayoutBaselineMetadataSeed(
                        new UiLayoutBaselineMetadataSeed.NativeDocumentSource(
                                originalSource.serviceKey(),
                                originalSource.documentRef(),
                                originalSource.documentRevision(),
                                originalSource.producerRef(),
                                "tampered-publication"),
                        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                        originalObs,
                        originalSeed.rawInputText()),
                new UiLayoutBaselineMetadataSeed(
                        originalSource,
                        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                        new UiLayoutBaselineMetadataSeed.Observation(
                                originalObs.actorRef(),
                                originalObs.administrativeUnit(),
                                originalObs.contextVersion(),
                                "tampered:policy",
                                originalObs.policyRevision(),
                                originalObs.capturedAt()),
                        originalSeed.rawInputText()),
                new UiLayoutBaselineMetadataSeed(
                        originalSource,
                        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                        new UiLayoutBaselineMetadataSeed.Observation(
                                originalObs.actorRef(),
                                originalObs.administrativeUnit(),
                                originalObs.contextVersion(),
                                originalObs.policyRef(),
                                "999",
                                originalObs.capturedAt()),
                        originalSeed.rawInputText()),
                new UiLayoutBaselineMetadataSeed(
                        new UiLayoutBaselineMetadataSeed.OperationSource(
                                originalSource.serviceKey(),
                                "workforce.employees",
                                "/api/workforce/employees",
                                "GET",
                                "response",
                                "schema-ref-1",
                                originalSource.producerRef(),
                                originalSource.publicationRef()),
                        new UiLayoutBaselineMetadataSeed.SchemaProjection(
                                "norm-1",
                                "proj-1",
                                new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "asm-1", "{}")),
                        originalObs,
                        originalSeed.rawInputText()));

        for (UiLayoutBaselineMetadataSeed tampered : tamperedSeeds) {
            assertThatThrownBy(
                            () ->
                                    support.baselineMetadataAdmission()
                                            .require(
                                                    readerInvocation,
                                                    PAGE_TARGET,
                                                    fixture.seed().authoring(),
                                                    fixture.seed().sourceRef(),
                                                    fixture.seed().document(),
                                                    tampered))
                    .isInstanceOf(UiLayoutLifecycleException.class)
                    .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                    .isEqualTo(UiLayoutLifecycleException.Code.DENIED);
        }

        // Declared child request source must stay bound to stored metadata documentRef/documentRevision
        UiLayoutPageChildSeed originalChild = fixture.seed().pageChildren().getFirst();
        UiLayoutPageChildSource mismatchedRequest = new UiLayoutPageChildSource(
                originalChild.request().widgetKey(),
                new UiLayoutPageChildSource.NativeSource("registered-native", "other-page-source", "1"));
        UiLayoutPageChildSeed mismatchedChildSeed = new UiLayoutPageChildSeed(
                mismatchedRequest,
                originalChild.authoring(),
                originalChild.patch(),
                originalChild.sourceRef(),
                originalChild.document(),
                originalChild.metadata());

        assertThatThrownBy(
                        () ->
                                support.draftWorkspaceSource()
                                        .requirePageChildEvidence(
                                                readerInvocation,
                                                PAGE_TARGET,
                                                mismatchedChildSeed,
                                                validationFor(readerInvocation, UiLayoutLifecycleOperation.READ_HEAD)))
                .isInstanceOf(UiLayoutLifecycleException.class)
                .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                .isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }

    private CorporatePageRuntimeSupport createSupport(CorporatePageCatalogContributor contributor) {
        ObjectProvider<PageRuntimeReadService> provider =
                new StaticListableBeanFactory().getBeanProvider(PageRuntimeReadService.class);
        return new CorporatePageRuntimeSupport(contributor, MAPPER, FIXED_CLOCK, provider);
    }

    private CorporatePageCatalogContributor baseContributor(
            Set<String> admittedComposedWidgetTypes, boolean rejectAdmission) {
        return baseContributor(admittedComposedWidgetTypes, new AtomicBoolean(rejectAdmission));
    }

    private CorporatePageCatalogContributor baseContributor(
            Set<String> admittedComposedWidgetTypes, AtomicBoolean rejectAdmission) {
        ObjectNode baseline = baselinePageDocument();
        return new CorporatePageCatalogContributor() {
            @Override
            public String applicationId() {
                return "workforce-portal";
            }

            @Override
            public List<NativePageSeed> nativePages() {
                return List.of(
                        new NativePageSeed(
                                "workforce-overview",
                                "1",
                                PAGE_ID,
                                baseline,
                                "Workforce Overview",
                                "groups"));
            }

            @Override
            public Set<String> admittedComposedWidgetTypes() {
                return admittedComposedWidgetTypes;
            }

            @Override
            public void checkComposedWidgetAdmission(
                    UiLayoutLifecycleInvocation invocation,
                    UiLayoutTarget page,
                    String widgetType,
                    JsonNode childDocument) {
                if (rejectAdmission.get()) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Sensitive criteria rejected by host policy.");
                }
            }
        };
    }

    private CapturesFixture createInitialFixture(CorporatePageRuntimeSupport support) {
        UiLayoutDraftWorkspaceSeed workspaceSeed =
                support.draftWorkspaceSource().capture(INVOCATION, validation(UiLayoutLifecycleOperation.CREATE_DRAFT));
        UiLayoutDraftWorkspaceSeed.TargetSeed targetSeed = workspaceSeed.targets().get(0);
        CanonicalJsonHashService hashes = new CanonicalJsonHashService(MAPPER);
        UiLayoutPageChildCaptures captures = new UiLayoutPageChildCaptures(hashes);
        UUID draftId = UUID.randomUUID();
        List<UiLayoutPageChildEvidence> initialEvidence =
                captures.initial(
                        INVOCATION,
                        draftId,
                        targetSeed,
                        support.draftWorkspaceSource(),
                        validation(UiLayoutLifecycleOperation.CREATE_DRAFT));
        UiLayoutDraftWorkspaceDocument.TargetState state =
                new UiLayoutDraftWorkspaceDocument.TargetState(
                        PAGE_TARGET,
                        targetSeed.authoring(),
                        targetSeed.patch(),
                        new UiLayoutDraftWorkspaceDocument.BaselineDocument(
                                targetSeed.sourceRef(),
                                hashes.sha256Exact(targetSeed.document()),
                                targetSeed.document()),
                        new UiLayoutDraftWorkspaceDocument.WorkingDocument(
                                hashes.sha256Exact(targetSeed.document()),
                                targetSeed.document()),
                        null,
                        null,
                        initialEvidence);
        return new CapturesFixture(draftId, targetSeed, captures, state);
    }

    private ObjectNode baselinePageDocument() {
        ObjectNode doc = MAPPER.createObjectNode().put("kind", "praxis.dynamic-page.editor").put("version", 1);
        ObjectNode config = doc.putObject("config");
        ObjectNode def = config.putObject("definition");
        def.putObject("layout").put("orientation", "columns").put("columns", 12).put("gap", "20px");
        ArrayNode widgets = def.putArray("widgets");
        widgets.add(tableWidget(TABLE_WIDGET_KEY));
        config.putObject("presentation")
                .put("title", "Workforce Overview")
                .put("icon", "groups")
                .put("description", "Governed workforce baseline");
        config.putArray("navigation");
        return doc;
    }

    private ObjectNode multiWidgetCandidate(boolean includeUnadmittedList) {
        ObjectNode doc = MAPPER.createObjectNode().put("kind", "praxis.dynamic-page.editor").put("version", 1);
        ObjectNode config = doc.putObject("config");
        ObjectNode def = config.putObject("definition");
        def.putObject("layout").put("orientation", "columns").put("columns", 12).put("gap", "20px");
        ObjectNode canvas = def.putObject("canvas").put("mode", "grid").put("columns", 12).put("gap", "16px");
        ObjectNode items = canvas.putObject("items");
        items.putObject(TABLE_WIDGET_KEY)
                .put("widgetKey", TABLE_WIDGET_KEY)
                .put("col", 1)
                .put("row", 1)
                .put("colSpan", 12)
                .put("rowSpan", 4);
        items.putObject(CHART_WIDGET_KEY)
                .put("widgetKey", CHART_WIDGET_KEY)
                .put("col", 1)
                .put("row", 5)
                .put("colSpan", 6)
                .put("rowSpan", 4);
        items.putObject(ANALYTICS_WIDGET_KEY)
                .put("widgetKey", ANALYTICS_WIDGET_KEY)
                .put("col", 7)
                .put("row", 5)
                .put("colSpan", 6)
                .put("rowSpan", 4);

        ArrayNode widgets = def.putArray("widgets");
        widgets.add(tableWidget(TABLE_WIDGET_KEY));
        widgets.add(chartWidget(CHART_WIDGET_KEY));
        widgets.add(analyticsPresentationWidget(ANALYTICS_WIDGET_KEY));
        if (includeUnadmittedList) {
            items.putObject(LIST_WIDGET_KEY)
                    .put("widgetKey", LIST_WIDGET_KEY)
                    .put("col", 1)
                    .put("row", 9)
                    .put("colSpan", 12)
                    .put("rowSpan", 3);
            widgets.add(listWidget(LIST_WIDGET_KEY));
        }

        config.putObject("presentation")
                .put("title", "Workforce Multi-Widget Overview")
                .put("icon", "groups")
                .put("description", "Governed multi-widget workforce projection");
        config.putArray("navigation");
        return doc;
    }

    private ObjectNode tableWidget(String key) {
        ObjectNode w = MAPPER.createObjectNode().put("key", key);
        w.putObject("shell")
                .put("kind", "dashboard-card")
                .put("title", "Employee Directory")
                .put("icon", "groups")
                .put("showHeader", true);
        ObjectNode def = w.putObject("definition").put("id", "praxis-table");
        ObjectNode inputs = def.putObject("inputs");
        inputs.put("resourcePath", "/api/workforce/employees");
        ObjectNode cfg = inputs.putObject("config");
        cfg.putArray("columns");
        ObjectNode projection = cfg.putObject("columnProjection").put("source", "schema");
        projection.putArray("include").add("fullName");
        projection.putObject("overrides").putObject("fullName")
                .put("header", "Full Name")
                .put("visible", true)
                .put("order", 0);
        return w;
    }

    private ObjectNode chartWidget(String key) {
        ObjectNode w = MAPPER.createObjectNode().put("key", key);
        w.putObject("shell")
                .put("kind", "dashboard-card")
                .put("title", "Headcount by Unit")
                .put("icon", "monitoring")
                .put("showHeader", true);
        ObjectNode def = w.putObject("definition").put("id", "praxis-chart");
        ObjectNode inputs = def.putObject("inputs");
        inputs.put("resourcePath", "/api/workforce/stats");
        ObjectNode cfg = inputs.putObject("config");
        cfg.put("title", "Headcount by Unit").put("chartType", "bar");
        return w;
    }

    private ObjectNode analyticsPresentationWidget(String key) {
        ObjectNode w = MAPPER.createObjectNode().put("key", key);
        w.putObject("shell")
                .put("kind", "dashboard-card")
                .put("title", "Compensation Analytics")
                .put("icon", "insights")
                .put("showHeader", true);
        ObjectNode def = w.putObject("definition").put("id", "praxis-analytics-presentation");
        ObjectNode inputs = def.putObject("inputs");
        inputs.put("resourcePath", "/api/workforce/employees");
        inputs.put("preferredFamily", "chart");
        inputs.put("title", "Salary Bands");
        inputs.put("height", 320);
        ObjectNode analytics = inputs.putObject("analytics");
        analytics.putArray("groupBy").add("department");
        analytics.putObject("metric").put("field", "salary").put("aggregation", "avg");
        inputs.putObject("queryContext").put("resourcePath", "/api/workforce/employees");
        return w;
    }

    private ObjectNode listWidget(String key) {
        ObjectNode w = MAPPER.createObjectNode().put("key", key);
        ObjectNode def = w.putObject("definition").put("id", "praxis-list");
        def.putObject("inputs").putObject("config").putObject("dataSource").put("resourcePath", "/api/workforce/employees");
        return w;
    }

    private UiLayoutPageChildSource composedSource(String widgetKey, String widgetType) {
        return new UiLayoutPageChildSource(
                widgetKey,
                new UiLayoutPageChildSource.NativeSource(
                        "registered-native",
                        CorporatePageRuntimeSupport.COMPOSED_WIDGET_SOURCE_PREFIX + widgetType,
                        CorporatePageRuntimeSupport.COMPOSED_WIDGET_SOURCE_REVISION));
    }

    private UiLayoutLifecycleInvocation invocationFor(String actor, String unit, String contextVersion) {
        return new UiLayoutLifecycleInvocation(
                actor,
                "default",
                unit,
                "prod",
                contextVersion,
                new UiLayoutCompositionRegistration(PAGE_TARGET, List.of(PAGE_TARGET)));
    }

    private UiLayoutValidationContext validation(UiLayoutLifecycleOperation operation) {
        return validationFor(INVOCATION, operation);
    }

    private UiLayoutValidationContext validationFor(
            UiLayoutLifecycleInvocation invocation, UiLayoutLifecycleOperation operation) {
        return new UiLayoutValidationContext(
                UiLayoutValidationAttempt.start(invocation, operation, Duration.ofSeconds(30)),
                UiLayoutValidationPurpose.AUTHORING);
    }

    private record CapturesFixture(
            UUID draftId,
            UiLayoutDraftWorkspaceSeed.TargetSeed seed,
            UiLayoutPageChildCaptures captures,
            UiLayoutDraftWorkspaceDocument.TargetState state) {}
}
