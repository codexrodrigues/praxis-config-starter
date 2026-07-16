# Evidencia estendida OpenAI e fechamento da variancia de dashboard

Data do corte: 2026-07-16.

## Resultado

O primeiro gate oficial `extended x3` com OpenAI real executou `30` turnos e
encontrou uma unica variancia no basico. Os `12/12` turnos exclusivos do perfil
estendido passaram, incluindo os `6/6` turnos da jornada progressiva de Table.
O gate completo ficou vermelho porque a terceira repeticao de uma tela aberta
de funcionarios precisou de um segundo passe `intent_fast`, ultrapassou o
budget de custo em `2,12%` e produziu uma mensagem final generica demais.

A causa foi corrigida no commit `4a70dc8`: o ranking de projecoes passou a
respeitar o mesmo escopo canonico de `domainDiscovery` ja usado pelo ranking
operacional, e a apresentacao passou a poder reutilizar a intencao visual da
decisao semantica. O gate oficial `must-pass x3` pos-correcao fechou verde com
`18/18` turnos, `3/3` transacoes e todos os budgets ativos.

## Classificacao e mapa de impacto

- classificacao: `transversal`, por tocar resolucao semantica, apresentacao do
  preview, quickstart de referencia, corpus e gate operacional;
- aderencia: `ja-suportado-mal-nomeado-ou-mal-materializado`;
- fonte canonica corrigida: `praxis-config-starter`, no ranking de candidatos
  governados e no sintetizador de mensagem do preview;
- consumidores impactados: Page Builder e demais hosts do turn engine agentic;
- contratos publicos, DTOs, endpoints, SSE, OpenAPI, headers e ETag:
  inalterados;
- artefatos derivados revisados: corpus, runner, workflow e evidencias; nao
  houve mudanca que exigisse bindings Angular, landing page ou corpus HTTP;
- risco de breaking change publico: nenhum identificado.

Nenhum contrato novo foi criado. A LLM ja havia authorado o foco semantico em
funcionarios e em uma superficie analitica; o problema estava na aderencia do
ranking local a essa decisao governada.

## Gate estendido diagnostico

O workflow
[29529877412](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/29529877412),
no SHA `e31ea3cd124aa7c6fe02e151799c4820c2895d3f`, produziu:

| Metrica | Resultado |
| --- | ---: |
| Turnos aprovados | `29/30` |
| Acuracia must-pass | `94,4%` |
| Acuracia dos casos extended | `100%` |
| Mediana terminal | `10,1905 s` |
| P95 terminal | `18,975 s` |
| Tokens totais | `62.926` |
| Maximo de tokens por turno | `9.156` |
| Custo estimado total | `72.365` USD micros |
| Maximo estimado por turno | `10.212` USD micros |

A unica falha foi `employee-beautiful-screen-pt`, repeticao 3. O preview era
valido, aplicavel e corretamente ancorado em
`/api/human-resources/funcionarios`, mas executou:

1. `pre_intent_tool_plan`: `1.205` tokens;
2. `intent_fast`: `7.951` tokens.

O segundo passe confirmou a mesma decisao que o foco pre-intent ja sustentava.
O sintetizador final exibiu a operacao analitica como fonte, mas nao reutilizou
o objetivo canonico `dashboard de acompanhamento de funcionarios`.

## Jornada progressiva de Table

A jornada `employee-table-progressive-columns-pt` passou nas tres repeticoes:

| Turno | Resultado | Estado materializado | Faixa de tokens |
| --- | --- | --- | ---: |
| adicionar e-mail | `3/3` | `nomeCompleto`, `cargoNome`, `departamentoNome`, `email` | `3.902–3.958` |
| adicionar salario | `3/3` | colunas anteriores preservadas + `salario` | `2.937–2.956` |

O segundo turno herdou thread, decisao ativa e preview do primeiro. Nao houve
coluna duplicada nem perda das colunas existentes. Isso prova o baseline de
refinamento cumulativo; reordenacao, visibilidade, formato, filtros e
recuperacao de schema continuam como proxima ampliacao do corpus.

## Correcao

O commit `4a70dc8` fez dois ajustes sem ampliar contrato:

- candidatos de projecao agora recebem o bonus de escopo de
  `domainDiscovery` tanto na selecao quanto na verificacao de separacao entre
  concorrentes;
- quando `userGoal` apenas repete o prompt, o sintetizador pode usar
  `semanticDecision.visualizationDecision.intent` como objetivo humano do
  preview.

Os testes reproduzem a evidencia real: serie temporal de funcionarios compete
com analytics de folha e afastamentos, mas o foco canonico em funcionarios
vence sem invocar o resolver completo.

## Gate pos-correcao

O workflow
[29531278524](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/29531278524),
no SHA `4a70dc85c4c4443479096ec25d94efdd43e80e0c`, produziu:

| Metrica | Resultado |
| --- | ---: |
| Turnos aprovados | `18/18` |
| Acuracia must-pass | `100%` |
| Transacoes completas | `3/3` |
| Mediana terminal | `6,792 s` |
| P95 terminal | `12,021 s` |
| Tokens totais | `24.843` |
| Media de tokens por turno | `1.380` |
| Maximo de tokens por turno | `1.779` |
| Custo estimado total | `32.147` USD micros |
| Maximo estimado por turno | `2.651` USD micros |

As tres repeticoes da tela aberta usaram somente
`pre_intent_tool_plan`, consumiram `1.193–1.256` tokens e custaram
`1.964–2.247` USD micros. Todas mencionaram `Funcionarios` na resposta e
nenhuma executou `intent_fast`.

## Validacao local

- suites focais de resolver e sintetizador: `248/248`;
- suite Maven completa: `2.072` testes, zero falha, zero erro e `4` skips
  opcionais;
- teste versionado do corpus: `6/6`;
- `bash -n` nos tres runners do gate: verde;
- `git diff --check`: verde.

## Proximos gates

1. Ampliar a jornada progressiva de Table usando as operacoes canonicas ja
   existentes: `column.order.set`, `column.visibility.set`,
   `column.format.set` e `filter.advanced.configure`.
2. Adicionar assercoes de estado para ordem, visibilidade, formato e filtros,
   preservando lineage e preview herdado.
3. Executar o perfil `extended x3` novamente no mesmo corte que incorporar a
   jornada ampliada, evitando um workflow remoto intermediario apenas para
   repetir evidencia ja obtida.
4. Realizar prova browser no Page Builder para qualidade visual, loading,
   review/apply, acessibilidade e responsividade.

