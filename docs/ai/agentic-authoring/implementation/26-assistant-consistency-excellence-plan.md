# Plano de Excelencia e Consistencia do Assistente Praxis

Data: 2026-07-15

Status: plano ativo de produto e plataforma

## Objetivo

Levar o assistente Praxis do estado em que capacidades importantes funcionam
em cenarios isolados para um produto em que o basico funciona de forma
previsivel, explicavel e apresentavel. Antes de ampliar a quantidade de
capacidades, a plataforma deve certificar que um usuario novo consegue:

- perguntar o que pode fazer no contexto atual;
- receber uma recomendacao util de proximo passo;
- pedir uma tela, formulario, tabela, grafico ou filtro em linguagem natural;
- revisar o que sera criado ou alterado;
- aplicar, corrigir ou cancelar sem ficar preso em estado intermediario;
- entender quando o pedido e configuracao local ou decisao governada.

A meta nao e fazer a LLM gerar configuracao ocasionalmente correta. Praxis deve
operar como uma plataforma de decisoes semanticas authoradas por IA: a LLM
resolve intencao e plano usando contexto governado; contratos canonicos,
manifests e tools validam e materializam o resultado.

## Checkpoint de implementacao e evidencia real

O primeiro slice P0 foi implementado em 2026-07-15:

- corpus interno versionado `assistant-consistency-corpus.v1`, schema e runner
  comparavel com perfis `must-pass` e `extended`;
- oportunidade contextual no empty state do Page Builder para perguntar
  "O que posso fazer aqui?", enviada pelo turn controller canonico com escopo
  semantico estruturado;
- `semanticIntentClass` authorado pela LLM para separar a decisao semantica do
  tuple tecnico de materializacao;
- normalizacao fail-safe do tuple de `platform_guidance`, sem classificar o
  texto do usuario por palavras-chave;
- preservacao das quick replies do intent resolution no resultado terminal;
- conclusao de orientacao de plataforma a partir da primeira resposta
  semantica, sem uma segunda chamada LLM redundante;
- recuperacao nao mutante, em caso de falha do provider, quando a oportunidade
  governada da UI ja declarou `semanticScope=platform-capabilities`.

O segundo slice P0 fechou a perda de grounding entre decisao semantica e
discovery de recurso sem criar um contrato paralelo:

- o foco de recurso ja authorado pela LLM e reconciliado, depois da resolucao
  de intencao, com os `resourceKey` canonicos publicados por
  `contextHints.domainDiscovery`;
- identidade textual serve apenas para grounding e desambiguacao dentro desse
  escopo semantico ja resolvido; ela nao decide a intencao primaria;
- empates ou matches fracos permanecem fail-closed no fluxo normal de retrieval
  e clarificacao;
- quando o catalogo `api_metadata` do tenant/release ainda nao materializou o
  recurso canonico, o discovery publica um candidato `schema-probe-pending`,
  sem alegar schema ou evidencia semantica inexistentes;
- esse candidato so pode chegar a `canApply=true` depois da verificacao real
  por `/schemas/filtered` e dos gates normais de preview/materializacao;
- o prefixo semantico agora preserva marcadores estaveis para conceitos e
  surface ausentes, evitando que trechos posteriores sejam incorporados ao
  `resourceKey`;
- o intent resolver preserva o `artifactKind` do proprio
  `resourceDiscovery`, em vez de reclassificar a operacao pelo tipo do host
  Page Builder; assim, formulario usa o POST do recurso base e tabela usa a
  projection de consulta;
- o corpus interno passou a aceitar `expected.intent.submitUrls`, porque
  recurso correto com operacao incorreta e um falso positivo, nao sucesso.

O terceiro slice P0 fechou a prova transacional da vertical de formulario sem
criar endpoint, DTO ou persistencia paralela:

- o mesmo resultado terminal do turno alimenta
  `POST /api/praxis/config/ai/authoring/page-apply` com a decisao semantica e a
  materializacao compilada que passaram no preview;
- o gate exige readback exato pela fronteira canonica
  `/api/praxis/config/ui`, incluindo payload, quantidade de widgets, versao e
  `ETag`;
