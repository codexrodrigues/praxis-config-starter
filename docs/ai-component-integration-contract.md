# AI Component Integration Contract

## Purpose
Define the required artifacts, structure, and quality bar for any component
that integrates with the AI assistant and AI template ingestion pipeline.

## Scope
Applies to components in `praxis-ui-angular` that expose configuration via AI
or require AI templates for generation. This document covers runtime behavior
(adapter + assistant) and offline ingestion (recipes + capabilities).

## Terms
- Component config: Public configuration object for a component (ex: TableConfig, FormConfig).
- Capabilities catalog: Declarative list of config paths the AI is allowed to edit.
- Adapter: Runtime bridge between `praxis-ai-assistant` and a component instance.
- Recipe template: JSON example used for ingestion and prompt grounding.
- Component definition capabilities: Inputs/outputs capabilities for a component host.

## Required Artifacts (Runtime)

### 1) Capabilities catalog for the config
Location: `projects/<lib>/src/lib/ai/<component>-ai-capabilities.ts`

Must contain:
- `CapabilityCatalog` with `version`, `enums`, `notes`, `capabilities[]`
- Each `Capability` must include:
  - `path` (use `[]` for arrays)
  - `category`
  - `valueKind`
  - `allowedValues` for enums
  - `description`
  - `critical` when change is destructive or high impact
  - `intentExamples` for real user intent
  - `dependsOn`, `example`, `safetyNotes` when relevant

Coverage rule:
- 100% of the public API surface of the config model
- Ignore internal/private fields (prefix `_`)

### 2) Adapter
Location: `projects/<lib>/src/lib/ai/<component>-ai.adapter.ts`

Must implement `AiConfigAdapter` (see `projects/praxis-ai/src/lib/core/adapters/ai-config.adapter.ts`):
- Required:
  - `componentName`
  - `getCurrentConfig()`
  - `getCapabilities()`
  - `createSnapshot()`
  - `restoreSnapshot(snapshot)`
  - `applyPatch(patch, intent?)`
- Optional but recommended:
  - `getSuggestions(forceReload?)`
  - `getRuntimeState()`
  - `getTaskPresets()`

Expected behavior:
- Apply patch safely (merge, preserve existing arrays unless explicitly replaced)
- Validate against capabilities where possible
- Keep component reactive (state updates reflect in UI)

### 3) Component wiring
Location: `projects/<lib>/src/lib/<component>.ts` and `.html`

Must include:
- `aiAdapter` instance in component class
- `<praxis-ai-assistant [adapter]="aiAdapter">` in the template

## Required Artifacts (Ingestion / Templates)

### 4) AI recipes (templates)
Location: `examples/ai-recipes/**`

Rules:
- JSON object, optional `_comment`, `aiDescription`, `templateMeta`
- Generic placeholders only (no real endpoints or project-specific IDs)
- File naming defines `componentId` and optional `variantId`
  - `<componentId>.json`
  - `<componentId>.<variantId>.json`
  - Nested folders are allowed for namespacing

Dynamic fields rule:
- Use `examples/ai-recipes/praxis-dynamic-fields/<controlType>.json`
- If no specific capabilities exist for a controlType, treat as FieldMetadata base

## Optional but Recommended Artifacts

### Profiler
Location: `projects/<lib>/src/lib/ai/<component>-data-profiler.ts`
Purpose: summarize runtime data and provide context for AI suggestions.

### Sanitizer / Normalizer
Location: `projects/<lib>/src/lib/ai/<component>-ai-sanitization.ts`
Purpose: clean and normalize AI patches (enums, defaults, unsafe fields).

### Component definition capabilities
Location: `projects/<lib>/src/lib/ai/<component>-component-ai-capabilities.ts`
Purpose: describe component inputs/outputs (host-level config).
Use when AI must reason about inputs/outputs beyond the inner config.

## Quality Bar (Definition of Done)
- Capabilities cover 100% of public config fields (no missing paths).
- Adapter exists and is used by the component template.
- Recipes exist and are generic (no project endpoints).
- Optional profiler/sanitizer exist if data-driven heuristics are needed.

## Related Decision
See `praxis-config-starter/docs/ai-context-runtime-state-decision.md` for:
- ComponentId rules (selector-based)
- Generic recipe policy and registry keys

## Audit Prompt (Copy/Paste)
You are an audit agent. Goal: identify missing AI integration artifacts for each
component in `praxis-ui-angular`.

Context:
- Read `AGENTS.md` and `codex-rules.md` first.
- Focus on components that render `praxis-ai-assistant` or have AI files.

Tasks:
1) Enumerate components with AI integration signals:
   - `praxis-ai-assistant` in templates
   - `*-ai-capabilities.ts` files
   - `*-ai.adapter.ts` files
2) For each component:
   - Identify the config model and its public API
   - Verify config capabilities coverage (100% of public paths)
   - Check for adapter and component wiring
   - Check for recipes in `examples/ai-recipes/`
   - Note profiler/sanitizer presence (optional)
3) For dynamic fields:
   - If controlType lacks specific capabilities, treat as FieldMetadata base
4) Report:
   - Checklist per component (capabilities, adapter, wiring, recipes, profiler/sanitizer)
   - Missing files and missing paths (full path names)
   - Suggestions for new artifacts (file path + brief description)

Output format:
- One table or checklist per component
- A consolidated list of missing artifacts
- Clear next actions
