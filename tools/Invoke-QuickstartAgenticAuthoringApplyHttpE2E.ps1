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
    [string] $ComponentId = "agentic-authoring:e2e:operations-incident-form",
    [string] $UserPrompt = "Crie um formulario didatico so com os campos realmente necessarios para cadastrar incidentes de missao operacionais. Use a fonte Incidentes de Missao."
)

$ErrorActionPreference = "Stop"

function Get-HeaderValue($Headers, [string] $Name) {
    $value = $Headers[$Name]
    if ($value -is [array]) {
        return $value[0]
    }
    return $value
}

function Read-ErrorBody([System.Management.Automation.ErrorRecord] $ErrorRecord) {
    $response = $ErrorRecord.Exception.Response
    if ($null -eq $response) {
        return $ErrorRecord.Exception.Message
    }
    $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
    try {
        return $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
}

function ConvertFrom-SseContent([string] $Content) {
    $events = @()
    if ([string]::IsNullOrWhiteSpace($Content)) {
        return $events
    }
    foreach ($line in ($Content -split "`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed.StartsWith("data:")) {
            continue
        }
        $payload = $trimmed.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($payload)) {
            continue
        }
        try {
            $events += ($payload | ConvertFrom-Json)
        } catch {
            # Ignore non-JSON SSE frames. Praxis terminal turn events are JSON.
        }
    }
    return $events
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
    $applyTarget = @{
        schemaVersion = "praxis-agentic-authoring-apply-target.v1"
        componentType = $ComponentType
        componentId = $ComponentId
        scope = "user"
        mode = "create"
    }
    $turnBody = @{
        userPrompt = $UserPrompt
        provider = $Provider
        model = $model
        apiKey = $apiKey
        clientTurnId = [guid]::NewGuid().ToString()
        targetApp = "praxis-api-quickstart"
        targetComponentId = "praxis-dynamic-page-builder"
        currentPage = @{
            widgets = @()
        }
        contextHints = @{
            agenticApplyTarget = $applyTarget
        }
    } | ConvertTo-Json -Depth 10 -Compress

    $health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
    $startResponse = Invoke-WebRequest `
        -Method Post `
        -Uri "$base/api/praxis/config/ai/authoring/turn/stream/start" `
        -Headers $headers `
        -Body $turnBody `
        -TimeoutSec 120
    $start = $startResponse.Content | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($start.streamId)) {
        throw "Authoring turn start did not include streamId."
    }

    $streamQuery = ""
    if (-not [string]::IsNullOrWhiteSpace($start.streamAccessToken)) {
        $streamQuery = "?accessToken=$([System.Uri]::EscapeDataString($start.streamAccessToken))"
    }
    $streamContent = ""
    try {
        $streamResponse = Invoke-WebRequest `
            -Method Get `
            -Uri "$base/api/praxis/config/ai/authoring/turn/stream/$($start.streamId)$streamQuery" `
            -Headers $headers `
            -TimeoutSec 180
        $streamContent = $streamResponse.Content
    } catch {
        $streamContent = Read-ErrorBody $_
    }
    $events = @(ConvertFrom-SseContent $streamContent)
    $artifactDir = Join-Path $root "target\agentic-authoring\apply-http-e2e"
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
    $events |
        ConvertTo-Json -Depth 40 |
        Set-Content -LiteralPath (Join-Path $artifactDir "stream-events.json") -Encoding utf8
    $errorEvent = @($events | Where-Object { "$($_.type)".ToLowerInvariant() -eq "error" } | Select-Object -First 1)[0]
    if ($null -ne $errorEvent) {
        throw "Authoring turn ended with error: $($errorEvent.payload.message)"
    }
    $terminal = @($events | Where-Object { "$($_.type)".ToLowerInvariant() -eq "result" } | Select-Object -Last 1)[0]
    if ($null -eq $terminal) {
        throw "Authoring turn did not produce a terminal result event."
    }
    $terminal |
        ConvertTo-Json -Depth 40 |
        Set-Content -LiteralPath (Join-Path $artifactDir "terminal-result.json") -Encoding utf8
    if ([string]::IsNullOrWhiteSpace($terminal.streamId) -or [string]::IsNullOrWhiteSpace($terminal.eventId)) {
        throw "Authoring terminal result did not include streamId and eventId lineage."
    }
    if (-not [bool] $terminal.payload.canApply) {
        $blockReason = "$($terminal.payload.decisionDiagnostics.terminalPreviewApplyBlockReason)"
        if ([string]::IsNullOrWhiteSpace($blockReason)) {
            $blockReason = "$($terminal.payload.decisionDiagnostics.reviewReason)"
        }
        if ([string]::IsNullOrWhiteSpace($blockReason)) {
            $blockReason = "unspecified-terminal-apply-block"
        }
        throw "Authoring turn result is not applicable: $blockReason"
    }
    $preview = $terminal.payload.preview
    $intent = $terminal.payload.intentResolution

    $applyBody = @{
        compiledFormPatch = $preview.compiledFormPatch
        semanticDecision = $intent.semanticDecision
        streamId = $terminal.streamId
        resultEventId = $terminal.eventId
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
        authoringStreamId = $terminal.streamId
        authoringResultEventId = $terminal.eventId
        componentType = $apply.componentType
        componentId = $apply.componentId
        persistedScope = $saved.scope
        persistedVersion = $saved.version
        applyEtag = $persistedEtag
        getEtag = $savedEtag
        widgetCount = $widgets.Count
        widgetId = if ($null -ne $firstWidget) { $firstWidget.definition.id } else { $null }
        submitUrl = if ($null -ne $firstWidget) { $firstWidget.definition.inputs.submitUrl } else { $null }
        persistedAuthoringStreamId = $saved.tags.authoringStreamId
        persistedAuthoringResultEventId = $saved.tags.authoringResultEventId
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
    if ($result.submitUrl -ne "/api/operations/incidentes") {
        throw "Persisted submitUrl is not the canonical operations incident create endpoint."
    }
    if ($result.persistedAuthoringStreamId -ne $result.authoringStreamId -or
        $result.persistedAuthoringResultEventId -ne $result.authoringResultEventId) {
        throw "Persisted UI config did not preserve the terminal authoring lineage."
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
