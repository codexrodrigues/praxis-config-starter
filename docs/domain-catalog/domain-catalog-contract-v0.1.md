# Domain Catalog Contract v0.1

Status: baseline contract  
Schema version: `praxis.domain-catalog/v0.1`  
Date: 2026-04-21

This contract defines the first stable vocabulary layer for Praxis domain
knowledge. It is designed for runtime use by backend services, frontend
builders, analysts and LLM agents without requiring source-code inspection.

The contract is intentionally compatible with the current runtime shape emitted
by `praxis-metadata-starter` and persisted by `praxis-config-starter`.

## Goals

- Publish domain vocabulary in a deterministic JSON shape.
- Preserve evidence that explains where each semantic item came from.
- Support governance metadata for privacy, compliance and LLM visibility.
- Allow multiple services to publish separate releases into a federated catalog.
- Keep executable rules separate from semantic hints.

## Top-Level Shape

```json
{
  "schemaVersion": "praxis.domain-catalog/v0.1",
  "service": {},
  "release": {},
  "contexts": [],
  "nodes": [],
  "edges": [],
  "bindings": [],
  "aliases": [],
  "evidence": [],
  "governance": []
}
```

## Canonical Item Types

| Item type | Meaning | Runtime storage |
| --- | --- | --- |
| `context` | Bounded context, module or business area. | `domain_catalog_item.item_type` |
| `node` | Semantic unit such as resource, field, state, action or policy hint. | `domain_catalog_item.item_type` |
| `edge` | Relationship between two nodes. | `domain_catalog_item.item_type` |
| `binding` | Link from a semantic node to runtime assets. | `domain_catalog_item.item_type` |
| `alias` | Business-language variant for a node. | `domain_catalog_item.item_type` |
| `evidence` | Source proof explaining why an item exists. | `domain_catalog_item.item_type` |
| `governance` | Privacy, compliance, ownership or AI-usage metadata. | `domain_catalog_item.item_type` |

## Canonical Node Types

The runtime already emits `concept`, `field`, `state`, `action`, `surface` and
`policy_hint`. v0.1 keeps these values and adds forward-compatible semantic
types needed by the next architecture steps.

| Node type | Meaning |
| --- | --- |
| `concept` | Generic business concept, usually inferred from an API resource. |
| `resource` | Business resource exposed by an API or service boundary. |
| `entity` | Domain entity, aggregate or persisted business object. |
| `field` | Business field or attribute. |
| `state` | Lifecycle state. |
| `operation` | Business/API operation. |
| `action` | Existing Praxis workflow action. Equivalent to an operation with workflow semantics. |
| `surface` | UI/business interaction surface. |
| `relationship` | Relationship represented as a node when it needs governance or binding. |
| `policy_hint` | Non-executable semantic hint about eligibility, selection or compliance. |
| `llm_visibility` | Node describing what LLMs may see, summarize, transform or use. |

## Governance Minimum

Governance can appear as:

- a top-level `governance` item linked to a `nodeKey`;
- metadata inside a `node`, `binding` or `evidence` payload;
- future declarative semantic artifacts authored by analysts or LLM agents.

The minimum governance vocabulary is:

| Field | Values | Meaning |
| --- | --- | --- |
| `classification` | `public`, `internal`, `confidential`, `restricted` | General business/security classification. |
| `dataCategory` | `none`, `personal`, `sensitive_personal`, `financial`, `health`, `credential`, `legal`, `operational` | Data category used for privacy/compliance decisions. |
| `complianceTags` | Free list, e.g. `LGPD`, `GDPR`, `SOX`, `INTERNAL_POLICY` | Applicable obligations. |
| `owner` | String | Business or platform owner. |
| `steward` | String | Person/team responsible for semantic quality. |
| `retentionPolicy` | String | Retention classification or policy reference. |
| `aiUsage.visibility` | `allow`, `mask`, `summarize_only`, `deny` | What LLMs may see. |
| `aiUsage.trainingUse` | `allow`, `deny` | Whether content can be used for training/fine-tuning workflows. |
| `aiUsage.ruleAuthoring` | `allow`, `review_required`, `deny` | Whether LLMs may use this item to propose or alter rules. |
| `aiUsage.reasoningUse` | `allow`, `review_required`, `deny` | Whether LLMs may use this item as reasoning context. |

## Rule Boundary

`policy_hint` and governance items are not executable rules.

The correct interpretation is:

- `policy_hint`: describes business intent or selection/compliance semantics;
- `governance`: describes ownership, privacy, AI usage and compliance posture;
- executable rule: future versioned artifact, for example `DomainRuleDefinition`;
- materialized form rule: future binding from a reusable rule to a specific
  `FormConfig`.

This boundary prevents the catalog from becoming an accidental policy engine.

## Cross-Service Federation

Each service publishes its own immutable catalog release:

```text
serviceKey + releaseKey + schemaVersion
```

`praxis-config-starter` aggregates releases by:

- `tenantId`;
- `environment`;
- `serviceKey`;
- `releaseKey`.

Cross-service relationships must be explicit edges or relationship nodes. A
service must not rely on an LLM guessing that two similarly named fields mean
the same concept.

Recommended cross-service edge types:

| Edge type | Meaning |
| --- | --- |
| `same_as` | Two nodes represent the same business concept. |
| `depends_on` | One node depends on another service concept. |
| `references` | One field/entity references another service entity. |
| `owned_by` | Node is owned by a context, team or service. |
| `governed_by` | Node is governed by a policy/governance item. |
| `materializes` | Runtime/API/form artifact materializes a semantic concept. |

## LLM Runtime Use

LLMs should consume the catalog through compact runtime APIs, not source code.

Recommended runtime query:

```text
GET /api/praxis/config/domain-catalog/context
```

The response should give the LLM:

- relevant semantic items;
- evidence;
- governance restrictions;
- retrieval guidance;
- source release metadata.

LLMs should not infer executable behavior unless a rule/binding explicitly says
it is executable.

## Compatibility Notes

- Current `concept` nodes remain valid.
- Current `action` nodes remain valid and may later be normalized to
  `operation`.
- Current `surface` nodes remain valid.
- Current `policy_hint` nodes remain valid as non-executable semantic hints.
- The `metadata` map remains the forward-compatible extension point.

