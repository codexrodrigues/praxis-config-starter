param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [string] $ComponentType = "praxis-dynamic-page",
    [string] $ComponentId = "agentic-authoring:e2e:helpdesk-ticket-form",
    [string] $UserPrompt = "Crie um formulario didatico so com os campos realmente necessarios para abrir chamados para notebooks com a tela quebrada."
)

$ErrorActionPreference = "Stop"

function Get-HeaderValue($Headers, [string] $Name) {
    $value = $Headers[$Name]
    if ($value -is [array]) {
        return $value[0]
    }
    return $value
}

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
$applyHeaders = $headers.Clone()
$applyHeaders["X-Updated-By"] = "agentic-authoring-e2e"

$encodedType = [System.Uri]::EscapeDataString($ComponentType)
$encodedId = [System.Uri]::EscapeDataString($ComponentId)
$uiConfigUri = "$base/api/praxis/config/ui?componentType=$encodedType&componentId=$encodedId&scope=user"
$deleted = $false
$persistedEtag = $null

try {
    $previewBody = @{
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
        -Body $previewBody `
        -TimeoutSec 90
    $previewRequest = $previewBody | ConvertFrom-Json
    $previewRequest | Add-Member -NotePropertyName intentResolution -NotePropertyValue $intent
    $previewBodyWithIntent = $previewRequest | ConvertTo-Json -Depth 40 -Compress
    $preview = Invoke-RestMethod `
        -Method Post `
        -Uri "$base/api/praxis/config/ai/authoring/page-preview" `
        -Headers $headers `
        -Body $previewBodyWithIntent `
        -TimeoutSec 90

    $applyBody = @{
        compiledFormPatch = $preview.compiledFormPatch
        semanticDecision = $intent.semanticDecision
        componentType = $ComponentType
        componentId = $ComponentId
        scope = "user"
        tags = @{
            purpose = "agentic-authoring-http-e2e"
        }
    } | ConvertTo-Json -Depth 40 -Compress

    $applyResponse = Invoke-WebRequest `
        -Method Post `
        -Uri "$base/api/praxis/config/ai/authoring/page-apply" `
        -Headers $applyHeaders `
        -Body $applyBody `
        -TimeoutSec 30
    $apply = $applyResponse.Content | ConvertFrom-Json
    $persistedEtag = Get-HeaderValue $applyResponse.Headers "ETag"

    $getResponse = Invoke-WebRequest `
        -Method Get `
        -Uri $uiConfigUri `
        -Headers $headers `
        -TimeoutSec 30
    $saved = $getResponse.Content | ConvertFrom-Json
    $savedEtag = Get-HeaderValue $getResponse.Headers "ETag"

    $deleteHeaders = $headers.Clone()
    $deleteHeaders["If-Match"] = $savedEtag
    Invoke-WebRequest `
        -Method Delete `
        -Uri $uiConfigUri `
        -Headers $deleteHeaders `
        -TimeoutSec 30 | Out-Null
    $deleted = $true

    $widgets = @($saved.payload.widgets)
    $firstWidget = if ($widgets.Count -gt 0) { $widgets[0] } else { $null }
    $result = [pscustomobject]@{
        health = $health.status
        provider = $Provider
        model = $model
        previewValid = [bool] $preview.valid
        applied = [bool] $apply.applied
        componentType = $apply.componentType
        componentId = $apply.componentId
        persistedScope = $saved.scope
        persistedVersion = $saved.version
        applyEtag = $persistedEtag
        getEtag = $savedEtag
        widgetCount = $widgets.Count
        widgetId = if ($null -ne $firstWidget) { $firstWidget.definition.id } else { $null }
        submitUrl = if ($null -ne $firstWidget) { $firstWidget.definition.inputs.submitUrl } else { $null }
        cleanupDeleted = $deleted
        failureCodes = @($preview.failureCodes)
    }

    if ($result.health -ne "UP") {
        throw "Quickstart health is not UP."
    }
    if (-not $result.previewValid) {
        throw "Page preview is not valid: $($result.failureCodes -join ', ')"
    }
    if (-not $result.applied) {
        throw "Page apply did not report success."
    }
    if ($result.widgetId -ne "praxis-dynamic-form") {
        throw "Persisted UI config did not contain praxis-dynamic-form."
    }
    if ($result.submitUrl -ne "/api/helpdesk/chamados") {
        throw "Persisted submitUrl is not the helpdesk create endpoint."
    }
    if (-not $result.cleanupDeleted) {
        throw "E2E record cleanup did not run."
    }

    $result | ConvertTo-Json -Depth 8
} finally {
    if (-not $deleted -and -not [string]::IsNullOrWhiteSpace($persistedEtag)) {
        try {
            $deleteHeaders = $headers.Clone()
            $deleteHeaders["If-Match"] = $persistedEtag
            Invoke-WebRequest `
                -Method Delete `
                -Uri $uiConfigUri `
                -Headers $deleteHeaders `
                -TimeoutSec 30 | Out-Null
        } catch {
            Write-Warning "Failed to cleanup $ComponentType/$ComponentId after apply E2E: $($_.Exception.Message)"
        }
    }
}
