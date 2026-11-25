package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.http.HttpStatus;

@Tag("unit")
class KpiGovernanceUserConfigGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String PAGE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String STRIP_WIDGET_KEY = "22222222-2222-4222-8222-222222222222";
    private static final String METRIC_CARD_ID = "33333333-3333-4333-8333-333333333331";
    private static final String FULL_CARD_ID = "33333333-3333-4333-8333-333333333332";
    private static final String FREE_CARD_ID = "33333333-3333-4333-8333-333333333333";

    @Test
    void mergesTenantAndMatchingUserLayersFromReleaseReaderAndExcludesOtherUsers()
            throws Exception {
        UiLayoutCompositionReleaseReader releaseReader = mock(UiLayoutCompositionReleaseReader.class);
        UiLayoutTarget pageTarget = new UiLayoutTarget("praxis-dynamic-page", PAGE_ID);
        ObjectNode tenantPatch = buildPublishedPageWithLocks();
        ObjectNode userOnePatch =
                (ObjectNode)
                        MAPPER.readTree(
                                """
                                {"config":{"presentation":{"title":"My Personalized Title"}}}
                                """);
        ObjectNode userTwoPatch =
                (ObjectNode)
                        MAPPER.readTree(
                                """
                                {"config":{"presentation":{"title":"Other User Title"}}}
                                """);

        UiLayoutCompositionSnapshotBuilder builder = new UiLayoutCompositionSnapshotBuilder();
        UiLayoutCompositionReleaseSnapshot snapshot =
                builder.snapshot(
                        pageTarget,
                        tenantPatch,
                        userOnePatch,
                        userTwoPatch);

        when(releaseReader.read("tenant-a", "prod", pageTarget)).thenReturn(snapshot);

        ReleaseReaderPublishedUiLayoutBaselineProvider provider =
                new ReleaseReaderPublishedUiLayoutBaselineProvider(releaseReader, MAPPER);

        Optional<JsonNode> resolved =
                provider.findActivePublishedDocument(
                        "tenant-a",
                        "user-1",
                        "praxis-dynamic-page",
                        "page:" + PAGE_ID,
                        "");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().at("/config/presentation/title").asText())
                .isEqualTo("My Personalized Title");
    }

    @Test
    void rejectsMetricLockedAndFullTamperingWhilePermittingVisualEditsAndUnlockedSiblings()
            throws Exception {
        ObjectNode publishedPage = buildPublishedPageWithLocks();
        PublishedUiLayoutBaselineProvider provider =
                (tenantId, userId, componentType, componentId, env) ->
                        Optional.of(publishedPage);
        KpiGovernanceUserConfigGuard guard = new KpiGovernanceUserConfigGuard(provider);

        // 1. Visual edit on metric-locked + editing free sibling succeeds
        ObjectNode safeUserOverride = buildRuntimePageFrom(publishedPage);
        ((ObjectNode)
                        safeUserOverride.at(
                                "/widgets/0/definition/inputs/config/cards/0/presentation"))
                .put("label", "Custom Visual Title")
                .put("tone", "success");
        ((ObjectNode)
                        safeUserOverride.at(
                                "/widgets/0/definition/inputs/config/cards/2/source/request"))
                .put("field", "contractType");

        assertThatCode(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        safeUserOverride))
                .doesNotThrowAnyException();

        // 2. Modifying source on metric-locked fails with 422 KPI_GOVERNANCE_LOCK_VIOLATION
        ObjectNode alteredMetricSource = safeUserOverride.deepCopy();
        ((ObjectNode)
                        alteredMetricSource.at(
                                "/widgets/0/definition/inputs/config/cards/0/source/request"))
                .put("field", "salary");
        assertThatThrownBy(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        alteredMetricSource))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .satisfies(
                        ex -> {
                            UiConfigWriteDeniedException denied =
                                    (UiConfigWriteDeniedException) ex;
                            assertThat(denied.getStatusCode())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(denied.getErrorCode())
                                    .isEqualTo(
                                            KpiGovernanceUserConfigGuard
                                                    .KPI_GOVERNANCE_LOCK_ERROR_CODE);
                        });

        // 3. Downgrading governance.lockLevel fails
        ObjectNode downgradedLock = safeUserOverride.deepCopy();
        ((ObjectNode)
                        downgradedLock.at(
                                "/widgets/0/definition/inputs/config/cards/0/governance"))
                .put("lockLevel", "none");
        assertThatThrownBy(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        downgradedLock))
                .isInstanceOf(UiConfigWriteDeniedException.class);

        // 4. Visual edit on full-locked card fails
        ObjectNode fullVisualEdit = safeUserOverride.deepCopy();
        ((ObjectNode)
                        fullVisualEdit.at(
                                "/widgets/0/definition/inputs/config/cards/1/presentation"))
                .put("label", "Unauthorized Label");
        assertThatThrownBy(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        fullVisualEdit))
                .isInstanceOf(UiConfigWriteDeniedException.class);

        // 5. Removing a locked card fails
        ObjectNode deletedCard = safeUserOverride.deepCopy();
        ((ArrayNode) deletedCard.at("/widgets/0/definition/inputs/config/cards")).remove(1);
        assertThatThrownBy(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        deletedCard))
                .isInstanceOf(UiConfigWriteDeniedException.class);
    }

    @Test
    void neutralizesStaleTamperingDuringGetResolutionForPageStripAndSingleCard() throws Exception {
        ObjectNode publishedPage = buildPublishedPageWithLocks();
        PublishedUiLayoutBaselineProvider pageProvider =
                (tenantId, userId, componentType, componentId, env) -> Optional.of(publishedPage);
        KpiGovernanceUserConfigGuard pageGuard = new KpiGovernanceUserConfigGuard(pageProvider);

        ObjectNode tamperedPersisted = buildRuntimePageFrom(publishedPage);
        // Safe visual tweak on metric-locked card + tampered source + tampered full card
        ((ObjectNode)
                        tamperedPersisted.at(
                                "/widgets/0/definition/inputs/config/cards/0/presentation"))
                .put("label", "Kept Visual Title");
        ((ObjectNode)
                        tamperedPersisted.at(
                                "/widgets/0/definition/inputs/config/cards/0/source/request"))
                .put("field", "tampered");
        ((ObjectNode)
                        tamperedPersisted.at(
                                "/widgets/0/definition/inputs/config/cards/0/governance"))
                .put("lockLevel", "none");
        ((ArrayNode) tamperedPersisted.at("/widgets/0/definition/inputs/config/cards")).remove(1);

        JsonNode reconciled =
                pageGuard.reconcileResolvedPayload(
                        "tenant-a", "user-1", "praxis-dynamic-page", PAGE_ID, "prod", tamperedPersisted);

        assertThat(reconciled.at("/widgets/0/definition/inputs/config/cards/0/presentation/label").asText())
                .isEqualTo("Kept Visual Title");
        assertThat(reconciled.at("/widgets/0/definition/inputs/config/cards/0/source/request/field").asText())
                .isEqualTo("status");
        assertThat(reconciled.at("/widgets/0/definition/inputs/config/cards/0/governance/lockLevel").asText())
                .isEqualTo("metric-locked");
        assertThat(reconciled.at("/widgets/0/definition/inputs/config/cards")).hasSize(3);
        assertThat(reconciled.at("/widgets/0/definition/inputs/config/cards/2/id").asText())
                .isEqualTo(FULL_CARD_ID);

        // Standalone praxis-kpi-card enforcement
        ObjectNode singleCardBaseline =
                (ObjectNode) publishedPage.at("/config/definition/widgets/0/definition/inputs/config/cards/1");
        KpiGovernanceUserConfigGuard cardGuard =
                new KpiGovernanceUserConfigGuard(
                        (t, u, ct, cid, e) -> Optional.of(singleCardBaseline));
        ObjectNode tamperedCard = singleCardBaseline.deepCopy();
        tamperedCard.withObject("presentation").put("label", "Tampered Full Card");
        assertThatThrownBy(
                        () ->
                                cardGuard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-kpi-card",
                                        FULL_CARD_ID,
                                        "prod",
                                        tamperedCard))
                .isInstanceOf(UiConfigWriteDeniedException.class);
        JsonNode reconciledCard =
                cardGuard.reconcileResolvedPayload(
                        "tenant-a", "user-1", "praxis-kpi-card", FULL_CARD_ID, "prod", tamperedCard);
        assertThat(reconciledCard.at("/presentation/label").asText())
                .isEqualTo("Turnover Rate");
    }

    @Test
    void rejectsDuplicateCardIdsForLockedStripCardsAndDeduplicatesOnReconcile() {
        ObjectNode publishedPage = buildPublishedPageWithLocks();
        PublishedUiLayoutBaselineProvider provider =
                (tenantId, userId, componentType, componentId, env) -> Optional.of(publishedPage);
        KpiGovernanceUserConfigGuard guard = new KpiGovernanceUserConfigGuard(provider);

        ObjectNode duplicatePayload = buildRuntimePageFrom(publishedPage);
        ArrayNode cards = (ArrayNode) duplicatePayload.at("/widgets/0/definition/inputs/config/cards");
        ObjectNode spoofedDuplicate = makeCard(METRIC_CARD_ID, "Spoofed Duplicate", "salary", "none");
        cards.insert(0, spoofedDuplicate);

        assertThatThrownBy(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        duplicatePayload))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .satisfies(
                        ex ->
                                assertThat(((UiConfigWriteDeniedException) ex).getErrorCode())
                                        .isEqualTo(
                                                KpiGovernanceUserConfigGuard
                                                        .KPI_GOVERNANCE_LOCK_ERROR_CODE));

        JsonNode reconciled =
                guard.reconcileResolvedPayload(
                        "tenant-a", "user-1", "praxis-dynamic-page", PAGE_ID, "prod", duplicatePayload);
        ArrayNode reconciledCards = (ArrayNode) reconciled.at("/widgets/0/definition/inputs/config/cards");
        assertThat(reconciledCards).hasSize(3);
        assertThat(reconciledCards.get(0).at("/source/request/field").asText()).isEqualTo("status");
        assertThat(reconciledCards.get(0).at("/governance/lockLevel").asText())
                .isEqualTo("metric-locked");
    }

    @Test
    void enforcesUnconditionalSsrfAndQuotasWithoutBaselineAndOnMetricLockedInteractions() {
        KpiGovernanceUserConfigGuard guardWithoutBaseline = new KpiGovernanceUserConfigGuard(null);

        // SSRF in source.resourcePath rejected without baseline
        ObjectNode ssrfSourcePage = buildPublishedPageWithLocks().at("/config/definition").deepCopy();
        ((ObjectNode) ssrfSourcePage.at("/widgets/0/definition/inputs/config/cards/2/source"))
                .put("resourcePath", "https://evil.example/payroll");
        assertThatThrownBy(
                        () ->
                                guardWithoutBaseline.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        ssrfSourcePage))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .satisfies(
                        ex ->
                                assertThat(((UiConfigWriteDeniedException) ex).getErrorCode())
                                        .isEqualTo(
                                                KpiGovernanceUserConfigGuard
                                                        .KPI_STRUCTURE_VIOLATION_ERROR_CODE));

        // Strip with >12 cards rejected without baseline
        ObjectNode oversizedStripPage = buildPublishedPageWithLocks().at("/config/definition").deepCopy();
        ArrayNode cards =
                (ArrayNode) oversizedStripPage.at("/widgets/0/definition/inputs/config/cards");
        for (int i = 0; i < 10; i++) {
            cards.add(makeCard("33333333-3333-4333-8333-3333333333" + (40 + i), "Extra " + i, "f_" + i, "none"));
        }
        assertThat(cards).hasSize(13);
        assertThatThrownBy(
                        () ->
                                guardWithoutBaseline.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        oversizedStripPage))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .hasMessageContaining("12");

        // Metric-locked card interaction drillDown/crossFilter paths still pass anti-SSRF check
        ObjectNode publishedPage = buildPublishedPageWithLocks();
        KpiGovernanceUserConfigGuard governedGuard =
                new KpiGovernanceUserConfigGuard((t, u, ct, cid, e) -> Optional.of(publishedPage));
        ObjectNode ssrfDrillDown = buildRuntimePageFrom(publishedPage);
        ((ObjectNode) ssrfDrillDown.at("/widgets/0/definition/inputs/config/cards/0"))
                .putObject("interaction")
                .putObject("drillDown")
                .put("resourcePath", "workforce/../payroll/secret");
        assertThatThrownBy(
                        () ->
                                governedGuard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        ssrfDrillDown))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .satisfies(
                        ex ->
                                assertThat(((UiConfigWriteDeniedException) ex).getErrorCode())
                                        .isEqualTo(
                                                KpiGovernanceUserConfigGuard
                                                        .KPI_STRUCTURE_VIOLATION_ERROR_CODE));
    }

    @Test
    void enforcesAndReconcilesLockedKpiCardsInsideNestedTabsAndPanelsContainers() {
        ObjectNode nestedPublished = MAPPER.createObjectNode();
        nestedPublished.put("kind", "praxis.dynamic-page.editor");
        nestedPublished.put("version", 1);
        ObjectNode config = nestedPublished.putObject("config");
        ArrayNode widgets = config.putObject("definition").putArray("widgets");
        ObjectNode tabsWidget = widgets.addObject();
        tabsWidget.put("key", "tabs-shell");
        ObjectNode tabsDef = tabsWidget.putObject("definition");
        tabsDef.put("id", "praxis-tabs");
        ArrayNode tabs = tabsDef.putObject("inputs").putObject("config").putArray("tabs");
        ObjectNode firstTab = tabs.addObject();
        firstTab.put("id", "overview-tab");
        ArrayNode nestedWidgets = firstTab.putArray("widgets");
        ObjectNode nestedStrip = nestedWidgets.addObject();
        nestedStrip.put("id", "praxis-kpi-strip");
        nestedStrip.put("childWidgetKey", "nested-strip-1");
        ArrayNode nestedCards =
                nestedStrip.putObject("inputs").putObject("config").putArray("cards");
        nestedCards.add(makeCard(METRIC_CARD_ID, "Nested Locked KPI", "status", "metric-locked"));
        config.putObject("presentation").put("title", "Nested Cockpit").put("icon", "analytics");
        config.putArray("navigation");

        KpiGovernanceUserConfigGuard guard =
                new KpiGovernanceUserConfigGuard((t, u, ct, cid, e) -> Optional.of(nestedPublished));

        ObjectNode tamperedRuntime = MAPPER.createObjectNode();
        tamperedRuntime.set("widgets", nestedPublished.at("/config/definition/widgets").deepCopy());
        ((ObjectNode)
                        tamperedRuntime.at(
                                "/widgets/0/definition/inputs/config/tabs/0/widgets/0/inputs/config/cards/0/source/request"))
                .put("field", "tamperedField");

        assertThatThrownBy(
                        () ->
                                guard.assertWriteAllowed(
                                        "tenant-a",
                                        "user-1",
                                        "praxis-dynamic-page",
                                        PAGE_ID,
                                        "prod",
                                        tamperedRuntime))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .satisfies(
                        ex ->
                                assertThat(((UiConfigWriteDeniedException) ex).getErrorCode())
                                        .isEqualTo(
                                                KpiGovernanceUserConfigGuard
                                                        .KPI_GOVERNANCE_LOCK_ERROR_CODE));

        JsonNode reconciled =
                guard.reconcileResolvedPayload(
                        "tenant-a", "user-1", "praxis-dynamic-page", PAGE_ID, "prod", tamperedRuntime);
        assertThat(
                        reconciled
                                .at(
                                        "/widgets/0/definition/inputs/config/tabs/0/widgets/0/inputs/config/cards/0/source/request/field")
                                .asText())
                .isEqualTo("status");
    }

    @Test
    void isolatesOrganizationAndProfileBaselineLayersWhenAudienceIsMissingOrMismatching()
            throws Exception {
        UiLayoutCompositionReleaseReader releaseReader = mock(UiLayoutCompositionReleaseReader.class);
        UiLayoutTarget pageTarget = new UiLayoutTarget("praxis-dynamic-page", PAGE_ID);
        ObjectNode tenantPatch = buildPublishedPageWithLocks();
        ObjectNode hrProfilePatch =
                (ObjectNode)
                        MAPPER.readTree(
                                """
                                {"config":{"presentation":{"title":"HR Profile View"}}}
                                """);
        ObjectNode financeProfilePatch =
                (ObjectNode)
                        MAPPER.readTree(
                                """
                                {"config":{"presentation":{"title":"Finance Profile View"}}}
                                """);

        UiLayoutCompositionReleaseSnapshot snapshot =
                new UiLayoutCompositionReleaseSnapshot(
                        "rel-audience-1",
                        List.of(
                                new UiLayoutCompositionContribution(
                                        0,
                                        pageTarget,
                                        "tenant",
                                        "a-tenant",
                                        "rev-0",
                                        "hash-0",
                                        new UiLayoutResolutionCandidate(
                                                pageTarget,
                                                "rev-0",
                                                "hash-0",
                                                UiLayoutResolutionCandidate.LayerClass.TENANT,
                                                (short) 0,
                                                new UiLayoutAudienceSelector(
                                                        "tenant-a", null, null, null, null, null),
                                                tenantPatch,
                                                "tenant")),
                                new UiLayoutCompositionContribution(
                                        1,
                                        pageTarget,
                                        "hr-profile",
                                        "a-hr",
                                        "rev-1",
                                        "hash-1",
                                        new UiLayoutResolutionCandidate(
                                                pageTarget,
                                                "rev-1",
                                                "hash-1",
                                                UiLayoutResolutionCandidate.LayerClass.PROFILE,
                                                (short) 10,
                                                new UiLayoutAudienceSelector(
                                                        "tenant-a", null, null, null, "HR_ANALYST", null),
                                                hrProfilePatch,
                                                "hr")),
                                new UiLayoutCompositionContribution(
                                        2,
                                        pageTarget,
                                        "fin-profile",
                                        "a-fin",
                                        "rev-2",
                                        "hash-2",
                                        new UiLayoutResolutionCandidate(
                                                pageTarget,
                                                "rev-2",
                                                "hash-2",
                                                UiLayoutResolutionCandidate.LayerClass.PROFILE,
                                                (short) 20,
                                                new UiLayoutAudienceSelector(
                                                        "tenant-a", null, null, null, "FINANCE_DIRECTOR", null),
                                                financeProfilePatch,
                                                "fin"))));

        when(releaseReader.read("tenant-a", "prod", pageTarget)).thenReturn(snapshot);

        // Without EffectiveUiAudienceProvider, must NOT merge HR/Finance profile layers blindly
        ReleaseReaderPublishedUiLayoutBaselineProvider noAudienceProvider =
                new ReleaseReaderPublishedUiLayoutBaselineProvider(releaseReader, MAPPER);
        Optional<PublishedUiLayoutBaselineProvider.PublishedBaseline> tenantOnly =
                noAudienceProvider.findActivePublishedBaseline(
                        "tenant-a", "user-1", "praxis-dynamic-page", PAGE_ID, "prod");
        assertThat(tenantOnly).isPresent();
        assertThat(tenantOnly.get().document().at("/config/presentation/title").asText())
                .isEqualTo("Executive Cockpit");
        assertThat(tenantOnly.get().versionToken()).isNotBlank();

        // With verified EffectiveUiAudienceProvider, ONLY the caller's profile layer applies
        EffectiveUiAudienceProvider hrAudience =
                (principal, ctxVersion) ->
                        new EffectiveUiAudience(
                                "tenant-a",
                                "prod",
                                "user-1",
                                null,
                                null,
                                "HR_ANALYST",
                                java.util.Set.of(),
                                "ctx-1");
        ReleaseReaderPublishedUiLayoutBaselineProvider withAudience =
                new ReleaseReaderPublishedUiLayoutBaselineProvider(
                        releaseReader,
                        MAPPER,
                        hrAudience,
                        () ->
                                new ReleaseReaderPublishedUiLayoutBaselineProvider.RequestAuthContext(
                                        () -> "user-1", "ctx-1"));
        Optional<PublishedUiLayoutBaselineProvider.PublishedBaseline> hrResolved =
                withAudience.findActivePublishedBaseline(
                        "tenant-a", "user-1", "praxis-dynamic-page", PAGE_ID, "prod");
        assertThat(hrResolved).isPresent();
        assertThat(hrResolved.get().document().at("/config/presentation/title").asText())
                .isEqualTo("HR Profile View");
        assertThat(hrResolved.get().versionToken()).isNotEqualTo(tenantOnly.get().versionToken());
    }

    private static ObjectNode buildPublishedPageWithLocks() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "praxis.dynamic-page.editor");
        root.put("version", 1);
        ObjectNode config = root.putObject("config");
        ArrayNode widgets = config.putObject("definition").putArray("widgets");
        ObjectNode stripWidget = widgets.addObject();
        stripWidget.put("key", STRIP_WIDGET_KEY);
        ObjectNode def = stripWidget.putObject("definition");
        def.put("id", "praxis-kpi-strip");
        ObjectNode stripCfg = def.putObject("inputs").putObject("config");
        stripCfg.put("schemaVersion", 1);
        ArrayNode cards = stripCfg.putArray("cards");
        cards.add(makeCard(METRIC_CARD_ID, "Active Ratio", "status", "metric-locked"));
        cards.add(makeCard(FULL_CARD_ID, "Turnover Rate", "terminationReason", "full"));
        cards.add(makeCard(FREE_CARD_ID, "Distribution", "departmentCode", "none"));
        config.putObject("presentation").put("title", "Executive Cockpit").put("icon", "analytics");
        config.putArray("navigation");
        return root;
    }

    private static ObjectNode buildRuntimePageFrom(ObjectNode published) {
        ObjectNode runtime = MAPPER.createObjectNode();
        runtime.set("widgets", published.at("/config/definition/widgets").deepCopy());
        return runtime;
    }

    private static ObjectNode makeCard(
            String id, String label, String field, String lockLevel) {
        ObjectNode card = MAPPER.createObjectNode();
        card.put("schemaVersion", 1);
        card.put("id", id);
        card.putObject("presentation").put("label", label).put("format", "percent");
        ObjectNode source = card.putObject("source");
        source.put("kind", "stats-query");
        source.put("resourcePath", "workforce/assignments");
        source.put("operation", "group-by");
        source.putObject("request").put("field", field);
        ObjectNode derivation = card.putObject("derivation");
        derivation.put("kind", "ratio");
        derivation.putArray("numeratorKeys").add("ACTIVE");
        derivation.put("denominatorMode", "population-total");
        card.putObject("governance").put("lockLevel", lockLevel);
        return card;
    }

    private static final class UiLayoutCompositionSnapshotBuilder {
        UiLayoutCompositionReleaseSnapshot snapshot(
                UiLayoutTarget target,
                ObjectNode tenantPatch,
                ObjectNode userOnePatch,
                ObjectNode userTwoPatch) {
            return new UiLayoutCompositionReleaseSnapshot(
                    "rel-1",
                    List.of(
                            new UiLayoutCompositionContribution(
                                    0,
                                    target,
                                    "tenant",
                                    "assign-0",
                                    "rev-0",
                                    "hash-0",
                                    new UiLayoutResolutionCandidate(
                                            target,
                                            "rev-0",
                                            "hash-0",
                                            UiLayoutResolutionCandidate.LayerClass.TENANT,
                                            (short) 0,
                                            new UiLayoutAudienceSelector(
                                                    "tenant-a", null, null, null, null, null),
                                            tenantPatch,
                                            "tenant")),
                            new UiLayoutCompositionContribution(
                                    1,
                                    target,
                                    "u1",
                                    "assign-1",
                                    "rev-1",
                                    "hash-1",
                                    new UiLayoutResolutionCandidate(
                                            target,
                                            "rev-1",
                                            "hash-1",
                                            UiLayoutResolutionCandidate.LayerClass.USER,
                                            (short) 10,
                                            new UiLayoutAudienceSelector(
                                                    "tenant-a", null, null, null, null, "user-1"),
                                            userOnePatch,
                                            "u1")),
                            new UiLayoutCompositionContribution(
                                    2,
                                    target,
                                    "u2",
                                    "assign-2",
                                    "rev-2",
                                    "hash-2",
                                    new UiLayoutResolutionCandidate(
                                            target,
                                            "rev-2",
                                            "hash-2",
                                            UiLayoutResolutionCandidate.LayerClass.USER,
                                            (short) 10,
                                            new UiLayoutAudienceSelector(
                                                    "tenant-a", null, null, null, null, "user-2"),
                                            userTwoPatch,
                                            "u2"))));
        }
    }
}
