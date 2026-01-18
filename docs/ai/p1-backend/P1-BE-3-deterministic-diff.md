# P1-BE-3 Diff deterministico (before/after) e base para completeness loop

Contexto
- Hoje o backend retorna apenas o patch, sem diff deterministico.
- Falta base para loops de completeness e validacao incremental.

Objetivo
- Gerar diff deterministico (before/after) do patch aplicado a currentState.
- Criar base para completeness loop (ex.: confirmacao de campos alterados).

Escopo sugerido
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`
- DTO de resposta (adicionar campo opcional `diff`)
- Testes unitarios de diff

Passos
- [ ] Implementar merge deterministico (deep merge) em memoria para produzir after.
- [ ] Gerar diff compacto (paths alterados + before/after).
- [ ] Incluir `diff` em `AiOrchestratorResponse` quando patch for valido.

Criterios de aceite
- Diff sempre deterministico para o mesmo input.
- Sem dependencia de LLM.
- Nao altera persistencia.
