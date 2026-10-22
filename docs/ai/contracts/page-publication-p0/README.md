# Publicação de páginas — contrato P0

22/10/2026. **Especificação normativa para implementação; nenhuma API nova está ativa.**
Classificação: `contrato-publico`, com impacto arquitetural/transversal. P0 altera somente
especificação, corpus e testes de contrato. Não modifica produção, hosts, banco ou release.
O schema tem versão de documento 1; `p0` identifica o pacote de planejamento, não uma API paralela.

## Planejamento e entrega

- [Proposta de produto](PRODUCT-PROPOSAL.md).
- [Plano técnico P0–P6](TECHNICAL-PLAN.md).
- [Pacotes, dependências e coordenação entre máquinas](DELIVERY.md).

## Inventário e impacto

Fontes auditadas: Config `60ff10a4b95538f540fc0002e7df91c03bc518e1`, Angular
`cf27f5b89480a34a299fa6d3c591bfa9776a99b4`; Hero usa libs `10.0.0-rc.37`.
Referências abaixo são relativas à raiz de cada repositório nesse commit, não ao HEAD móvel.

| Necessidade | Aderência | Fonte e decisão |
|---|---|---|
| Compor página, editar Form/Table, escolher ícone | `ja-suportado-so-ux` | Angular `projects/praxis-core/src/lib/widgets/widget-page.model.ts`, editores dos owners e `shell/shell.models.ts`; reaproveitar apresentação e semântica. |
| Documento integral de página governada | `lacuna-real-de-contrato` | Config `UiLayoutAuthoringProjection` admite só Form/Table. Adicionar codec nativo de página, não novo grafo. |
| Inserir/remover filhos | `suportado-parcialmente` | `UiLayoutCompositionRegistration`/`UiLayoutDraftWorkspaceCodec` fixam targets. Uma página terá exatamente um target; filhos versionados dentro dele. |
| Publicação, revisão, ETag, reconciliação | `ja-suportado-mal-nomeado-ou-mal-materializado` | `UiLayoutLifecycleController`, serviços e receipts existentes; consumir a mesma máquina, sem outro status editável. |
| Identidade durável, criação por fonte, URL e menus | `lacuna-real-de-contrato` | Catálogo de identidade e vínculo próprio de navegação faltam. Config é dono; Core materializa; host registra namespace/política. |
| Admissão de filhos introduzidos após B0 | `lacuna-real-de-contrato` | Fonte atual captura targets iniciais; falta evidência por filho interno novo. Extensão da revisão e captura descrita abaixo. |
| Navegação com ícone | `suportado-parcialmente` | `PraxisNavItem.icon` existe; `EnterpriseRuntimeNavigationNode` precisa projetá-lo. |
| Cópia com identidade e proveniência | `lacuna-real-de-contrato` | Duplicação visual de widget não remapeia página/storage nem estabelece fonte governada. |

Para cada lacuna, a UX bloqueada é respectivamente salvar composição completa, criar/encontrar
página, inserir filho com origem verificável e copiar sem compartilhar identidade. Os dados novos
são os envelopes abaixo, referência de origem, captura correlacionada e identidade de criação.
Consumidores: Core/Page Builder, owners Form/Table, Quickstart, Hero e exemplo Angular.
Derivados futuros: Landing, manifests de admissão, receitas e corpus HTTP. Metadata Starter
continua dono de recursos/operações; não muda schema de negócio neste pacote.

Risco de quebra: imports legados, componentes fora do perfil e editores que exportam defaults
runtime serão rejeitados até migração/adaptação. Nada os remove silenciosamente. Não alterar
consumidores antigos nem criar flags preventivas; migrar cada consumidor no pacote próprio.

## Artefatos e alcance da prova

- [contracts.schema.json](contracts.schema.json): JSON Schema 2020-12 com definições nomeadas.
- [corpus.json](corpus.json): exemplos estruturais válidos/inválidos e relações nativas.
- [scenarios.json](scenarios.json): casos relacionais, cópia e gates transacionais futuros.
- [VALIDATION.md](VALIDATION.md): execução e revisão independente deste corte.
- Teste focal: `src/test/java/org/praxisplatform/config/service/PagePublicationP0ContractTest.java`.

