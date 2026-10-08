package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Opt-in SQL gate against an existing, exclusively allocated b1a_it_* schema.
 * No database/schema creation, migration, clean or product producer. All fixture writes roll back.
 * This covers database constraints; Java integrity/admission and JPA/JDBC atomicity are separate gates.
 */
@Tag("postgres")
@EnabledIfSystemProperty(named = "praxis.ui-layout.pg.it", matches = "true")
class UiLayoutMetadataCapturePostgresIT {
  private Connection connection;
  private UUID draft;

  @BeforeEach void requireMigratedDedicatedSchemaAndBeginFixture() throws Exception {
    String schema = required("PRAXIS_UI_LAYOUT_PG_SCHEMA");
    if (!schema.matches("b1a_it_[a-z0-9_]+")) throw new IllegalStateException("Dedicated b1a_it_* schema is required.");
    connection = DriverManager.getConnection(required("PRAXIS_UI_LAYOUT_PG_JDBC_URL"),
        required("PRAXIS_UI_LAYOUT_PG_USER"), required("PRAXIS_UI_LAYOUT_PG_PASSWORD"));
    connection.setSchema(schema);
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    connection.setAutoCommit(false);
    execute("SET LOCAL lock_timeout = '2s'");
    execute("SET LOCAL statement_timeout = '5s'");
    assertThat(scalar("SELECT current_schema()")).isEqualTo(schema);
    assertThat(scalar("SHOW server_encoding")).isEqualTo("UTF8");
    assertThat(scalar("SELECT count(*)::text FROM flyway_schema_history WHERE version='68' AND success" )).isEqualTo("1");
    draft = UUID.randomUUID();
    try (var statement = connection.prepareStatement("INSERT INTO ui_layout_draft"
        + " (id,tenant_id,environment,root_component_type,root_component_id,draft_document,draft_etag,state,created_by,created_at,updated_at,row_version)"
        + " VALUES (?,'fixture-tenant','lab','praxis-table','fixture-root','{}',?,'DRAFT','fixture-actor',now(),now(),0)")) {
      statement.setObject(1, draft); statement.setObject(2, UUID.randomUUID()); statement.executeUpdate();
    }
  }

  @AfterEach void rollbackAndClose() throws Exception {
    if (connection != null) try { connection.rollback(); } finally { connection.close(); }
  }

  @Test void preservesRawAndAssemblyTextExactly() throws Exception {
    var row = operation("first", " \n{\"properties\":{\"b\":{},\"a\":{}}} \t", " \n{\"config\":{\"label\":\"Ação 😀\"},\"bindings\":{}} \t");
    insert(row);
    try (var query = connection.prepareStatement("SELECT raw_schema_text,assembly_input_text,assembly_input_hash FROM ui_layout_baseline_metadata_evidence WHERE capture_ref=?")) {
      query.setObject(1, row.get("capture_ref"));
      try (var result = query.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(row.get("raw_schema_text"));
        assertThat(result.getString(2)).isEqualTo(row.get("assembly_input_text"));
        assertThat(result.getString(3)).isEqualTo(row.get("assembly_input_hash"));
      }
    }
  }

  @ParameterizedTest @ValueSource(strings = {"assembly_input_version", "assembler_ref", "assembly_input_text", "assembly_input_hash"})
  void rejectsMissingAssemblyWithoutSqlUnknownEscape(String column) throws Exception {
    var row = operation("first", "{}", "{}"); row.put(column, null);
    rejected(() -> insert(row), "23514");
  }

  @Test void rejectsUnsupportedVersion() throws Exception {
    var row = operation("first", "{}", "{}"); row.put("assembly_input_version", 2);
    rejected(() -> insert(row), "23514");
  }

  @Test void admitsNativeIdentityWithoutAssembly() throws Exception {
    insert(nativeIdentity());
  }

  @Test void rejectsAssemblyOnNativeIdentity() throws Exception {
    var row = nativeIdentity(); row.put("assembler_ref", "fabricated");
    rejected(() -> insert(row), "23514");
  }

  @ParameterizedTest @ValueSource(strings = {"[]", "null"})
  void rejectsNonObjectAssembly(String body) throws Exception {
    rejected(() -> insert(operation("first", "{}", body)), "23514");
  }

  @Test void boundsAssemblyByUtf8Bytes() throws Exception {
    String body = "{\"a\":\"" + "é".repeat(140000) + "\"}";
    rejected(() -> insert(operation("first", "{}", body)), "23514");
  }

  @Test void countsBothInputsAtExactQuotaAndRejectsAnotherValidPair() throws Exception {
    String body = "{\"a\":\"" + "a".repeat(262144 - 8) + "\"}";
    insert(operation("first", body, body)); insert(operation("second", body, body));
    assertThat(scalar("SELECT sum(octet_length(raw_schema_text)+octet_length(assembly_input_text))::text FROM ui_layout_baseline_metadata_evidence WHERE source_draft_ref='" + draft + "'"))
        .isEqualTo("1048576");
    // The minimum valid next pair is four bytes; a separate test covers +1 exactly.
    rejected(() -> insert(operation("third", "{}", "{}")), "P0001");
  }

