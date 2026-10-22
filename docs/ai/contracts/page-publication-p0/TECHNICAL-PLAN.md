# Plano canônico — páginas derivadas nos hosts Praxis

22/10/2026. Complementa e corrige a [proposta de produto](PRODUCT-PROPOSAL.md). **P0 formalizado e revisado localmente; implementação de runtime/hosts não iniciada.** As decisões abaixo são o destino da evolução, não descrição de APIs já disponíveis. O desenho inicial foi `docs-apenas`; o P0 é uma formalização de `contrato-publico` com schemas, corpus e testes focais. Implementar runtime exige mudanças arquiteturais e transversais.

## 1. Resultado e limites do incremento

Nos hosts habilitados, criar uma página independente a partir de uma revisão publicada ou template nativo aprovado, editar sua composição, salvar trabalho, publicar com ícone e entradas de menu, abrir por URL direta e reencontrar após recarga. Editar uma página mantém sua identidade; duplicar cria outra. A origem não muda.

Dois consumidores obrigatórios: Hero HQ e aplicação de exemplo Angular. Quickstart prova a integração corporativa autenticada. Hero mantém também o laboratório explicitamente local, sem login. Nenhuma persona simulada será tratada como autorização corporativa.

Fora deste incremento: copiar telas Angular arbitrárias, criar recursos de domínio/tabelas de banco, copiar rascunhos ou personalizações pessoais para uma página compartilhada, herança automática entre cópias, troca de URL/slug, promoção entre ambientes, variantes estruturais por audiência e edição autônoma de um filho que altere a publicação da página por fora dela. Essas exclusões delimitam o produto inicial, sem flags de compatibilidade ou uma segunda versão de governança.

## 2. Inventário verificável e cinco correções

Baselines de leitura: Config `60ff10a4b95538f540fc0002e7df91c03bc518e1`; Angular `cf27f5b89480a34a299fa6d3c591bfa9776a99b4`; pacote Core instalado no Hero `10.0.0-rc.37`. São refs locais e artefato instalado, não uma afirmação sobre o HEAD remoto atual. Ler arquivos históricos com `git show <commit>:<path>`. Nenhum reset/fetch foi necessário.

| Revisão | Evidência existente | Decisão e evolução necessária |
|---|---|---|
| R1 — documento de página | Config `service/UiLayoutAuthoringProjection.java:26–30` aceita somente Table/Form. | `lacuna-real-de-contrato`: admitir documento nativo de página, validar filhos e reproduzir sua materialização. Reusar o protocolo de governança, sem fingir que basta um provider. |
| R2 — estrutura | `UiLayoutCompositionRegistration.java:7–8`, `UiLayoutDraftWorkspaceCodec.java:28–43,66–70` e `UiLayoutLifecycleCommandService.java:258–280,468–470` exigem targets registrados correspondentes. | Página será um target estável. Widgets internos e conexões pertencem à revisão desse documento. Não alterar membership externo a cada edição. |
| R3 — rota/menu/rollback | O head existente seleciona uma release de layouts, sem comando de navegação. | `lacuna-real-de-contrato`: incluir vínculos próprios de navegação no documento publicado e projetar catálogo/rota/menu a partir do mesmo head. Grupos são agregados separados. |
| R4 — origem da cópia | Revisões e fontes nativas existem; não há operação canônica de cópia de página nesse caminho. | `lacuna-real-de-contrato`: copiar revisão autoral publicada selecionada, com origem imutável e transformação de identidades registrada; excluir resolução individual e estado runtime. |
| R5 — identidade/endereço | Core `models/page/page-config.model.ts:7–17,72–78` usa `routePath` em `buildPageKey`. | Conservar `PageIdentity` onde usado; identidade governada será `UiLayoutTarget` estável. URL derivada e imutável. Não renomear chaves ao mudar título. |

Já suportado e a reutilizar: Page Builder, `WidgetPageDefinition`, editores filhos, composição/diagnostics Core, seletor de ícones, `PraxisNavItem.icon`, ETags, drafts/revisões/assignments/releases, aprovação/auditoria e protocolo de recuperação do lifecycle. O campo `status` de `PersistedPageConfig` não é uma máquina de estados concorrente; não será promovido a fonte da publicação.