- um replay condicional com o `ETag` corrente deve preservar exatamente o
  estado materializado e a quantidade de widgets, avancando a versao e girando
  o `ETag`;
- uma nova tentativa com o `ETag` anterior deve falhar com `412` sem mudar o
  estado;
- o cleanup usa o ultimo `ETag`, exige `204` e confirma `404` no readback final;
- essa propriedade e convergencia de estado sob replay condicional, nao
  idempotencia da operacao HTTP: o replay valido incrementa a versao.

Evidencia obtida contra quickstart real, Neon, OpenAI e stream SSE:

- `platform-what-can-i-do-pt`: 3/3 execucoes consecutivas corretas, sem preview,
  sem recurso selecionado e com tres recommended intents;
- `platform-how-can-you-help-pt` e `platform-next-step-pt`: ambas passaram no
  ensaio focal posterior, tambem sem recurso e com tres proximas acoes;
- antes do segundo slice, formulario e tabela chegaram a selecionar projections
  analiticas de folha e afastamentos; a rodada completa daquele baseline passou
  apenas 1/6 e continua registrada como evidencia negativa, nao como aceite;
- depois do grounding canonico, formulario, tela aberta e tabela passaram 9/9:
  tres jornadas, cada uma repetida tres vezes, com preview, `canApply=true`,
  zero terminal incorreto e nenhum recurso analitico indevido;
- as nove execucoes selecionaram
  `/api/human-resources/funcionarios`; formularios usaram
  `POST /api/human-resources/funcionarios` com schema de request, enquanto tela
  e tabela usaram `/filter/cursor` para consulta;
- o gate da vertical aplicou o limite de 45 s e passou com mediana terminal de
  28,444 s; formularios ficaram entre 35,0 s e 36,388 s.
- depois da extensao transacional, a jornada de formulario passou novamente
  3/3 no gate de release com OpenAI e Neon reais: recurso e submit exatos em
  `/api/human-resources/funcionarios`, versao `1 -> 2`, um widget antes e depois
  do replay, tres rejeicoes de `ETag` obsoleto e tres cleanups confirmados;
- a mediana terminal dessa certificacao foi 35,804 s, com primeira mensagem de
  progresso imediata e todas as execucoes abaixo do limite de authoring de 45 s;
  a etapa transacional levou de 2 s a 3 s por repeticao.

O baseline funcional da orientacao melhorou, mas seu SLO de latencia ainda nao:
a mediana observada nas tres execucoes da pergunta basica foi aproximadamente
41 s. O trace mostra planning, discovery e resolucao semantica antes de uma
resposta que nao precisa materializar recurso. A telemetria agora representa
resolucao por evidencia como `intentResolveLlm=null`, mas a rota consultiva
ainda deve ser otimizada antes de usar P95 de 12 s como gate global.

## Classificacao e mapa de impacto

- Classificacao do plano: `arquitetural`.
- Classificacao do segundo slice: `transversal`, com comportamento interno de
  AI authoring e prova operacional; nenhum endpoint, DTO ou tipo publico novo.
- Classificacao do terceiro slice: `transversal`, restrito ao contrato interno
  de avaliacao e ao runner operacional; reutiliza apply, configuracao, ETag e
  delete publicos sem alterar suas superficies.
- Fonte canonica de orchestration e configuracao: `praxis-config-starter`.
- Runtime e UX canonicos: `@praxisui/ai` em `praxis-ui-angular`.
- Primeiros consumidores: Page Builder, Table e Dynamic Form.
- Consumidores seguintes: Charts, List, CRUD, Manual Form, Tabs, Stepper e
  Expansion.
- Provas operacionais: `praxis-api-quickstart`, playgrounds oficiais,
  `praxis-ui-landing-page` e `praxisui-http-examples` quando houver mudanca de
  contrato publico.
- Risco futuro: alto para runtime e contratos AI; qualquer implementacao deve
  ser dividida em slices focais e manter o caminho canonico fail-closed.

## Resultado do inventario de aderencia

O primeiro achado importante e que a plataforma ja conhece muito do que a UX
parece nao saber. O proximo ciclo nao deve criar um segundo catalogo de ajuda ou
um bot especial para o Page Builder.

