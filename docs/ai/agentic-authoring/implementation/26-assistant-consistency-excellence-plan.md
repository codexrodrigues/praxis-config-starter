# Plano de Excelencia e Consistencia do Assistente Praxis

Data: 2026-07-15

Status: Gate A verde nos perfis `must-pass` e `extended`; jornada multi-turn
progressiva consistente e gates de latencia, tokens e custo ativos

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

O quarto slice P0 removeu duas causas estruturais de inconsistencia no basico:

- oportunidades estruturadas com `semanticScope=platform-capabilities`
  recebem confirmacao semantica compacta da LLM, sem keyword routing, sem tool
  de recurso e sem eventos persistidos redundantes;
- uma resposta de orientacao confirmada pela LLM nao e mais transformada em
  clarificacao obrigatoria apenas porque termina com uma pergunta retorica;
- a criacao de formulario nao faz uma segunda LLM inventar um
  `MinimalFormPlan` a partir de uma URL de schema que ela nao leu;
- depois que a LLM resolve `create/form/create_artifact` e o recurso, o plano e
  materializado deterministicamente pelo request schema canonico de
  `/schemas/filtered`;
- labels, `controlType`, obrigatoriedade, `schemaPointer`, defaults e option
  sources passam a vir da fonte canonica; schema indisponivel falha fechado em
  vez de produzir campos plausiveis, mas nao confirmados;
- a materializacao real de funcionarios passou de campos inventados como
  `nome` e `cargo` para os 12 campos publicados pelo contrato, incluindo
  `nomeCompleto`, `salario`, `cargoId`, `departamentoId` e seus option sources.

O quinto slice P0 eliminou a cauda operacional do planner e mais um decisor
textual residual sem criar contrato novo:

- o timeout deixou de ser budget por tentativa e passou a ser budget terminal
  unico da fase de pre-intent planning, com teto padrao de 12 s;
- timeout e terminal para esse planner opcional; rate limit, capacity,
  transport e server error so recebem uma segunda tentativa quando ainda
  restam pelo menos dois segundos dentro do mesmo budget;
- cada retry recebe apenas o tempo restante da fase, impedindo que duas
  chamadas sequenciais multipliquem silenciosamente a latencia do turno;
- foi removido o fallback que procurava verbos e nomes de componentes no texto
  para executar discovery antes da intencao; se o planner nao produzir plano,
  a LLM resolve primeiro a intencao e o discovery governado continua no fluxo
  pos-intencao;
- falha ou timeout do planner permanece diagnosticado e fail-safe, sem decidir
  intencao, inventar recurso ou promover uma heuristica textual substituta.

O sexto slice P0 iniciou a cobertura `extended` pelo refinamento de uma pagina
ja materializada, reutilizando contratos existentes:

- o corpus passou a representar uma tabela de funcionarios existente com
  `currentPage` e `selectedWidgetKey`, sem criar um formato paralelo de pagina;
- a LLM continua responsavel por resolver a intencao primaria como
  `modify/table/column.add` e por preservar o recurso/widget selecionados;
- depois dessa decisao, o preview busca `/schemas/filtered` e publica
  `schemaFields` governados para o materializador generico;
- o campo solicitado e ranqueado apenas dentro desse catalogo canonico, com
  normalizacao de variacoes como `e-mail` e `Email`; esse matching nao decide a
  intencao nem pode inventar campo ausente no schema;
- o materializador deriva a operacao `column.add` ja definida no manifesto de
  `praxis-table`, preserva as colunas existentes e falha fechado quando nao ha
  alvo de schema unico;
- composicoes intermediarias continuam como `praxis.ui-composition-plan`,
  enquanto paginas runtime continuam usando o compiled page patch existente;
- mensagens tecnicas residuais de busca de evidencia e reparo foram substituidas
  por progresso humano em portugues, sem alterar fases ou diagnostics internos.

O setimo slice P0 transformou esse refinamento em uma prova multi-turn e
corrigiu a identidade canonica da conversa:

- o inventario classificou replay, cancelamento e idempotencia como ja
  suportados, mas lineage multi-turn como `suportado-parcialmente`;
- `sessionId` ja existia no contrato HTTP e no cliente Angular, mas o stream
  backend nao o repassava a `AiThreadService` e forcava `mode=new` em todos os
  turnos;
- UUIDs validos agora materializam `mode=continue` no thread existente;
  session ausente ou invalida continua abrindo uma conversa nova;
