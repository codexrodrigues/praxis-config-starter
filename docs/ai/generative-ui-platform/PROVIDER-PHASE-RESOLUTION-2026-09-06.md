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
CI do host `34037036192` aprovado: 548 previstos, 540 executados, 8 skips, zero falhas/erros. Preparação oficial `34037734563` e publicação `34038369120` aprovadas para `2.0.0-rc.49`, na mesma linha prerelease 2.0.0. Tag fonte aponta para `f5315d2094fbd25db3b700969a846c7726fe3035`; tag pública aponta para `a2eeb4dd5fb16f6fdee2d5ebe5bded77c2e42026`. POMs fonte/público confirmam rc.49/Config151. Render confirmou rc.49/UP por HTTP em `2026-09-06T14:14:36Z`, build `2026-09-06T14:11:26.839Z`. ID de deploy no painel não coletado. Jornada livre concluída com bloqueio semântico, conforme abaixo.

Após validar o código fonte, o cache Maven local de rc.150 foi restaurado com
JAR/POM/sources/Javadoc públicos, todos com SHA-512 verificado. Os artefatos e
recibos locais da prova permanecem separados; builds futuros não herdam o override
fonte sob a coordenada rc.150. O consumidor novo usa rc.151 público.

## Jornada livre rc.49: bloqueio operacional preservado

Run `free-599ea931-89d7-4b58-a130-d8ed1924ef8a`, stream
`e4b936cb-1d74-4dc5-98d9-70eeb96bd4c8`: um turno humano, zero retries/continuações,
preview tecnicamente válido e apply negado. Planejamento e refinamento usaram Luna;
`live-option-refinement-scoped-to-constraints` confirmou a reconciliação de linhagem.
Seis invocações de authoring: cinco sucessos e `preview_message` incompleto,
classificado `unknown`. Uso agregado não cobre embeddings e custo total permanece
desconhecido; não há atestado independente do limite de conta.

O evento 34 iniciou `verifyDomainOperation`; o 35 retornou
`operational-grounding-binding-required`. O resultado 44 contém
`semantic-preview-resource-workspace-grounding-required`, `decisionValid=false`,
`requiresReview=true`, `retrievalSource=semantic_retrieval`, schema verificado e
nenhum fallback por palavra-chave. Layout semântico `resource-master-detail`,
componente principal `praxis-table`, layout materializado `master-detail-dashboard`;
`resourceWorkspaceGrounding.status=unavailable`, zero operações verificadas.
A projeção canônica de constraints contém field `name`/operator `contains`; a
correspondência com o filtro runtime ainda não foi certificada.

O catálogo demo/landing permaneceu reconciliado (762 funcionários, 105 cargos,
90 departamentos; 957 total). Isso não comprova bindings de Domain Knowledge:
`AgenticAuthoringDomainBindingService` exige bindings do mesmo escopo, release
corrente do conceito e evidência ativa. A tool retornou ausência de binding elegível;
ainda não foi determinado qual desses requisitos falhou. Não criar binding ad hoc
nem retirar o gate para conseguir aplicar.

Cancelamento HTTP200 confirmou `completed`, os registros próprios 277/278/279 foram
removidos e `cleanupComplete=true`. Nenhum apply, leitura de página persistida,
reload, refinamento posterior ou edição foi certificado. As consultas de recuperação
foram somente leitura, limitadas ao stream, sem prompts, respostas nativas ou chaves.

CI da versão `34038368893` passou. O smoke automático `34038403312` falhou no passo
RH com curl56 (conexão interrompida); o mesmo script `verify-human-resources-runtime.sh`
passou localmente por HTTP no Render, sem LLM ou mutação. Não foi repetido o workflow
completo nem convertido seu resultado histórico em sucesso.

## Próximo passo

Auditar bindings operacionais de `human-resources.funcionarios` no escopo demo/landing,
suas releases/conceitos e evidência ativa; corrigir a fonte canônica ou sua projeção
com o fluxo de publicação existente, depois validar `verifyDomainOperation` por HTTP
antes de outra jornada paga. Reutilizar contratos existentes de Domain Knowledge,
capabilities/actions e schema. Só então repetir a jornada livre delimitada e verificar
filtros/edição. O bloqueio é parte da governança e deve permanecer ativo.

O recibo da landing agora projeta códigos de decisão/falha e IDs de quick reply antes
das asserções, além de `failureKind`/`finishReason` na telemetria sanitizada. Validação:
22 testes Node e TypeScript, sem nova chamada paga. Não há mudança de frontend de produto.

A projeção canônica já existe em `DomainKnowledgeProjectionService` e é condicionada
por `praxis.domain-knowledge.projection.enabled`. `DomainCatalogIngestionService`
a chama também na reingestão de itens existentes quando o serviço está ativo.
A ativação efetiva no Render e o estado dos bindings/conceitos/evidências ainda
precisam ser verificados; não inferir que a flag está desativada apenas pelo bloqueio.
