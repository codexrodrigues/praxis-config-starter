# Implementation Backlog

Este backlog esta em ordem de execucao. Nao abra as fases finais antes de fechar
as dependencias estruturais das fases iniciais.

## Fase 1 - Turn engine e tools minimas

### Item 1. Extrair `AgenticAuthoringTurnEngine`

**Objetivo**
- tirar a orquestracao principal de `AgenticAuthoringTurnStreamService`;
- centralizar o turno em um engine explicito.

**Arquivos provaveis**
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnEngine.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnState.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnOutcome.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnStreamService.java`

**Definition of Done**
- stream service deixa de ser o orquestrador primario;
- o engine executa pelo menos o fluxo linear atual;
- endpoints e envelopes publicos permanecem compativeis.

### Item 2. Criar o modelo executavel de tools

**Objetivo**
- criar registry/executor internos para tools reais.

**Tipos minimos**
- `AgenticAuthoringToolDefinition`
- `AgenticAuthoringToolCall`
- `AgenticAuthoringToolResult`
- `AgenticAuthoringToolExecutor`
- `AgenticAuthoringToolRegistry`

**Definition of Done**
- engine consegue registrar e executar tool por nome;
- erro de tool tem shape estruturado;
- ha rastreabilidade de chamada e resultado.

### Item 3. Implementar `searchApiResources` como tool real

**Objetivo**
- promover a busca de candidatos de hint textual para execucao canonica.

**Definition of Done**
- tool usa o catalogo/backend canonico;
- resultado traz candidatos, score e provenance;
- o engine pode chama-la sem depender do controller.

### Item 4. Implementar `inspectCurrentPage`

**Objetivo**
- oferecer inspecao estruturada do artefato atual.

**Resposta minima esperada**
- `artifactKind`
- `componentType`
- `boundResource`
- `editableRegions`
- `fields`
- `widgets`
- `serverBindings`
- `transientBindings`

**Definition of Done**
- engine consulta `currentPage` como estrutura;
- inspecao nao depende de resumo opaco;
- existe cobertura com fixtures de form e dashboard.

### Item 5. Primeira politica de decisao de tools

**Objetivo**
- permitir que o engine chame tool antes de gerar resultado.

**Definition of Done**
- ha cenarios reais em que `searchApiResources` roda antes do preview;
- ha cenarios reais em que `inspectCurrentPage` roda antes de decidir `modify`;
- numero maximo de tool calls por turno e limitado.

## Fase 2 - Reducao de summary e retrieval governado

### Item 6. Rebaixar `currentPageSummary` a derivado auxiliar

**Objetivo**
- usar `currentPage` como fonte primaria;
- manter `currentPageSummary` apenas para compatibilidade e compactacao.

**Definition of Done**
- resolver deixa de depender do summary para regras principais;
- summary continua apenas como apoio textual.

### Item 7. Migrar planner/validator para inspecao estruturada

**Objetivo**
- remover dependencia indireta de summary em plan/preview/validator.

**Definition of Done**
- planner e validator operam sobre dados estruturados;
- cenarios de `add_field`, `remove_field` e `add_widget` continuam corretos.

### Item 8. Separar retrieval semantico de fallback lexical

**Objetivo**
- dividir retrieval em componentes claros.

**Componentes esperados**
- `SemanticCandidateRetriever`
- `LexicalFallbackCandidateRetriever`
- `CandidateRankingPolicy`
- opcionalmente `DeterministicOverridePolicy`

**Definition of Done**
- origem do candidato e explicita;
- RAG e a trilha primaria quando disponivel;
- fallback continua funcional sem contaminar a camada semantica.

## Fase 3 - Docs, contrato e UI principal

### Item 9. Atualizar docs para refletir o comportamento real

**Objetivo**
- alinhar docs a implementacao efetiva.

**Definition of Done**
- `docs/ai/agentic-authoring-streaming.md` e `docs/ai/contracts/**` nao vendem
  um comportamento que ainda nao existe;
- este diretorio de implementation continua coerente com o codigo.

### Item 10. Promover tools estabilizadas ao contrato publico

**Objetivo**
- atualizar OpenAPI/contratos somente quando a implementacao estiver madura.

**Definition of Done**
- schema/documentacao bate com o runtime;
- exemplos de payload estao atualizados;
- nao ha drift entre docs e backend.

### Item 11. Fazer `@praxisui/ai` usar o turn stream como fluxo principal

**Objetivo**
- fazer o assistente Angular depender primariamente do fluxo agentic do backend.

**Definition of Done**
- fluxo principal usa `startAgenticAuthoringTurnStream`;
- probe/cancel funcionam;
- fallback antigo so fica onde for indispensavel e explicitado.

## Fase 4 - Self-healing

### Item 12. Classificar falhas recuperaveis

**Tipos minimos**
- `retryable`
- `non_retryable`
- `route_required`
- `user_clarification_required`

**Definition of Done**
- erros de preview/compile sao classificados;
- o engine sabe distinguir retry de falha terminal.

### Item 13. Implementar repair loop limitado

**Objetivo**
- permitir retry automatico para falhas recuperaveis.

**Definition of Done**
- maximo inicial: 1 retry por fase;
- stream mostra `repair.attempt`;
- erro terminal so aparece apos retry esgotado ou falha nao recuperavel.

## Fase 5 - Memoria persistente de projeto

### Item 14. Definir modelo canonico de project knowledge

Plano detalhado: [05-governed-project-knowledge-plan.md](./05-governed-project-knowledge-plan.md)

**Modelo minimo**
- `scope`
- `kind`
- `source`
- `status`
- `payload`
- `visibility`

**Definition of Done**
- memoria nao e historico de chat;
- escopo e governanca sao explicitos.
- Domain Knowledge Layer e usada como fonte canonica, salvo lacuna explicita.
- RAG/vector store e tratado apenas como indice derivado.

### Item 15. Expor retrieval seletivo de knowledge no turno

**Definition of Done**
- engine recupera apenas o conhecimento relevante;
- knowledge influencia o turno de forma auditavel;
- cliente nao e a fonte canonica dessa memoria.

## Regras operacionais para qualquer PR desta trilha

- Nao misturar Fase 1 e Fase 4 no mesmo PR.
- Nao promover contrato publico de uma tool antes de estabilizar a execucao.
- Nao remover fallback antigo do frontend antes do backend provar o novo fluxo em
  navegador real.
- Sempre atualizar docs afetados no mesmo ciclo quando mudar comportamento real.
- Se mexer em SSE, validar `start`, `probe`, stream, evento terminal e `cancel`.

## Fechamento obrigatorio por item

Nenhum item acima deve ser marcado como concluido sem:

- testes focais backend verdes;
- docs/contratos sincronizados quando aplicavel;
- validacao E2E de navegador dos cenarios impactados conforme
  `03-browser-e2e-definition-of-done.md`.
