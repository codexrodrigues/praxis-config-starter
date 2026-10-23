package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.service.UiLayoutPageGroupHostFixture.Actor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("neon")
@EnabledIfSystemProperty(named = "praxis.page.p3a.neon.it", matches = "true")
class UiLayoutPageGroupPostgresIT extends UiLayoutPageGroupPostgresFixture {
  private UiLayoutPageGroupHostFixture host;

  @BeforeEach
  void setupHost() throws Exception {
    host = new UiLayoutPageGroupHostFixture("p3a-" + UUID.randomUUID());
  }

  @AfterEach
  void closeHost() {
    probe = null;
    if (host != null) host.close();
  }

  @Test
  void groupsAndCatalogPagePublishThroughTheRealOwnerAndBlockDanglingReferences() throws Exception {
    UUID group = UUID.randomUUID();
    var groupRelease = groupRelease(group);
    var groupHead = publish(host.alice, host.groups, groupRelease, null);
    var page = newPage(host.alice);
    var pageRelease = pageRelease(host.alice, page, "Page A", group);
    long started = System.nanoTime(), sqlBefore = sqlCalls.get(), ioBefore = sqlNanos.get();
    var pageHead = publish(host.alice, page.target(), pageRelease, null);
    System.out.println("P3-A page-publish wallMs=" + (System.nanoTime() - started) / 1_000_000
        + " SQL=" + (sqlCalls.get() - sqlBefore) + " jdbcMs=" + (sqlNanos.get() - ioBefore) / 1_000_000);
    assertThat(pageHead.activeReleaseRef()).isEqualTo(pageRelease.toString());
    var before = headState(host.groups);
    long events = eventCount();
    assertThatThrownBy(() -> host.commands.withdraw(host.alice, host.alice.version(), host.groups,
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(groupHead.headEtag()), "Cannot orphan A"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
    assertThat(headState(host.groups)).isEqualTo(before);
    assertThat(eventCount()).isEqualTo(events);
    host.commands.withdraw(host.alice, host.alice.version(), page.target(),
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(pageHead.headEtag()), "Withdraw A first");
    host.commands.withdraw(host.alice, host.alice.version(), host.groups,
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(groupHead.headEtag()), "Now unused");
    assertThat(headState(host.groups).get("active_release_id")).isNull();
    assertThat(headState(page.target()).get("active_release_id")).isNull();
    assertNoOrphans();
  }

  @Test
  void serverCommitFailureRollsBackHeadEtagEventAndDeferredOrphan() throws Exception {
    UUID group = UUID.randomUUID();
    var release = groupRelease(group);
    UUID orphan = UUID.randomUUID();
    long events = eventCount();
    var orphanInserted = new java.util.concurrent.atomic.AtomicInteger();
    var headWrites = new java.util.concurrent.atomic.AtomicInteger();
    var eventWrites = new java.util.concurrent.atomic.AtomicInteger();
    var commitFailures = new java.util.concurrent.atomic.AtomicInteger();
    probe = new SqlProbe() {
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer, Object> binds, Object result) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("insert into ") && normalized.contains("ui_layout_release_head")) headWrites.incrementAndGet();
        if (normalized.startsWith("insert into ") && normalized.contains("ui_layout_release_event")) eventWrites.incrementAndGet();
      }
      @Override public void beforeCommit(java.sql.Connection connection) {
        assertThat(orphanInserted.get()).isEqualTo(1);
        assertThat(headWrites.get()).isEqualTo(1);
        assertThat(eventWrites.get()).isEqualTo(1);
      }
      @Override public void afterCommit(java.sql.Connection connection, Throwable failure) {
        assertThat(sqlState(failure)).isEqualTo("23503");
        commitFailures.incrementAndGet();
      }
    };
    Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
      publish(host.alice, host.groups, release, null);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void beforeCommit(boolean readOnly) {
          orphanInserted.set(jdbc.update("""
              INSERT INTO ui_page_catalog(page_id,application_id,tenant_id,environment,route_namespace,
                root_component_type,root_component_id,created_at) VALUES(?,?,?,?,?,'praxis-dynamic-page',?,now())
              """, orphan, host.scope.application(), host.scope.tenant(), host.scope.environment(),
              host.scope.namespace(), orphan.toString()));
        }
      });
    }));
    probe = null;
    assertThat(failure).isNotNull();
    assertThat(sqlState(failure)).describedAs("Failure after commit entry: %s; head=%d event=%d orphan=%d", failure, headWrites.get(), eventWrites.get(), orphanInserted.get()).isEqualTo("23503");
    assertThat(commitFailures.get()).isEqualTo(1);
    assertThat(headState(host.groups)).isEmpty();
    assertThat(eventCount()).isEqualTo(events);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_page_catalog WHERE page_id=?", Long.class, orphan)).isZero();
  }

  @Test
  void staleManagedHeadAcceptsCurrentEtagAndRejectsTheOldValidator() {
    UUID group = UUID.randomUUID();
    UUID first = groupRelease(group), second = groupRelease(group), third = groupRelease(group);
    var initial = publish(host.alice, host.groups, first, null);
    var requiresNew = new org.springframework.transaction.support.TransactionTemplate(transactions.getTransactionManager());
    requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    var finalHead = transactions.execute(status -> {
      var managed = repo(org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository.class)
          .findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(host.scope.tenant(), host.scope.environment(),
              host.groups.componentType(), host.groups.componentId()).orElseThrow();
      var concurrent = requiresNew.execute(inner -> publish(host.alice, host.groups, second, initial.headEtag()));
      assertThat(managed.getHeadEtag().toString()).isEqualTo(initial.headEtag());
      return publish(host.alice, host.groups, third, concurrent.headEtag());
    });
    assertThat(finalHead.activeReleaseRef()).isEqualTo(third.toString());
    var before = headState(host.groups);
    long events = eventCount();
    assertThatThrownBy(() -> publish(host.alice, host.groups, first, initial.headEtag()))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    assertThat(headState(host.groups)).isEqualTo(before);
    assertThat(eventCount()).isEqualTo(events);
  }

  @Test
  void lastMetadataCallbackCannotRevokeFinalPageStructureAndStillCommit() {
    UUID group = UUID.randomUUID();
    publish(host.alice, host.groups, groupRelease(group), null);
    var page = newPage(host.alice);
    UUID release = pageRelease(host.alice, page, "Late structure", group);
    var inCommit = new java.util.concurrent.atomic.AtomicBoolean();
    var revoked = new java.util.concurrent.atomic.AtomicBoolean();
    host.metadataCallback = (invocation, target) -> {
      if (inCommit.get() && target.componentType().equals("praxis-dynamic-page")) revoked.set(true);
    };
    host.structureCallback = (invocation, target) -> {
      if (revoked.get() && target.equals(page.target())) throw denied();
    };
    long events = eventCount();
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      markBeforeCommit(inCommit);
      publish(host.alice, page.target(), release, null);
    })).isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    assertThat(revoked).isTrue();
    assertThat(headState(page.target())).isEmpty();
    assertThat(eventCount()).isEqualTo(events);
  }

  @Test
  void pageStructureCallbackCannotRevokeReadHeadOfGroupsAndStillCommit() {
    UUID group = UUID.randomUUID();
    publish(host.alice, host.groups, groupRelease(group), null);
    var page = newPage(host.alice);
    UUID release = pageRelease(host.alice, page, "Late dependency grant", group);
    var inCommit = new java.util.concurrent.atomic.AtomicBoolean();
    var revoked = new java.util.concurrent.atomic.AtomicBoolean();
    host.structureCallback = (invocation, target) -> {
      if (inCommit.get() && target.equals(page.target())) revoked.set(true);
    };
    host.grant = (operation, invocation) -> {
      if (revoked.get() && operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(host.groups)) throw denied();
    };
    long events = eventCount();
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      markBeforeCommit(inCommit);
      publish(host.alice, page.target(), release, null);
    })).isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    assertThat(revoked).isTrue();
    assertThat(headState(page.target())).isEmpty();
    assertThat(eventCount()).isEqualTo(events);
  }

  @Test
  void lastAdmissionCannotReplaceGroupsMappingWithoutChangingContextVersion() {
    UUID group = UUID.randomUUID();
    UUID release = groupRelease(group);
    var inCommit = new java.util.concurrent.atomic.AtomicBoolean();
    var metadataSeen = new java.util.concurrent.atomic.AtomicBoolean();
    var replaced = new java.util.concurrent.atomic.AtomicBoolean();
    host.metadataCallback = (invocation, target) -> { if (inCommit.get()) metadataSeen.set(true); };
    host.grant = (operation, invocation) -> {
      if (metadataSeen.get() && operation == UiLayoutLifecycleOperation.PUBLISH_RELEASE && replaced.compareAndSet(false, true)) {
        host.bindGroups(host.scope, new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString()));
      }
    };
    long events = eventCount();
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      markBeforeCommit(inCommit);
      publish(host.alice, host.groups, release, null);
    })).isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertThat(replaced).isTrue();
    assertThat(headState(host.groups)).isEmpty();
    assertThat(eventCount()).isEqualTo(events);
  }

  private static void markBeforeCommit(java.util.concurrent.atomic.AtomicBoolean marker) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public int getOrder() { return Integer.MIN_VALUE; }
      @Override public void beforeCommit(boolean readOnly) { marker.set(true); }
    });
  }

  private static UiLayoutLifecycleException denied() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Current fixture policy denied.");
  }

  enum Removal { OMIT, ROLLBACK, WITHDRAW }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> races() {
    return Arrays.stream(Removal.values()).flatMap(removal -> java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(removal, false),
        org.junit.jupiter.params.provider.Arguments.of(removal, true)));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "page versus {0}, groupsWin={1}")
  @org.junit.jupiter.params.provider.MethodSource("races")
  void controlledPageAndGroupTransitionsShareTheReferenceLock(Removal removal, boolean groupsWin) throws Exception {
    UUID group = UUID.randomUUID();
    UUID empty = removal == Removal.WITHDRAW ? null : groupRelease();
    String historicalEtag = removal == Removal.ROLLBACK ? publish(host.alice, host.groups, empty, null).headEtag() : null;
    UUID withGroup = groupRelease(group);
    var initialGroupHead = publish(host.alice, host.groups, withGroup, historicalEtag);
    UUID removalRelease = empty;
    var page = newPage(host.alice);
    UUID pageRelease = pageRelease(host.alice, page, "Controlled race", group);
    java.util.concurrent.Callable<UiLayoutReleaseHeadReceipt> pageAction = () -> publish(host.alice, page.target(), pageRelease, null);
    java.util.concurrent.Callable<UiLayoutReleaseHeadReceipt> groupAction = () -> removeGroups(removal, removalRelease, initialGroupHead.headEtag());
    var lockAcquired = new java.util.concurrent.CountDownLatch(1);
    var releaseWinner = new java.util.concurrent.CountDownLatch(1);
    var winningConnection = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
    var losingConnection = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
    var key = new java.util.concurrent.atomic.AtomicReference<Map<Integer, Object>>();
    var loserAttempts = new java.util.concurrent.atomic.AtomicInteger();
    long eventsBeforeRace = eventCount();
    var groupBeforeRace = headState(host.groups);
    var pageBeforeRace = headState(page.target());
    probe = new SqlProbe() {
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
        if (!sql.contains("pg_try_advisory_xact_lock") || !Thread.currentThread().getName().equals("p3-loser")) return;
        if (losingConnection.compareAndSet(null, connection)) {
          assertThat(connection).isNotSameAs(winningConnection.get());
          assertThat(binds).isEqualTo(key.get());
          loserAttempts.incrementAndGet();
        }
      }
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer, Object> binds, Object result) throws Exception {
        if (!sql.contains("pg_try_advisory_xact_lock") || !Thread.currentThread().getName().equals("p3-winner")
            || !winningConnection.compareAndSet(null, connection)) return;
        key.set(binds);
        lockAcquired.countDown();
        if (!releaseWinner.await(45, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("bounded winner barrier expired");
      }
    };
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var winner = executor.submit(() -> {
        Thread.currentThread().setName("p3-winner");
        return (groupsWin ? groupAction : pageAction).call();
      });
      assertThat(lockAcquired.await(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var loser = executor.submit(() -> {
        Thread.currentThread().setName("p3-loser");
        return catchThrowable(() -> (groupsWin ? pageAction : groupAction).call());
      });
      Throwable conflict = loser.get(60, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(conflict).isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
      assertThat(loserAttempts.get()).isEqualTo(1);
      releaseWinner.countDown();
      winner.get(60, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      releaseWinner.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      probe = null;
    }
    assertNoOrphans();
    assertThat(eventCount()).isEqualTo(eventsBeforeRace + 1);
    var groupsAfterRace = headState(host.groups);
    var pageAfterRace = headState(page.target());
    if (groupsWin) assertThat(pageAfterRace).isEqualTo(pageBeforeRace);
    else assertThat(groupsAfterRace).isEqualTo(groupBeforeRace);
    assertThatThrownBy(() -> (groupsWin ? pageAction : groupAction).call())
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
    assertNoOrphans();
    assertThat(eventCount()).isEqualTo(eventsBeforeRace + 1);
    assertThat(headState(host.groups)).isEqualTo(groupsAfterRace);
    assertThat(headState(page.target())).isEqualTo(pageAfterRace);
    if (groupsWin) assertThat(headState(page.target())).isEmpty();
    else assertThat(headState(host.groups).get("active_release_id")).isEqualTo(withGroup);
  }

  private UiLayoutReleaseHeadReceipt removeGroups(Removal removal, UUID release, String etag) {
    var condition = UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag);
    return switch (removal) {
      case OMIT -> publish(host.alice, host.groups, release, etag);
      case ROLLBACK -> host.commands.rollback(host.alice, host.alice.version(), host.groups, condition, release, "Controlled removal");
      case WITHDRAW -> host.commands.withdraw(host.alice, host.alice.version(), host.groups, condition, "Controlled removal");
    };
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ui_layout_release_head", "ui_layout_release_event"})
  void failureAfterActualSqlWriteRollsBackHeadAndAudit(String table) {
    UUID release = groupRelease(UUID.randomUUID());
    long events = eventCount();
    var inserted = new java.util.concurrent.atomic.AtomicInteger();
    probe = new SqlProbe() {
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer, Object> binds, Object result) {
        if (sql.toLowerCase(Locale.ROOT).startsWith("insert into " + table)) {
          inserted.incrementAndGet();
          throw new IllegalStateException("Injected after successful runtime INSERT");
        }
      }
    };
    assertThatThrownBy(() -> publish(host.alice, host.groups, release, null)).isInstanceOf(RuntimeException.class);
    probe = null;
    assertThat(inserted.get()).isEqualTo(1);
    assertThat(headState(host.groups)).isEmpty();
    assertThat(eventCount()).isEqualTo(events);
  }

  @Test
  void planChangedBeforeItsFirstLockAbortsAndExplicitRetryUsesTheNewHead() throws Exception {
    UUID initial = groupRelease(UUID.randomUUID()), concurrent = groupRelease(UUID.randomUUID()), candidate = groupRelease(UUID.randomUUID());
    var initialHead = publish(host.alice, host.groups, initial, null);
    var planned = new java.util.concurrent.CountDownLatch(1);
    var resume = new java.util.concurrent.CountDownLatch(1);
    var intercepted = new java.util.concurrent.atomic.AtomicBoolean();
    probe = new SqlProbe() {
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) throws Exception {
        if (Thread.currentThread().getName().equals("p3-old-plan") && sql.contains("pg_try_advisory_xact_lock")
            && intercepted.compareAndSet(false, true)) {
          planned.countDown();
          if (!resume.await(45, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("plan barrier timeout");
        }
      }
    };
    long events = eventCount();
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    UiLayoutReleaseHeadReceipt concurrentHead;
    try {
      var plannedAttempt = executor.submit(() -> {
        Thread.currentThread().setName("p3-old-plan");
        return catchThrowable(() -> publish(host.alice, host.groups, candidate, initialHead.headEtag()));
      });
      assertThat(planned.await(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      concurrentHead = publish(host.alice, host.groups, concurrent, initialHead.headEtag());
      resume.countDown();
      assertThat(plannedAttempt.get(60, java.util.concurrent.TimeUnit.SECONDS))
          .isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    } finally {
      resume.countDown(); executor.shutdownNow();
      assertThat(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      probe = null;
    }
    assertThat(headState(host.groups).get("active_release_id")).isEqualTo(concurrent);
    assertThat(eventCount()).isEqualTo(events + 1);
    publish(host.alice, host.groups, candidate, concurrentHead.headEtag());
    assertThat(eventCount()).isEqualTo(events + 2);
  }

  @Test
  void initialHeadAbsenceIsProtectedByThePhysicalRootLock() throws Exception {
    UUID first = groupRelease(), second = groupRelease();
    var acquired = new java.util.concurrent.CountDownLatch(1);
    var resume = new java.util.concurrent.CountDownLatch(1);
    var winnerConnection = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
    var key = new java.util.concurrent.atomic.AtomicReference<Map<Integer, Object>>();
    var loserMatched = new java.util.concurrent.atomic.AtomicBoolean();
    probe = new SqlProbe() {
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
        if (Thread.currentThread().getName().equals("p3-initial-loser") && sql.contains("pg_try_advisory_xact_lock")) {
          assertThat(connection).isNotSameAs(winnerConnection.get());
          assertThat(binds).isEqualTo(key.get()); loserMatched.set(true);
        }
      }
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer, Object> binds, Object result) throws Exception {
        if (Thread.currentThread().getName().equals("p3-initial-winner") && sql.contains("pg_try_advisory_xact_lock")
            && winnerConnection.compareAndSet(null, connection)) {
          key.set(binds); acquired.countDown();
          if (!resume.await(45, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("root barrier timeout");
        }
      }
    };
    long events = eventCount();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var winner = executor.submit(() -> { Thread.currentThread().setName("p3-initial-winner"); return publish(host.alice, host.groups, first, null); });
      assertThat(acquired.await(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var loser = executor.submit(() -> { Thread.currentThread().setName("p3-initial-loser"); return catchThrowable(() -> publish(host.alice, host.groups, second, null)); });
      assertThat(loser.get(60, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(UiLayoutLifecycleException.class)
          .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
      resume.countDown(); winner.get(60, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      resume.countDown(); executor.shutdownNow();
      assertThat(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); probe = null;
    }
    assertThat(loserMatched).isTrue();
    assertThat(headState(host.groups).get("active_release_id")).isEqualTo(first);
    assertThat(eventCount()).isEqualTo(events + 1);
  }

  @Test
  void rollbackRestoresTheWholePageAndItsBindingsWithoutChangingOtherHeads() throws Exception {
    UUID firstGroup = UUID.randomUUID(), secondGroup = UUID.randomUUID();
    var groupHead = publish(host.alice, host.groups, groupRelease(firstGroup, secondGroup), null);
    var pageA = newPage(host.alice);
    var pageB = newPage(host.alice);
    UUID a1 = pageRelease(host.alice, pageA, "A full", firstGroup);
    var firstHead = publish(host.alice, pageA.target(), a1, null);
    UUID b1 = pageRelease(host.alice, pageB, "B private", secondGroup);
    publish(host.alice, pageB.target(), b1, null);
    var secondDraft = host.commands.createDraft(host.alice, host.alice.version(), pageA.target(), UUID.randomUUID().toString());
    ObjectNode secondDocument = secondDraft.targets().getFirst().working().document().deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) secondDocument.at("/config/definition/widgets")).remove(1);
    ((ObjectNode) secondDocument.path("config")).putArray("navigation").addObject().put("id", UUID.randomUUID().toString())
        .put("groupId", secondGroup.toString()).put("label", "Moved A").put("icon", "home").put("order", 0);
    UUID a2 = approve(host.alice, pageA.target(), secondDraft, secondDocument);
    var secondHead = publish(host.alice, pageA.target(), a2, firstHead.headEtag());
    var groupsBefore = headState(host.groups); var bBefore = headState(pageB.target());
    long groupEvents = eventCount(host.groups), bEvents = eventCount(pageB.target());
    var restored = host.commands.rollback(host.alice, host.alice.version(), pageA.target(),
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(secondHead.headEtag()), a1, "Restore complete A1");
    assertThat(frozenDocument(UUID.fromString(restored.activeReleaseRef()))).isEqualTo(frozenDocument(a1));
    assertThat(frozenDocument(a1).at("/config/definition/widgets")).hasSize(2);
    assertThat(frozenDocument(a1).at("/config/navigation/0/groupId").asText()).isEqualTo(firstGroup.toString());
    assertThat(headState(host.groups)).isEqualTo(groupsBefore); assertThat(headState(pageB.target())).isEqualTo(bBefore);
    assertThat(eventCount(host.groups)).isEqualTo(groupEvents); assertThat(eventCount(pageB.target())).isEqualTo(bEvents);
    var again = publish(host.alice, pageA.target(), a2, restored.headEtag());
    var deniedHead = headState(pageA.target()); long aEvents = eventCount(pageA.target());
    host.grant = (operation, invocation) -> { if (operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(host.groups)) throw denied(); };
    assertThatThrownBy(() -> host.commands.rollback(host.alice, host.alice.version(), pageA.target(),
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(again.headEtag()), a1, "Denied dependency"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    host.grant = (operation, invocation) -> {};
    publish(host.alice, host.groups, groupRelease(secondGroup), groupHead.headEtag());
    assertThatThrownBy(() -> host.commands.rollback(host.alice, host.alice.version(), pageA.target(),
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(again.headEtag()), a1, "Removed dependency"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
    assertThat(headState(pageA.target())).isEqualTo(deniedHead); assertThat(eventCount(pageA.target())).isEqualTo(aEvents);
    assertThat(headState(pageB.target())).isEqualTo(bBefore); assertThat(eventCount(pageB.target())).isEqualTo(bEvents);
    assertNoOrphans();
  }

  private com.fasterxml.jackson.databind.JsonNode frozenDocument(UUID release) throws Exception {
    return JSON.readTree(jdbc.queryForObject("SELECT d.draft_document #> '{targets,0,working,document}' FROM ui_layout_draft d JOIN ui_layout_release r ON r.source_draft_id=d.id WHERE r.id=?", String.class, release));
  }

  private long eventCount(UiLayoutTarget root) {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=? AND environment=? AND root_component_type=? AND root_component_id=?",
        Long.class, host.scope.tenant(), host.scope.environment(), root.componentType(), root.componentId());
  }

  @Test
  void opposingRequiredTransitionsPoisonBothWholeTransactionsEvenWhenConflictsAreCaught() throws Exception {
    UUID firstGroup = UUID.randomUUID(), secondGroup = UUID.randomUUID();
    publish(host.alice, host.groups, groupRelease(firstGroup, secondGroup), null);
    var pageA = newPage(host.alice);
    var pageB = newPage(host.alice);
    UUID aRelease = pageRelease(host.alice, pageA, "A concurrent", firstGroup);
    UUID bRelease = pageRelease(host.alice, pageB, "B concurrent", secondGroup);
    long events = eventCount();
    var arrived = new java.util.concurrent.CountDownLatch(2);
    var continueTogether = new java.util.concurrent.CountDownLatch(1);
    var conflictsCaught = new java.util.concurrent.atomic.AtomicInteger();
    var insertedHeads = new java.util.concurrent.atomic.AtomicInteger();
    var insertedEvents = new java.util.concurrent.atomic.AtomicInteger();
    var bothSecondLocks = new java.util.concurrent.CountDownLatch(2);
    var firstKeys = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<java.sql.Connection, Map<Integer, Object>>());
    var connections = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<java.sql.Connection, Boolean>()));
    probe = new SqlProbe() {
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer, Object> binds, Object result) throws Exception {
        if (sql.contains("pg_try_advisory_xact_lock")) {
          if (Thread.currentThread().getName().equals("p3-opposing-first")) firstKeys.putIfAbsent(connection, binds);
          if (Thread.currentThread().getName().equals("p3-opposing-second")) {
            synchronized (firstKeys) {
              assertThat(firstKeys).hasSize(2);
              assertThat(binds).isEqualTo(firstKeys.entrySet().stream().filter(entry -> entry.getKey() != connection).findFirst().orElseThrow().getValue());
            }
            bothSecondLocks.countDown();
            if (!bothSecondLocks.await(45, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("both conflicts barrier expired");
          }
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("insert into ") && normalized.contains("ui_layout_release_head")) { insertedHeads.incrementAndGet(); connections.add(connection); }
        if (normalized.startsWith("insert into ") && normalized.contains("ui_layout_release_event")) insertedEvents.incrementAndGet();
      }
    };
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.function.Function<Boolean, Throwable> operation = reverse -> catchThrowable(() -> transactions.executeWithoutResult(status -> {
        Thread.currentThread().setName("p3-opposing-first");
        publish(host.alice, reverse ? pageB.target() : pageA.target(), reverse ? bRelease : aRelease, null);
        arrived.countDown();
        try { if (!continueTogether.await(45, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("cross-TX barrier expired"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        try {
          Thread.currentThread().setName("p3-opposing-second");
          publish(host.alice, reverse ? pageA.target() : pageB.target(), reverse ? aRelease : bRelease, null);
          fail("Opposite transaction still holds the required group/root");
        } catch (UiLayoutLifecycleException failure) {
          assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
          conflictsCaught.incrementAndGet(); // Deliberately swallowed: the transaction must remain poisoned.
        }
      }));
      var left = executor.submit(() -> operation.apply(false));
      var right = executor.submit(() -> operation.apply(true));
      assertThat(arrived.await(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      continueTogether.countDown();
      assertThat(left.get(60, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
      assertThat(right.get(60, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
    } finally {
      continueTogether.countDown(); executor.shutdownNow();
      assertThat(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); probe = null;
    }
    assertThat(conflictsCaught.get()).isEqualTo(2);
    assertThat(connections).hasSize(2);
    assertThat(insertedHeads.get()).isEqualTo(2);
    assertThat(insertedEvents.get()).isEqualTo(2);
    assertThat(headState(pageA.target())).isEmpty(); assertThat(headState(pageB.target())).isEmpty();
    assertThat(eventCount()).isEqualTo(events);
    publish(host.alice, pageA.target(), aRelease, null); publish(host.alice, pageB.target(), bRelease, null);
    assertThat(eventCount()).isEqualTo(events + 2);
    assertNoOrphans();
  }

  @Test
  void repeatedPageTransitionsInOneRequiredTransactionValidateItsFinalState() {
    UUID g1 = UUID.randomUUID(), g2 = UUID.randomUUID(), g3 = UUID.randomUUID();
    publish(host.alice, host.groups, groupRelease(g1, g2, g3), null);
    var page = newPage(host.alice);
    UUID first = pageRelease(host.alice, page, "First", g1);
    UUID second = pageReleaseFromNewDraft(page, "Second", g2);
    UUID third = pageReleaseFromNewDraft(page, "Third", g3);
    // This fixture explicitly budgets three commands and their commit callbacks, not one command's SLA.
    host.budget = java.time.Duration.ofSeconds(120);
    var longTransaction = new org.springframework.transaction.support.TransactionTemplate(transactions.getTransactionManager());
    longTransaction.setTimeout(130);
    long events = eventCount(page.target());
    var firstBudgetStart = new java.util.concurrent.atomic.AtomicLong();
    host.budgetCallback = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.PUBLISH_RELEASE && invocation.rootTarget().equals(page.target())) firstBudgetStart.compareAndSet(0, System.nanoTime());
    };
    var finalReceipt = longTransaction.execute(status -> {
      var h1 = publish(host.alice, page.target(), first, null);
      var h2 = publish(host.alice, page.target(), second, h1.headEtag());
      return publish(host.alice, page.target(), third, h2.headEtag());
    });
    long completeElapsed = (System.nanoTime() - firstBudgetStart.get()) / 1_000_000;
    System.out.println("P3-A REQUIRED three transitions firstBudgetMs=120000 completeElapsedMs=" + completeElapsed);
    assertThat(firstBudgetStart.get()).isPositive();
    assertThat(completeElapsed).isLessThan(120000);
    assertThat(finalReceipt.activeReleaseRef()).isEqualTo(third.toString());
    assertThat(headState(page.target()).get("active_release_id")).isEqualTo(third);
    assertThat(eventCount(page.target())).isEqualTo(events + 3);
    assertNoOrphans();
  }

  private UUID pageReleaseFromNewDraft(PageCreationReceipt page, String title, UUID group) {
    var draft = host.commands.createDraft(host.alice, host.alice.version(), page.target(), UUID.randomUUID().toString());
    ObjectNode document = draft.targets().getFirst().working().document().deepCopy();
    ((ObjectNode) document.at("/config/presentation")).put("title", title);
    ((ObjectNode) document.path("config")).putArray("navigation").addObject().put("id", UUID.randomUUID().toString())
        .put("groupId", group.toString()).put("label", title).put("icon", "home").put("order", 0);
    return approve(host.alice, page.target(), draft, document);
  }

  @Test
  void groupLockScopeIncludesApplicationTenantEnvironmentNamespaceButNotUnitOrActor() {
    var store = new UiLayoutPageGroupHeadStore(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource), transactions,
        repo(org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository.class));
    var keys = new ArrayList<Object>();
    probe = new SqlProbe() {
      @Override public void before(java.sql.Connection connection, String sql, Map<Integer, Object> binds) {
        if (sql.contains("pg_try_advisory_xact_lock")) keys.add(binds.get(1));
      }
    };
    UUID group = UUID.randomUUID();
    var bob = host.addActor("bob-scope", "unit-b", "context-b", host.scope);
    var actors = new ArrayList<Actor>(); actors.add(host.alice); actors.add(bob);
    var scopes = List.of(
        new UiLayoutPageGroupHostFixture.Scope(host.scope.tenant(), "app-b", host.scope.environment(), host.scope.namespace()),
        new UiLayoutPageGroupHostFixture.Scope(host.scope.tenant(), host.scope.application(), host.scope.environment(), "/other"),
        new UiLayoutPageGroupHostFixture.Scope(host.scope.tenant()+"-b", host.scope.application(), host.scope.environment(), host.scope.namespace()),
        new UiLayoutPageGroupHostFixture.Scope(host.scope.tenant(), host.scope.application(), "other-env", host.scope.namespace()));
    for (int index = 0; index < scopes.size(); index++) {
      var scope = scopes.get(index);
      if (index < 2) assertThatThrownBy(() -> host.bindGroups(scope, host.groups)).isInstanceOf(UiLayoutLifecycleException.class);
      var root = index < 2 ? new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString()) : host.groups;
      host.bindGroups(scope, root);
      actors.add(host.addActor("scope-actor-"+index, "unit-a", "c1", scope));
    }
    var pageHost = host.application.getBean(PageCreationHost.class);
    for (Actor actor : actors) {
      var root = host.groupRoots.get(actor.scope());
      var context = host.context(actor, root);
      assertThat(pageHost.navigationGroupsRoot(context)).isEqualTo(root);
      assertThat(pageHost.resolveLifecycle(context.invocation())).isEqualTo(context);
      transactions.executeWithoutResult(status -> store.lockGroup(context, group));
    }
    probe = null;
    assertThat(keys).hasSize(6);
    assertThat(keys.get(0)).isEqualTo(keys.get(1));
    assertThat(new HashSet<>(keys)).hasSize(5);
    var page = newPage(host.alice);
    UUID release = pageRelease(host.alice, page, "Scoped page");
    var pageHead = publish(host.alice, page.target(), release, null);
    var before = headState(page.target()); long events = eventCount();
    for (Actor actor : actors.subList(2, actors.size())) {
      assertThatThrownBy(() -> publish(actor, page.target(), release, pageHead.headEtag()))
          .isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
      assertThat(headState(page.target())).isEqualTo(before); assertThat(eventCount()).isEqualTo(events);
    }
  }

  @Test
  void privatePageInAnotherUnitStillPreventsGroupRemoval() {
    UUID group = UUID.randomUUID();
    var groupHead = publish(host.alice, host.groups, groupRelease(group), null);
    var page = newPage(host.alice);
    publish(host.alice, page.target(), pageRelease(host.alice, page, "Private unit A", group), null);
    var bob = host.addActor("bob-private", "unit-b", "c2", host.scope);
    host.grant = (operation, invocation) -> {
      if (invocation.actorRef().equals(bob.name()) && invocation.rootTarget().equals(page.target())) throw denied();
    };
    assertThatThrownBy(() -> host.reads.head(bob, bob.version(), page.target()))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.DENIED);
    var before = headState(host.groups); long events = eventCount();
    assertThatThrownBy(() -> host.commands.withdraw(bob, bob.version(), host.groups,
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(groupHead.headEtag()), "Private pages still count"))
        .isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE);
    assertThat(headState(host.groups)).isEqualTo(before); assertThat(eventCount()).isEqualTo(events); assertNoOrphans();
  }

  @Test
  void uncataloguedNativePageCannotPublishAndAnUnattributedActiveHeadBlocksGroupAdoption() {
    UUID group = UUID.randomUUID();
    var groupHead = publish(host.alice, host.groups, groupRelease(group), null);
    var root = UiLayoutPageRevisionReproductionTest.PAGE;
    var draft = host.commands.createDraft(host.alice, host.alice.version(), root, UUID.randomUUID().toString());
    UUID release = approve(host.alice, root, draft, host.sourcePage);
    long events = eventCount();
    assertThatThrownBy(() -> publish(host.alice, root, release, null)).isInstanceOf(UiLayoutLifecycleException.class)
        .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    assertThat(headState(root)).isEmpty(); assertThat(eventCount()).isEqualTo(events);
    // Explicit legacy adoption fixture: a valid native frozen release with a pre-P2 head, no catalog.
    UUID headEtag = UUID.randomUUID();
    transactions.executeWithoutResult(status -> {
      jdbc.update("INSERT INTO ui_layout_release_head(id,tenant_id,environment,root_component_type,root_component_id,active_release_id,head_etag,updated_at,row_version) VALUES(?,?,?,?,?,?,?,now(),0)",
          UUID.randomUUID(), host.scope.tenant(), host.scope.environment(), root.componentType(), root.componentId(), release, headEtag);
      jdbc.update("INSERT INTO ui_layout_release_event(id,tenant_id,environment,root_component_type,root_component_id,event_type,to_release_id,head_etag,actor,reason,created_at) VALUES(?,?,?,?,?,'PUBLISHED',?,?,?,'Pre-catalog fixture',now())",
          UUID.randomUUID(), host.scope.tenant(), host.scope.environment(), root.componentType(), root.componentId(), release, headEtag, host.alice.name());
    });
    var before = headState(host.groups); long beforeEvents = eventCount();
    Throwable failure = catchThrowable(() -> host.commands.withdraw(host.alice, host.alice.version(), host.groups,
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(groupHead.headEtag()), "Unknown active page"));
    assertThat(failure).isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(failure.getMessage()).doesNotContain(root.componentId());
    assertThat(headState(host.groups)).isEqualTo(before); assertThat(eventCount()).isEqualTo(beforeEvents);
  }

  @Test
  void realJdbcSavepointRollbackCannotEraseTheGlobalAbortLatch() {
    var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
    var nested = new org.springframework.transaction.support.TransactionTemplate(manager);
    nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
    var guard = new ConfigJdbcTransactionGuard(dataSource, outer);
    UUID id = UUID.randomUUID();
    Throwable failure = catchThrowable(() -> outer.executeWithoutResult(status -> {
      assertThat(jdbc.update("INSERT INTO ui_layout_definition(id,tenant_id,environment,component_type,component_id,schema_version,created_by,created_at) VALUES(?,?,?,?,?,?,?,now())",
          id, host.scope.tenant(), host.scope.environment(), "praxis-dynamic-page", id.toString(), "praxis.ui-layout/v1", host.alice.name())).isEqualTo(1);
      nested.executeWithoutResult(savepoint -> {
        var holder = (org.springframework.jdbc.datasource.ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
        holder.setRollbackOnly();
        guard.abortTransaction();
        savepoint.setRollbackOnly();
      });
    }));
    assertThat(failure).isInstanceOf(UiLayoutLifecycleException.class).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM ui_layout_definition WHERE id=?", Long.class, id)).isZero();
  }

  private PageCreationReceipt newPage(Actor actor) {
    return host.pages.create(actor, actor.version(), new PageCreationCommand(UUID.randomUUID(),
        new PageSourceReference.Native("fixture:page", "r1"), "Created page")).receipt();
  }

  private UUID groupRelease(UUID... groups) {
    var draft = host.commands.createDraft(host.alice, host.alice.version(), host.groups, UUID.randomUUID().toString());
    return approve(host.alice, host.groups, draft, UiLayoutPageGroupHostFixture.groupsDocument(groups));
  }

  private UUID pageRelease(Actor actor, PageCreationReceipt page, String title, UUID... groups) {
    var draft = host.reads.draft(actor, actor.version(), page.target(), page.draftId());
    var document = (ObjectNode) draft.targets().getFirst().working().document();
    ((ObjectNode) document.at("/config/presentation")).put("title", title);
    var bindings = ((ObjectNode) document.path("config")).putArray("navigation");
    for (int index = 0; index < groups.length; index++) bindings.addObject().put("id", UUID.randomUUID().toString())
        .put("groupId", groups[index].toString()).put("label", title + " " + index).put("icon", "home").put("order", index);
    return approve(actor, page.target(), draft, document);
  }

  private UUID approve(Actor actor, UiLayoutTarget root, UiLayoutDraftWorkspaceReceipt draft, ObjectNode document) {
    UUID draftId = UUID.fromString(draft.draft().draftRef());
    var revision = host.commands.createRevision(actor, actor.version(), root, draftId, draft.draft().draftEtag(),
        new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(), root, document.toString(), "P3 native revision"));
    var assignment = host.commands.createAssignment(actor, actor.version(), root, draftId, revision.nextDraft().draft().draftEtag(),
        new UiLayoutLifecycleCommandService.AssignmentInput(UUID.randomUUID(), root, "TENANT",
            new UiLayoutAudienceSelectorCommand(actor.scope().tenant(), null, null, null, null, null), (short) 0, "P3 contribution"));
    var frozen = host.commands.freezeRelease(actor, actor.version(), root, draftId, assignment.nextDraft().draft().draftEtag(), UUID.randomUUID());
    UUID release = UUID.fromString(frozen.release().releaseRef());
    var submitted = host.commands.submit(actor, actor.version(), root, release, frozen.review().reviewEtag());
    host.commands.approve(actor, actor.version(), root, release, submitted.reviewEtag(), "P3 approved");
    return release;
  }

  private UiLayoutReleaseHeadReceipt publish(Actor actor, UiLayoutTarget root, UUID release, String etag) {
    var condition = etag == null ? UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish()
        : UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag);
    return host.commands.publish(actor, actor.version(), root, condition, release, "P3 publish");
  }

  private Map<String, Object> headState(UiLayoutTarget root) {
    var rows = jdbc.queryForList("""
        SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head
        WHERE tenant_id=? AND environment=? AND root_component_type=? AND root_component_id=?
        """, host.scope.tenant(), host.scope.environment(), root.componentType(), root.componentId());
    return rows.isEmpty() ? Map.of() : rows.getFirst();
  }

  private long eventCount() {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?", Long.class, host.scope.tenant());
  }

  private void assertNoOrphans() {
    assertThat(jdbc.queryForObject("""
        WITH group_ids AS (
          SELECT g->>'id' AS id FROM ui_layout_release_head h JOIN ui_layout_release r ON r.id=h.active_release_id
          JOIN ui_layout_draft d ON d.id=r.source_draft_id,
          LATERAL jsonb_array_elements(d.draft_document->'targets'->0->'working'->'document'->'config'->'groups') g
          WHERE h.tenant_id=? AND h.environment=? AND h.root_component_id=?)
        SELECT count(*) FROM ui_layout_release_head h JOIN ui_page_catalog c
          ON c.tenant_id=h.tenant_id AND c.environment=h.environment AND c.root_component_id=h.root_component_id
        JOIN ui_layout_release r ON r.id=h.active_release_id JOIN ui_layout_draft d ON d.id=r.source_draft_id,
          LATERAL jsonb_array_elements(d.draft_document->'targets'->0->'working'->'document'->'config'->'navigation') n
        WHERE c.tenant_id=? AND c.environment=? AND c.application_id=? AND c.route_namespace=?
          AND NOT EXISTS(SELECT 1 FROM group_ids g WHERE g.id=n->>'groupId')
        """, Long.class, host.scope.tenant(), host.scope.environment(), host.groups.componentId(),
        host.scope.tenant(), host.scope.environment(), host.scope.application(), host.scope.namespace())).isZero();
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
    }
    return null;
  }
}
