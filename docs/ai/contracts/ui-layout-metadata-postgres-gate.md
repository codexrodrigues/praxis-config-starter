# Gate focal PostgreSQL de metadata/assembly

`UiLayoutMetadataCapturePostgresIT` usa o opt-in e as variáveis já existentes de `UiLayoutLifecyclePostgresIT`. A classe exige um schema `b1a_it_*` **existente, exclusivo para testes e previamente migrado até V68**. Ela não executa Flyway, não cria banco/schema e não faz clean. As fixtures usam UUIDs novos e transações locais com rollback em AfterEach; lock timeout é 2s e statement timeout 5s. Preflight confere schema, UTF8 e sucesso de V68 antes de inserir.

Configuração: `PRAXIS_UI_LAYOUT_PG_JDBC_URL`, `PRAXIS_UI_LAYOUT_PG_USER`, `PRAXIS_UI_LAYOUT_PG_PASSWORD`, `PRAXIS_UI_LAYOUT_PG_SCHEMA`. Não registrar valores ou credenciais em relatórios. Configurar essas variáveis não substitui a autorização da janela/alocação exclusiva. O gate antigo aplica migrations automaticamente; não combiná-lo por reflexo com este gate em uma janela somente de validação.

Na janela autorizada, usando o JDK/Maven normatizado e o cache aprovado:

```powershell
mvn -B "-Dtest=UiLayoutMetadataCapturePostgresIT" "-Dpraxis.ui-layout.pg.it=true" test
```

Sem `praxis.ui-layout.pg.it=true`, a classe fica desabilitada. Não contabilizar testes desabilitados ou compilação como prova PostgreSQL. O código preparado nesta etapa foi apenas compilado; as quatro variáveis estavam ausentes e nenhum banco foi consultado.

| Caso preparado | Camada/expectativa |
| --- | --- |
| Schema/assembly com whitespace, Unicode e ordem | JDBC preserva textos/hashes declarados exatos |
| Cada campo de assembly ausente | SQLSTATE 23514, sem escape por UNKNOWN |
| Versão não suportada | SQLSTATE 23514 |
| NativeIdentity sem assembly / com assembly | aceita / SQLSTATE 23514 |
| Assembly array/null | SQLSTATE 23514 |
| Texto multibyte acima de 256 KiB | SQLSTATE 23514 |
| Schema + assembly exatamente 1 MiB | aceita os dois pares |
| Composição 1 MiB + 1 byte | SQLSTATE P0001 do guard de quota |
| Outro par depois da quota exata | SQLSTATE P0001 |
| Mesmo alvo no draft | SQLSTATE 23505 |
| UPDATE/DELETE das próprias fixtures | SQLSTATE P0001 |
| Isolamento repeatable read | SQLSTATE P0001 |

## Cobertura ainda pendente

Este gate SQL não comprova host producer/admission, normalização, correlação B0 ou reprodução. O banco não recalcula SHA-256 nem rejeita todas as formas aceitas pelo parser JSON PostgreSQL (por exemplo duplicatas); integridade e parser estrito continuam no codec Java. A mera existência do hash não autoriza conteúdo.

Ainda preparar/executar: migração de um schema antigo com linhas históricas incompletas e leitura negada sem backfill; execução integrada do JDBC store/writer/JPA; rollback após insert parcial e revogação final; FK em escopo e draft liberado; TRUNCATE; concorrência multi-conexão entre captures/transições com observação de locks, sem inferência só por sleep; encoding incompatível em ambiente apropriado; invalid JSON/hash e seus gates Java; entrega/replay UX. A classe antiga de alocação não prova a concorrência do novo guard de quota.

A constraint NOT VALID de V68 impede novos inserts incompletos, mas não certifica todas as linhas históricas. Não executar VALIDATE CONSTRAINT para legitimar/fabricar evidências ausentes. Não iniciar EmbeddedPostgres/Docker ou criar fixture remota como atalho para a janela.
