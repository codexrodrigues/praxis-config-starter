package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.controller.*;
import org.praxisplatform.config.service.PageRuntimeHostFixture.Actor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("neon")
@EnabledIfSystemProperty(named = "praxis.page.p3b.neon.it", matches = "true")
class PageRuntimePostgresIT extends PageRuntimePostgresFixture {
  private PageRuntimeHostFixture host;

  @BeforeEach void setUpHost() throws Exception {
    host = new PageRuntimeHostFixture("p3b-" + UUID.randomUUID());
  }
  @AfterEach void closeHost() {
    probe = null;
    if (host != null) host.close();
  }

  @Test void publishedCatalogAndMenuShareTheHeadButUrlAndMenuKeepDistinctGrants() throws Exception {
    UUID group = UUID.randomUUID();
    publish(host.groups, groupRelease(group), null);
    var page = newPage();
    var draftOnly = host.runtime.catalog(host.alice, host.alice.version(), null, 50);
    assertThat(draftOnly.items()).isEmpty();
    UUID release = pageRelease(page, "Published A", group);
    var head = publish(page.target(), release, null);
    var direct = published(page.pageId());
    var catalog = host.runtime.catalog(host.alice, host.alice.version(), null, 50);
    var menu = navigation(group.toString(), null, 50);
    assertThat(direct.head()).isEqualTo(catalog.items().getFirst().head());
    assertThat(menu.headReceipts()).contains(direct.head());
    assertThat(direct.head()).isEqualTo(head);
    assertThat(menu.nodes()).extracting(EnterpriseRuntimeNavigationNode::route).containsExactly(direct.route());
    assertThat(direct.document().at("/config/presentation/title").asText()).isEqualTo("Published A");
    var mapper = JSON.copy().addMixIn(org.springframework.http.ProblemDetail.class,
        org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
    var mvc = MockMvcBuilders.standaloneSetup(new PageRuntimeController(host.runtime))
        .setControllerAdvice(new UiLayoutResolutionExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    String url = "/api/praxis/config/pages/" + page.pageId() + "/published";
    mvc.perform(get(url).principal(host.alice).header("X-Praxis-Context-Version", host.alice.version())
        .header("If-None-Match", "W/\"" + direct.receiptEtag() + "\""))
        .andExpect(status().isNotModified()).andExpect(header().string("Cache-Control", "no-cache, private"));
    host.grant = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(host.groups)) throw denied();
    };
    assertThat(published(page.pageId()).head()).isEqualTo(head);
    assertThat(navigation(null, null, 50).nodes()).isEmpty();
    host.grant = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(page.target())) throw denied();
    };
    assertThat(navigation(group.toString(), null, 50).nodes()).isEmpty();
    mvc.perform(get(url).principal(host.alice).header("X-Praxis-Context-Version", host.alice.version()).header("If-None-Match", "*"))
        .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
    host.grant = (operation, invocation) -> {};
    host.nativeCallback = context -> { throw denied(); };
    mvc.perform(get(url).principal(host.alice).header("X-Praxis-Context-Version", host.alice.version()).header("If-None-Match", "*"))
        .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
    host.nativeCallback = context -> {};
    host.metadataCallback = (invocation, target) -> {
      host.grant = (operation, current) -> {
        if (operation == UiLayoutLifecycleOperation.READ_HEAD && current.rootTarget().equals(page.target())) throw denied();
      };
    };
    mvc.perform(get(url).principal(host.alice).header("X-Praxis-Context-Version", host.alice.version()).header("If-None-Match", "*"))
        .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"));
    assertThat(headState(page.target()).get("active_release_id")).isEqualTo(release);
  }

  @Test void fullVectorDetectsNullHeadPhantomsAndHeadAbaAcrossIndependentTransactions() throws Exception {
    // Catalog identity without any head must participate in the vector.
    assertSnapshotConflictAfterFirstVector(() -> newPage(), () -> host.runtime.catalog(host.alice, "c1", null, 50));
    assertThat(host.runtime.catalog(host.alice, "c1", null, 50).items()).isEmpty();
    UUID releaseA = groupRelease(), releaseB = groupRelease();
    var headA = publish(host.groups, releaseA, null);
    var before = navigation(null, null, 50);
    assertThat(before.nodes()).isEmpty();
    assertThat(before.headReceipts()).containsExactly(headA);
    assertSnapshotConflictAfterFirstVector(() -> {
      var next = publish(host.groups, releaseB, headA.headEtag());
      host.commands.rollback(host.alice, "c1", host.groups,
          UiLayoutLifecycleCommandService.HeadWriteCondition.match(next.headEtag()), releaseA, "ABA proof");
      return null;
    }, () -> navigation(null, null, 50));
    var after = navigation(null, null, 50);
    assertThat(after.headReceipts().getFirst().activeReleaseRef()).isEqualTo(releaseA.toString());
    assertThat(after.headReceipts().getFirst().headEtag()).isNotEqualTo(headA.headEtag());
    assertThat(after.receiptEtag()).isNotEqualTo(before.receiptEtag());
  }

  @Test void participatingReadsObserveOwnWritesAndDoNotFreezeEarlierSnapshotsUntilCommit() {
    var page = newPage();
    UUID release = pageRelease(page, "Own writes");
    var initial = publish(page.target(), release, null);
    var original = headState(page.target());
    long events = eventCount();
    transactions.executeWithoutResult(status -> {
      assertThat(published(page.pageId()).head()).isEqualTo(initial);
      host.commands.withdraw(host.alice, "c1", page.target(),
          UiLayoutLifecycleCommandService.HeadWriteCondition.match(initial.headEtag()), "Outer rollback");
      assertThat(host.runtime.catalog(host.alice, "c1", null, 50).items()).isEmpty();
      assertThatThrownBy(() -> published(page.pageId())).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
      // The failed participating read poisons this outer transaction; only the separate branch commits.
      assertThat(status.isRollbackOnly()).isTrue();
      status.setRollbackOnly();
    });
    assertThat(headState(page.target())).isEqualTo(original);
    assertThat(eventCount()).isEqualTo(events);
    transactions.executeWithoutResult(status -> {
      assertThat(published(page.pageId()).head()).isEqualTo(initial);
      host.commands.withdraw(host.alice, "c1", page.target(),
          UiLayoutLifecycleCommandService.HeadWriteCondition.match(initial.headEtag()), "Outer commit");
      assertThat(host.runtime.catalog(host.alice, "c1", null, 50).items()).isEmpty();
    });
    assertThat(headState(page.target()).get("active_release_id")).isNull();
    assertThat(eventCount()).isEqualTo(events + 1);
    assertThatThrownBy(() -> published(page.pageId())).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    String withdrawn = headState(page.target()).get("head_etag").toString();
    transactions.executeWithoutResult(status -> {
      assertThat(host.runtime.catalog(host.alice, "c1", null, 50).items()).isEmpty();
      var republished = publish(page.target(), release, withdrawn);
      assertThat(published(page.pageId()).head()).isEqualTo(republished);
    });
    assertThat(headState(page.target()).get("active_release_id")).isEqualTo(release);
  }

  @Test void continuationBindsTheAuthorizedProjectionAndRejectsContextAbaWithoutLeakingPrivateHeads() {
    UUID group = UUID.randomUUID();
    publish(host.groups, groupRelease(group), null);
    UUID binding = UUID.randomUUID();
    var later = newPage();
    var earlier = newPage();
    var hidden = newPage();
    publish(later.target(), orderedPageRelease(later, "Later", group, binding, 9), null);
    var earlierHead = publish(earlier.target(), orderedPageRelease(earlier, "Earlier", group, binding, 1), null);
    publish(hidden.target(), orderedPageRelease(hidden, "Private label", group, binding, 0), null);
    host.grant = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(hidden.target())) throw denied();
    };
    var first = navigation(group.toString(), null, 1);
    assertThat(first.nodes()).extracting(EnterpriseRuntimeNavigationNode::label).containsExactly("Earlier");
    assertThat(first.nodes().getFirst().id()).isEqualTo(earlier.pageId() + ":" + binding);
    assertThat(first.headReceipts()).noneMatch(receipt -> receipt.rootTarget().equals(hidden.target()));
    var second = navigation(group.toString(), first.nextCursor(), 1);
    assertThat(second.nodes()).extracting(EnterpriseRuntimeNavigationNode::label).containsExactly("Later");
    assertThat(second.nodes().getFirst().id()).isEqualTo(later.pageId() + ":" + binding);
    assertThat(second.nextCursor()).isNull();
    assertThat(second.headReceipts()).noneMatch(receipt -> receipt.rootTarget().equals(hidden.target()));
    host.grant = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.READ_HEAD
          && (invocation.rootTarget().equals(hidden.target()) || invocation.rootTarget().equals(later.target()))) throw denied();
    };
    assertThatThrownBy(() -> navigation(group.toString(), first.nextCursor(), 1)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_CONTINUATION_STALE);
    host.grant = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(hidden.target())) throw denied();
    };
    assertThat(navigation(group.toString(), first.nextCursor(), 1).nodes()).extracting(EnterpriseRuntimeNavigationNode::label).containsExactly("Later");
    var isolatedScopes = List.of(
        new PageRuntimeHostFixture.Scope(host.scope.tenant(), "app-b", host.scope.environment(), host.scope.namespace()),
        new PageRuntimeHostFixture.Scope(host.scope.tenant(), host.scope.application(), host.scope.environment(), "/pages-b"),
        new PageRuntimeHostFixture.Scope(host.scope.tenant(), host.scope.application(), "other-env", host.scope.namespace()),
        new PageRuntimeHostFixture.Scope(host.scope.tenant() + "-other", host.scope.application(), host.scope.environment(), host.scope.namespace()));
    for (int index = 0; index < isolatedScopes.size(); index++) {
      var isolatedScope = isolatedScopes.get(index);
      String version = "c2-" + index;
      host.bindGroups(isolatedScope, new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString()));
      host.addActor(host.alice.name(), host.alice.unit(), version, isolatedScope);
      assertThat(host.runtime.catalog(host.alice, version, null, 50).items()).isEmpty();
      assertThatThrownBy(() -> host.runtime.published(host.alice, version, earlier.pageId())).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
      assertThatThrownBy(() -> host.runtime.published(host.alice, version, UUID.randomUUID())).extracting("code")
          .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    }
    host.addActor(host.alice.name(), host.alice.unit(), "c3", host.scope);
    assertThatThrownBy(() -> navigation(group.toString(), null, 1)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertThatThrownBy(() -> host.runtime.navigation(new EnterpriseRuntimeContextRequest(host.alice,null,null,null),
        "c3",group.toString(),first.nextCursor(),1)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_CONTINUATION_STALE);
    var current = host.runtime.navigation(new EnterpriseRuntimeContextRequest(host.alice,null,null,null), "c3",group.toString(),null,1);
    assertThat(current.receiptEtag()).isNotEqualTo(first.receiptEtag());
    assertThat(current.nodes()).extracting(EnterpriseRuntimeNavigationNode::label).containsExactly("Earlier");
    assertThat(headState(earlier.target()).get("head_etag").toString()).isEqualTo(earlierHead.headEtag());
  }

  private UUID orderedPageRelease(PageCreationReceipt page, String title, UUID group, UUID binding, int order) {
    var draft = host.reads.draft(host.alice, "c1", page.target(), page.draftId());
    var document = (ObjectNode) draft.targets().getFirst().working().document();
    ((ObjectNode) document.at("/config/presentation")).put("title", title);
    ((ObjectNode) document.path("config")).putArray("navigation").addObject().put("id",binding.toString())
        .put("groupId",group.toString()).put("label",title).put("icon","home").put("order",order);
    return approve(page.target(), draft, document);
  }

  @Test void currentSelectorAndLastMappingExcludeContentBeforeReceiptsAndConditionalSuccess() throws Exception {
    UUID group = UUID.randomUUID();
    publish(host.groups, groupRelease(group), null);
    var page = newPage();
    var draft = host.reads.draft(host.alice,"c1",page.target(),page.draftId());
    var document = (ObjectNode) draft.targets().getFirst().working().document();
    ((ObjectNode) document.path("config")).putArray("navigation").addObject().put("id",UUID.randomUUID().toString())
        .put("groupId",group.toString()).put("label","Only Alice").put("icon","home").put("order",0);
    UUID release = approveWithSelector(page.target(), draft, document, "USER",
        new UiLayoutAudienceSelectorCommand(host.scope.tenant(),null,null,null,null,host.alice.name()));
    publish(page.target(),release,null);
    assertThat(published(page.pageId()).head().activeReleaseRef()).isEqualTo(release.toString());
    var bob = host.addActor("bob-"+host.scope.tenant(),"unit-b","c1",host.scope);
    assertThat(host.runtime.catalog(bob,"c1",null,50).items()).isEmpty();
    var hidden = host.runtime.navigation(new EnterpriseRuntimeContextRequest(bob,null,null,null),"c1",group.toString(),null,50);
    assertThat(hidden.nodes()).isEmpty();
    assertThat(hidden.headReceipts()).extracting(UiLayoutReleaseHeadReceipt::rootTarget).containsExactly(host.groups);
    assertThatThrownBy(() -> host.runtime.published(bob,"c1",page.pageId())).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    assertThatThrownBy(() -> host.runtime.published(bob,"c1",UUID.randomUUID())).extracting("code").isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    var mappings = new AtomicInteger();
    host.mappingCallback = context -> {
      if (mappings.incrementAndGet()==2) host.grant = (operation, invocation) -> {
        if(operation==UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(page.target()))throw denied();
      };
    };
    assertThatThrownBy(() -> navigation(group.toString(),null,50)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
    assertThat(mappings).hasValue(2);
    host.mappingCallback = context -> {};
    host.grant = (operation, invocation) -> {};
    var originalHead = headState(page.target());
    long originalEvents = eventCount();
    var mapper = JSON.copy().addMixIn(org.springframework.http.ProblemDetail.class,
        org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
    var mvc = MockMvcBuilders.standaloneSetup(new PageRuntimeController(host.runtime))
        .setControllerAdvice(new UiLayoutResolutionExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    String url = "/api/praxis/config/pages/" + page.pageId() + "/published";
    var metadataCalls = new AtomicInteger();
    host.metadataCallback = (invocation, target) -> { metadataCalls.incrementAndGet(); throw denied(); };
    mvc.perform(get(url).principal(host.alice).header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*"))
        .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
    assertThat(metadataCalls.get()).isPositive();
    host.metadataCallback = (invocation, target) -> {};
    var structureCalls = new AtomicInteger();
    host.structureCallback = (invocation, target) -> { structureCalls.incrementAndGet(); throw denied(); };
    mvc.perform(get(url).principal(host.alice).header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*"))
        .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
    assertThat(structureCalls.get()).isPositive();
    host.structureCallback = (invocation, target) -> {};
    assertThat(published(page.pageId()).head().activeReleaseRef()).isEqualTo(release.toString());
    host.grant = (operation, invocation) -> {
      if (operation == UiLayoutLifecycleOperation.READ_HEAD && invocation.rootTarget().equals(host.groups)) throw denied();
    };
    assertThatThrownBy(() -> navigation(group.toString(), null, 50)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    assertThatThrownBy(() -> navigation(UUID.randomUUID().toString(), null, 50)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
    assertThat(headState(page.target())).isEqualTo(originalHead);
    assertThat(eventCount()).isEqualTo(originalEvents);

  }

  @Test void aCommittedPublicationWithLostResponseReconcilesThroughANewReadOwner() throws Exception {
    UUID releaseA = groupRelease(), releaseB = groupRelease();
    var headA = publish(host.groups, releaseA, null);
    var before = navigation(null, null, 50);
    long events = eventCount();
    var headWritten = new AtomicBoolean();
    var committed = new AtomicInteger();
    probe = new SqlProbe() {
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer,Object> binds, Object result) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("update ") && normalized.contains("ui_layout_release_head")) headWritten.set(true);
      }
      @Override public void afterCommit(java.sql.Connection connection, Throwable failure) throws Exception {
        if (headWritten.get() && failure == null && committed.compareAndSet(0, 1)) {
          throw new java.sql.SQLTransientConnectionException("Synthetic lost acknowledgement after actual COMMIT", "08006");
        }
      }
    };
    Throwable uncertain;
    try { uncertain = catchThrowable(() -> publish(host.groups, releaseB, headA.headEtag())); }
    finally { probe = null; }
    assertThat(uncertain).isNotNull();
    assertThat(committed).hasValue(1);
    assertThat(headState(host.groups).get("active_release_id")).isEqualTo(releaseB);
    assertThat(eventCount()).isEqualTo(events + 1);
    var newOwner = newReadOwner();
    var response = newOwner.navigation(new EnterpriseRuntimeContextRequest(host.alice,null,null,null), "c1",null,null,50);
    assertThat(response.receiptEtag()).isNotEqualTo(before.receiptEtag());
    assertThat(response.headReceipts().getFirst().activeReleaseRef()).isEqualTo(releaseB.toString());
    assertThat(response.nodes()).isEmpty();
    var after = newOwner.navigation(new EnterpriseRuntimeContextRequest(host.alice,null,null,null), "c1",null,null,50);
    assertThat(after.receiptEtag()).isEqualTo(response.receiptEtag());
    assertThat(after.resolvedAt()).isAfter(response.resolvedAt());
    var controller = new EnterpriseRuntimeContextController(null, null, null, null,
        newOwner::navigation, null);
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    mvc.perform(get("/api/praxis/runtime/navigation").principal(host.alice)
        .header("X-Praxis-Context-Version", "c1").header("If-None-Match", "W/\"" + before.receiptEtag() + "\""))
        .andExpect(status().isOk()).andExpect(header().string("ETag", "W/\"" + response.receiptEtag() + "\""))
        .andExpect(header().string("Cache-Control", "no-cache, private"));
    mvc.perform(get("/api/praxis/runtime/navigation").principal(host.alice)
        .header("X-Praxis-Context-Version", "c1").header("If-None-Match", "W/\"" + response.receiptEtag() + "\""))
        .andExpect(status().isNotModified()).andExpect(header().string("Cache-Control", "no-cache, private"));
    assertThat(eventCount()).isEqualTo(events + 1);
  }

  private PageRuntimeReadService newReadOwner() {
    var application = host.application;
    return new PageRuntimeReadService(application.getBean(PageCreationHost.class),
        application.getBean(EffectiveUiAudienceProvider.class),
        host.pages.lifecycleAdmission(application.getBean(UiLayoutLifecycleAdmission.class)),
        application.getBean(UiLayoutLifecycleStructureValidator.class), host.pages.workspaceSource(), host.pages.metadataAdmission(),
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource), transactions,
        application.getBean(UiLayoutValidationBudgetPolicy.class),
        repo(org.praxisplatform.config.repository.UiLayoutReleaseRepository.class),
        repo(org.praxisplatform.config.repository.UiLayoutDraftRepository.class),
        repo(org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository.class),
        repo(org.praxisplatform.config.repository.UiLayoutRevisionRepository.class),
        repo(org.praxisplatform.config.repository.UiLayoutDefinitionRepository.class),
        repo(org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository.class),
        JSON, new CanonicalJsonHashService(JSON), java.time.Clock.systemUTC());
  }

  private void assertSnapshotConflictAfterFirstVector(Callable<?> concurrentWrite, Callable<?> read) throws Exception {
    var reached = new AtomicInteger();
    var firstConnection = new AtomicReference<java.sql.Connection>();
    var writeConnection = new AtomicReference<java.sql.Connection>();
    var executor = Executors.newSingleThreadExecutor();
    probe = new SqlProbe() {
      @Override public void after(java.sql.Connection connection, String sql, Map<Integer,Object> binds, Object result) throws Exception {
        if (sql.contains("FROM ui_page_catalog c LEFT JOIN ui_layout_release_head h") && reached.incrementAndGet() == 1) {
          firstConnection.set(connection);
          executor.submit(concurrentWrite).get(45, TimeUnit.SECONDS);
        } else if (Thread.currentThread().getName().startsWith("pool-") && (sql.toLowerCase(Locale.ROOT).startsWith("insert") || sql.toLowerCase(Locale.ROOT).startsWith("update"))) {
          writeConnection.compareAndSet(null, connection);
        }
      }
    };
    try {
      assertThatThrownBy(read::call).extracting("code").isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
      assertThat(reached).hasValue(2);
      assertThat(writeConnection.get()).isNotNull().isNotSameAs(firstConnection.get());
    } finally {
      probe = null;
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private PageCreationReceipt newPage() {
    return host.pages.create(host.alice, "c1", new PageCreationCommand(UUID.randomUUID(),
        new PageSourceReference.Native("fixture:page", "r1"), "Created runtime page")).receipt();
  }
  private UUID groupRelease(UUID... groups) {
    var draft = host.commands.createDraft(host.alice, "c1", host.groups, UUID.randomUUID().toString());
    return approve(host.groups, draft, PageRuntimeHostFixture.groupsDocument(groups));
  }
  private UUID pageRelease(PageCreationReceipt page, String title, UUID... groups) {
    var draft = host.reads.draft(host.alice, "c1", page.target(), page.draftId());
    var document = (ObjectNode) draft.targets().getFirst().working().document();
    ((ObjectNode) document.at("/config/presentation")).put("title", title);
    var bindings = ((ObjectNode) document.path("config")).putArray("navigation");
    for (int i=0;i<groups.length;i++) bindings.addObject().put("id", UUID.randomUUID().toString()).put("groupId",groups[i].toString())
        .put("label",title).put("icon","home").put("order",i);
    return approve(page.target(), draft, document);
  }
  private UUID approve(UiLayoutTarget root, UiLayoutDraftWorkspaceReceipt draft, ObjectNode document) {
    return approveWithSelector(root, draft, document, "TENANT",
        new UiLayoutAudienceSelectorCommand(host.scope.tenant(), null, null, null, null, null));
  }
  private UUID approveWithSelector(UiLayoutTarget root, UiLayoutDraftWorkspaceReceipt draft,
      ObjectNode document, String layer, UiLayoutAudienceSelectorCommand selector) {
    UUID draftId = UUID.fromString(draft.draft().draftRef());
    var revision = host.commands.createRevision(host.alice, "c1", root, draftId, draft.draft().draftEtag(),
        new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(), root, document.toString(), "Runtime revision"));
    var assignment = host.commands.createAssignment(host.alice, "c1", root, draftId, revision.nextDraft().draft().draftEtag(),
        new UiLayoutLifecycleCommandService.AssignmentInput(UUID.randomUUID(), root, layer, selector, (short)0, "runtime"));
    var frozen = host.commands.freezeRelease(host.alice,"c1",root,draftId,assignment.nextDraft().draft().draftEtag(),UUID.randomUUID());
    UUID release = UUID.fromString(frozen.release().releaseRef());
    var submitted = host.commands.submit(host.alice,"c1",root,release,frozen.review().reviewEtag());
    host.commands.approve(host.alice,"c1",root,release,submitted.reviewEtag(),"Runtime approved");
    return release;
  }
  private UiLayoutReleaseHeadReceipt publish(UiLayoutTarget root, UUID release, String etag) {
    return host.commands.publish(host.alice,"c1",root,etag==null?UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish()
        :UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag),release,"Runtime publication");
  }
  private PublishedPageResponse published(UUID page) { return host.runtime.published(host.alice,"c1",page); }
  private EnterpriseRuntimeNavigationResponse navigation(String parent,String cursor,int size) {
    return host.runtime.navigation(new EnterpriseRuntimeContextRequest(host.alice,null,null,null),"c1",parent,cursor,size);
  }
  private Map<String,Object> headState(UiLayoutTarget root) {
    var rows = jdbc.queryForList("SELECT active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND environment=? AND root_component_type=? AND root_component_id=?",
        host.scope.tenant(),host.scope.environment(),root.componentType(),root.componentId());
    return rows.isEmpty()?Map.of():rows.getFirst();
  }
  private long eventCount() { return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=?",Long.class,host.scope.tenant()); }
  private static UiLayoutLifecycleException denied() { return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED,"private fixture policy"); }
}