- depois da resolucao, o request efetivo recebe o `threadId` canonico como
  identidade de sessao, evitando que decisoes novas persistam apenas o rotulo
  local usado antes do primeiro start;
- o corpus interno ganhou jornadas ordenadas sem criar endpoint ou DTO novo;
  cada passo pode derivar `currentPage` da preview anterior e acumular o
  historico conversacional;
- o runner verifica mesmo `threadId`, novo `turnId`, decisao ativa herdada,
  colunas obrigatorias e ausencia de duplicatas;
- uma falha anterior agora bloqueia somente os passos dependentes e aparece no
  relatorio completo, em vez de encerrar o gate sem diagnostico.

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
- o gate focal final de formulario passou 3/3 com mediana terminal de 29,437 s,
  tres materializacoes schema-grounded e tres provas transacionais completas;
- depois da extensao transacional, a jornada de formulario passou novamente
  3/3 no gate de release com OpenAI e Neon reais: recurso e submit exatos em
  `/api/human-resources/funcionarios`, versao `1 -> 2`, um widget antes e depois
  do replay, tres rejeicoes de `ETag` obsoleto e tres cleanups confirmados;
- o gate integral final executou os seis casos `must-pass` tres vezes no mesmo
  corte e passou 18/18, com 100% de acuracia, zero terminal incorreto, 3/3
  transacoes e mediana terminal global de 17,5375 s;
- as nove orientacoes de plataforma terminaram entre 8,386 s e 9,433 s, abaixo
  do SLO bloqueante de 12 s, sempre sem recurso, preview ou apply e com tres
  proximas acoes;
- formulario, tela aberta e tabela terminaram corretamente nas nove execucoes;
  os formularios ficaram em 28,742 s, 30,191 s e 42,798 s, todos abaixo do
  limite de authoring de 45 s;
- o baseline anterior revelou uma cauda de 20,943 s no pre-intent planner por
  timeout seguido de retry sequencial;
- depois do budget terminal, um gate focal adicional de formulario passou 6/6,
  com seis provas transacionais, planners entre 6,324 s e 9,639 s e tempos
  terminais entre 28,632 s e 33,090 s;
- o gate integral posterior passou novamente 18/18; os nove planners de
  authoring ficaram entre 6,180 s e 8,340 s, sem timeout/retry sequencial, e as
  jornadas de authoring terminaram entre 26,927 s e 31,457 s;
- as nove orientacoes desse corte terminaram entre 8,670 s e 10,171 s, ainda
  abaixo do SLO de 12 s, e a mediana terminal global foi 18,549 s;
- todas as 18 jornadas emitiram primeiro feedback imediatamente segundo a
  resolucao do runner, e nenhuma ficou presa em estado intermediario.
- o primeiro probe real do refinamento revelou a lacuna com
  `canApply=false` e
  `intent-resolution-artifact-requires-ui-composition-plan`; a evidencia foi
  preservada como baseline negativo antes da correcao;
- apos a correcao, `employee-table-add-email-column-pt` passou com OpenAI,
  quickstart e Neon reais: `modify/table/column.add`, recurso
  `/api/human-resources/funcionarios`, coluna `email` schema-grounded, demais
  colunas preservadas, preview valido e `canApply=true`;
- o slice `extended` completo atual passou 3/3: descoberta de plataforma em
  ingles, formulario com erro humano e refinamento da tabela existente, com
  100% de acuracia, mediana terminal de 28,975 s e zero mensagem tecnica na
  auditoria de apresentacao.
- a prova direta posterior ao reempacotamento confirmou continuidade real:
  o segundo start preservou `threadId=9ec46509-723e-3eae-87f9-dc06feadb544`,
  criou outro `turnId` e o cancelamento terminou nesse mesmo par canonico;
- a jornada completa de colunas revelou a inconsistencia que o novo gate deve
  impedir: em duas amostras consecutivas com `gpt-4.1-mini`, "adicionar coluna
  e-mail" foi resolvido como `set_column_order`, gerou preview invalida e
  `canApply=false`; cada rodada terminou em 0/2 porque o segundo passo depende
  corretamente da materializacao anterior;
- o resultado negativo permanece como evidencia: o transporte multi-turn esta
  corrigido, mas a selecao semantica da operacao basica ainda nao tem a
  consistencia necessaria para declarar a jornada verde.