| Necessidade | Aderencia | Evidencia atual | Acao correta |
| --- | --- | --- | --- |
| Responder "o que posso fazer aqui?" | `ja-suportado-mal-nomeado-ou-mal-materializado` | O prompt canonico classifica orientacao de plataforma, o context bundle publica `platformGuide`, componentes authoraveis e capabilities | Tornar a projecao obrigatoria em todo host e certificar a resposta ponta a ponta |
| Sugerir o proximo passo | `ja-suportado-so-ux` | `@praxisui/ai` possui `PraxisAssistantOpportunityCatalog`, `PraxisAssistantRecommendedIntent` e empty state; Table ja deriva recomendacoes | Derivar recomendacoes do contexto canonico em todos os hosts, sem listas locais concorrentes |
| Criar formulario/tela de funcionarios | `suportado-parcialmente` | Preview passou 9/9 e a vertical completa de formulario passou apply/readback/replay/cleanup 3/3 com recurso e operacao canonicos | Acrescentar variacoes linguisticas, refinamento, cancelamento e recuperacao na mesma jornada |
| Criar tabela, grafico e filtros | `suportado-parcialmente` | Manifests, handlers e validadores existem, mas a cobertura de hosts e o caminho de turno nao sao uniformes | Convergir os consumidores para o mesmo turn engine e os mesmos gates |
| Continuidade e estados terminais | `suportado-parcialmente` | SSE, replay, heartbeat, registry e shell existem | Definir state machine observavel, timeout, retry seguro e zero sessoes presas |
| Intencao semantica consistente | `suportado-parcialmente` | A LLM ja produz decisao tipada e o keyword fallback legado fica desabilitado por padrao | Remover heuristicas textuais residuais da decisao primaria e limitar matching a grounding pos-intencao |
| Suite versionada de excelencia | `ja-suportado-mal-nomeado-ou-mal-materializado` | Corpus, schema e runner internos agora existem e validam recurso, operacao, terminalidade, mensagem, seguranca e latencia | Expandir cobertura e ligar o perfil integral ao gate de fase sem transforma-lo em contrato HTTP publico |
| Politica de modelo/provider por tarefa | `lacuna-real-de-contrato` | O provider e configuravel, mas OpenAI ainda usa default global `gpt-4o-mini` e heuristicas por nome de modelo | Definir politica canonica de capability, tier, snapshot, custo, latencia e fallback |

O ensaio real refinou ainda duas classificacoes de aderencia:

| Necessidade | Aderencia refinada | Evidencia | Proximo ajuste |
| --- | --- | --- | --- |
| Escolher `funcionarios` para formulario/tabela | `ja-suportado-mal-nomeado-ou-mal-materializado` | `domainDiscovery` ja publica `human-resources.funcionarios`, campos e surfaces; a perda ocorria entre o foco authorado pela LLM e o catalogo scoped | Reconciliar o foco pos-intencao com o `resourceKey` canonico e exigir schema live quando a projection de `api_metadata` estiver ausente |
| Responder orientacao sem tool de recurso | `suportado-parcialmente` | O resultado terminal agora e correto, mas o pre-intent planner ainda pode executar search desnecessario | Usar a evidencia semantica estruturada da oportunidade para planejar apenas contexto de plataforma, sem keyword shortcut |

## Diagnostico estrutural

### 1. A autoconsciencia existe, mas nao chega sempre ao usuario

`page-builder-system-prompt.v1.md` ja instrui a LLM a tratar perguntas sobre o
que Praxis faz como orientacao consultiva. `AgenticAuthoringContextBundle` ja
publica `platformGuide`, catalogo de componentes authoraveis e capabilities.
O shell ja sabe apresentar recomendacoes iniciais.

A inconsistencia nasce quando hosts diferentes projetam subconjuntos distintos
desse contexto, usam fluxos de turno diferentes ou convertem uma resposta
consultiva em preview/handoff inadequado. A correcao e tornar a mesma
oportunidade semantica visivel em todos os pontos de entrada.

### 2. O caminho canonico ainda mistura decisao da LLM e decisao textual local

O `legacy-keyword-fallback-enabled` esta desabilitado por padrao, o que e
correto. Mesmo assim, `AgenticAuthoringIntentResolverService`,
`AgenticAuthoringTurnEngine`, `AiOrchestratorService` e policies auxiliares
ainda possuem funcoes que classificam prompts consultivos, dashboards,
formularios e outros pedidos por `contains`, listas textuais e normalizacao.

