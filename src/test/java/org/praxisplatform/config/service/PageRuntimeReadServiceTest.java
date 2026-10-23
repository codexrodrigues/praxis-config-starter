package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.praxisplatform.config.domain.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real owner, frozen resolver and codecs with mocked JDBC/repositories; not PostgreSQL/JPA integration proof. */
@Tag("unit")
class PageRuntimeReadServiceTest {
  final ObjectMapper json = new ObjectMapper();
  final CanonicalJsonHashService hashes = new CanonicalJsonHashService(json);
  final UUID groupId = UUID.fromString("11111111-1111-4111-8111-111111111111");
  final UiLayoutTarget root = new UiLayoutTarget("praxis-app-shell", "22222222-2222-4222-8222-222222222222");
  final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "unit", "test", "c1",
      new UiLayoutCompositionRegistration(root, List.of(root)));
  final PageCreationContext context = new PageCreationContext("app", "/pages", invocation);
  final PageCreationHost host = mock(PageCreationHost.class);
  final UiLayoutLifecycleAdmission grants = mock(UiLayoutLifecycleAdmission.class);
  final EffectiveUiAudienceProvider audiences = mock(EffectiveUiAudienceProvider.class);
  final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class);
  Duration budget = Duration.ofMinutes(1);
  final List<PageRuntimeStore.Head> heads = new ArrayList<>();
  final Set<UiLayoutTarget> denied = new HashSet<>();
  final AtomicInteger vectorReads = new AtomicInteger();
  Runnable admissionHook = () -> {};
  Runnable mappingHook = () -> {};
  Runnable vectorHook = () -> {};
  EffectiveUiAudience audience = new EffectiveUiAudience("tenant", "test", "actor", null, null, null, Set.of(), "c1");
  final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
  final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  final UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
  final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
  final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
  final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
  final Map<UUID, UiLayoutRelease> releaseRows = new HashMap<>();
  final Map<UUID, UiLayoutDraft> draftRows = new HashMap<>();
  final Map<UUID, UiLayoutReleaseMember> memberRows = new HashMap<>();
  final Map<UUID, UiLayoutRevision> revisionRows = new HashMap<>();
  final Map<UUID, UiLayoutDefinition> definitionRows = new HashMap<>();
  final Map<UUID, UiLayoutAssignmentRevision> assignmentRows = new HashMap<>();
  final Map<UUID, UiLayoutMetadataCapture> captures = new HashMap<>();
  final jakarta.persistence.EntityManagerFactory factory = mock(jakarta.persistence.EntityManagerFactory.class);
  PageRuntimeReadService service;
  TransactionTemplate transactions;

  @BeforeEach void setUp() throws Exception {
    var connection = mock(Connection.class);
    var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
    when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
    doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    var dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    var jdbcTransactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var entityManager = mock(jakarta.persistence.EntityManager.class);
    var entityTransaction = mock(jakarta.persistence.EntityTransaction.class);
    var session = mock(org.hibernate.engine.spi.SessionImplementor.class);
    var persistence = mock(org.hibernate.engine.spi.PersistenceContext.class);
    when(persistence.getEntitiesByKey()).thenReturn(Map.of());
    when(session.getPersistenceContextInternal()).thenReturn(persistence);
    when(entityManager.getTransaction()).thenReturn(entityTransaction);
    when(entityTransaction.isActive()).thenReturn(true);
    when(entityManager.unwrap(org.hibernate.Session.class)).thenReturn(session);
    when(entityManager.unwrap(org.hibernate.engine.spi.SessionImplementor.class)).thenReturn(session);
    doAnswer(call -> ((org.hibernate.jdbc.ReturningWork<?>) call.getArgument(0)).execute(connection))
        .when(session).doReturningWork(any());
    var holder = new org.springframework.orm.jpa.EntityManagerHolder(entityManager);
    holder.setSynchronizedWithTransaction(true);
    TransactionSynchronizationManager.bindResource(factory, holder);
    var manager = new org.springframework.orm.jpa.JpaTransactionManager();
    manager.setEntityManagerFactory(factory); manager.setDataSource(dataSource);
    // Exercise Spring REQUIRED synchronization with mocked ORM identity, not a JPA integration claim.
    transactions = new TransactionTemplate(manager) {
      @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> callback) {
        return jdbcTransactions.execute(callback);
      }
    };
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.getJdbcTemplate()).thenReturn(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    doAnswer(call -> {
      String sql = call.getArgument(0);
      MapSqlParameterSource parameters = call.getArgument(1);
      if (sql.contains("FROM ui_page_catalog")) {
        vectorReads.incrementAndGet(); vectorHook.run();
        UUID page = (UUID) parameters.getValue("page");
        boolean groups = parameters.hasValue("groupRoot");
        return heads.stream().filter(head -> page == null ? head.pageId() != null || groups : page.equals(head.pageId())).toList();
      }
      if (sql.contains("AS weight")) {
        Collection<UUID> ids = (Collection<UUID>) parameters.getValue("ids");
        return ids.stream().map(id -> Map.entry(id, 1024L)).toList();
      }
      if (sql.contains("ui_layout_baseline_metadata_evidence")) {
        return List.of(captures.get((UUID) parameters.getValue("source_draft_ref")));
      }
      throw new AssertionError("Unexpected fixture SQL");
    }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    when(releases.findAllById(any())).thenAnswer(call -> select(releaseRows, call.getArgument(0)));
    when(drafts.findAllById(any())).thenAnswer(call -> select(draftRows, call.getArgument(0)));
    when(revisions.findAllById(any())).thenAnswer(call -> select(revisionRows, call.getArgument(0)));
    when(definitions.findAllById(any())).thenAnswer(call -> select(definitionRows, call.getArgument(0)));
    when(assignments.findAllById(any())).thenAnswer(call -> select(assignmentRows, call.getArgument(0)));
    when(members.findByReleaseIdInOrderByReleaseIdAscMemberOrderAsc(any())).thenAnswer(call -> {
      Collection<UUID> ids = call.getArgument(0);
      return memberRows.values().stream().filter(row -> ids.contains(row.getReleaseId())).toList();
    });
    var metadata = mock(UiLayoutBaselineMetadataAdmission.class);
    doAnswer(call -> { admissionHook.run(); return null; }).when(metadata).require(any(), any(), any(), any(), any(), any());
    when(host.resolve(any(), eq("c1"))).thenReturn(context);
    when(host.resolveLifecycle(any())).thenAnswer(call -> new PageCreationContext("app", "/pages", call.getArgument(0)));
    when(host.navigationGroupsRoot(any())).thenAnswer(call -> { mappingHook.run(); return root; });
    when(audiences.resolve(any(), any())).thenAnswer(call -> audience);
    doAnswer(call -> {
      if (denied.contains(((UiLayoutLifecycleInvocation) call.getArgument(1)).rootTarget())) {
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private grant");
      }
      return null;
    }).when(grants).require(eq(UiLayoutLifecycleOperation.READ_HEAD), any());
    service = new PageRuntimeReadService(host, audiences, grants, structure,
        mock(UiLayoutDraftWorkspaceSource.class), metadata, jdbc, transactions,
        (op, inv) -> budget, releases, drafts, members, revisions, definitions, assignments,
        json, hashes, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  @AfterEach void close() { TransactionSynchronizationManager.unbindResourceIfPossible(factory); }

  private static <T> List<T> select(Map<UUID, T> records, Iterable<UUID> ids) {
    var values = new ArrayList<T>();
    ids.forEach(id -> { if (records.containsKey(id)) values.add(records.get(id)); });
    return values;
  }

  @Test void elapsedBudgetAfterSuccessfulStructureCallbackCannotReturnBodyOr304() throws Exception {
    var page = addPage("30000000-0000-4000-8000-000000000001", 1, "Must not escape");
    budget = Duration.ofSeconds(2);
    var callbacks = new AtomicInteger();
    var admittedRemaining = new java.util.concurrent.atomic.AtomicLong();
    var expired = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(call -> {
      UiLayoutValidationContext validation = call.getArgument(2);
      callbacks.incrementAndGet();
      admittedRemaining.set(validation.attempt().remainingBudget().toNanos());
      assertThat(admittedRemaining.get()).isPositive();
      long bound = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (!validation.attempt().remainingBudget().isZero()) {
        assertThat(System.nanoTime()).isLessThan(bound);
        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
      }
      expired.set(true);
      return null; // An extension succeeds, but its admitted attempt has elapsed.
    }).when(structure).validateFrozenRelease(any(), anyList(), any());
    var mapper = json.copy().addMixIn(org.springframework.http.ProblemDetail.class,
        org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
        new org.praxisplatform.config.controller.PageRuntimeController(service))
        .setControllerAdvice(new org.praxisplatform.config.controller.UiLayoutResolutionExceptionHandler())
        .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
        "/api/praxis/config/pages/" + page.pageId() + "/published").principal(() -> "actor")
        .header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("ETag"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.receiptEtag").doesNotExist())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.document").doesNotExist())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Must not escape"))));
    assertThat(callbacks).hasValue(1);
    assertThat(expired).isTrue();
    assertThat(admittedRemaining.get()).isPositive();
    System.out.println("P3-B callback budget admittedRemainingNanos=" + admittedRemaining.get()
        + " observedRemainingNanos=0 callbacks=" + callbacks.get() + " HTTP=503 noStore=true IO=simulated");
  }

  @Test void unexpectedHostFailureIsUnavailableAndInvalidInputIsAClientError() {
    doThrow(new IllegalArgumentException("private-host-context")).when(host).resolve(any(), anyString());
    assertThatThrownBy(() -> service.catalog(() -> "actor", "c1", null, 50)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThatThrownBy(() -> navigation("invalid-parent", null, 50)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR);
    assertThatThrownBy(() -> service.catalog(() -> "actor", "c1", null, 101)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR);
  }

  @Test void malformedHostGroupsRootIsUnavailableRatherThanAnInvalidCallerCursor() {
    when(host.navigationGroupsRoot(any())).thenReturn(new UiLayoutTarget("praxis-app-shell", "private-invalid-host-root"));
    assertThatThrownBy(() -> navigation(null, null, 50)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }

  @Test void authorizedEmptyGroupsWitnessTheirHeadButDeniedGroupsRevealNoHead() {
    var group = addGroups(false);
    var first = navigation(null, null, 50);
    assertThat(first.nodes()).isEmpty(); assertThat(first.headReceipts()).hasSize(1);
    replaceHead(group);
    var next = navigation(null, null, 50);
    assertThat(next.receiptEtag()).isNotEqualTo(first.receiptEtag());
    denied.add(root);
    var hidden = navigation(null, null, 50);
    assertThat(hidden.headReceipts()).isEmpty();
    var old = heads.getFirst();
    heads.set(0, new PageRuntimeStore.Head(null, root, old.id(), null, UUID.randomUUID(), 3L, Instant.EPOCH));
    assertThat(navigation(null, null, 50).receiptEtag()).isEqualTo(hidden.receiptEtag());
    assertThatThrownBy(() -> navigation(groupId.toString(), null, 50)).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
  }

  @Test void directPageDoesNotRequireGroupGrantButMenuRequiresBoth() {
    addGroups(true); var page = addPage("30000000-0000-4000-8000-000000000001", 1, "Entry");
    denied.add(root);
    assertThat(service.published(() -> "actor", "c1", page.pageId()).head().activeReleaseRef()).isEqualTo(page.release().toString());
    assertThat(navigation(null, null, 50).nodes()).isEmpty();
    denied.clear(); denied.add(page.root());
    var children = navigation(groupId.toString(), null, 50);
    assertThat(children.nodes()).isEmpty(); assertThat(children.headReceipts()).hasSize(1);
    assertThatThrownBy(() -> service.published(() -> "actor", "c1", page.pageId())).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.NOT_FOUND);
  }

  @Test void globalOrderingAndBindingIdentityAreIndependentOfCatalogOrder() {
    addGroups(true);
    var pageA = addPage("30000000-0000-4000-8000-000000000001", 9, "Later");
    var pageB = addPage("30000000-0000-4000-8000-000000000002", 1, "Earlier");
    var first = navigation(groupId.toString(), null, 1);
    assertThat(first.nodes()).extracting(EnterpriseRuntimeNavigationNode::label).containsExactly("Earlier");
    assertThat(first.nodes().getFirst().id()).startsWith(pageB.pageId().toString() + ":");
    var second = navigation(groupId.toString(), first.nextCursor(), 1);
    assertThat(second.nodes()).extracting(EnterpriseRuntimeNavigationNode::label).containsExactly("Later");
    assertThat(second.nextCursor()).isNull();
    denied.add(pageA.root());
    assertThatThrownBy(() -> navigation(groupId.toString(), first.nextCursor(), 1)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_CONTINUATION_STALE);
  }

  @Test void lastMetadataAndLastMappingCallbacksCannotRevokeGrantsAndStillReturn() {
    addGroups(true); var page = addPage("30000000-0000-4000-8000-000000000001", 1, "Entry");
    admissionHook = () -> denied.add(page.root());
    assertThatThrownBy(() -> service.published(() -> "actor", "c1", page.pageId())).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
    admissionHook = () -> {}; denied.clear();
    var mappings = new AtomicInteger();
    mappingHook = () -> { if (mappings.incrementAndGet() == 2) denied.add(page.root()); };
    assertThatThrownBy(() -> navigation(groupId.toString(), null, 50)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
  }

  @Test void permissiveReadGrantNeverBypassesNativeAdmissionAndFinalAudience() {
    var page = addPage("30000000-0000-4000-8000-000000000001", 1, "Entry");
    admissionHook = () -> { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "producer private"); };
    assertThatThrownBy(() -> service.published(() -> "actor", "c1", page.pageId())).extracting("code")
        .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    admissionHook = () -> audience = new EffectiveUiAudience("tenant", "test", "actor", null, null, "changed", Set.of(), "c1");
    assertThatThrownBy(() -> service.published(() -> "actor", "c1", page.pageId())).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
  }

  @Test void vectorPhantomAndHeadABAInvalidateTheOperation() {
    var group = addGroups(true);
    vectorHook = () -> { if (vectorReads.get() == 2) replaceHead(group); };
    assertThatThrownBy(() -> navigation(null, null, 50)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
  }

  @Test void requiredReadsDoNotFreezeTheHeadUntilOuterCommitButKeepAuthorityChecks() {
    var group = addGroups(false);
    transactions.execute(status -> {
      var first = navigation(null, null, 50);
      replaceHead(group);
      var second = navigation(null, null, 50);
      assertThat(second.receiptEtag()).isNotEqualTo(first.receiptEtag());
      return null;
    });
    assertThat(vectorReads).hasValue(4); // no third vector in either beforeCommit callback
    assertThatThrownBy(() -> transactions.execute(status -> {
      navigation(null, null, 50);
      denied.add(root);
      return null;
    })).extracting("code").isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED);
  }

  private EnterpriseRuntimeNavigationResponse navigation(String parent, String cursor, int size) {
    return service.navigation(new EnterpriseRuntimeContextRequest(() -> "actor", null, null, null), "c1", parent, cursor, size);
  }

  private PageRuntimeStore.Head addGroups(boolean populated) {
    ObjectNode document = json.createObjectNode().put("kind", UiLayoutNavigationGroupsRevisionReproduction.KIND).put("version", 1);
    var groups = document.putObject("config").putArray("groups");
    if (populated) groups.addObject().put("id", groupId.toString()).put("label", "Group").put("icon", "home").put("order", 0);
    return add(null, root, document);
  }

  private PageRuntimeStore.Head addPage(String id, int order, String label) {
    UUID page = UUID.fromString(id);
    var target = new UiLayoutTarget(UiLayoutPageRevisionReproduction.TYPE, id);
    ObjectNode document = json.createObjectNode().put("kind", UiLayoutPageRevisionReproduction.KIND).put("version", 1);
    var config = document.putObject("config");
    config.putObject("presentation").put("title", label).put("icon", "home");
    config.putObject("definition").putArray("widgets");
    config.putArray("navigation").addObject().put("id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        .put("groupId", groupId.toString()).put("label", label).put("icon", "home").put("order", order);
    return add(page, target, document);
  }

  private PageRuntimeStore.Head add(UUID page, UiLayoutTarget target, ObjectNode document) {
    UUID release = UUID.randomUUID(), revision = UUID.randomUUID();
    String hash = hashes.sha256Exact(document);
    String patchHash = hashes.sha256Exact(document.get("config"));
    var state = new UiLayoutDraftWorkspaceDocument.TargetState(target,
        new UiLayoutAuthoringDocumentDescriptor(document.path("kind").textValue(), "fixture", "1"),
        new UiLayoutPatchDocumentDescriptor("fixture", "praxis.ui-layout/v1"),
        new UiLayoutDraftWorkspaceDocument.BaselineDocument("fixture", hash, document),
        new UiLayoutDraftWorkspaceDocument.WorkingDocument(hash, document),
        new UiLayoutDraftWorkspaceDocument.RevisionSelection(revision, 1, patchHash, "praxis.ui-layout/v1", UUID.randomUUID()),
        new UiLayoutDraftWorkspaceDocument.AssignmentSelection(UUID.randomUUID(), revision, "TENANT",
            new UiLayoutAudienceSelectorCommand("tenant", null, null, null, null, null), (short) 0, "page", UUID.randomUUID()));
    UUID draftId = UUID.randomUUID(), definitionId = UUID.randomUUID();
    var workspace = new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION,
        List.of(state), UUID.randomUUID());
    var releaseRow = UiLayoutRelease.builder().id(release).tenantId("tenant").environment("test")
        .rootComponentType(target.componentType()).rootComponentId(target.componentId()).sourceDraftId(draftId).build();
    var draftRow = UiLayoutDraft.builder().id(draftId).tenantId("tenant").environment("test")
        .rootComponentType(target.componentType()).rootComponentId(target.componentId()).state("RELEASED")
        .draftEtag(UUID.randomUUID()).draftDocument(new UiLayoutDraftWorkspaceCodec(json, hashes).encode(workspace)).build();
    var member = UiLayoutReleaseMember.builder().id(UUID.randomUUID()).releaseId(release).memberOrder(0)
        .componentType(target.componentType()).componentId(target.componentId()).contentRevisionId(revision)
        .assignmentRevisionId(state.selectedAssignment().assignmentRevisionRef()).contributionKey("page").build();
    var definition = UiLayoutDefinition.builder().id(definitionId).tenantId("tenant").environment("test")
        .componentType(target.componentType()).componentId(target.componentId()).schemaVersion("praxis.ui-layout/v1").build();
    var revisionRow = UiLayoutRevision.builder().id(revision).definitionId(definitionId).revisionNumber(1L)
        .contentHash(patchHash).schemaVersion("praxis.ui-layout/v1").patchDocument(document.get("config").toString()).build();
    var assignment = UiLayoutAssignmentRevision.builder().id(state.selectedAssignment().assignmentRevisionRef())
        .tenantId("tenant").environment("test").revisionId(revision).layerClass("TENANT")
        .selectorDocument(json.valueToTree(state.selectedAssignment().selector()).toString())
        .priority((short) 0).contributionKey("page").build();
    releaseRows.put(release, releaseRow); draftRows.put(draftId, draftRow); memberRows.put(member.getId(), member);
    definitionRows.put(definitionId, definition); revisionRows.put(revision, revisionRow); assignmentRows.put(assignment.getId(), assignment);
    when(releases.findByIdAndTenantIdAndEnvironment(release, "tenant", "test")).thenReturn(Optional.of(releaseRow));
    when(releases.findBySourceDraftId(draftId)).thenReturn(Optional.of(releaseRow));
    when(drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(draftId, "tenant", "test",
        target.componentType(), target.componentId())).thenReturn(Optional.of(draftRow));
    when(members.findByReleaseIdOrderByMemberOrder(release)).thenReturn(List.of(member));
    when(definitions.findById(definitionId)).thenReturn(Optional.of(definition));
    when(revisions.findById(revision)).thenReturn(Optional.of(revisionRow));
    when(assignments.findById(assignment.getId())).thenReturn(Optional.of(assignment));
    var binding = new UiLayoutMetadataCapture.Binding(draftId,
        new UiLayoutMetadataCapture.Scope("tenant", "test", target, target), "fixture", state.authoring(), hash);
    captures.put(draftId, new UiLayoutMetadataCaptureCodec(json, hashes).seal(UUID.randomUUID(), binding, document,
        new UiLayoutBaselineMetadataSeed.NativeDocumentSource("fixture", "native", "1", "producer", "publication"),
        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
        new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "c1", "policy", "1", Instant.EPOCH), document.toString()));
    var head = new PageRuntimeStore.Head(page, target, UUID.randomUUID(), release, UUID.randomUUID(), 1L, Instant.EPOCH);
    heads.add(head); return head;
  }

  private void replaceHead(PageRuntimeStore.Head head) {
    int index = heads.indexOf(head);
    heads.set(index, new PageRuntimeStore.Head(head.pageId(), head.root(), head.id(), head.release(), UUID.randomUUID(), head.version() + 2, Instant.EPOCH.plusSeconds(1)));
  }
}
