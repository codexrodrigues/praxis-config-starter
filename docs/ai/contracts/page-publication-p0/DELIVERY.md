# Entrega coordenada — páginas governadas

Estado deste documento: plano versionado. Status operacional, responsáveis, PRs e impedimentos
ficam nas issues e no épico GitHub vinculado no PR. IDs abaixo são IDs de pacote, não números
GitHub. O épico fica no **praxis-hero-hq-ui**; issues técnicas pertencem aos seus owners.

## Objetivo e definição de pronto

Um visitante cria e copia páginas/formulários, configura menus/ícones e reencontra após recarga
no laboratório local. Uma aplicação corporativa usa os mesmos componentes, com fontes admitidas,
autorização, revisão/publicação e histórico reais. Hero HQ é a vitrine/laboratório principal;
Quickstart prova backend; exemplo Angular prova integração mínima; Landing ensina adoção.

Pronto exige implementação integrada, provas de integridade/isolamento/concorrência, UX educativa
acessível, Hero testado, exemplos reproduzíveis, documentação detalhada, skills atualizadas e
matriz de artefatos adotável. P0, PR aberto, mock ou screenshot isolado não encerram o programa.

## Protocolo para outras sessões e computadores

1. Ler épico, issue, [contrato](README.md), [plano](TECHNICAL-PLAN.md), AGENTS dos repos e
   [guia canônico de agentes](https://github.com/codexrodrigues/praxis-codex-skills/blob/a2cf40200f4f3e47bb2d94bad713ac046eb28777/docs/platform-governance/agentes-e-modelos.md).
   Confirmar instruções mais recentes; a cópia do workspace não viaja automaticamente.
2. Antes de codar, registrar na issue intenção de assumir, sessão/máquina, branch, SHA base,
   arquivos/pacotes, recursos exclusivos, plano de prova e revisor. Coordenação confirma um único
   responsável ativo. Comentário por si só não é lock atômico: resolver disputa antes de editar.
   Ausência de assignee não prova disponibilidade. Sem confirmação, fazer apenas leitura/preparação.
3. Trabalhar em branch/worktree isolado. Dependências devem estar integradas ou ter SHA de contrato
   explicitamente aprovado pelo coordenador; não criar cópia local de contrato para desbloquear-se.
4. Um escritor por arquivo/pacote; reservar dispatchers, public-api, migrations, targets, Maven
   coordinates, dev servers e branch Neon. Paralelo só em escopos independentes. DB remoto Neon
   isolado com projeto/branch/TLS/versão/papéis conferidos; nunca pai/produção ou fallback local.
5. Autor abre PR ligado à issue e registra evidência por SHA/dependências/comando. Revisor diferente
   do autor confronta contrato, negócio, consumidores, docs e skills. Merge técnico e entrega
   completa são estados distintos. Não fechar issue apenas por existir patch/teste local.
6. Ao parar, registrar progresso, pendências, processos/recursos liberados e próximo passo.
   Não herdar autorização para release/deploy de um pedido de implementar/testar.

Estado operacional sugerido em comentários: disponível → reservado → em execução → em revisão
→ integrado → validado. Bloqueio é relação de dependência e motivo concreto. Encerrar somente
quando critérios da issue foram cumpridos e evidência revisada. Não usar percentuais fictícios.

## Mapa de pacotes e dependências

| ID | Repositório dono | Entrega | Bloqueado por |
|---|---|---|---|
| P0 | praxis-config-starter | Integrar baseline de contratos, corpus e plano de páginas governadas | — |
| P1-A | praxis-config-starter | Implementar codec de página e admissão de filhos Form/Table | P0 |
| P1-B | praxis-ui-angular | Formalizar codec de página, identidade e remapeamento canônico no Core | P0 |
| P1-C | praxis-ui-angular | Criar adapters nativos Form/Table sem defaults e sem storage concorrente | P0 |
| P1-D | praxis-config-starter | Implementar documento nativo de grupos e integração ao lifecycle existente | P0 |
| P2 | praxis-config-starter | Criar catálogo e operação transacional de criar/copiar página | P1-A, P1-B |
| P3-A | praxis-config-starter | Garantir publicação, rollback e integridade entre páginas e grupos | P2, P1-D |
| P3-B | praxis-config-starter | Resolver catálogo, URL e navegação pelo mesmo head autorizado | P3-A |
| UX | praxis-ui-angular | Desenhar jornada educativa de criar, editar e disponibilizar páginas | P0 |
| P4 | praxis-ui-angular | Implementar jornada compartilhada de autoria e publicação nos hosts | P1-B, P1-C, P3-B, UX |
| P3-C | praxis-api-quickstart | Provar integração corporativa por HTTP e Neon remoto | P3-B, P1-C |
| P5-A | praxis-hero-hq-ui | Migrar laboratório local para autoria, cópia e menus com ícone | P4 |
| P5-B | praxis-hero-hq-ui | Integrar laboratório corporativo e biblioteca de páginas no Hero HQ | P5-A, P3-C |
| P5-D | praxis-ui-angular | Atualizar exemplo oficial e importar páginas publicadas legadas | P4, P3-C |
| P5-C | praxis-hero-hq-ui | Certificar a vitrine: E2E, acessibilidade, recuperação e escala | P5-B, P3-C |
| P6-A | praxis-ui-landing-page | Publicar documentação detalhada, tutorial e playground de páginas governadas | P5-C, P5-D |
| P6-B | praxisui-http-examples | Atualizar corpus HTTP e superfície LLM para o lifecycle de páginas | P3-C |
| P6-C | praxis-codex-skills | Atualizar skills canônicas e protocolo de adoção das páginas | P5-B, P5-D |
| RELEASE | praxis-config-starter | Fechar compatibilidade e prontidão de distribuição entre os repositórios | P5-C, P5-D, P6-A, P6-B, P6-C |
| ACEITE | praxis-hero-hq-ui | Concluir aceite integral e demonstração reproduzível da plataforma | RELEASE |

## Paralelismo e caminho crítico

Após P0, P1-A (Config), P1-B (Core), P1-C (Form/Table) e UX são frentes independentes com
coordenação de arquivos. P1-D pode desenvolver codec isolado, mas dispatcher compartilhado com
P1-A tem escritor único. P2 → P3-A → P3-B é caminho crítico de backend. P4 e P3-C podem avançar
em paralelo após DTOs; Hero local P5-A e exemplo P5-D têm gates próprios. P5-B aguarda P5-A para
não disputar shell/routes. Docs/skills/corpus podem preparar rascunhos antes, mas só são aceitos
quando correspondem às versões comprovadas. RELEASE e ACEITE fecham a cadeia, sem auto-deploy.

P0 já tem prova local histórica (63 testes e revisão independente), ainda depende de integração
no repo. Não iniciar automaticamente implementação em hosts ao cadastrar este backlog.

## Pacotes para execução

### P0 — Integrar baseline de contratos, corpus e plano de páginas governadas

Integrar somente especificação, schemas, corpus, testes de contrato e documentos do P0. Inventário: contrato público proposto, nenhuma API ativa. Revisar contra o HEAD atual antes do merge; preservar os invariantes do baseline.

Critérios:

- Schema/corpus e plano disponíveis por SHA para todos os trabalhadores; links portáveis.
- Revisão independente; 63 testes focais do corte original reconciliados com a árvore integrada.
- Explicitar perfil inicial limitado, single target, autoria única, childSources, binding fixo e cópia tipada.
- Não habilitar hosts, publicar bibliotecas nem encerrar o épico com a entrega do P0.

Validação: mvn -B -Dtest=PagePublicationP0ContractTest test; diff/check de docs; sem banco. Evidência de 22/10 é histórica até confirmar o SHA atual.

Coordenação: Pronto para revisão/integração. Desbloqueia implementação dos contratos; planejamento de testes e UX pode ser preparado enquanto aguarda.

### P1-A — Implementar codec de página e admissão de filhos Form/Table

Owner: projeção/reprodução, UiLayoutDraftWorkspaceSource, capturas e validators nativos. Lacuna real: praxis.dynamic-page.editor e evidência por filho interno novo. Manter targets externos=[root].

Critérios:

- Round-trip e patch reproduzem config integral; uma única autoria por filho.
- Novas keys exigem childSources exatas; servidor resolve fonte versionada, captura e reproduz seed; sem atestado do cliente.
- Envelope/bindings fixos por identidade; substituir exige key nova; restauração usa evidência histórica ainda admitida.
- Validar cada filho mesmo dentro de array atômico; versões/tipos/inputs não admitidos negados.
- Manter limites 256KiB/profundidade64/body HTTP; migrar teste de ausência de codec P0 para provas positivas.

Validação: Testes focais de codec, metadata, reprodução, seeds e falhas. Paridade com vetores TS do P1-B/P1-C antes de aceitar integração; banco somente se necessário em Neon isolado.

Coordenação: Pode avançar com P1-B, P1-C e UX. Compartilha dispatcher/projection com P1-D: um escritor nesses arquivos e integração sequencial.

### P1-B — Formalizar codec de página, identidade e remapeamento canônico no Core

Owner: Core/contratos TS intencionais. Reutilizar WidgetPageDefinition/PageIdentity, composition.version=1.0.0. Sem novo grafo nem fachada transitiva entre barrels.

Critérios:

- Serializar somente perfil admitido, preservando presença/ausência/null; negar autoria fora do perfil sem descartar.
- Cópia usa mapa (kind,id), endpoints por widget; colisão widget/link coberta; strings comuns preservadas.
- Nova página/filhos recebem identidades novas; routePath derivado é estável; título não muda storage.
- Documentar e testar paridade com corpus Config; exports mínimos e dependências públicas revisadas.

Validação: Testes focais Core + build da lib e consumidor direto; round-trip/cópia; E2E focal se API/runtime público mudar. Nenhum host privado substitui o codec.

Coordenação: Paralelo a Config e adapters de filhos; reservar arquivos Core/public-api por responsável, integrando antes de P4.

### P1-C — Criar adapters nativos Form/Table sem defaults e sem storage concorrente

Owners: Dynamic Form e Table. Suporte parcial: editores já existem, mas exportam defaults/IDs e não recebem authoringDocument runtime. Uma única fonte inputs.config.

Critérios:

- Reconstruir documentos nativos com bindings/contextSnapshot e presença exata; nenhuma configuração duplicada.
- Round-trip sem perder autoria, sem materializar defaults como decisão do autor; campos fora do perfil recebem diagnóstico.
- Config publicada é controlada pela página; filhos não resolvem latest pessoal/remoto nem persistem por conta própria.
- IDs runtime derivados de pageId+widgetKey; submit/dados continuam domínio; salvar editor altera só draft.
- Testar binding change, seed nova, null de Form versus Table e limites; não introduzir input fictício.

Validação: Testes dos owners, build focal de cada lib alterada e consumidor; teste de reopen e storage conflitante; E2E focal dos adapters.

Coordenação: Paralelo P1-A/P1-B em arquivos dos owners; compartilhar contrato P0. Writer único por lib; expansão para Core exige coordenação.

### P1-D — Implementar documento nativo de grupos e integração ao lifecycle existente

Lacuna real: praxis.navigation-groups.editor no target praxis-app-shell. Grupos planos com identidade/ícone/ordem, sem armazenar vínculos das páginas. Não criar máquina de publicação alternativa.

Critérios:

- Codec/projeção/reprodução nativos e registro root estável por escopo de navegação.
- Dados de grupos separados de páginas; ausência de grupo representa remoção; nenhuma lista global de pages no documento.
- Schema/ícones/IDs únicos e fontes admitidos; resolver alterações via draft/revisão/review/head existentes.
- Guardas entre grupos/páginas serão integradas em P3-A antes de permitir transições operacionais.

Validação: Testes focais de codec/lifecycle de grupos; os testes de integridade entre agregados são gate P3-A.

Coordenação: Implementação isolada do codec pode ser paralela P1-A; dispatcher compartilhado requer escritor único. Não habilitar transições de grupo antes de P3-A.

### P2 — Criar catálogo e operação transacional de criar/copiar página

Owner Config: identidade e descoberta, criação idempotente, registro root, baseline, capturas e draft. Fonte selecionada publicada ou registered-native; nenhum effectiveLayout/draft/DOM como origem.

Critérios:

- Criação/cópia no mesmo tenant/app/env; autorização atual de ler/copiar/criar; identidade de servidor e namespace registrado.
- Catálogo+registro+baseline+capturas+draft+receipt são atômicos.
- CreationKey por ator/escopo: replay igual mesma página, corpo diferente409; resposta perdida reconciliável.
- Cópia com hashes/mapa/transformRef, sem aprovação/assignments/menu/head herdados; sem dados runtime/segredos.
- Retenção da evidência/idempotência; primeira publicação permanece no lifecycle existente; sem endpoint paralelo publish.

Validação: Unitários + transações/fault injection/concorrência em Neon remoto isolado. Validar HTTP de criação/replay e falhas posteriormente no Quickstart P3-C.

Coordenação: Depende de codec e mapa canônicos. Pode coexistir com integração Angular P1-C e UX; sem migrations paralelas na mesma branch de banco.

### P3-A — Garantir publicação, rollback e integridade entre páginas e grupos

Owner Config lifecycle/referential integrity. Todas transições de head de página/grupos devem compartilhar protocolo transacional de locks por escopo+groupId.

Critérios:

- Publish/rollback/withdraw de grupo não removem grupo usado por página ativa, incluindo omissão num novo documento.
- Locks determinísticos incluem head anterior/candidato, releitura sob lock e retry quando conjunto muda.
- Índice de referências transacional ou leitura autoritativa; eventual consistency não autoriza integridade.
- Rollback de A não altera B/grupo; dependência retirada nega operação e preserva head.
- Pré-condições fortes de draft/review/head; publicação inteira conserva estrutura e vínculos próprios.

Validação: Testes de interleavings, deadlock/retry, falha de commit e ETag em Neon remoto exclusivo, papéis operacionais reais; revisão independente de transações.

Coordenação: Trecho crítico coordenado; não dividir writers de lifecycle/locks. Consumidores podem desenvolver testes de contrato sem simular aprovação desta prova.

### P3-B — Resolver catálogo, URL e navegação pelo mesmo head autorizado

Owner Config leitura/runtime. Formalizar DTOs de descoberta/resolução no dono, reutilizar recibos/contexto; projetar ícone no DTO EnterpriseRuntimeNavigationNode.

Critérios:

- URL catálogo→target→head e navegação usam a publicação autorizada; não duplicar ponteiro de publicação.
- Autorização/contexto antes de ETag/304; revogação impede conteúdo de cache; A→B→A não aceita callback velho.
- Sem fallback para draft/baseline quando não publicado/withdrawn; clique em menu antigo trata indisponibilidade.
- Paginação e carga sob demanda; snapshots coerentes, receipts e invalidação recuperável após falha.
- Grupos/vínculos/ícones/ordem têm projeção determinística sem vazar entitlements.

Validação: Testes de resolução, segurança, cache, contexto, paginação e recuperação. Provas com banco em Neon isolado; HTTP real em P3-C.

Coordenação: Depois das invariantes de publicação. DTO estabilizado libera wiring P4 e host; design de testes pode antecipar sem alterar contrato.

### UX — Desenhar jornada educativa de criar, editar e disponibilizar páginas

Design da superfície compartilhada Page Builder/Settings Panel/shell, com Hero HQ como referência visual. Reutilizar ícones, previews, diagnostics e capabilities já existentes; não criar negócio no frontend.

Critérios:

- Fluxo criar a partir desta/template → editar draft → revisar título/ícone/menu/público → publicar → abrir/reabrir.
- Separar salvar trabalho de publicação; explicar URL estável, fonte da cópia, alterações não publicadas e indisponibilidade.
- Estados de conflito, quota, fonte incompatível, falta de autorização e recuperação desenhados; acesso a detalhe técnico progressivo.
- Teclado/foco/leitores de tela e 390px/desktop; nomes e affordances consistentes com contratos reais.
- Storyboard e critérios visuais revisados; não anunciar recursos excluídos do perfil P0.

Validação: Revisão de produto/acessibilidade e walkthrough dos estados; runtime será provado em P4/P5-C.

Coordenação: Desenho pode iniciar durante revisão P0, implementação aguarda integração. Independente de backend; mudanças de contrato voltam ao owner.

### P4 — Implementar jornada compartilhada de autoria e publicação nos hosts

Owners Core/Page Builder/Settings Panel: providers e componentes reutilizáveis, sem repositório corporativo privado no host. Consome lifecycle/receipts/contexto reais.

Critérios:

- Criar/copiar, salvar draft, reabrir, revisar/publicar e recuperar conflitos/resposta perdida pelos contratos aprovados.
- Menu com ícone e preview fiel; autorização por capabilities; rascunho não muda página publicada.
- Preview bloqueia mutações inclusive hooks/ações indiretas; adapter não certificado não entra em preview interativo.
- Context generation monotônica; cancelar/inutilizar callbacks antigos; filhos sob autoria da página.
- API pública mínima com adapters de persistência/contexto explícitos para corporativo e local, sem simular aprovação no navegador.

Validação: Tests focais, build libs+consumidor e E2E da jornada/proteção de storage/preview/contexto; acessibilidade e estados do UX.

Coordenação: Pode executar com P3-C após DTOs estabilizados; um writer no wiring de autoria. Hosts aguardam contratos/adapters integrados.

### P3-C — Provar integração corporativa por HTTP e Neon remoto

Quickstart é host operacional de referência, não fonte de contrato. Registrar fontes/templates, namespace/contexto/admissão e dados de prova sanitizados; integrar versões dos owners.

Critérios:

- Criar/copy/replay, revisões, aprovação/publicação, withdraw/rollback, URL e navegação com HTTP real.
- Tenants/ambientes/atores distintos, revogação/ETag/negativos de autorização sem cabeçalhos fingindo grants.
- Concorrência grupo/página e falhas de resposta/commit verificadas em branch Neon isolada.
- Confirmar projeto/branch/endpoint/TLS/versão/papéis owner/runtime/control-plane; nenhuma mutação em pai/produção.
- Evidência vincula SHA dos sources, versões reais, identidades sanitizadas e resultado bruto; não confundir template com origem atestada.

Validação: Suite focal HTTP + banco remoto, sem PostgreSQL local/embutido substituto; instalar/empacotar artefatos pelo fluxo oficial sem colisão Maven.

Coordenação: Paralelo à UI P4 em checkout e branch Neon exclusivos. Não compartilhar credenciais/fixtures nem mutar Quickstart público.

### P5-A — Migrar laboratório local para autoria, cópia e menus com ícone

Hero HQ é vitrine da plataforma; modo visitante continua neste navegador, sem login. Adotar componentes/adapters oficiais, sem regras paralelas de publicação corporativa.

Critérios:

- Preservar UUIDs e /laboratorio/paginas/<uuid>; draft separado do conteúdo disponível localmente.
- Criar a partir de página, editar/formulário, salvar nova página, escolher menus/ícones e recarregar direto.
- Migrar storage inteiro sob lock; preservar bytes em corrupção/quota; sem duas fontes ativas ou provenance inventada.
- Limites atuais 30 páginas/20 menus/2MiB até decisão explícita; web quando ícone legado ausente.
- Copiar não altera origem; renomear não altera URL; UI identifica armazenamento local e não promete acesso entre dispositivos.

Validação: Tests repositório/adapters+migração+duas abas/quota e build host; E2E local focal com reload e rotas. Usar portas/origins documentadas.

Coordenação: Independente do backend após P4, mas shell/routes/menus compartilhados com P5-B: implementar/integrar em sequência.

### P5-B — Integrar laboratório corporativo e biblioteca de páginas no Hero HQ

Adotar integração oficial da UI+Config+Quickstart no Hero; contexto/capabilities reais. Host define namespace e catálogo admitido, não contratos nem autorização por personas simuladas.

Critérios:

- Jornada fim a fim de criação/template/cópia, draft, revisão/publicação, descoberta, menus/ícones, rota direta e reload.
- Publicação corporativa usa contexto autenticado; modo local permanece explícito e isolado.
- Página com Form e Table, várias entradas, rollback/retirada e mensagens educativas de conflitos/fonte negada.
- Trocar contexto/usuário e abrir URL negada não expõe página/dados; menus refletem grants reais.
- Nenhum atalho ao backend, CORS ad hoc ou persistência própria dos filhos; dependências pinadas e bootstrap pela API pública.

Validação: Build host + smoke real contra Quickstart/Neon isolado; evidência de contratos/version matrix. Certificação extensa em P5-C.

Coordenação: Depois de P5-A para não disputar shell/routes; exemplo Angular P5-D pode avançar em paralelo.

### P5-D — Atualizar exemplo oficial e importar páginas publicadas legadas

Aplicação de exemplo Angular adota a mesma superfície pública. Legado published-page:<uuid> exige importação explícita para draft governado, preservando /pages/<uuid>.

Critérios:

- Fluxo criar formulário pelo Builder → publicar nova rota → acesso direto/reload pelo contrato canônico.
- Importação valida fonte/conteúdo, preserva endereço, não fabrica aprovação; documento incompatível fica preservado/exportável.
- Sem fallback invisível ao renderer antigo nem exclusão prematura do armazenamento legado.
- Exemplo didático mostra configuração mínima do host, limites do perfil e erros/recovery; não cria API ou serviço paralelo.

Validação: Build consumidor direto, testes de importação/storage e E2E focal com reload, cópia, menu e negativas; artefatos oficiais atualizados.

Coordenação: Pode ser paralelo ao Hero após P4/P3-C; mudanças limitadas à aplicação de exemplo, sem competir com writers das libs.

### P5-C — Certificar a vitrine: E2E, acessibilidade, recuperação e escala

Gate de qualidade final Hero HQ em local e corporativo. Testar como analista/visitante e operador, com browser real e dependências comprovadas; não aprovar só por render ou mocks.

Critérios:

- Desktop/390px/teclado/foco: criar do zero/formulário e copiar, editar, salvar, publicar com ícones, navegar/reload.
- Edição preserva head; várias páginas/grupos, rollback/withdraw, duas abas, quota, corrupção e resposta perdida.
- Preview sem mutações indiretas; configurações pessoais não sobrescrevem release; revogação/contexto/URL negada.
- 1.000 páginas sintéticas no catálogo corporativo (laboratório local continua limitado a 30): paginação/carga sob demanda e publicar A sem regravar B; medir latência/memória e definir orçamento, sem SLO inventado.
- Evidência sanitizada por SHA, versão, browser/viewport; capturas/vídeo e falhas resolvidas; roteiro reproduzível de demonstração.

Validação: E2E focal real e revisão visual independente; banco Neon exclusivo para cenários dependentes; não repetir suites válidas sem mudança relevante.

Coordenação: Roteiro/fixtures podem ser preparados antes; execução final somente nos SHAs integrados. Evitar disputar dev server/branch Neon com outros ensaios.

### P6-A — Publicar documentação detalhada, tutorial e playground de páginas governadas

Landing projeta contratos dos owners. Documentar arquitetura, conceitos e uma jornada educativa com Hero HQ como vitrine; exemplos executáveis não redefinem backend.

Critérios:

- Guias separados para analista e integrador; criar/copy/editar/publicar/menu/ícone/reload/histórico/recuperação.
- Explicar local versus corporativo, configuração versus dados, fonte/seed, aprovação, permissão e limites do perfil.
- Tutorial completo com Form+Table, host mínimo, erros comuns, migração e snippets testados nas versões integradas.
- Playground, links e evidências do Hero; referências canônicas sem promessas de funcionalidades não certificadas.
- Docs de API/recipes/manifests derivados revisados com donos; docs acessíveis e responsivas.

Validação: Build/docs/playground e validadores focais da superfície; se alterar corpus dynamic-page, executar seu validador oficial.

Coordenação: Estrutura e texto preliminar podem ser escritos em paralelo; publicação factual e aceite dependem de versões e provas finais.

### P6-B — Atualizar corpus HTTP e superfície LLM para o lifecycle de páginas

Artefato operacional derivado; reutilizar endpoints/headers/ETags e semântica de Config. Não criar outra fonte de contrato ou omitir condições/recovery.

Critérios:

- examples.manifest.json, LLM_SURFACE.md e HTTP/payloads cobrem criação/copiar/replay/revisão/publicação/leitura/menu/rollback.
- Casos positivos e negativos: seeds, escopo/autoridade, ETag, grupos e resposta perdida.
- Nenhum segredo/dado real; sources/datasets de prova registrados e versões pinadas.
- Smokes executam against Quickstart real; documentação diferencia schema válido de origem admitida.

Validação: Validadores oficiais do manifesto/corpus + smoke focal; banco dependente somente Neon isolado atribuído.

Coordenação: Paralelo ao Hero e docs após HTTP P3-C; não mutar fixtures compartilhadas nem disparar workflows duplicados.

### P6-C — Atualizar skills canônicas e protocolo de adoção das páginas

Reavaliar skills de persistência, composição, autoria/round-trip e hosts na fonte canônica. Atualizar apenas guidance comprovado; nada de remendar espelhos locais.

Critérios:

- Registrar classificação por skill e evidência; procedimentos de integração, contratos, limites e testes atualizados.
- Ensinar semântica de source/seed/copy/head, storage dos filhos e diferença entre laboratório e corporativo.
- Auditoria/manifesto/validação do repo, revisão independente e entrega por PR canônico.
- Sincronização seletiva após integração, sem --force na família; registrar drift/pendência.
- Skills não apresentam feature planejada como disponível; onboarding em outra máquina reproduz caminho oficial.

Validação: Scripts oficiais de auditoria/validação e testes de ativação da skill; confrontar guidance com código/HTTP/UX integrados.

Coordenação: Paralelo às docs após implementação certificável; evitar alteração de skills por vários agentes ao mesmo tempo.

### RELEASE — Fechar compatibilidade e prontidão de distribuição entre os repositórios

Coordenação de integração/release train, sem mover semântica dos donos. Registrar matriz fonte→artefato→consumidor e gate necessário para distribuição adotável.

Critérios:

- PRs dos owners integrados, versões de libs/Config/Quickstart/Hero/exemplo alinhadas e sem dependências locais ocultas.
- Gates focais existentes aprovados no mesmo SHA/artefato; checks obrigatórios não contornados; documentação e skills correspondentes.
- Inspecionar triggers antes de push/tag/dispatch, evitar workflows redundantes.
- Release/deploy só mediante autorização aplicável; esta issue permite preparar prontidão, não presume publicação automática.
- Se distribuição for necessária e não autorizada/disponível, manter gate pendente e não declarar produto 100% pronto.

Validação: Matriz de compatibilidade, builds/consumidores e smokes dos artefatos reais; quando houver publicação autorizada, postflight oficial correspondente.

Coordenação: Coordenação serial de integrações/versões e recursos; não disparar release de cada worker independentemente.

### ACEITE — Concluir aceite integral e demonstração reproduzível da plataforma

Encerrar o épico somente com resultado de produto completo. Hero HQ é o laboratório/vitrine de referência, com documentação, exemplos e operação demonstráveis.

Critérios:

- Executar roteiro final na matriz de versões distribuível/adotável e registrar URL/ambiente oficial autorizado.
- Visitante local e analista corporativo completam jornada; limites e audiência são claros.
- Revisão independente de integridade de código, negócio, plano versus executado, segurança contextual e docs.
- Todos requisitos e 15 cenários de integração têm evidência ou impedimento explícito; impedimento necessário mantém épico aberto.
- Histórico/rollback/retirada/migração e troubleshooting ensinados; não confundir issues fechadas com prova de resultado.

Validação: Checklist de aceite cruzado + demonstração final do Hero e do exemplo; evidências sanitizadas anexadas e versões pinadas.

Coordenação: Último gate do programa; não é tarefa de implementação paralela nem substitui revisão de cada PR.

## Relação com trabalho já existente

A consulta por páginas derivadas/praxis.dynamic-page.editor e rota no Hero não encontrou épico
ou issue equivalente no momento do cadastro. Existem temas adjacentes de authoring e
UiCompositionPlan (por exemplo Config #444); são referências de inventário, não dependências
inventadas. Cada worker deve repetir busca focal antes de criar contrato ou duplicar trabalho.
Novos arquétipos como tabs, fontes de dados e state estão fora do perfil inicial P0; qualquer
expansão exige decisão e prova no owner, sem promoção silenciosa de uma demo.

## Registro P1-A — 22/10/2026

Issue #508, escritor `p1_config`, branch `codex/page-publication-p1-config-508`, worktree isolada;
revisor é o coordenador distinto do autor. P0 #506 e refinamento #514 integrados, base atual
`996e15ffb305dfef9a5456530e3d677abf100c15`. Codec/admissão implementados no PR draft #515; testes focais, Neon e downstream Quickstart
registrados por SHA, sob revisão independente; detalhes, fontes, recursos e limites em [P1-A-IMPLEMENTATION.md](P1-A-IMPLEMENTATION.md).
Sem P1-D/P2, migração adicional, host de páginas ou release. Skills canônicas sob responsabilidade
do coordenador; nenhum arquivo compartilhado de skills foi editado por este trabalhador.

## Registro posterior P2/P3-A — 23/10/2026

P1-D/P2 foram integrados no Config em `78e914fdc1f03c7abf7e201fd2d2a79fc561f26f` (PR517). P3-A #511 usa worktree e Neon exclusivos, escritor `p2_catalog`, revisão independente do coordenador/peer e rastreio no PR518. [P3A-IMPLEMENTATION](P3A-IMPLEMENTATION.md) contém fontes, gates, limitações, recursos e skills. P3-B/hosts/UI/migração permanecem posteriores; nenhum deploy/release é inferido desse registro.