## Classificacao e mapa de impacto

- Classificacao do plano: `arquitetural`.
- Classificacao do segundo slice: `transversal`, com comportamento interno de
  AI authoring e prova operacional; nenhum endpoint, DTO ou tipo publico novo.
- Classificacao do terceiro slice: `transversal`, restrito ao contrato interno
  de avaliacao e ao runner operacional; reutiliza apply, configuracao, ETag e
  delete publicos sem alterar suas superficies.
- Classificacao do quarto slice: `transversal`, restrito a orchestration,
  resolucao semantica e materializacao interna de formulario; nenhum endpoint,
  DTO, OpenAPI ou tipo publico novo.
- Classificacao do quinto slice: `transversal`, restrito a budget/retry interno
  do planner e retirada de fallback textual do turn engine; nenhum endpoint,
  DTO, evento SSE, OpenAPI ou tipo publico novo.
- Classificacao do sexto slice: `transversal`, restrito a materializacao interna
  do Page Builder, schema grounding, corpus e UX de progresso; reutiliza
  `UiCompositionPlan`, `column.add` e `/schemas/filtered` sem alterar endpoint,
  DTO, evento SSE ou public API.
- Classificacao do setimo slice: `transversal`, com correcao interna de
  materializacao de `sessionId` e extensao do corpus/runner; cumpre o contrato
  HTTP existente sem alterar endpoint, DTO, evento SSE, OpenAPI ou public API.
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
| Criar formulario/tela de funcionarios | `ja-suportado-mal-nomeado-ou-mal-materializado` | O gate final passou 9/9 criacoes; formulario agora projeta os 12 campos e option sources de `/schemas/filtered`, com apply/readback/replay/cleanup 3/3 | Acrescentar variacoes linguisticas, refinamento, cancelamento e recuperacao na mesma jornada |
| Criar tabela, grafico e filtros | `suportado-parcialmente` | Manifests, handlers e validadores existem, mas a cobertura de hosts e o caminho de turno nao sao uniformes | Convergir os consumidores para o mesmo turn engine e os mesmos gates |
| Continuidade e estados terminais | `suportado-parcialmente` | SSE, replay, heartbeat, registry e shell existem | Definir state machine observavel, timeout, retry seguro e zero sessoes presas |
| Intencao semantica consistente | `suportado-parcialmente` | A LLM ja produz decisao tipada e o keyword fallback legado fica desabilitado por padrao | Remover heuristicas textuais residuais da decisao primaria e limitar matching a grounding pos-intencao |
| Suite versionada de excelencia | `ja-suportado-mal-nomeado-ou-mal-materializado` | Corpus, schema e runner internos agora existem e validam recurso, operacao, terminalidade, mensagem, seguranca e latencia | Expandir cobertura e ligar o perfil integral ao gate de fase sem transforma-lo em contrato HTTP publico |
| Politica de modelo/provider por tarefa | `lacuna-real-de-contrato` | O provider e configuravel, mas OpenAI ainda usa default global `gpt-4o-mini` e heuristicas por nome de modelo | Definir politica canonica de capability, tier, snapshot, custo, latencia e fallback |
| Transporte OpenAI moderno | `lacuna-real-de-contrato` | A linha compativel foi atualizada e o bug de `extra_body` foi provado como corrigido, mas o adapter real ainda usa Chat Completions HTTP manual | Criar apenas o contrato interno do adapter, certificar SDK oficial/Responses contra o corpus e substituir o caminho manual sem criar trilhas paralelas ou DTO HTTP publico |

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

O projeto passou a usar Spring Boot `3.5.15` e Spring AI `1.1.8`, mas
`SpringAiOpenAiService` contorna o `ChatClient` por causa de uma limitacao
historica de `extra_body`. O servico monta manualmente requests para
`/v1/chat/completions`, interpreta SSE e envia `response_format=json_object`.
Assim, parte do lifecycle de tools, advisors, structured output,
observabilidade e portabilidade declarada nas dependencias nao governa o
caminho OpenAI real.

As linhas estaveis atuais exigem duas pistas:

- pista compativel: baseline Spring AI `1.1.8` e Spring Boot `3.5.15` provado;
  o teste black-box confirma que `extra_body` e achatado corretamente, restando
  certificar paridade de streaming, cancelamento, structured output e telemetria
  antes de remover o workaround;
