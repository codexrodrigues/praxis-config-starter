# Prova do corte publicado de authoring

Classificação deste registro: `docs-apenas`. A implementação transversal permanece na fonte
canônica Config; não há contrato, modelo ou artefato estrutural novo nesta consolidação.

## Backend publicado e jornada canônica

Config `0.1.0-rc.149` foi publicado pelo workflow 34006472352 a partir da tag em
78109b4e54a2dab880ced2c62e6de2b712e0bca7. Quickstart `2.0.0-rc.47` foi preparado/publicado
pelos workflows 34007755714/34008247036. A tag fonte aponta para
50d096bf7aa85b34dd8a6f13cb962dbe947bd11d; a pública para 7ba8d7d2c9de61482ac8da46be443e91021ffcae.
Ambos os POMs fixam Config 149. O modelo continua `gpt-5-mini`; não houve troca para Astra.

O [gate pós-publicação 34008305555](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/34008305555)
passou **3/3 testes, first-pass, um pedido humano, zero retries Playwright**, sem clarificação,
revisão corretiva ou reparo determinístico. Nove pós-condições passaram: master/detail, propagação
de seleção, discovery de actions/capabilities, comando HTTP 200, duplicação HTTP 409, refresh e reload.
O payload e o ETag persistidos coincidem com o reload. Cleanup confirmado; nenhuma interceptação crítica.

O JAR Maven Central 149 e o JAR aninhado no Quickstart possuem o mesmo SHA-256
`71c905af2b7a8d7f265d14f30484c0718275d69c0774fc2e044c6b0c002ecfe1`.
Ao contrário do gate anterior de source-checkout, esta execução comprova o artefato publicado,
incluindo as correções adicionais de interrupção da worker e lookup de configuração.
Registry 24 sem degradação, RAG 460/460 e pgvector prontos.

Primeiro status útil em 45.712 ms, terminal aplicável em 46.324 ms e jornada completa em 76.603 ms.
Esses tempos são desta execução; a comparação com o run anterior não é um benchmark controlado.
A fase fast completou em 8.314 ms e a full não foi necessária. Persistem uma resposta pre-intent
incompleta em 8.125 ms (640 tokens de saída) e uma repetição interna que expirou em 4.029 ms.
Declared action completou em 2.860 ms. Zero retry do teste não significa zero retry interno.

As três respostas com usage somam 19.982 tokens de entrada, 2.015 de saída e 11.776 de cache-read.
Uma chamada sem resposta não informou usage: custo total permanece desconhecido, e embeddings não
estão nesse ledger. O limite da conta continua atestado pelo usuário, sem verificação independente.
O próximo ajuste de eficiência deve tratar a capacidade de saída e a repetição pre-intent na fonte
existente; não aumentar deadlines nem remover grounding para fazer o gate passar.

## Render e consumidor público

O deploy Render `dep-daedibk9v7es73b3m130` do commit da tag 47 foi observado como
`Deploy succeeded | Live`. Em 2026-09-06T03:14:39Z, `/actuator/info` retornou `2.0.0-rc.47`,
build 03:08:49Z; health `UP`; status Config confirmou OpenAI, `gpt-5-mini`, chave disponível e
origem `env`, com Origin real `https://praxisui.dev`. Nenhum valor de credencial foi publicado.
O aviso de pagamento do Render continua visível, mas não bloqueou esse deploy.

Landing #214 foi integrada em bb2524a7baaec2911413f03eb941a302dcda9665 após CI 34006902047;
o CI de main 34008293159 também passou. O deploy 34009416445 publicou essa revisão no Firebase.
Os smokes hospedados e o canário livre têm seus estados registrados no recibo ao fechar a execução.
As libs públicas do consumidor são 9.0.65; o gate canônico acima conserva UI a9436025 (9.0.64).
Não atribuir a essa prova a certificação de outra versão de UI ou da matriz livre.

A validação local do consumidor incluiu build, 74 documentos, nove exemplos, seis casos Chromium
sem retry e 12 testes de controle de canário. O CI da PR registrou duas instabilidades recuperadas
por retry: prontidão do preview após handoff e navegação inicial para o Studio, ambas com asserções
de 5 s. Isso não foi omitido nem convertido em prova de primeira tentativa dessas duas interações.

