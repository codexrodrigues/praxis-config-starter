package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Raw storage probes preserve real constraints; malformed rows are not admitted publications. */
@Tag("neon")
@EnabledIfSystemProperty(named = "praxis.page.p3b.neon.it", matches = "true")
class PageRuntimeRawEvidencePostgresIT extends PageRuntimePostgresFixture {
  private static final int MIB = 1024 * 1024;

  @Test void ledgerRawPrefixUsesExactPostgresEnvelopeAndConsumesOnlyItsBoundedPrefix() throws Exception {
    realStorePrefixes(false);
  }

  @Test void metadataRawPrefixUsesExactPostgresEnvelopeAndConsumesOnlyItsBoundedPrefix() throws Exception {
    realStorePrefixes(true);
  }

  private void realStorePrefixes(boolean metadata) throws Exception {
    try (var host = new PageRuntimeHostFixture("p3b-raw-" + UUID.randomUUID())) {
      var created = host.pages.create(host.alice, "c1", new PageCreationCommand(UUID.randomUUID(),
          new PageSourceReference.Native("fixture:page", "r1"), "Raw prefix prototype")).receipt();
      var original = new PageCreationCodec().read(jdbc.queryForObject(
          "SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, created.pageId()));
      int largeCount = metadata ? 16 : 8;
      int rowBytes = metadata ? MIB / 2 : MIB;
      var seed = new PageRuntimeDenseFixture(host, original, UUID.randomUUID(), UUID.randomUUID(), 129 + largeCount + 1);
      String table = metadata ? "ui_layout_baseline_metadata_evidence" : "ui_page_creation_evidence";
      Class<?> ownerType = metadata ? JdbcUiLayoutMetadataCaptureStore.class : PageCatalogStore.class;
      var columns = columns(ownerType);
      var expected = new LinkedHashMap<UUID, String>();
      var rowKeys = new LinkedHashMap<UUID, JdbcUiLayoutMetadataCaptureStore.RowKey>();
      var bindings = new LinkedHashMap<UUID, UiLayoutMetadataCapture.Binding>();
      var plainJdbc = new NamedParameterJdbcTemplate(dataSource);
      for (int index = 0; index < seed.pageIds().size(); index++) {
        var values = seed.rawEvidenceRowForStorageProbe(index, table);
        UUID page = seed.pageIds().get(index);
        if (metadata) {
          UiLayoutTarget root = new UiLayoutTarget("praxis-dynamic-page", page.toString());
          var key = new JdbcUiLayoutMetadataCaptureStore.RowKey((UUID) values.get("source_draft_ref"),
              host.scope.tenant(), host.scope.environment(), root, root);
          rowKeys.put(page, key);
          bindings.put(page, new UiLayoutMetadataCapture.Binding(key.draft(),
              new UiLayoutMetadataCapture.Scope(key.tenant(), key.environment(), root, root),
              (String) values.get("baseline_source_ref"), new UiLayoutAuthoringDocumentDescriptor(
                  (String) values.get("document_type"), (String) values.get("authoring_schema_ref"),
                  (String) values.get("authoring_schema_version")), (String) values.get("baseline_content_hash")));
        } else {
          // The projection joins namespace from the catalog rather than storing it in evidence.
          values = new LinkedHashMap<>(values);
          values.put("route_namespace", host.scope.namespace());
        }
        if (index >= 129 && index < 129 + largeCount) {
          if (metadata) {
            values.put("baseline_document_text", padding(0));
            values.put("raw_schema_text", padding(0));
          } else values.put("evidence_text", padding(0));
          int overhead = bytes(project(plainJdbc, columns, values));
          int paddingBytes = rowBytes - overhead;
          assertThat(paddingBytes).isPositive();
          if (metadata) {
            values.put("baseline_document_text", padding(paddingBytes / 2));
            values.put("raw_schema_text", padding(paddingBytes - paddingBytes / 2));
            assertThat(bytes((String) values.get("baseline_document_text"))).isLessThanOrEqualTo(262144);
            assertThat(bytes((String) values.get("raw_schema_text"))).isLessThanOrEqualTo(262144);
          } else {
            values.put("evidence_text", padding(paddingBytes));
            seed.rawEvidenceRowForStorageProbe(index, table).put("evidence_text", values.get("evidence_text"));
            assertThat(bytes((String) values.get("evidence_text"))).isLessThanOrEqualTo(2097152);
          }
        }
        String raw = project(plainJdbc, columns, values);
        if (index >= 129 && index < 129 + largeCount) assertThat(bytes(raw)).isEqualTo(rowBytes);
        expected.put(page, raw);
      }
      seed.insert();
      var before = heads(host);
      long eventsBefore = events(host);
      var observed = new ObservedJdbc();
      var hashes = new CanonicalJsonHashService(JSON);
      var ledger = new PageCatalogStore(observed, transactions, new PageCreationCodec(), hashes);
      var captures = new JdbcUiLayoutMetadataCaptureStore(observed, JSON, hashes, transactions);
      Object owner = metadata ? captures : ledger;
      var context = host.context(host.alice, host.groups);
      UUID absent = UUID.randomUUID();
      Function<List<UUID>, Integer> prefetch = ids -> metadata
          ? captures.prefetch(ids.stream().map(id -> rowKeys.getOrDefault(id,
              new JdbcUiLayoutMetadataCaptureStore.RowKey(id, host.scope.tenant(), host.scope.environment(),
                  new UiLayoutTarget("praxis-dynamic-page", id.toString()), new UiLayoutTarget("praxis-dynamic-page", id.toString())))).toList())
          : ledger.prefetch(context, ids);
      transactions.executeWithoutResult(status -> {
        assertThat(prefetch.apply(seed.pageIds().subList(0, 128))).isEqualTo(128);
        assertThat(observed.lastPayloadOrdinals).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 128).boxed().toList());
        assertRetained(owner, expected, seed.pageIds().subList(0, 128), metadata);
        int beforeConsume = observed.queries;
        for (UUID page : seed.pageIds().subList(0, 128)) {
          if (metadata) assertThat(captures.readVerified(host.context(host.alice, rowKeys.get(page).root()).invocation(),
              bindings.get(page), (invocation, capture) -> {})).isNotNull();
          else assertThat(ledger.byPage(context, page)).isPresent();
        }
        assertThat(observed.queries).isEqualTo(beforeConsume);
        observed.reset();
        assertThat(prefetch.apply(seed.pageIds().subList(128, 129))).isEqualTo(1);
        assertThat(observed.lastPayloadOrdinals).containsExactly(0);
        assertRetained(owner, expected, seed.pageIds().subList(128, 129), metadata);
        UUID lastSmall = seed.pageIds().get(128);
        int beforeLast = observed.queries;
        if (metadata) assertThat(captures.readVerified(host.context(host.alice, rowKeys.get(lastSmall).root()).invocation(),
            bindings.get(lastSmall), (invocation, capture) -> {})).isNotNull();
        else assertThat(ledger.byPage(context, lastSmall)).isPresent();
        assertThat(observed.queries).isEqualTo(beforeLast);
      });
      transactions.executeWithoutResult(status -> {
        var selected = new ArrayList<UUID>();
        selected.add(absent);
        selected.addAll(seed.pageIds().subList(129, seed.pageIds().size()));
        UUID absentMiddle = UUID.randomUUID();
        int missingOrdinal = 1 + largeCount / 2;
        selected.add(missingOrdinal, absentMiddle);
        observed.reset();
        int consumed = prefetch.apply(selected);
        assertThat(consumed).isEqualTo(largeCount + 2);
        assertThat(observed.lastPayloadOrdinals).containsExactlyElementsOf(java.util.stream.IntStream.range(1, consumed)
            .filter(ordinal -> ordinal != missingOrdinal).boxed().toList());
        assertThat(observed.lastPayloadBytes).isEqualTo(8L * MIB);
        var retainedPages = selected.subList(1, consumed).stream().filter(page -> !page.equals(absentMiddle)).toList();
        assertRetained(owner, expected, retainedPages, metadata);
        assertThat(retained(owner)).hasSize(largeCount);
        int beforeNegativeConsumption = observed.queries;
        for (UUID malformed : retainedPages) {
          if (metadata) {
            assertThatThrownBy(() -> captures.readVerified(host.context(host.alice, rowKeys.get(malformed).root()).invocation(),
                bindings.get(malformed), (invocation, capture) -> {})).isInstanceOf(UiLayoutLifecycleException.class);
          } else {
            assertThatThrownBy(() -> ledger.byPage(context, malformed)).isInstanceOf(UiLayoutLifecycleException.class);
          }
        }
        assertThat(observed.queries).isEqualTo(beforeNegativeConsumption);
        observed.reset();
        assertThat(prefetch.apply(selected.subList(consumed, selected.size()))).isEqualTo(1);
        assertThat(observed.lastPayloadOrdinals).containsExactly(0);
        assertRetained(owner, expected, selected.subList(consumed, selected.size()), metadata);
        observed.reset();
        assertThat(prefetch.apply(List.of(absent))).isEqualTo(1);
        assertThat(observed.lastPayloadOrdinals).isEmpty();
        int sizeBeforeMiss = retained(owner).size();
        int queries = observed.queries;
        if (metadata) {
          var absentRoot = new UiLayoutTarget("praxis-dynamic-page", absent.toString());
          var firstBinding = bindings.values().iterator().next();
          var missingBinding = new UiLayoutMetadataCapture.Binding(absent,
              new UiLayoutMetadataCapture.Scope(host.scope.tenant(), host.scope.environment(), absentRoot, absentRoot),
              firstBinding.baselineSourceRef(), firstBinding.authoring(), firstBinding.baselineContentHash());
          for (int attempt = 0; attempt < 2; attempt++)
            assertThatThrownBy(() -> captures.readVerified(host.context(host.alice, absentRoot).invocation(), missingBinding,
                (invocation, capture) -> {})).isInstanceOf(UiLayoutLifecycleException.class);
        } else {
          assertThat(ledger.byPage(context, absent)).isEmpty();
          assertThat(ledger.byPage(context, absent)).isEmpty();
        }
        assertThat(observed.queries - queries).isEqualTo(2);
        assertThat(retained(owner)).hasSize(sizeBeforeMiss);
      });
      assertThat(heads(host)).isEqualTo(before);
      assertThat(events(host)).isEqualTo(eventsBefore);
      System.out.println("P3-B raw prefix owner=" + ownerType.getSimpleName()
          + " entryWindows=128,1 exactAggregateBytes=8388608 missingFirstAndInterleavedProgress=true payloadOnlyPrefix=true"
          + " escapingAndNulls=true negativeStorageRows=true admittedPublication=false freshHeadsAuditUnchanged=true");
    }
  }

