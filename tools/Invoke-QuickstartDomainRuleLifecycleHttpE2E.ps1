param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local"
)

$ErrorActionPreference = "Stop"

function Invoke-JsonRequest(
    [string] $Method,
    [string] $Uri,
    [hashtable] $Headers,
    [object] $Body = $null
) {
    $args = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 60
    }
    if ($null -ne $Body) {
        $args["Body"] = ($Body | ConvertTo-Json -Compress -Depth 20)
    }
    return Invoke-RestMethod @args
}

function Invoke-ExpectedFailure(
    [string] $Method,
    [string] $Uri,
    [hashtable] $Headers,
    [object] $Body,
    [string] $ExpectedMessage
) {
    try {
        Invoke-JsonRequest -Method $Method -Uri $Uri -Headers $Headers -Body $Body | Out-Null
    } catch {
        $text = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($text)) {
            $text = $_.Exception.Message
        }
        if ($text -notlike "*$ExpectedMessage*") {
            throw "Expected failure containing '$ExpectedMessage', got: $text"
        }
        return $true
    }
    throw "Expected request to fail with '$ExpectedMessage', but it succeeded: $Method $Uri"
}

function Assert-MaterializationOutcome(
    [object] $Publication,
    [string] $ExpectedResolution,
    [string] $ExpectedMaterializationKey
) {
    $outcomes = @($Publication.explainability.publicationDiagnostics.materializationOutcomes)
    if ($outcomes.Count -lt 1) {
        throw "Expected publication diagnostics materializationOutcomes to contain at least one item."
    }
    $matched = @($outcomes | Where-Object {
        $_.resolution -eq $ExpectedResolution -and $_.materializationKey -eq $ExpectedMaterializationKey
    })
    if ($matched.Count -lt 1) {
        $actual = ($outcomes | ConvertTo-Json -Compress -Depth 12)
        throw "Expected materialization outcome resolution='$ExpectedResolution' materializationKey='$ExpectedMaterializationKey', got: $actual"
    }
    $outcome = $matched[0]
    if ([string]::IsNullOrWhiteSpace([string] $outcome.targetLayer)) {
        throw "Expected materialization outcome to include targetLayer."
    }
    if ([string]::IsNullOrWhiteSpace([string] $outcome.targetArtifactType)) {
        throw "Expected materialization outcome to include targetArtifactType."
    }
    if ([string]::IsNullOrWhiteSpace([string] $outcome.targetArtifactKey)) {
        throw "Expected materialization outcome to include targetArtifactKey."
    }
    return $true
}

function Assert-DecisionDiagnostics(
    [object] $Response,
    [string] $ExpectedDecisionSource,
    [string] $ExpectedResourceKey,
    [string] $ExpectedRuleType
) {
    return Assert-DecisionDiagnosticsNode `
        -Diagnostics $Response.explainability.decisionDiagnostics `
        -ExpectedDecisionSource $ExpectedDecisionSource `
        -ExpectedResourceKey $ExpectedResourceKey `
        -ExpectedRuleType $ExpectedRuleType
}

