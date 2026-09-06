# Refinamento de campos — investigação live em 2026-09-06

## Corte publicado e validação

Config `rc.150` foi publicado pela execução `34012131367`, tag `c50230f0f85840b290ee742c196d98dcd321588b`, após gate determinístico `34011912895` (provider não utilizado). CI `34012131731` passou. JAR SHA-256 `29926ea7efd2eb20e6d7c512238116ed5032901fee06875a20dc2e75438dd1b6`; SHA-512 público do JAR/POM conferido e JAR aninhado no host idêntico.

Quickstart #271 integrou a dependência. `verify` local: 548 previstos, 526 executados, 22 ignorados (16 Docker, 6 opt-in), zero falhas. CI `34012769698`: 540 executados, 8 ignorados, zero falhas. Preparação `34013173923` e publicação `34013635445` passaram; tag fonte `v2.0.0-rc.48`, SHA `b2b671d1408aa4f5fcecd6559a7bb37aee81d11f`; commit público `2593c4408c78ad4090fd02f1cb5497ce7b33f6b1`. POM público confirma rc.48/Config150.

Render respondeu rc.48/UP em `2026-09-06T05:20:58Z`, build `2026-09-06T05:18:15.378Z`. Identidade verificada por HTTP público; ID do deploy no painel não foi coletado. CI da versão `34013635663`, Domain Catalog Runtime Smoke `34013663604` e Sync Published Domain Catalog `34014148574` passaram. O smoke automático de catálogo não substitui o preflight no escopo da landing.

Landing de produto permanece `bb2524a7`, deploy `34009416445`, libs 9.0.65. #218 integrou perfis separados e política de custo; #219 acrescentou marcos funcionais e exclusões após reload. 20 testes Node e TypeScript passaram. A autorização por limite de conta admite somente os três turnos delimitados e preserva custo desconhecido; não é limite numérico verificado ou fatura conciliada.

## Jornada observada

Um pedido humano, zero retries Playwright, zero clarificações automáticas, sem interceptação. Catálogo `demo/landing` reconciliado: 957/957 documentos. A primeira resolução permitiu `component_authoring`; após refinamento de campo, o turno desviou para `advisory_authoring`, terminando como `explain/component/answer_component_catalog_question`, `canApply=false`.

A fase `live_option_refinement` agora identifica corretamente Luna. Seis invocações observadas, cinco sucessos e uma falha de intenção rápida sem uso disponível. Custo total permanece desconhecido; nenhuma troca para Astra e nenhuma chamada paga adicional para recuperar evidências. Replay HTTP funcionou, cancelamento confirmou `completed` e todos os três registros próprios foram removidos. Nenhum apply, readback de materialização, reload ou edição foi certificado.

A consulta SQL somente leitura foi limitada ao stream `81813cdd-7893-4187-98fc-d9016b22fac6` e projetou apenas tipos/rotas/tuplas/campos canônicos: evento 25 `component_authoring/canMaterialize=true`; evento 38 `advisory_authoring/canMaterialize=false`; evento 40 resultado consultivo. Não exportou prompts ou respostas nativas.

## Inventário e correção

Classificação transversal; aderência `ja-suportado-mal-nomeado-ou-mal-materializado`. Config já possui `preserveLiveOptionRefinementLineage`, usado no refinamento de valores para preservar operação, artefato, recurso e visualização da decisão previamente resolvida pela LLM. O refinamento de campos não chamava essa reconciliação e podia reabrir a intenção primária.

A correção reutiliza o mesmo helper após a resposta do refinamento de campos, antes da validação dos predicados e do roteamento. Não muda modelos, prompts, DTOs, endpoints, budgets, eventos ou regras por palavra-chave. Respostas não resolvidas continuam sujeitas aos bloqueios existentes; predicados não preservados continuam bloqueados. Não cria permissão por texto nem converte falha do provider em apply.

Regressão de execução do motor falhou antes da correção porque o preview não era chamado. Após a correção, 203 testes do motor, 46 do resolvedor e 12 de continuidade passaram (261 distintos). A prova é local e determinística: a correção ainda precisa do gate e da publicação próprios antes de nova certificação no Render.

Consumidores: todos os clientes de authoring recebem a decisão original reconciliada pelos contratos existentes. Artefatos derivados: runbooks/evidências e guidance da skill. OpenAPI, tipos Angular, barrels, manifests e corpus HTTP não mudam. O recibo live permanece reprovado; os testes locais não o reescrevem como sucesso.
