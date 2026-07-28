# Independent Review Checklist

## Review stance

The reviewer verifies evidence and architecture; it does not accept the executor's completion claim
at face value. Review the actual diff, generated reports and focused test output.

## Scope and hygiene

- [ ] The executor worked only on the active phase.
- [ ] Root and local AGENTS rules were followed.
- [ ] Pre-existing user changes were preserved and separated.
- [ ] No destructive Git operation, unrequested publication or deploy occurred.
- [ ] Canonical owner and impacted consumers are correct.
- [ ] Derived artifacts were regenerated from source rather than hand-edited.
- [ ] Validation is proportionate and honestly reported.

## Architecture

- [ ] No parallel Semantic IR, RAG, page model, registry or runtime observation layer was created.
- [ ] LLM semantic judgment is separate from deterministic platform enforcement.
- [ ] No primary keyword/regex routing was introduced.
- [ ] Domain business decisions remain outside component configuration ownership.
- [ ] Component-specific behavior was not promoted as universal without cross-component evidence.
- [ ] New contracts, if any, are supported by a `lacuna-real-de-contrato` inventory.

## Coverage semantics

- [ ] Public paths are classified rather than assumed authorable.
- [ ] Generated family/profile copies are not counted as independent source manifests.
- [ ] Resolver, validator and handler IDs are proven executable.
- [ ] Dependency closure is tested from minimal/default states.
- [ ] Post-state/invariants are checked independently of compilation success.
- [ ] Runtime outcome proof exists for visible/interactive claims.
- [ ] Explanation fidelity verifies claims, not message presence.
- [ ] Negative and conflicting cases are included.

## Knowledge and provider resources

- [ ] Skills are actually attached/pinned when a claim depends on them.
- [ ] Skills contain procedure, not mutable copies of canonical component truth.
- [ ] Retrieval evidence is tied to current release/hash and tenant/environment.
- [ ] File Search/vector rows are treated as derived indexes.
- [ ] Tool Search/MCP adapters preserve internal canonical tools and authorization.
- [ ] Real-model metrics identify model, effort, case and repeated-run variance.
- [ ] Secrets/private rows are absent from artifacts and logs.

## Phase checkpoint

- [ ] Every deliverable in the active phase exists.
- [ ] Every checkpoint criterion has direct evidence or is marked open.
- [ ] Counts reconcile with baseline or source deltas explain them.
- [ ] `CURRENT-STATE.md` matches the actual state.
- [ ] The next phase has not been prematurely marked ready.
- [ ] The handoff lists what remains unvalidated.

## Review outcome

Use one result:

- `accepted`: checkpoint is closed; next phase may start.
- `accepted-with-nonblocking-followups`: checkpoint closes and follow-ups are explicitly assigned.
- `changes-required`: list actionable findings ordered by severity; checkpoint remains open.
- `blocked`: missing evidence/external state prevents a reliable decision.

For each finding record:

```text
severity
claim/checkpoint affected
file or artifact
evidence
required correction
minimum revalidation
```

Do not approve based only on documentation quality when the phase claims executable behavior.
