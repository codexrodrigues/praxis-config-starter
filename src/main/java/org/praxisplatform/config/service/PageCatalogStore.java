package org.praxisplatform.config.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal immutable catalog/creation ledger, on the same Config connection as lifecycle JPA/JDBC.
 */
final class PageCatalogStore {
  private final NamedParameterJdbcTemplate jdbc;
  private final ConfigJdbcTransactionGuard transactionGuard;
  private final PageCreationCodec codec;
  private final CanonicalJsonHashService hashes;

  PageCatalogStore(
      NamedParameterJdbcTemplate jdbc,
      TransactionOperations transactions,
      PageCreationCodec codec,
      CanonicalJsonHashService hashes) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.hashes = hashes;
    this.transactionGuard =
        new ConfigJdbcTransactionGuard(jdbc.getJdbcTemplate().getDataSource(), transactions);
  }

  void requireTransaction() {
    transactionGuard.require();
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw failure();
  }

  void lock(PageCreationContext context, UUID creationKey) {
    requireTransaction();
    var invocation = context.invocation();
    String key =
        part(context.applicationId())
            + part(invocation.tenantId())
            + part(invocation.environment())
            + part(invocation.actorRef())
            + part(creationKey.toString());
    jdbc.getJdbcTemplate()
        .queryForObject(
            "select pg_advisory_xact_lock(hashtext(?),hashtext(?))",
            (rs, n) -> 0,
            "praxis-page-create",
            key);
  }

  Optional<PageCreationEvidence> byKey(PageCreationContext context, UUID key) {
    var params =
        scope(context).addValue("actor", context.invocation().actorRef()).addValue("key", key);
    return read(
        "e.application_id=:app AND e.tenant_id=:tenant AND e.environment=:env AND"
            + " e.actor_ref=:actor AND e.creation_key=:key",
        params);
  }

  Optional<PageCreationEvidence> byPage(PageCreationContext context, UUID page) {
    return read(
        "e.application_id=:app AND e.tenant_id=:tenant AND e.environment=:env AND e.page_id=:page",
        scope(context).addValue("page", page));
  }

  boolean knownPage(UUID page) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from ui_page_catalog where page_id=:page)",
            Map.of("page", page),
            Boolean.class));
  }

  private Optional<PageCreationEvidence> read(String where, MapSqlParameterSource params) {
    var list =
        jdbc.query(
            "select e.*,c.route_namespace from ui_page_creation_evidence e join ui_page_catalog c"
                + " on c.page_id=e.page_id where "
                + where,
            params,
            (row, index) -> {
              String text = row.getString("evidence_text");
              var value = codec.read(text);
              var observed = value.observation();
              if (!value.pageId().equals(row.getObject("page_id", UUID.class))
                  || !value.draftId().equals(row.getObject("draft_id", UUID.class))
                  || !value
                      .command()
                      .creationKey()
                      .equals(row.getObject("creation_key", UUID.class))
                  || !observed.applicationId().equals(row.getString("application_id"))
                  || !observed.routeNamespace().equals(row.getString("route_namespace"))
                  || !observed.invocation().tenantId().equals(row.getString("tenant_id"))
                  || !observed.invocation().environment().equals(row.getString("environment"))
                  || !observed.invocation().actorRef().equals(row.getString("actor_ref"))
                  || !hashes
                      .sha256Exact(codec.tree(value.command()))
                      .equals(row.getString("command_hash"))
                  || !hashes.sha256Exact(codec.tree(value)).equals(row.getString("evidence_hash")))
                throw corrupt();
              return value;
            });
    if (list.size() > 1) throw corrupt();
    return list.stream().findFirst();
  }

  void insert(PageCreationEvidence value) {
    requireTransaction();
    var c = value.observation();
    var i = c.invocation();
    var p =
        scope(c)
            .addValue("page", value.pageId())
            .addValue("namespace", c.routeNamespace())
            .addValue("type", value.target().componentType())
            .addValue("id", value.target().componentId())
            .addValue("created", java.sql.Timestamp.from(value.createdAt()))
            .addValue("actor", i.actorRef())
            .addValue("key", value.command().creationKey())
            .addValue("draft", value.draftId())
            .addValue("commandHash", hashes.sha256Exact(codec.tree(value.command())))
            .addValue("evidenceHash", hashes.sha256Exact(codec.tree(value)))
            .addValue("evidence", codec.write(value));
    if (jdbc.update(
            "insert into"
                + " ui_page_catalog(page_id,application_id,tenant_id,environment,route_namespace,root_component_type,root_component_id,created_at)"
                + " values(:page,:app,:tenant,:env,:namespace,:type,:id,:created)",
            p)
        != 1) throw failure();
    if (jdbc.update(
            "insert into"
                + " ui_page_creation_evidence(page_id,application_id,tenant_id,environment,actor_ref,creation_key,draft_id,command_hash,evidence_hash,evidence_text)"
                + " values(:page,:app,:tenant,:env,:actor,:key,:draft,:commandHash,:evidenceHash,:evidence)",
            p)
        != 1) throw failure();
  }

  boolean wasPublished(UiLayoutLifecycleInvocation invocation, UUID release) {
    var i = invocation;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from ui_layout_release_event where tenant_id=:tenant and"
                + " environment=:env and root_component_type=:type and root_component_id=:id and"
                + " to_release_id=:release and event_type in ('PUBLISHED','ROLLED_BACK') and"
                + " head_etag is not null)",
            new MapSqlParameterSource()
                .addValue("tenant", i.tenantId())
                .addValue("env", i.environment())
                .addValue("type", i.rootTarget().componentType())
                .addValue("id", i.rootTarget().componentId())
                .addValue("release", release),
            Boolean.class));
  }

  private static MapSqlParameterSource scope(PageCreationContext c) {
    return new MapSqlParameterSource()
        .addValue("app", c.applicationId())
        .addValue("tenant", c.invocation().tenantId())
        .addValue("env", c.invocation().environment());
  }

  private static String part(String value) {
    return value.length() + ":" + value;
  }

  private static UiLayoutLifecycleException corrupt() {
    return new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.INVALID_STATE, "Page creation evidence is inconsistent.");
  }

  private static UiLayoutLifecycleException failure() {
    return new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "Page catalog requires the active Config transaction.");
  }
}
