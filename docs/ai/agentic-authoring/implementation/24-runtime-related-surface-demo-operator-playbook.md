# Runtime Related Surface Demo Operator Playbook

Data: 2026-06-09

## Objetivo

Este playbook orienta uma demonstracao humana do preview runtime related
surface governado no Page Builder IA. Ele e operacional: define preparacao,
ordem de telas, prompts copiaveis, sinais visuais esperados e criterio de
sucesso por passo.

Nao use este documento para abrir nova capacidade funcional. A demo deve
apresentar apenas o estado ja validado pelo gate real oficial.

## Preparacao antes da demo

1. Confirmar portas livres:

```bash
lsof -nP -iTCP:4003 -sTCP:LISTEN || true
lsof -nP -iTCP:8088 -sTCP:LISTEN || true
```

2. Subir o Quickstart real em `http://localhost:8088` com Neon/Postgres remoto
   configurado no `.env.dev` local e policies backend-owned. Use
   `run_local.sh` para carregar o env de forma segura; nao use
   `set -a && source ./.env.dev`, porque senhas com `$` ou `&` podem ser
   interpretadas pelo shell.

```bash
cd praxis-api-quickstart
PORT=8088 \
PRAXIS_AI_AUTHORING_RUNTIME_TOOL_POLICY_REF=runtime-tool-policy:multi-tool-readonly-beta \
PRAXIS_AI_AUTHORING_RUNTIME_RELATED_SURFACE_INTENT_POLICY_REF=runtime-related-surface-intent-policy:temporal-compare-smoke \
PRAXIS_AI_AUTHORING_RUNTIME_RELATED_SURFACE_TEMPORAL_COMPARISON_FIELD_REF=ocorridoEm \
bash ./run_local.sh
```

3. Subir Angular real em `http://localhost:4003`:

```bash
cd praxis-ui-angular
PAX_PROXY_TARGET=http://localhost:8088 PORT=4003 npm start
```

4. Abrir:

```text
http://localhost:4003/page-builder-ia
```

5. Carregar a recipe `mission-command-center`.
6. Selecionar uma missao na tabela principal antes de iniciar os prompts.
7. Abrir o assistente do Page Builder pelo botao global da toolbar do builder
   (`page-builder-agentic-toggle`). Nao use o botao de assistente que aparece
   dentro do widget de tabela: ele pertence ao assistente contextual da tabela e
   usa o fluxo de patch/QA do componente, nao o stream runtime related surface
   governado deste playbook.

## Entrada visual correta

Na tela existem mais de uma entrada com icone de assistente. Para esta demo,
use somente o painel do Page Builder:

- botao/painel esperado: `page-builder-agentic-toggle` ->
  `page-builder-agentic-authoring-panel`;
- transporte esperado: `/api/praxis/config/ai/authoring/turn/stream/start`;
- evidencias esperadas no request: `runtimeComponentObservations` e
  `runtimeComponentObservationTrustBoundary=untrusted_frontend_observation`.

O assistente embutido da tabela continua existindo para autoria/QA contextual do
componente de tabela, mas ele nao demonstra o contrato runtime related surface
governado. Se ele for usado por engano, a resposta pode parecer correta ou
conservadora, mas nao prova `runtimeToolPlan`, multi-read, summary, compare,
quick replies governadas ou fail-closed de superficies relacionadas.

## Narrativa sugerida

A mensagem central da demo:

> O componente runtime descreve evidencias e affordances; o backend aterra,
> reconcilia e governa o que pode ser lido; a IA nao inventa relacoes nem usa o
> frontend como autoridade.

Evite vender como "chat que entende tabela". A tese correta e: "decisoes
semanticas governadas sobre capacidades runtime consultaveis".

## Sequencia principal

### 1. Provar contexto runtime consultavel

Prompt:

```text
Quais superficies relacionadas estao disponiveis para a missao selecionada?
```

Criterios visuais:

- O assistente reconhece que existe uma selecao atual.
- A resposta menciona superficies relacionadas como participantes/equipe e
  linha do tempo/eventos.
- Nao aparecem linhas cruas, `sampleRows`, payload tecnico nem valores
  sensiveis.

Mensagem para narrar:

> A tabela nao decide a intencao. Ela publicou um snapshot seguro do que existe
> agora: selecao, campos, superficies e operacoes. O backend transforma isso em
> contexto consultavel aterrado.

### 2. Provar multi-read governado

Prompt:

```text
Liste participantes e eventos da missao selecionada.
```

Criterios visuais:

- A resposta lista dados relacionados das duas superficies.
- O comportamento deve corresponder a no maximo dois reads read-only
  governados.
- Nao deve aparecer alias singular de leitura como se fosse uma unica
  superficie quando duas foram lidas.
- Nao deve haver vazamento de linha crua, CPF, token, email ou payload bruto.

Mensagem para narrar:

> Aqui o plano usa duas leituras read-only porque a policy backend permite ate
> duas superficies aceitas. Se uma delas falhasse, o terminal nao exibiria
> registros parciais.

### 3. Provar summary governado

Prompt:

```text
Resuma participantes e eventos da missao selecionada.
```

Criterios visuais:

- A resposta traz um resumo agregado.
- O resumo deve derivar dos reads sanitizados, nao de valores runtime crus.
- Compare deve permanecer ausente neste passo.

