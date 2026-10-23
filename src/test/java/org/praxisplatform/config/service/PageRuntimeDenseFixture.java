package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.praxisplatform.config.dto.*;
import org.springframework.jdbc.core.namedparam.*;

/** Synthetic correlated bulk records, not 1000 HTTP/authoring journeys. Runtime grants and all DDL stay active. */
final class PageRuntimeDenseFixture {
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(PageRuntimePostgresFixture.JSON);
  private final PageCreationCodec evidenceCodec = new PageCreationCodec();
  private final UiLayoutMetadataCaptureCodec captures = new UiLayoutMetadataCaptureCodec(PageRuntimePostgresFixture.JSON, hashes);
  private final UiLayoutDraftWorkspaceCodec workspaces = new UiLayoutDraftWorkspaceCodec(PageRuntimePostgresFixture.JSON, hashes);
  private final PageCatalogLifecycleBridge outputProducer = new PageCatalogLifecycleBridge(null, null, evidenceCodec, hashes,
      null, null, null, null, null, PageRuntimePostgresFixture.JSON, null);
  private final PageRuntimeHostFixture host;
  private final PageCreationEvidence prototype;
  private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(PageRuntimePostgresFixture.dataSource);
  private final List<UUID> pageIds;
  private final List<Rows> rows = new ArrayList<>();
  long workspaceEncodedInputBytes;
  long ledgerStoredBytes;
  long rootMetadataBytes;

  PageRuntimeDenseFixture(PageRuntimeHostFixture host, PageCreationEvidence prototype,
      UUID keptGroup, UUID removedGroup, int count) throws Exception {
    this.host = host;
    this.prototype = prototype;
    assertThat(prototype.observation()).isEqualTo(host.context(host.alice, prototype.observation().invocation().rootTarget()));
    assertThat(prototype.observation().invocation().tenantId()).isEqualTo(host.scope.tenant());
    assertThat(prototype.observation().invocation().environment()).isEqualTo(host.scope.environment());
    evidenceCodec.require(prototype.command());
    pageIds = java.util.stream.IntStream.range(0, count).mapToObj(ignored -> UUID.randomUUID())
        .sorted(Comparator.comparing(UUID::toString)).toList();
    for (int index = 0; index < count; index++) rows.add(build(pageIds.get(index), index == count - 1 ? removedGroup : keptGroup, index == count - 1 ? 0 : 9));
  }

  List<UUID> pageIds() { return pageIds; }

  UUID releaseId(int index) { return (UUID) rows.get(index).tables.get("ui_layout_release").get("id"); }

