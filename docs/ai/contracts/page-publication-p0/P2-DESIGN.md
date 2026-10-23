# P2 — desenho aprovado e implementação em validação

Estado: direção A e assinaturas server-only aprovadas pela coordenação; implementação entregue com [evidência versionada](evidence/p2/README.md); revisão e integração registradas no PR #517. Base integrada Config `468f9de696330be2ee16d181c5145cfe849dc29b` (inclui codec grupos P1-D); issue #510. Inventário original auditado em `6144b485`, preservado abaixo como fonte de decisão.
Classificação: contrato-publico/transversal. Autor `p2_catalog`; revisão coordenador.

## Aderência e restrições verificadas

- `UiLayoutLifecycleCommandService:129–202` já cria draft, captura root/filhos, valida origem, faz flush JPA para JDBC e revalida antes do commit. Reutilizar; `ja-suportado-mal-nomeado-ou-mal-materializado` na jornada de criação.
- `UiLayoutLifecycleInvocation:6–12` carrega actor/tenant/administrativeUnit/environment/contextVersion/composition. `EffectiveUiAudience:9–18` também não tem aplicação/namespace. `EnterpriseRuntimeEffectiveContext` tem módulo **hint**, explicitamente não autoridade. `UiLayoutLifecycleInvocationProvider` depende de root já registrado. Usar módulo/unidade como aplicação seria inferência indevida. Identidade por aplicação e namespace registrado antes de alocar root é `lacuna-real-de-contrato`.
- `UiLayoutCompositionRegistry:4–6` é resolução host-owned sem persistência. `UiLayoutCompositionRegistration:7–18` já impõe página UUID e root único. O catálogo deve persistir identidade/namespace/vínculo à criação, não título/head/corpo atual. Persistência de identidade é `lacuna-real-de-contrato`.
- `UiLayoutBaselineMetadataSeed:18–19,74–75` fecha native source em NativeIdentity. `UiLayoutMetadataCaptureCodec:121–132` exige raw nativo igual ao B0, inclusive ordem. Copiar fonte e trocar IDs/título exige evidência de transformação; chamar source externo original de NativeIdentity seria falso.
- `UiLayoutPageChildCaptures:28–47` exige seed.document igual ao filho inicialmente incluído. Uma cópia de revisão publicada pode ter config evoluída em relação ao B0 original: reutilizar a fonte original como se produzisse a configuração final seria falso. As capturas originais precisam continuar evidência, com reprodução e admissão atuais.
- `UiLayoutFrozenWorkspaceResolver:35–99` já reconstrói release exata, correlacionando draft/members/revisões/assignments e capturas. Falta comprovar evento publicado e escopo de aplicação do catálogo. Reutilizar, nunca consultar latest/effectiveLayout.
- `UiLayoutPageRevisionReproduction` já valida perfil root/Form/Table/bindings, sem descartar autoria; `CanonicalJsonHashService.sha256Exact` já fornece identidade de conteúdo. Mapa tipado e transformação são `lacuna-real-de-contrato` coberta pelo P0 e codec Core integrado.

## Contratos P0 reutilizados

`createCommand`, `source` (registered-native ou published-page exata), `creationReceipt`, `copyEvidence`; transformação `praxis.page-copy/1`; UUID por servidor, typed map widget/link, endpoints por mapa widget, navegação vazia, preservação de strings comuns. HTTP POST pages e GET by-creation-key. Sem descoberta/runtime P3 nem publish paralelo. CreationKey ator+app+tenant+env; contextVersion não altera identidade do comando, mas precisa ser atual em todas operações.

## Fluxo implementado

