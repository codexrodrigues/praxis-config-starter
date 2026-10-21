# G2 Config — publication, bootstrap and runtime admission

2026-10-21. Config-owned architectural/public-contract increment, on baseline
`f358533bd7e08aed1334a00567caa0361f06867e`. The coordinator independently reviewed
the historical verifier increment, the canonical publication/activation design and
the final production diff. This report supersedes the implementation limits of the
[first-stage historical report](g2-runtime-history-validation-2026-10-21.md), while
retaining that report as the evidence of its earlier source/dependency stage.

## Inventory and contract decision

Historical integrity verification already existed in supersession and was only partly
materialized in readers. It is now one package-private verifier shared by snapshot and
rollout services. Integrity precedes tuple compatibility; current READY/execution admission
still uses the current compiler/catalog. Intact historical incompatible or later-revoked
content remains readable, while pre-manifest detail is denied and corruption is INVALID.
All non-READY version actions are UNAVAILABLE. Operational head/status always re-evaluates
with no-store rather than returning 304 from a head token whose readiness may have changed.

Publication-only was a real contract gap: the old publish operation always selected the
head and bypassed activation governance. The reviewed canonical correction changes the
existing POST response to the existing `DomainRuleSnapshotStoredResponse`, preserves
immutable content/hash and appends only PUBLISHED. New publication events never carry a
selection revision or head ETag; historical selected-publication events are retained.
No parallel endpoint, DTO, compatibility mode, synthetic head or implicit allow-all was added.
The Contracts beta.5 composition receipt contains only the four immutable publication
components. Head selection remains explicit through activate/rollback.

An absent head is a real condition: If-None-Match:* plus paired-null rollout expectation.
Bootstrap requires an independently governed REQUIRED policy, positive quorum, real host
preload evidence and conditional explicit selection. READY without head offers
CREATE_ROLLOUT. Existing-head mutations use strong If-Match; competing conditions fail
closed. Rollback keeps its existing emergency no-quorum norm but verifies current
runtime/catalog, validity, direction and CAS.

Publication, selection, rollout creation/cancellation and policy mutation share a
PostgreSQL transaction advisory scope lock. Config READ COMMITTED writable transactions
are required. Dirty external contexts are rejected before queries; clean lifecycle inputs
are loaded without a stale-version repository lock, then refreshed with PESSIMISTIC_WRITE.
Publication refreshes approved source definitions too. Observational probe updates lock
the rollout row only and do not select a head; final lifecycle admission rechecks evidence
under the scope lock. Policy replacement flushes the previous SUPERSEDED policy before
saving ACTIVE to honor immediate uniqueness, within the same rollback-capable transaction.

V69 changes only event/rollout nullability and constraints plus chronological index. It
does not rewrite old rows, snapshot payloads, hashes, approvals or publication identities.
Manual Java wiring now requires the catalog, activation gate, real scope lock and refresh
owner; auto-configuration supplies canonical infrastructure and a fail-closed missing gate.

## Impact and derived artifacts

Canonical owner is Config Starter. Consumers include Contracts composition receipts,
Quickstart composition/runtime/probe callers, Core API types and Policy Studio. Root
coordinates those downstream changes and public documentation/corpus adoption. Local
README, snapshot control plane, staged readiness and execution-observation docs plus
controller/DTO OpenAPI annotations were updated. The versioned AI OpenAPI YAML does not
model snapshot routes and was not expanded with unrelated routes; generated host OpenAPI
remains a downstream HTTP proof. Existing Java callers/test sources compile with the new
constructors and signatures. Existing embedded integration sources were not executed.

## Remote PostgreSQL evidence

Exclusive assignment: Neon project `ancient-leaf-72055027`, branch
`br-small-butterfly-a43abtin`, endpoint
`ep-late-lab-a4ixef5h.us-east-1.aws.neon.tech`, new database `payroll_g2_config`, PG17.11.
Owner `payroll_g2_config_owner` applies only official Flyway migrations; runtime
`payroll_g2_config_runtime` has DML only, no superuser/createdb/createrole/schema CREATE.
No parent database, inherited production rows, CREATE DATABASE/ROLE or global cleanup
was used. Public branch attestation is coordinator-owned at
`docs/engineering/payroll-reactive-p20-20261020/g2-config-neon-attestation.json` in
the monorepo. Private URI files were 0600 and their values were never logged.

