package org.praxisplatform.config.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** Current heads and reference locks, always read from the active Config connection. Never cached. */
final class UiLayoutPageGroupHeadStore {
  static final int PAGE_BATCH_SIZE = 32;
  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigJdbcTransactionGuard transactions;
  private final UiLayoutReleaseHeadRepository heads;

  UiLayoutPageGroupHeadStore(NamedParameterJdbcTemplate jdbc, TransactionOperations operations,
      UiLayoutReleaseHeadRepository heads) {
    this.jdbc = jdbc;
    this.transactions = new ConfigJdbcTransactionGuard(jdbc.getJdbcTemplate().getDataSource(), operations);
    this.heads = heads;
  }

  void flush() { transactions.flush(); }
  java.util.Set<Object> managedDefinitions() { return transactions.managedDefinitions(); }
  void detach(UiLayoutFrozenRecordBatch batch, java.util.Set<Object> previouslyManagedDefinitions) {
    transactions.detachBatch(batch, previouslyManagedDefinitions);
  }

  Head head(UiLayoutLifecycleInvocation invocation, UiLayoutTarget root) {
    transactions.require();
    var result = jdbc.query("""
        SELECT id, active_release_id, head_etag, row_version FROM ui_layout_release_head
        WHERE tenant_id=:tenant AND environment=:environment
          AND root_component_type=:type AND root_component_id=:root
        """, scope(invocation, root), (row, index) -> new Head(row.getObject("id", UUID.class),
            row.getObject("active_release_id", UUID.class), row.getObject("head_etag", UUID.class),
            row.getLong("row_version")));
    if (result.size() > 1) throw unavailable();
    return result.isEmpty() ? null : result.getFirst();
  }

  void lockAndRefreshHead(UiLayoutLifecycleInvocation invocation) {
    transactions.require();
    var current = head(invocation, invocation.rootTarget());
    if (current == null) return;
    // A locking entity query upgrades a stale managed instance before refresh and may reject its
    // old version. Resolve the authoritative identity without a lock, then refresh under row lock.
    var managed = heads.findById(current.id()).orElseThrow(this::unavailable);
    transactions.refresh(managed);
  }

  void requireCatalog(PageCreationContext context) {
    transactions.require();
    var invocation = context.invocation();
    var parameters = scope(invocation, invocation.rootTarget())
        .addValue("application", context.applicationId()).addValue("namespace", context.routeNamespace());
    var count = jdbc.queryForObject("""
        SELECT count(*) FROM ui_page_catalog WHERE tenant_id=:tenant AND environment=:environment
          AND root_component_type=:type AND root_component_id=:root
          AND application_id=:application AND route_namespace=:namespace
        """, parameters, Long.class);
    if (count == null || count != 1) throw new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.NOT_FOUND, "The page is unavailable in the current context.");
  }

  void requireCompleteCatalog(PageCreationContext context) {
    transactions.require();
    var invocation = context.invocation();
    Boolean unknown = jdbc.queryForObject("""
        SELECT EXISTS (
          SELECT 1 FROM ui_layout_release_head h LEFT JOIN ui_page_catalog c
            ON c.tenant_id=h.tenant_id AND c.environment=h.environment
            AND c.root_component_type=h.root_component_type AND c.root_component_id=h.root_component_id
          WHERE h.tenant_id=:tenant AND h.environment=:environment
            AND h.root_component_type='praxis-dynamic-page' AND h.active_release_id IS NOT NULL
            AND c.page_id IS NULL)
        """, scope(invocation, invocation.rootTarget()), Boolean.class);
    if (!Boolean.FALSE.equals(unknown)) throw unavailable();
  }

  List<ActivePage> activePages(PageCreationContext context, UUID after) {
    transactions.require();
    var parameters = scope(context.invocation(), context.invocation().rootTarget())
        .addValue("application", context.applicationId()).addValue("namespace", context.routeNamespace())
        .addValue("after", after).addValue("limit", PAGE_BATCH_SIZE);
    return jdbc.query("""
        SELECT c.page_id, c.root_component_type, c.root_component_id, h.active_release_id
        FROM ui_page_catalog c JOIN ui_layout_release_head h
          ON h.tenant_id=c.tenant_id AND h.environment=c.environment
          AND h.root_component_type=c.root_component_type AND h.root_component_id=c.root_component_id
        WHERE c.tenant_id=:tenant AND c.environment=:environment
          AND c.application_id=:application AND c.route_namespace=:namespace
          AND h.active_release_id IS NOT NULL
        """ + (after == null ? "" : " AND c.page_id > :after ") + " ORDER BY c.page_id LIMIT :limit",
        parameters, (row, index) -> new ActivePage(row.getObject("page_id", UUID.class),
            new UiLayoutTarget(row.getString("root_component_type"), row.getString("root_component_id")),
            row.getObject("active_release_id", UUID.class)));
  }

  void lockGroup(PageCreationContext context, UUID groupId) {
    var invocation = context.invocation();
    tryLock(lockKey("page-group", invocation.tenantId(), context.applicationId(),
        invocation.environment(), context.routeNamespace(), groupId.toString()));
  }

  void lockRoot(UiLayoutLifecycleInvocation invocation) {
    var root = invocation.rootTarget();
    tryLock(lockKey("page-group-root", invocation.tenantId(), invocation.environment(),
        root.componentType(), root.componentId()));
  }

  private void tryLock(long key) {
    transactions.require();
    Boolean acquired = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(:key)",
        new MapSqlParameterSource("key", key), Boolean.class);
    if (!Boolean.TRUE.equals(acquired)) conflict();
  }

  void conflict() {
    transactions.abortTransaction();
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.PRECONDITION_FAILED,
        "Publication state changed or is busy; reload and retry in a new transaction.");
  }

  static long lockKey(String... parts) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return ByteBuffer.wrap(digest.digest()).getLong();
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private MapSqlParameterSource scope(UiLayoutLifecycleInvocation invocation, UiLayoutTarget root) {
    return new MapSqlParameterSource("tenant", invocation.tenantId())
        .addValue("environment", invocation.environment()).addValue("type", root.componentType())
        .addValue("root", root.componentId());
  }

  private UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "The authoritative page/group state is unavailable.");
  }

  record Head(UUID id, UUID activeReleaseId, UUID etag, long version) {}
  record ActivePage(UUID pageId, UiLayoutTarget root, UUID releaseId) {}
}
