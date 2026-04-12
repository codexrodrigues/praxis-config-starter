param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [string] $UserPrompt = "Crie um formulario didatico so com os campos realmente necessarios para abrir chamados para notebooks com a tela quebrada."
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
$preview = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/page-preview" `
    -Headers $headers `
    -Body $body `
    -TimeoutSec 90

$widgets = @($preview.compiledFormPatch.patch.page.widgets)
$firstWidget = if ($widgets.Count -gt 0) { $widgets[0] } else { $null }
$result = [pscustomobject]@{
    health = $health.status
    provider = $Provider
    model = $model
    valid = [bool] $preview.valid
    fields = @($preview.minimalFormPlan.fields | ForEach-Object { $_.name })
    widgetCount = $widgets.Count
    widgetId = if ($null -ne $firstWidget) { $firstWidget.definition.id } else { $null }
    submitUrl = if ($null -ne $firstWidget) { $firstWidget.definition.inputs.submitUrl } else { $null }
    failureCodes = @($preview.failureCodes)
    warningCount = @($preview.warnings).Count
}

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if (-not $result.valid) {
    throw "Agentic authoring page preview is not valid: $($result.failureCodes -join ', ')"
}
if ($result.fields -notcontains "titulo") {
    throw "Page preview MinimalFormPlan did not include titulo."
}
if ($result.widgetId -ne "praxis-dynamic-form") {
    throw "Page preview did not compile a praxis-dynamic-form widget."
}
if ($result.submitUrl -ne "/api/helpdesk/chamados") {
    throw "Page preview submitUrl is not the helpdesk create endpoint."
}

$result | ConvertTo-Json -Depth 6
