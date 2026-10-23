package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** SQL boundary simulation; PostgreSQL serialized octet lengths require the separate Neon gate. */
@Tag("unit")
class ConfigImmutableSqlRowTest {
  @Test @SuppressWarnings("unchecked")
  void consumesExactSerializedPrefixAndAdvancesMissingOrOversizedFirstWithoutLoadingIt() {
    var jdbc = mock(NamedParameterJdbcTemplate.class);
    var payloads = List.of("x".repeat(3 * 1024 * 1024), "y".repeat(5 * 1024 * 1024));
    var weights = new ArrayList<Map.Entry<Integer, Long>>();
    weights.add(Map.entry(0, (long) payloads.get(0).length()));
    weights.add(Map.entry(1, (long) payloads.get(1).length()));
    weights.add(Map.entry(2, 1L));
    var fetched = new ArrayList<Integer>();
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
      if (((String) call.getArgument(0)).contains("AS weight")) return List.copyOf(weights);
      Collection<Integer> ordinals = ((MapSqlParameterSource) call.getArgument(1)).getValue("retained_ordinals") instanceof Collection<?> c
          ? (Collection<Integer>) c : List.of();
      fetched.addAll(ordinals);
      return ordinals.stream().map(index -> Map.entry(index, payloads.get(index))).toList();
    });
    var prefix = ConfigImmutableSqlRow.prefix(jdbc, List.of("a", "b", "c"), "SELECT raw", new MapSqlParameterSource());
    assertThat(prefix.consumed()).isEqualTo(2);
    assertThat(prefix.values()).containsOnlyKeys("a", "b");
    assertThat(fetched).containsExactly(0, 1);
    weights.clear(); weights.add(Map.entry(0, ConfigTransactionReadSet.MAX_BYTES + 1));
    weights.add(Map.entry(1, 2L)); fetched.clear();
    assertThat(ConfigImmutableSqlRow.prefix(jdbc, List.of("large", "next"), "SELECT raw", new MapSqlParameterSource()))
        .isEqualTo(new ConfigTransactionReadSet.Prefetched(1, Map.of()));
    assertThat(fetched).isEmpty();
    weights.clear(); weights.add(Map.entry(0, 0L));
    assertThat(ConfigImmutableSqlRow.prefix(jdbc, List.of("missing"), "SELECT raw", new MapSqlParameterSource()))
        .isEqualTo(new ConfigTransactionReadSet.Prefetched(1, Map.of()));
    assertThat(fetched).isEmpty();
  }

  @Test
  void rawEnvelopePreservesNullsAndMalformedDomainTextWithoutInterpretingIt() throws Exception {
    var row = mock(java.sql.ResultSet.class);
    when(row.getString("body")).thenReturn("{invalid private JSON");
    when(row.getString("optional")).thenReturn(null);
    String raw = ConfigImmutableSqlRow.capture(row, List.of("body", "optional"));
    var decoded = ConfigImmutableSqlRow.parse(raw);
    assertThat(decoded.getString("body")).isEqualTo("{invalid private JSON");
    assertThat(decoded.getString("optional")).isNull();
    assertThatThrownBy(() -> decoded.getString("missing-column")).isInstanceOf(IllegalArgumentException.class);
  }
}
