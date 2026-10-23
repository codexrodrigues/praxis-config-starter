package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Raw SQL strings/nulls only. Domain parsing belongs to the existing authorized consumer. */
final class ConfigImmutableSqlRow {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JsonNode columns;

  private ConfigImmutableSqlRow(JsonNode columns) { this.columns = columns; }

  static ConfigImmutableSqlRow parse(String raw) {
    try {
      JsonNode columns = JSON.readTree(raw);
      if (!columns.isObject()) throw new IllegalArgumentException();
      return new ConfigImmutableSqlRow(columns);
    } catch (Exception exception) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE,
          "Immutable evidence row is inconsistent.");
    }
  }

  static String capture(ResultSet row, List<String> columns) throws SQLException {
    var raw = JSON.createObjectNode();
    for (String column : columns) raw.put(column, row.getString(column));
    return raw.toString();
  }

  String getString(String column) {
    JsonNode value = columns.get(column);
    if (value == null) throw new IllegalArgumentException("Missing evidence column.");
    if (value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException("Invalid evidence column.");
    return value.textValue();
  }

  UUID getObject(String column, Class<UUID> type) {
    String value = getString(column);
    return value == null ? null : UUID.fromString(value);
  }

  int getInt(String column) {
    String value = getString(column);
    return value == null ? 0 : Integer.parseInt(value);
  }

  /** Column names/alias are internal constants, never request input. Exact serialized SQL size. */
  static String projection(String alias, List<String> columns) {
    return "jsonb_build_object(" + String.join(",", columns.stream()
        .map(column -> "'" + column + "'," + alias + "." + column + "::text").toList()) + ")::text";
  }

  /**
   * rawQuery returns one ordinal and nullable raw row per requested identity. It never parses
   * private JSON. Missing/oversized first candidates count as progress but are not retained.
   */
  static ConfigTransactionReadSet.Prefetched prefix(NamedParameterJdbcTemplate jdbc,
      List<?> keys, String rawQuery, MapSqlParameterSource parameters) {
    if (keys.isEmpty() || keys.size() > ConfigTransactionReadSet.MAX_ENTRIES) {
      throw new IllegalArgumentException("Invalid immutable evidence window.");
    }
    var weights = jdbc.query("SELECT ordinal, COALESCE(octet_length(raw),0)::bigint AS weight FROM ("
        + rawQuery + ") evidence ORDER BY ordinal", parameters,
        (row, index) -> Map.entry(row.getInt("ordinal"), row.getLong("weight")));
    if (weights.size() != keys.size()) throw unavailable();
    int consumed = 0;
    long total = 0;
    var retainedOrdinals = new java.util.ArrayList<Integer>();
    for (var weight : weights) {
      if (weight.getKey() != consumed || weight.getValue() < 0) throw unavailable();
      long bytes = weight.getValue();
      if (bytes > ConfigTransactionReadSet.MAX_BYTES - total) {
        if (consumed == 0) consumed = 1;
        break;
      }
      if (bytes > 0) retainedOrdinals.add(consumed);
      total += bytes;
      consumed++;
    }
    var values = new LinkedHashMap<Object, String>();
    if (!retainedOrdinals.isEmpty()) {
      parameters.addValue("retained_ordinals", retainedOrdinals);
      var rows = jdbc.query("SELECT ordinal, raw FROM (" + rawQuery
          + ") evidence WHERE ordinal IN (:retained_ordinals) ORDER BY ordinal", parameters,
          (row, index) -> Map.entry(row.getInt("ordinal"), row.getString("raw")));
      long actual = 0;
      for (var row : rows) {
        actual += row.getValue().getBytes(StandardCharsets.UTF_8).length;
        if (actual > ConfigTransactionReadSet.MAX_BYTES || !retainedOrdinals.contains(row.getKey())) {
          throw unavailable();
        }
        if (values.put(keys.get(row.getKey()), row.getValue()) != null) throw unavailable();
      }
      if (rows.size() != retainedOrdinals.size()) throw unavailable();
    }
    return new ConfigTransactionReadSet.Prefetched(consumed, values);
  }

  private static UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "Immutable evidence prefix is unavailable.");
  }
}
