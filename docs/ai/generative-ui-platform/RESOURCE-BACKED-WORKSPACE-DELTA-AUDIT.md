# Resource-backed workspace delta audit

Status: partial implementation slice for `praxis-config-starter#356`

## Classification and canonical ownership

This change is `arquitetural` and transversal because it joins semantic grounding,
server-side composition, the Angular Dynamic Page runtime, and persisted authoring.
It does **not** introduce a new public contract.

- `praxis-metadata-starter` remains the canonical source for resources, schemas,
  surfaces, actions, and principal-scoped capabilities.
- `praxis-config-starter` remains the canonical authoring boundary. It verifies
  governed domain bindings and materializes decisions into `UiCompositionPlan`.
- `praxis-ui-angular` remains the canonical runtime and target-registry owner.
- `praxis-api-quickstart` remains the operational proof host.

The existing internal `contextHints.verifiedDomainOperations` projection is
sufficient for this cut only after its trust boundary is enforced. Every public
authoring ingress removes a client-supplied value, including envelopes that copy
the expected schema and source strings. The streaming engine may reinsert the
projection only from typed `OperationProjection` values produced by backend
schema and principal-scoped capability verification. Direct `page-preview` does
not accept that client evidence and therefore blocks command discovery unless a
future backend re-grounding path supplies it. Creating a workspace DTO or endpoint
would duplicate evidence and split canonical ownership.

## What the platform already knows

| Concern | Existing evidence | Adherence | Decision for this cut |
| --- | --- | --- | --- |
| Semantic resource selection | `AgenticAuthoringSemanticDecision` and the selected candidate | `ja-suportado-so-ux` | Reuse the resolved resource; never infer it from prompt words. |
| Governed executable operations | backend-owned `OperationProjection`, derived from `/schemas/filtered` plus resource capabilities | `ja-suportado-so-ux` | Project verified operations into diagnostics and enable the official Table discovery runtime; never trust the serialized client envelope. |
| Component presentation affordances | `AgenticAuthoringResourceBackedPresentationAffordanceProvider` | `ja-suportado-mal-nomeado-ou-mal-materializado` | Keep it as the component presentation catalog; it is not a business-resource workspace provider. |
| Generic master/detail page | `AgenticAuthoringGenericUiCompositionPlanProvider.pagePlan` | `suportado-parcialmente` | Add state, certified port bindings, operational action discovery, and useful responsive layout. |
| Server-side preview and compilation | `AgenticAuthoringPreviewService` and `AgenticAuthoringUiCompositionPlanCompiler` | `ja-suportado-so-ux` | Exercise the existing official preview/compile path with the richer plan. |
| Target/component/port validation | Angular Page Builder preflight and the published component registry | `suportado-parcialmente` | Do not create a Java registry replica. Cross-language attestation remains tracked by #357. |
| Transactional persistence | `AgenticAuthoringApplyService`, terminal-event ownership, issued-patch matching, `If-Match`, and `UserConfigService` | `ja-suportado-so-ux` | Prove that the rich compiled page follows the existing apply and stale-ETag protections. |
| Loading, empty, and error handling | Table and Dynamic Form resource runtimes | `ja-suportado-mal-nomeado-ou-mal-materializado` | Preserve resource-driven runtime ownership; do not encode view-local business state in Java. |
| Device variants | `canvas` and `deviceLayouts` in `UiCompositionPlan` | `suportado-parcialmente` | Emit desktop master/detail and stacked mobile/tablet variants. |

## Smallest coherent slice

For a semantically selected resource with a trusted verified-operation envelope,
the generic provider will materialize:

1. a resource-backed Table master;
2. a Dynamic Form detail;
3. canonical page state for the selected row;
4. `selectionChange -> state -> initialValue` bindings over registry-certified
   ports;
5. official Table discovery only for the command scopes proven by backend-owned
   non-read operations: `/{id}/actions/...` enables item discovery and
   `/actions/...` enables collection discovery; the runtime then resolves the
   concrete actions from the canonical action catalog and HATEOAS context;
6. diagnostics that preserve grounding source, operation identities, schema and
   capability references, and an explicit reason when operational grounding is
   absent or rejected;
7. a 7/5 desktop canvas and stacked tablet/mobile variants.

Action discovery is deliberate. The config starter does not fabricate a toolbar
button, action endpoint, `api.post`, or `api.patch` command from the operational
projection. It enables the existing Table discovery policy. The Table runtime
fetches the canonical action/capability documents, filters `ITEM` actions into row
actions and `COLLECTION` actions into toolbar/bulk actions, disables denied
operations, opens the canonical Dynamic Form command surface, and owns submit and
refresh lifecycle. Existing Angular focal specs prove this allow/deny/open/execute
chain; this slice does not claim a browser or real-HTTP end-to-end proof.

## Failure policy

- A client envelope is removed at public ingress even when its schema version,
  source, count, paths, and evidence strings appear valid.
- An internally produced envelope with an unknown schema version or source is
  ignored and diagnosed.
- Operations for another resource are ignored and diagnosed.
- No prompt keyword, regex, alias, or fuzzy match decides resource or command
  intent.
- Item and collection discovery are gated independently. A verified command in
  one scope does not enable the other; without any backend-owned verified
  non-read operation both scopes remain disabled.
- Preview/compiler/apply failures remain fail-closed before persistence.
- A stale `If-Match` must not mutate the previously stored page.

## Impact map

- Canonical subproject: `praxis-config-starter` agentic authoring provider.
- Direct consumers: Page Builder and Dynamic Page preview/apply flows in
  `praxis-ui-angular`.
- Operational proof: existing Quickstart workflow resources and capability tests.
- Public docs/examples: this audit and the generative UI readiness document.
- Derived corpus: no new HTTP endpoint, public DTO, manifest, or OpenAPI path is
  introduced; `praxisui-http-examples` does not need regeneration in this cut.
- Minimum validation: generic provider tests, compiler-backed preview tests,
  apply/persistence concurrency tests, package build, and focused Quickstart
  workflow proof against the locally built starter where feasible.
- Breaking-change risk: low for contracts, moderate for generated page shape. The
  platform is beta, so the canonical richer shape replaces the incomplete stacked
  skeleton instead of adding a parallel compatibility mode.

## Remaining real gap

The interoperable target-attestation artifact shared by Java and Angular is still
a real contract gap and remains owned by `praxis-config-starter#357`. A real HTTP
E2E that traverses metadata/action discovery, click/open, Dynamic Form submit,
command execution, refresh and persisted page reload is also still outstanding.
Consequently this slice does not close #356 and must not simulate either gap with
a local component/port allowlist or an authoring-owned command endpoint.
