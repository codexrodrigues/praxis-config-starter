# Agentic Authoring Implementation Guide

Este diretorio existe para manter um trilho canonico de implementacao do
agentic authoring do Praxis. Ele deve ser lido antes de abrir PRs que mexam em:

- `POST /api/praxis/config/ai/authoring/**`
- stream SSE de authoring
- contratos AI em `docs/ai/contracts/**`
- consumo Angular em `@praxisui/ai`
- fluxos do Page Builder dependentes de authoring backend-driven

## Objetivo

Sair do estado atual, em que o fluxo de authoring ainda e majoritariamente
linear (`intent-resolution -> page-preview`), para o estado alvo em que o
backend opera um turno agentic real:

1. resolve intencao inicial;
2. decide se precisa de tools;
3. executa tools canonicas;
4. observa os resultados;
5. gera preview/resultado;
6. valida e tenta repair quando a falha for recuperavel;
7. expone progresso e resultado por stream SSE canonico.

## Ordem de leitura

1. [01-current-state-and-target.md](./01-current-state-and-target.md)
2. [02-implementation-backlog.md](./02-implementation-backlog.md)
3. [03-browser-e2e-definition-of-done.md](./03-browser-e2e-definition-of-done.md)

## Invariantes canonicos

- O dono canonico do fluxo e o backend em `praxis-config-starter`.
- O frontend nao deve virar orquestrador de tool calls.
- `currentPage` e o artefato primario; `currentPageSummary` e derivado auxiliar.
- O envelope SSE canonico continua sendo `AiTurnEventEnvelope`.
- Tools de authoring devem ser tratadas como contrato executavel, nao como hint.
- Heuristica lexical pode existir como fallback, mas nao como semantica primaria.
- Repair loop deve ser backend-owned, limitado e auditavel.
- Memoria persistente de projeto, quando entrar, deve ser governada e escopada;
  nunca um blob opaco de prompt salvo no cliente.

## O que nao fazer

- Nao criar um segundo formato de stream para authoring.
- Nao empurrar logica de selecao de recurso para o Angular quando o backend ja
  tiver superficie canonica para isso.
- Nao adicionar feature flags ou trilhas paralelas permanentes para preservar o
  fluxo antigo, salvo necessidade operacional explicita.
- Nao tratar `currentPageSummary` como fonte de verdade quando `currentPage`
  estiver disponivel.
- Nao declarar tool como "implementada" se ela ainda for apenas um item textual
  do prompt ou `quickReply`.

## Prova minima de conclusao por incremento

Cada incremento so pode ser considerado concluido quando:

- os testes focais do backend passarem;
- os docs e contratos afetados estiverem atualizados;
- o fluxo E2E de navegador real definido em
  [03-browser-e2e-definition-of-done.md](./03-browser-e2e-definition-of-done.md)
  estiver verde para os cenarios impactados;
- o resultado final vier do backend canonico e nao de fallback mockado no
  frontend.