function Assert-DecisionDiagnosticsNode(
    [object] $Diagnostics,
    [string] $ExpectedDecisionSource,
    [string] $ExpectedResourceKey,
    [string] $ExpectedRuleType,
    [string] $ExpectedDecisionStage = $null
) {
    $diagnostics = $Diagnostics
    if ($diagnostics.decisionKind -ne "semantic_domain_rule") {
        throw "Expected decisionDiagnostics.decisionKind=semantic_domain_rule, got '$($diagnostics.decisionKind)'."
    }
    if ($diagnostics.authoringMode -ne "governed") {
        throw "Expected decisionDiagnostics.authoringMode=governed, got '$($diagnostics.authoringMode)'."
    }
    if ($diagnostics.decisionSource -ne $ExpectedDecisionSource) {
        throw "Expected decisionDiagnostics.decisionSource=$ExpectedDecisionSource, got '$($diagnostics.decisionSource)'."
    }
    if ($diagnostics.resourceKey -ne $ExpectedResourceKey) {
        throw "Expected decisionDiagnostics.resourceKey=$ExpectedResourceKey, got '$($diagnostics.resourceKey)'."
    }
    if ($diagnostics.ruleType -ne $ExpectedRuleType) {
        throw "Expected decisionDiagnostics.ruleType=$ExpectedRuleType, got '$($diagnostics.ruleType)'."
    }
    if ($diagnostics.canonicalOwner -ne "praxis-config-starter") {
        throw "Expected decisionDiagnostics.canonicalOwner=praxis-config-starter, got '$($diagnostics.canonicalOwner)'."
    }
    if ($diagnostics.materializationModel -ne "derived_projection") {
        throw "Expected decisionDiagnostics.materializationModel=derived_projection, got '$($diagnostics.materializationModel)'."
    }
    if ($diagnostics.runtimeSurfacesAreDerived -ne $true) {
        throw "Expected decisionDiagnostics.runtimeSurfacesAreDerived=true, got '$($diagnostics.runtimeSurfacesAreDerived)'."
    }
    if ($null -eq $diagnostics.predictedMaterializationCount -or [int] $diagnostics.predictedMaterializationCount -lt 1) {
        throw "Expected decisionDiagnostics.predictedMaterializationCount to be at least 1."
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedDecisionStage) -and $diagnostics.decisionStage -ne $ExpectedDecisionStage) {
        throw "Expected decisionDiagnostics.decisionStage=$ExpectedDecisionStage, got '$($diagnostics.decisionStage)'."
    }
    return $true
}

function Assert-MaterializationDecisionDiagnostics(
    [object] $Materialization,
    [string] $ExpectedMaterializationKey,
    [string] $ExpectedTargetLayer
) {
    $diagnostics = $Materialization.decisionDiagnostics
    if ($diagnostics.decisionKind -ne "semantic_domain_rule") {
        throw "Expected materialization decisionDiagnostics.decisionKind=semantic_domain_rule, got '$($diagnostics.decisionKind)'."
    }
    if ($diagnostics.decisionStage -ne "materialization") {
        throw "Expected materialization decisionDiagnostics.decisionStage=materialization, got '$($diagnostics.decisionStage)'."
    }
    if ($diagnostics.decisionSource -ne "materialization_record") {
        throw "Expected materialization decisionDiagnostics.decisionSource=materialization_record, got '$($diagnostics.decisionSource)'."
    }
    if ($diagnostics.canonicalOwner -ne "praxis-config-starter") {
        throw "Expected materialization decisionDiagnostics.canonicalOwner=praxis-config-starter, got '$($diagnostics.canonicalOwner)'."
    }
    if ($diagnostics.materializationModel -ne "derived_projection") {
        throw "Expected materialization decisionDiagnostics.materializationModel=derived_projection, got '$($diagnostics.materializationModel)'."
    }
    if ($diagnostics.runtimeSurfacesAreDerived -ne $true) {
        throw "Expected materialization decisionDiagnostics.runtimeSurfacesAreDerived=true, got '$($diagnostics.runtimeSurfacesAreDerived)'."
    }
    if ($diagnostics.materializationKey -ne $ExpectedMaterializationKey) {
        throw "Expected materialization decisionDiagnostics.materializationKey=$ExpectedMaterializationKey, got '$($diagnostics.materializationKey)'."
    }
    if ($diagnostics.targetLayer -ne $ExpectedTargetLayer) {
        throw "Expected materialization decisionDiagnostics.targetLayer=$ExpectedTargetLayer, got '$($diagnostics.targetLayer)'."
    }
    return $true
}