Mensagem para narrar:

> Summary nao e uma nova tool. Ele agrega evidencias ja lidas e sanitizadas,
> mantendo o mesmo orcamento e as mesmas regras de redaction.

### 4. Provar compare governado

Prompt:

```text
Faca uma comparacao governada entre participantes e eventos da missao selecionada usando o campo ordem; nao liste registros, compare as superficies.
```

Criterios visuais:

- A resposta deve falar em comparacao governada.
- A evidencia deve ser agregada, sem listar registros individuais.
- Os fatos esperados incluem contagens/distribuicoes/overlap conforme contrato
  vigente, sempre dentro da allowlist backend-reconciled.
- Summary e detail nao devem aparecer como evidencias terminais deste passo.

Mensagem para narrar:

> Compare so existe quando o backend aceita uma dimensao comparavel e fact
> kinds permitidos. Campo ausente, redigido, ambiguo ou nao governado bloqueia
> antes de qualquer leitura.

### 5. Provar detail ambiguo e quick replies governadas

Prompt:

```text
Detalhe a superficie relacionada da missao selecionada.
```

Criterios visuais:

- A resposta deve pedir desambiguacao ou oferecer opcoes.
- Devem aparecer quick replies/opcoes para superficies como participantes e
  eventos/linha do tempo.
- Nao deve haver read backend neste passo.
- As opcoes devem representar refs governadas, nao texto livre do frontend.

Mensagem para narrar:

> Quando ha multiplas superficies elegiveis e a intencao precisa de um alvo, o
> sistema falha fechado e oferece opcoes canonicas. A quick reply carrega uma
> decisao semantica backend-owned para o proximo turno.

### 6. Provar selecao de opcao de detail

Acao:

```text
Clique na quick reply de eventos/linha do tempo.
```

Se nao houver clique disponivel na apresentacao, use o prompt de contingencia:

```text
Quero um drill-down detalhado da linha do tempo e dos eventos da missao selecionada; nao detalhe participantes.
```

Criterios visuais:

- Deve executar exatamente uma leitura governada.
- A superficie alvo deve ser eventos/linha do tempo.
- Participantes nao devem ser lidos neste passo.
- O alvo deve ser reconciliado pelo backend.

Mensagem para narrar:

> O frontend so promove a decisao recebida do backend. A autorizacao real ainda
> acontece no backend, contra candidatos runtime atuais.

### 7. Provar follow-up multi-turn como grounding-only

Prompt:

```text
Mostre os eventos.
```

Criterios visuais:

- O sistema deve usar o contexto historico para orientar o alvo.
- Ainda assim, a leitura so deve acontecer se o backend resolver e reconciliar
  `LIST_TARGET_SURFACE_REF` contra o runtime atual.
- Deve executar uma leitura governada na superficie de eventos/linha do tempo.

Prompt alternativo para summary direcionado:

```text
Resuma os eventos.
```

Criterios visuais adicionais:

- Deve produzir summary direcionado.
- Deve ler apenas a superficie de eventos/linha do tempo.
- O contexto historico nao deve aparecer como autorizacao direta.

Mensagem para narrar:

> O historico ajuda o grounding, mas e deliberadamente insuficiente como
> permissao. Isso evita que uma opcao antiga, stale ou de outra pagina autorize
> leitura no turno atual.

## Cenarios de seguranca para mencionar sem executar

- Surface forjada: bloqueia antes de HTTP.
- Envelope stale: bloqueia.
- Selecao ausente: nao le dados relacionados.
- Segunda superficie falhando: terminal sai sem registros parciais.
- Hint frontend tentando ativar policy: ignorado.
- Compare com dimensao redigida ou ausente: planning-only/read-free.

## Criterios de sucesso da demo

- O publico ve uma selecao runtime virar contexto consultavel.
- A demo mostra pelo menos um multi-read e um read direcionado.
- A demo mostra pelo menos um bloqueio/read-free por ambiguidade.
- Quick replies aparecem como escolha governada, nao como comando local.
- Follow-up natural funciona sem transformar historico em autorizacao.
- Nenhuma resposta exibe payload bruto, linhas cruas, `sampleRows`, tokens,
  CPF, email ou dado sensivel.

## Se algo oscilar durante a demo

- Se o provider LLM falhar, use o artefato do gate oficial como evidencia do
  baseline e rode um cenario focal isolado depois.
- Se a resposta ficar conservadora demais, narre como comportamento fail-closed
  e use o passo de quick reply ou o prompt explicito de detail.
- Se Angular ou Quickstart nao estiverem prontos, nao improvise outra porta:
  use `4003` e `8088`, que sao os origins oficiais deste gate.
- Se algum processo ficar preso, encerre servidores e confirme:

```bash
lsof -nP -iTCP:4003 -sTCP:LISTEN || true
lsof -nP -iTCP:8088 -sTCP:LISTEN || true
```

## Gate final antes de apresentar

```bash
cd praxis-ui-angular
npm run smoke:runtime-tool-plan:readonly:short -- --timeout-ms 240000
```

Resultado esperado:

- `scenarioCount=15`
- `allPass=true`
- `failedScenarios=[]`
- `runtimeRelatedSurfaceReads[]` plural como evidencia canonica
- sem alias singular em multi-read
- sem vazamento bruto
- portas `4003` e `8088` livres depois do encerramento