  @Test void syntheticOversizedRawSqlRowAdvancesWithoutReturningItsPayload() {
    var observed = new ObservedJdbc();
    var guard = new ConfigJdbcTransactionGuard(dataSource, transactions);
    Object owner = new Object();
    var keys = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    transactions.executeWithoutResult(status -> {
      String large = "x".repeat(8 * MIB + 1);
      String sql = "SELECT ordinal,CASE WHEN value IS NULL THEN NULL ELSE "
          + ConfigImmutableSqlRow.projection("input", List.of("value", "nullable"))
          + " END AS raw FROM (VALUES (0,CAST(:large AS text),CAST(NULL AS text)),"
          + "(1,CAST(:small AS text),CAST(NULL AS text)),(2,CAST(NULL AS text),CAST(NULL AS text))) input(ordinal,value,nullable)";
      var params = new MapSqlParameterSource("large", large).addValue("small", "quote\" slash\\ newline\n café");
      long exact = jdbc.queryForObject("SELECT octet_length(jsonb_build_object('value',?::text,'nullable',NULL)::text)", Long.class, large);
      assertThat(exact).isGreaterThan(8L * MIB);
      int consumed = ConfigTransactionReadSet.prefetch(owner, guard,
          () -> ConfigImmutableSqlRow.prefix(observed, keys, sql, params));
      assertThat(consumed).isEqualTo(1);
      assertThat(observed.lastPayloadOrdinals).isEmpty();
      assertThat(retained(owner)).isEmpty();
      String rest = "SELECT ordinal-1 AS ordinal,raw FROM (" + sql + ") original WHERE ordinal>=1";
      assertThat(ConfigTransactionReadSet.prefetch(owner, guard,
          () -> ConfigImmutableSqlRow.prefix(observed, keys.subList(1, 3), rest, params))).isEqualTo(2);
      assertThat(observed.lastPayloadOrdinals).containsExactly(0);
      assertThat(retained(owner)).hasSize(1);
      String raw = retained(owner).values().iterator().next();
      assertThat(ConfigImmutableSqlRow.parse(raw).getString("value")).isEqualTo(params.getValue("small"));
      assertThat(ConfigImmutableSqlRow.parse(raw).getString("nullable")).isNull();
    });
    System.out.println("P3-B synthetic raw oversized helper progress=1,2 firstRawReturned=0 tableInsertion=false largeParameterSentToPostgres=true");
  }

