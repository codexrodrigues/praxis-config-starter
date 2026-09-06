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

## Gate pós-correção: reprovado, publicação pendente

A execução [34014454238](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/34014454238) exercitou o merge #466 `45f57295c9f96ac7d2d5ce146e71b6f04cfd2bec`, source-checkout, API rc.48, Metadata `f43d3c3` e UI `a943602`. O JAR fonte e o aninhado foram idênticos (`4e78ffe2c067cab4b1cdd57afa670a86cbcad6fe56c01aebc5bb1f6339d1159e`); esse JAR não é o rc.150 publicado no Central. Não houve nova publicação.

Resultado: 3 cenários executados, 2 aprovados, 1 reprovado, zero retries Playwright; cleanup verificado. Proveniência e proteção contra interceptação passaram. A jornada de missões não chegou a produzir recibo funcional aprovado. Dois turnos foram registrados; quatro invocações no primeiro e nenhuma no segundo. O planejamento com mini terminou incompleto (640 tokens de saída), e a segunda tentativa terminou em timeout. As fases de ação declarada e intenção rápida tiveram sucesso. Não houve invocação `live_option_refinement`, portanto este gate não comprova nem refuta a correção específica de linhagem.

A falha visível foi um preview não aplicável com confirmações de descoberta de recursos fora do cenário, sem a ação de reparo esperada pelo teste. Isso não autoriza relaxar o gate, selecionar automaticamente um candidato ou contabilizar sucesso com continuação. Custo completo permanece desconhecido.

### Investigação e próximo corte

A auditoria de código encontrou que `planningModel` e `liveOptionRefinementModel` aplicam os modelos por fase somente quando o provider do pedido é explicitamente OpenAI. Já `AiProviderManagementService.generateJson` resolve depois o provider efetivo, incluindo configuração armazenada e default. Isso permite diferença de política entre um pedido explícito e um pedido que herda provider. A evidência sanitizada deste gate não inclui o request, portanto a omissão do provider nesta execução permanece hipótese a provar por regressão local; não é causa raiz comprovada pela telemetria.

Antes de outro gate pago: reproduzir localmente provider explícito versus herdado, preservar a precedência canônica de configuração do gerenciador e garantir que seleção de modelo por fase use o mesmo provider efetivo. Classificar o ajuste e mapear consumidores antes de editar; não corrigir apenas o frontend ou aumentar retries/timeout para mascarar divergência. Depois, executar uma única prova delimitada, publicar Config rc.151/API rc.49 somente com gate aprovado, e repetir a jornada livre no Render.

Landing #220 consolidou a política de conta e a comparação estrutural da limpeza, mantendo ETag e propriedade. Não alterou a UI publicada. Nenhum modelo configurado no Render foi alterado, inclusive para Astra.