O [recibo sanitizado](PUBLISHED-PROVIDER-CUT-2026-09-06.receipt.json) distingue publicação,
provas controladas, gate pago canônico e jornada livre no Render. Nada neste corte certifica
“qualquer componente”: consulta aberta, refinamentos arbitrários, coluna calculada, renderer,
ocultação, navegação e edição profunda continuam exigindo seus próprios oráculos funcionais.

## Jornada livre no Render: resultado e remediação

O deploy 34009416445 terminou com todos os smokes hospedados aprovados. A jornada livre de
funcionários **não foi certificada**. A primeira execução parou antes de registros/inferência porque
o canário assumia o cookie default `SESSION`; o host publicou o nome configurado `PRAXIS_HEROES`.
Login e session retornaram 204. O coletor foi corrigido para a identidade HttpOnly realmente emitida,
com conferência de domínio/path/flags e sem persistir o valor da sessão.

A execução seguinte criou três registros próprios e admitiu um pedido. O backend terminou em
27,786 s, `DONE`, com `canApply=false`; não houve página aplicada. Os diagnósticos apontaram
`llm-pre-intent-query-constraints-not-preserved`, foco de recurso ainda não confirmado e
`metadata-probe-not-run`. O candidato era funcionários, com semantic retrieval e actions ainda
pendentes de probe. A resposta ao usuário recusou materialização sem grounding confirmado.

O antigo coletor perdeu o body do EventSource encerrado pela UI e aguardou seis minutos. O cancel
retornou 403 porque faltou `streamAccessToken`, obrigatório no modo assinado. Todos os registros
sintéticos (268/269/270) foram removidos. Uma consulta somente leitura por **esse único stream ID**
no config-store confirmou o resultado persistido e o status DONE; não havia stream ativo.
O recibo original continua reprovado. A recuperação é evidência independente de encerramento,
não reescrita do cancelamento HTTP nem sucesso funcional da jornada.

Foram observadas três chamadas bem-sucedidas: pre-intent `gpt-5.6-luna` (7.643 ms), declared action
mini (2.672 ms) e fast mini (11.765 ms). Essa é a separação de modelos por fase já documentada
no owner; não houve alteração do modelo do Render. Entrada 15.341, saída 2.264, cache-read 2.816.
O snapshot do canário só cobria mini e cache-write não foi informado: custo total permanece
desconhecido. A [documentação de Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna)
também exige considerar cache-write e faixa de contexto; não se inventou custo para essas dimensões.

### Inventário operacional e correção

A consulta HTTP demonstrou **zero releases em `demo/landing`**, escopo real do frontend,
enquanto `default/dev` tinha 21. Aderência `ja-suportado-mal-nomeado-ou-mal-materializado`:
o metadata starter já publica o catálogo, mas ele não estava materializado no escopo consumidor.
Correção transversal operacional: Config persiste/publica RAG; Quickstart fornece `/schemas/domain`
e o script oficial; Landing consome a projeção. Sem contrato, binding manual, alteração de modelo,
mudança de permissões ou novo artefato npm/Maven. Docs operacionais e guidance de jornada atualizados.

O script `ensure-domain-catalog-context.sh` publicou funcionários, cargos e departamentos usando
os headers `demo/landing` e Origin oficial. RAG confirmou `PUBLISHED` e 762/762 + 105/105 + 90/90
documentos. Uma segunda execução do script encontrou os três recursos `ready`, sem reingestão.
Isso corrige uma causa operacional comprovada, mas não prova isoladamente preservação de filtros
ou que não exista outro problema de grounding. Nenhuma nova chamada de authoring foi feita após essa correção.

O canário agora exige catálogo não vazio/reconciliado no escopo real e modelo/preço de planejamento
separado do modelo geral, antes de fixtures/inferência. Lê o replay canônico do mesmo stream, mantém
o token assinado em memória para leitura/cancelamento e registra IDs/diagnósticos antes das asserções.
Passaram **17 testes Node e TypeScript**; o canário sem opt-in foi skipped. O novo coletor não recebeu
uma certificação paga. Próxima rodada: fechar a cobertura de preço/uso por fase no contrato existente
e executar uma única jornada delimitada sobre o catálogo corrigido, sem afrouxar os oráculos.

O [recibo da jornada livre](RENDER-FREE-JOURNEY-2026-09-06.receipt.json) conserva os dois resultados,
a recuperação e o estado reconciliado do catálogo. O guidance canônico de jornadas foi espelhado
diretamente na skill instalada; sync/bootstrap não existem neste workspace. A skill específica de
grounding também não possui cópia canônica neste checkout, portanto não foi criada uma cópia paralela.