  /** Deliberate negative storage fixture, never used by the 1000 valid-page density seed. */
  void malformedFrozenWorkspaceForSelectionProbe(int index, int postgresqlTextBytes) {
    var row = rows.get(index);
    workspaceEncodedInputBytes -= row.frozenDocument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    // PostgreSQL renders this object as {"padding": "..."}: 15 UTF-8 bytes of envelope.
    row.frozenDocument = "{\"padding\":\"" + "x".repeat(postgresqlTextBytes - 15) + "\"}";
    workspaceEncodedInputBytes += row.frozenDocument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  /** V65 permits a legacy release without source_draft_id. Selection must not hide it. */
  void missingSourceDraftForSelectionProbe(int index) {
    rows.get(index).tables.get("ui_layout_release").put("source_draft_id", null);
  }

  /** Deliberate invalid metadata evidence; the workspace, ledger and frozen revision remain correlated. */
  void corruptRootMetadataRawHashForAdmissionProbe(int index) {
    rows.get(index).tables.get("ui_layout_baseline_metadata_evidence").put("raw_schema_hash", "0".repeat(64));
  }

  void insert() {
    long started = System.nanoTime();
    for (int offset = 0; offset < rows.size(); offset += 50) {
      var chunk = rows.subList(offset, Math.min(offset + 50, rows.size()));
      PageRuntimePostgresFixture.transactions.executeWithoutResult(status -> {
        for (String table : List.of("ui_layout_draft", "ui_page_catalog", "ui_page_creation_evidence", "ui_layout_definition",
            "ui_layout_revision", "ui_layout_assignment_revision", "ui_layout_baseline_metadata_evidence",
            "ui_layout_release", "ui_layout_release_member", "ui_layout_release_review", "ui_layout_release_approval")) {
          insertTable(table, chunk.stream().map(row -> row.tables.get(table)).toList());
        }
        var params = chunk.stream().map(row -> new MapSqlParameterSource(row.tables.get("ui_layout_draft")).addValue("frozen_document", row.frozenDocument).addValue("frozen_etag", row.frozenEtag))
            .toArray(MapSqlParameterSource[]::new);
        for (int updated : jdbc.batchUpdate("UPDATE ui_layout_draft SET state='RELEASED',draft_document=CAST(:frozen_document AS jsonb),draft_etag=:frozen_etag,row_version=1 WHERE id=:id AND state='DRAFT'", params))
          assertThat(updated).isEqualTo(1);
        insertTable("ui_layout_release_head", chunk.stream().map(row -> row.tables.get("ui_layout_release_head")).toList());
        insertTable("ui_layout_release_event", chunk.stream().flatMap(row -> row.events.stream()).toList());
      });
    }
    var scoped = new MapSqlParameterSource("tenant", host.scope.tenant()).addValue("env", host.scope.environment())
        .addValue("pages", pageIds.stream().map(UUID::toString).toList());
    var counts = jdbc.query("SELECT event_type,count(*) AS n FROM ui_layout_release_event WHERE tenant_id=:tenant AND environment=:env AND root_component_type='praxis-dynamic-page' AND root_component_id IN (:pages) GROUP BY event_type",
        scoped, (row, index) -> Map.entry(row.getString("event_type"), row.getLong("n")));
    assertThat(counts).containsExactlyInAnyOrder(Map.entry("RELEASE_CREATED", (long) pageIds.size()),
        Map.entry("RELEASE_SUBMITTED", (long) pageIds.size()), Map.entry("RELEASE_APPROVED", (long) pageIds.size()),
        Map.entry("PUBLISHED", (long) pageIds.size()));
    assertThat(jdbc.queryForObject("""
        SELECT count(*) FROM ui_layout_release_head h JOIN ui_layout_release_review r ON r.release_id=h.active_release_id
          JOIN ui_layout_release_approval a ON a.release_id=h.active_release_id
        WHERE h.tenant_id=:tenant AND h.environment=:env AND h.root_component_type='praxis-dynamic-page'
          AND h.root_component_id IN (:pages) AND r.state='APPROVED'
        """, scoped, Long.class)).isEqualTo((long) pageIds.size());
    assertThat(jdbc.queryForObject("""
        SELECT count(*) FROM ui_layout_release_event e JOIN ui_layout_release_head h
          ON h.tenant_id=e.tenant_id AND h.environment=e.environment AND h.root_component_type=e.root_component_type AND h.root_component_id=e.root_component_id
        WHERE e.tenant_id=:tenant AND e.environment=:env AND e.root_component_type='praxis-dynamic-page' AND e.root_component_id IN (:pages)
          AND e.from_release_id IS NULL AND (
            (e.event_type='RELEASE_CREATED' AND e.to_release_id IS NULL AND e.head_etag IS NULL) OR
            (e.event_type IN ('RELEASE_SUBMITTED','RELEASE_APPROVED') AND e.to_release_id=h.active_release_id AND e.head_etag IS NULL) OR
            (e.event_type='PUBLISHED' AND e.to_release_id=h.active_release_id AND e.head_etag=h.head_etag))
        """, scoped, Long.class)).isEqualTo(4L * pageIds.size());
    System.out.println("P3-B synthetic seed pages=" + rows.size() + " workspaceEncodedInputBytes=" + workspaceEncodedInputBytes + " ledgerStoredBytes=" + ledgerStoredBytes + " rootMetadataBytes=" + rootMetadataBytes
        + " wallMs=" + (System.nanoTime() - started) / 1_000_000 + " runtime=true triggers=true");
    rows.clear(); // Seed preparation retains strings; the measured scan starts after releasing these references.
  }

  private void insertTable(String table, List<Map<String, Object>> values) {
    var columns = values.getFirst().keySet();
    var json = Set.of("draft_document", "patch_document", "selector_document");
    String sql = "INSERT INTO " + table + "(" + String.join(",", columns) + ") VALUES ("
        + String.join(",", columns.stream().map(column -> json.contains(column) ? "CAST(:" + column + " AS jsonb)" : ":" + column).toList()) + ")";
    var params = values.stream().map(MapSqlParameterSource::new).toArray(MapSqlParameterSource[]::new);
    for (int inserted : jdbc.batchUpdate(sql, params)) assertThat(inserted).isEqualTo(1);
  }

  private Rows build(UUID pageId, UUID groupId, int bindingOrder) throws Exception {
    UUID draftId = UUID.randomUUID(), definitionId = UUID.randomUUID(), revisionId = UUID.randomUUID(), assignmentId = UUID.randomUUID();
    UUID releaseId = UUID.randomUUID(), headEtag = UUID.randomUUID();
    Instant now = Instant.now();
    String title = "Synthetic page " + pageId;
    var command = new PageCreationCommand(UUID.randomUUID(), prototype.command().source(), title);
    var transformed = new PageCopyTransformation(hashes).allocate(prototype.original().document(),
        UUID.fromString(prototype.original().baseline().target().componentId()), pageId, title);
    var evidence = new PageCreationEvidence(pageId, draftId, prototype.observation(), command, prototype.original(),
        transformed.sourceHash(), transformed.outputHash(), PageCopyTransformation.TRANSFORM, transformed.identityMap(), now);
    var current = host.context(host.alice, evidence.target()).invocation();
    var seed = outputProducer.outputSeed(evidence, current);
    var baseline = seed.document();
    ObjectNode candidate = baseline.deepCopy();
    ((ObjectNode) candidate.path("config")).putArray("navigation").addObject().put("id", UUID.randomUUID().toString())
        .put("groupId", groupId.toString()).put("label", "Synthetic binding").put("icon", "home").put("order", bindingOrder);
    var patch = UiLayoutAuthoringProjection.project(seed.target(), seed.authoring(), baseline, candidate, seed.patch(), () -> {}).compilePatch();
    String patchHash = hashes.sha256Exact(patch);
    var children = new ArrayList<UiLayoutPageChildEvidence>();
    for (var child : seed.pageChildren()) {
      var target = new UiLayoutTarget("praxis.table.editor".equals(child.authoring().documentType()) ? "praxis-table" : "praxis-dynamic-form", child.request().widgetKey());
      var capture = seal(current, draftId, target, child.sourceRef(), child.authoring(), child.document(), child.metadata());
      children.add(new UiLayoutPageChildEvidence(child.request(), child.patch(), UiLayoutPageChildCaptures.evidenceMapper().writeValueAsString(capture)));
    }
    var selector = new UiLayoutAudienceSelectorCommand(host.scope.tenant(), null, null, null, null, null);
    var state = new UiLayoutDraftWorkspaceDocument.TargetState(seed.target(), seed.authoring(), seed.patch(),
        new UiLayoutDraftWorkspaceDocument.BaselineDocument(seed.sourceRef(), hashes.sha256Exact(baseline), baseline),
        new UiLayoutDraftWorkspaceDocument.WorkingDocument(hashes.sha256Exact(candidate), candidate),
        new UiLayoutDraftWorkspaceDocument.RevisionSelection(revisionId, 1, patchHash, seed.patch().schemaVersion(), UUID.randomUUID()),
        new UiLayoutDraftWorkspaceDocument.AssignmentSelection(assignmentId, revisionId, "TENANT", selector, (short) 0, "synthetic", UUID.randomUUID()), children);
    var workspace = new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, List.of(state), UUID.randomUUID());
    String document = workspaces.encode(workspace);
    workspaceEncodedInputBytes += document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    workspaces.decode(document, current.composition());
    new UiLayoutPageChildCaptures(hashes).historicalIntegrity(draftId, seed.target(), baseline, candidate, children);
    UiLayoutPageRevisionReproduction.require(seed.target(), seed.authoring(), baseline, candidate, seed.patch(), patch);
    assertThat(evidenceCodec.read(evidenceCodec.write(evidence)).identityMap()).isEqualTo(evidence.identityMap());
    var rootCapture = seal(current, draftId, seed.target(), seed.sourceRef(), seed.authoring(), baseline, seed.metadata());
    ledgerStoredBytes += evidenceCodec.write(evidence).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    rootMetadataBytes += rootCapture.rawInputText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    var rows = new Rows();
    rows.frozenDocument = document;
    rows.frozenEtag = UUID.randomUUID();
    var initialState = new UiLayoutDraftWorkspaceDocument.TargetState(seed.target(), seed.authoring(), seed.patch(), state.baseline(),
        new UiLayoutDraftWorkspaceDocument.WorkingDocument(hashes.sha256Exact(baseline), baseline), null, null, children);
    String initialDocument = workspaces.encode(new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, List.of(initialState), null));
    var time = java.sql.Timestamp.from(now);
    rows.put("ui_layout_draft", root(pageId, "id", draftId, "draft_document", initialDocument, "draft_etag", UUID.randomUUID(), "state", "DRAFT",
        "created_by", host.alice.name(), "created_at", time, "updated_at", time, "row_version", 0L, "creation_idempotency_key", command.creationKey().toString()));
    rows.put("ui_page_catalog", root(pageId, "page_id", pageId, "application_id", host.scope.application(), "route_namespace", host.scope.namespace(), "created_at", time));
    rows.put("ui_page_creation_evidence", map("page_id", pageId, "application_id", host.scope.application(), "tenant_id", host.scope.tenant(), "environment", host.scope.environment(),
        "actor_ref", host.alice.name(), "creation_key", command.creationKey(), "draft_id", draftId, "command_hash", hashes.sha256Exact(evidenceCodec.tree(command)),
        "evidence_hash", hashes.sha256Exact(evidenceCodec.tree(evidence)), "evidence_text", evidenceCodec.write(evidence)));
    rows.put("ui_layout_definition", map("id", definitionId, "tenant_id", host.scope.tenant(), "environment", host.scope.environment(), "component_type", seed.target().componentType(),
        "component_id", pageId.toString(), "schema_version", seed.patch().schemaVersion(), "created_by", host.alice.name(), "created_at", time));
    rows.put("ui_layout_revision", map("id", revisionId, "definition_id", definitionId, "revision_number", 1L, "patch_document", patch.toString(),
        "content_hash", patchHash, "schema_version", seed.patch().schemaVersion(), "created_by", host.alice.name(), "created_reason", "Synthetic density seed", "created_at", time));
    rows.put("ui_layout_assignment_revision", map("id", assignmentId, "tenant_id", host.scope.tenant(), "environment", host.scope.environment(), "revision_id", revisionId,
        "layer_class", "TENANT", "selector_document", PageRuntimePostgresFixture.JSON.writeValueAsString(selector), "priority", (short)0,
        "contribution_key", "synthetic", "created_by", host.alice.name(), "created_at", time));
    rows.put("ui_layout_baseline_metadata_evidence", metadata(rootCapture));
    rows.put("ui_layout_release", root(pageId, "id", releaseId, "source_draft_id", draftId, "created_by", host.alice.name(), "created_at", time));
    rows.put("ui_layout_release_member", map("id", UUID.randomUUID(), "release_id", releaseId, "member_order", 0, "component_type", seed.target().componentType(),
        "component_id", pageId.toString(), "assignment_revision_id", assignmentId, "content_revision_id", revisionId, "contribution_key", "synthetic"));
    rows.put("ui_layout_release_review", map("id", UUID.randomUUID(), "release_id", releaseId, "state", "APPROVED", "review_etag", UUID.randomUUID(),
        "submitted_by", host.alice.name(), "submitted_at", java.sql.Timestamp.from(now.plusMillis(1)), "row_version", 2L));
    rows.put("ui_layout_release_approval", map("id", UUID.randomUUID(), "release_id", releaseId, "actor", host.alice.name(), "reason", "Synthetic density seed", "approved_at", java.sql.Timestamp.from(now.plusMillis(2))));
    rows.put("ui_layout_release_head", root(pageId, "id", UUID.randomUUID(), "active_release_id", releaseId, "head_etag", headEtag, "updated_at", java.sql.Timestamp.from(now.plusMillis(3)), "row_version", 0L));
    for (int index = 0; index < 4; index++) {
      String event = List.of("RELEASE_CREATED", "RELEASE_SUBMITTED", "RELEASE_APPROVED", "PUBLISHED").get(index);
      rows.events.add(root(pageId, "id", UUID.randomUUID(), "event_type", event, "from_release_id", null,
          "to_release_id", index == 0 ? null : releaseId, "head_etag", index == 3 ? headEtag : null,
          "actor", host.alice.name(), "reason", "Synthetic correlated history; not an HTTP journey",
          "created_at", java.sql.Timestamp.from(now.plusMillis(index))));
    }
    return rows;
  }

  private UiLayoutMetadataCapture seal(UiLayoutLifecycleInvocation invocation, UUID draft, UiLayoutTarget target, String source,
      UiLayoutAuthoringDocumentDescriptor descriptor, com.fasterxml.jackson.databind.JsonNode document, UiLayoutBaselineMetadataSeed metadata) {
    var binding = new UiLayoutMetadataCapture.Binding(draft,
        new UiLayoutMetadataCapture.Scope(invocation.tenantId(), invocation.environment(), invocation.rootTarget(), target), source, descriptor, hashes.sha256Exact(document));
    return captures.seal(UUID.randomUUID(), binding, document, metadata.source(), metadata.reproduction(), metadata.observation(), metadata.rawInputText());
  }

  private Map<String, Object> metadata(UiLayoutMetadataCapture capture) throws Exception {
    var binding = capture.binding(); var scope = binding.scope(); var observation = capture.observation();
    var source = (UiLayoutBaselineMetadataSeed.NativeDocumentSource) capture.source();
    return map("capture_ref", capture.captureRef(), "source_draft_ref", binding.sourceDraftRef(), "tenant_id", scope.tenantId(), "environment", scope.environment(),
        "root_component_type", scope.root().componentType(), "root_component_id", scope.root().componentId(), "component_type", scope.target().componentType(), "component_id", scope.target().componentId(),
        "baseline_source_ref", binding.baselineSourceRef(), "document_type", binding.authoring().documentType(), "authoring_schema_ref", binding.authoring().schemaRef(),
        "authoring_schema_version", binding.authoring().schemaVersion(), "baseline_content_hash", binding.baselineContentHash(), "baseline_document_text", capture.rawInputText(),
        "origin_kind", "NATIVE_DOCUMENT", "service_key", source.serviceKey(), "native_document_ref", source.documentRef(), "native_document_revision", source.documentRevision(),
        "producer_ref", source.producerRef(), "publication_ref", source.publicationRef(), "actor_ref", observation.actorRef(), "administrative_unit", observation.administrativeUnit(),
        "context_version", observation.contextVersion(), "policy_ref", observation.policyRef(), "policy_revision", observation.policyRevision(), "captured_at_text", observation.capturedAt().toString(),
        "raw_schema_text", capture.rawInputText(), "raw_schema_hash", capture.rawInputHash());
  }

  private Map<String, Object> root(UUID page, Object... fields) {
    var result = map("tenant_id", host.scope.tenant(), "environment", host.scope.environment(), "root_component_type", "praxis-dynamic-page", "root_component_id", page.toString());
    result.putAll(map(fields)); return result;
  }
  private static Map<String, Object> map(Object... fields) {
    var result = new LinkedHashMap<String, Object>();
    for (int index = 0; index < fields.length; index += 2) result.put((String) fields[index], fields[index + 1]);
    return result;
  }
  private static class Rows {
    String frozenDocument;
    UUID frozenEtag;
    final Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
    final List<Map<String, Object>> events = new ArrayList<>();
    void put(String table, Map<String, Object> row) { tables.put(table, row); }
  }
}