Suporte parcial: runtime de navegação contextual e resolvedor de layouts. O DTO backend `EnterpriseRuntimeNavigationNode` ainda não possui ícone; a evolução deve acrescentar essa projeção no dono, mantendo o catálogo visual aprovado. Providers de host continuam responsáveis pela admissão, não pelo desenho de outra semântica de páginas.

## 3. Decisões arquiteturais

### D1 — página como agregado versionado

O root usa o selector existente `praxis-dynamic-page` e um `componentId` opaco, estável e atribuído pelo servidor. Seu registro de lifecycle contém o root como único target. A identidade dos widgets é interna à página, estável entre revisões e renovada na cópia. Remover um widget não apaga sua configuração nas revisões históricas.

A evolução adiciona um formato nativo de autoria de página ao mecanismo de projeção/reprodução de Config. O corpo reutiliza `WidgetPageDefinition` como semântica de estrutura, layout, conexões e inputs; não cria um segundo grafo. O envelope acrescenta somente o necessário para autoria/publicação: propriedades apresentacionais da página, vínculos de menu próprios e referências verificáveis de proveniência/versões. Identidade do principal, tenant, ETag, estado de aprovação e permissões permanecem na fronteira de Config, fora dos inputs dos widgets.

**Uma única fonte por conteúdo:** a configuração autoral de cada filho ocupa um único lugar no documento da página. Envelopes nativos de editores filhos são reconstruídos/exportados pelos adapters oficiais, com contexto de autoria/proveniência separado quando necessário. Não armazenar dois JSONs autoritativos para o mesmo formulário. O teste de round-trip deve provar que abrir/salvar pelo editor nativo não perde informação. Se um adapter não conseguir representar esse conteúdo sem perda, o componente não é admitido na paleta governada até a evolução de seu dono.

Config aplica schema de página, composição e validação canônica de cada tipo admitido, incluindo a origem de metadata dos filhos. O novo codec não aceita um saco de `inputs` arbitrários nem enfraquece os validators de Table/Form. Cada novo componente precisa de formato, versão, fonte de baseline e reprodução admitidos. A primeira fatia certifica página/layout + formulário + tabela; outros componentes só entram quando passarem a mesma conformidade. A paleta corporativa reflete esse suporte real, mesmo que a paleta local já renderize mais componentes.

A alternativa de versionar dinamicamente o conjunto de targets corporativos foi rejeitada neste incremento: alteraria a semântica de registro, autorização histórica e resolução de composições existentes. Componentes corporativos administrados independentemente continuam como dependências externas explícitas, nunca convertidos silenciosamente em filhos internos.

### D2 — fontes, cópia e baseline

A ação padrão copia o **documento autoral da revisão publicada selecionada**, e não o DOM, o retorno de `effectiveLayout`, o rascunho ou as preferências do solicitante. O receipt identifica página/release de origem, hash do conteúdo e versão da transformação. A autorização atual para ler a fonte, copiá-la e criar no destino é verificada pelo backend. Proveniência histórica não concede permissão.

O primeiro incremento aceita cópia no mesmo tenant, aplicação e ambiente. Compartilhamento entre equipes ocorre depois, por assignments/autorização já existentes. Cópias entre tenants/ambientes ficam fora do escopo.

A cópia nasce como trabalho novo, sem head publicado, aprovação, assignments ou entradas de menu herdadas. Preserva conteúdo/layout admitidos e propõe um título novo; grupo/ícone da origem podem ser sugestões visuais, mas os vínculos do destino só são gravados por escolha explícita e validação. O acesso ao draft segue a política do ator criador, não a audiência da origem. Essas remoções fazem parte da transformação reproduzível, não são uma alteração silenciosa após a cópia.

O servidor cria nova identidade de página e remapeia identidades internas e referências do grafo com os codecs da plataforma: widgets, alvos de links, contextos de storage e componentes aninhados. Não substituir strings globalmente. Dependências de domínio permanecem referências admitidas ao recurso/operação, sem duplicar dados nem credenciais. Estado inicial declarado de interface pode ser preservado; dados carregados, seleções, respostas de formulário e demais valores runtime não entram.

Baseline da cópia = resultado reproduzível dessa transformação sobre a revisão fixada. Os inputs nativos/metadata de cada filho permanecem verificáveis, com captura correlacionada conforme `UiLayoutDraftWorkspaceSource`; mudar todos os IDs não autoriza inventar identidades de operações. Fonte sem reprodução ou com componente não admitido bloqueia a cópia com diagnóstico seguro, sem copiar parcialmente.

