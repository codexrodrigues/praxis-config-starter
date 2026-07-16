# Evidencia do adapter OpenAI Responses com SDK oficial

Data do corte: 2026-07-16.

## Resultado

O caminho de geracao OpenAI do `praxis-config-starter` deixou de usar HTTP
manual em `/v1/chat/completions` e passou a usar o SDK Java oficial `4.43.0`
com a Responses API. A troca foi limpa: nao foi criada feature flag, rota
`v1`/`v2`, DTO publico ou adapter paralelo.

O nome historico `SpringAiOpenAiService` foi preservado apenas para evitar
churn de wiring interno. O transporte efetivo e identificado na telemetria
como `openai-responses-sdk`; tipos do SDK nao escapam pelo contrato `AiProvider`.

## Classificacao e mapa de impacto

- classificacao da auditoria: `arquitetural`;
- classificacao deste corte: `transversal`;
- fonte canonica: boundary interno de provider do `praxis-config-starter`;
- consumidor operacional validado: `praxis-api-quickstart` contra o artefato
  Maven local;
- consumidores indiretos: turn engine, Page Builder, Table, Dynamic Form e
  demais hosts que usam `AiProvider`;
- OpenAPI, DTOs, eventos SSE Praxis, headers, ETag e contratos Angular:
  inalterados;
- risco de breaking change publico: nenhum identificado;
- risco operacional remanescente: revalidacao do corpus real com credencial e
  comparacao de latencia/tokens/custo no novo transporte.

## Inventario de aderencia

| Necessidade | Aderencia antes do corte | Evidencia reaproveitada | Resultado |
| --- | --- | --- | --- |
| Contrato por chamada | `ja-suportado-mal-nomeado-ou-mal-materializado` | `AiCallConfig` ja publica modelo, temperatura, budget, timeout, credencial e trace | Preservado no adapter; nenhum DTO novo |
| Structured output | `suportado-parcialmente` | `AiJsonSchema` e `BeanOutputConverter` ja carregam o schema canonico | Schema passa em `text.format` como `json_schema` estrito; sem injecao no prompt |
| Streaming | `suportado-parcialmente` | `AiProvider` e o turn runtime ja possuem chunks, terminalidade e fallback | Eventos tipados `response.output_text.delta` e `ResponseAccumulator` substituem parser SSE manual |
| Cancelamento | `ja-suportado-mal-nomeado-ou-mal-materializado` | `AiStreamExecutionContextHolder` ja registra abort actions | `AsyncStreamResponse.close()` atende cancelamento antes e depois do primeiro chunk |
| Telemetria | `ja-suportado-mal-nomeado-ou-mal-materializado` | `AiProviderInvocationTrace` ja aceita transporte, response id, modelo, status e usage | Responses alimenta os campos existentes, incluindo cached input tokens; cache write permanece `null` porque o provider nao o publica |
| Retry | `ja-suportado-mal-nomeado-ou-mal-materializado` | Orquestrador ja governa retry/fallback e budget terminal | Retry interno do SDK foi desativado para evitar duplicacao e custo invisivel |
| Persistencia no provider | `lacuna-real-de-contrato` no adapter antigo | Praxis opera turn/thread em sua propria fronteira canonica | Toda criacao usa `store=false`; estado do provider nao vira segunda fonte de verdade |

Nao foi necessario criar contrato canonico. A plataforma ja possuia os
contratos internos necessarios; a lacuna estava na materializacao do transporte.

## Comportamento implementado

- `OpenAIOkHttpClient` com base URL normalizada para `/v1`, timeout por chamada
  e `maxRetries(0)`;
- Responses sincrono para texto e JSON;
- `text.format` com `json_schema`, `strict=true` e schema derivado do contrato
  Praxis; `json_object` somente quando nenhum schema foi fornecido;
- `store=false` em todas as geracoes;
- streaming assincrono com eventos semanticos do SDK, acumulacao do terminal e
  fechamento deterministico do recurso;
