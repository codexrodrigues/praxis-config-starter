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

## Gate real aprovado

[34035509733](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/34035509733)
passou no merge #468 `559a047a4be44563c46bf78147b4e06442afed9e`. Mesmos consumidores
fixados do gate anterior: API `b2b671d`, Metadata `f43d3c3`, Angular `a943602`.
3/3 cenários passaram, zero retries/skips, uma jornada de missões com um turno humano,
nenhuma clarificação/revisão/reparo, nove verificações funcionais de master/detail,
seleção, discovery, comando HTTP200, duplicação HTTP409, atualização e reload.
Cleanup confirmado. Jornada funcional first-pass em 87.612 ms.

Pre-intent usou Luna com sucesso. Ação declarada mini passou, intenção rápida mini
expirou e passe completo mini passou. Quatro invocações; custo total desconhecido.
O timeout não exigiu novo turno humano, mas permanece uma limitação operacional.
Nenhuma invocação de refinamento live-option ocorreu neste cenário: a correção de
linhagem ainda requer a jornada livre no host publicado.

JAR fonte de CI e JAR aninhado idênticos: SHA-256
`fe1dc526776a984ce953e8d41518828e7f9322c1e05bd568995ae06df57c040f`.
Esta evidência é source-checkout e não substitui a identidade do artefato público.
Tag rc.151 criada pela execução `34036122954`, commit
`e1f0426e4b7725c34659e340f86f8e2b024c2477`; publicação `34036155253` e CI `34036155348` aprovados. JAR/POM públicos tiveram SHA-512 conferidos; JAR SHA-256 `1cbcd54312c0db7cb45aa11ee589dd20e3d7261b20f1f91835df5cce02902242`.

## Consumo público no host

Quickstart #272 integrou rc.151 no SHA `893b3cd2a3057bc26073a7f87cb0500ae766b39d`.
`mvn -U verify` contra o Central passou: 548 previstos, 526 executados, 22 skips,
zero falhas/erros e exit 0. O JAR aninhado tem SHA-256
`1cbcd54312c0db7cb45aa11ee589dd20e3d7261b20f1f91835df5cce02902242`, idêntico ao público.
CI do host `34037036192` aprovado: 548 previstos, 540 executados, 8 skips, zero falhas/erros. Preparação oficial `34037734563` em execução para `2.0.0-rc.49`, na mesma linha prerelease 2.0.0; publicação e jornada Render ainda pendentes.

Após validar o código fonte, o cache Maven local de rc.150 foi restaurado com
JAR/POM/sources/Javadoc públicos, todos com SHA-512 verificado. Os artefatos e
recibos locais da prova permanecem separados; builds futuros não herdam o override
fonte sob a coordenada rc.150. O consumidor novo usa rc.151 público.
