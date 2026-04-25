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
                @("INACTIVE")
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

$inactiveHash = [string] $inactivePublication.materializations[0].sourceHash
$suspendedHash = [string] $suspendedPublication.materializations[0].sourceHash
$inactiveMaterializationKey = [string] $inactivePublication.materializations[0].materializationKey
if ([string]::IsNullOrWhiteSpace($inactiveHash) -or $inactiveHash -notlike "derived:sha256:*") {
    throw "Expected inactive semantic hash to use derived:sha256 prefix, got '$inactiveHash'."
}
if ([string]::IsNullOrWhiteSpace($suspendedHash) -or $suspendedHash -notlike "derived:sha256:*") {
    throw "Expected suspended semantic hash to use derived:sha256 prefix, got '$suspendedHash'."
}
if ($inactiveHash -eq $suspendedHash) {
    throw "Expected semantic source hashes to differ when rule semantics differ."
}

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

$selectedExistingDiagnosticsSeen = Assert-MaterializationOutcome `
    -Publication $inactiveRepublish `
    -ExpectedResolution "selected_existing" `
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
    publicationCreatedDiagnosticsSeen = [bool] $createdDiagnosticsSeen
    publicationSelectedExistingDiagnosticsSeen = [bool] $selectedExistingDiagnosticsSeen
} | ConvertTo-Json -Depth 8