A raiz valida pageDocument; outros contratos selecionam seu `$defs` explicitamente. Este é um **perfil estrutural fechado** da autoria,
não um substituto para todos os tipos TypeScript nem para schemas de FormConfig/TableConfig.
Esses owners não possuem JSON Schema público completo no baseline auditado. O schema deixa
seus objetos de configuração ao codec nativo; isso NÃO autoriza inputs, operações ou conteúdo.
Uma validação estrutural positiva não prova proveniência, autorização, renderização ou publicação.
Todos os exemplos são sintéticos; IDs, fontes, hashes e receipts não são atestados reais.

## Identidade e documento

O root é `UiLayoutTarget(componentType="praxis-dynamic-page", componentId=<UUID do servidor>)`.
`componentId == pageId`; registro externo contém somente esse root. Chave lógica do catálogo:
aplicação + tenant + ambiente + pageId, confirmados no contexto do servidor. Catálogo contém
identidade, namespace registrado e vínculo à criação; não duplica corpo, título autoral ou head.

`pageDocument` é `{kind:"praxis.dynamic-page.editor", version:1, config:{definition,
presentation,navigation}}`. `definition` materializa `WidgetPageDefinition`; `presentation`
contém título, ícone e descrição opcional; `navigation` contém somente vínculos desta página.
Cada vínculo tem ID estável, grupo de destino, rótulo, ícone e ordem não negativa. Vários
vínculos para grupos distintos são permitidos; IDs são únicos na página. Uma página pode ser
publicada com zero vínculos e continuar acessível por URL a quem tiver permissão.

O primeiro perfil admite widgets Form/Table, `layout` e `composition.links` com endpoints
component-port, output → input, intent event-propagation. IDs de widget/link são UUIDs e as
referências devem existir. O registry do owner admite portas e compatibilidade; texto de porta
não cria uma capacidade. `composition.version`, quando presente, é "1.0.0" neste perfil.
Layout usa a interface Core existente, com 1–24 colunas e comprimentos não negativos em px/rem
(ou 0). São restrições de admissão, não uma alteração global das interfaces Core.

Neste corte não são admitidos `dataSources`, `wiring`, state/context, canvas, grouping,
deviceLayouts, presets, i18n, shells, nestedPath, transforms, condições ou global-actions.
Isso é um limite explícito da primeira fatia certificável. Página que precise deles recebe
diagnóstico de perfil incompatível, preservando fonte/trabalho; não se converte por descarte.
Ampliar o perfil requer contrato/corpus e prova no owner antes de expor a capacidade.

## Uma fonte por filho e evolução necessária

Persistir conteúdo somente em `definition.inputs.config`. Os demais inputs autorais admitidos
reconstroem bindings/contexto do documento nativo. **Não existe input runtime
`authoringDocument` em Form/Table no baseline**; não o introduzir como se já funcionasse.

| Filho | Reconstrução nativa sem defaults |
|---|---|
| Form | kind `praxis.dynamic-form.editor`, version 1, config = inputs.config; bindings a partir de mode/emptyState/presentationModeGlobal; contextSnapshot de backConfig/presentation/schemaPrefs (notifyIfOutdated, snoozeMs, autoOpenSettingsOnOutdated). |
| Table | kind `praxis.table.editor`, version 1, config = inputs.config; bindings de resourcePath/horizontalScroll. |

Ausência, null e valor são distintos. O adapter só emite bindings/contextSnapshot/schemaPrefs
quando algum membro correspondente está presente. Preserva null onde o owner o admite; não
converte ausência em defaults. `layoutScope` Form não é representável nesse perfil: uma fonte
que o contenha explicitamente deve ser rejeitada até suporte, nunca perdê-lo na conversão.
Inputs de identidade/persistência e dados runtime não entram. formId/tableId/componentInstanceId
são derivados do pageId + widgetKey pelo adapter de execução, em namespace estável e isolado;
não são strings autorais a copiar. `childWidgetKey` explícito também está fora deste perfil.

Os editores atuais exportam defaults, IDs e opções adicionais. Portanto a montagem acima é
**trabalho P1/P4**, com round-trip de presença e valores; não delegar diretamente a serialização
ao editor atual. O adapter deve classificar campos extra: runtime derivado é removido de forma
declarada; autoria fora do perfil bloqueia com diagnóstico. Não usar um filtro cego de propriedades.

Para uma mesma identidade de filho, todo o envelope nativo fora de config permanece fixo.
Alterar mode, recurso, bindings ou contexto exige novo widgetKey e uma nova seed admitida.
A UX oferece substituição explícita e explica o impacto nas conexões. Troca de componente
também exige nova identidade; não reciclar key removida para outro conteúdo.

