param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [string] $UserPrompt = "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras"
)

$ErrorActionPreference = "Stop"

$base = $BaseUrl.TrimEnd("/")
$headers = @{
    "Origin" = $Origin
    "Content-Type" = "application/json"
    "X-Tenant-ID" = $TenantId
    "X-User-ID" = $UserId
    "X-Env" = $Environment
}

$model = if ($Provider -eq "openai") {
    $env:PRAXIS_AI_OPENAI_MODEL
} else {
    $env:PRAXIS_AI_GEMINI_MODEL
}

$bodyObject = @{
    userPrompt = $UserPrompt
    targetApp = "praxis-ui-angular"
    targetComponentId = "praxis-dynamic-page-builder"
    currentRoute = "/page-builder-ia"
    currentPage = @{}
    # This smoke validates the LLM-first canonical route. Do not force deterministic intent fallback.
    provider = $Provider
}
if (-not [string]::IsNullOrWhiteSpace($model)) {
    $bodyObject["model"] = $model
}
$body = $bodyObject | ConvertTo-Json -Compress -Depth 8

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
$groundingResources = @(
    [pscustomobject]@{ group = "procurement"; path = "/api/procurement/suppliers" },
    [pscustomobject]@{ group = "procurement"; path = "/api/procurement/purchase-orders" },
    [pscustomobject]@{ group = "operations"; path = "/api/operations/incidentes" }
)
$httpMethods = @("get", "post", "put", "patch", "delete")
$catalogEndpoints = @()
$openApiByGroup = @{}
$catalogVersion = "quickstart-e2e"

foreach ($resource in $groundingResources) {
    $resourcePath = $resource.path
    $group = $resource.group
    if (-not $openApiByGroup.ContainsKey($group)) {
        $groupOpenApi = Invoke-RestMethod -Method Get -Uri "$base/v3/api-docs/$group" -TimeoutSec 30
        $openApiByGroup[$group] = $groupOpenApi
        if ($catalogVersion -eq "quickstart-e2e" -and -not [string]::IsNullOrWhiteSpace([string] $groupOpenApi.info.version)) {
            $catalogVersion = [string] $groupOpenApi.info.version
        }
    }
    $openApi = $openApiByGroup[$group]
    $pathProperty = $openApi.paths.PSObject.Properties[$resourcePath]
    if ($null -eq $pathProperty) {
        throw "Required Quickstart OpenAPI resource is missing from group '$group': $resourcePath"
    }
    foreach ($method in $httpMethods) {
        $operationProperty = $pathProperty.Value.PSObject.Properties[$method]
        if ($null -eq $operationProperty) {
            continue
        }
        $operation = $operationProperty.Value
        $requestSchema = $null
        $requestContent = $operation.requestBody.content.PSObject.Properties["application/json"]
        if ($null -ne $requestContent) {
            $requestSchema = $requestContent.Value.schema
        }
        $responseSchema = $null
        $successResponse = $operation.responses.PSObject.Properties |
            Where-Object { $_.Name -match '^2\d\d$' } |
            Select-Object -First 1
        if ($null -ne $successResponse) {
            $responseContent = $successResponse.Value.content.PSObject.Properties["application/json"]
            if ($null -ne $responseContent) {
                $responseSchema = $responseContent.Value.schema
            }
        }
        $catalogEndpoints += [pscustomobject]@{
            path = $resourcePath
            method = $method.ToUpperInvariant()
            tags = @($operation.tags)
            summary = [string] $operation.summary
            description = [string] $operation.description
            operationId = [string] $operation.operationId
            requestSchema = $requestSchema
            responseSchema = $responseSchema
            parameters = @($operation.parameters)
        }
    }
}

if ($catalogEndpoints.Count -lt $groundingResources.Count) {
    throw "Quickstart OpenAPI did not expose enough operations for the scoped authoring corpus."
}

$catalogReleaseId = "v1"
$catalogBody = @{
    releaseId = $catalogReleaseId
    version = $catalogVersion
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    endpoints = $catalogEndpoints
} | ConvertTo-Json -Compress -Depth 64
$catalogIngest = Invoke-WebRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/api-catalog/ingest" `
    -Headers $headers `
    -Body $catalogBody `
    -TimeoutSec 120 `
    -UseBasicParsing
if ($catalogIngest.StatusCode -ne 202) {
    throw "Scoped API catalog ingestion returned HTTP $($catalogIngest.StatusCode), expected 202."
}
$apiCatalogGrounding = [pscustomobject]@{
    tenantId = $TenantId
    environment = $Environment
    releaseId = $catalogReleaseId
    endpointCount = $catalogEndpoints.Count
    resourcePaths = @($groundingResources | ForEach-Object { $_.path })
    ingestStatus = $catalogIngest.StatusCode
}

$intent = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/intent-resolution" `
    -Headers $headers `
    -Body $body `
    -TimeoutSec 60

$previewBodyObject = @{
    userPrompt = $UserPrompt
    targetApp = "praxis-ui-angular"
    targetComponentId = "praxis-dynamic-page-builder"
    currentRoute = "/page-builder-ia"
    currentPage = @{}
    provider = $Provider
    intentResolution = $intent
}
if (-not [string]::IsNullOrWhiteSpace($model)) {
    $previewBodyObject["model"] = $model
}
$previewBody = $previewBodyObject | ConvertTo-Json -Compress -Depth 16

$preview = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/page-preview" `
    -Headers $headers `
    -Body $previewBody `
    -TimeoutSec 60

