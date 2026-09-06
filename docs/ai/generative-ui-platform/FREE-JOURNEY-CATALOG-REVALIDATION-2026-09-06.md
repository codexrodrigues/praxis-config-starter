# Revalidação livre após publicação do catálogo — 2026-09-06

## Resultado observado

Uma execução Chromium contra `https://praxisui.dev` e Quickstart `2.0.0-rc.47` no Render, Config `0.1.0-rc.149`, sem interceptação, sem retry do teste e sem mudar modelos. Um pedido humano admitido; nenhum esclarecimento automático e nenhuma segunda chamada de usuário.

O catálogo `demo/landing` passou o preflight: funcionários 762/762, cargos 105/105 e departamentos 90/90. A LLM produziu `canApply=true`, `preview.valid=true`, recurso funcionários correto e `keywordFallbackApplied=false`. Isso prova a criação de preview governado; não prova aplicação, persistência ou funcionamento da tela.

O teste interrompeu antes de aplicar: uma invocação de refinamento Luna estava rotulada `intent_fast`, contrariando o perfil que o canário verificava. Também houve uma invocação sem contadores e todas as escritas em cache permaneceram desconhecidas; o custo total não pode ser estimado com honestidade.

Seis invocações observadas: planejamento Luna (sucesso), ação declarada mini (sucesso), intenção rápida mini (falha com uso desconhecido), intenção completa mini (sucesso), ação declarada mini (sucesso), refinamento Luna registrado como intenção rápida (sucesso). O modelo geral permaneceu `gpt-5-mini`. Não houve troca para Astra.

Replay HTTP coletou o terminal sem falhas de observação. Cancelamento do stream já encerrado retornou HTTP 200, `terminalState=completed`. Os três registros sintéticos 271–273 foram removidos com conferência de propriedade e ETag. `cleanupComplete=true`, `journeyPassed=false`. A consulta SQL somente leitura por stream confirmou `DONE`; não foi necessária para substituir a prova HTTP.

## Inventário, impacto e correção

Classificação: transversal, com correção de valor diagnóstico público. Aderência: `ja-suportado-mal-nomeado-ou-mal-materializado`. O resolvedor já distingue `liveOptionRefinement` e seleciona o modelo configurado; a projeção da fase omitia essa distinção. Não há lacuna de intenção, DTO ou endpoint.

Fonte: Config `AgenticAuthoringLlmIntentResolverService`. A fase de refinamento passa a `live_option_refinement`; as demais continuam iguais. Consumidores: telemetria, métricas e canário Landing. Risco: consumidores que agregavam refinamento em `intent_fast` precisam aceitar o valor correto. Tipos já admitem string; nenhuma fachada ou rota nova. Docs operacionais atualizadas; exemplos visuais, corpus HTTP e manifests de componentes não mudam.

O canário exige modelo de refinamento explicitamente revisado (`PRAXIS_FREE_AUTHORING_EXPECTED_REFINEMENT_MODEL`), separado dos modelos geral e de planejamento. Não aceita Luna em qualquer fase indistintamente. Ausência de contador de escrita em cache deixa custo desconhecido, em vez de assumir zero.

## Preços e limite de evidência

Snapshot existente atualizado com preços-base de Luna consultados em 2026-09-06: entrada 0,20, cache lido 0,02 e saída 1,20 USD/milhão. Mini permanece 0,25/0,025/2,00. Fontes oficiais: [Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna) e [mini](https://developers.openai.com/api/docs/models/gpt-5-mini).

O contrato v1 ainda não representa o adicional de escrita em cache de Luna nem a faixa acima de 272 mil tokens. Estes preços-base não certificam custo integral nessas condições. O adaptador Responses atual não projeta contador de escrita; ausência continua desconhecida. Não inferir ausência de cobrança. Limite de conta informado pelo usuário, sem verificação independente; embeddings não incluídos e nenhuma reconciliação de fatura.

## Validação e próximo corte

46 testes focais do resolvedor passaram, incluindo a identificação explícita do refinamento; 18 testes Node do canário e sua compilação TypeScript passaram. Não houve nova execução paga após essa correção. A mudança de fase está local, ainda não publicada no Render; o recibo preserva a execução anterior à correção.

Antes de certificar a jornada completa: publicar a identificação correta da fase, fechar a representação canônica das dimensões tarifárias/uso quando disponíveis e então executar um único canário com aplicação, refinamentos, reload e edição. Preview válido não equivale a certificação universal de componentes.
