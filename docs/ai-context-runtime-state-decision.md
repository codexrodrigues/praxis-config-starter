# AI Context Runtime State Decision

## Purpose
Document how AI context is resolved in the backend using selector-based component identifiers,
with two supported sources for runtime state: persisted state (`ui_user_config`) and explicit
runtime state sent by the caller.

## Decision
- `componentId` equals the Angular selector (example: `praxis-table`).
- `componentType` is a logical namespace (example: `table`, `form`, `page`) used for
  prompt context and for runtime state lookup keys.
- `GET /api/praxis/config/ai-context/{componentId}` may hydrate `currentState` from
  `ui_user_config` (tenant/user/environment fallback path).
- `POST /api/praxis/config/ai-context/{componentId}` accepts `currentState` explicitly,
  allowing deterministic context generation over unsaved editor state.
- Store templates in `ai_registry` with `registry_key = <componentId>` for base templates
  and `registry_key = <componentId>:<variantId>` for variants.
- Add an AI context/orchestrator endpoint that accepts runtime state and returns `AiContextDTO`
  with template + component definition resolved from `ai_registry`.

## Rationale
- The user can have unsaved changes in the editor; explicit runtime state avoids stale context.
- Selector-based IDs remain stable across frontend/backends.
- GET fallback preserves convenience for editor bootstrap/resume from persisted state.
- Templates can be scoped by logical type without coupling template storage to per-user state.

## Implications
- For deterministic generation/edition flows, the frontend should send `currentState`
  in `POST /ai-context` (and in AI patch/orchestrator requests).
- `GET /ai-context` can be used to bootstrap context from persisted `ui_user_config`
  when explicit runtime state is not available.
- Templates are fetched from `ai_registry` using `componentId` (base) or
  `componentId:variantId` (variant). Base templates can list `templateMeta.variants`
  and `defaultVariantId` to guide selection.
- Definitions remain stored by selector in `ai_registry` (`registry_type=component_definition`).
- `ui_user_config` remains user/tenant/environment storage; `ai_registry` remains template/definition storage.

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