function Assert-OptionSourceSelectionPolicy(
    [object] $Materialization,
    [string[]] $ExpectedBlockedStatuses
) {
    if ($Materialization.targetLayer -ne "option_source") {
        throw "Expected materialization targetLayer=option_source, got '$($Materialization.targetLayer)'."
    }
    if ($Materialization.targetArtifactType -ne "resource-option-source") {
        throw "Expected materialization targetArtifactType=resource-option-source, got '$($Materialization.targetArtifactType)'."
    }
    if ($Materialization.targetArtifactKey -ne "supplier") {
        throw "Expected materialization targetArtifactKey=supplier, got '$($Materialization.targetArtifactKey)'."
    }

    $payload = $Materialization.materializedPayload
    if ($payload.kind -ne "lookup_selection_policy") {
        throw "Expected materializedPayload.kind=lookup_selection_policy, got '$($payload.kind)'."
    }
    if ($payload.optionSourceKey -ne "supplier") {
        throw "Expected materializedPayload.optionSourceKey=supplier, got '$($payload.optionSourceKey)'."
    }
    if ($payload.selectionPolicy.statusPropertyPath -ne "status") {
        throw "Expected selectionPolicy.statusPropertyPath=status, got '$($payload.selectionPolicy.statusPropertyPath)'."
    }

    $actualBlockedStatuses = @($payload.selectionPolicy.blockedStatuses)
    foreach ($status in $ExpectedBlockedStatuses) {
        if ($actualBlockedStatuses -notcontains $status) {
            $actual = ($actualBlockedStatuses -join ",")
            throw "Expected selectionPolicy.blockedStatuses to contain '$status', got '$actual'."
        }
    }
    return $true
}

function Find-MaterializationByLayer(
    [object] $Publication,
    [string] $TargetLayer
) {
    $matches = @($Publication.materializations | Where-Object { $_.targetLayer -eq $TargetLayer })
    if ($matches.Count -ne 1) {
        $actual = ($Publication.materializations | ConvertTo-Json -Compress -Depth 12)
        throw "Expected exactly one materialization with targetLayer='$TargetLayer', got $($matches.Count): $actual"
    }
    return $matches[0]
}

function Assert-BackendValidationPolicy(
    [object] $Materialization,
    [string] $ExpectedResourceKey,
    [string[]] $ExpectedBlockedStatuses
) {
    if ($Materialization.targetLayer -ne "backend_validation") {
        throw "Expected materialization targetLayer=backend_validation, got '$($Materialization.targetLayer)'."
    }
    if ($Materialization.targetArtifactType -ne "resource-validation") {
        throw "Expected materialization targetArtifactType=resource-validation, got '$($Materialization.targetArtifactType)'."
    }
    if ($Materialization.targetArtifactKey -ne $ExpectedResourceKey) {
        throw "Expected materialization targetArtifactKey=$ExpectedResourceKey, got '$($Materialization.targetArtifactKey)'."
    }

    $payload = $Materialization.materializedPayload
    if ($payload.kind -ne "resource_validation_policy") {
        throw "Expected materializedPayload.kind=resource_validation_policy, got '$($payload.kind)'."
    }
    if ($payload.validationPolicy.resourceKey -ne $ExpectedResourceKey) {
        throw "Expected validationPolicy.resourceKey=$ExpectedResourceKey, got '$($payload.validationPolicy.resourceKey)'."
    }
    $conditionStatuses = @($payload.validationPolicy.condition.'in'[1])
    foreach ($status in $ExpectedBlockedStatuses) {
        if ($conditionStatuses -notcontains $status) {
            $actual = ($conditionStatuses -join ",")
            throw "Expected validationPolicy.condition.in[1] to contain '$status', got '$actual'."
        }
    }
    return $true
}

$base = $BaseUrl.TrimEnd("/")
$headers = @{
    "Origin" = $Origin
    "Content-Type" = "application/json"
    "X-Tenant-ID" = $TenantId
    "X-User-ID" = $UserId
    "X-Env" = $Environment
}

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
if ($health.status -ne "UP") {
    throw "Quickstart health is not UP."
}

