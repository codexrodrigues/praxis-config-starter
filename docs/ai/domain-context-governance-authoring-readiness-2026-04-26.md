# Domain Context Governance Authoring Readiness

Date: 2026-04-26

## Purpose

Record the local readiness scan for the next Praxis cycle: governed semantic
context for LLM authoring.

Praxis is a platform for AI-authored semantic decisions. Before an LLM proposes
or changes a business decision, it must receive domain context through governed
retrieval, not through incidental UI hints or unrestricted prompt text.

## Current Canonical Pieces

### Domain Catalog Prompt Context

`DomainCatalogPromptContextService` already builds prompt blocks from
`contextHints.domainCatalog`.

Observed behavior:

- resolves `serviceKey`, `resourceKey`, `contextKey`, `nodeType`, query and
  relationship hints from `contextHints.domainCatalog`;
- supports federated relationship retrieval when
  `contextHints.domainCatalog.relationships.federated=true`;
- forwards `policyProfile` into `DomainFederationRetrievalPolicyOptions`;
- formats `DOMAIN_CATALOG_CONTEXT`, `DOMAIN_CATALOG_RELATIONSHIPS`, federation
  contracts, federation resolutions and policy reports for prompt consumption;
- preserves retrieval guidance and inline `aiUsage` fields such as visibility,
  training use, rule authoring and reasoning use.

Risk:

- non-federated `domain-catalog/context` formatting currently relies on
  `DomainCatalogIngestionService.contextLatest`, whose direct context path does
  not expose the same explicit `policyProfile=authoring` options as federation;
- prompt formatting is string-based, so the next code PR must be careful not to
  leak masked payloads as unrestricted diagnostics.

### Federation Retrieval Policy

`DomainFederationRetrievalPolicyService` already defines the strongest existing
runtime retrieval policy for LLM-facing domain context.

Profiles:

- `explanation`: `minConfidence=0.7`, `includeDenied=false`,
  `includeLowConfidence=true`;
- `authoring`: `minConfidence=0.8`, `includeDenied=false`,
  `includeLowConfidence=false`;
- `compliance_review`: `minConfidence=0.9`, `includeDenied=false`,
  `includeLowConfidence=false`;
- `diagnostics`: `minConfidence=0.0`, `includeDenied=true`,
  `includeLowConfidence=true`.

Observed behavior:

- excludes `aiUsage.visibility=deny` unless diagnostics explicitly allows it;
- excludes `contract.visibility=restricted` and
  `contract.visibility=deny_for_llm` outside diagnostics;
- excludes `resolution.visibility=restricted` and
  `resolution.visibility=deny_for_llm` outside diagnostics;
- tracks governed summaries for `payloadMode=governed-summary`,
  `contextVisibility=mask` and `contextVisibility=summarize_only`;
- reports decisions, denied counts, governed summary counts and low-confidence
  counts.

Risk:

- this policy is strong for federated context, but authoring must consistently
  request it with `policyProfile=authoring` instead of relying on the default
  `explanation` profile.

### Domain Catalog Hints

`AgenticAuthoringDomainCatalogHints` already enriches quick replies with a
versioned `contextHints.domainCatalog` envelope.

Observed behavior:

- emits schema version `praxis.ai.context-hints.domain-catalog/v0.2`;
- resolves `serviceKey`, `contextKey`, `resourceKey`, query, item type and
  node type from selected API candidates;
- sets `intent=authoring`;
- sets `policyProfile=authoring` by default;
- upgrades to `policyProfile=compliance_review` for prompts involving LGPD,
  GDPR, compliance, governance or privacy;
- enables federated relationships and copies the selected policy profile into
  relationship hints;
- adds `recommendedAuthoringFlow=shared_rule_authoring` for business-rule
  prompts that should route to domain rules.

Risk:

- hints are preserved for a later turn, but they are not by themselves proof
  that the LLM context bundle already consumed the governed context pack before
  planning.

### Agentic Authoring Context Bundle

`AgenticAuthoringContextBundle` is the structured payload used by the LLM intent
resolver.

Observed behavior:

- includes runtime context, user intent, candidate resources, component
  capabilities, conversation messages, attachments and raw `contextHints`;
- includes tool catalog guidance for `resource-candidates`;
- currently stores `contextHints` inside `conversationContext`.

Gap:

- the bundle does not yet have a first-class `domainContext` or
  `governedSemanticContext` section populated from `domain-catalog/context`;
- the LLM may see the hints and decide to ask for another tool call, but the
  backend has not yet guaranteed that business-rule planning always starts with
  a governed context pack.

## Readiness Matrix

| Area | Current state | Next action |
| --- | --- | --- |
| Context hint schema | Present as `praxis.ai.context-hints.domain-catalog/v0.2` | Preserve and document as input contract |
| Policy profiles | Present in federation retrieval policy | Require `authoring` for business-rule planning |
| Denied content filtering | Present in federation retrieval policy | Keep `includeDenied=false` outside diagnostics |
| Mask/summarize handling | Present as governed summaries | Ensure prompt diagnostics never expand masked payloads |
| LLM context bundle | Structured, but no first-class governed domain context | Add explicit governed context section before planning |
| Runtime proof | Existing domain catalog/federation smokes | Add focused authoring smoke marker in later PR |
| UI/cockpit explanation | Not in scope yet | Explain only after backend contract is firm |

## Recommended PR Sequence

### PR 1 - Contract Readiness

Status: this document.

Outcome:

- confirms the existing policy and context pieces;
- identifies the real implementation gap: explicit injection of governed domain
  context into the LLM authoring bundle.

Validation:

- local source scan only;
- no GitHub Actions;
- no Maven Central or npm publication.

### PR 2 - Authoring Context Contract

Status: implemented locally on `codex/domain-context-governance-readiness`.

Add or document a first-class authoring context section, for example
`governedDomainContext`, with:

- `schemaVersion=praxis-agentic-authoring-governed-domain-context.v1`;
- `source=domain-catalog/context`;
- `policyProfile`;
- `available`;
- `resolutionStatus`;
- `requested`;
- `usageRule`;
- `promptBlock`.

The implementation injects the prompt block resolved by
`DomainCatalogPromptContextService` into
`contextBundle.governedDomainContext` before the LLM provider call. The field is
also visible in `llmDiagnostics.request.contextBundle` when diagnostics are
requested.

The section must not include denied payloads outside diagnostics.

### PR 3 - Authoring Injection

Status: implemented locally for the LLM intent resolver path.

For business-rule authoring:

- `contextHints.domainCatalog` is resolved by the existing
  `DomainCatalogPromptContextService`;
- the generated context is inserted into the LLM bundle before intent planning;
- authoring policy remains governed by the context hints and the federation
  service defaults to `authoring` when no stricter profile is requested.

Validation on 2026-04-26:

```bash
mvn -B -Dtest=AgenticAuthoringLlmIntentResolverServiceTest,DomainCatalogPromptContextServiceTest,DomainFederationRetrievalPolicyServiceTest,AgenticAuthoringDomainCatalogHintsTest test
mvn -B -Dtest=AgenticAuthoringAutoConfigurationTest test
```

Both focal validations passed locally without GitHub Actions.

### PR 4 - Operational Proof

Add a focused quickstart/runtime smoke marker such as:

```text
domainContextGovernanceAuthoringSeen=true
```

The proof should show that denied content is excluded and masked/summarized
content is presented only as governed context before a decision proposal.

## Guardrails

- Do not create another decision API. Authoring still ends in
  `/api/praxis/config/domain-rules/**`.
- Do not make `includeDenied=true` the default for authoring.
- Do not leak masked payloads in diagnostics, prompt snapshots or public docs.
- Do not turn this into a RAG side channel outside Domain Catalog/Federation.
- Do not publish Maven/npm for this readiness step.

## Checkpoint Question

Before implementing the next PR:

> Does this change make the LLM's semantic grounding more governed before it
> authors a decision, or does it only pass more loose hints into the prompt?

If it only passes loose hints, redesign it around `domain-catalog/context` or
the federation retrieval policy.
