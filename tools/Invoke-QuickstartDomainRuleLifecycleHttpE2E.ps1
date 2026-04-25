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
$definitionBody = @{
    ruleKey = $ruleKey
    ruleType = "selection_eligibility"
    status = "approved"
    contextKey = "procurement"
    resourceKey = "procurement.suppliers"
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
} | ConvertTo-Json -Depth 8
