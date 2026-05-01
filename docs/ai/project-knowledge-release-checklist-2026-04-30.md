# Project Knowledge Release Checklist

Date: 2026-04-30

Scope: operational checklist for closing the governed Project Knowledge beta
checkpoint without spending GitHub Actions during normal iteration.

## Decision

Use this checklist only when the team intentionally decides to close the
Project Knowledge checkpoint as a phase gate.

Do not use it to justify a Maven Central or npm publication by itself.

## Current Phase State

The original checkpoint is complete as internal beta evidence for read-only
governed Project Knowledge:

- canonical storage remains the Domain Knowledge layer;
- authoring influence is read-only and filtered to approved, active and
  AI-visible safe projections;
- backend diagnostics derive audit counts from safe entries;
- Page Builder renders citation-count explanation only;
- local browser/LLM proof exists through a versioned wrapper in
  `praxis-ui-angular`.

As of 2026-05-01, the next beta slice also has source-level evidence for
governed writes:

- LLM-authored continuation of Project Knowledge goes through
  `/api/praxis/config/domain-knowledge/change-sets`;
- the Page Builder cockpit can create, validate, approve, apply and read back
  a governed `add_evidence` proposal;
- unsupported evidence payloads are rejected by config-starter validation
  before database constraints can surface as generic runtime failures;
- source semantics such as `project_preference` remain payload/source metadata,
  while persisted evidence type stays canonical (`llm_proposal` in the Page
  Builder continuation path).

## Local-First Gates

Run these locally before any remote gate.

### Config Starter Focal Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -Dtest=AgenticAuthoringPreviewServiceTest test
```

Expected:

- all `AgenticAuthoringPreviewServiceTest` tests pass;
- no Project Knowledge audit counter trusts client/context-supplied counts.

### Browser/LLM Cockpit Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

AI_PROVIDER=openai \
AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh \
PRAXIS_E2E_TIMEOUT_MS=600000 \
./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh
```

Expected:

- the wrapper installs the local config starter into the local Maven repository;
- quickstart starts on an isolated API port;
- Angular starts on an isolated UI port;
- Playwright proves the Project Knowledge cockpit creates a governed Domain
  Knowledge change-set;
- the cockpit validates, approves, applies and reads back the proposal through
  the backend;
- the cockpit does not expose raw payloads, concept keys, source summaries or
  knowledge summaries.

### Docs Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

git diff --check
```

Expected:

- no whitespace errors;
- release-readiness links point to files that exist;
- the docs still say this checkpoint is not a publication trigger by itself.

## Remote Gate Budget

Keep the default budget at zero Actions during implementation.

Use at most one remote gate when closing the phase:

- merge the docs/checkpoint PR only when the team accepts one `main` CI run for
  documentation closure; or
- run `Agentic Authoring HTTP Smoke` only if the phase is being promoted toward
  Maven Central publication.

Do not run both unless there is an explicit release decision.

## Maven Central Decision

Do not publish a new Maven Central version for this checkpoint unless all of
the following are true:

- a named downstream consumer needs the Project Knowledge checkpoint from a
  public artifact;
- local config starter focal tests passed;
- local browser/LLM cockpit proof passed;
- the intended Maven version is explicit and not already tagged;
- the `Agentic Authoring HTTP Smoke` remote gate is intentionally selected as
  the release gate.

If those conditions are not met, keep the checkpoint as source-level beta
evidence.

## npm Decision

Do not publish npm packages for this checkpoint unless an external Angular
consumer explicitly needs to install the Page Builder audit cockpit support from
the public registry.

Source-level validation in `praxis-ui-angular` is sufficient for internal beta
evidence.

## Merge Timing

Recommended merge timing for the docs checkpoint PR:

1. Keep the PR as draft while additional release-readiness documentation is
   being accumulated.
2. Mark it ready only when we intentionally accept one `main` CI run.
3. Merge it before starting a new capability cut so future agents treat Phase 7
   as closed.

## Stop Conditions

Do not close this checkpoint if any of these happen:

- local browser/LLM proof fails and the failure is not understood;
- Page Builder exposes raw Project Knowledge payload, concept key, source
  summary or knowledge summary;
- backend audit counters can be manipulated by context/client-supplied counts;
- the quickstart fixture depends on a local-only database path instead of the
  configured operational datasource;
- closing the PR would trigger repeated Actions reruns for a docs-only change.

## Next Capability Is Not Included

This checklist does not include:

- vector/RAG ranking;
- external package publication.

Rollback, broader operation types beyond the current governed `add_evidence`
slice, vector/RAG ranking and external publication still require separate
implementation plans and separate gates.