Uma página estática só pode servir de origem se o host registrar fonte nativa versionada, admitida e reproduzível. Página vazia nasce de template canônico mínimo versionado. O frontend não publica um snapshot arbitrário como se fosse uma origem aprovada.

### D3 — catálogo e endereço

Config mantém um catálogo de **identidade e descoberta**, sem uma segunda cópia do documento publicado. Chave lógica: aplicação + tenant + ambiente + ID estável; app e escopo são confirmados no contexto autenticado. O catálogo liga a identidade ao `UiLayoutTarget`, fonte de criação e namespace de host registrado. Título, ícone, status de uso e resumo de navegação são projeções das revisões/heads autorizados, não campos mutáveis concorrentes com a release.

O host monta uma rota parametrizada no namespace que já possui. A criação de página não altera o código do Angular Router, não gera bundle e não grava URL fornecida pelo usuário. No Hero local, preservar `/laboratorio/paginas/<uuid>`; no exemplo, preservar a convenção `/pages/<uuid>` quando migrada. Novos hosts registram seu namespace e reservas na integração oficial. Sem troca de slug neste incremento.

Renomear título/rótulo não altera endereço nem storage. `PageIdentity.routePath`, quando exigido pelo runtime existente, recebe o caminho estável derivado; `buildPageKey` não passa a governar o catálogo. `PersistedPageConfig` será auditado por consumidores durante a implementação: reutilizar semânticas existentes, sem manter seus campos `status/version/key` como outra fonte de estado. Não é preciso removê-lo de consumidores alheios a esta mudança.

### D4 — publicação atômica de uma página

Uma release de página inclui estrutura, conteúdo autoral de filhos, apresentação e **somente seus próprios vínculos de menu**. Usa o mesmo draft → revisão → assignments → freeze → review → publish do lifecycle. Não há endpoint paralelo que publique sem esses gates.

A transação de publish/rollback confirma políticas atuais, dependências, ETag e integridade, seleciona a release no head e registra auditoria. O endereço resolve catálogo → target → head; nunca mantém outro ponteiro independente para a revisão. A navegação projeta os vínculos da mesma release ativa. Assim, uma gravação separada de menu não é necessária para publicar a página.

O título de uma página de trabalho pode aparecer no catálogo do autor; o catálogo operacional usa somente o publicado. Primeira publicação não expõe o rascunho. Retirada de uso remove a descoberta operacional e bloqueia resolução direta como página publicada, preservando identidade/histórico para autores autorizados. Não há fallback para rascunho ou para uma baseline privada.

Cache/indexação são derivados. A autoridade é o head, não o índice. Após publicação, o host usa a confirmação/versão de resolução para invalidar e reler navegação e página. Leituras de navegação consultam uma fotografia coerente dos heads autorizados; antes de emitir conteúdo, o resolvedor revalida acesso/contexto. Cache antigo nunca autoriza acesso. Um clique em item recentemente retirado retorna estado indisponível e atualiza a navegação, sem abrir configuração antiga. Invalidação distribuída persistida deve ser recuperável após falha de processo, usando infraestrutura existente onde disponível.

Rollback seleciona uma release histórica íntegra e hoje admitida, restaurando widgets removidos e vínculos próprios. Não executa novamente ações de negócio. Se contrato, versão de componente ou destino de navegação não for mais admitido, preserva o head e informa impedimento; o caminho de recuperação é preparar uma nova revisão compatível, nunca adulterar a release histórica.

### D5 — menus compartilhados

Uma página pode ter várias entradas sem duplicar conteúdo. Cada vínculo contém identidade estável da entrada e do grupo, rótulo, ícone do catálogo aprovado e ordenação local. A ordenação não renumera páginas vizinhas; usar chave de ordem e desempate estável. Alterar rótulo/ícone/posição de uma entrada é alteração da página e segue sua publicação.

Grupos possuem identidade própria e administração separada. A decisão é usar **o mesmo protocolo de governança** para o documento de grupos da aplicação, com target estável e codec nativo de navegação; não criar outro motor de aprovação. Essa admissão também é evolução necessária. Seu documento contém grupos e sua organização, sem replicar os vínculos de todas as páginas. O grupo e a página são agregados distintos.

