# Fechamento de consistencia sob variancia de provider

Data do corte: 2026-07-16.

## Resultado

O corte `b694708` fechou o gate `extended x3` com OpenAI real em `42/42`
turnos, `100%` de acuracia nos perfis `must-pass` e `extended` e `3/3`
transacoes completas. O resultado foi reproduzido localmente antes de usar o
unico workflow remoto de fechamento da fase.

O basico agora se manteve consistente nas tres repeticoes: orientacao sobre o
que o assistente pode fazer, recomendacao de proximo passo, formulario,
dashboard aberto, tabela, tolerancia a erro de digitacao, resposta em ingles e
seis refinamentos cumulativos de Table.

## Classificacao e inventario de aderencia

- classificacao da mudanca: `transversal`, porque o corte toca transporte SSE,
  resolucao semantica, selecao de recurso, apresentacao de preview e jornada
  manifest-backed;
- corrida entre replay e registro do emitter: `suportado-parcialmente`;
- foco canonico de recurso LLM em dashboard/analytics:
  `ja-suportado-mal-nomeado-ou-mal-materializado`;
- mensagem governada de formulario compilado:
  `ja-suportado-mal-nomeado-ou-mal-materializado`;
- recuperacao de falha transitoria no resolvedor compacto:
  `suportado-parcialmente`;
- mensagem especifica e suficientemente explicativa em edicoes:
  `ja-suportado-so-ux`;
- lacuna real de contrato publico: nenhuma.

Nenhum DTO, endpoint, evento SSE, schema publico, header, ETag ou manifest foi
criado. O corte corrigiu como semantica e evidencias canonicas existentes sao
materializadas e como uma chamada semantica idempotente reage a falha
transitoria.

## Causas fechadas

1. `connect` podia ler o snapshot de replay antes de registrar o emitter,
   enquanto um append concorrente ocorria no intervalo. O registro passou a
   compartilhar o mesmo monitor de `appendAndEmit`.
2. Um foco exato de recurso authorado pela LLM era descartado para necessidades
   analiticas, permitindo drift para uma projecao estatistica. O foco canonico
   agora continua elegivel, exceto no caso de perfil individual, que exige
   projecao propria.
3. O formulario governado usa `compiledFormPatch`, nao `uiCompositionPlan`.
   Por isso o sintetizador nao reconhecia a materializacao ja confirmada e
   fazia uma chamada editorial opcional de 22 a 28 segundos. O tipo canonico
   do artefato agora sustenta imediatamente a mensagem de formulario.
4. O resolvedor semantico compacto tinha uma unica tentativa de 12 segundos.
   Ele agora possui ate duas tentativas de no maximo 8 segundos para falhas de
   transporte, timeout, rate limit, capacidade, servidor ou erro desconhecido.
   Falhas de autenticacao, quota e cliente continuam sem retry.
5. Edicoes manifest-backed preservam a explicacao especifica da LLM e deixam
   explicito que as demais configuracoes atuais serao preservadas.

A retentativa continua sendo LLM-first e usa o mesmo contrato estruturado,
contexto governado e schema estrito. Nao foi introduzido roteamento por
palavras-chave, regex ou heuristica textual primaria.

## Gate local de release

Artefato ignorado localmente:
`artifacts/local-e2e/openai-full-extended-x3-rc83-post-reliability-fixes-20260716`.

| Metrica | Resultado |
| --- | ---: |
| Turnos aprovados | `42/42` |
| Acuracia must-pass | `100%` |
| Acuracia extended | `100%` |
| Transacoes completas | `3/3` |
| Mediana terminal | `20,3895 s` |
| P95 terminal | `38,483 s` |
| Tokens totais | `82.868` |
| Media de tokens por turno | `1.973` |
| Maximo de tokens por turno | `4.002` |
| Custo estimado total | `91.437` USD micros |
| Maximo estimado por turno | `4.588` USD micros |

## Gate oficial

O workflow
[29541634715](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/29541634715),
no SHA `b69470818662b1c37b37df480bfd804f492bec94`, produziu:

| Metrica | Resultado |
| --- | ---: |
| Turnos aprovados | `42/42` |
| Acuracia must-pass | `100%` |
| Acuracia extended | `100%` |
| Transacoes completas | `3/3` |
| Mediana terminal | `12,008 s` |
| P95 terminal | `17,276 s` |
| Tokens totais | `82.862` |
| Media de tokens por turno | `1.973` |
| Maximo de tokens por turno | `4.012` |
| Custo estimado total | `91.727` USD micros |
| Maximo estimado por turno | `4.655` USD micros |

Todas as `42` estimativas de uso e custo ficaram completas. Nao houve falha de
provider no gate oficial; a recuperacao por segunda tentativa foi provada de
forma deterministica na suite unitaria, sem depender de provocar instabilidade
no provider real.

## Validacao local

- suites ampliadas de authoring/provider/OpenAI: `1.225` testes, zero falha,
  zero erro e `3` skips opcionais;
- testes focais do sintetizador e resolvedor compacto: verdes;
- `mvn -q -DskipTests install` no Config Starter: verde;
- Quickstart empacotado contra o artefato local do corte: verde;
- `git diff --check`: verde;
- gate focal dos dois casos anteriormente instaveis: `7/7`;
- gate integral local `extended x3`: `42/42`;
- gate oficial `extended x3`: `42/42`.

O Quickstart permanece pinado a uma versao publicada para builds normais. Nas
provas de workspace, o host deve ser empacotado explicitamente contra o
artefato local do Config Starter; o workflow oficial ja faz essa substituicao
de versao de forma canonica.

## Artefatos derivados

Foram revisados contratos AI, manifests, corpus, runner, workflow, docs
publicas, playgrounds, landing page e corpus HTTP. Como nao houve alteracao de
contrato publico ou do corpus de casos, nao foi necessario sincronizar
bindings Angular, manifests, examples, landing page ou `praxisui-http-examples`.

## Proximo corte recomendado

Com a consistencia funcional basica certificada, o proximo corte deve ser a
prova browser do Page Builder: estado vazio, loading e streaming, preview,
review/apply, mensagens de erro, acessibilidade por teclado, responsividade e
qualidade visual. Em paralelo, o runner deve ganhar fault injection local para
provar timeout + segunda tentativa no fluxo HTTP completo sem depender de uma
falha real da OpenAI.