  @Test void rawPageKeysKeepScopeAcrossPrefetchAndHitsInOneConfigTransaction() throws Exception {
    try (var host = new PageRuntimeHostFixture("p3b-raw-key-" + UUID.randomUUID())) {
      var created = host.pages.create(host.alice, "c1", new PageCreationCommand(UUID.randomUUID(),
          new PageSourceReference.Native("fixture:page", "r1"), "Scope probe")).receipt();
      var observed = new ObservedJdbc();
      var store = new PageCatalogStore(observed, transactions, new PageCreationCodec(), new CanonicalJsonHashService(JSON));
      var original = host.context(host.alice, host.groups);
      var scopes = List.of(
          new PageRuntimeHostFixture.Scope(host.scope.tenant(), "other-app", host.scope.environment(), host.scope.namespace()),
          new PageRuntimeHostFixture.Scope(host.scope.tenant(), host.scope.application(), host.scope.environment(), "/other"),
          new PageRuntimeHostFixture.Scope("other-tenant", host.scope.application(), host.scope.environment(), host.scope.namespace()),
          new PageRuntimeHostFixture.Scope(host.scope.tenant(), host.scope.application(), "other-env", host.scope.namespace()));
      transactions.executeWithoutResult(status -> {
        store.prefetch(original, List.of(created.pageId()));
        assertThat(store.byPage(original, created.pageId())).isPresent();
        for (var scope : scopes) {
          var actor = new PageRuntimeHostFixture.Actor(host.alice.name(), host.alice.unit(), "c1", scope);
          var foreign = host.context(actor, host.groups);
          assertThat(store.prefetch(foreign, List.of(created.pageId()))).isEqualTo(1);
          assertThat(store.byPage(foreign, created.pageId())).isEmpty();
          int beforeReturn = observed.queries;
          assertThat(store.byPage(original, created.pageId())).isPresent();
          assertThat(observed.queries).isEqualTo(beforeReturn);
          assertThat(retained(store)).hasSize(1);
        }
      });
      System.out.println("P3-B RawPageKey sameOwnerSameTransaction=true dimensions=app,namespace,tenant,environment originalPositiveHit=true foreignMissesUncached=true");
    }
  }

