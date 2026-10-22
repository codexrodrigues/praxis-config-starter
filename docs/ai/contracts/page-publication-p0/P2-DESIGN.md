# P2 — inventário e proposta para revisão

Estado: proposta antes de alterar SPIs públicos. Base Config `6144b485`; issue #510.
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

## Fluxo proposto

1. Resolver contexto autenticado atual independente de root: ator/tenant/env/unidade/contextVersion + app/namespace registrados. Reutilizar as cinco dimensões existentes; acrescentar somente app/namespace na fronteira páginas, sem reescrever lifecycle global.
2. Autorizar CREATE, READ_SOURCE e COPY_SOURCE explicitamente; reading não implica copy. Serializar por chave exata via lock PostgreSQL transacional. Revalidar contexto após lock. Replay igual retorna catálogo e ETag atual do draft; divergente409. Não recapturar nem transformar replay. Lookup não exige integridade completa/publicação, somente autoridade atual de ler associação e draft.
3. Resolver registered-native no SPI host de fonte exata; published-page no frozen resolver Config + catálogo mesmo app/tenant/env + evento de publicação da release/revisão exata. Capturar autoria original e evidência original dos filhos. Validar/reproduzir todos os filhos sob autoridade atual e limites existentes.
4. Transformar deterministicamente com mapa tipado server-generated, produzir output + sourceHash/outputHash/transformRef. Registrar evidência imutável correlacionada com criação/draft; não gravar título/status/head duplicados no catálogo.
5. Criar root registrado e draft pelo lifecycle existente na mesma transação Config. Não herdar assignments/reviews/approvals/head/menu. Todos writes e capturas participam da mesma conexão/transação. beforeCommit repete contexto, grants e admissão da fonte original/capturas; qualquer falha reverte tudo. ContextVersion histórico nunca concede autoridade.
6. A integração de leitura/edição/freeze/publicação usa o registro durável de página para resolver root e a admissão vigente; não existe cache mutável paralelo de registro nem thread-local que desapareça antes de callbacks do commit.

## Opções de reprodução para decisão

A. **Proposta**: output imutável é documento nativo produzido pelo Config, apontado por NativeDocumentSource de criação (producer=transformRef, revision=outputHash). NativeIdentity reproduz esse output, enquanto a admissão canônica Config comprova source original + transformação + mapa + captura dos filhos. Filhos copiados são snapshots nativos do mesmo produtor, nunca falsamente apresentados como B0 de uma fonte host antiga. Source JSON/capturas ficam evidência imutável da criação, não corpo autoral mutável. É obrigatório verificar o registro completo, hash e reprodução a cada admissão, e revalidar grants/dependências da origem atual. Nenhum simples prefixo de sourceId ou existence check concede admissão.

B. Novo Reproduction PageCopy no codec metadata e nova origem/colunas/constraints. Expressa transformação diretamente no codec, mas muda toda superfície de evidência (JDBC mapper, V68 constraints, mixins filhos, owner admission) e requer correlação recursiva de fontes publicadas/filhos. Mais explícito, maior impacto. Só adotar se A não puder preservar invariantes existentes sem fingir fonte.

Rejeitados: source original + NativeIdentity após alterar conteúdo; copiar última configuração effective; novo endpoint publish; root só em memória; default permissivo para fonte/metadata; host atestar qualquer JSON recebido; duplicar head/título no catálogo; revalidar somente na entrada.

## Impacto, provas e recursos

Config é dono. Consumidores futuros Core/Page Builder/Quickstart P3/P4; nenhuma UI/host ativada neste corte. Docs P2 e fontes P0; Landing/corpus operacional pertencem P6, sem anunciar endpoints antes da prova. Skill persistence atualizar-existente após contrato aprovado e prova; domain-decisions existente-adequada. Escrita skills exclusiva coordenador.

Unitários: copy/paridade, schema fechado, ausência/null, autorização/escopo/context, replay/divergência, reabertura, statusHTTP. Neon branch `br-aged-bonus-a4rhfqwp` projeto `ancient-leaf-72055027`, schema exclusivo `p2_page_510_20261022`, atribuição coordenador 22/10/2026. Provas JPA/JDBC runtime restrito, falha de cada etapa/commit, chamadas concorrentes e leitura independente. Nenhuma conexão realizada ainda. Target da worktree e Maven `/tmp/praxis-page-p2-510-m2` exclusivos.

## Decisão de coordenação e assinaturas para revisão

Em 22/10/2026 a coordenação aprovou a direção A condicionada à cadeia original integral,
reprodução obrigatória e revalidação atual. Não aprova prefixo, exists ou hash isolados.
Cadeia transitiva limitada a 16 criações, cycle detection por pageId e limite agregado de bytes
conservador; atingido limite nega sem truncar. A publicação histórica exata é demonstrada por
release/member/revision e evento PUBLISH/ROLLBACK persistido, nunca pelo head corrente.

API mínima proposta (sem implementação pública ainda):

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
