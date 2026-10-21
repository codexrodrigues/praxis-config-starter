# G2 Config — historical verification and runtime admission

Historical first-stage record. The later authorized publication/bootstrap expansion and
remote evidence are recorded in [the final increment report](g2-publication-activation-validation-2026-10-21.md).
Statements below describe this earlier stage only.

21/10/2026. Implementation increment for review, not completion of G2. Baseline:
`f358533bd7e08aed1334a00567caa0361f06867e` (`main`), isolated checkout
`.worktrees/payroll-g2-config`. No commit, publication, deployment, database mutation or
G3 implementation was performed. Review and acceptance belong to the coordinator.

## Inventory, classification and impact

Classification: `arquitetural`, `contrato-publico`, `transversal`. Canonical owner:
Config Starter snapshot persistence/governance. The supersession path already verified
stored envelope/hash/composition approvals without requiring the current compiler;
historical reads were `suportado-parcialmente`. The missing incompatibility classification
is `lacuna-real-de-contrato` in the existing `governanceState` field, not a new DTO/endpoint.

Plan executed: factor that existing verification; separate historical detail from current
execution admission; adopt published engine beta.16; project the state and actions through
existing DTOs; reconcile rollout admission and caching; run focal tests and package.

Consumers: authenticated immutable detail readers, version catalogs/Policy Studio, head
status, in-process active-head adapter and candidate rollout/preload. Public OpenAPI
annotations and local snapshot/staged-readiness docs changed. External Core/Studio/host,
HTTP corpus, public site and canonical skill updates remain coordinated downstream work.
No new persistence schema, endpoint, DTO or Config Contracts version was introduced.
There is a beta public-constructor change for manual `DomainRuleRolloutService` wiring:
it now receives the existing host-owned `DomainRuleImplementationCatalog`; auto-configuration
and both repository call sites were updated.

## Result and invariants

`DomainRuleSnapshotVerifier` is one package-private owner for historical integrity,
current runtime admission and catalog coordinates. Snapshot and rollout services use it
without a service dependency cycle or another execution/compiler contract.

- Historical detail and supersession verify identity, canonical hash, composition and
  approvals without compiling under the current runtime/catalog. Intact incompatible or
  subsequently revoked content remains readable by authorized historical readers.
- State precedence is integrity first. Intact pre-manifest content requires republication,
  but detail remains denied; corrupt pre-manifest content is INVALID. A different exact
  engine/dialect/corpus tuple is RUNTIME_INCOMPATIBLE only after integrity succeeds.
- READY still requires current compiler/catalog admission; catalog revocation or compilation
  failure is INVALID. `findActive`, activation and rollback keep executable verification.
- Every non-READY version is UNAVAILABLE even when `active=true` records selection history.
- Head status always returns 200/no-store while preserving its head ETag as the mutation
  token. Runtime/catalog admission can change without rotating that head, so an incoming
  If-None-Match must not preserve stale readiness through 304.
- Rollout creation rejects a non-READY candidate before policy bootstrap/rollout writes.
  Existing rollout readiness rechecks canonical admission and suppresses activation/actions
  after upgrade or revocation, including OBSERVE_ONLY. Observational pending/probes cannot
  grant execution; final activation remains verified under the head lock.
- Snapshot bytes, content hashes, manifests, approval evidence, publication identities and
  existing event history are never rewritten by these readers. Tests assert no head/event
  mutation on executable rejection.

The version-catalog fixture and rollout fixtures now contain real envelopes, canonical
hashes and approval evidence. The PostgreSQL row-version fixture was adjusted to the new
constructor/admission requirements and compiled, but was not executed.

## Validation and provenance

Runtime: `/Users/rodrigo/.jdks/jdk-21.0.10+7/Contents/Home`;
Maven: `/Users/rodrigo/.maven/current/bin/mvn`.

Final command, from the checkout root:

```sh
JAVA_HOME=/Users/rodrigo/.jdks/jdk-21.0.10+7/Contents/Home /Users/rodrigo/.maven/current/bin/mvn -B -Dtest=DomainRuleSnapshotServiceTest,DomainRuleSnapshotControllerTest,DomainRuleHostStatusServiceTest,DomainRuleHostStatusControllerTest,DomainRuleSnapshotHeadReaderAdapterTest,DomainRuleSnapshotAutoConfigurationTest,DomainRuleRolloutServiceTest,DomainRuleRolloutControllerTest,DomainRuleRolloutPolicyServiceTest,DomainRuleRolloutPolicyControllerTest package
```

Result: BUILD SUCCESS, 103 tests, zero failures/errors/skips. Counts: snapshot service 45,
snapshot controller 10, rollout service 19, rollout policy service 12, auto-configuration 5,
host-status service 3, head-reader adapter 3, and three other controllers with 2 each.
Raw log: `target/g2-package-final.log`; Surefire XML/text: `target/surefire-reports/`.

Coverage includes exact engine/dialect/corpus mismatch, payload/hash/envelope/manifest
corruption, partial manifests, approval identity/role/hash/key failures, invalid reviewed
Test Run evidence, pre-manifest integrity, catalog revocation, historical read isolation by
tenant/environment, selection/action directions, immutable rejection and rollover
supersession, active-head reader behavior, no-store status and stale If-None-Match,
rollout rejection and preserved OBSERVE_ONLY action suppression.

The earlier focal run exposed the payload-less catalog fixture; after replacing it, 53
focal tests passed. During helper extraction a missing Collection import caused a compile
failure; that import was restored before the successful final package. No product failure
was suppressed or test skipped.

The focal scope was selected because this increment changes snapshot admission/readers,
rollout projections and wiring. Database/HTTP/consumer gates remain separate: no local or
embedded PostgreSQL was started, no Neon proof was attempted, and no integration test is
claimed. No `mvn install` was run. Maven resolved beta.16 from Central.

Candidate artifact (not a released coordinate):
`target/praxis-config-starter-0.1.0-rc.163.jar`, SHA-256
`a27136a9a70547d69456e4bd8dfe0d5ad7a61144b74af46e57426221566db1f2`.
Resolved published engine JAR SHA-256:
`c214084a9aeff5f8af1346eb67e9a303e61f3a310c7e543883afce080aaf2db7`.
Dependency-tree command:
`mvn -B dependency:tree -Dincludes=io.github.codexrodrigues:praxis-rules-engine,io.github.codexrodrigues:praxis-config-contracts`;
raw log `target/g2-dependency-tree.log`.

OpenAPI source audit: the versioned
`docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml` does not model snapshot routes.
It was therefore not extended with unrelated routes. Snapshot controller/DTO annotations
are the effective OpenAPI source updated here; live generated host OpenAPI remains a
future HTTP proof. `git diff --check` passed.

## Limits and next work

A separate real contract gap was confirmed: current publish persists and activates a head
unconditionally; it does not yet provide the publish-without-activation behavior assumed
by D4. Publication/bootstrap separation and rollback policy enforcement are under a
separate canonical design/review. They were not changed here. This package must not be
used to claim that an inactive candidate can already be published legitimately or that
bootstrap/cutover is approved. G2 remains open and G3 has not started.

Skill assessment: `atualizar-existente`. Inspected canonical
`codex-skills/praxis-config-domain-decisions/SKILL.md` from `origin/main`
`e44f3d90a55dddcf809c31d5a86ce1242f067976` in `praxis-codex-skills`.
The historical/runtime distinction, new state, status cache policy and shared rollout
admission need canonical guidance. No canonical skill or installed mirror was edited by
this worker; that update/integration/synchronization remains assigned to the coordinator.