Criar grupo pelo painel dispara o fluxo próprio; a interface mostra rascunho/aprovação/publicação desse grupo. Publicar a página exige grupos ativos e autorizados. No incremento inicial, não prometer publicação atômica de dois agregados: grupo primeiro, página depois. Falhar a página pode deixar um grupo vazio publicado; isso é estado válido, sem página parcialmente publicada, e deve ser comunicado.

Rollback de página não reverte título, ordem ou existência de grupos. Toda transição do head de grupos que remova/desative um grupo — publicar outro documento, rollback ou withdraw — exige ausência de vínculos ativos. O protocolo genérico atual não garante essa integridade entre roots: a extensão canônica deve verificá-la dentro da transação, junto da condição do head. O operador primeiro move/remove os vínculos pelas revisões das páginas. Uma mudança de política pode retirar a visibilidade do grupo imediatamente, sem ampliar acesso; a página permanece acessível apenas por meios e permissões válidos. Nunca ressuscitar grupo ou mover conteúdo para outro público silenciosamente.

Serialização obrigatória: transições de página e de grupos adquirem os mesmos locks por escopo e IDs dos grupos afetados, em ordem determinística; revalidam os heads e as referências após adquirir os locks e antes do commit. O conjunto inclui vínculos anteriores e candidatos; se o head usado no cálculo mudou, abortar por conflito, sem prosseguir com locks incompletos. Índices de referências são derivados e mantidos na mesma transação do head, ou a checagem lê os heads autoritativos sob a mesma exclusão. Não usar um índice eventualmente consistente para autorizar remoção de grupo.

### D6 — configuração publicada versus storage dos filhos

O runtime materializa filhos a partir do documento congelado da página. `autoPersist=false` no root é insuficiente: os providers/adapters oficiais devem impedir que `ui_user_config`, cache ou preferências locais sobrescrevam a configuração autoral publicada dos filhos. Durante edição, salvar no editor filho atualiza o draft da página; não grava um “latest” autônomo em outra chave.

Dados de negócio e submissões continuam no backend proprietário do recurso. Estado transitório de sessão não é revisão de configuração. Personalizações individuais sobre páginas novas ficam fora do primeiro incremento; não serão achatadas na cópia nem promovidas à publicação. Hosts que precisem dessa capacidade já existente em outros fluxos não podem ativá-la por um caminho implícito nesta superfície.

Preview de publicação usa renderização sem execução de mutações, admitida pelos adapters. Não basta esconder botões: hooks, ações automáticas e handlers também devem respeitar o contexto de preview. Componentes sem esse contrato ficam fora do preview interativo; indicar a limitação. Ensaios de envio usam dados/ambiente de teste admitidos, sem apresentar preview como sandbox de rede.

## 4. Fronteiras públicas mínimas a evoluir

Nomes de operações abaixo expressam a semântica fixada; assinaturas de escrita e envelopes estão formalizados no [P0](README.md), antes dos consumidores; DTOs detalhados de descoberta/resolução pertencem ao P3, com as invariantes abaixo já fixadas. Nenhuma URL nova será improvisada no host.

| Superfície/dono | Operação/resultado planejado | Reuso e alteração |
|---|---|---|
| Config, catálogo de páginas | Criar a partir de fonte admitida; consultar/listar identidades autorizadas; reconciliar criação por chave | Nova superfície sob `/api/praxis/config/**`, sem duplicar publish/rollback. Entrada inclui referência de fonte e propósito; principal/escopo efetivo são server-side. |
| Config, fonte/registro | Resolver root estável e capturar baseline da página/filhos com evidência | Implementação canônica de catálogo consumida pelos SPIs de host; autorização continua host-owned. |
| Config, codec/reprodução | Admitir documento de página e documento de grupos; gerar contribuição e validar reprodução exata | Evoluir projeção fechada e validadores; não habilitar JSON genérico como fallback. |
| `/api/praxis/config/ui-layouts/**` | Rascunho, revisão, atribuição, aprovação, publicação, retirada e rollback desses novos tipos | Reusar operações/ETags/idempotência já existentes; acrescentar tratamento nativo e receipts mínimos necessários, sem novo estado paralelo. |
| Resolução efetiva/descoberta | Entregar documento de página e navegação autorizados vinculados à release ativa | Evoluir materialização agregada, recibos/coerência e DTO de navegação com ícone; não retornar entitlements privados. |
| Core/Page Builder | Serializar documento nativo, remapear cópia, validar composição, orquestrar jornada e consumir receipts | Reusar `WidgetPageDefinition` e adapters/editores; contratos compartilhados na fonte TS canônica já adotada, sem fachada transitiva entre barrels. |
| Host | Namespace, contexto, política de criação/publicação, catálogo de tipos e integração do shell | Consumir semântica de Config; sem repositório corporativo privado de páginas ou inferência de permissão por texto. |

