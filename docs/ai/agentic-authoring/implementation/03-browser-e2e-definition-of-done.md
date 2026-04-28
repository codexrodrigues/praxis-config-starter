# Browser E2E Definition Of Done

Este arquivo define o gate minimo de navegador real para considerar a trilha de
agentic authoring concluida em cada fase relevante.

## Regra geral

Nao basta passar teste unitario ou smoke HTTP. O objetivo so e atingido quando o
comportamento for provado em navegador real, com:

- backend `praxis-api-quickstart` real;
- Angular `praxis-ui-angular` real;
- stream SSE real;
- provider LLM real quando o cenario exigir semantica nao deterministica;
- Page Builder/assistente real, sem mock local escondido.

## Runners canonicos

### Full local E2E

Executa backend Quickstart, Angular dev server e Playwright real:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-PbAgenticFullE2E.ps1 `
  -Provider openai `
  -QuickstartRoot ..\praxis-api-quickstart `
  -UiRoot ..\praxis-ui-angular `
  -StreamProcessingTimeoutSeconds 180
```

Esse runner:

- sobe o quickstart em `http://localhost:8088`;
- sobe o Angular em `http://localhost:4003`;
- executa `npx.cmd playwright test --config=tools/e2e/playwright/praxis-page-builder-agentic-validation.playwright.config.ts`.

### Full gate em GitHub Actions

Workflow canonico:

- `.github/workflows/agentic-authoring-smoke.yml`

Input obrigatorio quando o PR mexer em:

- stream de authoring;
- page builder agentic;
- patch/apply no fluxo assistido;
- integracao real Angular + backend.

Input:

- `run_page_builder_full_e2e=true`

## Cenarios obrigatorios por fase

## Fase 1 - Turn engine e tools minimas

### Cenario A. Stream de authoring abre e conclui

**Objetivo**
- provar que o turno agentic roda pelo backend e conclui em stream real.

**Pass criteria**
- o assistente inicia o turno sem erro de ownership/autenticacao;
- ha evento terminal `result` ou `error` coerente;
- `cancel` e `probe` continuam funcionais;
- o frontend nao trava em estado intermediario.

### Cenario B. Pedido de alteracao em tela existente usa contexto da pagina

**Objetivo**
- provar que o turno entende que se trata de `modify`, nao de `create`, em uma
  tela ja aberta.

**Pass criteria**
- o resultado aponta para alteracao da tela atual;
- o preview/reflexo da resposta nao cria uma pagina nova por engano;
- logs/diagnostico do backend mostram uso do artefato atual quando aplicavel.

### Cenario C. Pedido ambiguo de recurso aciona discovery

**Objetivo**
- provar que o backend consulta a camada de discovery antes do resultado.

**Pass criteria**
- o turno retorna candidato(s) coerente(s) ou clarificacao coerente;
- a resposta nao depende de mapa hardcoded no frontend.

## Fase 2 - Summary reduzido e retrieval governado

### Cenario D. Edicao de campo respeita estrutura real da pagina

**Objetivo**
- provar que o sistema usa a estrutura real de `currentPage`, nao apenas resumo.

**Pass criteria**
- adicionar/remover/renomear campo em tela existente funciona sem perder alvo;
- o preview nao opera em secao errada;
- a saida continua correta quando o resumo textual e ambiguo.

### Cenario E. Dashboard/widget usa candidate discovery semantico

**Objetivo**
- provar que a descoberta de recurso para widget/lista/grafico vem da trilha
  semantica primaria.

**Pass criteria**
- candidatos coerentes para pedido do tipo "mostrar chamados por prioridade";
- nao ha regressao para fallback lexical quando o semantico estiver indisponivel.

## Fase 3 - UI principal no turn stream

### Cenario F. Fluxo principal do assistente usa o stream agentic

**Objetivo**
- provar que a UI principal nao esta mais operando por caminho antigo por baixo.

**Pass criteria**
- acao principal do assistente usa `startAgenticAuthoringTurnStream`;
- progresso exibido acompanha eventos reais de backend;
- `cancel` encerra o turno sem deixar UI presa.

### Cenario G. Quick replies e hints sobrevivem ao stream

**Objetivo**
- provar que a experiencia continua util depois da troca de caminho principal.

**Pass criteria**
- quick replies continuam aparecendo quando cabivel;
- `contextHints` seguem disponiveis para retomada da conversa;
- fallback sincrono nao apaga dados do turno.

## Fase 4 - Self-healing

### Cenario H. Falha recuperavel nao vaza imediatamente ao usuario

**Objetivo**
- provar que o engine tenta repair antes de falhar terminalmente.

**Pass criteria**
- backend emite tentativa de repair;
- quando o erro for recuperavel, o turno conclui com preview valido;
- quando o erro nao for recuperavel, o erro terminal e coerente e auditavel.

## Fase 5 - Memoria persistente de projeto

### Cenario I. Convencao persistida influencia um turno novo

**Objetivo**
- provar que project knowledge backend-owned participa do turno.

**Pass criteria**
- uma convencao persistida e reutilizada em um turno posterior;
- o frontend nao depende de localStorage para lembrar a regra;
- a influencia da memoria e auditavel no diagnostico do backend.

## Checklist de verificacao no navegador

Use este checklist sempre que rodar um fluxo impactado:

- [ ] o Quickstart subiu em `http://localhost:8088`
- [ ] o Angular subiu em `http://localhost:4003`
- [ ] o assistente abriu no Page Builder sem erro de bootstrap
- [ ] o turno iniciou sem erro de CORS, token ou ownership
- [ ] houve progresso observavel no assistente
- [ ] houve evento terminal coerente
- [ ] o resultado veio do backend e nao de mock local
- [ ] o preview/aplicacao refletiu o pedido feito
- [ ] `cancel` e `probe` continuaram funcionando se o fluxo mexeu em stream
- [ ] nao houve regressao visual obvia no painel do assistente

## Evidencias que devem ser anexadas ao fechamento

Para marcar uma fase relevante como concluida, anexar ao menos:

- comando executado;
- resultado final do runner;
- referencia ao artifact gerado em `artifacts/page-builder-agentic-full-e2e/**`
  quando houver;
- breve nota dizendo quais cenarios acima foram cobertos.

## O que nao vale como prova suficiente

- apenas teste unitario;
- apenas mock HTTP;
- apenas chamada direta aos endpoints sem navegador;
- apenas screenshot estatico sem acao real do assistente;
- apenas "funcionou localmente" sem comando, artefato ou criterio de aceite.
