# Agentic Domain Task Envelope

## Purpose

Praxis authoring agents must operate from runtime contracts instead of source
code inspection. A domain-aware task therefore needs a small envelope that tells
the backend which business resource the user is talking about, which semantic
context may be retrieved, and which governed output shape is expected.

This envelope is not a rules engine. It is the bridge between user intent,
Domain Catalog context, AI visibility constraints and the governed authoring
surfaces exposed by Praxis.

## Canonical Request Shape

```json
{
  "userPrompt": "Crie uma tabela de funcionarios respeitando LGPD para CPF.",
  "targetApp": "praxis-ui-angular",
  "targetComponentId": "praxis-dynamic-page-builder",
  "currentRoute": "/page-builder-ia",
  "contextHints": {
    "domainCatalog": {
      "schemaVersion": "praxis.ai.context-hints.domain-catalog/v0.2",
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
- `recommendedAuthoringFlow=shared_rule_authoring` means the request should be
  routed to the shared rule/decision flow under
  `/api/praxis/config/domain-rules/**`, instead of being materialized first as
  a page/component preview.
- Even when the frontend has not sent `recommendedAuthoringFlow`, intent
  resolution may infer the same governed route from prompts that ask for a
  business rule, validation, policy, eligibility, compliance or decision over a
  resolved resource. This keeps older clients from accidentally pushing
  business-rule authoring into `componentEditPlan`.
- `recommendedRuleType` seeds the canonical shared-rule type when the request
  is routed to `shared_rule_authoring`, so hosts can open `simulation` without
  inventing a local rule taxonomy.

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
- route business-rule or shared-decision requests to
  `/api/praxis/config/domain-rules/**` when `recommendedAuthoringFlow` asks for
  `shared_rule_authoring`;
- prefer `POST /api/praxis/config/domain-rules/intake` before ad hoc draft
  construction when the host is opening a new shared-rule flow from natural
  language;
- treat `POST /api/praxis/config/domain-rules/simulations` as the canonical
  backend explanation step for shared-rule impact, grounding and governance,
  instead of rebuilding that explanation only in the frontend host;
- use `POST /api/praxis/config/domain-rules/publications` as the canonical
  promotion step once the persisted definition reports
  `explainability.publicationReadiness=ready_to_publish`; for
  `selection_eligibility`, the publication flow may already derive the
  canonical `option_source` materialization payload instead of forcing the host
  to handcraft a local lookup policy patch; for `validation`, `compliance` and
  `privacy`, the same publication flow may derive a canonical
  `backend_validation` materialization payload for downstream enforcement;
- only prefer `componentEditPlan` or manifest-backed edit operations when the
  request remains in component/page authoring;
- include an explanation that references the domain concepts used;
- leave review-required items as proposals, not direct approvals.

## Canonical Intent Routing Matrix

The intent resolver must classify the user's request before any preview or
patch is produced. This matrix is the canonical routing baseline for the first
semantic-decision platform cut:

| User intent | Typical prompts | Canonical route | Expected result |
| --- | --- | --- | --- |
| Shared business rule | "fornecedor bloqueado nao pode ser selecionado", "crie uma politica de elegibilidade para fornecedores inativos" | `/api/praxis/config/domain-rules/**` | `gate.status=route_required`, `failureCodes=["shared-rule-authoring-required"]` |
| Compliance or privacy decision | "crie uma regra LGPD para CPF", "dados sensiveis exigem revisao antes de aprovar" | `/api/praxis/config/domain-rules/**` | governed intake/simulation handoff, not component preview |
| Backend validation policy | "validar que pedido de compra sem aprovacao nao pode seguir", "bloquear status invalido no backend" | `/api/praxis/config/domain-rules/**` | semantic rule proposal with derived `backend_validation` target when publishable |
| Option-source eligibility | "fornecedor inactive ou blocked nao pode aparecer como selecionavel" | `/api/praxis/config/domain-rules/**` | semantic rule proposal with derived `option_source` target when publishable |
| Component/page authoring | "crie um formulario de funcionarios", "adicione campo salario no formulario", "monte um dashboard de folha" | `/api/praxis/config/ai/authoring/**` | eligible page/component preview with `componentEditPlan` or `uiCompositionPlan` |
| API catalog Q&A | "quais endpoints existem para funcionarios?", "qual schema devo usar para folha?" | `/api/praxis/config/ai/authoring/**` as API catalog answer | informative answer; no domain-rule draft and no page preview unless the next turn asks for creation |

When a prompt contains both business-rule and component words, the decision
semantics wins unless the request is clearly visual/editorial. For example,
"crie uma regra para CPF no formulario" is shared-rule authoring; "adicione o
campo CPF no formulario" is component/page authoring.

The resolver may use `recommendedAuthoringFlow=shared_rule_authoring` as a
strong host hint, but it must not depend exclusively on that hint. Older
clients that only send a prompt and a resolved business resource must still be
protected from routing governed business decisions into `componentEditPlan`.
The deterministic guardrail must therefore recognize governance language such
as approval, review, masking, privacy, sensitive data and backend validation
when a business resource is already resolved, even if the frontend has not sent
the explicit recommended flow hint.

## Safe Output Shape

For component/page authoring, the terminal result should preserve the existing
contract:

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

For shared rule/decision authoring, the safe output shape is a governed route,
not a page preview:

```json
{
  "intentResolution": {
    "valid": false,
    "selectedCandidate": {
      "resourcePath": "/api/human-resources/funcionarios"
    },
    "gate": {
      "status": "route_required",
      "messages": ["shared-rule-authoring-required"]
    }
  },
  "assistantMessage": "Esse pedido deve seguir pela trilha governada de regra compartilhada em /api/praxis/config/domain-rules, e nao pelo preview de formulario/pagina. Use POST /api/praxis/config/domain-rules/intake com o recurso /api/human-resources/funcionarios como grounding canonico, depois POST /api/praxis/config/domain-rules/simulations para validar cobertura, aprovacoes e materializacoes antes de publicar.",
  "canApply": false
}
```

## Why This Matters

This is the first Praxis contract designed for systems maintained by humans and
LLMs together. The LLM does not need to read Java entities, services or Angular
components to understand the domain. It receives a runtime semantic map, a
governed visibility boundary and a manifest-backed editing surface.