1. Resolver contexto autenticado atual independente de root: ator/tenant/env/unidade/contextVersion + app/namespace registrados. Reutilizar as cinco dimensões existentes; acrescentar somente app/namespace na fronteira páginas, sem reescrever lifecycle global.
2. Autorizar CREATE, READ_SOURCE e COPY_SOURCE explicitamente; reading não implica copy. Serializar por chave exata via lock PostgreSQL transacional. Revalidar contexto após lock. Replay igual retorna catálogo e ETag atual do draft; divergente409. Não recapturar nem transformar replay. Lookup não exige integridade completa/publicação, somente autoridade atual de ler associação e draft.
3. Resolver registered-native no SPI host de fonte exata; published-page no frozen resolver Config + catálogo mesmo app/tenant/env + evento de publicação da release/revisão exata. Capturar autoria original e evidência original dos filhos. Validar/reproduzir todos os filhos sob autoridade atual e limites existentes.
4. Transformar deterministicamente com mapa tipado server-generated, produzir output + sourceHash/outputHash/transformRef. Registrar evidência imutável correlacionada com criação/draft; não gravar título/status/head duplicados no catálogo.
5. Criar root registrado e draft pelo lifecycle existente na mesma transação Config. Não herdar assignments/reviews/approvals/head/menu. Todos writes e capturas participam da mesma conexão/transação. beforeCommit repete contexto, grants e admissão da fonte original/capturas; qualquer falha reverte tudo. ContextVersion histórico nunca concede autoridade.
6. A integração de leitura/edição/freeze/publicação usa o registro durável de página para resolver root e a admissão vigente; não existe cache mutável paralelo de registro nem thread-local que desapareça antes de callbacks do commit.

## Opções de reprodução para decisão

A. **Decisão aprovada**: output imutável é documento nativo produzido pelo Config, apontado por NativeDocumentSource de criação (producer=transformRef, revision=outputHash). NativeIdentity reproduz esse output, enquanto a admissão canônica Config comprova source original + transformação + mapa + captura dos filhos. Filhos copiados são snapshots nativos do mesmo produtor, nunca falsamente apresentados como B0 de uma fonte host antiga. Source JSON/capturas ficam evidência imutável da criação, não corpo autoral mutável. É obrigatório verificar o registro completo, hash e reprodução a cada admissão, e revalidar grants da fonte selecionada na criação e dependências atuais da cadeia. Nenhum simples prefixo de sourceId ou existence check concede admissão.

B. Novo Reproduction PageCopy no codec metadata e nova origem/colunas/constraints. Expressa transformação diretamente no codec, mas muda toda superfície de evidência (JDBC mapper, V68 constraints, mixins filhos, owner admission) e requer correlação recursiva de fontes publicadas/filhos. Mais explícito, maior impacto. Só adotar se A não puder preservar invariantes existentes sem fingir fonte.

Rejeitados: source original + NativeIdentity após alterar conteúdo; copiar última configuração effective; novo endpoint publish; root só em memória; default permissivo para fonte/metadata; host atestar qualquer JSON recebido; duplicar head/título no catálogo; revalidar somente na entrada.

## Impacto, provas e recursos

Config é dono. Consumidores futuros Core/Page Builder/Quickstart P3/P4; nenhuma UI/host ativada neste corte. Docs P2 e fontes P0; Landing/corpus operacional pertencem P6, sem anunciar endpoints antes da prova. Skill persistence atualizar-existente após contrato aprovado e prova; domain-decisions existente-adequada. Escrita skills exclusiva coordenador.

Unitários: copy/paridade, schema fechado, ausência/null, autorização/escopo/context, replay/divergência, reabertura, statusHTTP. Neon branch `br-aged-bonus-a4rhfqwp` projeto `ancient-leaf-72055027`, schema exclusivo `p2_page_510_20261022`, atribuição coordenador 22/10/2026. Provas JPA/JDBC runtime restrito, falha de cada etapa/commit, chamadas concorrentes e leitura independente. Preflight e ensaios remotos realizados em PostgreSQL 17.11/TLS verify-full, papel `p2_page_510_runtime` sem superuser/createdb/createrole/bypassrls. Rodada inicial 5 IT green; rodada autoconfig + fault-after-insert/rollback-only 2 IT green e 15 testes do serviço central green. Evidência final/proveniência será congelada no P2-IMPLEMENTATION; estas rodadas são checkpoints, não aceite. Target da worktree e Maven `/tmp/praxis-page-p2-510-m2` exclusivos.

## Decisão de coordenação e assinaturas para revisão