Esses sinais podem continuar existindo para telemetria, grounding ou migracao,
mas nao podem escolher a intencao primaria, evitar a chamada semantica ou
reescrever a decisao retornada pela LLM. Quando faltar uma tool ou operacao, o
runtime deve esclarecer ou falhar fechado; nao deve compensar com uma nova
lista de palavras.

### 3. A integracao OpenAI contorna a camada que deveria dar consistencia

O projeto usa Spring Boot `3.5.9` e Spring AI `1.1.1`, mas
`SpringAiOpenAiService` contorna o `ChatClient` por causa de uma limitacao
historica de `extra_body`. O servico monta manualmente requests para
`/v1/chat/completions`, interpreta SSE e envia `response_format=json_object`.
Assim, parte do lifecycle de tools, advisors, structured output,
observabilidade e portabilidade declarada nas dependencias nao governa o
caminho OpenAI real.

As linhas estaveis atuais exigem duas pistas:

- pista compativel: provar Spring AI `1.1.8` e o patch corrente de Spring Boot
  3.5.x, removendo workarounds que deixaram de ser necessarios;
- pista alvo: avaliar Spring AI `2.0.x`, que usa o SDK oficial `openai-java`,
  junto da migracao necessaria para Spring Boot 4.0/4.1.

Nao deve haver troca cega de versao ou modelo. Cada pista precisa passar pelo
mesmo corpus de consistencia, custo, latencia, streaming, tool calling e
structured output.

### 4. Testes extensos ainda nao formam um gate de produto

Ha suites unitarias e matrizes reais valiosas, mas elas validam slices e
investigacoes diferentes. Para o mercado, o baseline precisa responder uma
pergunta simples: "as jornadas basicas passaram repetidamente hoje, com esta
versao, este provider e este modelo?". Isso requer corpus unico, resultado
comparavel e criterio bloqueante.

## Principios de excelencia

1. Consistencia antes de amplitude: nenhuma nova capacidade compensa falha no
   pedido basico.
2. Uma identidade de assistente: os componentes fornecem contexto; nao criam
   bots ou semanticas concorrentes.
3. LLM decide semantica; codigo determinista valida, autoriza, ranqueia alvos
   depois da intencao e materializa.
4. Contexto progressivo: sempre enviar identidade, superficie, selecao,
   capabilities e guia compacto; recuperar detalhes por tools quando preciso.
5. Structured output estrito: decisao invalida nao entra silenciosamente no
   runtime.
6. Fail-closed com recuperacao humana: esclarecer, tentar repair controlado ou
   explicar a limitacao; nunca inventar recurso, componente ou acao.
7. Observabilidade de produto: medir assertividade, terminalidade, latencia,
   custo, retries, clarificacoes e aplicacoes, nao apenas status HTTP.
8. Prova real: mocks protegem unidade; gates de excelencia usam provider, API,
   stream e navegador reais em um ambiente controlado.

## P0 - Certificacao do basico

### P0.1 Corpus canonico de jornadas

Criar um corpus versionado com, no minimo, estas familias:

1. Descoberta da plataforma
   - "O que eu posso fazer aqui?"
   - "Como voce pode me ajudar nesta tela?"
   - "Qual seria um bom proximo passo?"
2. Criacao aberta
   - "Crie uma tela bonita para acompanhar funcionarios."
   - "Monte um formulario de funcionarios."
   - "Quero algo para consultar informacoes dos empregados."
3. Componentes explicitos
   - criar tabela, grafico, formulario e filtro;
   - combinar grafico e tabela conectados.
4. Refinamento multi-turn
   - adicionar/remover campo;
   - alterar label, filtro, renderer ou layout;
   - voltar ao pedido anterior sem perder o alvo.
5. Ambiguidade e erro humano
   - typos, transcricao ruim, termos leigos, ordem invertida e pedido composto.
6. Limites e governanca
   - pedir regra de acesso/negocio;
   - pedir capacidade inexistente;
   - cancelar, corrigir e retomar.

