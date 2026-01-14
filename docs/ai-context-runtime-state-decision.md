# AI Context Runtime State Decision

## Purpose
Document the decision to build AI context in the backend using runtime state and selector-based
component identifiers, without reading `ui_user_config` in the AI flow.

## Decision
- `componentId` equals the Angular selector (example: `praxis-table`).
- `componentType` is a logical namespace (example: `table`, `form`, `page`) used for
  prompt context; it is not used to resolve runtime state or template keys.
- AI context must use runtime state provided by the caller; it must not read or derive
  `currentState` from `ui_user_config` for the AI flow.
- Store templates in `ai_registry` with `registry_key = <componentId>` for base templates
  and `registry_key = <componentId>:<variantId>` for variants.
- Add an AI context/orchestrator endpoint that accepts runtime state and returns `AiContextDTO`
  with template + component definition resolved from `ai_registry`.

## Rationale
- The user can have unsaved changes in the editor; using runtime state avoids stale context.
- Selector-based IDs remain stable across frontend/backends.
- Templates can be scoped by logical type without coupling to per-user storage.

## Implications
- The AI orchestrator/frontend must send `currentState` on every context request.
- Templates are fetched from `ai_registry` using `componentId` (base) or
  `componentId:variantId` (variant). Base templates can list `templateMeta.variants`
  and `defaultVariantId` to guide selection.
- Definitions remain stored by selector in `ai_registry` (`registry_type=component_definition`).
- The AI flow must not query `ui_user_config`; that storage is only for user/tenant state.

## AI Recipes (Generic Examples)
- The files under `praxis-ui-angular/examples/ai-recipes/` are intentionally generic and serve as
  patterns for the AI to learn expected shapes.
- Values such as `resource.path`, `actions[].route`, `actions[].formId`, and API
  endpoints are placeholders, not real project routes.
- `praxis-crud.json` is an illustrative CrudMetadata example; it is not meant
  to be used as-is in production.
- When applying in a real app, replace placeholders with actual routes,
  endpoints, and identifiers.

## Example Request
POST `/api/praxis/config/ai/patch`

```json
{
  "componentId": "praxis-table",
  "componentType": "table",
  "userPrompt": "deixe as colunas mais estreitas",
  "currentState": { "columns": [], "behavior": { "pagination": { "enabled": true } } },
  "aiMode": "edit",
  "variantId": "columns",
  "resourcePath": "/api/orders",
  "schemaContext": { "path": "/api/orders", "operation": "GET", "schemaType": "response" }
}
```