$unique = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$intakeRuleKey = "procurement.suppliers.rule.intake-smoke-$unique"
$intakeResourceKey = "procurement.intake-smoke-$unique"
$intake = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/intake" `
    -Headers $headers `
    -Body @{
        prompt = "Impedir selecao de fornecedores bloqueados no intake HTTP."
        assistantMessage = "Vou abrir a trilha governada de regra compartilhada."
        ruleKey = $intakeRuleKey
        ruleType = "selection_eligibility"
        contextKey = "procurement"
        resourceKey = $intakeResourceKey
        serviceKey = "praxis-api-quickstart"
        definition = @{
            summary = "Impedir selecao de fornecedores bloqueados no intake HTTP."
        }
        parameters = @{
            optionSourceKey = "supplier"
        }
        governance = @{}
        createdByType = "llm"
        createdBy = "codex-http-smoke"
    }

if ($intake.status -ne "draft") {
    throw "Expected intake definition status=draft, got '$($intake.status)'."
}
if ($intake.grounding.nextEndpoint -ne "/api/praxis/config/domain-rules/simulations") {
    throw "Expected intake grounding nextEndpoint to point to simulations, got '$($intake.grounding.nextEndpoint)'."
}
$intakeDecisionDiagnosticsSeen = Assert-DecisionDiagnosticsNode `
    -Diagnostics $intake.grounding.decisionDiagnostics `
    -ExpectedDecisionSource "persisted_definition" `
    -ExpectedResourceKey $intakeResourceKey `
    -ExpectedRuleType "selection_eligibility" `
    -ExpectedDecisionStage "intake"

$ruleKey = "procurement.suppliers.rule.lifecycle-smoke-$unique"
$resourceKey = "procurement.lifecycle-smoke-$unique"
$definitionBody = @{
    ruleKey = $ruleKey
    ruleType = "selection_eligibility"
    status = "approved"
    contextKey = "procurement"
    resourceKey = $resourceKey
    serviceKey = "praxis-api-quickstart"
    definition = @{
        summary = "Impedir selecao de fornecedores bloqueados no smoke HTTP."
    }
    parameters = @{
        optionSourceKey = "supplier"
    }
    governance = @{}
    createdByType = "llm"
    createdBy = "codex-http-smoke"
}

$definition = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/definitions" `
    -Headers $headers `
    -Body $definitionBody

if ($definition.status -ne "approved") {
    throw "Expected created definition status=approved, got '$($definition.status)'."
}

$materializationBody = @{
    ruleDefinitionId = $definition.id
    materializationKey = "${ruleKey}:option_source:supplier"
    targetLayer = "option_source"
    targetArtifactType = "resource-option-source"
    targetArtifactKey = "supplier"
    targetPointer = "/selectionPolicy"
    materializedRuleId = "selection-policy"
    status = "applied"
    sourceHash = "http-smoke-$unique"
    appliedByType = "llm"
    appliedBy = "codex-http-smoke"
}

$appliedCreationBlocked = Invoke-ExpectedFailure `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/materializations" `
    -Headers $headers `
    -Body $materializationBody `
    -ExpectedMessage "Rule materialization can only be applied when its definition is active"

$definition = Invoke-JsonRequest `
    -Method Patch `
    -Uri "$base/api/praxis/config/domain-rules/definitions/$($definition.id)/status" `
    -Headers $headers `
    -Body @{
        status = "active"
        decidedByType = "human"
        decidedBy = "codex-http-smoke"
        validationResult = @{
            checks = @("http-lifecycle-smoke")
        }
    }

if ($definition.status -ne "active") {
    throw "Expected definition status=active, got '$($definition.status)'."
}

$appliedMaterialization = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/materializations" `
    -Headers $headers `
    -Body $materializationBody

if ($appliedMaterialization.status -ne "applied") {
    throw "Expected applied materialization, got '$($appliedMaterialization.status)'."
}
if ([string]::IsNullOrWhiteSpace([string] $appliedMaterialization.appliedAt)) {
    throw "Expected applied materialization to include appliedAt."
}
$materializationDecisionDiagnosticsSeen = Assert-MaterializationDecisionDiagnostics `
    -Materialization $appliedMaterialization `
    -ExpectedMaterializationKey $materializationBody.materializationKey `
    -ExpectedTargetLayer "option_source"

$manualSelectedExistingPublication = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/publications" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $definition.id
        applyEligibleMaterializations = $true
        publishedByType = "human"
        publishedBy = "codex-http-smoke"
        publicationNotes = @{
            smoke = "domain-rule-selected-existing-diagnostics"
        }
    }

