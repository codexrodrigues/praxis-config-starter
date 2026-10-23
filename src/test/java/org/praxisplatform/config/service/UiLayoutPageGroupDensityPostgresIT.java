package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("neon")
@EnabledIfSystemProperty(named="praxis.page.p3a.neon.it", matches="true")
class UiLayoutPageGroupDensityPostgresIT extends UiLayoutPageGroupPostgresFixture {
  @Test
  void thousandCorrelatedPagesAreScannedCompletelyWithALastPageSentinel() throws Exception {
    try (var host = new UiLayoutPageGroupHostFixture("p3a-density-" + UUID.randomUUID())) {
      UUID kept = UUID.randomUUID(), removed = UUID.randomUUID();
      UUID initial = groupRelease(host, kept, removed);
      var head = publish(host, initial, null);
      UUID valid = groupRelease(host, kept, removed), invalid = groupRelease(host, kept);
      var prototypePage = host.pages.create(host.alice, host.alice.version(),
          new PageCreationCommand(UUID.randomUUID(), new PageSourceReference.Native("fixture:page", "r1"), "Seed prototype")).receipt();
      var prototype = new PageCreationCodec().read(jdbc.queryForObject("SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, prototypePage.pageId()));
      var seed = new UiLayoutPageGroupDenseFixture(host, prototype, kept, removed, 1000);
      seed.insert();
      String scopedHeads = """
          FROM ui_page_catalog c JOIN ui_layout_release_head h ON h.tenant_id=c.tenant_id AND h.environment=c.environment
            AND h.root_component_type=c.root_component_type AND h.root_component_id=c.root_component_id
          WHERE c.tenant_id=? AND c.environment=? AND c.application_id=? AND c.route_namespace=? AND h.active_release_id IS NOT NULL
          """;
      Object[] scope = {host.scope.tenant(), host.scope.environment(), host.scope.application(), host.scope.namespace()};
      assertThat(jdbc.queryForObject("SELECT count(*) " + scopedHeads, Long.class, scope)).isEqualTo(1000L);
      assertThat(jdbc.queryForObject("SELECT c.page_id " + scopedHeads + " ORDER BY c.page_id DESC LIMIT 1", UUID.class, scope))
          .isEqualTo(seed.pageIds().getLast());
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_head WHERE tenant_id=? AND root_component_id=?", Long.class,
          host.scope.tenant(), prototypePage.target().componentId())).isZero();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      var positive = measure(host, valid, head.headEtag(), "all-1000-valid", seed.workspaceEncodedInputBytes);
      assertThat(positive.failure).isNull();
      assertLoadedPages(positive, seed.pageIds(), 2);
      assertThat(positive.pageQueries).isEqualTo(18); // Eight nonempty + one terminal query, twice.
      assertThat(positive.peakManaged).isLessThanOrEqualTo(positive.baselineManaged + 128 * 6 + 12);
      var before = jdbc.queryForMap("SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND root_component_id=?",
          host.scope.tenant(), host.groups.componentId());
      long events = jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?", Long.class, host.scope.tenant());
      var negative = measure(host, invalid, positive.receipt.headEtag(), "last-page-blocks", seed.workspaceEncodedInputBytes);
      assertThat(negative.failure).isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
      assertLoadedPages(negative, seed.pageIds(), 1);
      assertThat(negative.pageQueries).isEqualTo(8); // Sentinel in final nonempty batch prevents the terminal query.
      assertThat(negative.peakManaged).isLessThanOrEqualTo(negative.baselineManaged + 128 * 6 + 12);
      assertThat(jdbc.queryForMap("SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND root_component_id=?",
          host.scope.tenant(), host.groups.componentId())).isEqualTo(before);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?", Long.class, host.scope.tenant())).isEqualTo(events);
    } finally { probe = null; }
  }

  private void assertLoadedPages(Measurement measurement, List<UUID> expected, int passes) {
    assertThat(measurement.loadedPages).hasSize(passes);
    measurement.loadedPages.values().forEach(loaded -> {
      assertThat(loaded.keySet()).containsExactlyInAnyOrderElementsOf(expected);
      assertThat(loaded.values()).allMatch(count -> count == 1);
    });
  }

