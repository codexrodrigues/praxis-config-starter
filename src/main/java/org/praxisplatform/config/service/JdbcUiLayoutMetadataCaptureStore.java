package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal append-only Config store. No bean, public SPI, capture source or lifecycle wiring. */
final class JdbcUiLayoutMetadataCaptureStore implements UiLayoutMetadataCapturePersistence {
  private static final String TABLE = "ui_layout_baseline_metadata_evidence";
  private static final String SCOPE = "source_draft_ref=:source_draft_ref AND tenant_id=:tenant_id"
      + " AND environment=:environment AND root_component_type=:root_component_type"
      + " AND root_component_id=:root_component_id";
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final UiLayoutMetadataCaptureCodec captures;
  private final UiLayoutDraftWorkspaceCodec workspaces;

  JdbcUiLayoutMetadataCaptureStore(NamedParameterJdbcTemplate configJdbc, ObjectMapper mapper,
      CanonicalJsonHashService hashes) {
    this.jdbc = configJdbc;
    this.mapper = UiLayoutMetadataCaptureCodec.strictMapper(mapper);
    this.captures = new UiLayoutMetadataCaptureCodec(mapper, hashes);
    this.workspaces = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
  }

  /** Append once, before the draft creation transaction commits. Collisions never replace evidence. */
  @Override public void append(TransactionStatus writerStatus, UiLayoutLifecycleInvocation invocation, UUID draftRef,
      List<UiLayoutMetadataCapture> values, UiLayoutMetadataCaptureAccess access) {
    ConnectionHolder transaction = transaction();
    try {
      if (writerStatus == null || writerStatus.isCompleted() || writerStatus.isRollbackOnly() || transaction.isRollbackOnly()) {
        throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      }
      if (invocation == null || draftRef == null) throw failure(UiLayoutLifecycleException.Code.DENIED);
      var owner = new MapSqlParameterSource().addValue("draft", draftRef)
          .addValue("tenant", invocation.tenantId()).addValue("environment", invocation.environment())
          .addValue("root_type", invocation.rootTarget().componentType())
          .addValue("root_id", invocation.rootTarget().componentId()).addValue("actor", invocation.actorRef());
      var documents = jdbc.query("SELECT draft_document::text FROM ui_layout_draft WHERE id=:draft"
          + " AND tenant_id=:tenant AND environment=:environment AND root_component_type=:root_type"
          + " AND root_component_id=:root_id AND created_by=:actor AND state='DRAFT' FOR UPDATE",
          owner, (row, index) -> row.getString(1));
      if (documents.size() != 1) throw failure(UiLayoutLifecycleException.Code.DENIED);
      var workspace = workspaces.decode(documents.getFirst(), invocation.composition());
      captures.requireCompositionBudget(values);
      if (values.size() != workspace.targets().size()
          || !workspace.targets().stream().map(UiLayoutDraftWorkspaceDocument.TargetState::target).toList()
              .equals(invocation.composition().targets())) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      // Validate the complete set before the first insert, including exact B0/source/descriptor.
      for (int index = 0; index < values.size(); index++) {
        var capture = values.get(index);
        var target = workspace.targets().get(index);
        var expected = binding(invocation, draftRef, target);
        captures.verifyAndRead(invocation, expected, capture, access);
        if (!target.baseline().document().equals(capture.baselineDocument())
            || !invocation.actorRef().equals(capture.observation().actorRef())
            || !invocation.administrativeUnit().equals(capture.observation().administrativeUnit())
            || !invocation.contextVersion().equals(capture.observation().contextVersion())) {
          throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
        }
      }
      for (var capture : values) {
        var parameters = parameters(capture);
        var columns = new ArrayList<>(parameters.getValues().keySet());
        String sql = "INSERT INTO " + TABLE + " (" + String.join(",", columns) + ") VALUES ("
            + String.join(",", columns.stream().map(column -> ":" + column).toList()) + ")";
        if (jdbc.update(sql, parameters) != 1) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      }
      // Revocation after any insert poisons the entire Config transaction, even if a caller catches it.
      for (var capture : values) captures.verifyAndRead(invocation, capture.binding(), capture, access);
    } catch (RuntimeException exception) {
      transaction.setRollbackOnly();
      if (writerStatus != null && !writerStatus.isCompleted()) writerStatus.setRollbackOnly();
      if (exception instanceof UiLayoutLifecycleException lifecycle) throw lifecycle;
      throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    }
  }

