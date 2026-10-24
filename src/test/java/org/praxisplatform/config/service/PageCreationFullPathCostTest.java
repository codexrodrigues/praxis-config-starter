package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete canonical orchestration and JDBC stores, synthetic transport only. No latency/DB proof. */
@Tag("unit")
class PageCreationFullPathCostTest {
  @Test void countsCompleteNativeCreationThroughActualSpringCommitCallbacks() throws Exception {
    var fixture = new Fixture();
    var result = fixture.pages.create(() -> "alice", "c1", fixture.command);
    assertThat(result.created()).isTrue();
    assertThat(fixture.committed).isTrue();
    assertThat(fixture.draft.getId()).isEqualTo(result.receipt().draftId());
    assertThat(fixture.events.getLast().boundary()).isEqualTo("JDBC_COMMIT");
    assertThat(fixture.counts()).containsEntry("HOST_REQUIRE_CREATION", 7L);
    assertThat(fixture.events).anySatisfy(event -> {
      assertThat(event.boundary()).isEqualTo("HOST_REQUIRE_NATIVE_PROVENANCE");
      assertThat(event.phase()).isEqualTo("BEFORE_COMMIT");
    });
    fixture.writeReport();
  }

  @Test void countsPublishedCopyThroughRealFrozenResolverAndCommitCallbacks() throws Exception {
    var fixture = new Fixture();
    fixture.pages.create(() -> "alice", "c1", fixture.command);
    var published = fixture.freezeNativeSource();
    fixture.events.clear(); fixture.committed = false; fixture.rolledBack = false;
    var command = new PageCreationCommand(UUID.randomUUID(), published, "Published copy");
    var result = fixture.pages.create(() -> "alice", "c1", command);
    assertThat(result.created()).isTrue();
    assertThat(result.receipt().pageId()).isNotEqualTo(published.pageId());
    assertThat(fixture.committed).isTrue();
    assertThat(fixture.counts()).containsEntry("PUBLISHED_EVENT_SQL_READ", 1L);
    fixture.writeReport("published");
  }

  @Test void freshCaptureAdmissionRejectsRevocationAndContextDriftBeforeSourceIo() throws Exception {
    for (boolean published : List.of(false, true)) {
      for (boolean drift : List.of(false, true)) {
        var fixture = new Fixture();
        PageSourceReference source = fixture.origin;
        if (published) {
          fixture.pages.create(() -> "alice", "c1", fixture.command);
          source = fixture.freezeNativeSource();
        }
        fixture.events.clear(); fixture.jointCalls = 0; fixture.committed = false; fixture.rolledBack = false;
        fixture.denyCapture = !drift; fixture.driftCapture = drift;
        fixture.expectedPrincipal = () -> "alice";
        var command = new PageCreationCommand(UUID.randomUUID(), source, "Copy");
        assertThatThrownBy(() -> fixture.pages.create(fixture.expectedPrincipal, "c1", command))
            .extracting("code").isEqualTo(drift ? UiLayoutLifecycleException.Code.CONTEXT_STALE : UiLayoutLifecycleException.Code.DENIED);
        assertThat(fixture.counts()).containsEntry("HOST_REQUIRE_CREATION", 3L);
        assertThat(fixture.counts()).doesNotContainKeys("HOST_CAPTURE_NATIVE", "PUBLISHED_EVENT_SQL_READ", "LEDGER_SQL_WRITE", "DRAFT_WRITE");
        assertThat(fixture.committed).isFalse();
        assertThat(fixture.rolledBack).isTrue();
      }
    }
  }

  @Test void lateAuthorityDenialPreventsPhysicalCommitAfterAllProvisionalWrites() throws Exception {
    var fixture = new Fixture();
    fixture.denyBeforeCommit = true;
    assertThatThrownBy(() -> fixture.pages.create(() -> "alice", "c1", fixture.command))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    assertThat(fixture.draft).isNotNull();
    assertThat(fixture.metadataRows).isNotEmpty();
    assertThat(fixture.committed).isFalse();
    assertThat(fixture.rolledBack).isTrue();
  }

  @Test void finalJointCreationContextDriftPreventsCommitWithoutRetargetingProvisionalWrites() throws Exception {
    var fixture = new Fixture();
    fixture.driftBeforeCommit = true;
    assertThatThrownBy(() -> fixture.pages.create(() -> "alice", "c1", fixture.command))
        .extracting("code").isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertThat(fixture.draft).isNotNull();
    assertThat(fixture.metadataRows).isNotEmpty();
    assertThat(fixture.committed).isFalse();
    assertThat(fixture.rolledBack).isTrue();
  }