  private Measurement measure(UiLayoutPageGroupHostFixture host, UUID release, String etag, String name, long workspaceBytes) {
    // Fresh state is captured outside the timed operation, including on a deadline failure.
    var headBefore = headState(host);
    long eventsBefore = eventCount(host);
    var queries = new AtomicInteger();
    var peak = new AtomicInteger();
    var baseline = new AtomicInteger(-1);
    var nextBatchManaged = new AtomicInteger();
    var currentPass = new AtomicInteger();
    var currentBatchBytes = new java.util.concurrent.atomic.AtomicLong();
    var maxBatchBytes = new java.util.concurrent.atomic.AtomicLong();
    var currentBatchPatchBytes = new java.util.concurrent.atomic.AtomicLong();
    var maxBatchPatchBytes = new java.util.concurrent.atomic.AtomicLong();
    Map<Integer, Long> loadedPatchBytes = new LinkedHashMap<>();
    Map<Integer, Map<UUID, Integer>> loadedPages = new LinkedHashMap<>();
    Map<Integer, Long> loadedWorkspaceBytes = new LinkedHashMap<>();
    probe = new SqlProbe() {
      @Override public void managed(int count) { peak.accumulateAndGet(count, Math::max); }
      @Override public void loaded(Object entity) {
        if (currentPass.get() > 0 && entity instanceof org.praxisplatform.config.domain.UiLayoutRevision revision) {
          long bytes = revision.getPatchDocument().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          loadedPatchBytes.merge(currentPass.get(), bytes, Long::sum);
          maxBatchPatchBytes.accumulateAndGet(currentBatchPatchBytes.addAndGet(bytes), Math::max);
        }
        if (!(entity instanceof org.praxisplatform.config.domain.UiLayoutDraft draft)
            || !draft.getRootComponentType().equals("praxis-dynamic-page")
            || !draft.getTenantId().equals(host.scope.tenant())) return;
        int pass = currentPass.get();
        assertThat(pass).isPositive();
        loadedPages.computeIfAbsent(pass, ignored -> new LinkedHashMap<>())
            .merge(UUID.fromString(draft.getRootComponentId()), 1, Integer::sum);
        long bytes = draft.getDraftDocument().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        loadedWorkspaceBytes.merge(pass, bytes, Long::sum);
        maxBatchBytes.accumulateAndGet(currentBatchBytes.addAndGet(bytes), Math::max);
      }
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
        if (!sql.contains("SELECT c.page_id, c.root_component_type")) return;
        if (!sql.contains("c.page_id >")) currentPass.incrementAndGet();
        currentBatchBytes.set(0);
        currentBatchPatchBytes.set(0);
        var holder = (org.springframework.orm.jpa.EntityManagerHolder) TransactionSynchronizationManager.getResource(factory);
        int count = holder.getEntityManager().unwrap(org.hibernate.engine.spi.SessionImplementor.class)
            .getPersistenceContextInternal().getNumberOfManagedEntities();
        baseline.compareAndSet(-1, count);
        nextBatchManaged.set(count);
        queries.incrementAndGet();
      }
    };
    long start = System.nanoTime(), sql = sqlCalls.get(), io = sqlNanos.get();
    long isolation = isolationCalls.get(), isolationIo = isolationNanos.get();
    final UiLayoutReleaseHeadReceipt[] receipt = new UiLayoutReleaseHeadReceipt[1];
    Throwable failure = catchThrowable(() -> receipt[0] = publish(host, release, etag));
    long wallMs = (System.nanoTime() - start) / 1_000_000;
    long statements = sqlCalls.get() - sql, jdbcMs = (sqlNanos.get() - io) / 1_000_000;
    long isolationCount = isolationCalls.get() - isolation, isolationMs = (isolationNanos.get() - isolationIo) / 1_000_000;
    probe = null;
    if (failure != null) {
      assertThat(headState(host)).describedAs("Failed scan preserves active release, ETag and row version").isEqualTo(headBefore);
      assertThat(eventCount(host)).describedAs("Failed scan leaves no audit event").isEqualTo(eventsBefore);
    }
    var identitiesPerPass = new LinkedHashMap<Integer, Integer>();
    loadedPages.forEach((pass, identities) -> identitiesPerPass.put(pass, identities.size()));
    System.out.println("P3-A density case=" + name + " catalogPages=1000 batchSize=" + UiLayoutPageGroupHeadStore.PAGE_BATCH_SIZE + " budgetMs=" + host.budget.toMillis()
        + " wallMs=" + wallMs + " SQL=" + statements + " jdbcMs=" + jdbcMs
        + " isolationCalls=" + isolationCount + " isolationMs=" + isolationMs
        + " attemptedPageQueries=" + queries.get() + " loadedWorkspaceIdentitiesPerPass=" + identitiesPerPass
        + " loadedWorkspaceBytesPerPass=" + loadedWorkspaceBytes + " maxBatchLoadedWorkspaceTextBytes=" + maxBatchBytes.get()
        + " revisionPatchTextBytesLoadedDuringPass=" + loadedPatchBytes + " maxBatchLoadedPatchTextBytes=" + maxBatchPatchBytes.get()
        + " managedBaseline=" + baseline.get() + " managedPeak=" + peak.get() + " managedAtLastBatchStart=" + nextBatchManaged.get()
        + " knownWorkspaceEncodedInputBytesPerFullPass=" + workspaceBytes + " freshRollbackChecked=" + (failure != null)
        + " outcome=" + (failure == null ? "accepted" : failure));
    return new Measurement(receipt[0], failure, queries.get(), baseline.get(), peak.get(), loadedPages);
  }

  private Map<String, Object> headState(UiLayoutPageGroupHostFixture host) {
    return jdbc.queryForMap("SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND environment=? AND root_component_id=?",
        host.scope.tenant(), host.scope.environment(), host.groups.componentId());
  }

  private long eventCount(UiLayoutPageGroupHostFixture host) {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=? AND environment=?", Long.class,
        host.scope.tenant(), host.scope.environment());
  }

  private record Measurement(UiLayoutReleaseHeadReceipt receipt, Throwable failure, int pageQueries,
      int baselineManaged, int peakManaged, Map<Integer, Map<UUID, Integer>> loadedPages) {}

  private UiLayoutReleaseHeadReceipt publish(UiLayoutPageGroupHostFixture host, UUID release, String etag) {
    return host.commands.publish(host.alice, host.alice.version(), host.groups,
        etag == null ? UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish() : UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag), release, "Density proof");
  }

  private UUID groupRelease(UiLayoutPageGroupHostFixture host, UUID... groups) {
    var draft = host.commands.createDraft(host.alice, host.alice.version(), host.groups, UUID.randomUUID().toString());
    UUID draftId = UUID.fromString(draft.draft().draftRef());
    var revision = host.commands.createRevision(host.alice, host.alice.version(), host.groups, draftId, draft.draft().draftEtag(),
        new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(), host.groups, UiLayoutPageGroupHostFixture.groupsDocument(groups).toString(), "Dense groups"));
    var assigned = host.commands.createAssignment(host.alice, host.alice.version(), host.groups, draftId, revision.nextDraft().draft().draftEtag(),
        new UiLayoutLifecycleCommandService.AssignmentInput(UUID.randomUUID(), host.groups, "TENANT", new UiLayoutAudienceSelectorCommand(host.scope.tenant(),null,null,null,null,null), (short)0, "Dense groups"));
    var frozen = host.commands.freezeRelease(host.alice, host.alice.version(), host.groups, draftId, assigned.nextDraft().draft().draftEtag(), UUID.randomUUID());
    UUID release = UUID.fromString(frozen.release().releaseRef());
    var submitted = host.commands.submit(host.alice, host.alice.version(), host.groups, release, frozen.review().reviewEtag());
    host.commands.approve(host.alice, host.alice.version(), host.groups, release, submitted.reviewEtag(), "Dense proof");
    return release;
  }
}
