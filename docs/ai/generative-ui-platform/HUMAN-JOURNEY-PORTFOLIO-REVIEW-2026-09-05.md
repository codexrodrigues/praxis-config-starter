# Revisão das jornadas humanas do assistente Praxis

## Escopo e decisão

Revisão transversal solicitada pelo usuário em 2026-09-05. Objetivo: conversar sobre o negócio,
descobrir como representá-lo, criar componentes e editar uma composição existente preservando os
requisitos anteriores. Modelo do produto permanece `gpt-5-mini`; nenhuma troca para Astra.
O limite de gasto da conta OpenAI é declarado pelo usuário, não verificado independentemente.

Este inventário não certifica “qualquer componente”. Manifesto disponível, plano compilado,
interação determinística no navegador e jornada com LLM real são quatro evidências distintas.
As frases abaixo são roteiros de avaliação, nunca regras de roteamento de intenção.

## Resultado verificável

- **Seis jornadas browser determinísticas passaram**: criação de formulário, dashboard/drill-down
  e edição de registro em dois domínios, com dados/fronteiras controlados e componentes reais.
- **Uma jornada com LLM real completou as pós-condições**, no run `34001578127`: master-detail de
  missões, apply com lineage, abertura da gaveta de comando por discovery, execução HTTP 200,
  duplicação HTTP 409, refresh e reload com mesmo payload/ETag. Foram dois turnos, uma quick reply,
  nenhum prompt corretivo digitado, nenhum reparo determinístico e zero retries Playwright.
- **Release continua reprovado**: o fluxo real foi `eventual-pass`, não `first-pass`. O histórico
  inclui falha do provider na resolução semântica inicial. Não remover essa evidência nem afrouxar
  o validador para publicar. Primeiro status útil em 63,742 s; terminal aplicável em 104,648 s;
  pós-condições e reload em 134,976 s, conforme o recibo.
- Config #453/#454 e Angular #509 incorporados; CIs verdes. Landing #214 permanece draft, com CI
  `34000081735` verde. Não houve release novo do Config nem deploy da Landing nesta revisão.
- Render foi conferido: API `2.0.0-rc.46`, provider `openai`, modelo `gpt-5-mini`, chave presente,
  status válido com Origin oficial. Nenhuma troca para Astra.

## Continuação: campos escolhidos e timeout identificado

Config #457/#458 e Angular #510 incorporados, com CIs verdes. A seleção semântica de campos agora
é preservada no layout compilado; campos obrigatórios omitidos bloqueiam o preview e o pedido de
esclarecimento chega ao usuário, sem reparo automático. Quatro jornadas Chromium controladas
passaram; o extra opcional permaneceu ausente do DOM/POST após reload.

O novo gate real `34003732255` falhou antes do apply: três turnos, 2/3 testes, zero retries e limpeza
confirmada. A telemetria por turno foi exportada e identificou quatro timeouts nos limites locais de
12/30 segundos, enquanto duas chamadas menores completaram. Uso/custo das chamadas interrompidas
permanece desconhecido. O sucesso funcional antigo continua eventual-pass; não foi convertido em
certificação do novo corte ou dos formulários livres.

Ver [implementação, validação e análise de orçamento por fase](FIELD-SELECTION-AND-TURN-DIAGNOSTICS-2026-09-05.md)
e [recibo live sanitizado](HUMAN-JOURNEY-LIVE-TIMEOUT-DIAGNOSIS-2026-09-05.receipt.json).
Próxima prioridade: política de tempo/raciocínio e contexto na fonte canônica, com teste local de
transporte/deadline antes de novo gate pago. Release e deploy permanecem condicionados a first-pass.

## Plano e mapa de impacto

1. Revalidar a correção de continuidade em `66491255374553730a2ceee49bf30ec6afe9abc0` no gate
   canônico com provider real, três interações humanas no máximo e zero retries Playwright.
2. Mapear cada pedido ao contrato existente; rever testes, execução HTTP, renderização e negativos.
3. Reproduzir formulário, dashboard e edição de registro em dois domínios sintéticos, teclado, desktop e narrow.
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
| Validação | Gate pago canônico separado de seis testes browser determinísticos; análise de source focal |
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
| “Quero um formulário somente com estes campos” | `minimal-form-plan.v1` já possui `fields`; Dynamic Form possui configuração/layout/metadata de campos | `ja-suportado-mal-nomeado-ou-mal-materializado`: corrigido localmente: seleção semântica com schema e layout compilado; quatro provas browser controladas, interpretação real ainda pendente | Campos selecionados e required reconciliados com schema, sem omissão silenciosa; campo extra ausente do DOM e payload; reload preserva seleção |
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
A execução `33999199428` não chegou a revalidar a preservação do layout. Os gates subsequentes
já incluíram a correção do resolver: `34001578127` comprovou o resultado funcional e o layout,
mas precisou de uma segunda interação após falha inicial, permanecendo sem aprovação first-pass.
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

### P1 — Gate live consultava confirmação de salvamento removida

O run `34000631220`, Config `1da64d20e93f40e566d9c9e53b839f90c853883d`, passou nas asserções
semânticas do preview master-detail de missões, grounding e command discovery; `page-apply`
retornou HTTP 200. Depois falhou procurando `page-builder-agentic-status`, ausente no DOM.
O host já renderiza a confirmação persistente em `page-builder-reset-feedback`, com teste próprio.
O helper live foi corrigido para essa superfície. São 13 testes do host e 17 da auditoria de source
passando, TypeScript e descoberta Playwright aprovados; as demais asserções não foram afrouxadas.