Em 22/10/2026 a coordenação aprovou a direção A condicionada à cadeia original integral,
reprodução obrigatória e revalidação atual. Não aprova prefixo, exists ou hash isolados.
Cadeia transitiva limitada a 16 criações, cycle detection por pageId e limite agregado de bytes
conservador; atingido limite nega sem truncar. A publicação histórica exata é demonstrada por
release/member/revision e evento PUBLISH/ROLLBACK persistido, nunca pelo head corrente.

API mínima aprovada e implementada:

- `PageCreationContext(String applicationId, String routeNamespace, UiLayoutLifecycleInvocation invocation)`:
  contexto server-only. Reutiliza a invocation autenticada existente, cujo root é uma composição
  host previamente registrada usada como contexto; para a página, rebasa somente composition com
  o root durável do catálogo. application/namespace são attestados separadamente pelo host,
  não derivados do root, unidade ou module. Toda rechecagem compara o record completo.
- `PageCreationHost.resolve(Principal principal, String contextVersion) -> PageCreationContext`.
- `PageCreationHost.require(PageCreationOperation operation, PageCreationContext context, PageSourceReference source)`:
  enum CREATE_PAGE / READ_CREATION / READ_SOURCE / COPY_SOURCE. source nula apenas quando a operação
  não seleciona origem. Lifecycle admission de leitura/edição/review/publicação continua o SPI existente.
- `PageCreationHost.captureNative(PageCreationContext context, PageSourceReference source) -> UiLayoutDraftWorkspaceSeed.TargetSeed`:
  apenas registered-native exata. Reutiliza seed existente com root e evidência de filhos; fonte publicada
  é resolvida pelo Config, jamais pelo host via JSON solto.
- `PageCreationHost.requireNative(PageCreationContext context, PageSourceReference source, UiLayoutDraftWorkspaceSeed.TargetSeed captured)`:
  re-admite a captura completa antiga sem buscar latest; valida vínculo sourceId/revision com seed e
  fonte original. Reutiliza `UiLayoutBaselineMetadataAdmission` e `UiLayoutDraftWorkspaceSource.requirePageChild`
  para metadata/filhos, não os substitui.
- `PageSourceReference`: union P0 fechada registered-native(sourceId,revision) ou
  published-page(pageId,releaseId,revisionId); JSON sem campos arbitrários.
- `PageCreationCommand`: creationKey/source/title P0. `PageCreationReceipt`: exatamente receipt P0.
- `PageCreationService.create(Principal,String,PageCreationCommand)` e `byCreationKey(Principal,String,UUID)`;
  `PageCreationController` nos dois endpoints P0. Nenhum endpoint de listagem P3.

Todo o restante é interno/package-private: transformação, ledger/store, wrappers das seams
invocation/source/metadata. A auto-configuração só instala páginas quando o host fornece
PageCreationHost junto das seams existentes; defaults ausentes permanecem fechados. O wrapper de
invocation consulta identidade durável por app/tenant/env antes de rebasing; não altera a invocation
pública global. `UiLayoutLifecycleCommandService` recebe somente overload package-private de
createDraft com UUID previamente reservado para FK diferível da criação, sem duplicar lógica.

Negativas mínimas: app/tenant/env errado, READ sem COPY, callback contexto A→B→A antigo,
revogação no commit, source exata ausente, draft como origem, release congelada nunca publicada,
revision de outra release, mapa incompleto/injetividade/tipos, hash/bytes/captura adulterados,
ciclo/limite transitive, metadata antiga vs config evoluída, replay diferente e concorrente,
falha depois de cada write, nenhuma herança de head/assignment/approval/menu, origem preservada.

## Decisions confirmed during implementation review (2026-10-22)

