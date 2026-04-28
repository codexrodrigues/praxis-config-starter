# Current State And Target

## Onde estamos partindo

O estado atual ja nao e mais "prompt sincrono + patch". Ha avancos reais:

- existe stream SSE canonico para authoring em
  `/api/praxis/config/ai/authoring/turn/stream/**`;
- `currentPage` ja faz parte do contrato publico de authoring;
- `domainCatalog` ja entra como hint governado;
- o Angular ja possui cliente para o stream agentic;
- ha validacao e compilacao de preview no backend;
- existe busca semantica/RAG parcial para candidatos de recurso.

## O que ainda falta estruturalmente

### 1. O turn engine ainda nao e realmente tool-driven

Hoje o fluxo principal ainda e basicamente:

1. `intentResolverService.resolve(...)`
2. `previewService.preview(...)`

com SSE em volta. Isso nao e ainda um ciclo agentic real.

### 2. `currentPageSummary` ainda pesa mais do que deveria

Mesmo com `currentPage` presente, o resolver e partes do planner ainda dependem
demais do resumo derivado.

### 3. Retrieval semantico ainda esta misturado com heuristica

RAG existe, mas a superficie de candidate discovery ainda mistura retrieval
semantico, fallback lexical e overrides deterministas sem uma divisao clara de
responsabilidades.

### 4. Nao existe self-healing backend-owned

Quando preview/compile falha, o fluxo em geral termina com erro. Ainda nao ha
um loop de repair canonico e limitado.

### 5. A UI ainda nao usa claramente o turn stream como caminho principal

O cliente Angular ja sabe consumir o stream, mas isso ainda precisa virar o
fluxo principal do assistente, nao so uma capacidade disponivel.

### 6. Nao ha memoria persistente de projeto canonica

Ha historico local de conversa no cliente, mas isso nao e Project Knowledge de
plataforma.

## Onde queremos chegar

## Estado alvo por comportamento

O fluxo final desejado e:

1. usuario envia um pedido no assistente;
2. backend cria um turno agentic explicito;
3. backend resolve a intencao inicial;
4. backend decide se precisa de tool;
5. backend executa uma ou mais tools canonicas;
6. backend usa os resultados para gerar plano e preview;
7. backend valida o resultado;
8. se a falha for recuperavel, backend tenta repair;
9. backend emite progresso e resultado por SSE;
10. frontend apenas apresenta o progresso e aplica o resultado retornado.

## Estado alvo por arquitetura

### Backend

- `AgenticAuthoringTurnEngine` e o dono do turno.
- tools sao executaveis e registradas em um catalogo canonico.
- `currentPage` e inspecionado estruturalmente.
- RAG e a fonte primaria de retrieval semantico.
- heuristicas ficam isoladas como fallback auditavel.
- repair loop e controlado pelo engine.
- memoria de projeto, quando houver, entra por retrieval seletivo.

### Contrato

- stream reutiliza `AiTurnEventEnvelope`;
- payloads de `thought.step`, `result` e `error` refletem o comportamento real;
- tools estabilizadas aparecem na documentacao/contrato;
- `currentPageSummary` permanece apenas como compatibilidade/transporte auxiliar.

### Frontend

- `@praxisui/ai` usa o turn stream como caminho principal;
- o frontend nao reimplementa discovery semantico;
- quick replies e hints continuam preservados;
- cancelamento/probe/retry exibem o estado real do backend.

## Criterio de sucesso de plataforma

O trabalho so esta realmente completo quando um pedido real no Page Builder,
executado em navegador real, mostrar que:

- o backend chamou o fluxo canonico de authoring;
- o frontend nao resolveu localmente o problema por fallback indevido;
- o resultado foi aplicado ou revisado a partir do retorno do backend;
- os cenarios obrigatorios do checklist E2E passaram.