Classificação: `local-pequena` no verificador Angular; `ja-suportado-mal-nomeado-ou-mal-materializado`.
Nenhuma mudança de lib pública, npm, modelo ou endpoint. Nesse run, lineage/payload pós-save, comando, refresh e reload ficaram após a asserção falhada.
O run posterior `34001578127` comprovou essas pós-condições, mas não passou no critério first-pass.
O workflow publicou apenas result/source audit porque faltou o recibo terminal. Por isso, contagem
exata de turnos, invocações e custo desse run continuam desconhecidos (máximo de três turnos e
zero retries). Preservar a telemetria disponível quando um teste falha antes do recibo terminal é
uma lacuna operacional do coletor a resolver; não reconstruir contadores nem publicar payloads crus.
Recibo sanitizado: `HUMAN-JOURNEY-LIVE-SAVE-REVALIDATION-2026-09-05.receipt.json`.

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

O teste de criação de formulário envia POST para criar um registro sintético. A suite adicional
`decision-playground-free-runtime.spec.ts` passou em staff e shipments: preservação de três filtros
após dois refinamentos e reload, abertura da gaveta pelo botão do registro, prefill, alteração de
`pending`, PUT do mesmo id e nova leitura removendo a linha do recorte. As fronteiras semânticas,
HTTP e de persistência são controladas; a UI e os planos compilados são reais. Essa prova não
certifica identificação livre de pessoa por nome, alteração de salário por conversa nem concorrência.
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
paginação em inglês (`Items per page`) em uma jornada portuguesa. A gaveta de edição também
exibe `Record being edited` em inglês; não confundir isso com labels sintéticos do domínio. Classificação:
`ja-suportado-so-ux`; investigar owner de Table/list presentation/i18n, não CSS corretivo da landing.
A navegação por Tab/Enter no composer e formulário passou; Tab permanece no modal e Escape fecha.
Seleção de pontos do gráfico por teclado e leitor de tela continuam sem certificação.

## Evidência executada nesta revisão

- Angular público 9.0.64 e Landing `78dde921f44377f1ee0b590ac6790a16dec39933`.
- Browser oficial `http://127.0.0.1:4301/decision-playground`, uma worker, zero retries.
- `decision-playground-free-portfolio.spec.ts`: **4/4 passaram**, 1,3 minuto.
- `decision-playground-free-runtime.spec.ts`: **2/2 passaram**, 12,1 segundos; edição via gaveta,
  prefill, mutation, readback e preservação de predicados após refinamentos e reload.
- Dois domínios sintéticos: staff e shipments; formulários, opção remota, envio, dashboard,
  filtro pela categoria clicada, lista no modal, reload, teclado e narrow.
- Provider, metadata, HTTP de domínio e persistência são controlados nessa suite; compilação Java
  e componentes Angular são reais. Não são seis jornadas live.
- Capturas e log locais em `../human-review-browser` e `../human-review-browser.log` relativos à
  raiz do checkout Landing; dados sintéticos, sem secrets. Edição adicional em
  `../human-review-runtime-browser` e `../human-review-runtime-browser.log`. Servidor local encerrado.
- Revalidação live canônica: [33999199428](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/33999199428).
  **2/3 testes passaram, 1 falhou**, três turnos humanos e zero retries; apply não executado e
  cleanup verificado. Recibo sanitizado em `HUMAN-JOURNEY-LIVE-REVALIDATION-2026-09-05.receipt.json`.
  Tokens conhecidos: 309 de entrada e 110 de saída. Total/custo desconhecidos; outras chamadas
  falharam sem counters e embeddings não entram nesses números.

- Revalidação com o seletor corrigido: [34001578127](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/34001578127).
  **3/3 testes passaram**, duas interações, zero retries, cleanup verificado. As nove pós-condições
  funcionais passaram; o gate de evidência reprovou `eventual-pass`. Recibo sanitizado em
  `HUMAN-JOURNEY-LIVE-FUNCTIONAL-2026-09-05.receipt.json`. Invocações/usage/custo não exportados;
  `blockingDiagnosticCodes` agrega o histórico dos dois turnos, não um bloqueio do terminal aplicável.
- Snapshot de preços atualizado em UTC para `provider-pricing-snapshot.free-authoring.2026-09-06.json`,
  consultando a fonte oficial já declarada no contrato; mesmos valores, validador do canary aprovado.

## Artefatos derivados

As correções internas desta revisão não mudam endpoints, headers, public API, manifests, schemas ou exemplos.
Não exige regeneração do corpus HTTP, `LLM_SURFACE.md` ou registry. A correção futura de seleção de
campos exigirá sincronizar exemplos, provas do compiler e browser do formulário no mesmo corte.


## Continuação: política de provider e primeira tentativa aprovada

O corte seguinte corrigiu perfil/reasoning, preservação de modelo no timeout e cancelamento.
O gate real 34005676687 passou 3/3 testes com um único pedido e todas as nove pós-condições funcionais.
Há ainda uma resposta incompleta e dois timeouts em fases preliminares; custo total é desconhecido.
Ver [política, inventário, validações e limitações](PROVIDER-PHASE-POLICY-2026-09-05.md).
A prova é do workspace master/detail com comando, não de todo o portfólio de criação/edição livre.
