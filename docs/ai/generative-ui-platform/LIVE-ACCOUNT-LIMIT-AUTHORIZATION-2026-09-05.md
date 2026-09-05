# Live journey authorization and preflight

The user authorized continuation without a numeric USD ceiling on 2026-09-05,
relying on the spending limit already configured in their OpenAI account.
That account limit is user-attested, not independently verified by this run.
No billing settings or provider credentials are changed. Praxis remains on
OpenAI `gpt-5-mini`; Astra is not a backend configuration change.

The selected paid release lane is Page Builder `smoke`: one mission workspace
journey, at most three authoring UI submissions, zero Playwright retries, plus
the canonical catalog/embedding preparation. Three human turns do not imply
three provider invocations: the backend may use multiple phases per turn.
No additional paid HTTP lane or landing canary is included in this execution.
Stop on failure; preserve sanitized evidence and execute fixture cleanup.

The existing `humanTurnLimit` matrix property is now applied to `smoke` and
consumed before prompt and quick-reply submissions in the Angular runner.
This is a partially supported test control completed across its existing
Config owner and Angular consumer, with no new public contract or runtime
change. The runner has deterministic tests for exhaustion before transport,
failed attempts consuming admission, invalid limits and journey isolation.

The main Config CI (run 33994903682) reported 2,785 tests with one failure:
the Java compiler receipt in the neutral golden corpus referenced the previous
compiler bytes. All 20 semantic corpus cases passed. The receipt hashes were
regenerated from the actual compiled classes and source closure, preserving
the cases and expectations; the nine focused corpus tests then passed.

This document records authorization and preparation, not a successful paid
journey or a completed deployment. Execution receipts must identify the actual
workflow run, immutable source revisions, result and measured usage separately.
