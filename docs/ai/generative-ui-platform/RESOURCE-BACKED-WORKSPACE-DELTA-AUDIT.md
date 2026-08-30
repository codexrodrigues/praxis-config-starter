# Resource-backed workspace delta audit

Status: implementation baseline for `praxis-config-starter#356`

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

The existing `contextHints.verifiedDomainOperations` envelope is sufficient for
this cut. Its entries are emitted only after schema and capability verification,
and already contain resource identity, API path and method, schema and capability
references, capability operation id, source release, and evidence. Creating a
workspace DTO or endpoint would duplicate this evidence and split canonical
ownership.

## What the platform already knows

| Concern | Existing evidence | Adherence | Decision for this cut |
| --- | --- | --- | --- |
| Semantic resource selection | `AgenticAuthoringSemanticDecision` and the selected candidate | `ja-suportado-so-ux` | Reuse the resolved resource; never infer it from prompt words. |
| Governed executable operations | `verifiedDomainOperations`, derived from `/schemas/filtered` plus resource capabilities | `ja-suportado-so-ux` | Project verified operations into workspace diagnostics and action discovery. |
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
5. resource action discovery only when at least one non-read operation is present
   in the verified envelope;
6. diagnostics that preserve grounding source, operation identities, schema and
   capability references, and an explicit reason when operational grounding is
   absent or rejected;
7. a 7/5 desktop canvas and stacked tablet/mobile variants.

Action discovery is deliberate. The config starter must not fabricate a direct
`api.post`/`api.patch` command from an incomplete request-body, concurrency, or
confirmation contract. The Table runtime consumes actions and capabilities from
their canonical metadata source and remains responsible for presenting only
allowed commands.

## Failure policy

- An envelope with an unknown schema version or source is ignored and diagnosed.
- Operations for another resource are ignored and diagnosed.
- No prompt keyword, regex, alias, or fuzzy match decides resource or command
  intent.
- No verified non-read operation means no command affordance is materialized.
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
a real contract gap and remains owned by `praxis-config-starter#357`. This cut
must not simulate that artifact with a local component/port allowlist.
