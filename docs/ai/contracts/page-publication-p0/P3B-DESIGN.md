# P3-B — leitura publicada, catálogo e navegação

Estado: desenho aprovado em direção pela coordenação, registrado para revisão independente antes de código. Este commit é documental; não representa implementação, execução de testes ou conexão SQL. Issue [512](https://github.com/codexrodrigues/praxis-config-starter/issues/512), dependência P3-A integrada em `724f92ff051dcf8eb3ad8e4d6924fa441c2d3eb4`. As decisões abaixo substituem as alternativas de cursor, parent e visibilidade discutidas no inventário; não permanecem como opções concorrentes.

Classificação do incremento: `arquitetural`, `contrato-publico` e impacto `transversal`. A mudança pública beta será limpa, sem overload de compatibilidade, segundo provider de navegação, flag, ponteiro de publicação ou fallback de documento. O commit deste desenho, isoladamente, é `docs-apenas`.

## Fontes e inventário de aderência

Referências de código nesta seção correspondem à base Config `724f92ff`; caminhos Java são relativos a `src/main/java/org/praxisplatform/config/`.

| Aderência | Fonte existente | Decisão e lacuna delimitada |
|---|---|---|
| `ja-suportado-so-ux` | `service/PageCatalogStore.java:64`, `service/PageCreationContext.java:11`, `service/PageCreationHost.java:14` | Identidade, application, namespace e contexto autenticado já existem. Não adicionar campos mutáveis de título, status ou publicação ao catálogo. |
| `ja-suportado-mal-nomeado-ou-mal-materializado` | `service/UiLayoutLifecycleReadService.java:139`, `dto/UiLayoutReleaseHeadReceipt.java`, `service/UiLayoutPageGroupHeadStore.java:35` | Head e receipt já identificam a publicação. Materializar a mesma seleção na URL/menu e na revalidação condicional, sem criar outro status. |
| `suportado-parcialmente` | `service/UiLayoutFrozenWorkspaceResolver.java:32`, `service/UiLayoutPageGroupHeadIntegrity.java:174,199` | Há correlação histórica, reprodução nativa e admissão atual. Reutilizar helpers internos, separando a dependência autoral de grupos da finalidade runtime. |
| `suportado-parcialmente` | `service/UiLayoutAudienceSelector.java:18`, `service/EffectiveUiAudienceProvider.java`, `service/PageCatalogLifecycleBridge.java:108` | Contexto, audiência e grants existem. READ_HEAD sozinho não substitui selector nem admissão atual de produtor/metadata/estrutura; captura histórica não é grant. |
| `lacuna-real-de-contrato` | `controller/PageCreationController.java:55,75`; P0 README tabela de rotas e D4/D6 | Não há GET de descoberta paginada nem resposta operacional do documento publicado. Config será dono desses DTOs e endpoints. |
| `lacuna-real-de-contrato` | `dto/EnterpriseRuntimeNavigationNode.java:5`, `dto/EnterpriseRuntimeNavigationResponse.java:6`, `service/EnterpriseRuntimeNavigationProvider.java:8`, `controller/EnterpriseRuntimeContextController.java:151` | Faltam icon, contexto esperado/confirmado, receipt e paginação na navegação existente. Evoluir essa superfície; não duplicá-la em `/config/pages/navigation`. |
| `ja-suportado-mal-nomeado-ou-mal-materializado` | Angular canônico `f6769d3cab8ee4f8038a7871f9df81592eefc2ab`, `enterprise-runtime-context.service.ts` (`contextRevision`, `invalidate`, `getRuntimeSurface`) | Geração monotônica local e invalidação existem. Navigation precisa usar o mesmo guard após await; não criar outro token de contexto. |

`UiLayoutCompositionReadService.java:43–75` e `JpaUiLayoutCompositionReleaseReader.java:52–93` não fornecem a semântica operacional requerida: admitem baseline, ausência de release e composição de candidatos. Seu DTO/receipt genérico não testemunha o head ETag. O caminho publicado usa catálogo → head fresco → release fixada → workspace congelado validado, sem `ui_user_config`, baseline alternativo, draft editável ou overlay pessoal.

P0 é a fonte de identidade, documento nativo, bindings, ordem, namespace e separação de agregados: [README](README.md), [TECHNICAL-PLAN](TECHNICAL-PLAN.md), [scenarios](scenarios.json). O corpus preserva sua proveniência; testes owner deste pacote não tornam os cenários HTTP/P4 executados.

## Superfícies e assinaturas mínimas

Todos os GETs abaixo exigem Principal autenticado e `X-Praxis-Context-Version`. App/tenant/environment/namespace/ator são atestados no servidor; hints de módulo, locale e timezone não os definem. `If-None-Match` é avaliado somente depois da resolução e das verificações finais de autoridade. Sucesso usa `Cache-Control: private, no-cache`; erros usam `no-store` e payload sanitizado.

| Endpoint | Semântica |
|---|---|
| `GET /api/praxis/config/pages?cursor=…&limit=…` | Catálogo operacional de páginas publicadas e autorizadas, ordenado por pageId UUID. `limit` padrão 50, intervalo 1–100. Sem corpo de workspace na listagem. |
| `GET /api/praxis/config/pages/{pageId}/published` | Documento nativo completo da publicação atual dessa identidade, com receipt do head e contexto. Ausente, withdrawn ou não autorizado não usa outro documento. |
| `GET /api/praxis/runtime/navigation?parentRef=…&cursor=…&pageSize=…` | Única superfície de navegação. Sem parentRef: grupos raiz; com parentRef: bindings das páginas autorizadas para o UUID canônico desse grupo. `pageSize` padrão 50, intervalo 1–100. |

POST de criação, by-creation-key e lifecycle permanecem nos donos existentes. A listagem deste incremento é operacional: descoberta de drafts para authoring não é introduzida como fallback. As rotas retornadas derivam do namespace registrado e pageId; não aceitam URL, slug ou código de router do cliente.

DTOs novos no pacote `dto`, sem nova hierarquia de contexto:

- `PageCatalogEntry`: `pageId`, `rootTarget`, `route`, `title`, `icon`, `head` (`UiLayoutReleaseHeadReceipt`). Campos de apresentação derivam da revisão publicada autorizada.
- `PageCatalogResponse`: `schemaVersion`, `items`, `contextVersion`, `receiptEtag`, `nextCursor` nullable, `resolvedAt`.
- `PublishedPageResponse`: `schemaVersion`, `pageId`, `route`, `document`, `contextVersion`, `receiptEtag`, `head` (`UiLayoutReleaseHeadReceipt`), `resolvedAt`. `document` é a árvore nativa de página validada; não inclui ledger, capturas, entitlements ou wrapper de workspace.
- `EnterpriseRuntimeNavigationNode`: manter campos existentes e acrescentar `icon`, ID do catálogo admitido; sem SVG/URL de ícone. Não criar DTO paralelo de node.
- `EnterpriseRuntimeNavigationResponse`: manter `schemaVersion`, `nodes`, `capabilities`, `resolvedAt`; acrescentar `contextVersion`, `receiptEtag`, `headReceipts`, `nextCursor` nullable. `headReceipts` contém somente o root de grupos autorizado e as páginas dos nodes retornados, sem vetor completo ou IDs de páginas privadas. Não incluir timestamp de resolução no hash estável da representação.

Assinatura beta do provider existente:

```java
EnterpriseRuntimeNavigationResponse getNavigation(
    EnterpriseRuntimeContextRequest request,
    String expectedContextVersion,
    String parentRef,
    String cursor,
    int pageSize);
```

Owner concreto Config proposto, sem SPI público concorrente:

```java
PageCatalogResponse catalog(Principal principal, String expectedContextVersion,
    String cursor, int limit);
PublishedPageResponse published(Principal principal, String expectedContextVersion,
    UUID pageId);
EnterpriseRuntimeNavigationResponse navigation(EnterpriseRuntimeContextRequest request,
    String expectedContextVersion, String parentRef, String cursor, int pageSize);
```

Esses métodos pertencem a `PageRuntimeReadService`; construtor/wiring usa os colaboradores existentes. Reutilização de reprodução/admissão será package-private, com finalidade explícita, sem novo permit, audience matcher ou callback em PageCreationHost. Não adicionar defaults permissivos.

O host que deseja páginas Config delega explicitamente seu `EnterpriseRuntimeNavigationProvider` ao serviço concreto e pode reduzir a projeção ou compor outros menus. Ele não concede acesso ao conteúdo da página por incluir um node. A presença de PageCreationHost não instala autoappend, não substitui custom provider e não abre a superfície por si só. Provider ausente permanece `503 RUNTIME_PROVIDER_UNAVAILABLE`, conforme implementação atual; corrigir a promessa documental de árvore vazia no README. Serviço de páginas exige grafo completo de dependências; host parcial não deve ativar controller utilizável apenas nominalmente.

## Autoridade e visibilidade

Resolver PageCreationHost e EffectiveUiAudienceProvider para o mesmo Principal e contexto esperado. Exigir correlação de `actorRef == audience.user`, tenant, environment e contextVersion; ambos representam a mesma identidade autenticada. Não equiparar administrativeUnit e organization: suas semânticas podem ser distintas. Audience é interna e nunca é serializada/logada.

Cada página exige READ_HEAD próprio, selector da assignment publicada compatível por `UiLayoutAudienceSelector.matches`, identidade do catálogo no app/tenant/environment/namespace e admissão atual de produtor, metadata, filhos e estrutura. Proveniência ancestral continua íntegra sem instituir READ_SOURCE/COPY_SOURCE perpétuo de templates. Reutilizar o binder root→KIND e reprodução B0/patch fixado, sem duplicar schema nem recapturar origem histórica.

URL direta não depende do grant do root app-shell. Navegação exige a interseção de visibilidade/admissão do root de grupos e da página. O contrato atual atesta visibilidade do root de grupos, não ACL nova por groupId. Group-root negado e página permitida: URL válida e menu ausente. Grupo permitido e página negada: nenhum binding dessa página é retornado. Grupo vazio válido pode ser node; não publicar `hasChildren` baseado em filhos privados.

No fim da operação, após as extensões de produtor/metadata/estrutura, repetir contexto/audiência, grants pertinentes e mapping quando usado. Antes de retornar 304, essas verificações também são obrigatórias. Mudança de audiência/visibilidade durante a materialização não pode preservar um receipt previamente aceito. Mudança de contexto aborta a operação inteira; indisponibilidade de fonte ou integridade não vira lista vazia.

O host deve atestar contextVersion corrente e monotônico para mudanças de contexto, incluindo A→B→A. O guard de geração Angular rejeita callback da primeira A mesmo que uma identidade de negócio volte a coincidir. Rechecks server não constituem fence atômico com autoridade externa ou outro banco; não há promessa de atomicidade entre sistemas. O digest da visão autorizada detecta mudança entre requests mesmo quando um grant mudou sem alteração de contextVersion; não substitui rechecks dentro da request.

## Coerência em READ COMMITTED

1. Iniciar um attempt com deadline não renovável e resolver autoridade atual. Usar a transação/conexão Config canônica; manter participação REQUIRED e sua semântica de completion, sem REQUIRES_NEW ou troca silenciosa para REPEATABLE_READ.
2. Coletar via uma consulta JDBC fresca o vetor completo do escopo consultado: catálogo app/tenant/environment/namespace, identidades e heads das páginas, mais root de grupos quando navegação o utiliza. Registrar presença/ausência, activeRelease, headETag e rowVersion. O vetor captura também inserções/remoções; não é uma lista apenas dos heads visíveis ao usuário.
3. Carregar registros imutáveis por releaseIds PINADOS da primeira coleta. Não reutilizar `activePages` para escolher outra release corrente durante a materialização. Reusar batching, frozen resolver, correlações e validação nativa; contexto e decisões de autoridade não entram no readset.
4. Filtrar/admitir e projetar a visão autorizada. Fazer rechecks finais e recolher o vetor por JDBC fresco. Divergência retorna conflito explícito; não há retry interno, ampliação de escopo ou renovação do budget. ETag/rowVersion impedem aceitar A→B→A da publicação apenas porque releaseId voltou a coincidir.
5. Somente o resultado coerente e atualmente autorizado pode gerar 200/304. Deadline cooperativo inclui callbacks e conclusão da operação, sem alegar cancelamento de I/O bloqueado. Transação externa conserva as regras canônicas de lifetime; não confundir retorno participante com conclusão da transação.

Vetor completo e seu hash permanecem internos: não serializar nem usar diretamente no receipt/cursor público. O receipt público deriva da representação autorizada e dos respectivos headETags, contexto e seleção da request. Timestamps de leitura não provocam invalidação artificial. A página URL consulta seu próprio head; menu/catalog usam o conjunto necessário à sua projeção, sempre sem segundo ponteiro de publicação.

## Ordem, paginação e cursor

Grupos raiz usam `(order, groupId)`. Bindings de um grupo usam `(order, bindingId, pageId)`; bindingId é único dentro da página, portanto `node.id` da entrada deriva de pageId + bindingId com codificação inequívoca, e não de bindingId isolado. O parentRef é o groupId UUID canônico. Grupo parent oculto e desconhecido produzem a mesma resposta segura. `children` de node carregável sob demanda é null; ausência de filhos carregados não revela quantidade ou existência de filhos privados.

Sem índice derivado novo, ordem global requer examinar as publicações relevantes, em lotes, antes do recorte. Reter somente top K (`pageSize + 1`) necessário à resposta após a posição do cursor; catálogo ordena pageId. Não usar OFFSET, ordenar somente um lote UUID e alegar ordem global, aceitar scan parcial ou inferir fim por um lote de itens privados. Metadados do vetor têm custo O(N); documentos/entidades são processados em lotes e descartados com preservação do estado managed prévio do chamador, sem clear global.

Manter o padrão interno de até 128 registros/8 MiB de texto de workspace por prefixo, carregando as releases pinadas. O primeiro registro oversized ou inválido não some: é processado isoladamente e verificado. 8 MiB não limita heap total, patches ou catálogo; patches, capturas e cópias têm custos separados. Medir identidades, SQL, tempo, texto de workspace/patch e retenção managed. Não prometer O(pageSize), SLA ou ganho universal. Índice futuro exigiria lacuna medida e desenho de manutenção transacional/completude; nenhum índice/DDL entra neste desenho.

Cursor opaco é uma seleção não confiável de posição, **não uma credencial**. Sem MAC, segredo novo, estado server-side de cursor ou registry de grants. Formato estrito, com versão de formato, campos fechados, tipos e comprimento limitados (máximo proposto de 4 KiB codificados); rejeitar tokens malformados antes de executar queries. Não transportar listas de IDs privados, audience, grupos de entitlement ou vetor interno.

O cursor vincula superfície/query, ator e scope atestados, contexto, parent, tamanho solicitado, digest da projeção autorizada completa e último item AUTORIZADO retornado com sua chave de ordenação. Bindings sensíveis permanecem em digest opaco, não como fatos de audiência no payload. Na continuação, recomputar a visão autorizada completa, comparar seu digest e validar que a posição corresponde a um item permitido na ordenação corrente. Alterar posição pode somente saltar para outra posição autorizada: não concede acesso, muda scope ou transforma dado privado em público. Não se promete autenticação criptográfica do token.

Mudança de contexto, visibilidade ou publicação pertinente invalida continuação com 409; nunca reiniciar silenciosamente nem misturar páginas de snapshots diferentes. O digest público usa somente projeção autorizada, incluindo heads das publicações que a compõem; mudança exclusivamente privada não introduz IDs nem hash de vetor privado no token. Uma mudança detectada no vetor interno durante a request ainda pode produzir conflito sanitizado.

## Estados HTTP e recuperação

| Situação | Resultado |
|---|---|
| Representação válida/atual | 200; receiptEtag forte e privado. |
| If-None-Match coincide depois de toda autorização atual | 304 com a mesma política private/no-cache. |
| Cursor malformado, parâmetros fora do perfil ou posição inválida no snapshot confirmado | 400, `INVALID_PUBLICATION_CURSOR`/erro de parâmetro sanitizado. |
| Sessão ausente | 401, código de sessão existente. |
| Contexto atual difere do esperado | 409, código de contexto existente da superfície; não reinterpretar contexto do cliente como grant. |
| Continuação perdeu vínculo com contexto/query/visão autorizada | 409, `PUBLICATION_CONTINUATION_STALE`. |
| Vetores de heads diferem durante a request | 409, `PUBLICATION_SNAPSHOT_CHANGED`. |
| Página ausente, withdrawn, fora do escopo ou negada; parent oculto/desconhecido | 404 uniforme, sem IDs/causa privada. Negação de item durante discovery apenas o exclui, mantendo rechecks e digest. |
| Host/dependências indisponíveis, integridade inválida ou orçamento excedido | 503 sanitizado na leitura operacional; sem resultado parcial, baseline ou falso vazio. Reusar o código de indisponibilidade da superfície. Provider de navegação ausente mantém `RUNTIME_PROVIDER_UNAVAILABLE`. |

Os três códigos novos de cursor/coerência ampliam a família de erros existente e seus handlers/allowlists; não justificam hierarquia de exceções paralela. Não alterar semântica de precondições 412 do lifecycle de escrita. Erros têm no-store, inclusive na navegação.

Recuperação é pull por revalidação: head/ETag persistidos sobrevivem ao processo; a próxima consulta condicional atualiza o receipt após publish/rollback/withdraw mesmo que a resposta ou notificação tenha sido perdida. Não manter cache autorizador de heads/grants, nem usar UUID/createdAt do evento como ordem de commit. Não se entrega push invalidation, outbox ou checkpoint de consumidor. Clique em menu antigo resolve novamente a URL e trata indisponibilidade; não reutiliza documento cached sem confirmação atual.

## Impacto, plano e gates

Escritor Config único: agente P2/P3 reaproveitado pela coordenação. Revisores independentes: coordenação e peer P1-D; documentação/evidência/consumidor podem receber revisão UX separada. Não delegar recursivamente. Primários sujos ficam intocados.

1. Revisar este desenho e congelar contratos antes de código.
2. Implementar owner concreto, leitura fresca/pinada, helpers internos compartilhados e cursor/projeção, mantendo o comportamento P3-A. Evoluir DTO/provider/controller/autoconfig e erros existentes, sem migration.
3. Validar unitários e MockMvc focais; congelar checkpoint para revisão antes da campanha Neon. Preparar apenas recurso atribuído após preflight real de endpoint/PG/TLS/owner/runtime/control plane.
4. Provar Neon em lotes focais, diagnosticar falhas antes de reruns e registrar source SHA/tree, artefato, dependências, DDL, identidade runtime e logs/XML sanitizados. Revisar custo de scan antes de declarar capacidade.
5. Atualizar derivados mínimos e guidance canônico, compilar consumidor contra artefato isolado, obter revisão independente final. Autor não faz merge, ready, fechamento de issue, release ou deploy.

Mapa Config: `PageCatalogStore`/helper de leitura fresca, novo `PageRuntimeReadService`, extração interna de snapshot/native admission de `UiLayoutPageGroupHeadIntegrity`, wiring em autoconfig/factory existente, `PageCreationController` ou controller de leitura no mesmo owner `/pages`, `EnterpriseRuntimeContextController`, Provider/default, Node/Response e DTOs de catálogo/publicação, handlers/allowlists, testes focais. Heads jamais entram no readset imutável. Constructor/factory beta muda uma vez se necessário; sem overload para mascarar wiring incompleto.

Angular canônico de referência `f6769d3cab8ee4f8038a7871f9df81592eefc2ab`: atualizar somente o wire model e reutilizar contextRevision no retorno assíncrono de navigation. Renderer/menu/rota e adoção operacional são P4. As cópias primárias de model/service estão sujas e não são fonte para essa edição. Atribuição de writer/worktree Angular cabe à coordenação.

Quickstart canônico `64080d77986f567f2d9bc6b27b7c6e218dbd7834`: não contém os construtores antigos de NavigationNode nem implementação atual de NavigationProvider encontrados em checkout antigo. Não criar patch artificial ou ativar navegação automaticamente; compilar consumidor focal contra o JAR Config isolado. Isso não prova HTTP operacional P3-C.

Docs derivados: P3B-IMPLEMENTATION e evidência final, status/links P0 preservando história, README runtime (503 sem provider), OpenAPI/contratos runtime. Não alterar scenarios/manifest para alegar testes não executados. Landing, exemplos Hero/Angular e corpus HTTP operacional serão confrontados quanto a referências, sem afirmar wiring P3-C/P4 neste pacote.

Skills: `atualizar-existente`, fonte inspecionada `praxis-config-runtime-persistence`/`references/governed-layout-integrity.md`, canônica integrada `18d4e02` (árvore revisada `930bb6a`). Novo caminho de leitura/coerência/cursor precisa guidance no mesmo ciclo. Coordenação é escritora da skill; publicação e sincronização são gates separados, ainda pendentes neste desenho.

Risco beta: assinatura Java do provider/record muda; consumidores e fixtures precisam migração canônica no mesmo ciclo. Testes/builds focais de Config, Core Angular e consumidor direto devem corresponder à fonte final, sem suíte integral por reflexo. Eventual E2E focal deve provar somente a superfície pública realmente alterada, sem simular adoção P4 inexistente.

Matriz mínima:

- Mesmo head autorizado em catálogo, URL e menu; conteúdo completo/B0/patch/KIND, sem personal overlay. Baseline disponível com head ausente/withdrawn continua indisponível.
- Page grant negado com grupos permitidos não expõe entry; group-root negado com page grant permitido mantém URL e oculta menu; parent oculto/desconhecido uniforme.
- READ_HEAD/selector, produtor, metadata e estrutura atuais; revogação no último callback impede corpo/304. Sem COPY ancestral perpétuo; selector mismatch e correlação ator/user/scope falham fechados.
- A→B→A server context monotônico e geração Angular; callback antigo não atualiza estado. Mudança de audience sem contextVersion invalida continuação e rechecks finais negam representação antiga.
- Barreiras determinísticas entre coletas: publicação, retirada, inserção e ABA de head; nenhuma mistura de releases/receipt. EntityManager stale não substitui JDBC fresco.
- Cursor obsoleto, adulterado, cross-actor/app/tenant/env/namespace/parent/pageSize; nenhum grant por token, IDs/contagens privados ou reinício silencioso. Ordem global e tie-break entre páginas com bindingId repetido.
- Ícones admitidos, grupos vazios, carga children sob demanda, paginação além de itens privados, fim somente após varredura suficiente; custo denso/bytes/managed/SQL e timeout sem resultado parcial.
- Perda de resposta após commit e nova instância do reader: GET condicional reconcilia head persistido; clique de menu stale nega após retirada. Sem claim de outbox/push.
- Autoconfig sem host, host parcial e custom provider; ausência fail-closed, delegação explícita, sem beans ambíguos/autoappend. MockMvc 200/304/400/404/409/503, headers e sanitização.
- Regressões focais de P3-A apenas onde extração interna modificar execução; provas anteriores válidas permanecem identificadas, sem somar contagens de árvores distintas como suíte única.

## Recursos reservados e estado de execução

Worktree `config-page-p3b-512`, branch `codex/page-runtime-p3b-512`, base `724f92ff`. Maven exclusivo `/tmp/praxis-page-p3b-512-m2`, target do novo worktree. Nenhum build iniciado neste checkpoint.

Neon reservado exclusivamente: projeto `ancient-leaf-72055027` (praxis-config), branch `br-weathered-mountain-a48o0map`, nome `page-runtime-p3b-512-20261023`, endpoint `ep-floral-voice-a40bufyv.us-east-1.aws.neon.tech`, PG17 segundo control plane, compute 0.25–1 CU/autosuspend300. Schema atribuído `p3b_runtime_512_20261023`, runtime `p3b_runtime_512_runtime`; parent homolog `br-empty-sea-a4kvwyqz` somente leitura. P2/P3-A não são reutilizáveis.

Nenhuma conexão SQL, criação de schema/role ou migração foi realizada para este desenho. Preflight operacional de TLS verify-full, versão e identidades reais permanece gate antes de mutar exclusivamente o schema atribuído. Credenciais serão privadas e jamais versionadas. O recurso reservado não representa autorização para modificar parent/public/herdado ou publicar qualquer ambiente.