`pageRevisionCommand` mantém commandRef/target/authoringDocument/reason do endpoint existente,
acrescentando `childSources` somente para target de página. Não é um segundo endpoint de salvar.
Cada entrada declara widgetKey e fonte registrada/versionada. É **pedido de resolução**, não
prova fornecida pelo cliente. Para keys novas, a lista é obrigatória e exata; duplicatas, key
ausente no candidato, fonte não admitida ou declaração para filho já conhecido são negadas.
Filhos restaurados podem reutilizar a captura histórica do MESMO draft/identidade, se ainda
admitida; não precisam falsificar uma nova origem. O servidor conserva capturas mesmo após remoção.

Config estende a captura de `UiLayoutDraftWorkspaceSource`/metadata para os filhos internos:
resolve a fonte registrada sob autorização atual, captura entrada e assembly admitidos,
reproduz B0 do filho e valida candidato/patch pelo owner. SourceId não é URL executável.
`UiLayoutBaselineMetadataSeed` continua dono da evidência OperationSource/NativeDocumentSource,
reproduction e Observation. A nova correlação é page target + widgetKey + revisão/captura; não
inventar operação a partir de label nem atestar payload recebido. A evidência fica em registros
de revisão/workspace, não em outro mapa autoritativo de configs dentro da página.

Para filhos já conhecidos, a comparação continua contra sua B0 admitida, não contra o último
JSON efetivo. A revisão root usa o compilador de patch atual sobre config completo; arrays
substituem atomicamente. Isso não dispensa validação de cada filho: Table exige columns vazio,
columnProjection.source=schema e rejeita null; Form rejeita null em objetos mesclados, mas
permite null dentro de arrays atômicos. Reproduzir root e cada filho, sem normalizar autoria.
O root mantém B0 original e os mesmos targets externos ao longo do draft.

## Criação, cópia e evidência

`createCommand` admite creationKey, source e title. Não admite documento arbitrário, URL,
tenant/ator, status, aprovação, assignments, menus ou credenciais. Fontes:

1. `published-page`: pageId + releaseId + revisionId exatos; a revisão deve pertencer à release
   e ter sido publicada nesse escopo. Uma revisão histórica publicada pode ser escolhida, com
   autorização e dependências revalidadas hoje. Não consultar latest durante a transformação.
2. `registered-native`: sourceId + revisão imutável do catálogo admitido pelo host/Config.
   Abrange template vazio e página estática exportada nativamente; não tela Angular arbitrária.

A cópia usa autoria publicada, nunca effectiveLayout, rascunho, storage pessoal ou DOM. No
mesmo tenant/app/ambiente; permissão de ler não implica copiar/criar. O resolvedor identifica
fonte e dono no contexto autenticado, sem confiar em headers de identidade do cliente.

Transformação `praxis.page-copy/1`: criar novo pageId; mapear cada par (tipo, identidade)
de widgetKey/link.id a UUID novo, injetivo e sem interseção com origem. Um widget e um link
podem ter o mesmo UUID na fonte: são namespaces distintos. Remapear endpoints ref.widget
exclusivamente pelo mapa de widgets;
preservar config/defaults autorais e referências de domínio admitidas; substituir título pelo
título solicitado; remover vínculos de navegação; conservar ícone apenas como sugestão visual
da apresentação. IDs runtime serão derivados da nova página. Sem substituição global de strings:
um título ou valor de config igual a um UUID não é referência a remapear.

Resultado nasce sem head, aprovação e assignments; autorização do draft vem do ator atual.
Origem não se altera. `copyEvidence` é registro interno server-owned com fonte, sourceHash,
outputHash, transformRef e mapa de identidades, correlacionado à criação/draft. Hashes usam
`CanonicalJsonHashService.sha256Exact` da autoria preservada, não hash do JSON formatado para exibição.
O mapa é evidência auditável, não mais um grafo. Falha de qualquer filho aborta toda a criação.

## Operações wire e condições

As rotas novas abaixo são **contrato alvo**, não chamadas funcionais neste checkout.

