# Agentic Domain Task Envelope

## Purpose

Praxis authoring agents must operate from runtime contracts instead of source
code inspection. A domain-aware task therefore needs a small envelope that tells
the backend which business resource the user is talking about, which semantic
context may be retrieved, and which governed output shape is expected.

This envelope is not a rules engine. It is the bridge between user intent,
Domain Catalog context, AI visibility constraints and the existing authoring
manifest flow.

## Canonical Request Shape

```json
{
  "userPrompt": "Crie uma tabela de funcionarios respeitando LGPD para CPF.",
  "targetApp": "praxis-ui-angular",
  "targetComponentId": "praxis-dynamic-page-builder",
  "currentRoute": "/page-builder-ia",
  "contextHints": {
    "domainCatalog": {
      "schemaVersion": "praxis.ai.context-hints.domain-catalog/v0.1",
      "serviceKey": "praxis-service",
      "resourceKey": "human-resources.funcionarios",
      "contextKey": "human-resources",
      "type": "governance",
      "intent": "authoring",
      "query": "cpf lgpd funcionarios",
      "limit": 12,
      "relationships": {
        "enabled": true,
        "federated": true,
        "query": "cpf lgpd funcionarios",
        "limit": 8
      }
    }
  }
}
```

The same shape is valid for:

- `POST /api/praxis/config/ai/authoring/intent-resolution`;
- `POST /api/praxis/config/ai/authoring/page-preview`;
- `POST /api/praxis/config/ai/authoring/turn/stream/start`.

## Runtime Meaning

- `serviceKey` identifies the service publisher in the config store.
- `resourceKey` scopes latest-release lookup to one business resource.
- `contextKey` identifies the bounded context for broad grouping.
- `type` selects the catalog item family, usually `node`, `governance` or
  `relationship`.
- `intent` explains why context is being retrieved: `authoring`, `explain`,
  `validate` or `ai-access-control`.
- `query` narrows semantic retrieval to the user's business wording.
- `relationships.federated=true` allows cross-service semantic links when the
  domain spans multiple services.

When a resource candidate is selected from authoring discovery, Praxis derives:

- `contextKey` from `/api/{context}/...`;
- `resourceKey` from `/api/{context}/{resource}`;
- `query` from the user prompt plus the resource name.

## Agent Obligations

An LLM or agent using this envelope must:

- retrieve Domain Catalog context before proposing changes that touch domain
  fields, compliance, privacy, validation or business terminology;
- respect `aiUsage.visibility`, `trainingUse`, `reasoningUse` and
  `ruleAuthoring`;
- prefer `componentEditPlan` or manifest-backed edit operations over free-form
  patches;
- include an explanation that references the domain concepts used;
- leave review-required items as proposals, not direct approvals.

## Safe Output Shape

For authoring, the terminal result should preserve the existing contract:

```json
{
  "intentResolution": {
    "valid": true,
    "artifactKind": "table",
    "selectedCandidate": {
      "resourcePath": "/api/human-resources/funcionarios"
    }
  },
  "preview": {
    "canApply": true,
    "componentEditPlan": {
      "operations": []
    }
  },
  "assistantMessage": "Usei o catalogo de dominio de funcionarios e mantive CPF como dado mascarado com revisao obrigatoria.",
  "canApply": true
}
```

The output may explain governed summaries, but it must not reconstruct hidden
payload fields or expose values marked as `deny`, `mask` or `summarize_only`.

## Why This Matters

This is the first Praxis contract designed for systems maintained by humans and
LLMs together. The LLM does not need to read Java entities, services or Angular
components to understand the domain. It receives a runtime semantic map, a
governed visibility boundary and a manifest-backed editing surface.