The gate checks exact host/database/roles/branch attestation and PG major, verifies
TLS on the actual client SSLSocket, requires verify-full, DefaultJavaSSLFactory and
channelBinding=require. It intentionally does not infer client TLS from Neon-proxy
pg_stat_ssl. Missing/mismatching assignment fails rather than skips. Official Flyway
validated 67 migration files and applied through V69 under the owner; services and
concurrency tests then ran through ordinary runtime privileges.

`DomainRuleSnapshotPublicationNeonIT` is an explicit @Tag(neon) IT, excluded from normal
unit/release selection. It uses real JPA repositories, JpaTransactionManager, PostgreSQL
locks and services. Fixtures contain actual source/approval hashes; policy maker/checker,
composition approvals and host compilation/probe use their real services. Definition
approval rows are seeded as a fixture rather than proving the HTTP governance journey.

Two remote cases passed:

- Publication leaves the head absent and observations reject publication-only identity;
  actual activation admits observation. Concurrent first rollout/activation select once,
  positive quorum is enforced, head/event insertion rolls back on completion failure,
  preloaded external JPA context sees canonical stale-CAS 412 after another selector, and
  duplicate concurrent publication commits once without altering the head.
- Different initial candidate publications receive unique revisions without selecting;
  policy activation versus rollout creation binds the actual authority; publication versus
  first activation honors absent-head CAS. A real post-flush policy replacement failure
  observes the uncommitted previous policy superseded, then proves rollback restores its
  ACTIVE status, previous policy-head ID/ETag/revision, approved replacement and event count.

Initial failed runs were retained, diagnosed and corrected without relaxing schema/assertions:
missing fixture rule_type; incorrect JDBC-holder synchronization assumption for JPA;
stale-version optimistic check before directed refresh; policy flush ordering against
the immediate unique ACTIVE index. These logs are historical failures, not passing proof.

## Commands and results

JDK `/Users/rodrigo/.jdks/jdk-21.0.10+7/Contents/Home`; Maven
`/Users/rodrigo/.maven/current/bin/mvn`. No mvn install, embedded/local database, external
LLM call, tag, merge, deployment or Maven publication was performed by this worker.

Main focal plus remote gate: `mvn -B -Dtest=DomainRuleSnapshotServiceTest,DomainRuleSnapshotControllerTest,DomainRuleSnapshotAutoConfigurationTest,DomainRuleRolloutServiceTest,DomainRuleRolloutControllerTest,DomainRuleRolloutPolicyServiceTest,DomainRuleExecutionObservationServiceTest,JdbcDomainRuleSnapshotScopeLockTest,DomainRuleSnapshotPublicationNeonIT#realLifecycleConcurrencyAndAtomicityUnderRuntimePrivileges test`.
Result: 110 tests (109 unit + one remote), no failures/errors/skips;
`target/g2-neon-gate.log`. This precedes the additional policy flush-order correction.

Final additional gate on Contracts beta.5: `mvn -B -Dtest=DomainRuleRolloutPolicyServiceTest,DomainRuleSnapshotPublicationNeonIT#initialCandidatesPolicyChangesAndPublicationVersusBootstrapRemainSerialized test`.
Result: 13 tests (12 policy unit + one remote), no failures/errors/skips;
`target/g2-neon-additional-races.log`. Includes post-flush rollback and policy/selection races.

Both remote invocations require PRAXIS_CONFIG_G2_NEON_ prefixed variables:
OWNER_URI_FILE, RUNTIME_URI_FILE, EXPECTED_HOST, EXPECTED_OWNER_ROLE,
EXPECTED_RUNTIME_ROLE, EXPECTED_DATABASE, EXPECTED_PG_MAJOR, EXPECTED_BRANCH_ID,
ATTESTATION_FILE. Values must match the exclusive assignment above. URI-file paths
are private local inputs, not credentials embedded in source.