- Selected source READ_SOURCE/COPY_SOURCE apply at create/replay and before its commit. They do not grant content visibility into ancestry and do not become perpetual ancestor ACL requirements for independent copies. Historical producer integrity, exact publication/revision correlation and current domain/metadata dependency admission remain required throughout the chain. Lookup by creation key requires READ_CREATION and the existing READ_DRAFT, without requiring current permission to the old selected source. Receipts expose only their own selected source reference, never ancestral ledger contents.
- Native output capture separates immutable Config producer identity/B0 from the current observation of a new draft. Alice/context1 evidence remains unchanged; Bob/context2 receives a new observation with the identical immutable output. Source observations retained in the private ledger are never rewritten or used as grants.
- `resolveLifecycle(invocation)` is an abstract server-only host attestation, necessary because existing metadata/admission callbacks lack Principal/application. Host must independently attest actor, tenant, unit, environment, context version, application and namespace; the invocation is correlation only. Exact invocation equality is required before app-scoped SQL or rebasing. No authority/context cache, ThreadLocal attestation, fabricated principal or ledger-derived grant is permitted. The later immutable read set never stores an attestation or admission decision.
- Navigation groups reuse the existing host `UiLayoutLifecycleInvocationProvider` mapping: exactly one stable app-shell UUID per tenant/application/environment/namespace. The request cannot register a second root. No additional groups registry/table or host SPI was introduced. Shape validation in `UiLayoutCompositionRegistration` does not replace host registration. Operational head transitions are blocked in `UiLayoutLifecycleService` itself until P3-A, including direct callers with permissive policy/validator and absent PageCreationHost. This is the historical P2 boundary; the later mandatory integrity owner and its gates are documented in [P3A-IMPLEMENTATION](P3A-IMPLEMENTATION.md).
- Admission before a Config transaction re-attests current context and host policy. Provenance reads requiring the Config connection run during the same transaction and again before commit, not via an unrelated connection outside the transaction.
- Resource policy is closed: maximum 16 creation nodes per ancestry path, 8 MiB total distinct ledger bytes per traversal and 2,048 traversal checkpoints; deny instead of truncating. These bounds cap recursive reproduction and repeated metadata work while preserving an auditable complete chain. All nested validation uses the remaining monotonic outer budget; entry/return/commit checks reject late results, without claiming cancellation of blocked I/O.
- `ConfigJdbcTransactionGuard` is package-private reuse of the existing metadata store connection invariant, including READ_COMMITTED, the actual JPA/JDBC connection, active transaction and rollback-only detection. No new transaction SPI was added. Catalogue, original ledger, draft and metadata capture are one atomic transaction; an explicitly rollback-only result cannot be acknowledged as creation.

### Current wiring additions (existing SPIs returned, no adapter beans)

`PageCreationService.invocationProvider(UiLayoutLifecycleInvocationProvider)`,
`lifecycleAdmission(UiLayoutLifecycleAdmission)`, `workspaceSource()` and `metadataAdmission()`
return private wrappers for the existing seams. Auto-configuration retains one original host bean per seam.
The page service holds lazy command/read suppliers; wrappers hold only original delegates. The actual
application-context integration test starts all services and performs the first creation/replay/read.
Absent PageCreationHost leaves normal lifecycle operational and page endpoints absent.

### Transaction-local immutable read reuse (reviewed adjustment)

The Neon diagnostic measured a published copy at 46,743 ms with 295 JDBC statements
(40,599 ms in JDBC) against the unchanged 40-second host validation budget. Repeated
resolution of the same immutable publication was structural work, so increasing the
budget alone is not the correction.

The bounded internal read set belongs to the current Config transaction synchronization,
identified by its store owner and the exact guarded JDBC holder/connection. Suspended
synchronizations are not visible to REQUIRES_NEW; resume restores the original set, and
completion clears it. Outside an active synchronized transaction, reads follow the normal
path. The cache contains only positive serialized immutable data, with defensive decoding:
app/tenant/environment/page catalogue plus creation evidence, and an exact
app/tenant/environment/namespace/page/release/revision source snapshot after the historical
publication event, frozen workspace and correlated captures have been read successfully.
Creation-key lookup remains SQL; no actor-sensitive receipt or mutable draft state is cached.

Each hit still requires the Config connection guard. Context/namespace matching, host
resolution, grants, source/native/domain admission, metadata and structure callbacks,
transformation and hashes remain current and execute again, including beforeCommit.
No negative lookup, current head, admission result, authority context or verified flag is
retained. A frozen-snapshot hit repeats the current target structure callback normally
invoked by the frozen resolver. A positive publication event proves only exact history.

