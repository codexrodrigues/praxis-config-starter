# AI Suggestions Endpoint

## Endpoint
POST `/api/praxis/config/ai/suggestions`

## Purpose
Return deterministic, capability-aware suggestions for the AI assistant based on
the runtime `currentState`. This endpoint does **not** fetch schema or API metadata.

## Request
```json
{
  "componentId": "praxis-table",
  "componentType": "table",
  "currentState": { "columns": [], "behavior": {} },
  "dataProfile": {
    "rowCount": 120,
    "columns": {
      "status": { "inferredType": "string", "cardinality": 3, "topValues": ["Ativo", "Inativo"] }
    }
  },
  "variantId": "columns",
  "maxSuggestions": 5,
  "forceRefresh": false,
  "locale": "pt-BR"
}
```

Notes:
- `currentState` is the only runtime source used by the backend.
- `dataProfile` is optional and can be produced by frontend profilers.
- `variantId` is accepted for future use but not required.

## Response
```json
{
  "suggestions": [
    {
      "id": "table.pagination.enable",
      "label": "Habilitar paginacao",
      "description": "Tabela tem 120 linhas. Paginacao melhora performance.",
      "icon": "list_alt",
      "group": "Performance",
      "intent": "Habilitar paginacao com 10 itens por pagina",
      "score": 0.92
    }
  ],
  "source": "heuristic",
  "warnings": []
}
```

## Semantics
- `intent` is the user prompt sent to `/api/praxis/config/ai/patch`.
- Suggestions are stable (deterministic) for the same input.
- Only capabilities declared in the AI registry are used to decide which
  suggestions are allowed.

## Components
Initial coverage:
- `praxis-table`
- `praxis-dynamic-form`
