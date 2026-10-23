package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.praxisplatform.config.dto.UiLayoutReleaseHeadReceipt;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** Fresh mutable head vector; only sealed records may be reused by the separate immutable readset. */
final class PageRuntimeStore {
  private static final long WORKSPACE_BYTES = 8L * 1024 * 1024;
  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigJdbcTransactionGuard guard;

  PageRuntimeStore(NamedParameterJdbcTemplate jdbc, TransactionOperations transactions) {
    this.jdbc = jdbc;
    this.guard = new ConfigJdbcTransactionGuard(jdbc.getJdbcTemplate().getDataSource(), transactions);
  }

  void requireTransaction() { guard.require(); }
  Set<Object> managedDefinitions() { return guard.managedDefinitions(); }
  void detach(UiLayoutFrozenRecordBatch batch, Set<Object> previous) { guard.detachBatch(batch, previous); }

  List<Head> vector(PageCreationContext context, UUID page, UiLayoutTarget groupsRoot) {
    guard.flush();
    var invocation = context.invocation();
    var parameters = new MapSqlParameterSource().addValue("app", context.applicationId())
        .addValue("tenant", invocation.tenantId()).addValue("env", invocation.environment())
        .addValue("namespace", context.routeNamespace()).addValue("page", page);
    String sql = """
        SELECT c.page_id, c.root_component_type, c.root_component_id,
          h.id, h.active_release_id, h.head_etag, h.row_version, h.updated_at
        FROM ui_page_catalog c LEFT JOIN ui_layout_release_head h
          ON h.tenant_id=c.tenant_id AND h.environment=c.environment
          AND h.root_component_type=c.root_component_type AND h.root_component_id=c.root_component_id
        WHERE c.application_id=:app AND c.tenant_id=:tenant AND c.environment=:env
          AND c.route_namespace=:namespace
        """ + (page == null ? "" : " AND c.page_id=:page ");
    if (groupsRoot != null) {
      parameters.addValue("groupType", groupsRoot.componentType()).addValue("groupRoot", groupsRoot.componentId());
      sql += """
          UNION ALL
          SELECT NULL::uuid, CAST(:groupType AS varchar), CAST(:groupRoot AS varchar),
            h.id, h.active_release_id, h.head_etag, h.row_version, h.updated_at
          FROM (SELECT 1) seed LEFT JOIN ui_layout_release_head h
            ON h.tenant_id=:tenant AND h.environment=:env
            AND h.root_component_type=:groupType AND h.root_component_id=:groupRoot
          """;
    }
    sql += " ORDER BY page_id NULLS FIRST";
    return List.copyOf(jdbc.query(sql, parameters, (row, index) -> {
      UUID pageId = row.getObject("page_id", UUID.class);
      var root = new UiLayoutTarget(row.getString("root_component_type"), row.getString("root_component_id"));
      if (pageId != null && (!UiLayoutPageRevisionReproduction.TYPE.equals(root.componentType())
          || !pageId.toString().equals(root.componentId()))) throw unavailable();
      var timestamp = row.getTimestamp("updated_at");
      return new Head(pageId, root, row.getObject("id", UUID.class), row.getObject("active_release_id", UUID.class),
          row.getObject("head_etag", UUID.class), row.getObject("row_version", Long.class),
          timestamp == null ? null : timestamp.toInstant());
    }));
  }

  /** Weigh PINNED releases, never reselect current heads after the vector was collected. */
  List<Head> batch(List<Head> candidates, int offset) {
    guard.require();
    var window = candidates.subList(offset, Math.min(candidates.size(), offset + 128));
    var weights = jdbc.query("""
        SELECT r.id, GREATEST(COALESCE(octet_length(d.draft_document::text)::bigint,
          :maximum + 1), 1::bigint) AS weight
        FROM ui_layout_release r LEFT JOIN ui_layout_draft d ON d.id=r.source_draft_id
        WHERE r.id IN (:ids)
        """, new MapSqlParameterSource("ids", window.stream().map(Head::release).toList())
            .addValue("maximum", WORKSPACE_BYTES),
        (row, index) -> Map.entry(row.getObject("id", UUID.class), row.getLong("weight")))
        .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    var result = new ArrayList<Head>();
    long bytes = 0;
    for (var head : window) {
      long weight = weights.getOrDefault(head.release(), WORKSPACE_BYTES + 1);
      if (!result.isEmpty() && weight > WORKSPACE_BYTES - bytes) break;
      result.add(head);
      bytes += weight;
      if (bytes >= WORKSPACE_BYTES) break;
    }
    return List.copyOf(result);
  }

  record Head(UUID pageId, UiLayoutTarget root, UUID id, UUID release, UUID etag,
      Long version, Instant updatedAt) {
    UiLayoutReleaseHeadReceipt receipt() {
      if (id == null || release == null || etag == null || version == null || updatedAt == null) throw unavailable();
      return new UiLayoutReleaseHeadReceipt(root, release.toString(), etag.toString(), updatedAt);
    }
    Map<String, Object> stable() {
      receipt();
      return Map.of("root", root, "release", release.toString(), "headEtag", etag.toString(),
          "updatedAt", updatedAt.toString());
    }
  }

  private static UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "Published identity is unavailable.");
  }
}