The internal read set uses at most 16 entries and 8 MiB of UTF-8 values across its owner;
least-recently-used eviction causes a full reload and does not truncate provenance or relax
chain/deadline limits. Oversized values are read normally without retention. Tests cover
same-transaction reuse, separate owner/connection, suspend/REQUIRES_NEW/resume, completion,
defensive values, bounded eviction, uncached misses and current revocation after a hit.
Published-copy measurements are recorded per run on the same isolated Neon branch and
40-second budget; they are evidence of this test workload, not a latency/SLA claim.

The frozen workspace inventory found no legitimate post-RELEASED mutation:
`UiLayoutLifecycleService` creates DRAFT, gates edits/revision/assignment/freeze through
`editableDraft`, and performs the sole final body/state/ETag write at freeze. Reviews
and heads update separate records. V70 now seals UPDATE/DELETE of OLD.RELEASED and
TRUNCATE using the existing immutable-record function. This applies to page, form,
table and navigation-group frozen workspaces; no backfill or historical rewrite occurs.
A proposed mutable-body fingerprint fallback was rejected because it would preserve
a physical mutation path inconsistent with this lifecycle rule. Published copy also
requires reproduction against the exact persisted selected patch before snapshot reuse.

PgJDBC 42.7.11's `PgConnection.getTransactionIsolation()` executes `SHOW TRANSACTION
ISOLATION LEVEL` (verified from the effective dependency bytecode). The first readset
implementation therefore still repeated remote SQL through a JDBC getter. A focused
Neon probe of 20 reads measured one ledger SELECT but 21 isolation calls taking 2,942 ms
of 4,687 ms total; 22 autoCommit getters took less than 1 ms, and one connection open
consumed 1,004 ms. These diagnostic values do not establish a general latency budget.

Readset admission now explicitly executes `SELECT current_setting('transaction_isolation')`
once and requires READ COMMITTED. The Neon proof demonstrated that SHOW alone still
permits isolation change, while this SELECT causes a later SET TRANSACTION isolation
change to fail with SQLSTATE 25001. Every hit rechecks active transaction/synchronization,
exact JDBC holder/connection, JPA holder/connection and rollback state, and autoCommit.
The proof belongs only to that synchronization's lifetime; REQUIRES_NEW admits its own
transaction. Failed queries or other isolation levels retain nothing. The existing full
`ConfigJdbcTransactionGuard.require()` remains the mutation/metadata-store boundary.
No authority decision, source admission, host resolution or deadline is memoized.

Skills canônicas: incrementos persistence/governed integrity, incluindo PRs 823/824/825, integrados pelo coordenador em `7988937c2b2fbb02ae569257da0e987fa783203a`, com sincronização seletiva concluída. Domain-decisions permanece adequada. Não há guidance pendente conhecido para o desenho implementado.

Creation and reconciliation also check the same validation attempt after the actual
transaction returns. A JDBC COMMIT response can arrive after the deadline even though
the transaction committed. Such a response is SOURCE_UNAVAILABLE, not a success receipt
and not evidence of rollback. The caller retains its original creationKey and reconciles
through lookup or an authorized identical replay; it must not allocate a new key merely
because delivery was uncertain. The integration fixture delays only the response after
a real delegated JDBC commit and verifies the durable identity from a fresh connection.

When REQUIRED joins an existing Config transaction, a returned outcome is provisional
until the outer owner commits. The operation attempt remains alive through beforeCommit
and afterCommit, and closes at afterCompletion. An owned transaction also checks after
execute returns, before closing its attempt. The afterCommit check can reject a delayed
COMMIT response but cannot undo committed SQL. Lifetime is a per-call local state, never
an authority registry or a different propagation mode.

Spring 6.2 savepoint rollback keeps the synchronization list. The readset therefore
clears all retained bytes in savepointRollback without renewing its connection/isolation
proof. A DSTM unit fixture with mocked JDBC exercises actual NESTED/savepoint propagation
and a fresh loader after rollback. This defensive invalidation does not add nested JPA
or savepoint support to the canonical page/lifecycle contract.
