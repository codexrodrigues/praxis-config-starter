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

$planBody = @{
    userPrompt = $UserPrompt
    provider = $Provider
    model = $model
    apiKey = $apiKey
} | ConvertTo-Json -Compress

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
$plan = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/minimal-form-plan" `
    -Headers $headers `
    -Body $planBody `
    -TimeoutSec 90

$compileBody = @{
    minimalFormPlan = $plan.minimalFormPlan
} | ConvertTo-Json -Depth 20 -Compress

$compiled = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/compiled-form-patch" `
    -Headers $headers `
    -Body $compileBody `
    -TimeoutSec 30

$widgets = @($compiled.compiledFormPatch.patch.page.widgets)
$firstWidget = if ($widgets.Count -gt 0) { $widgets[0] } else { $null }
$result = [pscustomobject]@{
    health = $health.status
    provider = $Provider
    model = $model
    planValid = [bool] $plan.valid
    compileValid = [bool] $compiled.valid
    fields = @($plan.minimalFormPlan.fields | ForEach-Object { $_.name })
    widgetCount = $widgets.Count
    widgetId = if ($null -ne $firstWidget) { $firstWidget.definition.id } else { $null }
    submitUrl = if ($null -ne $firstWidget) { $firstWidget.definition.inputs.submitUrl } else { $null }
    failureCodes = @($plan.failureCodes + $compiled.failureCodes)
}

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if (-not $result.planValid) {
    throw "MinimalFormPlan is not valid: $($plan.failureCodes -join ', ')"
}
if (-not $result.compileValid) {
    throw "CompiledFormPatch is not valid: $($compiled.failureCodes -join ', ')"
}
if ($result.widgetId -ne "praxis-dynamic-form") {
    throw "Compiled patch did not target praxis-dynamic-form."
}
if ($result.submitUrl -ne "/api/operations/incidentes") {
    throw "Compiled patch submitUrl is not the canonical operations incident create endpoint."
}

$result | ConvertTo-Json -Depth 6
