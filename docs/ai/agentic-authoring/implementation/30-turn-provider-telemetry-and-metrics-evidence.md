# Evidência 30 — telemetria de provider por turno e métricas operacionais

## Resultado

O runtime de authoring agora preserva, de forma request-scoped, as invocações de provider das fases:

- `platform_guidance_confirmation`, `targeted_component_intent`, `intent_fast`
  e `intent_full`;
- `pre_intent_tool_plan`, incluindo tentativas e retries;
- `minimal_form_plan`;
- `component_edit_plan`;
- `preview_message`.

Quando `contextHints.includeLlmDiagnostics=true`, o evento terminal agrega até 12 invocações em `decisionDiagnostics.providerTelemetry`, com latência, status, transporte e uso de tokens. A projeção declara explicitamente que prompt, resposta bruta e credenciais não foram copiados.

O mesmo diário é propagado nos encerramentos antecipados de orientação da plataforma, resposta consultiva e clarificação governada; perguntas como “o que posso fazer aqui?” não ficam fora da observabilidade do turno.

Sem o opt-in, o diário permanece interno e não altera os contratos JSON de `AgenticAuthoringPlanResult` ou `AgenticAuthoringPreviewResult`.

## Inventário de aderência

| Necessidade | Classificação | Decisão |
| --- | --- | --- |
| Diagnóstico detalhado opt-in | `ja-suportado-mal-nomeado-ou-mal-materializado` | Reutilizar `llmDiagnostics` e `decisionDiagnostics` existentes. |
| Métricas operacionais | `ja-suportado-so-ux` | Reutilizar Micrometer global já empregado pelo runtime. |
| Propagar snapshots das fases até o turno | `lacuna-real-de-contrato` interno | Acrescentar diário interno com `@JsonIgnore`, sem novo envelope HTTP/SSE. |
| Identificar falhas sem mensagens sensíveis | `suportado-parcialmente` | Classificar em taxonomia operacional pequena e sanitizada. |

## Métricas

- `ai_provider_invocations_total`, tags: `phase`, `provider`, `status`;
- `ai_provider_invocation_duration_ms`, tags: `phase`, `provider`, `status`;
- `ai_provider_tokens`, tags: `phase`, `provider`, `kind`.

Não são usadas tags de tenant, usuário, modelo arbitrário, prompt, response id ou mensagem de erro. Isso mantém a cardinalidade limitada e evita vazamento de conteúdo.

## Segurança da projeção SSE

O redactor de eventos preserva apenas os nomes canônicos `inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheWriteInputTokens` e `totalTokens` quando o valor é inteiro não negativo ou `null`. A exceção é fechada por nome e tipo: `token`, `accessToken`, aliases, texto, números negativos e valores adulterados continuam redigidos.

Testes cobrem tanto o redactor isolado quanto a persistência do evento terminal, garantindo que os contadores operacionais permaneçam utilizáveis sem abrir a fronteira de credenciais.

## Mapa de impacto

- fonte canônica: `praxis-config-starter`, runtime de agentic authoring e adapters de provider;
- consumidores: evento terminal SSE apenas quando o diagnóstico detalhado é solicitado;
- contratos públicos: nenhum campo obrigatório novo; resultados internos continuam ignorados na serialização padrão;
- Angular, landing page, corpus HTTP e recipes: sem atualização necessária, pois transporte, rotas e payload padrão não mudaram;
- risco de breaking change: baixo, coberto por teste explícito de serialização.

## Validações

- testes focais de intent, pre-intent, plano, component edit, preview message, preview e turn engine;
- teste de métricas com `SimpleMeterRegistry`;
- teste de serialização garantindo que o diário interno não aparece nos contratos padrão;
- teste de redaction e persistência SSE para contadores numéricos, `null` e credenciais;
- gate completo `ci-smoke-unit`: 1.970 testes, sem falhas ou erros, além de JAR, sources e javadocs;
- quickstart empacotado contra o starter local e iniciado com OpenAI real, embeddings OpenAI, 35 migrations validadas e schema 35 atualizado.

## Provas operacionais reais

O corpus de consistência executado em `2026-07-16` passou em 5/5 casos fundamentais: orientação da plataforma, recomendação do próximo passo, criação de formulário, criação de tela e criação de tabela. Os três fluxos de criação terminaram com `canApply=true`; a jornada transacional do formulário também comprovou apply, readback, replay, ETag stale bloqueado e cleanup. Evidência local: `artifacts/local-e2e/assistant-consistency-20260716-101203`.

A prova final opt-in criou um formulário profissional de cadastro de funcionários com `canApply=true` e publicou duas invocações OpenAI bem-sucedidas:

| Fase | Latência | Input | Output | Total |
| --- | ---: | ---: | ---: | ---: |
| `pre_intent_tool_plan` | 4.268 ms | 947 | 217 | 1.164 |
| `preview_message` | 2.225 ms | 534 | 58 | 592 |
| agregado | 6.493 ms | 1.481 | 275 | 1.756 |

`cacheWriteInputTokens` permaneceu `null` nas invocações, em vez de ser confundido com credencial. A projeção confirmou `rawPromptCopied=false`, `rawResponseCopied=false` e `credentialsCopied=false`. Evidência local: `artifacts/local-e2e/provider-telemetry-opt-in-20260716-1023-final`.

O drift legado do `run-agentic-http-sse-smoke-local.sh`, que esperava o campo
fictício `titulo` para incidentes, foi removido no corte seguinte. O runner agora
resolve o `/schemas/filtered` apontado pelo próprio plano e valida campos,
obrigatoriedade e schema pointers contra a fonte canônica. A evidência, os gates
de tokens/custo e a prova real estão em
[`31-assistant-consistency-efficiency-gates-evidence.md`](31-assistant-consistency-efficiency-gates-evidence.md).