As lacunas justificam apenas os dados faltantes: identidade de criação durável, fonte/revisão e transformação da cópia, envelope nativo agregador, vínculos próprios e referência coerente de publicação. Não introduzir nova DSL de formulário, de composição, de audiência ou de regra de negócio. Decisões de domínio continuam na fonte canônica; a página apenas referencia/materializa capacidades admitidas.

## 5. Concorrência, persistência e recuperação

- Criar/copy: chave idempotente scoped ao ator/tenant/app/ambiente, vinculada ao comando normalizado. Mesma chave com corpo diferente conflita. Catálogo + baseline + associação ao draft são persistidos atomicamente no mesmo recurso transacional de Config. Fonte é uma revisão imutável fixada, sem consulta “latest” durante a cópia.
- Salvar trabalho: revisão/ETag conforme lifecycle. Confirmar salvamento apenas com receipt. Edição concorrente gera conflito explícito; não aplicar merge automático de grafos.
- Publicar/reverter: condição forte do head, política e dependências revalidadas na transação. Repetição após perda de resposta consulta receipt/head/comando; não gera outra página nem reaplica ação cegamente. Expandir reconciliação somente onde o protocolo existente não oferecer identidade suficiente.
- Troca de contexto: invalidar callbacks e caches antigos com revisão monotônica, inclusive A→B→A. Resultado incerto é reconciliado no escopo original autorizado, sem atribuí-lo ao contexto novo.
- Limites: manter os limites auditados até prova contrária — documento/candidato e patch de 256 KiB e profundidade admitida, com limite HTTP próprio. O agregado deve passar nesses limites completos, incluindo metadados pertinentes. Mostrar diagnóstico de excesso antes de confirmar; backend continua autoridade. Não dividir a página em gravações independentes para contornar o limite.
- Retenção: preservar releases, fontes de cópia e evidência necessárias à auditoria/rollback; excluir vínculo de menu não apaga página. Primeiro incremento usa retirada/arquivamento, sem exclusão física de páginas governadas com dependências históricas.

## 6. Laboratório local e migração limpa

O laboratório implementará a mesma separação conceitual de identidade, rascunho, cópia e disponibilização, com adapter de navegador explícito. Ele não emula identidade autenticada, aprovação corporativa nem segurança entre personas. Seus limites permanecem 30 páginas, 20 menus e 2 MiB por namespace até alteração deliberada validada; não se promete transportar qualquer documento local para produção.

Migração do envelope local atual: manter UUIDs e URLs existentes; transformar cada página salva em conteúdo disponível localmente; gerar vínculos de menu a partir das referências existentes e adotar `web` como ícone quando ausente. Origem histórica desconhecida permanece desconhecida, sem fabricar revisão corporativa. Criar uma cópia local usa essa fonte local explicitamente identificada. A migração é validada inteira e gravada sob o lock existente, com preservação dos bytes anteriores em caso de quota/corrupção; não manter duas fontes ativas. Rascunhos passam a ser persistidos separadamente da revisão disponível, mantendo o estado anterior em caso de falha.

Páginas do exemplo gravadas como `published-page:<uuid>` em `ui_user_config` exigem importação explícita: preservar endereço no catálogo, validar documento e fontes, criar draft canônico e submeter à política. Elas não recebem status de aprovação por já terem uma URL. O corte do consumidor ocorre após inventário/migração dos documentos suportados; documentos sem proveniência ficam exportáveis/preservados e com diagnóstico, sem fallback invisível para o renderer antigo. Não apagar armazenamento legado antes da reconciliação e da política de retenção.

## 7. Plano de implementação ordenado

