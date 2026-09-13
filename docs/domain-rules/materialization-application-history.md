# Materialization application history

`DomainRuleMaterialization` remains the canonical persisted projection. V63 adds an internal optimistic `rowVersion` and a nullable `everApplied` marker to the existing table; it does not add an operational policy resolver, a head table or an HTTP endpoint. The existing V55 unique applied-head index remains in force.

## Evidence and migration

- New drafts begin with `everApplied=false`. Applying a materialization records true in the same Config transaction as its application event. Both publication and explicit status transition mark the entity before saving.
- True is irreversible through revert, supersede, failure or return to draft. New SQL/ORM writes of superseded rows cannot claim false; inconclusive legacy superseded rows retain null. `appliedAt` also constitutes positive evidence, including in direct SQL writes outside the applied status.
- V63 backfills true from applied status, a non-null `applied_at`, or a `materialization.applied` event with exact materialization ID, definition, tenant, environment, key and target identity. It leaves other legacy rows null, including superseded rows with no remaining positive evidence. Null means inconclusive history; it must never be interpreted as false.
- A null marker on a newly inserted entity is initialized to false; updating a legacy entity preserves its null marker. No application history deleted before migration can be reconstructed by this upgrade. Confirm historical data completeness before using absence as permission in a future resolver.

Run the normal Config Flyway migrations on the **Config datasource**, never on the domain datasource. V63 backfills and installs guards transactionally. Check the next migration number against main before creating any subsequent migration. For a large history table, measure the upgrade and locking interval on a representative staging copy before deployment; this cut has no volume benchmark or production rollout claim.

## Retention and concurrency

PostgreSQL triggers prevent deletion or reidentification of materializations whose history is true or inconclusive. Identity includes materialization ID/key, definition ID, tenant, environment and the exact target coordinate. These guards also abort the existing definition deletion cascade. Never-applied drafts can still be removed.

Applied events are immutable and cannot be deleted, even when their materialization reference is already absent. `TRUNCATE`, including cascades from definitions, is blocked on the history tables. Administrative schema destruction or disabling triggers is outside this guarantee; the application database role must not own or disable these protections. Retention/export/restore procedures must preserve the evidence and coordinate identity. Do not remove and recreate an applied record as a draft to repair it.

`rowVersion` uses JPA `@Version`. A new entity, including one with an assigned UUID, starts with a null Java version so Spring Data persists it; the database version starts at zero. Hibernate updates advance by one. SQL updates that omit the version also advance by one, invalidating stale ORM writers. Other jumps, resets or null versions are rejected. Direct SQL callers needing optimistic comparison must supply the expected version in the WHERE clause and check the affected-row count; automatic increment alone is not compare-and-set.

A stale repository write becomes HTTP 409 in Config controllers, with guidance to reload current state. The response excludes entity IDs and database details. Reload and reassess the requested transition; do not blindly replay governance writes. This server-side optimistic lock does not implement client `If-Match` for existing materialization commands.

## Deliberate boundary of this increment

These guards preserve historical evidence and prevent lost updates to one materialization. They do **not** serialize application against concurrent definition deactivation. The next operational resolution increment must close that race, verify canonical hash/payload/effect, read one exact consistent snapshot with orphan events, and distinguish initial absence, eligible applied head, withdrawal, and inconsistent/unavailable state. The UX materialization list hides applied projections with inactive definitions and must not be used for admission. No new READY, bulk capability, ALLOW interpretation or host policy resolver is supplied by this migration.

## Focused proof

```sh
mvn -B -ntp -Dtest=DomainRuleApplicationHistoryPostgresTest,DomainRuleServiceTest,DomainRuleConcurrencyResponseTest,GovernedColorPalettePostgresMigrationTest test
```

`DomainRuleApplicationHistoryPostgresTest` executes the canonical rule migrations through V62, seeds legacy history, applies V63, and uses the resulting PostgreSQL schema for SQL and real Hibernate/Spring Data writes. Only unrelated FK prerequisite tables are fixtures; Hibernate schema generation is disabled. It proves exact positive/inconclusive backfill, monotonic retention, orphan events, delete/truncate cascades, rollback, unique-head replacement, successive ORM flushes and competing writes. The older palette test remains a V62 regression, not evidence that the new resolver or all downstream consumers exist. Validate the candidate JAR in the reference host before accepting integration; local SNAPSHOT proof is not a Maven Central release.
