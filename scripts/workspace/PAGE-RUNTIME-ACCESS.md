# Config V70 runtime access

This finite owner operation supplies the missing deployment ACL step for the two
tables introduced by Config rc169/V70. Classification: `local-pequena`, high ACL
risk; adherence `suportado-parcialmente`: persistence already exists, but the
release has no owner/runtime access installer. It changes no SDK, endpoint,
schema, immutable migration, data, role, password, default privilege or runtime
startup. The canonical owner is Config; Quickstart remains a consumer.

The source baseline is tag `v0.1.0-rc.169`, commit
`7f63d5cc2b449657f11fc59d27176bac3477d8ad`.
`PageRuntimeStore.vector` needs catalog SELECT; `PageCatalogStore` reads and inserts
catalog/creation evidence in the existing Config transaction. Both tables are
immutable. The only admitted runtime table privileges are **SELECT and INSERT**
on `public.ui_page_catalog` and `public.ui_page_creation_evidence`, without grant
option. Never run the isolated test preparation as an installation procedure.

## Prerequisites and private input

Use Python 3.9+ with psycopg3 and the existing administrative owner credential.
The owner may have legacy elevated attributes; they are recorded separately and
never accepted as a runtime identity. The runtime must already exist, be ordinary,
be distinct from owner and have no active outbound INHERIT/SET role memberships.
This profile admits no PUBLIC/unexpected grantee, column ACL, grant option,
UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER or MAINTAIN on the two tables.
It also denies runtime schema/database CREATE and permission to change
`session_replication_role`. Schema USAGE must already exist. It grants no role
membership or schema permission and never automatically revokes unexpected access.

The caller must preserve actual Neon control-plane evidence and attest an exclusive
administrative ACL window. That is a coordinator attestation, not an API query by
this tool. Runtime reads/writes may continue until the short apply transaction
locks the two target tables; no other administrator may change relevant roles,
schema, function, tables or ACLs during plan/apply. The table locks cannot fence
an unrelated privileged administrator changing a role. Do not infer exclusivity
from a JSON boolean. No new database or validation branch is created here.

Credentials enter through stdin JSON only, never arguments, environment files,
output, SQL interpolation or receipts. The caller injects `ownerPassword` in memory
from the already configured owner credential. The complete input shape is:

```json
{
  "pins": {
    "project":"<actual Config project>", "branch":"<actual Config branch>",
    "host":"<actual direct ep-...neon.tech host>", "database":"<database>",
    "schema":"public", "ownerRole":"<existing owner>",
    "runtimeRole":"<existing ordinary runtime>", "schemaOwnerRole":"<actual public schema owner>"
  },
  "controlPlane": {
    "source":"root-attestation", "project":"<same project>", "branch":"<same branch>",
    "host":"<same direct host>", "database":"<same database>", "postgresMajor":17,
    "observedAt":"<real UTC observation>", "rootEvidenceSha256":"<preserved evidence SHA256>",
    "exclusiveAclWindow":true
  },
  "ownerPassword":"<injected in memory, never a checked-in literal>"
}
```

The attestation must be at most 30 minutes old, with no future timestamp. The
fingerprint includes its evidence hash and pinned identity, but excludes observation
time/password. Keep the same reviewed evidence reference for plan/apply while fresh.
Host is the direct CP-observed endpoint; pooler hosts are rejected. The connection
requires PG17, `sslmode=verify-full`, `channel_binding=require`, read-write primary,
10 second connect timeout, 5 second statements and 2 second lock timeout. The
physical client SSL flag is checked. There is no weaker transport fallback.
The operator does not change the application's datasource URL; that is a separate,
CP-pinned host configuration operation.

Before connecting, the coordinator must set `PGSSLROOTCERT` to the reviewed,
readable system CA bundle (for example `/etc/ssl/cert.pem` where it exists),
preserving its path/hash in the operation evidence. libpq uses this public trust
configuration for verify-full; without it, the default `~/.postgresql/root.crt`
may be absent and connection fails. Never weaken sslmode or channel binding to
work around a missing trust store. This environment value contains no credential.
The schema owner is an observed pin, not a portable default; this environment's
readback reported `neondb_owner`, not `pg_database_owner`.

