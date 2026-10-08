# LK14 — visibility of a draft creation association

`UiLayoutDraftCreationLookupPostgresIT` has two opt-in cases: an independent JPA/service lookup must not observe an uncommitted insert, must recover its identity after commit, and must remain NOT_FOUND after rollback. This proves association visibility only, not the complete capture/producer transaction or production authentication.

Requires an existing exclusive `b1a_it_*` schema, migrated through V68 with V65/V68 success and the creation-key unique index present, UTF8, and explicit window admission. Uses the existing `PRAXIS_UI_LAYOUT_PG_JDBC_URL`, `PRAXIS_UI_LAYOUT_PG_USER`, `PRAXIS_UI_LAYOUT_PG_PASSWORD`, `PRAXIS_UI_LAYOUT_PG_SCHEMA` variables. Do not log credentials or create a schema/container/DB.

Hibernate is configured with hbm2ddl=validate and only the draft entity; it performs no schema update. The actual Spring Data repository and Config read service execute reads, with fixture invocation/admission. Writer and reader use independent connections in READ_COMMITTED; lock timeout 2s, statement timeout 5s. No sleeps or presumed lock timing. Own UUID/key fixtures only; AfterEach rolls back pending work and deletes only the own draft UUID/fixture tenant/actor, commits cleanup and closes connections/EM. No historical rows/captures are altered.

On the already admitted window, use the canonical JDK/Maven/cache and select this class alone:

```
mvn -o -Dtest=UiLayoutDraftCreationLookupPostgresIT -Dpraxis.ui-layout.pg.it=true test
```

Do not combine with the old UiLayoutLifecyclePostgresIT: that legacy gate creates/migrates schemas. Without opt-in this gate is disabled, and compilation/skipped tests are not DB evidence. In 6v it is compiled only: variables and authorized window were unavailable. Actual database execution remains pending.