$failureCodes = @($intent.failureCodes)
$assistantMessage = [string] $intent.assistantMessage
$selectedResourcePath = [string] $intent.selectedCandidate.resourcePath
$gateStatus = [string] $intent.gate.status
$componentEditPlan = $intent.PSObject.Properties["componentEditPlan"]
$previewFailureCodes = @($preview.failureCodes)
$previewWarnings = @($preview.warnings)
$previewUiCompositionPlan = $preview.PSObject.Properties["uiCompositionPlan"]
$previewCompiledFormPatch = $preview.PSObject.Properties["compiledFormPatch"]
$previewUiCompositionPlanPresent = $null -ne $previewUiCompositionPlan -and $null -ne $previewUiCompositionPlan.Value
$previewCompiledFormPatchPresent = $false
if ($null -ne $previewCompiledFormPatch -and $null -ne $previewCompiledFormPatch.Value) {
    $compiledFormPatchJson = $previewCompiledFormPatch.Value | ConvertTo-Json -Compress -Depth 16
    $previewCompiledFormPatchPresent = (
        -not [string]::IsNullOrWhiteSpace($compiledFormPatchJson) -and
        $compiledFormPatchJson -ne "{}" -and
        $compiledFormPatchJson -ne "null"
    )
}
$pagePreviewSharedRuleRouteBlocked = (
    -not [bool] $preview.valid -and
    $previewFailureCodes -contains "intent-resolution-shared-rule-route-required" -and
    $previewWarnings -contains "preview-skipped-invalid-intent-resolution" -and
    -not $previewUiCompositionPlanPresent -and
    -not $previewCompiledFormPatchPresent
)

$result = [pscustomobject]@{
    health = $health.status
    provider = $Provider
    valid = [bool] $intent.valid
    gateStatus = $gateStatus
    failureCodes = $failureCodes
    selectedResourcePath = $selectedResourcePath
    assistantMessage = $assistantMessage
    componentEditPlanPresent = $null -ne $componentEditPlan -and $null -ne $componentEditPlan.Value
    pagePreviewValid = [bool] $preview.valid
    pagePreviewFailureCodes = $previewFailureCodes
    pagePreviewWarnings = $previewWarnings
    pagePreviewSharedRuleRouteBlocked = $pagePreviewSharedRuleRouteBlocked
    pagePreviewUiCompositionPlanPresent = $previewUiCompositionPlanPresent
    pagePreviewCompiledFormPatchPresent = $previewCompiledFormPatchPresent
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$artifactDir = Join-Path $repoRoot "target/agentic-authoring/intent-resolution-http-e2e"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
$intent | ConvertTo-Json -Depth 32 | Set-Content -Path (Join-Path $artifactDir "intent-resolution.json") -Encoding UTF8
$preview | ConvertTo-Json -Depth 32 | Set-Content -Path (Join-Path $artifactDir "page-preview.json") -Encoding UTF8
$apiCatalogGrounding | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $artifactDir "api-catalog-grounding.json") -Encoding UTF8
$result | ConvertTo-Json -Depth 16 | Set-Content -Path (Join-Path $artifactDir "result.json") -Encoding UTF8
Write-Host "Intent resolution smoke artifacts written to $artifactDir"

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if ($result.valid) {
    throw "Intent resolution should not be valid for governed shared-rule authoring route."
}
if ($result.gateStatus -ne "route_required") {
    throw "Expected intent-resolution gate.status=route_required, got '$($result.gateStatus)'."
}
if ($result.failureCodes -notcontains "shared-rule-authoring-required") {
    throw "Intent resolution did not report shared-rule-authoring-required."
}
if ($result.selectedResourcePath -ne "/api/procurement/suppliers") {
    throw "Expected procurement suppliers candidate, got '$($result.selectedResourcePath)'."
}
if ($result.assistantMessage -notlike "*/api/praxis/config/domain-rules*") {
    throw "Assistant message did not route to /api/praxis/config/domain-rules."
}
if ($result.assistantMessage -notlike "*/api/praxis/config/domain-rules/intake*") {
    throw "Assistant message did not name the canonical domain-rules intake endpoint."
}
if ($result.assistantMessage -notlike "*/api/praxis/config/domain-rules/simulations*") {
    throw "Assistant message did not name the canonical domain-rules simulations endpoint."
}
if ($result.componentEditPlanPresent) {
    throw "Intent resolution returned componentEditPlan for a governed business-rule route."
}
if ($result.pagePreviewValid) {
    throw "Page preview should not be valid for governed shared-rule authoring route."
}
if ($result.pagePreviewFailureCodes -notcontains "intent-resolution-shared-rule-route-required") {
    throw "Page preview did not report intent-resolution-shared-rule-route-required."
}
if ($result.pagePreviewWarnings -notcontains "preview-skipped-invalid-intent-resolution") {
    throw "Page preview did not report preview-skipped-invalid-intent-resolution."
}
if ($result.pagePreviewUiCompositionPlanPresent) {
    throw "Page preview returned uiCompositionPlan for a governed business-rule route."
}
if ($result.pagePreviewCompiledFormPatchPresent) {
    throw "Page preview returned compiledFormPatch for a governed business-rule route."
}
if (-not $result.pagePreviewSharedRuleRouteBlocked) {
    throw "Page preview did not preserve the canonical shared-rule routing block."
}

$result | ConvertTo-Json -Depth 6
