package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** JDBC boundary simulation only. Does not prove PostgreSQL triggers, isolation or actual rollback. */
@Tag("unit")
class JdbcUiLayoutMetadataCaptureStoreTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutMetadataCaptureCodec codec = new UiLayoutMetadataCaptureCodec(mapper, hashes);
  private final DataSource dataSource = mock(DataSource.class);
  private final Connection connection = mock(Connection.class);
  private final ConnectionHolder holder = new ConnectionHolder(connection);
  private final SimpleTransactionStatus status = new SimpleTransactionStatus();
  private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
  private final UUID draft = UUID.randomUUID();
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  private final UiLayoutTarget child = new UiLayoutTarget("praxis-table", "items");
  private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "unit", "lab", "ctx",
      new UiLayoutCompositionRegistration(root, List.of(root, child)));
  private final List<Map<String, Object>> rows = new ArrayList<>();
  private JdbcUiLayoutMetadataCaptureStore store;
  private String workspace;
  private boolean draftAvailable = true;

  @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    when(jdbc.getJdbcTemplate()).thenReturn(new JdbcTemplate(dataSource));
    holder.setSynchronizedWithTransaction(true);
    TransactionSynchronizationManager.bindResource(dataSource, holder);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var seeds = List.of(seed(root), seed(child));
    var workspaceCodec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
    workspace = workspaceCodec.encode(workspaceCodec.fromSeed(invocation, new UiLayoutDraftWorkspaceSeed(seeds)));
    store = new JdbcUiLayoutMetadataCaptureStore(jdbc, mapper, hashes);
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
      var parameters = (SqlParameterSource) call.getArgument(1);
      var row = new LinkedHashMap<String, Object>();
      for (String name : parameters.getParameterNames()) row.put(name, parameters.getValue(name));
      if (rows.stream().anyMatch(existing -> existing.get("component_id").equals(row.get("component_id")))) {
        throw new DuplicateKeyException("private SQL body and credentials");
      }
      rows.add(row);
      return 1;
    });
    when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      RowMapper<?> reader = call.getArgument(2);
      if (sql.contains("FROM ui_layout_draft")) {
        if (!draftAvailable) return List.of();
        var row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(workspace);
        return List.of(reader.mapRow(row, 0));
      }
      var parameters = (SqlParameterSource) call.getArgument(1);
      var results = new ArrayList<Object>();
      for (var values : rows) {
        if (!values.get("component_id").equals(parameters.getValue("component_id"))) continue;
        var row = mock(ResultSet.class);
        when(row.getString(anyString())).thenAnswer(get -> {
          Object value = values.get(get.getArgument(0)); return value == null ? null : value.toString();
        });
        when(row.getInt(anyString())).thenAnswer(get -> {
          Object value = values.get(get.getArgument(0)); return value == null ? 0 : ((Number) value).intValue();
        });
        when(row.getObject(anyString(), eq(UUID.class))).thenAnswer(get -> values.get(get.getArgument(0)));
        results.add(reader.mapRow(row, results.size()));
      }
      return results;
    });
  }

  @AfterEach void release() {
    if (TransactionSynchronizationManager.hasResource(dataSource)) TransactionSynchronizationManager.unbindResource(dataSource);
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private UiLayoutDraftWorkspaceSeed.TargetSeed seed(UiLayoutTarget target) {
    return new UiLayoutDraftWorkspaceSeed.TargetSeed(target,
        new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "urn:table", "1"),
        new UiLayoutPatchDocumentDescriptor("urn:patch", "1"), "source:" + target.componentId(),
        mapper.createObjectNode().put("kind", "praxis.table.editor").put("version", 1), UiLayoutMetadataTestFixtures.metadata());
  }

  private UiLayoutMetadataCapture capture(UiLayoutTarget target) {
    var seed = seed(target);
    var binding = new UiLayoutMetadataCapture.Binding(draft,
        new UiLayoutMetadataCapture.Scope("tenant", "lab", root, target), seed.sourceRef(), seed.authoring(), hashes.sha256Exact(seed.document()));
    return codec.seal(UUID.randomUUID(), binding, seed.document(),
        new UiLayoutBaselineMetadataSeed.OperationSource("service", "resource", "/orders", "GET", "response", "urn:response", "producer", "publication"),
        new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector", UiLayoutMetadataTestFixtures.assembly()),
        new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "ctx", "policy", "revision", Instant.parse("2026-10-02T12:00:00.123456789Z")),
        " \n{\"properties\":{\"b\":{},\"a\":{}},\"title\":\"Ação 😀\"} \t");
  }

  private List<UiLayoutMetadataCapture> values() { return List.of(capture(root), capture(child)); }
  private void append() { store.append(status, invocation, draft, values(), (current, value) -> {}); }
  private void assertCode(Runnable work, UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(work::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        exception -> { assertThat(exception.getCode()).isEqualTo(code); assertThat(exception.getCause()).isNull(); });
  }

  private UiLayoutMetadataCapture nativeCapture(UiLayoutTarget target) {
    var original = capture(target);
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(original.baselineDocument(), "actor", "unit", "ctx");
    return codec.seal(original.captureRef(), original.binding(), original.baselineDocument(), metadata.source(), metadata.reproduction(),
        metadata.observation(), metadata.rawInputText());
  }

  @Test void corporateSortedMapperCannotReorderNativeBaselineAtPersistence() {
    var sorted = mapper.copy().configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.WRITE_PROPERTIES_SORTED, true);
    store = new JdbcUiLayoutMetadataCaptureStore(jdbc, sorted, hashes);
    var original = capture(root);
    var baseline = mapper.createObjectNode().put("version", 1).put("kind", "praxis.table.editor");
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline, "actor", "unit", "ctx");
    var value = codec.seal(original.captureRef(), original.binding(), baseline, metadata.source(), metadata.reproduction(),
        metadata.observation(), metadata.rawInputText());
    store.append(status, invocation, draft, List.of(value, capture(child)), (current, capture) -> {});
    assertThat(store.read(invocation, value.binding(), (current, capture) -> {})).isEqualTo(value);
    assertThat(rows.getFirst().get("baseline_document_text")).isEqualTo(value.baselineDocument().toString());
  }

  @Test void storedBaselineRejectsHostPermissiveJsonBeforeAdmission() {
    var permissive = mapper.copy().configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    store = new JdbcUiLayoutMetadataCaptureStore(jdbc, permissive, hashes);
    append();
    String original = (String) rows.getFirst().get("baseline_document_text");
    rows.getFirst().put("baseline_document_text", original.replace('"', '\''));
    assertCode(() -> store.read(invocation, capture(root).binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test void roundTripsMixedOriginsUnderOneCompositionAndQuota() {
    var nativeValue = nativeCapture(root);
    var structuralValue = capture(child);
    store.append(status, invocation, draft, List.of(nativeValue, structuralValue), (current, value) -> {});
    assertThat(store.read(invocation, nativeValue.binding(), (current, value) -> {})).isEqualTo(nativeValue);
    assertThat(store.read(invocation, structuralValue.binding(), (current, value) -> {})).isEqualTo(structuralValue);
    assertThat(rows.getFirst().get("operation_path")).isNull();
    assertThat(rows.getFirst().get("normalizer_ref")).isNull();
  }

  @ParameterizedTest @ValueSource(strings = {"operation_path", "normalizer_ref", "schema_type", "assembler_ref", "assembly_input_text", "assembly_input_hash", "assembly_input_version"})
  void rejectsNativeRowsContainingStructuralDeclarations(String column) {
    var nativeValue = nativeCapture(root);
    store.append(status, invocation, draft, List.of(nativeValue, capture(child)), (current, value) -> {});
    rows.getFirst().put(column, "fabricated");
    assertCode(() -> store.read(invocation, nativeValue.binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test void rejectsUnknownOriginAndNativeFieldsOnOperationRow() {
    append();
    rows.getFirst().put("origin_kind", "UNKNOWN");
    assertCode(() -> store.read(invocation, capture(root).binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
    rows.getFirst().put("origin_kind", "OPERATION_SCHEMA");
    rows.getFirst().put("native_document_ref", "foreign");
    assertCode(() -> store.read(invocation, capture(root).binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test void roundTripsRawUtf8AndNanosecondsAndExactBinding() {
    var values = values();
    var checks = new AtomicInteger();
    store.append(status, invocation, draft, values, (current, value) -> checks.incrementAndGet());
    assertThat(checks.get()).isEqualTo(8);
    var read = store.read(invocation, values.getFirst().binding(), (current, value) -> checks.incrementAndGet());
    assertThat(read).isEqualTo(values.getFirst());
    assertThat(checks.get()).isEqualTo(10);
    assertThat(holder.isRollbackOnly()).isFalse();
  }

  @Test void rejectsMissingTransactionWithoutQuery() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    assertThat(rows).isEmpty();
  }

  @Test void rejectsMissingWriterStatusBeforeFirstQuery() {
    assertCode(() -> store.append(null, invocation, draft, values(), (current, value) -> {}),
        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    assertThat(holder.isRollbackOnly()).isTrue();
  }

  @Test void rejectsCompletedWriterStatusBeforeFirstQuery() {
    status.setCompleted();
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    assertThat(rows).isEmpty();
  }

  @Test void rejectsTransactionOfAnotherDatasource() {
    TransactionSynchronizationManager.unbindResource(dataSource);
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(rows).isEmpty();
  }

  @Test void rejectsUnsynchronizedConnectionHolder() {
    holder.setSynchronizedWithTransaction(false);
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(rows).isEmpty();
  }

  @Test void rejectsRepeatableReadQuotaSnapshot() throws Exception {
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(rows).isEmpty();
  }

  @Test void requiresPersistedEditableScopedDraft() {
    draftAvailable = false;
    assertCode(this::append, UiLayoutLifecycleException.Code.DENIED);
    assertThat(rows).isEmpty();
    assertThat(holder.isRollbackOnly()).isTrue();
    assertThat(status.isRollbackOnly()).isTrue();
  }

  @Test void rejectsPartialCompositionBeforeFirstInsert() {
    assertCode(() -> store.append(status, invocation, draft, List.of(capture(root)), (current, value) -> {}),
        UiLayoutLifecycleException.Code.INVALID_STATE);
    assertThat(rows).isEmpty();
  }

  @Test void rejectsTargetOrderDivergenceBeforeFirstInsert() {
    assertCode(() -> store.append(status, invocation, draft, List.of(capture(child), capture(root)), (current, value) -> {}),
        UiLayoutLifecycleException.Code.INVALID_STATE);
    assertThat(rows).isEmpty();
  }

  @Test void rejectsCorruptPersistedBaselineBeforeFirstInsert() {
    workspace = workspace.replace("source:orders", "different-source");
    assertCode(this::append, UiLayoutLifecycleException.Code.INVALID_STATE);
    assertThat(rows).isEmpty();
  }

  @Test void deniesAbsentContentAuthorizationBeforeFirstInsert() {
    assertCode(() -> store.append(status, invocation, draft, values(), null), UiLayoutLifecycleException.Code.DENIED);
    assertThat(rows).isEmpty();
  }

  @Test void persistsAndReadsExactAssemblyInputsWithoutTouchingSchema() {
    append();
    var stored = store.read(invocation, capture(root).binding(), (current, value) -> {});
    assertThat(((UiLayoutBaselineMetadataSeed.SchemaProjection) stored.reproduction()).assembly())
        .isEqualTo(UiLayoutMetadataTestFixtures.assembly());
    assertThat(rows.getFirst().get("assembly_input_text")).isEqualTo(UiLayoutMetadataTestFixtures.assembly().inputText());
    assertThat(rows.getFirst().get("assembly_input_hash")).isEqualTo(stored.assemblyInputHash());
    assertThat(rows.getFirst().get("assembly_input_version")).isEqualTo(1);
    assertThat(stored.rawInputText()).isEqualTo(capture(root).rawInputText());
  }

  @ParameterizedTest @ValueSource(strings = {"assembly_input_version", "assembler_ref", "assembly_input_text", "assembly_input_hash"})
  void rejectsIncompleteHistoricalAssemblyWithoutBackfill(String column) {
    append(); rows.getFirst().put(column, null);
    assertCode(() -> store.read(invocation, capture(root).binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
    verify(jdbc, times(2)).update(anyString(), any(SqlParameterSource.class));
  }

  @Test void rejectsStoredAssemblyCorruption() {
    append(); rows.getFirst().put("assembly_input_text", "{\"changed\":true}");
    assertCode(() -> store.read(invocation, capture(root).binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test void requiresCurrentObservationContextAtAppend() {
    var original = capture(root);
    var stale = new UiLayoutMetadataCapture(original.captureRef(), original.binding(), original.baselineDocument(), original.source(),
        original.reproduction(), new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "old-context", "policy", "revision",
        original.observation().capturedAt()), original.rawInputText(), original.rawInputHash(), original.assemblyInputHash());
    assertCode(() -> store.append(status, invocation, draft, List.of(stale, capture(child)), (current, value) -> {}),
        UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    assertThat(rows).isEmpty();
  }

  @Test void poisonsTransactionWhenSecondInsertFailsEvenIfCallerCatches() {
    var attempts = new AtomicInteger();
    when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
      if (attempts.incrementAndGet() == 2) throw new DuplicateKeyException("private-schema-title");
      return 1;
    });
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(holder.isRollbackOnly()).isTrue();
    assertThat(status.isRollbackOnly()).isTrue();
  }

  @Test void finalTechnicalFailurePoisonsTransactionAndRedactsProviderFailure() {
    var checks = new AtomicInteger();
    assertCode(() -> store.append(status, invocation, draft, values(), (current, value) -> {
      if (checks.incrementAndGet() == 5) throw new IllegalStateException("private resource credential");
    }), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(holder.isRollbackOnly()).isTrue();
    assertThat(status.isRollbackOnly()).isTrue();
  }

  @Test void explicitFinalRevocationPoisonsTransactionWithoutBecomingTechnicalFailure() {
    var checks = new AtomicInteger();
    assertCode(() -> store.append(status, invocation, draft, values(), (current, value) -> {
      if (checks.incrementAndGet() == 5) throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.DENIED, "private policy revocation");
    }), UiLayoutLifecycleException.Code.DENIED);
    assertThat(holder.isRollbackOnly()).isTrue();
    assertThat(status.isRollbackOnly()).isTrue();
  }

  @Test void collisionNeverOverwritesOrRecapturesStoredEvidence() {
    append();
    var saved = List.copyOf(rows);
    assertCode(this::append, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(rows).containsExactlyElementsOf(saved);
    assertThat(holder.isRollbackOnly()).isTrue();
    assertThat(status.isRollbackOnly()).isTrue();
  }

  @Test void missingHistoricalRowDoesNotFallBackToCurrentMetadata() {
    assertCode(() -> store.read(invocation, capture(root).binding(), (current, value) -> {}),
        UiLayoutLifecycleException.Code.INVALID_STATE);
    verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
  }

  @ParameterizedTest @ValueSource(strings = {"raw_schema_text", "raw_schema_hash", "baseline_document_text", "tenant_id",
      "document_type", "component_id", "captured_at_text", "source_draft_ref"})
  void refusesCorruptOrNormalizedStoredFields(String column) {
    var original = capture(root);
    store.append(status, invocation, draft, List.of(original, capture(child)), (current, value) -> {});
    Object invalid = switch (column) {
      case "raw_schema_text" -> "{\"private\":1,\"private\":2}";
      case "raw_schema_hash" -> "0".repeat(64);
      case "baseline_document_text" -> "{\"kind\":\"private\"}";
      case "document_type" -> " praxis.table.editor ";
      case "component_id" -> " orders ";
      case "captured_at_text" -> "private-time";
      case "source_draft_ref" -> UUID.randomUUID();
      default -> "another-tenant";
    };
    rows.getFirst().put(column, invalid);
    assertCode(() -> store.read(invocation, original.binding(), (current, value) -> {}),
        UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test void crossTenantReadDeniesBeforeQuery() {
    var other = new UiLayoutLifecycleInvocation("actor", "other-tenant", "unit", "lab", "ctx", invocation.composition());
    assertCode(() -> store.read(other, capture(root).binding(), (current, value) -> {}), UiLayoutLifecycleException.Code.DENIED);
    verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
  }

  @Test void historicalReadUsesCurrentAuthorizationNotHistoricalContext() {
    var original = capture(root);
    store.append(status, invocation, draft, List.of(original, capture(child)), (current, value) -> {});
    var current = new UiLayoutLifecycleInvocation("another-actor", "tenant", "other-unit", "lab", "new-context", invocation.composition());
    var read = store.read(current, original.binding(), (context, value) -> assertThat(context).isEqualTo(current));
    assertThat(read.observation().contextVersion()).isEqualTo("ctx");
    assertCode(() -> store.read(current, original.binding(), null), UiLayoutLifecycleException.Code.DENIED);
  }
}
