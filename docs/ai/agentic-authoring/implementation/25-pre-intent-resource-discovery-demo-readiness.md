# Pre-Intent Resource Discovery Demo Readiness

Data: 2026-06-21

## Objetivo

Este documento fecha a primeira apresentacao da refatoracao que nasceu do
prompt simples:

```text
quero criar algo que mostre informacoes dos empregados
```

A apresentacao deve provar o antes/depois do fluxo de authoring no Decision
Playground: a LLM planeja uma busca governada antes da resolucao final de
intencao, o backend executa `searchApiResources`, a evidencia recuperada guia a
decisao semantica, e a preview e materializada sem sinonimos hardcoded no
frontend ou backend.

Este documento nao abre novo contrato. Ele consolida evidencias ja produzidas
localmente e separa o que esta pronto para demo do que ainda e melhoria de
performance/robustez.

## Classificacao

- Mudanca documentada: `docs-apenas`.
- Fonte canonica operacional: `praxis-config-starter`.
- Consumidores impactados na demo: Decision Playground / Page Builder IA via
  `/api/praxis/config/ai/authoring/turn/stream/start`.
- Contrato publico novo: nenhum.
- Artefatos derivados obrigatorios: nenhum alem deste checkpoint e do indice
  de implementacao.

## Inventario de aderencia

- `ja-suportado-mal-nomeado-ou-mal-materializado`: o catalogo governado e o
  `domainDiscovery` ja tinham `human-resources.funcionarios`, mas o fluxo antigo
  nao materializava isso como evidencia antes de pedir esclarecimento.
- `suportado-parcialmente`: o backend ja tinha SSE, replay, redaction,
  `searchApiResources`, catalogo de metadata e preview; faltava observabilidade
  e planejamento pre-intent LLM para acionar a busca no momento certo.
- `ja-suportado-so-ux`: o stream ja tinha eventos, mas a apresentacao precisava
  de mensagens curadas e fases legiveis para esperas longas.
- `lacuna-real-de-contrato`: nenhuma lacuna nova foi identificada para esta
  apresentacao.

## Estado demonstravel

O fluxo local atual ja demonstra:

- `context.bundle` com tela vazia e `domainDiscovery` contendo
  `human-resources.funcionarios`;
- `tool.plan` antes de `intent.resolved`;
- `searchApiResources` chamado pelo backend, nao pelo frontend;
- `retrievalQuery` authorada pela LLM com foco semantico, por exemplo
  `primary business entity: human-resources.funcionarios`;
- candidato selecionado `/api/human-resources/funcionarios`;
- evidencia `semantic-retrieval`, `llm-resource-focus`,
  `schema-available`, `semantic-role:operational-resource` e
  `tool-search-api-resources`;
- `intent.resolved` com `resolved=true`, `canMaterialize=true` e
  `routeClass=component_authoring`;
- preview governada com tabela e formulario;
- resposta final curta, apresentavel e sem a resposta consultiva longa que
  motivou a investigacao.

Smoke final local com OpenAI:

```text
artifact: artifacts/local-e2e/openai-llm-focus-shortcut-empregados
prompt: quero criar algo que mostre informacoes dos empregados
selectedResourcePath: /api/human-resources/funcionarios
canApply: true
toolResultCandidateCount: 1
catalogDiscoveryElapsedMs: 2360
groundingElapsedMs: 298
totalElapsedMs: 2661
preIntentPlanning: 8.390s
intentResolveLlm: null
```

Mensagem final observada:

```text
Montei uma pre-visualizacao governada.

- Fonte governada: Funcionarios.
- Materializacao: tabela e formulario.
- Validacao: usei a decisao semantica e os campos confirmados disponiveis.
- Proximo passo: revise a pre-visualizacao, peca ajustes ou salve quando estiver ok.
```

## Guard rail demonstravel

O atalho de foco canonico nao pode transformar toda mencao a funcionarios em
cadastro operacional. O smoke de guarda prova que uma intencao de perfil ainda
passa pela recuperacao semantica completa e seleciona a view correta:

```text
artifact: artifacts/local-e2e/openai-llm-focus-shortcut-profile-guard
prompt: quero uma tela de perfil individual do funcionario
selectedResourcePath: /api/human-resources/vw-perfil-heroi
canApply: true
toolResultCandidateCount: 3
catalogDiscoveryElapsedMs: 1996
groundingElapsedMs: 887
totalElapsedMs: 2885
preIntentPlanning: 12.019s
intentResolveLlm: null
selectedEvidence: semantic-retrieval, semantic-role:profile-projection
```

Esse controle e essencial para a narrativa arquitetural: a decisao continua
semanticamente governada. O sistema nao usa matriz de sinonimos, nao usa
keyword routing e nao promove o cadastro operacional quando a necessidade
material pede perfil/projecao.

## Matriz OpenAI completa

A matriz OpenAI completa foi rodada localmente contra o quickstart em
`127.0.0.1:8088`, usando API real, SSE real e provider OpenAI. Ela cobre
prompts curtos, erro de digitacao, transcricao ruim, ingles, pedidos abertos,
perfil individual, visao resumida, analytics de folha, fornecedores e narrativas
longas/confusas.

Baseline validado:

```text
artifact: artifacts/local-e2e/openai-full-matrix-preintent-demo-readiness-fixed-20260621-191100
cases: 16
expectedRecovered: 16
unexpectedApply: 0
recallAt1: 1.000
recallAt3: 1.000
MRR: 1.000
top1Accuracy: 1.000
llmSecondPassUsed: 0
streamFeedbackTechnicalMessageCount: 0
thoughtStepTechnicalMessageCount: 0
averageDurationSeconds: 36.295
maxDurationSeconds: 52.173
gatePassed: true
```

O caso que falhou antes da correcao tambem passou na matriz:

```text
case: visao_resumida_funcionario
prompt: visão resumida de funcionário
selectedResourcePath: /api/human-resources/vw-perfil-heroi
canApply: true
selectedEvidence: semantic-retrieval, semantic-role:profile-projection
```

A causa corrigida foi que a classificacao interna do foco de recurso authorado
pela LLM considerava `desired surface` e `semantic query`, mas ignorava
`supporting concepts`. Assim, um foco com `supporting concepts: visão resumida`
podia ser tratado como recurso operacional e acionar o atalho
`llm-resource-focus` para `/api/human-resources/funcionarios`. A correcao usa os
conceitos de apoio authorados pela LLM para decidir se o atalho operacional deve
ser evitado e se a recuperacao semantica completa deve ranquear projecoes de
perfil.

Essa correcao nao adiciona sinonimos hardcoded nem roteamento textual primario:
ela preserva a decisao semantica authorada pela LLM e melhora como a evidencia
interna ja produzida por ela e consumida pelo catalogo.

## Checkpoint de performance

Depois da matriz completa, o maior gargalo remanescente era
`searchApiResources catalog discovery`. A causa mais acionavel nao era a busca
semantica geral, mas o caminho em que a LLM ja havia authorado uma entidade
canonica em `primary business entity` e, mesmo assim, o catalogo executava
`findAll()` para filtrar todos os metadados em memoria.

A melhoria aplicada troca esse caminho feliz por lookup direto dos endpoints
canonicos do recurso:

```text
primary business entity: human-resources.funcionarios
lookup direto: /api/human-resources/funcionarios/filter/cursor POST
fallback: scan amplo por findAll() apenas se o lookup exato nao encontrar metadado
```

Isso nao muda a decisao semantica: a entidade continua sendo authorada pela LLM,
e a otimizacao apenas materializa esse foco canonico de forma mais eficiente.
Casos de perfil, analytics ou projecoes continuam passando pela recuperacao
semantica completa.

Smoke local OpenAI depois da melhoria:

```text
artifact: artifacts/local-e2e/openai-focused-resource-direct-lookup-empregados
prompt: quero criar algo que mostre informacoes dos empregados
selectedResourcePath: /api/human-resources/funcionarios
canApply: true
toolResultCandidateCount: 1
catalogDiscoveryElapsedMs: 1109
groundingElapsedMs: 309
totalElapsedMs: 1422
toolExecution: 2.657s
intentResolveLlm: null
selectedEvidence: semantic-retrieval, llm-resource-focus, semantic-role:operational-resource
```

Guard rail de perfil apos a melhoria:

```text
artifact: artifacts/local-e2e/openai-direct-lookup-profile-guard
prompt: visão resumida de funcionário
selectedResourcePath: /api/human-resources/vw-perfil-heroi
canApply: true
toolResultCandidateCount: 3
catalogDiscoveryElapsedMs: 2564
groundingElapsedMs: 914
totalElapsedMs: 3480
toolExecution: 4.740s
selectedEvidence: semantic-retrieval, semantic-role:profile-projection
```

## Roteiro curto de apresentacao

1. Mostrar a falha original pela imagem/historico: prompt simples, resposta
   longa, generica e sem ancoragem no recurso governado.
2. Explicar o diagnostico: o problema nao era falta de palavra "empregado" na
   sugestao; era ausencia de busca governada planejada pela LLM antes do
   `intent.resolved`.
3. Rodar ou mostrar o smoke local do prompt original.
4. Apontar no stream: `tool.plan`, `tool.result`, `intent.resolved` e `result`.
5. Mostrar que o candidato selecionado e `/api/human-resources/funcionarios`.
6. Mostrar a preview com tabela/formulario e a mensagem final curta.
7. Rodar ou mostrar o guard rail de perfil individual selecionando
   `/api/human-resources/vw-perfil-heroi`.
8. Fechar com a tese: Praxis authora decisoes semanticas governadas por IA; UI
   e componentes materializam a decisao, mas nao roteiam intencao por texto.

## Criterios de sucesso para a demo

- `tool.plan` aparece antes de `intent.resolved`.
- `tool.result` mostra `candidateCount > 0`.
- O prompt original termina com `canApply=true`.
- O recurso selecionado e `/api/human-resources/funcionarios`.
- A resposta final nao pede esclarecimento generico sobre empregados.
- O caso de perfil seleciona `/api/human-resources/vw-perfil-heroi`.
- `intentResolveLlm` permanece `null` quando a evidencia pre-intent ja resolve
  a decisao.
- Mensagens de progresso nao vazam payload tecnico, stack trace, JSON bruto ou
  texto redigido para o usuario.

## Limites ainda nao fechados

Estes pontos nao bloqueiam a apresentacao inicial, mas devem virar a proxima
onda de melhoria antes de chamar o corte de release:

- reduzir `preIntentPlanning`, que ainda fica em torno de 8s a 12s em smokes
  reais;
- reduzir ou aquecer custos de schema/OpenAPI/grounding, especialmente
  `searchApiResources catalog discovery`, que na matriz teve media de 6.949s e
  maximo de 20.627s;
- ampliar a matriz com prompts de multiplas intencoes narradas no mesmo texto,
  porque a matriz atual ja cobre texto longo/confuso, mas ainda nao fecha
  composicao multi-objetivo;
- revisar quando candidatos complementares devem ficar apenas como evidencia
  interna e quando devem aparecer para o usuario;
- manter prova browser no Decision Playground com API local antes de qualquer
  deploy remoto.

## Comandos de referencia

Smoke principal:

```bash
BASE_URL=http://127.0.0.1:8088 \
ORIGIN=http://localhost:4003 \
PROVIDER=openai \
STREAM_TIMEOUT_SECONDS=180 \
USER_PROMPT='quero criar algo que mostre informacoes dos empregados' \
ARTIFACTS_DIR=artifacts/local-e2e/openai-llm-focus-shortcut-empregados \
bash tools/local-e2e/run-agentic-turn-pre-intent-local.sh
```

Guard rail de perfil:

```bash
BASE_URL=http://127.0.0.1:8088 \
ORIGIN=http://localhost:4003 \
PROVIDER=openai \
STREAM_TIMEOUT_SECONDS=180 \
USER_PROMPT='quero uma tela de perfil individual do funcionario' \
ARTIFACTS_DIR=artifacts/local-e2e/openai-llm-focus-shortcut-profile-guard \
bash tools/local-e2e/run-agentic-turn-pre-intent-local.sh
```

Matriz completa recomendada antes de release:

```bash
ARTIFACTS_DIR="artifacts/local-e2e/openai-full-matrix-preintent-demo-readiness-$(date +%Y%m%d-%H%M%S)" \
BASE_URL=http://127.0.0.1:8088 \
ORIGIN=http://localhost:4003 \
PROVIDER=openai \
STREAM_TIMEOUT_SECONDS=180 \
bash tools/local-e2e/run-agentic-turn-pre-intent-matrix-local.sh
```