  @Test void rejectsQuotaOneByteAboveLimitWithIndividuallyValidBodies() throws Exception {
    String maximum = "{\"a\":\"" + "a".repeat(262144 - 8) + "\"}";
    String shorter = "{\"a\":\"" + "a".repeat(262141 - 8) + "\"}";
    insert(operation("first", maximum, maximum)); insert(operation("second", maximum, shorter));
    assertThat(scalar("SELECT sum(octet_length(raw_schema_text)+octet_length(assembly_input_text))::text FROM ui_layout_baseline_metadata_evidence WHERE source_draft_ref='" + draft + "'"))
        .isEqualTo("1048573");
    // First two pairs occupy 1 MiB - 3 bytes; the valid third pair adds four bytes.
    rejected(() -> insert(operation("third", "{}", "{}")), "P0001");
  }

  @Test void rejectsDuplicateTarget() throws Exception {
    var row = operation("first", "{}", "{}"); insert(row);
    rejected(() -> insert(operation("first", "{}", "{}")), "23505");
  }

  @ParameterizedTest @ValueSource(strings = {"UPDATE ui_layout_baseline_metadata_evidence SET assembler_ref='changed' WHERE source_draft_ref=?",
      "DELETE FROM ui_layout_baseline_metadata_evidence WHERE source_draft_ref=?"})
  void rejectsMutationOfFixtureEvidence(String sql) throws Exception {
    insert(operation("first", "{}", "{}"));
    rejected(() -> { try (var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, draft); statement.executeUpdate();
    } }, "P0001");
  }

  @Test void rejectsWrongIsolation() throws Exception {
    connection.rollback(); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    rejected(() -> insert(operation("first", "{}", "{}")), "P0001");
  }

  private Map<String, Object> nativeIdentity() throws Exception {
    var row = operation("first", "{}", "{}"); row.put("origin_kind", "NATIVE_DOCUMENT");
    row.put("native_document_ref", "fixture-native"); row.put("native_document_revision", "revision-1");
    for (String column : new String[]{"resource_ref", "operation_path", "operation_method", "schema_type", "source_schema_ref", "normalizer_ref", "projector_ref",
        "assembly_input_version", "assembler_ref", "assembly_input_text", "assembly_input_hash"}) row.put(column, null);
    return row;
  }

  private Map<String, Object> operation(String target, String raw, String assembly) throws Exception {
    var row = new LinkedHashMap<String, Object>();
    row.put("capture_ref", UUID.randomUUID()); row.put("source_draft_ref", draft);
    row.put("tenant_id", "fixture-tenant"); row.put("environment", "lab"); row.put("root_component_type", "praxis-table"); row.put("root_component_id", "fixture-root");
    row.put("component_type", "praxis-table"); row.put("component_id", target); row.put("baseline_source_ref", "fixture-b0");
    row.put("document_type", "praxis.table.editor"); row.put("authoring_schema_ref", "urn:fixture:table"); row.put("authoring_schema_version", "1");
    row.put("baseline_content_hash", digest("{}")); row.put("baseline_document_text", "{}");
    row.put("service_key", "fixture-service"); row.put("resource_ref", "fixture-resource"); row.put("operation_path", "/fixture"); row.put("operation_method", "GET");
    row.put("schema_type", "response"); row.put("source_schema_ref", "urn:fixture:schema"); row.put("producer_ref", "fixture-producer"); row.put("publication_ref", "fixture-publication");
    row.put("normalizer_ref", "fixture-normalizer"); row.put("projector_ref", "fixture-projector"); row.put("origin_kind", "OPERATION_SCHEMA");
    row.put("actor_ref", "fixture-actor"); row.put("administrative_unit", "fixture-unit"); row.put("context_version", "fixture-context");
    row.put("policy_ref", "fixture-policy"); row.put("policy_revision", "1"); row.put("captured_at_text", "2026-10-02T12:00:00Z");
    row.put("raw_schema_text", raw); row.put("raw_schema_hash", digest(raw));
    row.put("assembly_input_version", 1); row.put("assembler_ref", "fixture-assembler"); row.put("assembly_input_text", assembly); row.put("assembly_input_hash", digest(assembly));
    return row;
  }

  private void insert(Map<String, Object> row) throws Exception {
    String placeholders = String.join(",", row.keySet().stream().map(key -> "?").toList());
    try (var statement = connection.prepareStatement("INSERT INTO ui_layout_baseline_metadata_evidence (" + String.join(",", row.keySet()) + ") VALUES (" + placeholders + ")")) {
      int index = 1; for (Object value : row.values()) statement.setObject(index++, value); statement.executeUpdate();
    }
  }

  private String scalar(String sql) throws Exception {
    try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue(); return result.getString(1);
    }
  }
  private void execute(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }
  private static String digest(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
  private static String required(String name) {
    String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required."); return value;
  }
  private static void rejected(SqlAction action, String sqlState) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo(sqlState));
  }
  @FunctionalInterface private interface SqlAction { void run() throws Exception; }
}