Cada caso deve declarar contexto inicial, intencao semantica esperada,
evidencias obrigatorias/proibidas, resultado visual esperado, necessidade de
clarificacao, terminal esperado e invariantes de seguranca. Variacoes em
portugues e ingles nao devem ser tratadas como novos roteadores textuais; sao
amostras para medir a decisao semantica.

### P0.2 Gate bloqueante de consistencia

O baseline de release/demo deve exigir:

- 100% dos casos `must-pass` em tres execucoes consecutivas;
- pelo menos 95% do corpus estendido sem regressao estatisticamente relevante;
- zero recurso, campo, componente, endpoint ou capability inventado;
- zero retorno a keyword routing como decisor primario;
- zero sessao presa em `processing`, `clarifying`, `review` ou estado sem acao;
- 100% das mutacoes com preview/review e contrato de apply valido;
- primeira mensagem de progresso em ate 2 s e heartbeat legivel durante espera;
- alvo inicial de P95 de 12 s para orientacao sem tool e 45 s para authoring
  com retrieval/preview, sempre com timeout terminal de produto;
- registro de modelo, snapshot/alias, provider, tokens, custo estimado,
  latencias por fase, tools e retries.

Os limites de latencia devem ser recalibrados depois do primeiro baseline, mas
nao podem ser removidos do gate.

### P0.3 Contrato comportamental para "o que posso fazer aqui?"

Essa pergunta deve:

- ser respondida como orientacao, sem gerar patch ou preview vazio;
- mencionar o contexto atual em linguagem humana;
- explicar de forma curta que Praxis pode criar e ajustar componentes
  governados a partir de linguagem natural;
- oferecer exemplos realmente disponiveis no host, como formulario, tabela,
  grafico, filtro, pagina ou regra governada;
- expor de tres a cinco recommended intents contextuais;
- propor um proximo passo acionavel;
- nunca listar capability ausente nem despejar nomes internos de contratos.

O backend e a UI devem usar `platformGuide`, `authorableComponents`,
`componentCapabilities`, contexto de dominio e runtime ja existentes. Nao deve
nascer um catalogo paralelo de copy no Page Builder.

### P0.4 Contrato comportamental para "crie um formulario de funcionarios"

A jornada deve:

1. resolver semanticamente criacao de formulario/tela;
2. buscar o recurso governado de funcionarios quando ele nao estiver no
   contexto compacto;
3. confirmar schema, capabilities e operacoes permitidas;
4. montar uma preview editorial coerente, com campos e layout justificaveis;
5. explicar a fonte e o que foi proposto;
6. permitir refinamento, apply e cancelamento;
7. provar readback/materializacao no runtime consumidor.

Termos como empregado, funcionario, colaborador ou erro de digitacao entram no
corpus; nao viram uma matriz de sinonimos decisoria.

## P0 - Um unico caminho semantico e terminal

### P0.5 Convergir para o turn engine canonico

- Fazer `/api/praxis/config/ai/authoring/turn/**` ser o unico caminho novo de
  conversa, streaming, tool loop, preview, clarificacao, repair e terminal.
- Inventariar e retirar `/patch`, `/patch/stream` e orchestration local dos
  consumidores restantes.
- Extrair do `AgenticAuthoringIntentResolverService` responsabilidades de
  contexto, decisao, grounding, validation e materialization para que cada
  fase tenha contrato e telemetria proprios.
- Bloquear por teste qualquer `contains`/regex que escolha operacao primaria ou
  evite a resolucao semantica.
- Manter matching textual apenas depois de `operationKind`, `artifactKind` e
  decisao canonica terem sido resolvidos pela LLM/tooling governado.

### P0.6 State machine e recuperacao

- Formalizar estados e transicoes aceitas de um turno.
- Todo erro, cancelamento, timeout ou desconexao deve produzir terminal
  retomavel.
- Retry deve preservar idempotencia, `turnId`, evidencia e budget.
- SSE replay nao pode duplicar apply, tool side effect ou mensagem final.
- A UI deve sempre oferecer a proxima acao segura: responder clarificacao,
  revisar, salvar, tentar novamente, cancelar ou abrir diagnostico.

## P1 - Modernizacao de SDK, provider e modelos