- recusa, resposta incompleta, resposta vazia e stream sem terminal nao sao
  aceitos como sucesso;
- erros HTTP, quota, rate limit, capacity, auth, client/server, transporte e
  timeout continuam normalizados nos tipos Praxis;
- timeout encapsulado pelo OkHttp e reconhecido pela cadeia causal, sem ser
  reclassificado incorretamente como falha de transporte;
- response id, modelo efetivo, status, input/output/total tokens e cache read
  continuam sanitizados no trace existente;
- a listagem de modelos continua read-only e separada da geracao; ela anuncia
  `responses` como metodo suportado.

## Validacao local

- `SpringAiOpenAiServiceTest`: 16 testes, zero falha, cobrindo request shape,
  `store=false`, texto, schema estrito, JSON object, budget GPT-5, resposta
  vazia/incompleta, recusa, quota/rate limit, streaming, cancelamento antes e
  depois do primeiro chunk, timeout e ausencia de retry do SDK;
- gate focal de provider, router, fallback/cancelamento, metricas e turn/SSE:
  verde;
- suite completa do starter rebased sobre `origin/main`: 2.060 testes, zero
  falha, zero erro e 4 ignorados;
- arvore Maven: SDK oficial `com.openai:openai-java:4.43.0`, com Jackson
  resolvido em `2.21.4` pelo BOM do Spring Boot 3.5.15;
- `mvn -DskipTests install` do starter `0.1.0-rc.82`: verde;
- quickstart contra o artefato local:
  `AiPatchSchemaResolutionIsolatedIntegrationTest` e
  `SecurityConfigAiPatchPolicyTest` verdes;
- empacotamento do `praxis-api-quickstart` com testes desabilitados: verde.

O warning conhecido da fixture H2 do quickstart sobre a tabela
`ai_assistant_observation` ausente permaneceu nao bloqueante. Nao houve mudanca
no quickstart.

Na primeira execucao ampla depois do rebase, a fixture legada de contrato SSE
conectou antes de existir qualquer evento persistido e produziu um corpo vazio.
O teste passou a aguardar um terminal real por no maximo cinco segundos antes
do replay, usando o mesmo padrao ja aplicado ao authoring SSE; a reproducao
isolada e a suite ampla posterior ficaram verdes.

## Validacao externa pendente

`PRAXIS_AI_OPENAI_API_KEY` e `OPENAI_API_KEY` nao estavam configuradas no
ambiente deste corte. Por isso, o corpus `must-pass`/`extended` nao foi repetido
contra a API real depois da migracao. A ausencia de credencial nao foi
contornada com chave, porta, bridge ou sucesso simulado.

Antes de declarar o Gate C operacionalmente fechado, executar uma rodada
controlada no quickstart real e comparar com a evidencia anterior:

1. as seis jornadas `must-pass` tres vezes;
2. o perfil `extended` e a jornada progressiva de Table;
3. assertividade, terminalidade, P50/P95, input/output/cache tokens e custo;
4. cancelamento/replay pelo stream Praxis;
5. recusa ou indisponibilidade como terminal explicavel, nunca sucesso falso.

## Artefatos derivados

Nao houve mudanca de superficie publica. OpenAPI, bindings Angular,
`praxisui-http-examples`, landing page, manifests e recipes nao exigem
sincronizacao. Este documento e o plano de excelencia sao as evidencias
derivadas aplicaveis ao corte.

## Proximo passo recomendado

Revalidar o corpus real com o novo transporte. Se assertividade, P95 ou custo
regredirem, ajustar policy de modelo/budget no boundary canonico, sem restaurar
Chat Completions manual. Depois do gate real verde, ampliar a jornada
progressiva de Table para reordenacao, visibilidade, formato, filtros e
recuperacao de schema, antes de iniciar o spike separado de Spring AI 2/Boot 4.
