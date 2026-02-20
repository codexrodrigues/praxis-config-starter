# P1-BE-5 Thought Timeline (backend -> frontend)

Contexto
- O frontend ja possui card de "pensamento/plano", timeline de 4 etapas e botoes `Detalhes`/`Previa`.
- Hoje esse bloco e majoritariamente heuristico no FE (estado local), nao dirigido por um contrato estruturado do backend.
- O backend ja possui fases tecnicas claras e logs por `callType`, mas nao retorna trilha de execucao para a UI.

Evidencias no codigo atual
- Pipeline principal: `src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java` em `generatePatch(...)`.
- Chamadas LLM com `callType` + latencia: `callAiJson(...)` e `callAiText(...)`.
- Snapshot de timeline apenas em log diagnostico: `buildRequestTimelineSnapshot(...)`.
- Contrato de resposta sem bloco de thought: `src/main/java/org/praxisplatform/config/dto/AiOrchestratorResponse.java`.

Objetivo
- Expor um bloco opcional `thought` em `AiOrchestratorResponse` com trilha de execucao segura, resumida e consumivel pela UI.
- Permitir que o FE renderize modo "Thought" semelhante a referencia (`img_10`) com dados reais de backend.
- Preservar compatibilidade retroativa (clientes antigos continuam funcionando).

Nao objetivo
- Expor chain-of-thought literal do modelo (prompts completos, raciocinio livre, tokens internos).
- Criar tema visual dark independente (o FE continua em tokens Material 3 do host).
- Introduzir streaming realtime na primeira entrega.

---

## 1. Contrato proposto (MVP, snapshot final)

Adicionar campo opcional na resposta:

```json
{
  "type": "patch|clarification|error|info",
  "thought": {
    "version": "v1",
    "status": "completed|needs_input|failed",
    "durationMs": 1840,
    "summary": "Proposta pronta para revisao.",
    "steps": [
      {
        "id": "intent_classification",
        "title": "Classificacao da solicitacao",
        "kind": "llm",
        "state": "done",
        "durationMs": 220,
        "message": "Intencao e escopo identificados."
      },
      {
        "id": "intent_plan",
        "title": "Plano de impacto",
        "kind": "llm",
        "state": "done",
        "durationMs": 180,
        "message": "Checklist de validacao montado."
      },
      {
        "id": "patch_generation",
        "title": "Geracao do patch",
        "kind": "llm",
        "state": "done",
        "durationMs": 520,
        "message": "Patch candidato retornado."
      },
      {
        "id": "sanitize_validate",
        "title": "Validacao e seguranca",
        "kind": "validation",
        "state": "done",
        "durationMs": 90,
        "message": "Patch sanitizado e diff calculado."
      }
    ],
    "checklist": [
      { "id": "scope", "label": "Conferir resumo e escopo", "state": "done" },
      { "id": "diff", "label": "Validar diff antes/depois", "state": "done" },
      { "id": "risk", "label": "Aplicar conforme politica de risco", "state": "pending" }
    ]
  }
}
```

Observacoes de contrato
- Campo opcional para manter backward compatibility.
- `summary` e `message` devem ser textos operacionais curtos (nao narrativas longas).
- `steps` com tamanho limitado e ordenacao deterministica.

---

## 2. Regras de seguranca (corporativo)

Regra principal
- Nao retornar prompts completos, contextos brutos (`currentState`, `schema`, `runtimeMetadata`) ou output cru do modelo.

Sanitizacao
- Aplicar redacao de segredos em textos livres (reaproveitar heuristicas de scrub ja existentes no projeto de memoria local).
- Truncar campos textuais (`summary`, `message`) para limite configuravel.
- Limitar numero de passos e bullets por passo.

Flags de controle (configuracao sugerida)
- `praxis.ai.thought.enabled=true`
- `praxis.ai.thought.max-steps=12`
- `praxis.ai.thought.max-items-per-step=3`
- `praxis.ai.thought.max-text-chars=240`

---

## 3. Mapeamento de fases backend -> thought

Fases base (ordem)
1. `request_context`
2. `intent_classification`
3. `action_plan` (`table_action_plan` ou `component_action_plan`, quando houver)
4. `intent_plan`
5. `patch_generation`
6. `sanitize_validate`
7. `completeness`
8. `final_response`

