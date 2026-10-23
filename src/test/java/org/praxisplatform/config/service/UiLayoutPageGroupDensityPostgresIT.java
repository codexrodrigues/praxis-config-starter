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
      var positive = measure(host, valid, head.headEtag(), "all-1000-valid", seed.workspaceStoredBytes);
      assertThat(positive.failure).isNull();
      assertThat(positive.pageQueries).isEqualTo(66); // 32 nonempty + one terminal query, twice.
      assertThat(positive.peakManaged).isLessThanOrEqualTo(positive.baselineManaged + 32 * 6 + 12);
      var before = jdbc.queryForMap("SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND root_component_id=?",
          host.scope.tenant(), host.groups.componentId());
      long events = jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?", Long.class, host.scope.tenant());
      var negative = measure(host, invalid, positive.receipt.headEtag(), "last-page-blocks", seed.workspaceStoredBytes);
      assertThat(negative.failure).isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
      assertThat(negative.pageQueries).isEqualTo(32); // Sentinel in final nonempty batch prevents the terminal query.
      assertThat(negative.peakManaged).isLessThanOrEqualTo(negative.baselineManaged + 32 * 6 + 12);
      assertThat(jdbc.queryForMap("SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND root_component_id=?",
          host.scope.tenant(), host.groups.componentId())).isEqualTo(before);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?", Long.class, host.scope.tenant())).isEqualTo(events);
    } finally { probe = null; }
  }

  private Measurement measure(UiLayoutPageGroupHostFixture host, UUID release, String etag, String name, long workspaceBytes) {
    var queries = new AtomicInteger(); var peak = new AtomicInteger(); var baseline = new AtomicInteger(-1); var postBatch = new AtomicInteger();
    probe = new SqlProbe() {
      @Override public void managed(int count) { peak.accumulateAndGet(count, Math::max); }
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
        if (!sql.contains("SELECT c.page_id, c.root_component_type")) return;
        var holder = (org.springframework.orm.jpa.EntityManagerHolder) TransactionSynchronizationManager.getResource(factory);
        int count = holder.getEntityManager().unwrap(org.hibernate.engine.spi.SessionImplementor.class).getPersistenceContextInternal().getNumberOfManagedEntities();
        baseline.compareAndSet(-1, count); postBatch.set(count); queries.incrementAndGet();
      }
    };
    long start = System.nanoTime(), sql = sqlCalls.get(), io = sqlNanos.get(), isolation = isolationCalls.get(), isolationIo = isolationNanos.get();
    final UiLayoutReleaseHeadReceipt[] receipt = new UiLayoutReleaseHeadReceipt[1];
    Throwable failure = catchThrowable(() -> receipt[0] = publish(host, release, etag));
    probe = null;
    System.out.println("P3-A density case=" + name + " pages=1000 budgetMs=" + host.budget.toMillis()
        + " wallMs=" + (System.nanoTime()-start)/1_000_000 + " SQL=" + (sqlCalls.get()-sql) + " jdbcMs=" + (sqlNanos.get()-io)/1_000_000
        + " isolationCalls=" + (isolationCalls.get()-isolation) + " isolationMs=" + (isolationNanos.get()-isolationIo)/1_000_000
        + " pageQueries=" + queries.get() + " managedBaseline=" + baseline.get() + " managedPeak=" + peak.get() + " managedPostBatch=" + postBatch.get()
        + " workspaceStoredBytesPerFullPass=" + workspaceBytes + " outcome=" + (failure == null ? "accepted" : failure.getClass().getSimpleName()));
    return new Measurement(receipt[0], failure, queries.get(), baseline.get(), peak.get());
  }

  private record Measurement(UiLayoutReleaseHeadReceipt receipt, Throwable failure, int pageQueries, int baselineManaged, int peakManaged) {}

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
