package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Capacity diagnosis only: a reported deadline denial is not a successful capacity gate. */
@Tag("neon")
@EnabledIfSystemProperty(named = "praxis.page.p3b.neon.it", matches = "true")
class PageRuntimeDensityPostgresIT extends PageRuntimePostgresFixture {
  @Test void diagnoseThirtyTwoAuthorizedPagesBeforeLargerCatalogs() throws Exception { diagnose(32); }
  @Test void diagnoseOneHundredTwentyNineAuthorizedPagesAcrossBatchBoundary() throws Exception { diagnose(129); }

  @Test void demonstrateOneThousandAuthorizedPagesWithinExistingBudget() throws Exception { diagnose(1000, true); }

  @Test void mostlyPrivateCatalogDoesNotStopBeforeAuthorizedLastBatchOrLoseGlobalOrder() throws Exception {
    try (var host = new PageRuntimeHostFixture("p3b-private-density-" + UUID.randomUUID())) {
      UUID group = UUID.randomUUID();
      publishGroup(host, group);
      var prototypePage = host.pages.create(host.alice, "c1", new PageCreationCommand(UUID.randomUUID(),
          new PageSourceReference.Native("fixture:page", "r1"), "Private density prototype")).receipt();
      var prototype = new PageCreationCodec().read(jdbc.queryForObject(
          "SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, prototypePage.pageId()));
      var seed = new PageRuntimeDenseFixture(host, prototype, group, group, 129);
      seed.insert();
      var visible = Set.of(seed.pageIds().getFirst().toString(), seed.pageIds().getLast().toString());
      host.grant = (operation, invocation) -> {
        if (operation == UiLayoutLifecycleOperation.READ_HEAD
            && "praxis-dynamic-page".equals(invocation.rootTarget().componentType())
            && !visible.contains(invocation.rootTarget().componentId()))
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Private density fixture");
      };
      var before = headState(host);
      long events = eventCount(host);
      var loaded = new LinkedHashMap<UUID, Integer>();
      var batches = new AtomicInteger();
      probe = new SqlProbe() {
        @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
          if (category(sql).equals("weights")) batches.incrementAndGet();
        }
        @Override public void loaded(Object entity) {
          if (entity instanceof org.praxisplatform.config.domain.UiLayoutDraft draft
              && "praxis-dynamic-page".equals(draft.getRootComponentType()))
            loaded.merge(UUID.fromString(draft.getRootComponentId()), 1, Integer::sum);
        }
      };
      var request = new EnterpriseRuntimeContextRequest(host.alice, null, null, null);
      EnterpriseRuntimeNavigationResponse first;
      EnterpriseRuntimeNavigationResponse second;
      try {
        first = host.runtime.navigation(request, "c1", group.toString(), null, 1);
        assertThat(first.nodes()).hasSize(1);
        assertThat(first.nodes().getFirst().id()).startsWith(seed.pageIds().getLast() + ":");
        assertThat(first.nextCursor()).isNotNull();
        second = host.runtime.navigation(request, "c1", group.toString(), first.nextCursor(), 1);
        assertThat(second.nodes()).hasSize(1);
        assertThat(second.nodes().getFirst().id()).startsWith(seed.pageIds().getFirst() + ":");
        assertThat(second.nextCursor()).isNull();
      } finally { probe = null; }
      assertThat(batches).hasValue(4);
      assertThat(loaded.keySet()).containsExactlyInAnyOrderElementsOf(seed.pageIds());
      assertThat(loaded.values()).allMatch(value -> value == 2);
      for (var response : List.of(first, second)) {
        assertThat(response.headReceipts()).hasSize(2);
        assertThat(response.headReceipts()).allMatch(receipt -> receipt.rootTarget().equals(host.groups)
            || visible.contains(receipt.rootTarget().componentId()));
      }
      assertThat(headState(host)).isEqualTo(before);
      assertThat(eventCount(host)).isEqualTo(events);
      System.out.println("P3-B mostly-private pages=129 private=127 authorized=2 batches=4 loadedWorkspaceIdentities=129 loadsEach=2 globalOrder=true noFalseEnd=true privateHeadReceipts=false");
    } finally { probe = null; }
  }

  private void diagnose(int count) throws Exception { diagnose(count, false); }

  private void diagnose(int count, boolean requireCapacity) throws Exception {
    try (var host = new PageRuntimeHostFixture("p3b-density-" + UUID.randomUUID())) {
      UUID group = UUID.randomUUID();
      publishGroup(host, group);
      var prototypePage = host.pages.create(host.alice, "c1", new PageCreationCommand(UUID.randomUUID(),
          new PageSourceReference.Native("fixture:page", "r1"), "Density prototype")).receipt();
      var prototype = new PageCreationCodec().read(jdbc.queryForObject(
          "SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, prototypePage.pageId()));
      var seed = new PageRuntimeDenseFixture(host, prototype, group, group, count);
      seed.insert();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_head WHERE tenant_id=? AND root_component_type='praxis-dynamic-page' AND active_release_id IS NOT NULL",
          Long.class, host.scope.tenant())).isEqualTo((long) count);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_head WHERE tenant_id=? AND root_component_id=?",
          Long.class, host.scope.tenant(), prototypePage.pageId().toString())).isZero();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      measure(host, seed, requireCapacity);
    } finally { probe = null; }
  }

  private void measure(PageRuntimeHostFixture host, PageRuntimeDenseFixture seed, boolean requireCapacity) throws Exception {
    var headsBefore = headState(host);
    long eventsBefore = eventCount(host);
    var categories = new TreeMap<String, Long>();
    var categoryNanos = new TreeMap<String, Long>();
    var startedSql = new AtomicLong();
    var loadedPages = new LinkedHashMap<UUID, Integer>();
    var loadedWorkspaceBytes = new AtomicLong();
    var loadedPatchBytes = new AtomicLong();
    var batchWorkspaceBytes = new AtomicLong();
    var batchPatchBytes = new AtomicLong();
    var maxBatchWorkspaceBytes = new AtomicLong();
    var maxBatchPatchBytes = new AtomicLong();
    var peakManaged = new AtomicInteger();
    var baselineManaged = new AtomicInteger(-1);
    var managedAtLastBatch = new AtomicInteger();
    var managedAtBatchStarts = new ArrayList<Integer>();
    var nativeCalls = new AtomicInteger();
    var metadataCalls = new AtomicInteger();
    var structureCalls = new AtomicInteger();
    var structurePageRoots = new HashSet<String>();
    var retention = new ReadSetRetentionProbe();
    host.nativeCallback = context -> { nativeCalls.incrementAndGet(); retention.sample(); };
    host.metadataCallback = (invocation, target) -> { metadataCalls.incrementAndGet(); retention.sample(); };
    host.structureCallback = (invocation, target) -> {
      structureCalls.incrementAndGet();
      retention.sample();
      if ("praxis-dynamic-page".equals(target.componentType())) structurePageRoots.add(target.componentId());
    };
    probe = new SqlProbe() {
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
        String category = category(sql);
        categories.merge(category, 1L, Long::sum);
        startedSql.set(System.nanoTime());
        if (category.equals("weights")) {
          batchWorkspaceBytes.set(0);
          batchPatchBytes.set(0);
          var holder = (org.springframework.orm.jpa.EntityManagerHolder) TransactionSynchronizationManager.getResource(factory);
          int managed = holder.getEntityManager().unwrap(org.hibernate.engine.spi.SessionImplementor.class)
              .getPersistenceContextInternal().getNumberOfManagedEntities();
          baselineManaged.compareAndSet(-1, managed);
          managedAtLastBatch.set(managed);
          managedAtBatchStarts.add(managed);
        }
      }
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer, Object> binds, Object result) {
        categoryNanos.merge(category(sql), System.nanoTime() - startedSql.get(), Long::sum);
        retention.sample();
      }
      @Override public void managed(int count) { peakManaged.accumulateAndGet(count, Math::max); }
      @Override public void loaded(Object entity) {
        if (entity instanceof org.praxisplatform.config.domain.UiLayoutRevision revision) {
          long bytes = revision.getPatchDocument().getBytes(StandardCharsets.UTF_8).length;
          loadedPatchBytes.addAndGet(bytes);
          maxBatchPatchBytes.accumulateAndGet(batchPatchBytes.addAndGet(bytes), Math::max);
        }
        if (entity instanceof org.praxisplatform.config.domain.UiLayoutDraft draft
            && "praxis-dynamic-page".equals(draft.getRootComponentType())) {
          loadedPages.merge(UUID.fromString(draft.getRootComponentId()), 1, Integer::sum);
          long bytes = draft.getDraftDocument().getBytes(StandardCharsets.UTF_8).length;
          loadedWorkspaceBytes.addAndGet(bytes);
          maxBatchWorkspaceBytes.accumulateAndGet(batchWorkspaceBytes.addAndGet(bytes), Math::max);
        }
      }
    };
    var cpu = java.lang.management.ManagementFactory.getThreadMXBean();
    boolean cpuAvailable = cpu.isCurrentThreadCpuTimeSupported() && cpu.isThreadCpuTimeEnabled();
    long cpuStart = cpuAvailable ? cpu.getCurrentThreadCpuTime() : -1;
    long start = System.nanoTime(), sqlStart = sqlCalls.get(), sqlTime = sqlNanos.get();
    long isolationStart = isolationCalls.get(), isolationTime = isolationNanos.get();
    final PageCatalogResponse[] response = new PageCatalogResponse[1];
    Throwable failure = catchThrowable(() -> response[0] = host.runtime.catalog(host.alice, "c1", null, 1));
    long wallMs = (System.nanoTime() - start) / 1_000_000;
    long threadCpuNanos = cpuAvailable ? cpu.getCurrentThreadCpuTime() - cpuStart : -1;
    long prepared = sqlCalls.get() - sqlStart, jdbcMs = (sqlNanos.get() - sqlTime) / 1_000_000;
    long isolationCount = isolationCalls.get() - isolationStart, isolationMs = (isolationNanos.get() - isolationTime) / 1_000_000;
    probe = null;
    // A new DriverManager connection for each fresh assertion, after the read transaction has completed.
    assertThat(headState(host)).isEqualTo(headsBefore);
    assertThat(eventCount(host)).isEqualTo(eventsBefore);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    var report = new LinkedHashMap<String, Object>();
    report.put("catalogPages", seed.pageIds().size());
    report.put("expectedFullVectorIdentitiesIncludingUnpublishedPrototype", seed.pageIds().size() + 1);
    report.put("fixturePolicyCallbacksHaveNoRemoteExternalIO", true);
    report.put("budgetMs", host.budget.toMillis());
    report.put("acceptedWithinBudget", failure == null);
    report.put("wallMs", wallMs);
    report.put("threadCpuTimeAvailable", cpuAvailable);
    report.put("currentThreadCpuNanos", threadCpuNanos);
    report.put("readSetRetentionObserved", retention.report());
    report.put("preparedStatementsCompleted", prepared);
    report.put("completedPreparedExecutionMs", jdbcMs);
    report.put("attemptedSqlCategories", categories);
    var categoryMillis = new TreeMap<String, Long>();
    categoryNanos.forEach((name, nanos) -> categoryMillis.put(name, nanos / 1_000_000));
    report.put("completedSqlCategoryMs", categoryMillis);
    report.put("successfulIsolationGetterCalls", isolationCount);
    report.put("successfulIsolationGetterMs", isolationMs);
    report.put("nativeCallbacks", nativeCalls.get());
    report.put("metadataCallbacks", metadataCalls.get());
    report.put("structureCallbacks", structureCalls.get());
    report.put("pageRootsObservedByStructureCallback", structurePageRoots.size());
    var seededPageRoots = seed.pageIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
    report.put("seededPageRootsObservedByStructureCallback", structurePageRoots.stream().filter(seededPageRoots::contains).count());
    report.put("otherPageRootsObservedByStructureCallback", structurePageRoots.stream().filter(root -> !seededPageRoots.contains(root)).count());
    report.put("loadedIdentitiesDoNotProveAdmission", true);
    report.put("failedSqlExecutionTimeExcluded", true);
    report.put("loadedWorkspaceIdentities", loadedPages.size());
    report.put("loadedWorkspaceTextBytes", loadedWorkspaceBytes.get());
    report.put("loadedRevisionPatchTextBytes", loadedPatchBytes.get());
    report.put("maxBatchLoadedWorkspaceTextBytes", maxBatchWorkspaceBytes.get());
    report.put("maxBatchLoadedPatchTextBytes", maxBatchPatchBytes.get());
    report.put("workspaceEncodedSeedInputBytes", seed.workspaceEncodedInputBytes);
    report.put("managedBaseline", baselineManaged.get());
    report.put("managedPeak", peakManaged.get());
    report.put("managedAtLastBatchStart", managedAtLastBatch.get());
    report.put("managedAtBatchStarts", managedAtBatchStarts);
    report.put("returnedItems", response[0] == null ? 0 : response[0].items().size());
    report.put("freshHeadAndAuditUnchanged", true);
    report.put("outcome", failure == null ? "accepted" : failure.getClass().getSimpleName());
    System.out.println("P3-B runtime density diagnosis " + JSON.writeValueAsString(report));
    if (managedAtBatchStarts.size() > 1) {
      assertThat(managedAtBatchStarts.subList(1, managedAtBatchStarts.size())).allMatch(value -> value == baselineManaged.get());
    }
    if (requireCapacity) assertThat(failure).describedAs("1000 pages must complete under the unchanged budget").isNull();
    if (failure != null) {
      assertThat(failure).isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      assertThat(wallMs).describedAs("An early source failure is not classified as unavailability after the admitted budget")
          .isGreaterThanOrEqualTo(host.budget.toMillis());
      // This records unavailability after the budget, not its exact cause and never successful capacity.
      return;
    }
    assertThat(structurePageRoots).describedAs("Every authorized seeded page reached current structural admission")
        .containsAll(seededPageRoots);
    assertThat(loadedPages.keySet()).containsExactlyInAnyOrderElementsOf(seed.pageIds());
    assertThat(loadedPages.values()).allMatch(value -> value == 1);
    assertThat(response[0].items()).hasSize(1);
    assertThat(response[0].items().getFirst().pageId()).isEqualTo(seed.pageIds().getFirst());
    assertThat(response[0].nextCursor()).isNotNull();
    assertThat(categories.get("vector")).isEqualTo(2L);
    assertThat(categories.get("weights")).isEqualTo((seed.pageIds().size() + 127L) / 128L);
    assertThat(managedAtBatchStarts).hasSize((seed.pageIds().size() + 127) / 128);
    assertThat(peakManaged.get()).isLessThanOrEqualTo(baselineManaged.get() + 128 * 6 + 12);
  }

  /** Test-only observation, not production telemetry or a global/heap memory bound. */
  private static final class ReadSetRetentionProbe {
    final IdentityHashMap<Object, String> names = new IdentityHashMap<>();
    final Map<String, Long> peakBytes = new LinkedHashMap<>();
    final Map<String, Integer> peakEntries = new LinkedHashMap<>();
    final java.lang.reflect.Field ownerField;
    final java.lang.reflect.Field bytesField;
    final java.lang.reflect.Field valuesField;
    ReadSetRetentionProbe() {
      try {
        ownerField = ConfigTransactionReadSet.class.getDeclaredField("owner");
        bytesField = ConfigTransactionReadSet.class.getDeclaredField("bytes");
        valuesField = ConfigTransactionReadSet.class.getDeclaredField("values");
        ownerField.setAccessible(true); bytesField.setAccessible(true); valuesField.setAccessible(true);
      } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    long simultaneousBytes;
    int simultaneousEntries;

    void sample() {
      if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
      long bytes = 0;
      int entries = 0;
      try {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
          if (!(synchronization instanceof ConfigTransactionReadSet)) continue;
          Object owner = ownerField.get(synchronization);
          String name = names.computeIfAbsent(owner, key -> key.getClass().getSimpleName() + "#" + names.size());
          long retainedBytes = bytesField.getLong(synchronization);
          int retainedEntries = ((Map<?, ?>) valuesField.get(synchronization)).size();
          assertThat(retainedBytes).isBetween(0L, ConfigTransactionReadSet.MAX_BYTES);
          assertThat(retainedEntries).isBetween(0, ConfigTransactionReadSet.MAX_ENTRIES);
          peakBytes.merge(name, retainedBytes, Math::max);
          peakEntries.merge(name, retainedEntries, Math::max);
          bytes += retainedBytes; entries += retainedEntries;
        }
      } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
      simultaneousBytes = Math.max(simultaneousBytes, bytes);
      simultaneousEntries = Math.max(simultaneousEntries, entries);
    }

    Map<String, Object> report() {
      return Map.of("sampledAfterCompletedSqlAndCurrentCallbacks", true, "notHeapOrGlobalCap", true,
          "peakBytesPerOwner", peakBytes, "peakEntriesPerOwner", peakEntries,
          "maxSimultaneousBytesObserved", simultaneousBytes, "maxSimultaneousEntriesObserved", simultaneousEntries);
    }
  }

  private static String category(String sql) {
    String normalized = sql.toLowerCase(Locale.ROOT);
    if (normalized.contains("from ui_page_catalog c left join ui_layout_release_head h")) return "vector";
    if (normalized.contains("octet_length(d.draft_document::text)")) return "weights";
    String prefix = normalized.contains("requested.ordinal")
        ? (normalized.contains("as weight") ? "-prefetch-weights" : "-prefetch-rows") : "-individual";
    if (normalized.contains("ui_layout_baseline_metadata_evidence")) return "root-metadata" + prefix;
    if (normalized.contains("ui_page_creation_evidence")) return "creation-ledger" + prefix;
    for (String table : List.of("ui_layout_release_member", "ui_layout_assignment_revision", "ui_layout_revision", "ui_layout_definition", "ui_layout_draft", "ui_layout_release"))
      if (normalized.contains(table)) return "frozen-" + table;
    return "other";
  }
  private List<Map<String, Object>> headState(PageRuntimeHostFixture host) {
    return jdbc.queryForList("SELECT root_component_type,root_component_id,active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? ORDER BY root_component_type,root_component_id", host.scope.tenant());
  }
  private long eventCount(PageRuntimeHostFixture host) {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?", Long.class, host.scope.tenant());
  }
  private void publishGroup(PageRuntimeHostFixture host, UUID group) {
    var draft = host.commands.createDraft(host.alice, "c1", host.groups, UUID.randomUUID().toString());
    UUID draftId = UUID.fromString(draft.draft().draftRef());
    var revision = host.commands.createRevision(host.alice, "c1", host.groups, draftId, draft.draft().draftEtag(),
        new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(), host.groups, PageRuntimeHostFixture.groupsDocument(group).toString(), "Density groups"));
    var assignment = host.commands.createAssignment(host.alice, "c1", host.groups, draftId, revision.nextDraft().draft().draftEtag(),
        new UiLayoutLifecycleCommandService.AssignmentInput(UUID.randomUUID(), host.groups, "TENANT",
            new UiLayoutAudienceSelectorCommand(host.scope.tenant(), null, null, null, null, null), (short)0, "Density groups"));
    var frozen = host.commands.freezeRelease(host.alice, "c1", host.groups, draftId, assignment.nextDraft().draft().draftEtag(), UUID.randomUUID());
    UUID release = UUID.fromString(frozen.release().releaseRef());
    var submitted = host.commands.submit(host.alice, "c1", host.groups, release, frozen.review().reviewEtag());
    host.commands.approve(host.alice, "c1", host.groups, release, submitted.reviewEtag(), "Density seed groups");
    host.commands.publish(host.alice, "c1", host.groups, UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish(), release, "Density seed groups");
  }
}
