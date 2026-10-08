# Internal authored patch compiler

UiLayoutAuthoredPatchCompiler is a package-private, side-effect-free Config
helper. It accepts admitted baseline/candidate config ObjectNodes. It does not
extract config from arbitrary documents, validate descriptors/envelopes, attest
provenance, authorize a target or execute UI libraries. The lifecycle command now
invokes it through the native projection after resolving original pinned B0.
The request supplies only raw authoringDocument; client patchDocument is rejected.
See [the current contract](ui-layout-server-compiled-revisions.md).

All candidate properties are authored pins, including values equal to baseline.
Objects compile recursively. Baseline properties missing from candidate emit
null resets. Scalars and arrays replace atomically by deep copy; null inside an
array is preserved as data. Literal null in a merged object is rejected with
VALIDATION_FAILED and a sanitized message. The stricter Table null policy stays
in its adapter; compiling a value does not imply a component accepts it.

Compilation preflights config trees at 256 KiB/depth64, calculates exact derived
patch size before allocating its tree, and checks output bounds. Reset keys and
escaping count independently of candidate size. The bounded projection can
supply invocation budget checkpoints. See
[projection primitives](ui-layout-authoring-projection-primitives.md).

Compilation checks the resulting patch against the existing shared authored
presence, known-removal and exact-reproduction relation. Inputs are unchanged,
and mutable output subtrees are independent of baseline/candidate. No defaults,
schema normalization, minimal diff or new hash semantics are introduced.

The caller must resolve the pinned B0, not previous working C0, and admit
finite JSON, document size/depth and native component policies before use.
The helper introduces no HTTP/preparse quota or standalone persistence path.
The coordinated contract migration uses this helper; older documents without
the exact native mapping are not inferred/admitted.

Focused tests cover pins/reset, type transitions, empty containers, literal
null, unicode/numbers, immutable inputs and independent copies. Existing finite
TS corpora provide four Table and six Form comparisons plus native adapter
checks. These are local fixture proofs, not live host/browser/PG acceptance.
The initial compiler-only package did not install a JAR. Frozen rc.167 remains
unchanged; the coordinated cut uses private rc.169 followed by rc.170 for the
explicit OpenAPI request schema, with each candidate frozen separately.

See [shared relation](ui-layout-native-revision-relation.md) and
[input boundaries](ui-layout-json-input-boundaries.md) for the separate guards
and independent input limits. Rebase to a new baseline remains separate.
