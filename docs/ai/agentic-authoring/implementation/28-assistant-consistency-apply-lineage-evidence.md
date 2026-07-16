# Assistant consistency — apply lineage evidence — 2026-07-16

## Resultado

O `page-apply` deixou de confiar em uma combinacao de decisao semantica e patch
reenviada pelo navegador. Toda materializacao agora precisa referenciar o
`streamId` e o `resultEventId` do resultado terminal persistido pelo backend.

Antes do `ui_user_config` ser alterado, o Config Starter:

1. resolve o principal canonico do request;
2. revalida ownership, tenant, usuario, ambiente e expiracao do stream;
3. exige que o evento referenciado seja o terminal `result` do turno;
4. exige `canApply=true` no payload persistido;
5. compara exatamente o `compiledFormPatch` e a `semanticDecision` recebidos
   com os publicados no resultado terminal;
6. reaplica a politica de materializacao semantica e o contrato de ETag;
7. persiste `stream/thread/turn/event/decision` como tags auditaveis.

Nao foi criado token paralelo, segundo envelope ou autorizacao calculada no
frontend. O corte reutiliza as identidades canonicas que ja existiam no SSE e
o event log do turno.

## Inventario de aderencia

| Necessidade | Classificacao anterior | Resolucao |
|---|---|---|
| `streamId/threadId/turnId/eventId` no transporte | `ja-suportado-mal-nomeado-ou-mal-materializado` | O cliente SSE projeta `turnResultRef` a partir do envelope terminal, sem alterar a decisao |
| Event log e ownership do stream | `ja-suportado-so-ux` | `AiTurnEventService.requireOwnership` passou a ser gate do apply |
| Vinculo obrigatorio entre apply e resultado terminal | `lacuna-real-de-contrato` | `streamId` e `resultEventId` agora sao obrigatorios no DTO/OpenAPI |
| Linhagem no artefato materializado | `suportado-parcialmente` | Tags preservam as cinco identidades auditaveis sem copiar payload sensivel |
| Decisao de exibicao enriquecida no browser | `ja-suportado-mal-nomeado-ou-mal-materializado` | O Page Builder deixou de adicionar label ao objeto canonico enviado no apply |

## Contrato e consumidores

- Fonte canonica: `praxis-config-starter`.
- Contrato publico: `AgenticAuthoringApplyRequest` e OpenAPI AI v1.1.
- Projecao oficial: binding gerado de `@praxisui/ai`.
- Consumidor direto: `@praxisui/page-builder`.
- Persistencia: `ui_user_config`, mantendo scope, ambiente, secrets e ETag
  existentes.
- Runners derivados: prova transacional de consistencia, smoke HTTP/SSE e gate
  Playwright completo do Page Builder.

O contrato e estrito porque a plataforma esta em beta: previews sincronas sem
turno persistido nao podem mais ser promovidas a materializacao governada. O
turno agentico e a unidade canonica de autoria.

## Falhas certificadas

Os testes focais bloqueiam:

- `streamId`/`resultEventId` ausentes;
- referencia a outro evento terminal;
- terminal diferente de `result` ou sem autorizacao `canApply`;
- patch alterado depois da revisao;
- decisao semantica alterada depois da revisao;
- decisao que nao satisfaz a politica semantica;
- principal fora do ownership do stream, por delegacao ao gate canonico;
- ETag stale, preservando `412` antes de sobrescrever estado concorrente.

## Validacao executada

| Gate | Resultado |
|---|---:|
| `AgenticAuthoringApplyServiceTest` + `AgenticAuthoringControllerTest` | 27 testes verdes |
| Contrato OpenAPI + arquivos gerados | verde |
| Cliente backend/turn de `@praxisui/ai` | 49 testes verdes |
| Cliente apply do Page Builder | 12 testes verdes |
| Build `@praxisui/ai` | verde |
| Build `@praxisui/page-builder` | verde |
| Build de desenvolvimento do workspace Angular | verde, apenas warnings preexistentes de template |
| Sintaxe dos runners shell alterados | verde |
| Primeira matriz Playwright, JAR incremental contaminado | 5 de 10 jornadas verdes; 5 bloqueadas pelo host antes do apply |
| Matriz Playwright repetida com Quickstart limpo, OpenAI + Neon reais | 6 de 10 jornadas verdes; cockpit real aplicou o change-set com lineage terminal |
| `AgenticAuthoringTurnEngineTest` apos o gate | 148 testes verdes |
| Fluxo agentic do Page Builder apos o gate | 93 testes verdes |
| Fluxo 2 focal, formulario de funcionarios com LLM real | 1 de 1 verde em 2,7 min |