| Pacote | Dono e entrega | Dependência e critério de saída |
|---|---|---|
| P0 — contratos e corpus | Config + Core/Page Builder: formalizar schema/envelope, fontes, remapeamento, operações e exemplos válidos/inválidos; mapear consumidores de `PageIdentity`/`PersistedPageConfig` | Decisões D1–D6 mantidas; compatibilidade e orçamentos explícitos; revisão independente de contrato antes de publicar API. Sem wiring local antecipado. |
| P1 — codecs e runtime derivado | Config + libs donas: projeção/reprodução de página e grupos, validação recursiva, fontes/metadata e adapters Table/Form | Round-trip sem perda; inputs malformados/sem origem negados; uma fonte por configuração; documentos representativos cabem no orçamento. |
| P2 — criação e lifecycle | Config: catálogo, criação/cópia idempotente, registro root estável, drafts e publicação existentes | Criar/copy transacional; conflitos/retries/troca de contexto; adicionar/remover/restaurar widgets pelo documento, sem mudar membership externo. |
| P3 — resolução e navegação | Config + host operacional: head único, grupos independentes, navegação com ícone, autorização e invalidação | URL/menu resolvem publicação válida; grupos compartilhados não sofrem rollback de outra página; publish/rollback/withdraw de grupos e transições concorrentes de página preservam referências sob os mesmos locks. |
| P4 — integração de autoria/uso | Core/Page Builder/Settings Panel + hosts: jornada e proteção contra storage concorrente dos filhos | Editar preserva publicação; cópia não altera origem; preview não executa mutações; callbacks obsoletos não modificam novo contexto. |
| P5 — demonstração e migração | Hero + exemplo Angular + Quickstart: adapters, migração local/importação e prova corporativa | Mesmo fluxo com limites explícitos; atualização da página existente, múltiplos menus, reload direto, histórico e recuperação verificáveis. |
| P6 — documentação e adoção | Landing, recipes, corpus HTTP e skills | Provas aprovadas na árvore/dependências atuais; exemplos e guidance canônicos atualizados antes de anunciar a capacidade. |

Responsabilidades são por dono canônico, não atribuições de processos em execução. P0 foi formalizado em checkout isolado de Config; P1–P6 não iniciados. O P0 mantém as decisões arquiteturais e explicita o perfil de admissão inicial e a extensão childSources para filhos novos, sem wiring local antecipado. Qualquer impossibilidade concreta futura exige ADR antes de contorno local.

## 8. Matriz mínima de aceite

| Caso | Resultado obrigatório |
|---|---|
| Página com formulário e tabela | Salvar/reabrir pelos editores nativos e renderizar sem perda de propriedades ou referências. |
| Inserir/remover widget e conexão | Nova revisão estrutural válida; original e publicação anterior intactos; rollback reproduz a estrutura antiga. |
| Copiar página com preferências pessoais e dados carregados | Somente revisão autoral selecionada; nova identidade de filhos; nenhum dado/sessão/segredo copiado; vínculos internos corretos. |
| Filho com storage próprio | Configuração publicada não é substituída por local/remoto; salvar filho altera apenas draft da página. |
| Duas abas/atores editando e resposta perdida | ETag/conflito preserva trabalho; reconciliação encontra o mesmo comando/página; nenhuma duplicação. |
| Mesmo título, título renomeado e reload | Identidades distintas quando páginas diferentes; mesma URL e configuração quando só título muda. |
| Duas páginas no mesmo grupo | Rollback de A restaura apenas A; alterações publicadas de B e do grupo permanecem. |
| Publicar enquanto grupo é retirado | Uma ordem transacional válida vence; nenhuma referência ativa para grupo removido por comando administrativo. |
| Rollback/withdraw do documento de grupos com página vinculada | Bloquear a transição que removeria grupo referenciado, preservando o head; cobrir também publish de documento que omita o grupo. |
| Negação/revogação por tenant/app/ambiente | Catálogo, URL, navegação, cópia, edição e APIs de dados verificam suas próprias permissões; cache/preview não ampliam acesso. |
| Preview com hooks/ações automáticas | Nenhuma mutação no domínio; teste intercepta também ações indiretas, não apenas botão submit. |
| Rollback com dependência retirada/incompatível | Negação explicada e head atual preservado; recuperação por nova revisão, sem adulterar histórico. |
| Limite de documento/quota e migração local | Falha explícita sem perder documento anterior nem deixar menu sem página. |
| Catálogo com 1.000 páginas sintéticas | Listagem paginada e carga sob demanda; publicar uma página não exige regravar todas. Medir latência/memória para definir SLO, sem anunciar desempenho não medido. |

