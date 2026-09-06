# Revisão das jornadas humanas do assistente Praxis

## Escopo e decisão

Revisão transversal solicitada pelo usuário em 2026-09-05. Objetivo: conversar sobre o negócio,
descobrir como representá-lo, criar componentes e editar uma composição existente preservando os
requisitos anteriores. Modelo do produto permanece `gpt-5-mini`; nenhuma troca para Astra.
O limite de gasto da conta OpenAI é declarado pelo usuário, não verificado independentemente.

Este inventário não certifica “qualquer componente”. Manifesto disponível, plano compilado,
interação determinística no navegador e jornada com LLM real são quatro evidências distintas.
As frases abaixo são roteiros de avaliação, nunca regras de roteamento de intenção.

## Plano e mapa de impacto

1. Revalidar a correção de continuidade em `66491255374553730a2ceee49bf30ec6afe9abc0` no gate
   canônico com provider real, três interações humanas no máximo e zero retries Playwright.
2. Mapear cada pedido ao contrato existente; rever testes, execução HTTP, renderização e negativos.
3. Reproduzir formulário e dashboard em dois domínios sintéticos, teclado, desktop e narrow.
4. Registrar as lacunas pelo primeiro owner que perde informação. Só promover release após o
   gate real, sem declarar cobertura universal a partir de uma jornada de missões.

| Fronteira | Impacto |
| --- | --- |
| Config | Decisão semântica, continuidade, seleção de operação, materialização e validação |
| Metadata | Fonte de campos, escrita permitida, operações, filtros, surfaces e capabilities; sem mudança nesta revisão |
| Angular | Runtime oficial, manifests de 24 superfícies, edição delegada e observação funcional |
| API Quickstart | Host real do gate; Render precisa consumir o artefato publicado correspondente |
| Landing | Composer livre, preview/apply/readback e browser com libs públicas 9.0.64 |
| Docs/corpus | Este inventário de evidência; não há novo DTO, endpoint, manifesto ou DSL |
| Validação | Gate pago canônico separado de quatro testes browser determinísticos; análise de source focal |
| Breaking change | Nenhum neste registro de revisão |

## Inventário de aderência

`suportado-parcialmente` abaixo pode indicar uma lacuna de execução/certificação ponta a ponta,
não necessariamente ausência do contrato. Não justifica automaticamente um contrato novo.

| Jornada em linguagem de negócio | O que já existe e owner | Aderência e lacuna | Oráculo funcional obrigatório |
| --- | --- | --- | --- |
| “O que posso fazer com estes dados? Como representar este processo?” | Config: `platform_guidance`, `api_catalog_guidance`, consultative retrieval e filhos de decisão persistidos | `suportado-parcialmente`: contrato e testes de continuidade existem; esta revisão ainda não executou consulta aberta com LLM real | Resposta baseada no domínio/registry; consulta não aplica; opção executável aponta a filho emitido pelo servidor |
| “Quais recursos tenho para um dashboard? Monte uma visão por categoria” | Config visualization decision + GenericUiCompositionPlanProvider; Charts métricas/dimensões e Core composição | `suportado-parcialmente`: dashboard em dois domínios funciona com semântica controlada; criação livre real deste caso permanece sem certificação | Agregação/categoria corretas, dados reais, componentes pedidos, preservação de exclusões |
| “Clique no grupo para ver os funcionários” | Charts crossFilter; composição, queryContext, lista/tabela e surface modal/drawer | `suportado-parcialmente`: clique, filtro e modal observados nesta revisão; drawer de salário e acessibilidade do ponto do gráfico não certificados | Requisição filtrada e registros corretos; retorno/fechamento preservam contexto |
| “Crie um formulário para alterar o nome desta pessoa” | Metadata operação/schema/availability; CRUD descobre edição governada; prompt distingue seleção, escrita e prefill | `suportado-parcialmente`: não confundir criação POST com edição de entidade; sem prova desta solicitação livre específica | Resolver identidade sem ambiguidade; conferir campo gravável e operação; revisar valor; mutation e readback da mesma pessoa |
| “Quero um formulário somente com estes campos” | `minimal-form-plan.v1` já possui `fields`; Dynamic Form possui configuração/layout/metadata de campos | `ja-suportado-mal-nomeado-ou-mal-materializado`: criação determinística enumera todos os editáveis; compilador não projeta a seleção de `fields` | Campos selecionados e required reconciliados com schema, sem omissão silenciosa; campo extra ausente do DOM e payload; reload preserva seleção |
| “Liste funcionários desta categoria e sugira filtros” | Lista `data.resource.bind`, `data.query.set`; Config queryConstraints e grounding de campos/option values | `suportado-parcialmente`: materialização de lista e testes existem; cenário livre filtrado com sugestões não certificado nesta revisão | Campo/valor canônicos, filtro enviado e dados respeitando o recorte; sugestão não muda o filtro sem escolha |
| “Nesta tabela já renderizada, mostre o dado como moeda ou badge” | Table `column.format.set`, `column.renderer.set`; Config ComponentEditPlanService/PreviewService | `suportado-parcialmente`: operações e testes de refinamento existentes; não executados com LLM real neste corte | DOM usa renderer correto; tabela, linhas, filtros e colunas alheias preservados após apply/reload |
| “Crie uma coluna calculada e esconda a coluna anterior” | Table `column.computed.add`, `column.computed.configure`, `column.visibility.set`; validadores de expressão | `suportado-parcialmente`: não confundir existência de operação com execução por conversa | Valores calculados conferidos em linhas distintas, coluna oculta no DOM, campos-fonte preservados, sem redefinir regra de negócio |
| “Adicione um botão para outra página” | Table row actions + GlobalAction canônica; navegação exige destino registrado pelo host | `suportado-parcialmente`: revisão estática; sem prova de destino real neste corte | Botão visível/acionável, destino governado e identidade corretos; rota ausente bloqueia, nunca inventa URL |
| “Abra os detalhes salariais em modal ou gaveta” | Metadata surfaces/actions; Table `rowAction.add`; Dialog/Core/CRUD adapters de apresentação | `suportado-parcialmente`: modal de drill-down observado; relação salarial e drawer específicos não certificados | Fecha dependências da action; abre surface correta com id do empregado, autorização/campos da relação salarial, foco/Escape/retorno |
| “Edite uma tabela dentro de um formulário já montado” | Page Builder `childOperation.delegate`, target/nestedPath, manifests dos filhos | `suportado-parcialmente`: nested workspace existe; alvo profundo arbitrário requer prova focal | Altera só o alvo correto; preserva formulário pai, bindings, submit e demais widgets; ambiguidade esclarecida |