Fases condicionais
- `clarification` (quando faltar contexto ou perguntas pendentes).
- `fallback_deterministic` (quando patch e derivado de fallback seguro).
- `patch_generation_retry` (quando houver retry por completeness/contextRequest).
- `error` (excecao/falha final).

Mapeamento de status final
- `type=patch` -> `thought.status=completed`
- `type=clarification` -> `thought.status=needs_input`
- `type=error` -> `thought.status=failed`
- `type=info` -> `completed` ou `needs_input` conforme conteudo

---

## 4. Plano tecnico de implementacao

Fase A - DTO e builder interno
- Criar DTOs em `src/main/java/org/praxisplatform/config/dto/`:
  - `AiThought`
  - `AiThoughtStep`
  - `AiThoughtChecklistItem`
- Adicionar campo `thought` em `AiOrchestratorResponse`.
- Introduzir builder interno `ThoughtTraceBuilder` em `AiOrchestratorService` para acumular passos.

Fase B - Instrumentacao do fluxo
- Iniciar trace no inicio de `generatePatch(...)`.
- Marcar inicio/fim/erro dos blocos principais antes de cada retorno antecipado.
- Integrar `callAiJson(...)` e `callAiText(...)` para capturar:
  - `callType`
  - provider/model
  - attempt
  - latencia
  - sucesso/erro

Fase C - Acoplamento seguro na resposta
- Em `finalizeResponse(...)`, anexar `thought` antes de persistir a resposta.
- Garantir que idempotencia/cached response preserve `thought` previamente gerado.
- Limitar payload para nao inflar `ai_action.payload` excessivamente.

Fase D - Frontend contract (consumo)
- Atualizar interface `AiOrchestratorResponse` em `projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts`.
- No `ai-assistant.component.ts`, priorizar `result.thought` para:
  - tempo "Pensou por Xs"
  - checklist
  - resumo do card
  - timeline de estados
- Fallback para heuristica atual quando `thought` estiver ausente.

Fase E - Documentacao e rollout
- Atualizar:
  - `praxis-config-starter/README.md`
  - `projects/praxis-ai/README.md`
  - `projects/praxis-ai/src/lib/ui/ai-assistant/SPEC_AI_ASSISTANT.md`
- Liberar por flag (`praxis.ai.thought.enabled`) e validar em canario interno.

---

## 5. Plano de testes

Backend (unit/integration)
- `patch` retorna `thought.status=completed` e passos essenciais.
- `clarification` retorna `thought.status=needs_input`.
- `error` retorna `thought.status=failed`.
- Retry de `patch_generation` aparece no trace com tentativa 2.
- Sem vazamento de prompt/segredo em `thought.summary`/`steps.message`.

Controller/contrato
- Campo `thought` serializa sem quebrar headers de contrato atuais.
- Cliente antigo ignora campo extra sem regressao.

Frontend
- Quando `thought` existe, card usa dados do backend.
- Quando `thought` nao existe, fluxo atual permanece identico.
- `Detalhes` e `Previa` mantem semantica atual:
  - `Detalhes`: foco em resumo/decisoes/execucao contextual.
  - `Previa`: diff tecnico antes/depois (somente review).

---

## 6. Riscos e mitigacoes

Risco: payload maior e armazenamento em `ai_action.payload`.
- Mitigacao: limites de tamanho, truncamento, max passos.

Risco: confundir observabilidade com chain-of-thought.
- Mitigacao: contrato explicito "operational trace", sem prompt/output cru.

Risco: regressao em retornos antecipados (muitos `return finalizeResponse(...)`).
- Mitigacao: builder unico com helper `finalizeWithThought(...)` para reduzir omissao de passos.

---

## 7. Entrega incremental sugerida

Sprint 1 (MVP)
- DTO `thought` + builder + fases macro + testes principais.
- FE consome snapshot final (sem streaming).

Sprint 2
- Refinar passos por `callType`, retries e fallbacks.
- Melhorar checklist com base em `intentPlan`/`diff`.

Sprint 3 (opcional)
- Endpoint/fluxo de streaming (SSE) para progresso em tempo real.
- Mantendo mesmo schema `thought` para convergencia de UX.