### P1.1 Pista compativel Spring Boot 3.5

- Atualizar o baseline Spring AI de `1.1.1` para `1.1.8` em branch/slice
  dedicado.
- Avaliar o patch Spring Boot 3.5.x corrente e CVEs/dependencias transientes.
- Reproduzir o bug historico de `extra_body`; remover o bypass se a versao
  atual resolver o problema.
- Comparar `ChatClient`/advisors contra o caminho HTTP manual usando o corpus
  canonico.
- Nao manter dois caminhos permanentes. Como a plataforma esta em beta, a
  pista vencedora substitui a antiga no mesmo ciclo.

### P1.2 Pista alvo Spring AI 2.0 e Spring Boot 4

- Produzir matriz de compatibilidade Java 21, Boot 4, Jackson 3, JPA,
  observabilidade, PGVector, providers e starters Praxis.
- Provar a migracao `openai-java` usada pelo Spring AI 2.0.
- Revisar mudancas de advisors, tool calling, MCP e artefatos renomeados.
- Executar o mesmo corpus e comparar assertividade, custo, latencia e
  confiabilidade antes de promover.

Essa pista e arquitetural e nao deve ser misturada com uma correcao funcional
urgente do assistente.

### P1.3 Responses, structured output e tool lifecycle

- Definir uma fronteira `AiProvider` por capabilities, nao por `if` de nome de
  modelo.
- Usar structured output nativo/JSON Schema estrito para decisoes e planos.
- Validar schema e fazer repair recursivo com budget explicito.
- Delegar lifecycle de tools a uma abstracao que registre chamada, argumentos
  seguros, resultado sanitizado, duracao, falha e budget.
- Avaliar Responses API para OpenAI quando ela trouxer continuidade, tools ou
  streaming que a abstracao portavel nao exponha; a decisao deve ficar na
  adapter OpenAI, sem contaminar o dominio de authoring.
- Remover parser SSE e montagem manual de payload quando o SDK/framework
  comprovado cobrir o contrato.

### P1.4 Politica de modelos

- Substituir o default global por uma politica de tarefas:
  - modelo rapido/economico para classificacao, curation e respostas simples;
  - modelo equilibrado para planning e structured output;
  - modelo de maior raciocinio apenas para composicao complexa/repair dificil.
- Manter snapshots fixos no corpus de regressao e aliases apenas em canary.
- Promover um modelo por evidencia, nunca apenas porque e mais recente.
- Medir custo por jornada concluida, nao somente preco por token.
- Definir fallback por capability e erro recuperavel; fallback nao pode mudar
  silenciosamente semantica ou contrato de output.

## P1 - Contexto, componentes e UX uniforme

### P1.5 Pacote minimo de contexto assistivel

Todo host deve fornecer, com redaction e limite de tamanho:

- identidade da sessao, rota e tenant;
- componente/superficie e alvo selecionado;
- estado de pagina/componente e pending decision;
- `platformGuide`, componentes authoraveis e capabilities relevantes;
- schema/resource/surface/action em escopo;
- manifests e operacoes delegaveis;
- historico resumido e clarificacao pendente;
- limites de governanca e materializacao.

Detalhes devem ser recuperados por tools semanticamente planejadas. Nao enviar
o dominio inteiro a cada turno nem confiar em contexto escondido do frontend.

### P1.6 Ordem de certificacao dos consumidores

1. Page Builder: descoberta, composicao de pagina, formulario de funcionarios,
   tabela + grafico e refinamento multi-turn.
2. Table: descoberta contextual, filtros, colunas, acoes, exportacao e
   diagnostico.
3. Dynamic Form: criacao, campo/layout, validacao local e handoff de regra
   governada.
4. Charts e List/CRUD: authoring e analytics com a mesma shell/orchestration.
5. Demais hosts: migracao do legado somente depois do gate dos tres primeiros.

Um componente so pode declarar suporte ao assistente quando publicar context
snapshot, manifest/capabilities, recommended intents e bateria de certificacao.

### P1.7 UX de confianca

- Empty state deve dizer onde o usuario esta e o que pode pedir.
- Recommended intents devem nascer de oportunidades canonicas e estado atual.
- Progresso deve ser humano: entendendo pedido, consultando dominio, montando
  proposta e validando; detalhes tecnicos ficam no diagnostico.