| Operação | Resultado e condições |
|---|---|
| POST `/api/praxis/config/pages` (`createCommand`) | 201 `creationReceipt`, Location do workspace de draft existente, ETag = draftEtag e Cache-Control:no-store. Catálogo + registro root + baseline + capturas + draft + criação atomicamente persistidos. |
| GET `/api/praxis/config/pages/by-creation-key?creationKey=…` | 200 mesmo receipt de identidade atualizado com ETag atual do draft e no-store; 404 se não houver criação no escopo autorizado. Não afirma integridade/publicação; abrir workspace é próximo passo. |
| GET `/api/praxis/config/pages?cursor=…&limit=…` | Listagem autorizada de descoberta; alvo P2/P3 deve reutilizar convenções de paginação do dono. Contrato detalhado de leitura é P3, não liberado pelo P0 de escrita. |
| POST `/api/praxis/config/ui-layouts/drafts/{draftId}/revisions` | Evoluir body existente para `pageRevisionCommand`; root query/Context-Version/If-Match continuam os atuais; 201 receipt de revisão/nextDraft existentes. commandRef é correlação, **não chave idempotente**. |
| Demais `/ui-layouts` | Reutilizar assignments, freeze, submit, approve, publish, rollback, withdraw e suas respostas. Nenhuma `/pages/publish` paralela. |

X-Praxis-Context-Version confirma contexto atual; não concede autoridade. App/tenant/env/ator
vêm da resolução autenticada. CreationKey é idempotente por ator+app+tenant+env, vinculada ao
comando semanticamente normalizado: replay igual retorna a mesma criação (200, sem repetir a
transformação); corpo diferente retorna 409. ContextVersion transitório não muda identidade
do comando. Revalidar autorização antes de replay/consulta; troca A→B→A não libera callback antigo.
Conservar vínculo de idempotência junto ao catálogo; não expirar a chave enquanto puder duplicar
uma criação retida. Uma resposta perdida é reconciliada antes de nova tentativa.

403 para acesso negado, 404 para ausência em escopo autorizado, 409 para conflito de comando
ou dependência, 412 para condição forte inválida/ausente/stale, 422 para conteúdo/política
inadmitido, 503 para fonte/contexto indisponível. Não vazar existência em outros tenants.
Usar envelope de problema sanitizado existente; não persistir stack/segredos como diagnóstico.
P1/P2 detalham códigos do enum existente antes de ativar os endpoints, mantendo esta semântica.

Primeiro publish requer If-None-Match:*; head existente exige seu If-Match forte, inclusive após
withdraw. Nunca mandar ambos. Draft/review/head têm ETags diferentes e os comandos atuais
retornam seus próprios receipts. Recuperação de publish sem resposta consulta head/review;
não tratar commandRef de revisão como garantia de idempotência do publish.

Cada documento/candidato/patch: 256 KiB UTF-8 e profundidade máxima 64 segundo UiLayoutJsonBounds.
Body HTTP de revisão: 1 MiB, incluindo childSources; JSON duplicado/malformado é negado. Capturas
e evidências conservam seus limites próprios existentes, sem desmembrar publicação para burlar
o teto. A medição vale também para página inteira e arrays de filhos, não só cada config.

## Navegação e publicação corporativa

`groupsDocument` usa kind `praxis.navigation-groups.editor`, version 1, config.groups com
id/label/icon/order. Primeiro perfil tem grupos planos; ausência de um grupo equivale a removê-lo.
Target proposto: `praxis-app-shell` + UUID estável registrado para esse escopo pelo host.
O selector existe, mas seu codec/lifecycle de grupos **ainda não existe**. Um único root por
namespace de navegação é registrado; grupos não pertencem ao agregado de nenhuma página.

Página publica somente seus vínculos; grupos publicam antes, por lifecycle separado. Falha
posterior da página pode deixar grupo vazio publicado, sem simular atomicidade entre agregados.
Ícones são IDs do catálogo admitido projetados em PraxisNavItem/DTO, nunca SVG/URL arbitrário.
Ordem empatada desempata por ID estável; rótulos não são identidade. Ícone inválido bloqueia
nova revisão/publicação; descontinuação futura exige migração explícita, não substituição oculta.

Publish/rollback/withdraw de página e grupos usam locks compartilhados por escopo+groupId,
ordenados, abrangendo referências do head antigo e do candidato. Após lock: reler heads,
recalcular conjunto e abortar/repetir com novo conjunto se mudou. Transição de grupo que remove
grupo referenciado por qualquer página ativa é negada. Índice de referências deve ser atualizado
na mesma transação ou substituído por leitura autoritativa; índice eventual não decide integridade.
Rollback da página restaura só seus próprios vínculos/conteúdo. Dependência retirada bloqueia
rollback e preserva head; criar revisão nova para corrigir, sem adulterar história.