- pista alvo: avaliar Spring AI `2.0.x`, que usa o SDK oficial `openai-java`,
  junto da migracao necessaria para Spring Boot 4.0/4.1.

Nao deve haver troca cega de versao ou modelo. Cada pista precisa passar pelo
mesmo corpus de consistencia, custo, latencia, streaming, tool calling e
structured output.

### 4. O gate basico existe; cobertura e historico ainda precisam crescer

O corpus e o runner agora respondem de forma comparavel se as jornadas basicas
passaram repetidamente com uma versao, provider e modelo conhecidos. O corte
`must-pass` fechou 18/18. A lacuna seguinte e expandir o perfil `extended`,
reter historico por versao/modelo e publicar tendencias de P95, custo, retries,
clarificacoes e falhas, sem transformar GitHub Actions em loop de desenvolvimento.

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

- [x] Formalizar estados e transicoes aceitas de um turno, distinguindo reserva
  transacional e terminalidade observavel do event log.
- [x] Certificar erro, cancelamento e timeout como terminais persistidos e
  retomaveis por replay; desconexao nao cria um estado terminal local.
- [x] Certificar retomada de transporte preservando idempotencia, `turnId`,
  evidencia e budget, sem reexecutar processamento.
- [x] Certificar que SSE replay e corridas terminais nao duplicam apply, tool
  side effect ou mensagem final.
- A UI deve sempre oferecer a proxima acao segura: responder clarificacao,
  revisar, salvar, tentar novamente, cancelar ou abrir diagnostico.

## P1 - Modernizacao de SDK, provider e modelos

### P1.1 Pista compativel Spring Boot 3.5

- [x] Atualizar o baseline Spring AI de `1.1.1` para `1.1.8` em branch/slice
  dedicado.
- [x] Atualizar o patch Spring Boot compativel de `3.5.9` para `3.5.15`.
- [x] Reproduzir o bug historico de `extra_body` em teste black-box; Spring AI
  1.1.8 achata a extensao corretamente e elimina a justificativa original do
  bypass.
- [ ] Remover o bypass depois de provar paridade de streaming, cancelamento,
  structured output e telemetria.
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

Status em 2026-07-15: **verde no perfil `must-pass`, no refinamento isolado
`extended`, na jornada multi-turn progressiva de Table e na matriz
transacional P0.6 do authoring turn**.

- corpus e metricas definidos: concluido;
- as duas verticais P0 passam: 18/18 no gate integral;
- nenhum stuck turn ou alucinacao de contrato: concluido no corpus basico;
- Page Builder apresenta proximo passo sempre acionavel: 9/9 orientacoes com
  tres quick replies;
- o primeiro slice `extended` passou 3/3 e cobre idioma alternativo, erro
  humano e refinamento schema-grounded de pagina existente;
- `employee-table-progressive-columns-pt` passou 6/6 turnos reais em tres
  repeticoes consecutivas com OpenAI `gpt-4.1-mini`: `column.add` foi resolvido
  em todos os turnos, `email` foi preservado, `salario` foi acrescentado e nao
  houve coluna duplicada, timeout ou clarificacao indevida;
- o gate final registrou 100% de assertividade e mediana terminal de 33,3385 s,
  contra 33% e mediana de aproximadamente 50 s antes deste slice;
- a matriz P0.6 executa 76 testes sem provider externo e certifica retomada,
  replay SSE, cancelamento, timeout, schema indisponivel fail-closed,
  ownership, fronteira transacional e terminal unico no mesmo gate local;
- lineage de apply certificado: a materializacao exige `streamId` e
  `resultEventId`, revalida ownership e igualdade exata da decisao/preview
  persistidos e grava `stream/thread/turn/event/decision` nas tags;
- telemetria canonica de provider, usage, custo por snapshot e gates de
  eficiencia: concluidos;
- repeticao final de 2026-07-16: `must-pass` 18/18 e `extended` 12/12;
- a correcao da variancia de Table reduziu o P95 estendido de 68,512 s para
  39,481 s, tokens totais de 66.098 para 30.437 e custo estimado de 31.718 para
  13.721 micros de USD;
- proxima pendencia do Gate A: nenhuma; evolucoes de shell, SDK e modelo seguem
  para os Gates B, C e D.

### Gate B - Runtime canonico

- callers prioritarios usam `/turn/**`;
- decisao primaria e semanticamente authorada;
- state machine, replay, cancelamento e timeout estao certificados.

### Gate C - SDK e modelo