  private static String padding(int length) {
    return JSON.createObjectNode().put("escaped", "quote\" slash\\ newline\n café").put("padding", "x".repeat(length)).toString();
  }
  @SuppressWarnings("unchecked") private static List<String> columns(Class<?> type) throws Exception {
    var field = type.getDeclaredField("ROW_COLUMNS"); field.setAccessible(true); return (List<String>) field.get(null);
  }
  private static String project(NamedParameterJdbcTemplate template, List<String> columns, Map<String, Object> values) {
    var params = new MapSqlParameterSource();
    columns.forEach(column -> params.addValue(column, values.get(column)));
    String select = String.join(",", columns.stream().map(column -> "CAST(:" + column + " AS text) AS " + column).toList());
    return template.queryForObject("SELECT " + ConfigImmutableSqlRow.projection("evidence", columns)
        + " FROM (SELECT " + select + ") evidence", params, String.class);
  }
  private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
  @SuppressWarnings("unchecked") private static Map<Object, String> retained(Object owner) {
    try {
      var ownerField = ConfigTransactionReadSet.class.getDeclaredField("owner"); ownerField.setAccessible(true);
      var valuesField = ConfigTransactionReadSet.class.getDeclaredField("values"); valuesField.setAccessible(true);
      for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
        if (sync instanceof ConfigTransactionReadSet && ownerField.get(sync) == owner) {
          return (Map<Object, String>) valuesField.get(sync);
        }
      }
      throw new AssertionError("No readset registered for expected owner");
    } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
  }
  private static void assertRetained(Object owner, Map<UUID, String> expected, List<UUID> ids, boolean metadata) {
    var retained = retained(owner);
    assertThat(retained.size()).isLessThanOrEqualTo(128);
    assertThat(retained.values().stream().mapToLong(PageRuntimeRawEvidencePostgresIT::bytes).sum()).isLessThanOrEqualTo(8L * MIB);
    for (UUID id : ids) assertThat(retained.values()).contains(expected.get(id));
    if (metadata) for (String raw : retained.values()) {
      assertThat(ConfigImmutableSqlRow.parse(raw).getString("assembly_input_text")).isNull();
      assertThat(ConfigImmutableSqlRow.parse(raw).getString("resource_ref")).isNull();
    }
  }
  private List<Map<String, Object>> heads(PageRuntimeHostFixture host) {
    return jdbc.queryForList("SELECT id,active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND environment=? ORDER BY id", host.scope.tenant(), host.scope.environment());
  }
  private long events(PageRuntimeHostFixture host) {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=? AND environment=?", Long.class, host.scope.tenant(), host.scope.environment());
  }
  private static final class ObservedJdbc extends NamedParameterJdbcTemplate {
    final List<Integer> lastPayloadOrdinals = new ArrayList<>();
    long lastPayloadBytes;
    int queries;
    ObservedJdbc() { super(dataSource); }
    void reset() { lastPayloadOrdinals.clear(); lastPayloadBytes = 0; }
    @Override public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> mapper) {
      queries++;
      boolean payload = sql.startsWith("SELECT ordinal, raw FROM (");
      if (payload) reset();
      return super.query(sql, params, (row, number) -> {
        if (payload) {
          lastPayloadOrdinals.add(row.getInt("ordinal"));
          lastPayloadBytes += bytes(row.getString("raw"));
        }
        return mapper.mapRow(row, number);
      });
    }
  }
}