O gate Playwright foi executado contra backend `8088`, Angular `4003`, OpenAI e
Neon reais. Passaram as quatro provas deterministicas de shared-rule e a
auditoria estatica/HTTP. As jornadas Project Knowledge, PR7 e Fluxos 1/2 foram
bloqueadas antes do `page-apply`: o host retornou `500` em
`/schemas/filtered`, com `ClassNotFoundException` para
`org.praxisplatform.uischema.annotation.AiUsageMode`; houve tambem uma resposta
`404` para um schema request nao publicado pelo host.

Consequentemente, a matriz nao certifica ainda a persistencia browser-to-database
do novo contrato. A investigacao posterior descartou drift de contrato: fonte,
metadata `8.0.0-rc.112` e Config `0.1.0-rc.81` usam os enums separados
`AiVisibilityMode`, `AiTrainingUseMode` e `AiControlledUseMode`; nao existe
referencia atual ao enum antigo. O JAR do primeiro gate havia sido empacotado
incrementalmente sobre um `target` de outra revisao. Depois de `mvn clean
package`, as cinco provas isoladas de metadata/OpenAPI/AI/SSE passaram, incluindo
as operacoes de `/schemas/filtered` que falharam no browser.

O helper Playwright exige UUIDs validos de stream e evento no body de
`page-apply`. Na repeticao limpa, o cenario `Project Knowledge — cockpit aplica
change-set governado via backend real` passou e certificou o apply real com a
linhagem terminal. A matriz terminou em 6/10: os quatro cenarios deterministas,
o cockpit Project Knowledge e a auditoria estatica passaram.

As quatro falhas restantes nao sao mais falhas estruturais do host:

- a fixture revertida de Project Knowledge nao apareceu no audit do turno;
- o PR7 materializou tabela e contexto, mas nao chegou a uma revisao aplicavel;
- o Fluxo 1 terminou com resposta segura generica antes de materializar o resumo
  de fonte governada;
- o Fluxo 2 gerou formulario e contexto governado, mas terminou bloqueado sem
  quick reply de reparo.

O ultimo item revelou semantica ja existente mal materializada. O backend ja
publicava `canApply=false`, `reviewReason`, decisao semantica e contrato de quick
reply, mas nao compunha uma continuacao terminal quando o preview ficava
bloqueado. O Turn Engine agora inclui `governed-review-revise`, com fonte,
artifact, recurso, `reviewReason` e `semanticDecision`; o Page Builder preserva
esse payload e deixou de fabricar a mesma acao localmente. O Fluxo 2 focal passou
contra OpenAI e Neon reais depois da correcao.

## Proximo passo recomendado

Fechar a telemetria canonica de provider antes do spike de SDK, porque as duas
respostas genericas restantes ainda nao informam com precisao em qual passe a
assertividade foi perdida:

1. preservar `attempt`, provider/modelo e latencia por fase;
2. manter `ChatResponse`/usage no boundary do adapter em vez de reduzi-lo a
   texto;
3. agregar tokens de entrada, saida, cache e total por turno;
4. introduzir snapshot versionado de pricing/modelo para custo auditavel;
5. provar redacao, tenant isolation e ausencia de prompts/payloads sensiveis;
6. publicar as metricas no gate de consistencia e somente entao comparar
   Spring AI 1.1.8 com o baseline atual.

Em paralelo, manter dois gaps explicitamente separados da telemetria de
provider:

1. corrigir a projecao/auditoria de Project Knowledge revertido na fonte
   canonica, sem inferir ausencia por texto;
2. migrar as quick replies especializadas de qualidade de dashboard que ainda
   sao materializadas no Page Builder para o backend governado, removendo a
   ultima semantica de reparo local desse fluxo.
