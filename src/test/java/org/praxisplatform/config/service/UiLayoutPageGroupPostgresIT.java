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
        if (normalized.startsWith("insert into ui_layout_release_head")) headWrites.incrementAndGet();
        if (normalized.startsWith("insert into ui_layout_release_event")) eventWrites.incrementAndGet();
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
    assertThat(sqlState(failure)).isEqualTo("23503");
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
      if (inCommit.get() && invocation.rootTarget().equals(page.target())) revoked.set(true);
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