- Preview deve separar claramente mudanca local, decisao governada e efeito
  runtime.
- Erros devem explicar o que faltou e oferecer recuperacao concreta.
- Acessibilidade, teclado, responsividade e foco devem ser gates, nao polimento
  final.

## P1 - Observabilidade e qualidade continua

- Criar dashboard por versao/provider/modelo com pass rate, no-answer,
  clarificacao, repair, tool failure, hallucination, cancelamento e stuck turn.
- Correlacionar frontend, turn engine, provider e tools por `turnId` e
  `observationId`, com redaction por padrao.
- Guardar prompt/conteudo sensivel somente sob politica explicita; metricas e
  traces devem funcionar sem payload bruto.
- Executar corpus deterministico em PRs relevantes com mocks/fixtures e corpus
  real controlado como gate de fase/release, respeitando custo.
- Toda regressao encontrada em demo ou uso real deve virar caso reduzido no
  corpus antes da correcao ser considerada concluida.

## P2 - Prontidao de mercado e investidores

### Golden journeys

Preparar tres jornadas sem mocks e com reset deterministico:

1. Usuario novo pergunta o que pode fazer, escolhe uma sugestao e cria uma
   tela de funcionarios.
2. Usuario refina a tela com tabela, grafico e filtro, revisa e salva.
3. Usuario pede uma regra de negocio; Praxis explica a diferenca, cria handoff
   governado e prova materializacao/enforcement.

Cada jornada precisa passar tres vezes consecutivas antes de uma apresentacao,
com ambiente, modelo, dados e script registrados. Contingencia de provider
deve explicar indisponibilidade; nao pode simular sucesso.

### Evidencia de produto

- painel de qualidade com historico do corpus;
- comparacao antes/depois de assertividade, tempo e custo;
- trilha de auditoria de uma decisao ate a materializacao;
- roteiro curto para publico nao tecnico e diagnostico aprofundado para due
  diligence tecnica;
- politica de privacidade, autorizacao de tools, prompt injection, budgets,
  rate limit e isolamento por tenant documentada e testada.

## Backlog priorizado

| Prioridade | Entrega | Fonte canonica | Aceite principal |
| --- | --- | --- | --- |
| P0 | Corpus `assistant-basic-consistency` e runner comparavel | Config Starter + tools E2E | Casos, expectativas e relatorio versionados |
| P0 | Vertical slice "o que posso fazer aqui?" | Context bundle + turn engine + `@praxisui/ai` | Resposta contextual e recommended intents em Page Builder, sem patch |
| P0 | Vertical slice formulario de funcionarios | Turn engine + Page Builder + quickstart | 3 execucoes completas, preview/apply/readback verdes |
| P0 | Gate de terminalidade e replay | Turn engine + shell | Zero stuck/duplicated side effect no corpus |
| P0 | Auditoria/remocao de decisores textuais residuais | Config Starter | Testes provam decisao LLM-first; matching apenas pos-intencao |
| P0 | Migracao dos callers restantes para `/turn/**` | Config Starter + Angular | Nenhum fluxo novo usa patch endpoint legado |
| P1 | Spring AI 1.1.8 compatibility slice | Config Starter | Corpus, build e smokes iguais ou melhores; bypass removido se comprovado |
| P1 | Policy de provider/model capabilities | Config Starter | Selecao por tarefa/capability, snapshot e custo observavel |
| P1 | Structured output/tool lifecycle canonico | Config Starter | Schema estrito, repair limitado, tool trace e sem parser manual duplicado |
| P1 | Certificacao Table e Dynamic Form | Angular + Config Starter | Mesmos gates do Page Builder |
| P1 | Dashboard de qualidade | Config Starter/observabilidade | Regressao identificavel por versao/modelo/caso |
| P2 | Spike Spring AI 2.0 + Boot 4 | Branch arquitetural dedicada | Matriz de compatibilidade e decisao escrita de promocao/adiamento |
| P2 | Golden demo e investor readiness | Quickstart + playground/landing | 3 jornadas reais consecutivas e roteiro reproduzivel |
| P2 | Security/tenant/cost SLO hardening | Plataforma | Threat model e gates operacionais fechados |

