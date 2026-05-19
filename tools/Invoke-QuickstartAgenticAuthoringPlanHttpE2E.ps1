param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [string] $UserPrompt = "Crie um formulario didatico so com os campos realmente necessarios para cadastrar incidentes de missao operacionais. Use a fonte Incidentes de Missao."
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile
} else {
    Join-Path $root $EnvFile
}

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "AI env file not found: $envPath"
}

. $envPath

if ($Provider -eq "openai") {
    if ([string]::IsNullOrWhiteSpace($env:PRAXIS_AI_OPENAI_API_KEY) -or $env:PRAXIS_AI_OPENAI_API_KEY -eq "PASTE_OPENAI_API_KEY_HERE") {
        throw "PRAXIS_AI_OPENAI_API_KEY must be configured in $envPath."
    }
    $model = $env:PRAXIS_AI_OPENAI_MODEL
    $apiKey = $env:PRAXIS_AI_OPENAI_API_KEY
} else {
    if ([string]::IsNullOrWhiteSpace($env:PRAXIS_AI_GEMINI_API_KEY) -or $env:PRAXIS_AI_GEMINI_API_KEY -eq "PASTE_GEMINI_API_KEY_HERE") {
        throw "PRAXIS_AI_GEMINI_API_KEY must be configured in $envPath."
    }
    $model = $env:PRAXIS_AI_GEMINI_MODEL
    $apiKey = $env:PRAXIS_AI_GEMINI_API_KEY
}

$base = $BaseUrl.TrimEnd("/")
$headers = @{
    "Origin" = $Origin
    "Content-Type" = "application/json"
    "X-Tenant-ID" = $TenantId
    "X-User-ID" = $UserId
    "X-Env" = $Environment
}

$body = @{
    userPrompt = $UserPrompt
    provider = $Provider
    model = $model
    apiKey = $apiKey
} | ConvertTo-Json -Compress

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
$intent = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/intent-resolution" `
    -Headers $headers `
    -Body $body `
    -TimeoutSec 90
$planRequest = $body | ConvertFrom-Json
$planRequest | Add-Member -NotePropertyName intentResolution -NotePropertyValue $intent
$bodyWithIntent = $planRequest | ConvertTo-Json -Depth 40 -Compress
$plan = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/minimal-form-plan" `
    -Headers $headers `
    -Body $bodyWithIntent `
    -TimeoutSec 90

$fields = @($plan.minimalFormPlan.fields)
$result = [pscustomobject]@{
    health = $health.status
    provider = $Provider
    model = $model
    intentValid = [bool] $intent.valid
    valid = [bool] $plan.valid
    selectedResourcePath = $intent.selectedCandidate.resourcePath
    fields = @($fields | ForEach-Object { $_.name })
    failureCodes = @($intent.failureCodes + $plan.failureCodes)
    warningCount = @($plan.warnings).Count
}

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if (-not $result.intentValid) {
    throw "Intent resolution is not valid: $($intent.failureCodes -join ', ')"
}
if (-not $result.valid) {
    throw "Agentic authoring MinimalFormPlan is not valid: $($result.failureCodes -join ', ')"
}
if ($result.fields -notcontains "titulo") {
    throw "MinimalFormPlan did not include titulo."
}
if ($result.fields -contains "prioridadeId" -or $result.fields -contains "statusAtualId") {
    throw "MinimalFormPlan included blocked fields."
}

$result | ConvertTo-Json -Depth 6
