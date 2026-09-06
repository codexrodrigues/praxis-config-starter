# Provider efetivo e modelo por fase — 2026-09-06

## Inventário e impacto

Classificação: contrato Java transversal. Aderência: `suportado-parcialmente` no authoring; `lacuna-real-de-contrato` no override interno de chamada. O gerenciador já conhece a configuração salva, o default e os aliases. O `model` escalar de `AiCallConfig` não representava a política por provider antes da resolução; os serviços escolhiam pelo provider bruto do pedido. Não é necessário novo endpoint, DTO HTTP ou estado de UI.

Fonte canônica: `AiProviderManagementService` resolve provider e configuração uma vez; `AiCallConfig.providerModelOverrides` transporta somente a política definida pelo serviço backend. Consumidores diretos: planejamento pre-intent e refinamento live-option. JSON/texto compartilham a resolução; ausência de override preserva o comportamento geral. Política OpenAI não alcança Gemini. Os valores de modelo configurados permanecem inalterados.

O novo campo é ignorado por Jackson nos dois sentidos. Não é permissão recebida do frontend nem telemetria usada para decidir execução. A telemetria continua recebendo o modelo efetivamente selecionado. OpenAPI, contratos SSE, tipos Angular, manifests e corpus HTTP não precisam de atualização. O contrato Java do construtor completo de `AiCallConfig` muda em beta; os consumidores do workspace usam builder e precisam de recompilação contra o corte novo. Não há contrato HTTP breaking.

## Reprodução

Um teste usando planejador e gerenciador reais, com adapter determinístico, foi executado antes da correção. Das quatro variantes (null, vazio, OpenAI explícito, alias), três falharam: esperavam o modelo de planejamento Luna e receberam o modelo geral mini. OpenAI explícito passou. Essa prova confirma a divergência de código; não recupera o request ausente do gate histórico nem transforma sua causa provável em fato observado.

## Validação

333 testes focais aprovados (21 gerenciamento, 38 planejamento, 49 resolução, 203 motor, 12 continuidade, 9 roteador, 1 serialização), zero falhas ou skips. `mvn install` empacotou o starter, incluindo fontes/Javadoc. Quickstart (`39a4c08`, versão local rc.47, dependência Config150) passou `mvn verify`: 548 previstos, 526 executados, 22 skips, zero falhas/erros; Maven exit 0. Surefire precisou encerrar a JVM após 30s de shutdown e registrou aviso; não houve falha de teste. JAR aninhado idêntico ao fonte local, SHA-256 `64f39e5e180df25649acc8ee94de1dad2489ea3851b32c35fe1620ec99518545` (não é o Central rc.150). Depois, um gate pago delimitado no checkout fixado, sem aumentar retries ou timeouts. Publicação somente após aprovação funcional. O gate anterior continua reprovado.

O guidance canônico `codex-skills/praxis-generative-ui-authoring/references/pilot-scenarios.md`
foi atualizado com as variantes de provider e espelhado na instalação local. Os scripts
sync/bootstrap não existem neste checkout; sincronização restrita ao arquivo de referência.
