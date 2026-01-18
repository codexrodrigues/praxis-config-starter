# P1-BE-4 Completeness Loop (IntentPlan no backend)

Contexto
- O backend ja gera patch merge, mas nao verifica se todas as acoes planejadas foram cumpridas.
- O diff deterministico (P1-BE-3) permite verificar completude com checks deterministas.

Objetivo
- Gerar IntentPlan no backend (LLM).
- Comparar IntentPlan.actions com o diff aplicado ao currentState.
- Se incompleto: retry 1x com prompt focado nas acoes faltantes.
- Se continuar incompleto: retornar clarification com perguntas objetivas.

DTOs (minimos)
IntentPlan
```
{
  "intent": "update|create_flow|ask_how_to_configure|ask_about_config",
  "actions": [
    { "id": "string", "checks": [ { "type": "pathChanged|pathEquals|contains", "path": "string", "value": "any" } ] }
  ],
  "questions": []
}
```

Diff (exemplo)
```
[
  { "path": "title", "before": "Old", "after": "Novo" },
  { "path": "config.enabled", "before": false, "after": true }
]
```

Completeness rule
- pathChanged: existe diff cujo path inicia com o path do check.
- pathEquals: existe diff com path == check.path e after == check.value.
- contains: diff com path == check.path e after contem value.

Retry prompt (exemplo de payload)
```
INTENT_PLAN:
{ ... }
MISSING_ACTIONS:
[
  { "id": "enable-config", "checks": [ { "type": "pathEquals", "path": "config.enabled", "value": true } ] }
]
CURRENT_DIFF:
[
  { "path": "title", "before": "Old", "after": "Novo" }
]
REGRAS: nao mude o que ja esta correto; apenas complete o que falta.
```

Clarification (exemplos)
- "Qual valor devo usar em \"config.enabled\"? Opcoes: true, false."
- "Qual coluna devo usar? Colunas disponiveis: status, name."

Validacao
- /opt/maven/bin/mvn test
