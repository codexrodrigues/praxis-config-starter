# Evidencia do baseline de consistencia OpenAI Responses

Data do corte: 2026-07-16.

## Resultado

O baseline bloqueante do assistente Praxis passou com OpenAI real,
`gpt-5.4-mini`, seis jornadas `must-pass` e tres repeticoes consecutivas. O
workflow oficial ficou verde com `18/18` turnos, `3/3` transacoes completas e
os gates de latencia, tokens e custo ativos.

Esta evidencia fecha o basico medido pelo corpus atual: orientacao amigavel
sobre o Page Builder, recomendacao de proximo passo, formulario de
funcionarios, tela aberta de acompanhamento e tabela de funcionarios. Ela nao
declara todo o produto pronto para mercado. O perfil estendido e a jornada
progressiva inicial de Table foram executados posteriormente; os resultados e
a variancia encontrada estao registrados em
`36-openai-extended-consistency-and-dashboard-variance-evidence.md`. A jornada
ampliada de Table e a prova visual/browser continuam como proximos gates.

## Classificacao e mapa de impacto

- classificacao: `transversal`, porque o corte combina runtime canonico,
  quickstart de referencia, corpus versionado e workflow operacional;
- fonte canonica alterada: resolucao semantica interna do
  `praxis-config-starter`;
- consumidor operacional validado: `praxis-api-quickstart` na porta oficial
  `8088`, empacotado contra os starters locais;
- consumidores indiretos: Page Builder, Dynamic Form, Table e demais hosts do
  turn engine agentic;
- contratos publicos, DTOs, endpoints, SSE, OpenAPI, headers, ETag e bindings
  Angular: inalterados;
- risco de breaking change publico: nenhum identificado;
- artefatos derivados aplicaveis: corpus, runner, workflow e esta evidencia;
  landing page, recipes e `praxisui-http-examples` nao exigiram sincronizacao.

## Inventario de aderencia

| Melhoria | Classificacao de aderencia | Evidencia existente reaproveitada | Correcao |
| --- | --- | --- | --- |
| Criacao canonica de tabela | `ja-suportado-mal-nomeado-ou-mal-materializado` | Capability `author_component` e decisao `component_authoring` ja existiam | Alias amplo passou a materializar `create_artifact` |
| Formulario governado sem passes redundantes | `ja-suportado-mal-nomeado-ou-mal-materializado` | Foco pre-intent, tool `searchApiResources` e POST raiz ja eram suficientes | `/schemas/filtered` deixou de ser confundido com rota de filtro de negocio |
| Tabela governada sem reclassificacao | `ja-suportado-mal-nomeado-ou-mal-materializado` | A LLM ja escolhia `table` e a tool devolvia um unico `/filter/cursor` operacional | Candidato unico resolve o turno sem `intent_fast`/`intent_full` |
| Dashboard com eixos e componente primario coerente | `ja-suportado-mal-nomeado-ou-mal-materializado` | A decisao ja continha eixos governados e o plano ja materializava chart + tabela | `praxis-crud` incoerente e normalizado para `praxis-chart`, preservando eixos e layout |
| Gate oficial reproduzivel | `suportado-parcialmente` | Corpus, runner, pricing e assertions ja existiam | Workflow opt-in passou a executar e publicar a evidencia completa |

Nenhum item exigiu contrato canonico novo. As falhas estavam na aderencia entre
semantica ja resolvida, selecao governada e materializacao.

## Rodadas que encontraram as caudas de variancia

O primeiro workflow de consistencia
[29527109128](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/29527109128),
no SHA `ed85cf9`, terminou com `16/18`:

- uma tabela funcional saiu como `author_component`, em vez de
  `create_artifact`;
- um formulario repetiu `pre_intent_tool_plan`, `intent_fast`, `intent_full` e
  `preview_message`, chegando a `29.033` tokens;
- a causa do formulario era a verificacao textual de `schemaUrl`: o endpoint
  canonico `/schemas/filtered` era confundido com uma rota de filtro do recurso.

O commit `316d742` normalizou o alias e passou a reconhecer o POST raiz. A
rodada seguinte
[29528390555](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/29528390555)
provou formularios e transacoes `3/3`, mas ainda terminou com `16/18`:

- uma tabela executou quatro passes e consumiu `27.571` tokens;
- uma tela aberta escolheu dashboard de serie temporal com eixos validos, mas
  marcou `praxis-crud` como primario; o plano continha chart + tabela, enquanto
  o contrato de CRUD exige tabela + formulario;
- o total chegou a `74.519` tokens, com P95 de `15,033 s`.

O commit `b92edfc` promoveu a evidencia unica de leitura de Table a decisao
canonica e alinhou o componente primario do dashboard com seus eixos.

## Gate final

O workflow
[29529372412](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/29529372412),
no SHA `b92edfc650c2de9812ed88c45e4732dfbef7f66e`, produziu:

| Metrica | Resultado |
| --- | ---: |
| Turnos aprovados | `18/18` |
| Acuracia must-pass | `100%` |
| Transacoes apply/readback/replay/stale-ETag/cleanup | `3/3` |
| Mediana terminal | `3,252 s` |
| P95 terminal | `9,371 s` |
| Tokens totais | `24.820` |
| Media de tokens por turno | `1.379` |
| Maximo de tokens por turno | `1.802` |
| Custo estimado total | `32.043` USD micros |
| Maximo estimado por turno | `2.754` USD micros |

A telemetria sanitizada confirmou:

- orientacoes de plataforma: um passe `platform_guidance_confirmation` por
  turno;
- tabelas: somente `pre_intent_tool_plan` nas tres repeticoes;
- telas abertas: somente `pre_intent_tool_plan` nas tres repeticoes;
- formularios: `pre_intent_tool_plan + preview_message`, sempre abaixo do
  budget;
- nenhum `intent_full`, payload bruto ou credencial nos artefatos.

## Validacao local

- regressao focal de resolver LLM + resolver canonico: `255/255` testes;
- perfil completo `ci-smoke-unit`: `2.004/2.004`, zero falha e zero erro;
- `git diff --check`: verde;
- empacotamento e instalacao Maven local do starter `0.1.0-rc.83`: verdes.

## Proximos gates

1. Ampliar a jornada progressiva de Table para reordenacao, visibilidade,
   formato, filtros e recuperacao de schema, preservando action plan
   manifest-backed e lineage de apply.
2. Reexecutar o perfil `extended x3` no corte que incorporar essa jornada,
   mantendo os mesmos limites de latencia, tokens e custo.
3. Revalidar no Page Builder por browser real a qualidade visual, estados de
   loading/review/apply, acessibilidade e responsividade das composicoes.
4. Somente depois comparar Spring AI 2 / Spring Boot 4 em spike separado,
   usando o mesmo corpus e as mesmas metricas como criterio de decisao.
