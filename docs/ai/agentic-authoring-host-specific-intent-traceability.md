# Agentic Authoring Host-Specific Intent Traceability

Issue: <https://github.com/codexrodrigues/praxis-config-starter/issues/210>

This note closes the disabled host-specific intent coverage that previously lived in
`AgenticAuthoringIntentResolverServiceTest` under
`@Disabled("Host-specific quickstart semantics moved to governed domain catalog/RAG")`.

## Decision

The config starter remains responsible for host-neutral semantic intent invariants: LLM-authored
intent, governed candidate evidence, candidate provenance, review gates, clarification, API Catalog
Q&A boundaries, and rejection of keyword fallback as primary routing.

Quickstart-owned business semantics, such as `human-resources.funcionarios`,
`human-resources.vw-analytics-folha-pagamento`, payroll dashboards, people screens, mission
relationships, lookup option sources, stats, actions, and related surfaces, must be proved in the
quickstart host through metadata/domain catalog fixtures and HTTP tests.

Lexical expectations that depended on prompt words choosing the winning resource are deleted rather
than migrated. They conflict with the platform rule that textual matching may only rank or
disambiguate candidates after semantic scope has been resolved.

## Replacement Evidence

Starter evidence retained in enabled tests:

- `genericStarterDoesNotInventQuickstartResourcesWithoutHostCatalog`
- `explicitSourceSelectionScopesInitialLlmCandidateBundle`
- `semanticDecisionCarriesHostNeutralEvidenceBundleWithRetrievalSource`
- `openOperationalPromptDoesNotPromoteLlmSingleChartDecisionWithoutExplicitAnalyticalIntent`
- `semanticDecisionRequiresReviewForWeakLexicalEvidenceWithoutKeywordFallback`
- `visualProjectionPromptWithDifferentSemanticSourceDoesNotPreserveCurrentTableResource`
- `explicitPreserveDataRefinementDoesNotLetFallbackSwapResource`
- `resolvesHumanTabbedWorkspacePromptAsTabbedMasterDetailFormCreation`
- `resolvesHumanEmployeeSearchAndOpenDetailsPromptAsMasterDetailWithoutKeywordSyntax`
- `keepsEmployeeMasterDetailCanonicalWhenLlmResolvesPageIntent`
- `usesResolvedLlmIntentForConsultativePromptInsteadOfKeywordFallback`
- `doesNotFillResolvedLlmBlankFieldsWithKeywordFallback`
- `metadataBackedApiCatalogQuestionListsEndpointsWithoutStartingPageGeneration`
- `apiCatalogResourceListQuestionUsesNaturalLanguageWithoutTechnicalLeakage`
- `llmBackedConsultativeQuestionResolvesIntentBeforeApiCatalogDiscovery`
- `resolvesHumanAnalyticalPromptToAnnotatedAnalyticsResourceInsteadOfOperationalEntity`
- `confirmedDashboardFilterControlsAreEligibleWithoutWidgetTarget`
- `broadFallbackSelectionIsOverriddenByStrongerSemanticRetrievalCandidate`

Quickstart evidence retained in the host:

- `StatsSchemaSmokeHttpTest`: external-smoke proof for filtered schemas, stats endpoints,
  chart-ready payroll analytics, request/response schema links, option sources, and catalog group
  mapping for human resources analytics. This class is gated by
  `PRAXIS_EXTERNAL_SMOKE_TESTS=true`; without the external smoke datasource environment it is
  intentionally skipped.
- `MissaoPilotIntegrationTest`: proves related surfaces, actions, capabilities, child resources,
  summaries, team/timeline/detail composition, option sources, and HTTP behavior for the mission
  domain.
- `OperationalAssetsEntityLookupPilotIntegrationTest`: proves entity lookup option sources,
  including the `employee` option source backed by `/api/human-resources/funcionarios`.
- `docs/AI-HOST-BUSINESS-GROUNDING-GUIDE.md`: documents that words such as employee/payroll/salary
  are business vocabulary emitted by the host and must not become config-starter lexical routing.
- `docs/COCKPIT-QUICKSTART-REFERENCE.md`: documents the quickstart cockpit surface map, payroll
  analytics stats, human resources composition, actions, and semantic readiness inventory.

## Disabled Scenario Map