Final `mvn -B -P ci-smoke-unit verify`: BUILD SUCCESS, 3,454 tests, zero
failures/errors/skips; `target/g2-ci-smoke-unit-final.log`. Profile unit/smoke excludes
integration/external/e2e. The explicit Neon IT was not selected, and tagged test sources
were checked for embedded/database bootstrap before running. No clean was used so raw
earlier gate logs remained intact. All test sources compiled, including repository callers.

Final `mvn -B dependency:tree -Dincludes=io.github.codexrodrigues:praxis-rules-engine,io.github.codexrodrigues:praxis-config-contracts`
confirmed Central engine beta.16 and Contracts beta.5; `target/g2-dependency-tree-final.log`.
Contracts JAR SHA-256 `b50971ae80f47fd5ddcb69ed775a422b35b58a546bffd71c5f7993ab2ff44057`,
POM `e6de2dfb3a62404e0a9d0d8d9480b061bdff0258dc8e9a348c15bda590302e4f`;
engine JAR `c214084a9aeff5f8af1346eb67e9a303e61f3a310c7e543883afce080aaf2db7`.
No locally installed candidate dependency was used. The main remote case used the earlier
Contracts dependency; its runtime/locking path is unchanged by the immutable publication
receipt update. The additional remote case and final broad gate use beta.5.

Candidate (not a release) `target/praxis-config-starter-0.1.0-rc.163.jar` SHA-256
`17032f1f4dfd370a75a0bf0b5574ebadc450ac8c0e7c0985a4d45b1fd0b95fca`.
Java + migration SQL + pom source manifest SHA-256
`7e7f2859a600485eb3cdb9721366c968fcd11515bd71683d5166525c2069197f`
(sorted POSIX relative paths, each path + NUL + contents + NUL).
`git diff --check` passed. Raw logs remain in the exclusive checkout target, not Git;
the following hashes bind their preserved contents for coordinator archival:

| Raw evidence | SHA-256 |
| --- | --- |
| `target/g2-neon-gate.log` | `95f8e0c716e1b0a41a96c3cebe712ebdb0182ffa48a263b952058fb66437e71e` |
| `target/g2-neon-additional-races.log` | `55bf20c8fe89cc0646479555d1df6e8fb29d3d3556ae328618609a0fe1bc1340` |
| `target/g2-ci-smoke-unit-final.log` | `86c137fefb2cef6a064933aad45ad40f684461a80bfd20c79db50e6b1a9c3ea8` |
| `target/g2-dependency-tree-final.log` | `a22fe27139db1f34b0878b4a64c93e5c442ef553baee146e54794ee8f8549c25` |
| `target/g2-neon-initial-migration-fixture-failure.log` | `ac478d0312c0ebcd6af9b842271347d2439669a629f58f30350d09ea31c2d43c` |
| `target/g2-neon-jpa-holder-guard-failure.log` | `ffc06dc9b998162cdade3ac93ccf9c7a3be67276109b4812135ac9c5ee87b42b` |
| `target/g2-neon-preloaded-jpa-lock-failure.log` | `0ecf0f1ae78ec2a658ab3d69ee21ffa6b2fa0754c4a83bde2dbfe862f3854c91` |
| `target/g2-neon-policy-flush-order-failure.log` | `1fb3a2aed84510ff5fa3057a050c32a19c69918743140ab712eae7de48f95701` |

## Limits and skill assessment

These gates prove Config services, persistence, locking and transaction behavior. They
do not close G2 operational cutover: published Config adoption, authenticated generated
HTTP/OpenAPI, actual prior-runtime 1.4 history, all Quickstart families/Java catalogs, UI
journey, non-persisting consumer operations, observation after A/B/same-line rollback,
maintenance/correction-forward and production cutover remain coordinator-owned gates.
Dirty external-context rejection is unit-covered, not a separate remote dirty-context case.
No G3/prod authorization is inferred.

Skills: atualizar-existente. Reviewed canonical config-domain-decisions and
reactive-determinations drafts in payroll-g1-skills against source/D4, including historical
versus execution reads, absent-head bootstrap, immutable receipts, directed refresh and
maintenance/forward recovery. No remaining blocker reported to root. Root owns canonical
integration, publication and local synchronization; this worker did not edit/sync skills.