Validação focal: testes de codec/proveniência/reprodução e lifecycle em Config; testes de serialização/composição e storage nos owners Angular; build da lib alterada e consumidor direto; E2E dos dois hosts para a jornada pública, desktop e 390 px com painel aberto/teclado. Backend dependente de banco: Neon remoto em branch isolada e atribuída, com projeto/endpoint/TLS/versão e papéis owner/runtime/control plane conforme o fluxo. Unitários sem banco podem ser locais. Exemplos/manifestos/corpus atualizados usam seus validadores oficiais.

Não repetir suites amplas por reflexo. Evidência de versão, fontes e dependências deve acompanhar cada gate. Uma prova local de UX não aprova as garantias corporativas.

## 9. Artefatos, skills e aceite desta entrega

Impactos públicos futuros: schema/codec/contratos de Config, contratos TS intencionais no dono, projeção de navegação, registries/manifests dos tipos admitidos, documentação Landing, exemplos Angular/Hero, receipts e corpus HTTP, guidance de persistência e composição. Não há mudança proposta em schema de domínio ou autorização de dados do Metadata Starter; esses contratos são consumidos.

Skills canônicas consultadas: `praxis-product-evolution`, `praxis-config-runtime-persistence` e `praxis-page-builder-composition`, em `praxis-codex-skills/codex-skills/`. Para esta entrega de desenho: `existente-adequada` quanto ao inventário, ownership e reuso; não ensinar a nova API antes de existir. No pacote P6: `atualizar-existente` para documentar o novo caminho governado, limites do local e validações, após estabilização do comportamento. Usar auditoria/manifesto/validação/revisão e entrega canônica da família antes de sincronização seletiva. Não foi alterada skill nesta entrega.

Na entrega inicial do plano (anterior ao P0), validação documental concluída: referências locais resolvidas, cercas Markdown balanceadas, ausência de whitespace inválido e `git diff --no-index --check` sem diagnósticos; confronto com os arquivos dos commits acima. O workspace não é uma raiz Git, por isso foi usada comparação no-index contra a proposta anterior e arquivo novo. Naquela entrega inicial não foram executados testes/builds/banco ou ações de publicação; a evidência P0 está no relatório vinculado abaixo.

**Registro da entrega inicial do plano:** coordenador é autor dos dois documentos; subagente `page_inventory` forneceu análise/revisão read-only, Sol/high solicitado, sem inferência de modelo efetivo não exposto. A revisão cobriu D1–D6, lacunas, migração, concorrência e as cinco correções. O achado sobre remoção de grupos por publish/rollback/withdraw foi corrigido e reverificado; o reset explícito de aprovação/audiência/menu na cópia também foi conferido. Parecer final: **apto como proposta e plano técnico**, sem bloqueadores documentais no escopo revisado. Isso não aprova implementação ou garantias executáveis.

**Atualização P0 — 22/10/2026:** [contratos e corpus](README.md) entregues localmente em Config, branch `codex/page-publication-p0-20261022`, base `60ff10a4b95538f540fc0002e7df91c03bc518e1`. [Relatório de validação e revisão](VALIDATION.md). P1–P6 não iniciados; nenhuma API, migração ou alteração de host ativada. Registro original do P0: sem publicação naquele corte. Disponibilização posterior em PR, sem merge/release, acompanhada no épico GitHub.

P0 decidiu a representação única em inputs.config, reconstrução nativa sem defaults, bindings fixos por identidade e seeds registradas para filhos novos. Corrigiu composition.version para o valor Core 1.0.0 e remapeamento tipado (widget/link). O perfil inicial de WidgetPageDefinition é restrito e nega autoria incompatível sem perda silenciosa; leia a lista no contrato antes de implementar a paleta.

Coordenador foi o único escritor do pacote; page_inventory fez inventário e revisão independente read-only. Build/teste focal usa somente o target do worktree isolado; nenhum banco ou recurso mutável compartilhado. Próximo passo: P1, codecs/admissão/round-trip nos donos canônicos, sem avançar diretamente para hosts. Evidência válida e limitações constam do relatório; prova P0 não substitui Neon/HTTP/E2E posteriores.


## Controle da entrega no GitHub

Esta é a cópia versionada da proposta/plano do workspace, preservada como baseline. O [roteiro de entrega](DELIVERY.md) define pacotes, dependências e coordenação entre máquinas; o épico vinculado no PR controla status e responsáveis. Hero HQ é o laboratório/vitrine final. Contratos de runtime novos continuam indisponíveis até suas implementações e provas. Fontes locais não publicadas citadas na pesquisa são inventário histórico, não links nem prova remota.