  record Event(String boundary, String phase, List<String> callPath) {}
  private static final class Fixture {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
    final List<Event> events = new ArrayList<>();
    final Map<String, String> metadataRows = new LinkedHashMap<>();
    final PageSourceReference.Native origin = new PageSourceReference.Native("fixture:page", "r1");
    final PageCreationCommand command = new PageCreationCommand(UUID.randomUUID(), origin, "Copy");
    final UiLayoutTarget anchor = new UiLayoutTarget("praxis-app-shell", "99999999-9999-4999-8999-999999999999");
    final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
    final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
    final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
    final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
    final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
    final UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
    final UiLayoutReleaseReviewRepository reviews = mock(UiLayoutReleaseReviewRepository.class);
    final UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final PageCreationService pages;
    UiLayoutLifecycleCommandService commands;
    UiLayoutLifecycleReadService reads;
    UiLayoutDraft draft;
    String ledger;
    final Map<String,String> ledgers = new LinkedHashMap<>();
    final Map<UUID,UiLayoutDraft> draftRows = new LinkedHashMap<>();
    boolean committed, rolledBack, denyBeforeCommit, driftBeforeCommit, denyCapture, driftCapture;
    java.security.Principal expectedPrincipal;
    int jointCalls;

    Fixture() throws Exception {
      var connection = mock(Connection.class);
      when(connection.getAutoCommit()).thenReturn(false);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      var statement = mock(java.sql.PreparedStatement.class);
      var result = mock(java.sql.ResultSet.class);
      when(connection.prepareStatement("SELECT current_setting('transaction_isolation')")).thenReturn(statement);
      when(statement.executeQuery()).thenAnswer(call -> {
        record("ISOLATION_SELECT", false); when(result.next()).thenReturn(true, false); return result;
      });
      when(result.getString(1)).thenReturn("read committed");
      doAnswer(call -> { record("JDBC_COMMIT", false); committed = true; return null; }).when(connection).commit();
      doAnswer(call -> { record("JDBC_ROLLBACK", false); rolledBack = true; return null; }).when(connection).rollback();
      var dataSource = mock(DataSource.class);
      when(dataSource.getConnection()).thenReturn(connection);
      var plain = mock(JdbcTemplate.class);
      when(plain.getDataSource()).thenReturn(dataSource);
      when(jdbc.getJdbcTemplate()).thenReturn(plain);
      when(plain.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
          .thenAnswer(call -> { record("PAGE_LOCK", false); return 0; });
      var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
          .thenAnswer(call -> query(call.getArgument(0), call.getArgument(1)));
      when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
          .thenAnswer(call -> update(call.getArgument(0), call.getArgument(1)));
      when(jdbc.queryForObject(anyString(), any(Map.class), eq(Boolean.class))).thenReturn(false);
      when(drafts.save(any())).thenAnswer(call -> {
        record("DRAFT_WRITE", false); draft = call.getArgument(0); draftRows.put(draft.getId(), draft); return draft;
      });
      when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
          any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
        record("DRAFT_LOOKUP", false); return draftRows.values().stream().filter(d -> d.getRootComponentId().equals(call.getArgument(3)) && d.getCreationIdempotencyKey().equals(call.getArgument(5))).findFirst();
      });
      doAnswer(call -> { record("DRAFT_FLUSH", false); return null; }).when(drafts).flush();