$selectedExistingDiagnosticsSeen = Assert-MaterializationOutcome `
    -Publication $manualSelectedExistingPublication `
    -ExpectedResolution "selected_existing" `
    -ExpectedMaterializationKey $materializationBody.materializationKey

$decisionDiagnosticsSeen = Assert-DecisionDiagnostics `
    -Response $manualSelectedExistingPublication `
    -ExpectedDecisionSource "persisted_definition" `
    -ExpectedResourceKey $resourceKey `
    -ExpectedRuleType "selection_eligibility"

$failedMaterialization = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/materializations" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $definition.id
        materializationKey = "${ruleKey}:option_source:supplier-failed"
        targetLayer = "option_source"
        targetArtifactType = "resource-option-source"
        targetArtifactKey = "supplier"
        targetPointer = "/selectionPolicy"
        materializedRuleId = "selection-policy"
        status = "failed"
        sourceHash = "http-smoke-failed-$unique"
        appliedByType = "llm"
        appliedBy = "codex-http-smoke"
    }

if ($failedMaterialization.status -ne "failed") {
    throw "Expected failed materialization, got '$($failedMaterialization.status)'."
}

$terminalPublishBlocked = Invoke-ExpectedFailure `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/publications" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $definition.id
        materializationIds = @($failedMaterialization.id)
        applyEligibleMaterializations = $true
        publishedByType = "human"
        publishedBy = "codex-http-smoke"
        publicationNotes = @{
            smoke = "domain-rule-lifecycle"
        }
    } `
    -ExpectedMessage "Rule materialization status is not publishable: failed"

$reviewRequiredRuleKey = "procurement.suppliers.rule.review-required-$unique"
$reviewRequiredDefinition = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/definitions" `
    -Headers $headers `
    -Body @{
        ruleKey = $reviewRequiredRuleKey
        ruleType = "visual_guidance"
        status = "draft"
        contextKey = "procurement"
        resourceKey = "procurement.review-required-$unique"
        serviceKey = "praxis-api-quickstart"
        definition = @{
            summary = "Avisar analista sobre revisao humana obrigatoria no smoke HTTP."
        }
        parameters = @{}
        governance = @{
            ruleAuthoring = "review_required"
        }
        createdByType = "llm"
        createdBy = "codex-http-smoke"
    }

$blockedPublication = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/publications" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $reviewRequiredDefinition.id
        applyEligibleMaterializations = $true
        publishedByType = "human"
        publishedBy = "codex-http-smoke"
        publicationNotes = @{
            smoke = "domain-rule-blocked-diagnostics"
        }
    }

if ($blockedPublication.publicationStatus -ne "blocked") {
    throw "Expected blocked publication status, got '$($blockedPublication.publicationStatus)'."
}
if ($blockedPublication.publicationReadiness -ne "approval_required") {
    throw "Expected blocked publication readiness approval_required, got '$($blockedPublication.publicationReadiness)'."
}

$blockedDiagnostics = $blockedPublication.explainability.publicationDiagnostics
if ($blockedDiagnostics.publicationStatus -ne "blocked") {
    throw "Expected blocked publication diagnostics status=blocked, got '$($blockedDiagnostics.publicationStatus)'."
}
if ($blockedDiagnostics.publicationReadiness -ne "approval_required") {
    throw "Expected blocked publication diagnostics readiness approval_required, got '$($blockedDiagnostics.publicationReadiness)'."
}
if ($blockedDiagnostics.blockedReason -ne "approval_required") {
    throw "Expected blocked publication diagnostics blockedReason approval_required, got '$($blockedDiagnostics.blockedReason)'."
}
if ($blockedDiagnostics.definitionStatusAtResolution -ne "draft") {
    throw "Expected blocked publication diagnostics definitionStatusAtResolution draft, got '$($blockedDiagnostics.definitionStatusAtResolution)'."
}
if (@($blockedDiagnostics.materializationOutcomes).Count -ne 0) {
    throw "Expected blocked publication diagnostics materializationOutcomes to be empty."
}
$blockedDiagnosticsSeen = $true

$inactiveRuleKey = "procurement.suppliers.rule.semantic-hash-inactive-$unique"
$suspendedRuleKey = "procurement.suppliers.rule.semantic-hash-suspended-$unique"

$inactiveDefinition = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/definitions" `
    -Headers $headers `
    -Body @{
        ruleKey = $inactiveRuleKey
        ruleType = "selection_eligibility"
        status = "approved"
        contextKey = "procurement"
        resourceKey = "procurement.semantic-hash-inactive-$unique"
        serviceKey = "praxis-api-quickstart"
        definition = @{
            summary = "Impedir selecao de fornecedores inativos no smoke HTTP."
        }
        parameters = @{
            optionSourceKey = "supplier"
        }
        condition = @{
            "in" = @(
                @{
                    var = "status"
                },
                @("INACTIVE", "BLOCKED")
            )
        }
        governance = @{}
        createdByType = "llm"
        createdBy = "codex-http-smoke"
    }

$suspendedDefinition = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/definitions" `
    -Headers $headers `
    -Body @{
        ruleKey = $suspendedRuleKey
        ruleType = "selection_eligibility"
        status = "approved"
        contextKey = "procurement"
        resourceKey = "procurement.semantic-hash-suspended-$unique"
        serviceKey = "praxis-api-quickstart"
        definition = @{
            summary = "Impedir selecao de fornecedores suspensos no smoke HTTP."
        }
        parameters = @{
            optionSourceKey = "supplier"
        }
        condition = @{
            "in" = @(
                @{
                    var = "status"
                },
                @("SUSPENDED")
            )
        }
        governance = @{}
        createdByType = "llm"
        createdBy = "codex-http-smoke"
    }

$inactivePublication = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/publications" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $inactiveDefinition.id
        applyEligibleMaterializations = $true
        publishedByType = "human"
        publishedBy = "codex-http-smoke"
        publicationNotes = @{
            smoke = "domain-rule-semantic-source-hash"
        }
    }

$suspendedPublication = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/publications" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $suspendedDefinition.id
        applyEligibleMaterializations = $true
        publishedByType = "human"
        publishedBy = "codex-http-smoke"
        publicationNotes = @{
            smoke = "domain-rule-semantic-source-hash"
        }
    }

$inactiveOptionSourceMaterialization = Find-MaterializationByLayer `
    -Publication $inactivePublication `
    -TargetLayer "option_source"
$inactiveBackendValidationMaterialization = Find-MaterializationByLayer `
    -Publication $inactivePublication `
    -TargetLayer "backend_validation"
$suspendedOptionSourceMaterialization = Find-MaterializationByLayer `
    -Publication $suspendedPublication `
    -TargetLayer "option_source"
$suspendedBackendValidationMaterialization = Find-MaterializationByLayer `
    -Publication $suspendedPublication `
    -TargetLayer "backend_validation"
$inactiveHash = [string] $inactiveOptionSourceMaterialization.sourceHash
$suspendedHash = [string] $suspendedOptionSourceMaterialization.sourceHash
$inactiveBackendValidationHash = [string] $inactiveBackendValidationMaterialization.sourceHash
$suspendedBackendValidationHash = [string] $suspendedBackendValidationMaterialization.sourceHash
$inactiveMaterializationKey = [string] $inactiveOptionSourceMaterialization.materializationKey
if ([string]::IsNullOrWhiteSpace($inactiveHash) -or $inactiveHash -notlike "derived:sha256:*") {
    throw "Expected inactive semantic hash to use derived:sha256 prefix, got '$inactiveHash'."
}
if ([string]::IsNullOrWhiteSpace($suspendedHash) -or $suspendedHash -notlike "derived:sha256:*") {
    throw "Expected suspended semantic hash to use derived:sha256 prefix, got '$suspendedHash'."
}
if ([string]::IsNullOrWhiteSpace($inactiveBackendValidationHash) -or $inactiveBackendValidationHash -notlike "derived:sha256:*") {
    throw "Expected inactive backend validation hash to use derived:sha256 prefix, got '$inactiveBackendValidationHash'."
}
if ([string]::IsNullOrWhiteSpace($suspendedBackendValidationHash) -or $suspendedBackendValidationHash -notlike "derived:sha256:*") {
    throw "Expected suspended backend validation hash to use derived:sha256 prefix, got '$suspendedBackendValidationHash'."
}
if ($inactiveHash -eq $suspendedHash) {
    throw "Expected semantic source hashes to differ when rule semantics differ."
}
if ($inactiveBackendValidationHash -eq $suspendedBackendValidationHash) {
    throw "Expected backend validation semantic source hashes to differ when rule semantics differ."
}

$procurementOptionSourcePolicySeen = Assert-OptionSourceSelectionPolicy `
    -Materialization $inactiveOptionSourceMaterialization `
    -ExpectedBlockedStatuses @("INACTIVE", "BLOCKED")

$procurementBackendValidationPolicySeen = Assert-BackendValidationPolicy `
    -Materialization $inactiveBackendValidationMaterialization `
    -ExpectedResourceKey "procurement.semantic-hash-inactive-$unique" `
    -ExpectedBlockedStatuses @("INACTIVE", "BLOCKED")

