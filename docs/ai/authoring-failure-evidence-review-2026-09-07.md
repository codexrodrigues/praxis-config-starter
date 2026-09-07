# Mission authoring failure evidence — 2026-09-07

## Scope and impact

Classification: `transversal`, confined to test instrumentation, operational evidence
and its artifact upload. Adherence: `ja-suportado-mal-nomeado-ou-mal-materializado`.
The existing terminal diagnostics and governed-state projection already contain the
needed observations. No endpoint, public runtime contract, business rule or apply
permission changes.

- Angular: the mission E2E attaches the existing projection after each settled turn,
  before assertions can abort the scenario.
- Config: the runner exports the allowlisted derivative before functional receipt
  validation and before rejecting a failed Playwright exit code.
- Workflow: uploads only `governed-state-turns.json`, not the private browser report.
- Quickstart, Metadata, landing, component manifests and HTTP corpus: unchanged;
  no runtime/contract semantics or operational examples changed.
- Breaking-change risk: none for public consumers. A later live run must pin the
  updated Angular and Config SHAs together to obtain these new observations.

The failed paid run `34169286795` supplied no terminal projection for the mission.
Its absent evidence cannot be recovered by a fixture or retroactively reconstructed.
The original failure remains unresolved; the functional gate remains blocking.

## Local proof

Config: 69 Node tests passed across governed-state export, provider telemetry export,
runner source guards, canonical gate-profile resolution and functional-evidence validation.

Angular: 19 Node tests passed (17 existing source-audit tests and two new instrumentation
placement guards). The production-like source audit passed. Playwright `--list` loaded
the changed spec and discovered all three canonical smoke tests. Listing is not browser
execution, and source guards are not functional UI proof.

`git diff --check` passed in both worktrees. Existing macOS-owned Node dependencies
were used read-only for discovery; no dependency/workspace configuration was changed.
Angular MCP best practices were retrieved: preserve strict typing, inferred local
types, and focused responsibilities; this patch changes no component or template.
The documentation search returned no result, so no documentation citation is claimed.

PowerShell execution was not validated: `pwsh` is unavailable in this environment.
Node tests assert export ordering and retained failure handling in the Windows runner;
they do not replace executing that runner. No Maven/lib build was necessary for this
test/tool-only change. No live browser journey, provider call, release or deploy was run.

## Privacy and evidence limits

Only structured fields are selected; free-text reasons, canonical action payloads,
prompts, raw errors, titles and business identifiers are omitted. Diagnostic code and
reply-ID fields accept bounded tokens, never labels or messages. Missing observations
remain absent or null; an explicitly empty quick-reply list stays empty. Separate
turns and retries remain separately attributed. The exporter does not infer a root
cause, functional success or production-like approval.

A failure before a turn settles or before its projection can be read can still leave
no observation. Provider telemetry is independently captured in the existing finally
blocks. The fixture covers blocked and applicable projections, privacy filtering,
unknown values, repeated attempts, invalid/path attachments and bounded arrays.

The installed validation/provider skills have no exact canonical counterpart in this
checkout's `codex-skills/`. Their reusable lesson is recorded here: capture bounded
terminal diagnostics before functional assertions, export independently of receipts,
and never repeat a paid journey just to diagnose an export failure. No speculative
skill copy or local-only synchronization was performed.

## Next gate

Integrate both reviewed branches, then obtain deliberate approval for a single bounded
`page-builder/smoke` paid run pinned to their immutable SHAs. Use its sanitized state and
provider evidence to diagnose any remaining block. Release remains held until the
required functional gate actually passes.