## Achados priorizados

### P0 — Recuperação executável após falha semântica do provider

A execução live `33999199428` reproduziu falha nas fases `pre_intent_tool_plan`, `intent_fast` e
`intent_full`, com classificação insuficiente (`unknown`/`unknown-error`). Uma chamada separada
`declared_client_action_intent` completou com `gpt-5-mini-2025-08-07`. Isso não prova outage, quota ou
credencial inválida; a causa do erro inicial continua indeterminada.

Depois da falha, o resolver publicou quatro opções vindas de broad discovery (`context`, `tenants`,
`security-events`, `navigation`) sem intenção primária resolvida. O teste selecionou uma dessas
opções e chegou a uma `single-table-page` de `/api/praxis/runtime/context`, divergente do pedido de
master-detail de missões. O oráculo bloqueou antes do apply. A limpeza foi verificada.

Classificação: `ja-suportado-mal-nomeado-ou-mal-materializado`, falha de `decision-continuity` após
falha de `intent`. O Config já possuía terminal de erro com `retry-semantic-resolution` e `revise`;
a lista genérica de candidatos estava impedindo seu uso. Corrigido no resolver: falha primária de
provider não publica quick replies executáveis; candidatos permanecem evidência diagnóstica.
A mensagem/pergunta é preservada e o turn engine oferece a recuperação existente, sem nova
inferência automática. Nenhum contrato novo, filtro textual de recurso ou mudança de modelo.

Regressão negativa reproduziu exatamente as quatro opções indevidas; após o ajuste, **493 testes
focais passaram** (resolver 265, engine 202, continuidade 12, vertical 2, portfolio 4, policy 8).
A nova correção está validada localmente, ainda sem novo gate live. A execução falhada não confirma
nem refuta a correção anterior de preservação do layout: ela não chegou à mesma condição inicial.
Release do Config e deploy da Landing continuam pendentes do gate real aprovado.

### P1 — Oráculo da jornada livre confundia alias configurado e versão respondida

O canary da Landing comparava literalmente todas as respostas com `gpt-5-mini`. O provider real
identificou `gpt-5-mini-2025-08-07`. Config já declara `modelPrefixes: ["gpt-5-mini-"]` no snapshot
canônico; o verificador ignorava essa semântica tanto na identidade quanto no preço.

Corrigido apenas no suporte de teste: correspondência única da entrada canônica de preço, mantendo
identidade real na telemetria e modelo configurado na requisição. Sem inferir aliases não declarados;
modelo/provider diferente, correspondência ambígua e preço inválido continuam bloqueados.
**12 testes Node passaram**, TypeScript do canary passou. O canary pago da Landing não foi executado.

### P1 — Resposta rejeitada perdia telemetria já recebida

Defeito independente reproduzido no adapter `SpringAiOpenAiService`: a validação de conteúdo
precedia `captureInvocationMetadata` tanto em Responses quanto no terminal de streaming. Uma
resposta incompleta ou um terminal sem conteúdo lançava a exceção esperada, mas descartava
modelo, response ID, finish reason e usage já retornados pelo provider. Duas regressões negativas
reproduziram `finishReason=null` antes da correção.