Catálogo → target → head é o caminho único da URL; navegação projeta a mesma release ativa.
Recibos de resolução/navegação precisam identificar contexto e heads consultados, com autorização
antes de 304/cache. O detalhamento desses DTOs e invalidação recuperável é gate P3. Não existe
fallback de página sem head para baseline/draft. Rotas são derivadas de namespace registrado:
Hero `/laboratorio/paginas/<uuid>`; exemplo `/pages/<uuid>`. Renomear título não muda URL.

Não permitir overlay pessoal nesta primeira superfície. Root e filhos consomem autoria congelada,
sem ler outro latest em localStorage/ui_user_config. Preview bloqueia mutações inclusive hooks e
ações automáticas; configuração não é sandbox de rede. Domínio continua validando seus dados e
operações independentemente da autorização para página/menu.

## Consumidores de identidade e migração

| Fonte auditada | Efeito futuro |
|---|---|
| Core `models/page/page-config.model.ts:7–17,72–78` | buildPageKey inclui routePath. Manter PageIdentity; fornecer URL derivada estável. |
| Core `widgets/dynamic-widget-page.component.ts:1294,2416–2424,9944–9976` | Identidade, observabilidade e storage; materializar componentInstanceId estável e impedir storage paralelo governado. |
| Page Builder `dynamic-page-builder.component.ts:1528,2700` e `ai/page-builder-ai.adapter.ts:32–50` | Preservar fingerprint/contexto da página editada; cópia recebe identidade nova. |
| Page settings `page-config-editor/page-config-editor.component.ts:1073,1117` | Recebe identidade, não cria catálogo. |
| Exemplo `dynamic-page-lab.page.ts:646–653`, `ergon-composition-case.page.ts:324–328`, labs persistence/connections/json-logic | Continuam usando buildPageKey/PageIdentity; migrar somente o fluxo de páginas publicadas. |
| PersistedPageConfig/createPersistedPage | Nenhum consumidor funcional fora do próprio model localizado nessa ref. Não remover API pública nem promover status/version a lifecycle novo. |
| Hero HEAD a3190dfa + alterações locais | Dashboard, comando/workspace studio e visitor-lab fornecem identidades. Personas simuladas não são contexto autenticado. |
| Landing HEAD a6e7d4e4, fontes src | Nenhum uso dos quatro símbolos localizado; exemplos gerados fora desse recorte não foram atestados. |

Laboratório local continua explicitamente sem login; manter UUIDs/URLs, 30 páginas, 20 menus e
2 MiB por namespace. Migração validada inteira sob lock, preservando bytes anteriores em
corrupção/quota; origem desconhecida continua desconhecida e ícone ausente recebe web. Não emitir
copyEvidence corporativa para dados locais. Legado `published-page:<uuid>` do exemplo exige
importação validada para draft, preservação de endereço e aprovação real; sem fallback invisível.

## Saída do P0 e trabalho restante

Aceite P0: schema/corpus reproduzíveis; relações nativas confirmadas contra guards atuais;
exemplos de remapeamento e invariantes relacionais; fontes e limites explícitos; revisão por
autor distinto. Testes não executam uma futura implementação disfarçada nem aprovam HTTP/banco.
Casos transacionais em scenarios.json são requisitos rastreados, não testes aprovados.

P1 implementa codec de página/grupos, reconstrução/admissão de filhos e round-trip. P2 implementa
catálogo/criação/captura e integra lifecycle; P3 resolve navegação/coerência. P4/P5 implementam
adapters/hosts/migração somente após esses gates. Backend com banco exige Neon remoto isolado,
com identidade/TLS/papéis e branch atribuídos. Nenhum teste P0 depende de banco.

Skills canônicas inspecionadas: praxis-product-evolution, praxis-config-runtime-persistence,
praxis-page-builder-composition e, no inventário independente, praxis-authoring-editors e
praxis-form-editor-document-roundtrip, no repo praxis-codex-skills. Classificação desta entrega:
`existente-adequada` para inventário/ownership; API planejada ainda não muda o caminho operacional
ensinado. Evolução das skills de persistência/composição/round-trip é `atualizar-existente` junto
da implementação, antes da adoção P6. Nenhuma skill local foi editada ou anunciada como publicada.
Não atualizar corpus HTTP executável, manifests ativos, Landing ou release notes como se esta
capacidade estivesse disponível. O índice de contratos aponta explicitamente para esta especificação.
