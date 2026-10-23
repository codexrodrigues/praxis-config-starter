# P3-A — integridade entre páginas e grupos

Estado: direção aprovada pela coordenação em 22/10/2026; implementação e provas ainda não executadas. Base Config `78e914fdc1f03c7abf7e201fd2d2a79fc561f26f` (P2 integrado). Issue [#511](https://github.com/codexrodrigues/praxis-config-starter/issues/511). Esta etapa não entrega P3-B, adoção HTTP dos hosts, deploy ou release.

## Fonte, inventário e classificação

A mudança é **arquitetural** e altera contrato público **server-only**. P0 `TECHNICAL-PLAN.md` §§3.2–3.3 e cenários de integridade determinam heads independentes, rejeição de omissão de grupo usado, locks da união anterior/candidata, releitura sob locks e aborto/repetição quando essa base muda. O schema nativo e o corpus de cenários permanecem canônicos; uma prova Config não equivale ao gate HTTP dos hosts.

| Aderência | Plataforma atual | Decisão |
| --- | --- | --- |
| `ja-suportado-so-ux` | Draft/review/head têm ETags, motivos e eventos; os snapshots preservam estrutura e bindings próprios. | Reutilizar as transições e receipts existentes. |
| `ja-suportado-mal-nomeado-ou-mal-materializado` | O lifecycle já publica/retira/retrocede releases; app-shell está estruturalmente bloqueado aguardando P3-A. | Instalar a integridade no owner efetivo, sem endpoint paralelo. Atualizar estado documental preservando o histórico P0. |
| `suportado-parcialmente` | Codecs page/groups, reprodução B0+patch, catálogo P2, RELEASED selado V70, conexão Config e rechecks atuais existem. | Acrescentar locks de referências, leitura autoritativa entre heads e gate final. Revision-allocation lock não é lock de referência. |
| `lacuna-real-de-contrato` | `PageCreationHost.resolveLifecycle` atesta app/namespace de uma invocation conhecida, mas não encontra o root de grupos a partir de uma página. O root do contexto é uma âncora registrada, não necessariamente app-shell. | Uma assinatura abstrata no host existente, descrita abaixo. |

Fontes de código: `UiLayoutLifecycleService` (`moveHead`, `withdraw`, `Scope`), `UiLayoutLifecycleCommandService` (`validateHeadCondition`, `frozenRelease`, `inAuthorizedConfigTransaction`), `PageCreationHost`, `PageCreationContext`, `UiLayoutLifecycleInvocationProvider`, `UiLayoutReleaseHeadRepository`, `UiLayoutFrozenWorkspaceResolver`, `ConfigJdbcTransactionGuard`, `ConfigTransactionReadSet` e migrations V64/V70. A linha atual de skills é `praxis-codex-skills@7988937c2b2fbb02ae569257da0e987fa783203a`, especialmente `praxis-config-runtime-persistence` e `references/governed-layout-integrity.md`.

## Fronteira mínima e responsabilidades

Nova assinatura, abstrata e sem default, em `PageCreationHost`:

```java
UiLayoutTarget navigationGroupsRoot(PageCreationContext currentContext);
```

Ela devolve o root app-shell canônico do registro atual do host; não concede acesso. O mapping é estável por tenant/application/environment/namespace e **injetivo sobre o head físico tenant/environment/type/id**: duas aplicações/namespaces não podem compartilhar esse head. `resolveLifecycle` precisa atestar a inversa independentemente, com a invocation root-bound. Config exige UUID canônico, composição de um root e igualdade de ator/tenant/unidade/environment/contextVersion/application/namespace. Para comando de grupos, o root solicitado precisa ser o mesmo devolvido pelo mapping. Unidade, módulo, texto de header e ator não fornecem applicationId. Uma fixture de registro invertível prova estabilidade e rejeição de alias.

A factory beta `UiLayoutLifecycleCommandService.create` recebe adicionalmente `PageCreationHost`. A assinatura anterior é substituída intencionalmente, sem overload de compatibilidade. Nenhum DTO, endpoint, schema wire, flag ou SPI público de permit é adicionado. A ausência do host mantém drafts e demais funcionalidades que já existiam, mas nega transições de heads page/groups.

Um owner final package-private de integridade é instalado pela factory canônica e chamado dentro do core efetivo de `publish`/`rollback`/`withdraw`, recebendo invocation e validation. O core sem owner nega page **e** groups mesmo com policy/releaseValidator permissivos. Não há boolean `validated`, registry de autoridade, cache de grants ou bean substituível de autorização. Os SPIs atuais continuam donos de contexto, lifecycle grants, metadata/producer e estrutura.

## Fonte autoritativa de referências

O head existente continua a única autoridade publicada. A primeira implementação usa leitura autoritativa em lote, não um índice eventual nem tabela paralela. Para grupos, percorre o catálogo append-only por **keyset UUID estável**, no application/tenant/environment/namespace corrente, correlacionando cada head ativo à release e ao workspace RELEASED selado, membros e revisões fixadas. A reprodução histórica permanece íntegra; não recaptura fonte nem usa latest/head de template. O scan não usa OFFSET, READ_PAGE ou unidade do ator para filtrar páginas referenciadoras; uma página privada também impede remover seu grupo.

As leituras em lote evitam N+1 e não carregam todos os documentos ao mesmo tempo. O limite individual existente de 256KiB e o deadline monotônico do attempt continuam aplicáveis. Bytes processados e custo SQL são medidos; não existe limite arbitrário novo de número de páginas nem truncamento silencioso. O limite 16/8MiB de proveniência P2 não é reutilizado como limite de catálogo. Uma fixture densa avaliará o custo antes do freeze; índice transacional futuro exigiria prova de completude/migração, não começar vazio.

Novas transições de página exigem identidade do catálogo P2. Qualquer head ativo de página sem identidade atribuível ao catálogo no tenant/environment torna a checagem de grupos indisponível, com diagnóstico sanitizado sem IDs. É uma pré-condição explícita de adoção/migração P5, sem inferência, backfill automático ou compatflag. Não se promete que hosts anteriores ao catálogo já sejam adotáveis.

## Locks, releitura e transação

1. Resolver contexto e mapping atuais; flush de ownwrites JPA antes de ler a projeção JDBC. Planejar a união de groupIds do head anterior e candidato fixado: `navigation` para páginas, `groups` para app-shell. Retirada usa candidato vazio.
2. Adquirir `pg_try_advisory_xact_lock` dos groupIds ordenados em scope tenant/application/environment/namespace. Unidade, ator e contextVersion não particionam o lock. Chaves têm codificação inequívoca; colisão de hash só pode causar contenção adicional.
3. Adquirir try-lock do root físico tenant/environment/type/id, inclusive quando ainda não existe head; depois tomar o row lock desse head. Não tomar row locks dos heads dependentes nem de todas as páginas do scan.
4. Reatestar contexto/mapping e reler os tuples JDBC na mesma conexão Config. Atualizar explicitamente a entidade JPA sob lock: `findForUpdate` não é considerado refresh do primeiro nível. Se a base usada para o conjunto de locks mudou, abortar em conflito; não ampliar locks fora da ordem dentro da tentativa. ETag antigo continua falha de precondição.
5. Validar referência de página contra o head de grupos atual e rejeitar remoções/omissões de grupos usados por qualquer página ativa do escopo. Aplicar somente o head/evento do agregado solicitado.
6. No `beforeCommit`, flush de ownwrites e checagem da invariável no **estado final** sob os locks retidos, seguida de autoridade/contexto atuais após o último SPI. Cada comando conserva seu próprio attempt/grants. Múltiplas transições do mesmo root, por exemplo A:G1→G2→G3, não são rejeitadas apenas porque o primeiro candidato foi legitimamente substituído na mesma transação.

Try-lock evita deadlock de advisory mesmo quando uma transação REQUIRED executa várias operações em ordem global inversa. Contenção ou conjunto alterado marca **toda a transação Config** irreversivelmente para rollback e retorna conflito para reload/retry explícito em nova transação. Não há retry interno, REQUIRES_NEW ou registry de locks/grants. A marca global usa o owner JPA real e uma synchronization irrevogável até completion quando necessária: engolir a exceção ou rollback de savepoint não pode restaurar a possibilidade de commit. O teste de helper com savepoint não declara suporte de negócio a nested JPA.

Heads e decisões de admissão nunca entram no immutable readset. O attempt não é renovado durante lote, extensões, callbacks ou participação externa. Uma chamada participante tem retorno provisório até o commit externo, conforme o contrato existente. Exceção depois do commit real não deve ser descrita como rollback.

## Matriz mínima de prova

- Publish de página versus publish com omissão, rollback e withdraw de grupos, com barreiras JDBC determinísticas e vencedores alternados nas ordens controladas; uma corrida geral pode abortar ambos legitimamente. Conexão nova comprova ausência de referência ativa órfã, rollback integral e retry explícito.
- Duas transições por transação REQUIRED em ordens inversas; contenção, exceção engolida e rollback de savepoint não permitem commit parcial. A:G1→G2→G3 na mesma transação passa quando o estado final é válido.
- Head inicial concorrente; alteração entre plano e locks exige conflito e nova tentativa com novo ETag/conjunto; EntityManager previamente carregado prova freshness.
- Rollback A restaura estrutura e vínculos próprios e não altera B ou grupos; dependência removida/negada mantém head de A.
- Escopos application/tenant/environment/namespace e unidades distintas; páginas privadas contam; mapping alternativo/stale/alias, ausência host/catálogo e head não atribuível negam sem exposição de existência.
- Falhas após writes de head/evento e revogação no último callback anterior ao commit revertem integralmente por conexão nova. Não confundir esse callback com rejeição do COMMIT pelo servidor.
- Rejeição real de COMMIT pelo servidor: INSERT runtime de catálogo sem evidence em callback test-only exercita a FK deferrable V70 (SQLSTATE23503) após writes head/event; conexão nova comprova rollback e ausência do órfão, sem DDL de ensaio.
- Estrutura/metadata/producer revogados ou snapshot/patch malformados negam; ETags draft/review/head permanecem fortes; callback tardio não renova budget.
- Lote denso mede SQL/bytes/tempo e nunca aceita scan parcial. Testes HTTP focais preservam headers/precondições; não atestam host operacional/P3-B.

## Impacto e recursos

Config: core/command/factory/autoconfig, `PageCreationHost`, helpers internos de leitura/reprodução/locks/transação e fixtures/testes focais. Docs: README/TECHNICAL-PLAN/P1-D/P3-A e evidência auditável. Nenhum consumidor atual Quickstart/Angular/Hero identificado usa PageCreationHost ou a factory; adoção futura deve implementar o callback e cumprir o catálogo. Não alterar corpus para declarar gates HTTP não executados. Risco de breaking change é intencional na SPI server-only beta.

Skills: `atualizar-existente`; protocolo multi-head, mapping, fail-closed de adoção e prova transacional precisam ser ensinados na skill Config existente. A coordenação é escritora do incremento canônico e revisará antes da conclusão do pacote.

Worktree exclusiva `config-page-p3a-511`, branch `codex/page-group-integrity-p3a-511`, Maven `/tmp/praxis-page-p3a-511-m2`, target próprio. Neon exclusivo: projeto `ancient-leaf-72055027`, branch `br-holy-water-a4ihmkkj`, endpoint `ep-hidden-bread-a4x3zddv.us-east-1.aws.neon.tech`, schema `p3a_group_511_20261022`, runtime `p3a_group_511_runtime`, compute0.25–1CU/autosuspend300. Pai homolog e recurso P2 não são mutáveis/reutilizáveis por este ensaio. Preflight API/read-only confirmou PG17.11 e owner privilegiado; TLS direto verify-full, papéis/DDL e gates operacionais ainda pendentes nesta revisão do desenho. Nenhuma evidência owner substitui runtime least-privilege.