- [x] versao compativel atualizada;
- [x] adapter OpenAI usa SDK oficial 4.43/Responses, Structured Outputs estrito
  e streaming tipado em testes contratuais locais;
- [x] caminho manual de Chat Completions removido sem trilha paralela;
- [x] repetir `must-pass` e `extended` contra a API real no novo transporte e
  comparar assertividade, P95, tokens e custo;
- policy de modelo/custo observavel esta ativa;

### Gate D - Produto apresentavel

- baseline browser live aberto em 2026-07-16: `7 passed`, `2 skipped` e `1
  flaky` recuperado; evidencia em
  [`38-page-builder-browser-gate-baseline-evidence.md`](38-page-builder-browser-gate-baseline-evidence.md);
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
adicionou o executor transacional local. O quarto e o quinto slices alteraram
services e testes internos do authoring. O sexto atualizou o corpus interno, o
materializador generico, schema grounding, testes e este plano de excelencia.
O setimo atualizou novamente schema, corpus, runner, stream service, testes e
este plano para certificar continuidade multi-turn. O oitavo promoveu
`column.add` ao catalogo fallback canonico de Table, passou a ranquear
capabilities semanticamente depois da escolha do componente, habilitou a
resolucao LLM compacta para modificacoes ancoradas no alvo atual, removeu uma
segunda resolucao redundante e compactou eventos duplicados sem perder replay.
Tambem moveu a sequencia e o marcador terminal para `ai_turn` pela migracao
V34, eliminando consultas de `max(seq)` e de terminalidade no caminho feliz.
O nono slice formalizou a maquina de estados ja suportada, corrigiu a fixture
do teste de transaction manager e adicionou o gate local P0.6 que agrega 76
provas de retomada, replay, cancelamento, timeout, schema indisponivel,
ownership e terminal unico.
O decimo slice fechou a linhagem do `page-apply`: alterou o DTO/OpenAPI,
regenerou bindings Java/Angular, projetou a referencia terminal ja existente no
cliente SSE, atualizou Page Builder, runners transacionais e a prova Playwright.
A landing page e o corpus HTTP nao possuem payload de `page-apply` a sincronizar;
o inventario de cobertura continua valido.
O decimo primeiro slice agregou a telemetria de provider ao turno sem expor
prompt, resposta bruta ou credenciais. O decimo segundo removeu campos de
dominio hardcoded do smoke, passou a validar planos contra `/schemas/filtered`
e adicionou gates locais de latencia, tokens e custo com fotografia de precos
versionada. Nao houve mudanca de contrato publico nem artefato Angular, landing
page ou corpus HTTP a sincronizar nesses dois slices.
O decimo terceiro slice atualizou Spring Boot/Spring AI dentro da linha 3.5
compativel, adicionou a prova black-box de `extra_body` e registrou a matriz de
modernizacao em
[`33-spring-ai-openai-modernization-evidence.md`](33-spring-ai-openai-modernization-evidence.md).
Nao houve mudanca de contrato publico; portanto OpenAPI, bindings Angular,
landing page e corpus HTTP nao exigiram sincronizacao.
O decimo quarto slice substituiu o transporte manual de Chat Completions pelo
SDK Java oficial 4.43 e Responses, reutilizando `AiProvider`, `AiCallConfig`,
`AiJsonSchema`, streaming/cancelamento e telemetria existentes. A evidencia
esta em
[`34-openai-responses-sdk-adapter-evidence.md`](34-openai-responses-sdk-adapter-evidence.md).
Como nao houve mudanca de contrato publico, OpenAPI, bindings Angular, landing,
manifests e corpus HTTP tambem nao exigiram sincronizacao neste corte.
Quando os proximos slices alterarem contratos publicos, revisar no mesmo ciclo:

- `docs/ai/contracts/**` e OpenAPI do Config Starter;
- bindings e public APIs de `@praxisui/ai`;
- manifests/recipes de Page Builder, Table, Form e Charts;
- `praxisui-http-examples/examples.manifest.json` e `LLM_SURFACE.md`;
- playgrounds e documentacao oficial da landing page.

## Proximo slice recomendado

`P0.1`, `P0.3`, a vertical basica de `P0.4`, a jornada progressiva de Table, a
state machine deterministica de `P0.6`, lineage, telemetria por turno e o gate
de eficiencia estao certificados. O proximo corte deve ampliar a cobertura
repetivel antes de iniciar uma migracao ampla de SDK, nesta ordem:

1. [x] incorporar ao relatorio versionado a comparacao antes/depois deste slice
   em
   [`27-assistant-consistency-p06-evidence.md`](27-assistant-consistency-p06-evidence.md),
   registrando P50/P95 e classificando a ausencia atual de retries, tokens e
   custo sem inventar valores;
2. [x] corrigir diagnostics residuais de provenance e provar lineage completo
   da decisao semantica ate o preview e a materializacao; evidencia em
   [`28-assistant-consistency-apply-lineage-evidence.md`](28-assistant-consistency-apply-lineage-evidence.md);
3. [x] implementar telemetria canonica de provider por fase e por turno, com
   tokens e latencia sanitizados; evidencias em
   [`29-provider-phase-telemetry-evidence.md`](29-provider-phase-telemetry-evidence.md)
   e
   [`30-turn-provider-telemetry-and-metrics-evidence.md`](30-turn-provider-telemetry-and-metrics-evidence.md);
4. [x] remover drift de campos hardcoded do smoke e ativar gates de tokens e
   custo estimado; evidencia em
   [`31-assistant-consistency-efficiency-gates-evidence.md`](31-assistant-consistency-efficiency-gates-evidence.md);
5. [x] executar as seis jornadas `must-pass` tres vezes e depois o perfil
   `extended`, investigando e corrigindo a variancia antes da promocao;
   evidencia em
   [`32-assistant-extended-consistency-evidence.md`](32-assistant-extended-consistency-evidence.md);
6. [x] certificar a mesma shell/orchestration em Table e Dynamic Form com o
   pacote minimo de contexto assistivel;
7. ampliar a jornada progressiva provando que cada capability governada
   continua semanticamente distinta:
   - [x] reordenacao, visibilidade, formato e filtros; fechamento de
     consistencia em
     [`37-assistant-consistency-provider-variance-closure-evidence.md`](37-assistant-consistency-provider-variance-closure-evidence.md);
   - [ ] recuperacao apos schema temporariamente indisponivel;
8. [x] atualizar a pista compativel para Boot 3.5.15/Spring AI 1.1.8 e provar a
   correcao de `extra_body`; evidencia em
   [`33-spring-ai-openai-modernization-evidence.md`](33-spring-ai-openai-modernization-evidence.md);
9. manter Spring AI 2.0 + Boot 4 como spike arquitetural separado, promovendo
   apenas se a evidencia superar o caminho compativel;
10. [x] implementar o adapter OpenAI com SDK oficial/Responses, Structured
    Outputs e telemetria, substituindo o caminho manual; evidencia em
    [`34-openai-responses-sdk-adapter-evidence.md`](34-openai-responses-sdk-adapter-evidence.md);
11. [x] repetir `must-pass`, `extended` e a jornada progressiva contra a API real
    no novo transporte, comparando assertividade, P95, tokens e custo antes de
    fechar operacionalmente o Gate C; o gate oficial fechou `42/42` no workflow
    `29541634715`.
12. [x] promover o runner Node existente como gate local multiplataforma e
    registrar o primeiro baseline browser live do Page Builder;
13. [ ] corrigir overflow, hierarquia loading/review, dark theme, i18n,
    teclado, AXE e narrow viewport antes da matriz browser `full`.

## Referencias oficiais para a frente de SDK

- OpenAI Models: <https://developers.openai.com/api/docs/models>
- OpenAI latest model guidance: <https://developers.openai.com/api/docs/guides/latest-model>
- OpenAI Responses migration: <https://developers.openai.com/api/docs/guides/migrate-to-responses>
- OpenAI Structured Outputs: <https://developers.openai.com/api/docs/guides/structured-outputs>
- OpenAI Java SDK: <https://github.com/openai/openai-java>
- Spring AI 1.1.8 release: <https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/>
- Spring AI 2.0 GA: <https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/>
- Spring AI Getting Started: <https://docs.spring.io/spring-ai/reference/getting-started.html>
- Spring AI Upgrade Notes: <https://docs.spring.io/spring-ai/reference/upgrade-notes.html>
- Spring AI Tool Calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI ChatClient: <https://docs.spring.io/spring-ai/reference/api/chatclient.html>
- Spring AI Structured Output: <https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html>
- Spring AI Observability: <https://docs.spring.io/spring-ai/reference/observability/index.html>