## Gates por fase

### Gate A - Consistencia basica

- corpus e metricas definidos;
- as duas verticais P0 passam;
- nenhum stuck turn ou alucinacao de contrato;
- Page Builder apresenta proximo passo sempre acionavel.

### Gate B - Runtime canonico

- callers prioritarios usam `/turn/**`;
- decisao primaria e semanticamente authorada;
- state machine, replay, cancelamento e timeout estao certificados.

### Gate C - SDK e modelo

- versao compativel atualizada;
- structured output, tools e streaming passam no corpus;
- policy de modelo/custo observavel esta ativa;
- caminho manual antigo foi removido, ou existe decisao arquitetural explicita
  e temporaria com criterio de remocao.

### Gate D - Produto apresentavel

- Page Builder, Table e Dynamic Form certificados;
- golden journeys passam tres vezes;
- UX, acessibilidade, seguranca, auditoria e dashboard de qualidade estao
  demonstraveis.

## Validacao minima por tipo de slice

- Backend sem contrato publico: suite focal do service/turn engine alterado e
  runner de corpus relevante.
- Contrato AI/publico: suite focal, OpenAPI/bindings/corpus derivados, build de
  `@praxisui/ai` e de um consumidor direto.
- Angular: teste focal de shell/orchestrator/host, build da lib e E2E browser da
  jornada afetada.
- Provider/SDK/modelo: testes contratuais com fake provider, smoke OpenAI real,
  streaming/cancel/replay e comparacao de metricas.
- Release/demo: quickstart HTTP real, navegador real, tres execucoes golden e
  registro do ambiente.

## Artefatos derivados

O terceiro slice atualizou o schema, corpus e runner internos de consistencia e
adicionou o executor transacional local. Nao houve mudanca de endpoint, DTO,
OpenAPI ou public API; por isso bindings, landing page e corpus HTTP nao precisam
de sincronizacao neste corte. Quando os proximos slices alterarem contratos
publicos, revisar no mesmo ciclo:

- `docs/ai/contracts/**` e OpenAPI do Config Starter;
- bindings e public APIs de `@praxisui/ai`;
- manifests/recipes de Page Builder, Table, Form e Charts;
- `praxisui-http-examples/examples.manifest.json` e `LLM_SURFACE.md`;
- playgrounds e documentacao oficial da landing page.

## Proximo slice recomendado

`P0.1 + P0.3` possuem primeiro corte, o grounding de preview de `P0.4` passou
na vertical 3x e a mesma jornada agora passou apply/readback/replay/cleanup 3x.
O proximo slice recomendado e fechar o gate P0 integral, nesta ordem:

1. executar os seis `must-pass` tres vezes consecutivas no mesmo gate, incluindo
   as tres perguntas consultivas e as tres jornadas de criacao;
2. tornar falha bloqueante qualquer divergencia de terminal, recurso, operacao,
   preview ou prova transacional e publicar o resultado comparavel por
   provider/modelo;
3. adicionar variacoes de linguagem, erros humanos, refinamento e recuperacao
   ao perfil `extended`, mantendo o baseline `must-pass` bloqueante;
4. otimizar a rota consultiva para evitar planning/discovery de recursos e
   fechar o alvo P95 de 12 s;
5. corrigir os diagnostics residuais de provenance para que
   `domain-discovery-resource-focus` apareca como grounding conhecido, sem
   alterar a decisao funcional;
6. somente com o gate P0 integral verde iniciar a modernizacao de Spring AI,
   SDK OpenAI e politica de modelos, comparando o novo caminho com o mesmo
   corpus.

## Referencias oficiais para a frente de SDK

- OpenAI Models: <https://developers.openai.com/api/docs/models>
- Spring AI Getting Started: <https://docs.spring.io/spring-ai/reference/getting-started.html>
- Spring AI Upgrade Notes: <https://docs.spring.io/spring-ai/reference/upgrade-notes.html>
- Spring AI Tool Calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI ChatClient: <https://docs.spring.io/spring-ai/reference/api/chatclient.html>
- Spring AI Structured Output: <https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html>
- Spring AI Observability: <https://docs.spring.io/spring-ai/reference/observability/index.html>