$createdDiagnosticsSeen = Assert-MaterializationOutcome `
    -Publication $inactivePublication `
    -ExpectedResolution "created" `
    -ExpectedMaterializationKey $inactiveMaterializationKey

$inactiveRepublish = Invoke-JsonRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/domain-rules/publications" `
    -Headers $headers `
    -Body @{
        ruleDefinitionId = $inactiveDefinition.id
        applyEligibleMaterializations = $true
        publishedByType = "human"
        publishedBy = "codex-http-smoke"
        publicationNotes = @{
            smoke = "domain-rule-publication-diagnostics"
        }
    }

$reusedDiagnosticsSeen = Assert-MaterializationOutcome `
    -Publication $inactiveRepublish `
    -ExpectedResolution "reused" `
    -ExpectedMaterializationKey $inactiveMaterializationKey

$reusedHash = [string] $inactiveRepublish.materializations[0].sourceHash
if ($reusedHash -ne $inactiveHash) {
    throw "Expected republished materialization hash '$reusedHash' to match original '$inactiveHash'."
}

[pscustomobject]@{
    health = $health.status
    baseUrl = $base
    tenantId = $TenantId
    environment = $Environment
    ruleDefinitionId = $definition.id
    definitionStatus = $definition.status
    appliedCreationBlocked = [bool] $appliedCreationBlocked
    appliedMaterializationStatus = $appliedMaterialization.status
    appliedMaterializationHasAppliedAt = -not [string]::IsNullOrWhiteSpace([string] $appliedMaterialization.appliedAt)
    failedMaterializationStatus = $failedMaterialization.status
    terminalPublishBlocked = [bool] $terminalPublishBlocked
    semanticSourceHashesDiffer = $inactiveHash -ne $suspendedHash
    backendValidationSemanticSourceHashesDiffer = $inactiveBackendValidationHash -ne $suspendedBackendValidationHash
    publicationCreatedDiagnosticsSeen = [bool] $createdDiagnosticsSeen
    publicationSelectedExistingDiagnosticsSeen = [bool] $selectedExistingDiagnosticsSeen
    publicationReusedDiagnosticsSeen = [bool] $reusedDiagnosticsSeen
    publicationBlockedDiagnosticsSeen = [bool] $blockedDiagnosticsSeen
    intakeDecisionDiagnosticsSeen = [bool] $intakeDecisionDiagnosticsSeen
    decisionDiagnosticsSeen = [bool] $decisionDiagnosticsSeen
    materializationDecisionDiagnosticsSeen = [bool] $materializationDecisionDiagnosticsSeen
    procurementOptionSourcePolicySeen = [bool] $procurementOptionSourcePolicySeen
    procurementBackendValidationPolicySeen = [bool] $procurementBackendValidationPolicySeen
} | ConvertTo-Json -Depth 8