## Plan, review, apply

Create a separate empty private directory for each invocation (0700, owned by the
operator, canonical absolute path without symlink ancestors). The script writes
`intent.json` and `result.json` with O_EXCL, 0600 and file/directory fsync. Existing
attempt directories are never reused. Input can be piped from the coordinator's
secret-preserving parent process:

```sh
python3 scripts/workspace/page_runtime_access.py plan --attempt-dir /absolute/private/new-plan
python3 scripts/workspace/page_runtime_access.py apply --expected-fingerprint <reviewed-plan-fingerprint> \
  --attempt-dir /absolute/private/new-apply
```

Both commands read the JSON from stdin. Do not paste a password into a shell
command, commit an input file, or print the parent process environment. Preserve
source SHA256, tag, control-plane evidence, private plan, exact fingerprint and
independent review before apply. The caller should impose an external finite
process deadline (for example 120 seconds); the script has no overall wall-clock
timer and a filesystem operation can stall outside a statement deadline.

Plan starts REPEATABLE READ READ ONLY, checks the observed transaction mode, reads
catalog/privileges only, and explicitly rolls back. It does not repair history,
install schema or bootstrap anything. Successful history V70, exact canonical
columns/constraints/FKs, table ownership, immutable trigger/function definitions,
enabled triggers (including the released-draft guard) and safe ACLs are mandatory.
Unsupported schema/ACL drift fails closed for review. CHECK comparison removes
only parser parentheses/implicit text casts; it does not accept extra predicates.

Apply starts a fresh REPEATABLE READ writable transaction and acquires ACCESS
EXCLUSIVE locks on the **two target tables only**, before inspecting the same
catalog. The fingerprint must match the plan exactly. Only missing SELECT/INSERT
are granted through at most four closed GRANT statements to the pinned existing
role. It re-inspects the safe ACLs and requires exact SI plus unchanged structural
catalog before commit. Already exact access is a zero-GRANT operation. No broad
grant, default privilege, schema/role DDL, table write, retry or repair exists.

A failed precondition/statement is rolled back. A commit transport error is
`COMMIT_UNKNOWN_NO_REPLAY`, even when rollback is subsequently attempted. An
unpersisted result is `LOCAL_RECEIPT_FAILED_NO_REPLAY`. Retain intent/evidence and
inspect actual ACLs; never rename/delete an attempt and retry an uncertain commit.
`APPLY_COMMIT_CONFIRMED_ROOT_READBACK_REQUIRED` confirms this transaction only.
The root must independently read effective table/column privileges through the
ordinary runtime connection after commit, then exercise the original HTTP flow.
No owner execution, offline mock, health response or source merge certifies the
runtime consumer. Avoid another database rehearsal solely to repeat that evidence.

## Offline verification and remaining evidence

```sh
python3 -m unittest discover -s scripts/workspace -p test_page_runtime_access.py -v
```

Tests use an injected fake connector and patched catalog snapshot: no sockets,
database, owner fixture or Maven. They cover mode/readonly checks, input pins,
freshness, runtime closure, forbidden effective/PUBLIC/column/grant-option ACLs,
FK/trigger mutants, stable secret-free fingerprint, four-statement allowlist,
rollback, uncertain commit, exclusive private receipts and no secret output.
They do **not** prove the live PostgreSQL catalog or JDBC/HTTP consumer; the first
real plan and subsequent independently reviewed apply/readback are operational
evidence owned by the coordinator.

Skill impact: `atualizar-existente` in `praxis-config-runtime-persistence`, with
canonical skill governance/manifest review and selective sync owned by the
coordinator. This package changes no public runtime contract, release artifact,
examples or HTTP corpus. No library release is needed to run the reviewed script.