      var document = UiLayoutPageRevisionReproductionTest.page();
      ((com.fasterxml.jackson.databind.node.ArrayNode) document.at("/config/definition/widgets")).remove(1);
      var children = UiLayoutPageRevisionReproduction.children(document);
      var child = children.entrySet().iterator().next();
      var childSeed = new UiLayoutPageChildSeed(new UiLayoutPageChildSource(child.getKey(),
          new UiLayoutPageChildSource.NativeSource("registered-native", "fixture:form", "r1")),
          new UiLayoutAuthoringDocumentDescriptor(child.getValue().path("kind").asText(), "fixture:form", "1"),
          UiLayoutPageRevisionReproductionTest.PATCH, "fixture:form", child.getValue(),
          UiLayoutMetadataTestFixtures.nativeMetadata(child.getValue(), "alice", "unit", "c1"));
      var nativeSeed = new UiLayoutDraftWorkspaceSeed.TargetSeed(UiLayoutPageRevisionReproductionTest.PAGE,
          UiLayoutPageRevisionReproductionTest.DESCRIPTOR, UiLayoutPageRevisionReproductionTest.PATCH,
          "fixture:page", document, UiLayoutMetadataTestFixtures.nativeMetadata(document, "alice", "unit", "c1"), List.of(childSeed));
      PageCreationHost host = new PageCreationHost() {
        public PageCreationContext resolve(java.security.Principal principal, String version) {
          record("HOST_RESOLVE", true); return context(invocation(anchor));
        }
        public PageCreationContext resolveLifecycle(UiLayoutLifecycleInvocation invocation) {
          record("HOST_RESOLVE_LIFECYCLE", true); return context(invocation);
        }
        public UiLayoutTarget navigationGroupsRoot(PageCreationContext current) { throw new AssertionError("Not a groups operation"); }
        public void require(PageCreationOperation op, PageCreationContext current, PageSourceReference source) { record("HOST_REQUIRE_" + op, true); }
        public PageCreationContext requireCreation(java.security.Principal principal, String version, PageCreationContext current, PageSourceReference source) {
          record("HOST_REQUIRE_CREATION", true);
          if (expectedPrincipal != null) assertThat(principal).isSameAs(expectedPrincipal);
          assertThat(version).isEqualTo("c1");
          if (++jointCalls == 3 && denyCapture) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Revoked after lookup");
          if (jointCalls == 3 && driftCapture) return new PageCreationContext("foreign", "/pages", invocation(anchor));
          return driftBeforeCommit && events.getLast().phase().equals("BEFORE_COMMIT")
              ? new PageCreationContext("foreign", "/pages", invocation(anchor)) : context(invocation(anchor));
        }
        public UiLayoutDraftWorkspaceSeed.TargetSeed captureNative(PageCreationContext current, PageSourceReference.Native source) {
          record("HOST_CAPTURE_NATIVE", true); return nativeSeed;
        }
        public PageCreationContext requireNativeProvenance(PageCreationContext current, PageSourceReference.Native source, UiLayoutDraftWorkspaceSeed.TargetSeed captured) {
          record("HOST_REQUIRE_NATIVE_PROVENANCE", true); return context(current.invocation());
        }
        public void requireNative(PageCreationContext current, PageSourceReference.Native source, UiLayoutDraftWorkspaceSeed.TargetSeed captured) {
          record("HOST_REQUIRE_NATIVE", true);
        }
      };
      UiLayoutDraftWorkspaceSource nativeSource = new UiLayoutDraftWorkspaceSource() {
        public UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
          record("NATIVE_CAPTURE", true); return new UiLayoutDraftWorkspaceSeed(List.of(nativeSeed));
        }
        public void requirePageChildEvidence(UiLayoutLifecycleInvocation invocation, UiLayoutTarget page, UiLayoutPageChildSeed captured, UiLayoutValidationContext validation) {
          record("NATIVE_CHILD", true);
        }
      };
      UiLayoutBaselineMetadataAdmission metadata = (i,t,a,s,b,m) -> record("NATIVE_METADATA", true);
      var structure = mock(UiLayoutLifecycleStructureValidator.class, call -> {
        if (call.getMethod().getName().startsWith("validate")) record("STRUCTURE_" + call.getMethod().getName(), false);
        return null;
      });
      UiLayoutValidationBudgetPolicy budgets = (o,i) -> Duration.ofSeconds(40);
      pages = new PageCreationService(host, jdbc, transactions, hashes, mapper, nativeSource, metadata, structure,
          budgets, drafts, releases, members, definitions, revisions, assignments, () -> commands, () -> reads);
      var invocationProvider = pages.invocationProvider((p,v,r) -> { record("NATIVE_INVOCATION", true); return invocation(r); });
      var admission = pages.lifecycleAdmission(new PageLifecycleAdmission() {
        public void require(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
          record("LIFECYCLE_" + operation, true);
        }
        public PageCreationContext requirePage(UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {
          record("PAGE_LIFECYCLE_" + operation, true);
          return context(invocation);
        }
      });
      commands = UiLayoutLifecycleCommandService.assemble(definitions, revisions, assignments, drafts, releases, members,
          reviews, mock(UiLayoutReleaseApprovalRepository.class), heads, mock(UiLayoutReleaseEventRepository.class),
          (t,e,target) -> {}, hashes, mapper, (r,m) -> {}, invocationProvider, admission, structure, pages.workspaceSource(),
          (t,e,target,a,k) -> record("DRAFT_LOCK", false), new JdbcUiLayoutMetadataCaptureStore(jdbc, mapper, hashes, transactions),
          pages.metadataAdmission(), transactions, budgets, null);
      reads = new UiLayoutLifecycleReadService(invocationProvider, admission, drafts, releases, reviews, heads,
          definitions, revisions, assignments, mapper, hashes, structure, budgets);
    }