| Removed starter method | Classification | Replacement or disposition |
| --- | --- | --- |
| `dataSourceRefinementDoesNotForcePreviousResourceWhenPromptAsksForNewSource` | `starter-generic` | Covered by enabled source-refinement and candidate-bundle tests: `explicitSourceSelectionScopesInitialLlmCandidateBundle`, `visualProjectionPromptWithDifferentSemanticSourceDoesNotPreserveCurrentTableResource`, and `explicitPreserveDataRefinementDoesNotLetFallbackSwapResource`. |
| `dataSourceRefinementRecognizesNaturalEmployeeSourceCorrection` | `quickstart/domain-specific` | Employee/payroll re-grounding belongs to quickstart domain evidence. Host proof is covered by `StatsSchemaSmokeHttpTest`, `OperationalAssetsEntityLookupPilotIntegrationTest`, and the business grounding guide. |
| `dataSourceRefinementDetachesFromSelectedChartResourceWhenUserCorrectsSource` | `quickstart/domain-specific` | Payroll chart to employee source correction depends on human resources vocabulary. The starter keeps the generic “different semantic source does not preserve current table resource” invariant. |
| `dataSourceCorrectionAgainstSelectedChartRegroundsEvenWithoutLoadedDecisionMemory` | `quickstart/domain-specific` | Requires quickstart human resources catalog semantics. Replacement evidence is the host stats/schema/lookup proof plus starter generic source-refinement tests. |
| `detachesFromCurrentFormWhenUserPivotsToEmployeeMasterDetail` | `quickstart/domain-specific` | Employee master-detail is host semantics. Starter keeps generic page/master-detail intent boundaries; quickstart proves the people resource and option source. |
| `resolvesHumanCrudCompositionPromptAsMasterDetailCreation` | `quickstart/domain-specific` | Retained as enabled host-neutral behavior only where backed by canonical resource hints; quickstart owns human resources composition proof. |
| `consultativeRelatedTableQuestionAnswersInsteadOfMaterializingPreview` | `quickstart/domain-specific` | Consultative people-table copy and related-resource recommendations belong to quickstart/domain catalog evidence, not starter hardcoded prose. |
| `consultativePeopleTableFieldQuestionExplainsColumnsInsteadOfSchemaUrl` | `quickstart/domain-specific` | Field explanation must come from host schema/domain catalog. Starter retains API Catalog Q&A boundary tests without host-specific prose. |
| `comparativePeopleTableQuestionRecommendsNextStepInsteadOfMaterializingPreview` | `quickstart/domain-specific` | Comparative people-screen recommendations belong to domain catalog/RAG answer proof, not starter fallback text. |
| `preservesCanonicalPayrollAnalyticsSourceWhenDepartmentFollowUpTriesToSwitchDashboardDataSource` | `quickstart/domain-specific` | Payroll analytics and department continuation are quickstart domain semantics. Starter retains generic candidate provenance/review behavior. |
| `keepsGenericLlmExplorationAsGovernedDashboardConfirmationForAnalyticalHumanIntent` | `starter-generic` | Covered by enabled tests for LLM consult/edit boundary, no keyword fallback, governed ranking override, and analytical resource selection. |
| `deterministicFallbackPrefersPromptAlignedCandidateOverBroadDashboardTie` | `obsolete-lexical` | Deleted. Prompt-aligned deterministic fallback is not a canonical primary decision mechanism. The replacement invariant is `broadFallbackSelectionIsOverriddenByStrongerSemanticRetrievalCandidate`. |
| `asksForConfirmationWhenUserAsksBestWayToVisualizePayrollInformation` | `quickstart/domain-specific` | Payroll visualization recommendations belong to quickstart domain catalog/RAG and cockpit proof. Starter keeps clarification/review gates without payroll vocabulary. |
| `metadataBackedApiCatalogQuestionAnswersSchemaActionsFiltersAndApiChoice` | `quickstart/domain-specific` | Schema/actions/filter answers for payroll endpoints are covered by quickstart HTTP schema/action/stats tests and documentation. |
| `metadataBackedApiCatalogQuestionAnswersRelatedApisWithCatalogEvidence` | `quickstart/domain-specific` | Related API recommendations belong to quickstart catalog/surface evidence; starter retains API Catalog answer envelope tests. |
| `dashboardFilterConnectionOffersConcreteGovernedContinuations` | `quickstart/domain-specific` | Period/area filter copy for payroll dashboards is host/domain semantics. Starter retains the confirmed filter-control eligibility invariant. |
| `preservesAnalyticalDashboardIntentWhenLlmSuggestsOperationalForm` | `starter-generic` | Covered by enabled analytical intent tests and warnings that prevent operational LLM suggestions from becoming executable without governed evidence. |
| `preservesAnalyticalDashboardIntentWhenLlmStaysInConsultativePageMode` | `starter-generic` | Covered by enabled consult/edit boundary tests and analytical resource selection tests. |
| `vagueAnalyticalPromptWithMultipleAnalyticsSourcesAsksForResourceChoice` | `starter-generic` | Covered by enabled ambiguity, review, and governed ranking tests; host-specific quick replies are not retained in starter. |
| `specificPayrollAnalyticalPromptSelectsPayrollProjectionAmongMultipleAnalyticsSources` | `quickstart/domain-specific` | Payroll projection selection belongs to quickstart domain catalog/RAG proof and payroll stats/schema smoke tests. |
| `consultativePeopleScreenQuestionSuggestsScreensWithoutPromotingPreview` | `quickstart/domain-specific` | People screen suggestions belong to host domain catalog/RAG. Starter retains the API Catalog consultative boundary without people-specific copy. |

## Minimum Local Proof

Config starter:

```bash
mvn "-Dtest=AgenticAuthoringIntentResolverServiceTest" test
```

Quickstart host/domain proof:

```bash
mvn "-Dtest=StatsSchemaSmokeHttpTest,MissaoPilotIntegrationTest,OperationalAssetsEntityLookupPilotIntegrationTest" test
```

Run the external stats smoke only when the quickstart smoke datasource environment is present:

```bash
PRAXIS_EXTERNAL_SMOKE_TESTS=true mvn "-Dtest=StatsSchemaSmokeHttpTest" test
```

The starter test class must have no remaining `@Disabled` host-specific scenarios after this cleanup.
