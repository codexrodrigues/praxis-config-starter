# AI memory + PII notes

## Memory summary + pruning
- Summary is updated server-side when total messages exceed the threshold.
- Summary uses the older messages (excluding the tail window) and is stored in `ai_thread.summary`.
- Older messages are marked as redacted and their content is cleared after summarization.
- Behavior can be tuned via `praxis.ai.memory.summary.*` and `praxis.ai.memory.prune.redact`.
- Turn idempotency uses `ai_turn` with TTL (`praxis.ai.memory.turn.timeout-seconds`).
- When a turn is still processing, the backend may return an `info` response like "Turno em processamento." so the client can wait/retry.

## PII scrubbing
- Persisted `ai_message.content` is scrubbed for basic PII patterns before storage.
- Current redactions: email, phone, CPF, CNPJ, credit card-like numbers.
- Redaction is tracked via `ai_message.redacted`.
- Card redaction only triggers for numbers that pass Luhn validation.
- Can be disabled with `praxis.ai.memory.scrub.enabled=false`.
