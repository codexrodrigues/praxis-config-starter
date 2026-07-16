# Assistant consistency — provider/phase telemetry — 2026-07-16

## Resultado

O Config Starter agora preserva telemetria sanitizada por invocacao LLM durante
a resolucao semantica do Page Builder. O corte reutiliza
`llmDiagnostics.resolutionTelemetry`; nao cria endpoint, envelope SSE, estado
global ou contrato paralelo no frontend.

As fases inicialmente certificadas sao:

- `platform_guidance_confirmation`;
- `intent_fast`;
- `intent_full`.

Cada item registra tentativa, provider/modelo efetivos, transporte, latencia,
status, classe de falha e, quando disponiveis, tokens de entrada, saida, cache e
total. Prompts, completions, credenciais, headers e payloads nativos nao fazem
parte do snapshot.

## Inventario de aderencia

| Necessidade | Classificacao | Resolucao |
|---|---|---|
| `llmDiagnostics.resolutionTelemetry` | `ja-suportado-mal-nomeado-ou-mal-materializado` | A superficie existente recebeu a projecao opt-in |
| provider/modelo e latencia | `suportado-parcialmente` | O boundary ja conhecia esses valores, mas os descartava antes do resultado |
| usage/cache do provider | `lacuna-real-de-contrato` | Um trace efemero request-scoped preserva metadados sem `ThreadLocal` nem estado compartilhado |
| telemetria de plan/preview/copy | `suportado-parcialmente` | Continua como proximo corte; esta fase certifica primeiro o roteamento semantico |
| pricing/custo auditavel | `lacuna-real-de-contrato` | Adiado ate existir snapshot versionado de preco/modelo |

## Fronteira implementada

- `AiProviderInvocationTrace` vive apenas durante uma chamada e e carregado por
  `AiCallConfig` ate o adapter.
- `AiProviderManagementService` preserva o trace ao resolver configuracao
  efetiva de tenant/usuario/ambiente.
- OpenAI HTTP captura `usage.prompt_tokens`, `completion_tokens`,
  `prompt_tokens_details.cached_tokens`, total, modelo, response id e finish
  reason.
- Gemini HTTP captura `usageMetadata`, inclusive cached content; o caminho SDK
  preserva `ChatResponseMetadata` e `Usage` do Spring AI.
- O resolver mantem as tentativas em ordem e o diagnostico limita a projecao a
  12 itens.

O provider trace nao e uma nova fonte de verdade da decisao. Ele apenas explica
como a decisao semanticamente governada foi processada.

## Evidencia de consistencia

Uma falha simulada de quota prova que `intent_fast` e `intent_full` aparecem
como tentativas separadas, ambas com `status=failure` e
`failureKind=quota-exhausted`. Antes deste corte, o consumidor via somente a
resposta segura generica e nao conseguia localizar a fase perdida.

Os testes de adapters tambem certificam:

- OpenAI direto preservando input/output/cache/total sem copiar prompt ou
  completion;
- Gemini via Spring AI preservando `ChatResponseMetadata` e `Usage`;
- agregado opt-in com contagens, latencia e tokens;
- ausencia explicita de payload sensivel no diagnostico.

## Validacao executada

| Gate | Resultado |
|---|---:|
| Intent resolver + diagnostics + adapters OpenAI/Gemini | 265 testes verdes |
| Turn Engine | 148 testes verdes |
| `AiProviderManagementService` | 7 testes verdes |
| `mvn -B -P ci-smoke-unit -T 1C clean verify` | 1.963 testes verdes; build, source e javadoc JARs verdes |

O quickstart e o Angular nao precisaram de alteracao: nenhum endpoint, DTO HTTP,
envelope SSE ou binding publico mudou. A prova downstream completa fica para o
corte que publicar metricas terminais ou alterar a versao do SDK.

## Baseline de SDK

O `pom.xml` continua em Spring AI `1.1.1`. A documentacao oficial atual do
Spring AI confirma que `ChatResponse`, `Usage`, cache usage e as metricas
`gen_ai.*` sao superficies nativas. O upgrade de dependencia nao foi misturado
neste corte: ele exige uma comparacao isolada do adapter OpenAI, hoje em bypass
HTTP por causa de uma incompatibilidade historica de `extra_body`, seguida de
smoke real de structured output, streaming, cancelamento, RAG e observabilidade.

## Proximos passos

1. propagar o mesmo trace para `pre_intent`, `plan`, `preview_message` e demais
   chamadas LLM do turno, agregando o custo terminal completo;
2. publicar metricas Micrometer de baixa cardinalidade por provider/modelo,
   fase e status, sem prompt/completion;
3. criar snapshot versionado de pricing/modelo para custo auditavel;
4. executar o spike separado de Spring AI atual versus `1.1.1`, removendo o
   bypass OpenAI se as provas funcionais e de observabilidade forem verdes;
5. usar a evidencia por fase para corrigir as jornadas Flow 1 e PR7 ainda
   inconsistentes, sem adicionar heuristica textual.
