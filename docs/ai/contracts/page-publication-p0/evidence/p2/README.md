# P2 / P1-D — evidência auditável

Fonte final de código/testes: `fef0cb2cf31ad0bd9be6fc0424dcdf41b92283f0` (produção idêntica a `8cc699ec`), base Config `468f9de696330be2ee16d181c5145cfe849dc29b`. Este diretório conserva logs e XML sanitizados, com hashes dos originais e das cópias no [manifesto](manifest.json). Revisão e integração são registradas no [PR #517](https://github.com/codexrodrigues/praxis-config-starter/pull/517); esta evidência não é release ou adoção por host.

## Rodadas e reaproveitamento

| Rodada | Fonte | Resultado e alcance |
| --- | --- | --- |
| Baseline | `17592e69917a30ceeea950a7e1eba8ef75b2dcae` | 206 testes: 192 unitários + 14 Neon; zero falhas/erros/skips. |
| Lifetime/savepoint | `cd91f4d0900580699e9b6bc49dd6efe2699569c9` | 13 unitários: 9 readset + 4 HTTP; zero falhas/erros/skips. |
| Entryflow final | `8cc699ec669d3210e28189e39f36043fe889d340` | 20 testes: 16 Neon + 4 HTTP; zero falhas/erros/skips. |
| Concorrência com barreira | `fef0cb2cf31ad0bd9be6fc0424dcdf41b92283f0` | 2 Neon focais; zero falhas/erros/skips. |

As contagens se sobrepõem e não devem ser somadas. O baseline não é apresentado como 206 testes executados em `8cc699ec`. Após o baseline, somente `PageCreationService` e `ConfigTransactionReadSet` mudaram em produção: validação após COMMIT/retorno, lifetime ao participar de REQUIRED e invalidação de bytes após savepoint. A rodada focal validou esses fluxos; as demais regressões permanecem vinculadas ao baseline. Os XML enumeram os testcases; o manifesto vincula cada rodada à versão de fonte e aos hashes de dependências. O delta `cd91f4d0..8cc699ec` só acrescenta a prova de rollback externo no teste; `8cc699ec..fef0cb2c` só reforça a fixture de concorrência. O lifetime13 foi congelado em cd91 imediatamente após o teste, sem parâmetro source no XML; as demais rodadas incluem p2.evidence.source nos originais, preservado pelo manifesto.

- [Baseline 206](baseline-17592-gates.txt) e [XML](baseline/xml/).
- [Lifetime 13](lifetime-unit.txt) e [XML](lifetime/xml/).
- [Entryflow final 20](final-entryflow-gates.txt) e [XML](final-entryflow/xml/).
- [Concorrência focal 2](concurrency-final.txt) e [XML](concurrency-pinned/xml/).
- [Preflight Neon](neon-preflight.txt), [selagem V70](neon-seal-ddl.txt) e [prova de isolamento PostgreSQL](neon-isolation-proof.txt).
- [Package](package-final.txt): JAR `0.1.0-rc.166`, apenas build local, sem install/deploy. Construído em `cd91f4d0`; `git diff cd91f4d0 fef0cb2c -- src/main pom.xml` vazio. O manifesto registra SHA-256 do JAR e dos fontes/dependências.

O ensaio final usa `PageCreationPostgresIT,PageCreationHttpTest` com `-Dp2.evidence.source=8cc699ec669d3210e28189e39f36043fe889d340 -Dpraxis.page.p2.neon.it=true`. O addendum seleciona somente os métodos concurrentSameCommandHasOneDurableIdentity e concurrentDifferentCommandsHaveOneWinnerAndOneConflict em PageCreationPostgresIT, com source=fef0cb2c. A seleção completa do baseline consta no manifesto. Todos usam Maven local isolado, Java 21.0.10, sem instalações Maven compartilhadas. `PRAXIS_PAGE_P2_NEON_RUNTIME_FILE` aponta para um arquivo privado de atribuição/credenciais; ele nunca integra este diretório. Reexecutar requer uma branch Neon isolada explicitamente atribuída e seu próprio preflight, não credenciais copiadas desta prova.

## Identidade e privilégios

Projeto `ancient-leaf-72055027`, branch `br-aged-bonus-a4rhfqwp` (`page-publication-p2-510-20261022`), endpoint direto `ep-withered-rice-a48n0xi0.us-east-1.aws.neon.tech`, schema exclusivo `p2_page_510_20261022`. PostgreSQL 17.11, TLS verify-full/TLSv1.3. Runtime `p2_page_510_runtime` sem superuser/createdb/createrole/bypassrls; owner `neondb_owner` somente no preparo. A branch pai `br-empty-sea-a4kvwyqz` permaneceu sem mutação.

A fixture aplica somente V64/V65/V66/V67/V68/V70 no schema atribuído. Não é prova de upgrade integral de todos os módulos. Conexões independentes verificam persistência/rollback. UPDATE em DRAFT é permitido; UPDATE body/ETag/state em RELEASED é recusado pelo trigger. DELETE/TRUNCATE com runtime são recusados por ACL: isso não é apresentado como execução dos triggers por owner. O DDL e a introspecção complementam essa limitação. JPA real congela, faz flush duas vezes, clear/read e usa review/head sem nova escrita no draft RELEASED.

## O que a prova final cobre

Criação atômica, replay sem recaptura, comando divergente, concorrência igual/divergente, lost response, falha após cada INSERT, rollback-only, revogação no último callback, READ_DRAFT no commit do lookup, troca de app/contexto, novo draft por outro autor, cópia histórica de configuração evoluída, release nunca publicada recusada, dependência revogada e grupos sem PageHost com root estável/guard central. A barreira da fixture prova que duas conexões JDBC subjacentes distintas chegaram ao mesmo escopo/chave antes de qualquer execução do lock, com espera limitada a 30 segundos; não foi consultado pg_locks nem observada diretamente uma espera no servidor. Após ambas as futures, conexões independentes confirmam um catálogo/evidência/draft/captura; comando igual retorna created true/false, e divergente tem um vencedor e INVALID_STATE/409. Os novos cenários comprovam rollback e commit de create+lookup sob transação externa, e COMMIT real seguido de resposta tardia em create/lookup próprios e no owner externo. Resposta tardia é resultado incerto: a identidade persistida reconcilia com a mesma creationKey, sem declarar rollback nem recapturar.

A fixture `fault="commit"` lança em TransactionSynchronization.beforeCommit após as escritas; isso prova rollback por falha no callback anterior ao commit, não uma SQLException do JDBC COMMIT. Atraso depois do COMMIT delegado bem-sucedido é outro cenário, com resultado incerto. Não foi simulada rejeição do COMMIT pelo servidor nem validada uma política rollbackOnCommitFailure.

Os unitários incluem o corpus P0 de cópia, ingresso JSON bruto fechado, HTTP 201/200/409 e erros sanitizados/no-store, autoconfig completa/parcial/ausente, tamper de ledger/hash/mapa/patch, produtor sem ledger, ciclos/limites, deadline compartilhado, grants não cacheados, READ_COMMITTED fixado por SELECT e propagação REQUIRES_NEW/savepoint com JDBC simulado. O teste de savepoint não estabelece suporte nested JPA ao lifecycle.

## Sanitização e limites

Os XML perdem o bloco inteiro `properties` (paths/configuração de processo); testcases, tempos e saídas são mantidos. Paths locais são substituídos por marcadores. O export confere os segredos efetivos dos arquivos privados e padrões de URL com credenciais, recusando export se encontrados. Nenhum arquivo de credencial ou ambiente foi versionado. Originais completos permanecem nos caminhos `/tmp` registrados no manifesto; hashes permitem confrontá-los. Logs vermelhos de diagnóstico ficam identificados por hash e causa, sem serem contados como gate verde.

Não foi rodada suite integral Config, HTTP de Quickstart, UI/E2E, P3/P4 ou release. Métricas de duração/SQL são desta fixture e rede, sem SLA de produção. Revisão independente de fonte/testes foi feita pelo coordenador e peers; a execução foi feita pelo autor no recurso exclusivo. Skills canônicas atualizadas e sincronizadas no incremento `7988937c2b2fbb02ae569257da0e987fa783203a` (PR #825, após #823/#824).

Ao encerrar, os processos de teste, aplicações Spring, SessionFactory e conexões foram encerrados; executors usam awaitTermination limitado. A branch/schema permanecem preservados para auditoria com autosuspend300. Nenhuma reutilização ou mutação por P3 está concedida sem nova atribuição explícita.
