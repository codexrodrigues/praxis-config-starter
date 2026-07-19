# Machine-First Semantic Grounding and Generative UI Acceptance Corpus

Status: proposed evaluation contract
Version: `0.1.0`
Date: 2026-07-19
Related RFC: [`../2026-07-machine-first-semantic-ir-rfc.md`](../2026-07-machine-first-semantic-ir-rfc.md)

## Purpose

This corpus makes the RFC falsifiable. It tests whether Praxis can move progressively from
business meaning to technical execution and generative UI without loading unrelated APIs,
schemas or component manifests.

Machine-readable cases are stored at:

```text
docs/ai/agentic-authoring/proofs/semantic-grounding-generative-ui-corpus.v0.1.json
```

The corpus complements the existing assistant consistency corpus. It does not replace tests
for streaming lifecycle, persistence, ETag, replay, cancellation or general Page Builder
behavior.

## Evaluation Dimensions

Each case declares:

- abstraction level;
- task mode;
- expected semantic scope;
- allowed and forbidden technical reads;
- accepted resources and operations when applicable;
- whether clarification, answer or preview is allowed;
- provenance and safety requirements.

The evaluator must observe actual backend tools and canonical reads. It must not infer success
only from fluent assistant prose.

## Retrieval Profiles

| Profile | Expected behavior |
| --- | --- |
| `platform` | Explain Praxis capabilities and current surface; no domain/API scan |
| `global-domain` | Use enterprise/domain summaries; zero OpenAPI/schema reads |
| `context` | Use context/capability/concept packs; zero schema reads |
| `resource` | Resolve semantic binding; inspect accepted resources only |
| `execution` | Inspect one selected operation and canonical schema JIT |
| `ui-materialization` | Discover components by capability and inspect only selected manifests |

## Must-Pass Families

### Platform capability

Prompts such as "O que posso fazer aqui?" must explain forms, tables, charts, dashboards,
filters and relevant current-surface possibilities without selecting an arbitrary business
resource.

### Global business discovery

Prompts such as "Quais domínios existem?" or "O que o Praxis sabe sobre RH?" must use
high-level governed summaries and execute zero OpenAPI/schema reads.

### Current-surface explanation

"Sobre o que é este formulário?" must connect runtime component/resource refs to semantic
concepts and then explain the domain. Runtime observations remain untrusted and require
canonical re-grounding.

### Cross-domain sensemaking

"Como compras se relaciona com financeiro?" must traverse explicit governed relationships.
It must not infer equivalence from names or embeddings.

### Resource and field meaning

Questions about employee, employment relationship or CPF should return business meaning,
governance and evidence before any execution contract.

### Explicit technical execution

"Qual operação cria um funcionário?" is allowed to reach resource/action/schema inspection,
but only after resolving the employee concept to its governed resource binding.

### Generative UI on a blank page

"Crie um painel de admissões" must resolve domain, capability, metrics and resources before
component discovery. The result must compile as a `UiCompositionPlan`; no component inputs,
fields or actions may be invented.

### Ambiguity and failure

Missing binding, conflicting evidence, stale release, tenant mismatch, denied AI visibility,
component incompatibility or unavailable vector index must produce governed clarification,
structured fallback or fail-closed behavior.

## Global Must-Pass Metrics

- unrelated OpenAPI reads: `0` for `platform`, `global-domain` and `context` cases;
- hallucinated resource, operation, field, component or input: `0`;
- provenance coverage for material selections: `100%`;
- inferred-unapproved claims used as truth: `0`;
- cross-tenant or cross-environment reads: `0`;
- every preview has a valid compiled `UiCompositionPlan`;
- every mutation requires preview and governed apply;
- vector outage preserves correct structured retrieval or honest clarification.

Performance budgets are initially diagnostic, not release blockers, until a local baseline is
captured. The evaluator must still record:

- time to first useful status;
- time to terminal result;
- provider calls and tokens;
- canonical reads by artifact type;
- candidates considered, accepted and rejected;
- cache hits;
- repair attempts.

## Evaluator Output

One result per case should contain:

```json
{
  "caseId": "domain-hr-overview-pt",
  "passed": true,
  "semanticIntent": {},
  "retrievalProfile": "global-domain",
  "canonicalReads": [],
  "candidateFunnel": [],
  "evidenceRefs": [],
  "selectionRefs": [],
  "uiPlan": null,
  "terminalAuthority": "answer",
  "metrics": {},
  "violations": []
}
```

The result is evidence, not a new source of semantic truth.

## Promotion Gate

The corpus can become a release gate only after:

1. the proposed Semantic IR and tools have stable contracts;
2. a deterministic local runner validates the JSON corpus;
3. the runner records actual backend reads and tool events;
4. must-pass cases are stable across repeated real-LLM runs;
5. semantic correctness assertions are separated from provider wording;
6. no test depends on keyword-based intent routing.

## Relationship To Existing Gates

- Assistant consistency corpus: conversational and Page Builder behavior.
- This corpus: semantic abstraction, retrieval economy, provenance and materialization chain.
- Metadata tests: deterministic catalog generation and schema validity.
- Config tests: governance, release, RAG and tool contracts.
- Quickstart HTTP smoke: real host integration.
- Page Builder E2E: final UI preview/apply and user-facing stream behavior.