  /** Exact historical read; no current api_metadata lookup, upsert, backfill or recapture. */
  @Override public UiLayoutMetadataCapture read(UiLayoutLifecycleInvocation invocation,
      UiLayoutMetadataCapture.Binding expected, UiLayoutMetadataCaptureAccess access) {
    transaction();
    try {
      requireScope(invocation, expected);
      var parameters = scopeParameters(expected);
      var stored = jdbc.query("SELECT * FROM " + TABLE + " WHERE " + SCOPE
          + " AND component_type=:component_type AND component_id=:component_id", parameters, rowMapper());
      if (stored.size() != 1) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
      return captures.verifyAndRead(invocation, expected, stored.getFirst(), access);
    } catch (UiLayoutLifecycleException exception) { throw exception; }
    catch (RuntimeException exception) { throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE); }
  }

  private ConnectionHolder transaction() {
    try {
      var source = jdbc.getJdbcTemplate().getDataSource();
      if (source == null || !TransactionSynchronizationManager.isActualTransactionActive()
          || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
          || !holder.isSynchronizedWithTransaction()
          || holder.getConnection().getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      }
      return holder;
    } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE); }
  }

  private static void requireScope(UiLayoutLifecycleInvocation invocation, UiLayoutMetadataCapture.Binding expected) {
    if (invocation == null || expected == null || !invocation.tenantId().equals(expected.scope().tenantId())
        || !invocation.environment().equals(expected.scope().environment())
        || !invocation.rootTarget().equals(expected.scope().root())) throw failure(UiLayoutLifecycleException.Code.DENIED);
  }

  private static UiLayoutMetadataCapture.Binding binding(UiLayoutLifecycleInvocation invocation, UUID draft,
      UiLayoutDraftWorkspaceDocument.TargetState target) {
    return new UiLayoutMetadataCapture.Binding(draft,
        new UiLayoutMetadataCapture.Scope(invocation.tenantId(), invocation.environment(), invocation.rootTarget(), target.target()),
        target.baseline().sourceRef(), target.authoring(), target.baseline().contentHash());
  }

  private static MapSqlParameterSource scopeParameters(UiLayoutMetadataCapture.Binding binding) {
    var scope = binding.scope();
    return new MapSqlParameterSource().addValue("source_draft_ref", binding.sourceDraftRef())
        .addValue("tenant_id", scope.tenantId()).addValue("environment", scope.environment())
        .addValue("root_component_type", scope.root().componentType()).addValue("root_component_id", scope.root().componentId())
        .addValue("component_type", scope.target().componentType()).addValue("component_id", scope.target().componentId());
  }

  private MapSqlParameterSource parameters(UiLayoutMetadataCapture value) {
    try {
      var binding = value.binding(); var source = value.source(); var context = value.observation();
      var parameters = scopeParameters(binding).addValue("capture_ref", value.captureRef())
          .addValue("baseline_source_ref", binding.baselineSourceRef())
          .addValue("document_type", binding.authoring().documentType())
          .addValue("authoring_schema_ref", binding.authoring().schemaRef())
          .addValue("authoring_schema_version", binding.authoring().schemaVersion())
          .addValue("baseline_content_hash", binding.baselineContentHash())
          .addValue("baseline_document_text", mapper.writeValueAsString(value.baselineDocument()))
          .addValue("service_key", source.serviceKey())
          .addValue("producer_ref", source.producerRef()).addValue("publication_ref", source.publicationRef())
          .addValue("actor_ref", context.actorRef()).addValue("administrative_unit", context.administrativeUnit())
          .addValue("context_version", context.contextVersion()).addValue("policy_ref", context.policyRef())
          .addValue("policy_revision", context.policyRevision()).addValue("captured_at_text", context.capturedAt().toString())
          .addValue("raw_schema_text", value.rawInputText()).addValue("raw_schema_hash", value.rawInputHash());
      if (source instanceof UiLayoutBaselineMetadataSeed.OperationSource operation) {
        var projection = (UiLayoutBaselineMetadataSeed.SchemaProjection) value.reproduction();
        parameters.addValue("origin_kind", "OPERATION_SCHEMA")
            .addValue("resource_ref", operation.resourceRef()).addValue("operation_path", operation.path())
            .addValue("operation_method", operation.method()).addValue("schema_type", operation.schemaType())
            .addValue("source_schema_ref", operation.schemaRef())
            .addValue("normalizer_ref", projection.normalizerRef()).addValue("projector_ref", projection.projectorRef())
            .addValue("assembly_input_version", projection.assembly().inputVersion())
            .addValue("assembler_ref", projection.assembly().assemblerRef())
            .addValue("assembly_input_text", projection.assembly().inputText())
            .addValue("assembly_input_hash", value.assemblyInputHash())
            .addValue("native_document_ref", null).addValue("native_document_revision", null);
      } else if (source instanceof UiLayoutBaselineMetadataSeed.NativeDocumentSource nativeSource) {
        parameters.addValue("origin_kind", "NATIVE_DOCUMENT")
            .addValue("native_document_ref", nativeSource.documentRef()).addValue("native_document_revision", nativeSource.documentRevision())
            .addValue("resource_ref", null).addValue("operation_path", null).addValue("operation_method", null)
            .addValue("schema_type", null).addValue("source_schema_ref", null)
            .addValue("normalizer_ref", null).addValue("projector_ref", null)
            .addValue("assembly_input_version", null).addValue("assembler_ref", null)
            .addValue("assembly_input_text", null).addValue("assembly_input_hash", null);
      } else throw new IllegalArgumentException();
      return parameters;
    } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.INVALID_STATE); }
  }

  private RowMapper<UiLayoutMetadataCapture> rowMapper() {
    return (row, index) -> {
      try {
        String raw = row.getString("raw_schema_text");
        String baseline = row.getString("baseline_document_text");
        // Bound before parsing stored text. Full byte, nesting and digest gates follow in the codec.
        if (raw == null || baseline == null || raw.length() > UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES
            || baseline.length() > UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES) throw new IllegalArgumentException();
        var scope = new UiLayoutMetadataCapture.Scope(row.getString("tenant_id"), row.getString("environment"),
            target(row, "root_component_type", "root_component_id"), target(row, "component_type", "component_id"));
        var binding = new UiLayoutMetadataCapture.Binding(row.getObject("source_draft_ref", UUID.class), scope,
            row.getString("baseline_source_ref"), new UiLayoutAuthoringDocumentDescriptor(exact(row.getString("document_type")),
                exact(row.getString("authoring_schema_ref")), exact(row.getString("authoring_schema_version"))), row.getString("baseline_content_hash"));
        return new UiLayoutMetadataCapture(row.getObject("capture_ref", UUID.class), binding, mapper.readTree(baseline),
            origin(row), reproduction(row),
            new UiLayoutBaselineMetadataSeed.Observation(row.getString("actor_ref"), row.getString("administrative_unit"),
                row.getString("context_version"), row.getString("policy_ref"), row.getString("policy_revision"),
                Instant.parse(row.getString("captured_at_text"))), raw, row.getString("raw_schema_hash"), row.getString("assembly_input_hash"));
      } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.INVALID_STATE); }
    };
  }

  private static UiLayoutBaselineMetadataSeed.Source origin(ResultSet row) throws java.sql.SQLException {
    if ("OPERATION_SCHEMA".equals(row.getString("origin_kind"))) {
      requireNull(row, "native_document_ref", "native_document_revision");
      return new UiLayoutBaselineMetadataSeed.OperationSource(row.getString("service_key"), row.getString("resource_ref"),
          row.getString("operation_path"), row.getString("operation_method"), row.getString("schema_type"),
          row.getString("source_schema_ref"), row.getString("producer_ref"), row.getString("publication_ref"));
    }
    if ("NATIVE_DOCUMENT".equals(row.getString("origin_kind"))) {
      requireNull(row, "resource_ref", "operation_path", "operation_method", "schema_type", "source_schema_ref", "normalizer_ref", "projector_ref");
      requireNull(row, "assembly_input_version", "assembler_ref", "assembly_input_text", "assembly_input_hash");
      return new UiLayoutBaselineMetadataSeed.NativeDocumentSource(row.getString("service_key"), row.getString("native_document_ref"),
          row.getString("native_document_revision"), row.getString("producer_ref"), row.getString("publication_ref"));
    }
    throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  private static UiLayoutBaselineMetadataSeed.Reproduction reproduction(ResultSet row) throws java.sql.SQLException {
    return switch (row.getString("origin_kind")) {
      case "OPERATION_SCHEMA" -> new UiLayoutBaselineMetadataSeed.SchemaProjection(row.getString("normalizer_ref"), row.getString("projector_ref"),
          new UiLayoutBaselineMetadataSeed.DocumentAssembly(row.getInt("assembly_input_version"), row.getString("assembler_ref"), row.getString("assembly_input_text")));
      case "NATIVE_DOCUMENT" -> new UiLayoutBaselineMetadataSeed.NativeIdentity();
      default -> throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
    };
  }

  private static void requireNull(ResultSet row, String... columns) throws java.sql.SQLException {
    for (String column : columns) if (row.getString(column) != null) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  private static UiLayoutTarget target(ResultSet row, String type, String id) throws java.sql.SQLException {
    return new UiLayoutTarget(exact(row.getString(type)), exact(row.getString(id)));
  }

  private static String exact(String value) {
    if (value == null || !value.equals(value.trim())) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
    return value;
  }

  private static UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code) {
    return new UiLayoutLifecycleException(code, "Metadata evidence storage is unavailable.");
  }
}
