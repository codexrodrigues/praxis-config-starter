# LK14 — visibility of a draft creation association

`UiLayoutDraftCreationLookupPostgresIT` has two opt-in cases: an independent JPA/service lookup must not observe an uncommitted insert, must recover its identity after commit, and must remain NOT_FOUND after rollback. This proves association visibility only, not the complete capture/producer transaction or production authentication.

Requires an existing exclusive `b1a_it_*` schema, migrated through V68 with V65/V68 success and the creation-key unique index present, UTF8, and explicit window admission. Uses the existing `PRAXIS_UI_LAYOUT_PG_JDBC_URL`, `PRAXIS_UI_LAYOUT_PG_USER`, `PRAXIS_UI_LAYOUT_PG_PASSWORD`, `PRAXIS_UI_LAYOUT_PG_SCHEMA` variables. Do not log credentials or create a schema/container/DB.

Hibernate is configured with hbm2ddl=validate and only the draft entity; it performs no schema update. The actual Spring Data repository and Config read service execute reads, with fixture invocation/admission. Writer and reader use independent connections in READ_COMMITTED; lock timeout 2s, statement timeout 5s. No sleeps or presumed lock timing. Own UUID/key fixtures only; AfterEach rolls back pending work and deletes only the own draft UUID/fixture tenant/actor, commits cleanup and closes connections/EM. No historical rows/captures are altered.

On the already admitted window, use the canonical JDK/Maven/cache and select this class alone:

```
mvn -o -Dtest=UiLayoutDraftCreationLookupPostgresIT -Dpraxis.ui-layout.pg.it=true test
```

Do not combine with UiLayoutLifecyclePostgresIT in a read-only validation window: that gate creates/migrates schemas. The official workflow below separates the admitted preparation and reader phases. Without opt-in this gate is disabled, and compilation/skipped tests are not DB evidence. In 6v it is compiled only: variables and authorized window were unavailable. Actual database execution remains pending.

## Execução no workflow oficial

O workflow manual `.github/workflows/domain-catalog-postgres-migration.yml` prepara uma janela descartável no serviço oficial `pgvector/pgvector:pg16`, com Java 21. O input `checkout_ref` deve ser o SHA imutável de 40 caracteres que contém o código e o workflow revisados. O dispatch deve usar a referência que contém esse mesmo commit: o gate compara checkout, `github.sha` e `github.workflow_sha` e nega proveniência divergente. Não disparar a partir de `main` antigo para validar código privado novo.

As fases são sequenciais: os dois gates existentes de catálogo/template usam o banco original; a preparação de UI-layout usa outro banco vazio, `praxis_config_ui_layout_test`, criado de `template0`, UTF8 e owner `praxis`. `UiLayoutLifecyclePostgresIT` prepara o schema exclusivo `b1a_it_<run>_<attempt>` pelo Flyway canônico. Só após essa preparação e a admissão de V65/V68, índice, owner, encoding e extensões no schema exclusivo são executados os dois readers. O JDBC `currentSchema` e o search path não incluem `public`; não se move nem se instala manualmente `vector` para consertar uma preparação incorreta.

Os readers continuam sem Flyway, DDL, repair ou clean. Após admissão concluída e readers iniciados, o workflow captura o catálogo posterior mesmo se Maven/tee falharem. Registra exits distintos da captura, comparação e validação XML; distingue falha de fase não iniciada, sem suprimir o resultado. Compara os campos estruturais registrados e o histórico/checksums antes e depois (não certifica todo o catálogo nem todos os dados); preserva XMLs separados, logs, exits reais de Maven e tee, timestamps, hashes da árvore e log do PostgreSQL, inclusive quando há falha. Exige as classes previstas, testes executados e zero failures/errors/skips; compilação e testes desabilitados não passam. O orçamento de 30 minutos cobre as fases sequenciais, sem cancelamento automático de uma execução anterior.

Essa janela usa o owner do serviço descartável; não prova permissões mínimas no Neon, execução HTTP do host ou captura/producer completa. O encerramento do serviço pertence ao lifecycle da VM do Actions; a limpeza das próprias fixtures continua a cargo dos testes. A preparação desse workflow, sem execução e evidência revisada, não fecha o gate PostgreSQL.