Classificação da mudança: `local-pequena`, sem alteração pública. Aderência:
`ja-suportado-mal-nomeado-ou-mal-materializado`; `AiProviderInvocationTrace.providerResponse`
já preserva esses dados separadamente de sucesso/falha. A captura agora precede a rejeição,
que continua obrigatória. Timeouts sem resposta permanecem sem usage; nenhum contador é estimado.
Owner: Config provider adapter; consumidores: traces de authoring e seus verificadores operacionais.
Sem mudança de modelo, timeout, retry, DTO, preços, manifest ou endpoint. Não há artefato derivado
público a regenerar. A evidência não identifica a causa do run `33999199428` retroativamente.
Validação focal: 31 testes passaram no adapter, classificador, métricas e integração
de fallback/cancelamento; duas regressões vermelhas antes da correção ficaram verdes.

### P1 — Seleção de campos perdida na criação de formulário

Evidência de código:
- `AgenticAuthoringPlanService.materializeCreateFormPlanFromCanonicalSchema` percorre todas as
  propriedades editáveis do schema; não resolve seleção semântica de campos do pedido.
- `AgenticAuthoringPatchCompilerService.buildCompiledFormPatch` gera `schemaUrl`, `submitUrl`,
  método e identidade, mas não projeta `plan.fields` na configuração de criação.
- A branch de edição/relabel já projeta `config.fieldMetadata`; o contrato de campos e o runtime
  existem. Não começar criando outro DTO de seleção ou formulário paralelo.
- `AgenticAuthoringFreePortfolioForwardTest` usa required `name/groupId`; o teste de browser
  verifica esses controles, mas não verifica exclusão de um campo opcional explicitamente rejeitado.

Primeiro corte recomendado: baseline negativa com pedido de subset, schema com optional extra e
required adicional; seleção semântica apoiada no schema; projeção da seleção pelo compiler e
validação do payload/DOM. Required não pode simplesmente desaparecer para aparentar cumprimento.
A revisão não alterou esse fluxo nem certificou subset de campos.

### P1 — Prova de edição de entidade não equivale a formulário de criação

O teste de formulário executado nesta revisão envia POST para criar um registro sintético.
Ele não prova PUT/PATCH, identificação de pessoa por nome, controle de concorrência nem prefill.
O prompt sistêmico já exige tratar seleção e escrita separadamente. A jornada deve observar a
operação existente em Metadata/CRUD antes de dizer que alterou a pessoa ou seu salário.

### P1 — Edição profunda e ações precisam de pós-condição

Manifests de Table/Lista/Charts e delegação do Page Builder já existem. Os testes de compilação não
certificam visibilidade do botão, destino registrado, abertura do drawer ou propagação do id.
Executar os controles reais e conferir request, resposta, DOM, foco e reload. Nunca suprir a ausência
de uma surface/rota com URL inventada ou handler local ad hoc.

### P2 — Qualidade visual ainda não é uniforme

Capturas de 1280x720 e 390x844: formulário e modal são utilizáveis; controles de fechar e seleção
ficam dentro da viewport. O modal de drill-down apresenta cartões com muito espaço vazio e
paginação em inglês (`Items per page`) em uma jornada portuguesa. Classificação:
`ja-suportado-so-ux`; investigar owner de Table/list presentation/i18n, não CSS corretivo da landing.
A navegação por Tab/Enter no composer e formulário passou; Tab permanece no modal e Escape fecha.
Seleção de pontos do gráfico por teclado e leitor de tela continuam sem certificação.

## Evidência executada nesta revisão

- Angular público 9.0.64 e Landing `78dde921f44377f1ee0b590ac6790a16dec39933`.
- Browser oficial `http://127.0.0.1:4301/decision-playground`, uma worker, zero retries.
- `decision-playground-free-portfolio.spec.ts`: **4/4 passaram**, 1,3 minuto.
- Dois domínios sintéticos: staff e shipments; formulários, opção remota, envio, dashboard,
  filtro pela categoria clicada, lista no modal, reload, teclado e narrow.
- Provider, metadata, HTTP de domínio e persistência são controlados nessa suite; compilação Java
  e componentes Angular são reais. Não são quatro jornadas live.
- Capturas e log locais em `../human-review-browser` e `../human-review-browser.log` relativos à
  raiz do checkout Landing; dados sintéticos, sem secrets.
- Revalidação live canônica: [33999199428](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/33999199428).
  **2/3 testes passaram, 1 falhou**, três turnos humanos e zero retries; apply não executado e
  cleanup verificado. Recibo sanitizado em `HUMAN-JOURNEY-LIVE-REVALIDATION-2026-09-05.receipt.json`.
  Tokens conhecidos: 309 de entrada e 110 de saída. Total/custo desconhecidos; outras chamadas
  falharam sem counters e embeddings não entram nesses números.

## Artefatos derivados

As correções internas desta revisão não mudam endpoints, headers, public API, manifests, schemas ou exemplos.
Não exige regeneração do corpus HTTP, `LLM_SURFACE.md` ou registry. A correção futura de seleção de
campos exigirá sincronizar exemplos, provas do compiler e browser do formulário no mesmo corte.
