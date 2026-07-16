# Evidencia de modernizacao Spring AI e OpenAI

Data do corte: 2026-07-16.

## Objetivo

Fechar o inventario tecnico que antecede a modernizacao do provider OpenAI,
atualizar a linha Spring Boot 3.5 compativel e escolher a proxima migracao sem
perder as garantias ja existentes de streaming, cancelamento, telemetria e
fallback.

## Classificacao e mapa de impacto

- classificacao da auditoria: `arquitetural`;
- classificacao deste corte: `transversal`, sem mudanca de contrato publico;
- fonte canonica: `praxis-config-starter`;
- consumidor operacional: `praxis-api-quickstart`;
- consumidores de contrato: `@praxisui/ai` e hosts Angular, sem alteracao neste
  corte;
- OpenAPI, DTOs, eventos SSE, headers e ETag: inalterados;
- risco de breaking change deste corte: baixo, restrito a dependencias;
- risco da migracao seguinte: alto no adapter OpenAI, por envolver transporte,
  streaming e structured output.

## Inventario de aderencia

| Necessidade | Aderencia | Evidencia | Decisao |
| --- | --- | --- | --- |
| Baseline Spring Boot/Spring AI mantido | `suportado-parcialmente` | O projeto estava em Boot 3.5.9 e Spring AI 1.1.1 | Atualizado para Boot 3.5.15 e Spring AI 1.1.8 |
| Serializacao de extensoes OpenAI | `ja-suportado-mal-nomeado-ou-mal-materializado` | Spring AI 1.1.8 achata `extraBody` no request, mas o runtime ainda preservava o bypass historico | Teste black-box passou e removeu a justificativa original do bypass |
| Streaming e cancelamento | `suportado-parcialmente` | O caminho manual fecha o stream e cancela a future; a paridade do framework/SDK ainda nao foi certificada | Preservar temporariamente ate o corpus provar cancelamento, timeout e terminal unico |
| Telemetria de uso | `ja-suportado-mal-nomeado-ou-mal-materializado` | `AiProviderInvocationTrace` ja registra modelo, tokens, cache, latencia e response id, mas fora da observabilidade Spring AI | Exigir paridade no adapter novo antes da troca |
| Structured output estrito | `suportado-parcialmente` | `AiJsonSchema` existe, mas OpenAI recebe `json_object`; o schema e apenas acrescentado ao prompt | Migrar para JSON Schema nativo e manter validacao/repair governados |
| Advisors e RAG no caminho OpenAI | `ja-suportado-mal-nomeado-ou-mal-materializado` | Dependencias e resolucao de advisors existem, mas o HTTP manual nao as executa | Decidir explicitamente quais advisors pertencem ao provider e quais pertencem ao turn engine |
| Responses API | `lacuna-real-de-contrato` | O adapter usa `/v1/chat/completions`; OpenAI recomenda Responses para novos projetos e fluxos agenticos | Implementar como contrato interno do adapter, sem contaminar contratos de authoring ou criar DTO HTTP publico |
| Politica de modelo por tarefa | `lacuna-real-de-contrato` | Default global e heuristicas por nome continuam ativos | Tratar em slice proprio, depois do transporte observavel |

## Evidencia oficial consultada

- A linha estavel compativel com Boot 3.5 e Spring AI 1.1.8; o release inclui
  atualizacoes de dependencias e correcao de seguranca:
  <https://spring.io/blog/2026/06/12/spring-ai-1-1-8-1-0-9-avaialble-now/>.
- Spring AI 2.0.0 e GA, adota Spring Boot 4 e consolida OpenAI sobre o SDK oficial:
  <https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/>.
- A OpenAI recomenda Responses API para projetos novos e documenta melhor
  continuidade, tools e cache em relacao a Chat Completions:
  <https://developers.openai.com/api/docs/guides/migrate-to-responses>.
- Structured Outputs com `json_schema` e preferivel ao JSON mode porque garante
  aderencia ao schema:
  <https://developers.openai.com/api/docs/guides/structured-outputs>.
- O SDK Java oficial atual e 4.43.0 e sua API primaria e Responses, com helpers
  para streaming, acumulacao, Structured Outputs e function calling:
  <https://github.com/openai/openai-java>.