    PageSourceReference.Published freezeNativeSource() throws Exception {
      var workspaceCodec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
      var workspace = workspaceCodec.decodeHistorical(draft.getDraftDocument());
      var state = workspace.targets().getFirst();
      var target = state.target();
      var patch = UiLayoutAuthoringProjection.project(target, state.authoring(), state.baseline().document(),
          state.working().document(), state.patch(), () -> {}).compilePatch();
      UUID releaseId = UUID.randomUUID(), revisionId = UUID.randomUUID(), assignmentId = UUID.randomUUID(), definitionId = UUID.randomUUID();
      var selector = new UiLayoutAudienceSelectorCommand("tenant", "unit", null, null, null, null);
      var selected = new UiLayoutDraftWorkspaceDocument.TargetState(target, state.authoring(), state.patch(),
          state.baseline(), state.working(),
          new UiLayoutDraftWorkspaceDocument.RevisionSelection(revisionId, 1, hashes.sha256Exact(patch), state.patch().schemaVersion(), UUID.randomUUID()),
          new UiLayoutDraftWorkspaceDocument.AssignmentSelection(assignmentId, revisionId, "ORGANIZATION", selector, (short) 1, "main", UUID.randomUUID()), state.pageChildren());
      var sourceDraft = draft;
      sourceDraft.setState("RELEASED");
      sourceDraft.setDraftDocument(workspaceCodec.encode(new UiLayoutDraftWorkspaceDocument(
          UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, List.of(selected), UUID.randomUUID())));
      var release = UiLayoutRelease.builder().id(releaseId).tenantId("tenant").environment("test")
          .rootComponentType(target.componentType()).rootComponentId(target.componentId()).sourceDraftId(sourceDraft.getId()).build();
      when(releases.findByIdAndTenantIdAndEnvironment(releaseId,"tenant","test")).thenReturn(Optional.of(release));
      when(releases.findBySourceDraftId(sourceDraft.getId())).thenReturn(Optional.of(release));
      when(drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(sourceDraft.getId(),"tenant","test",target.componentType(),target.componentId())).thenReturn(Optional.of(sourceDraft));
      when(definitions.findById(definitionId)).thenReturn(Optional.of(UiLayoutDefinition.builder().id(definitionId).tenantId("tenant").environment("test")
          .componentType(target.componentType()).componentId(target.componentId()).schemaVersion(state.patch().schemaVersion()).build()));
      when(revisions.findById(revisionId)).thenReturn(Optional.of(UiLayoutRevision.builder().id(revisionId).definitionId(definitionId)
          .revisionNumber(1L).contentHash(hashes.sha256Exact(patch)).schemaVersion(state.patch().schemaVersion()).patchDocument(patch.toString()).build()));
      when(assignments.findById(assignmentId)).thenReturn(Optional.of(UiLayoutAssignmentRevision.builder().id(assignmentId).tenantId("tenant").environment("test")
          .revisionId(revisionId).layerClass("ORGANIZATION").selectorDocument(mapper.valueToTree(selector).toString()).priority((short)1).contributionKey("main").build()));
      when(members.findByReleaseIdOrderByMemberOrder(releaseId)).thenReturn(List.of(UiLayoutReleaseMember.builder().id(UUID.randomUUID())
          .releaseId(releaseId).memberOrder(0).componentType(target.componentType()).componentId(target.componentId())
          .contentRevisionId(revisionId).assignmentRevisionId(assignmentId).contributionKey("main").build()));
      when(jdbc.queryForObject(startsWith("select exists(select 1 from ui_layout_release_event"), any(MapSqlParameterSource.class), eq(Boolean.class)))
          .thenAnswer(call -> { record("PUBLISHED_EVENT_SQL_READ", false); return true; });
      return new PageSourceReference.Published(UUID.fromString(target.componentId()),releaseId,revisionId);
    }