- A orientacao de modelo corrente e GPT-5.6, mas a propria OpenAI recomenda
  comparar configuracoes em workloads representativos; portanto este corte nao
  mudou o modelo padrao:
  <https://developers.openai.com/api/docs/guides/latest-model>.

## Matriz de decisao

| Opcao | Vantagem | Limitacao | Decisao |
| --- | --- | --- | --- |
| Spring AI 1.1.8 `OpenAiChatModel` | Menor mudanca e recupera observabilidade/advisors | Permanece em Chat Completions; paridade de cancelamento e telemetria precisa ser provada | Baseline de comparacao |
| Modulo Spring AI 1.1 `openai-sdk` | Usa SDK oficial dentro da linha Boot 3.5 | Continua centrado em Chat Completions e fixa SDK mais antigo que o oficial corrente | Nao promover como destino permanente |
| SDK oficial OpenAI 4.43 no adapter | Responses, Structured Outputs, streaming helpers e superficie atual | Exige adapter explicito e integracao propria com observabilidade/cancelamento | Proximo spike recomendado |
| Spring AI 2.0 + Boot 4 | Fundacao framework mais atual e SDK oficial consolidado | Migracao transversal de Boot, Jackson, starters, providers e hosts | Spike arquitetural separado |

## Mudancas executadas

- Spring Boot `3.5.9` -> `3.5.15`;
- Spring AI `1.1.1` -> `1.1.8`;
- comentario do bypass atualizado para declarar a contingencia e seus criterios
  de remocao;
- teste black-box adicionado para provar que `extraBody` e achatado no payload e
  que `extra_body` nao vaza como propriedade top-level incorreta;
- `DatabaseConnectionTest` reduzido ao escopo que realmente exercia, sem subir
  todo o starter para testar uma conexao e um repository simulados;
- teste SSE estabilizado para aguardar a persistencia de um terminal real antes
  do replay, com timeout explicito de cinco segundos e sem aceitar fluxo
  incompleto.

## Validacao

- `SpringAiOpenAiCompatibilityTest`: 1/1;
- `SpringAiOpenAiServiceTest`: 11/11;
- `AiProviderRouterTest`: 8/8;
- `AiContractV11RetroCompatibilityTest`: 6/6;
- total focal observado: 26 testes, zero falha.
- gate focal de convencao, compatibilidade e fixture: 6 testes, zero falha;
- `AgenticAuthoringTurnStreamHttpSseIntegrationTest`: 6/6;
- suite completa do starter: 2.053 testes, zero falha, zero erro e 4 ignorados;
- arvore Maven confirmou todos os modulos Spring AI resolvidos em `1.1.8`;
- instalacao Maven local do starter `0.1.0-rc.82`: concluida;
- consumidor `praxis-api-quickstart`:
  `AiPatchSchemaResolutionIsolatedIntegrationTest` e
  `SecurityConfigAiPatchPolicyTest` verdes contra o artefato local;
- empacotamento do `praxis-api-quickstart` com testes desabilitados: concluido.

O teste de compatibilidade usa servidor HTTP local, sem credencial ou chamada a
provider externo. Ele prova que a causa historica do bypass foi corrigida, mas
nao prova ainda equivalencia integral de streaming, cancelamento, retries,
advisors e telemetria.

O teste downstream em H2 registrou warning de captura de observabilidade porque
a fixture isolada nao cria `ai_assistant_observation`; o servico tratou a
captura como nao bloqueante e o gate terminou com sucesso. Nao houve chamada a
provider externo nem alteracao no quickstart.

## Criterio do proximo corte

O adapter candidato so substitui o caminho manual se passar, no mesmo commit:

1. texto sincrono e erro normalizado por status;
2. JSON Schema estrito, recusa e resposta incompleta;
3. streaming incremental com fechamento de recurso;
4. cancelamento antes e depois do primeiro chunk;
5. tokens de entrada/saida, cache read/write, modelo e response id;
6. timeout e retry sem duplicar side effect;
7. perfis `must-pass` e `extended` sem regressao relevante de assertividade,
   P95 ou custo por jornada.

Como a plataforma esta em beta, o caminho vencedor deve substituir o manual;
nao sera criada uma flag permanente nem uma trilha `v1`/`v2`.