    UiLayoutLifecycleInvocation invocation(UiLayoutTarget root) {
      return new UiLayoutLifecycleInvocation("alice", "tenant", "unit", "test", "c1", new UiLayoutCompositionRegistration(root, List.of(root)));
    }
    PageCreationContext context(UiLayoutLifecycleInvocation invocation) { return new PageCreationContext("app", "/pages", invocation); }
    void record(String boundary, boolean authority) {
      var path = StackWalker.getInstance().walk(stream -> stream
          .filter(frame -> frame.getClassName().startsWith("org.praxisplatform.config.service.")
              && !frame.getClassName().contains("PageCreationFullPathCostTest"))
          .map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
          .distinct().toList());
      String phase = path.stream().anyMatch(name -> name.endsWith(".beforeCommit")) ? "BEFORE_COMMIT" : "BODY";
      events.add(new Event(boundary, phase, path));
      if (authority && denyBeforeCommit && phase.equals("BEFORE_COMMIT"))
        throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Synthetic late denial");
    }
    List<String> query(String sql, MapSqlParameterSource p) {
      if (sql.startsWith("select e.*")) {
        record("LEDGER_SQL_READ", false);
        return ledgers.values().stream().filter(raw -> {
          try {
            var row = mapper.readTree(raw);
            return p.hasValue("page") ? row.path("page_id").asText().equals(p.getValue("page").toString())
                : row.path("creation_key").asText().equals(p.getValue("key").toString());
          } catch (Exception e) { throw new AssertionError(e); }
        }).toList();
      }
      if (sql.startsWith("SELECT draft_document::text")) {
        record("METADATA_OWNER_SQL_READ", false); return List.of(draftRows.get(p.getValue("draft")).getDraftDocument());
      }
      if (sql.startsWith("SELECT * FROM ui_layout_baseline_metadata_evidence")) {
        record("METADATA_SQL_READ", false); return List.of(metadataRows.get(p.getValue("component_id").toString()));
      }
      throw new AssertionError("Unmodeled SQL query: " + sql);
    }
    int update(String sql, MapSqlParameterSource p) throws Exception {
      if (sql.startsWith("insert into ui_page_catalog")) { record("CATALOG_SQL_WRITE", false); return 1; }
      if (sql.startsWith("insert into ui_page_creation_evidence")) {
        record("LEDGER_SQL_WRITE", false);
        var row = mapper.createObjectNode();
        String[][] mapping = {{"page_id","page"},{"draft_id","draft"},{"creation_key","key"},{"application_id","app"},
            {"route_namespace","namespace"},{"tenant_id","tenant"},{"environment","env"},{"actor_ref","actor"},
            {"command_hash","commandHash"},{"evidence_hash","evidenceHash"},{"evidence_text","evidence"}};
        for (var pair : mapping) row.put(pair[0], p.getValue(pair[1]).toString());
        ledger = row.toString(); ledgers.put(row.path("page_id").asText(), ledger); return 1;
      }
      if (sql.startsWith("INSERT INTO ui_layout_baseline_metadata_evidence")) {
        record("METADATA_SQL_WRITE", false); var row = mapper.createObjectNode();
        p.getValues().forEach((key,value) -> row.put(key, value == null ? null : value.toString()));
        metadataRows.put(p.getValue("component_id").toString(), row.toString()); return 1;
      }
      throw new AssertionError("Unmodeled SQL update: " + sql);
    }
    Map<String,Long> counts() {
      var counts = new TreeMap<String,Long>(); events.forEach(event -> counts.merge(event.boundary(), 1L, Long::sum)); return counts;
    }
    void writeReport() throws Exception { writeReport("native"); }
    void writeReport(String sourceKind) throws Exception {
      long units = events.stream().filter(event -> event.boundary().startsWith("HOST_")
          || event.boundary().startsWith("LIFECYCLE_") || event.boundary().startsWith("PAGE_LIFECYCLE_") || event.boundary().startsWith("NATIVE_")).count();
      var report = new LinkedHashMap<String,Object>();
      report.put("kind", "synthetic-complete-canonical-operation-not-latency-proof");
      report.put("baseline", "v0.1.0-rc.174 + capture checkpoint using joint creation admission"); report.put("nativeChildren", 1); report.put("sourceKind", sourceKind);
      report.put("authorityUnits", units); report.put("modeledCurrentObservationsAtTwoPerUnit", 2 * units);
      report.put("counts", counts()); report.put("events", events);
      report.put("modeledAuthorityMillisAt250PerUnit", 250 * units);
      var output = Path.of("target/page-creation-" + sourceKind + "-full-path-cost.json"); Files.createDirectories(output.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      System.out.println("Synthetic canonical create: " + units + " authority units; " + counts());
    }
  }
}
