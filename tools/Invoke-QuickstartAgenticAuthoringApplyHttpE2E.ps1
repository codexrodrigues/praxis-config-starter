param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [int] $StreamProcessingTimeoutSeconds = 180,
    [string] $ComponentType = "praxis-dynamic-page",
    [string] $ComponentId = "agentic-authoring:e2e:operations-incident-form",
    [string] $UserPrompt = "Crie um formulario didatico so com os campos realmente necessarios para cadastrar incidentes de missao operacionais. Use o recurso canonico confirmado POST /api/operations/incidentes como fonte e gere a pre-visualizacao aplicavel."
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
    $events = @()
    $seenEventIds = [System.Collections.Generic.HashSet[string]]::new()
    $lastEventId = $null
    $connectionAttempts = 0
    $streamDeadline = (Get-Date).AddSeconds([Math]::Max(30, $StreamProcessingTimeoutSeconds))
    do {
        $connectionAttempts++
        $remainingSeconds = [Math]::Max(1, [int][Math]::Ceiling(($streamDeadline - (Get-Date)).TotalSeconds))
        $connectionTimeoutSeconds = [Math]::Min(180, $remainingSeconds)
        $replayQuery = $streamQuery
        if (-not [string]::IsNullOrWhiteSpace($lastEventId)) {
            $joiner = if ($replayQuery.Contains("?")) { "&" } else { "?" }
            $replayQuery += "$joiner" + "lastEventId=$([System.Uri]::EscapeDataString($lastEventId))"
        }
        $streamContent = ""
        try {
            $streamResponse = Invoke-WebRequest `
                -Method Get `
                -Uri "$base/api/praxis/config/ai/authoring/turn/stream/$($start.streamId)$replayQuery" `
                -Headers $headers `
                -TimeoutSec $connectionTimeoutSeconds
            $streamContent = $streamResponse.Content
        } catch {
            $streamContent = Read-ErrorBody $_
        }
        foreach ($event in @(ConvertFrom-SseContent $streamContent)) {
            $eventId = "$($event.eventId)".Trim()
            if ([string]::IsNullOrWhiteSpace($eventId) -or $seenEventIds.Add($eventId)) {
                $events += $event
            }
            if (-not [string]::IsNullOrWhiteSpace($eventId)) {
                $lastEventId = $eventId
            }
        }
        $terminal = @($events | Where-Object {
            "$($_.type)".ToLowerInvariant() -in @("result", "error", "cancelled")
        } | Select-Object -Last 1)[0]
        if ($null -ne $terminal) {
            break
        }
    } while ((Get-Date) -lt $streamDeadline)

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
        $eventTypes = @($events | ForEach-Object { "$($_.type)" } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
        $lastEvent = @($events | Select-Object -Last 1)[0]
        throw "Authoring turn did not produce a terminal result event within $StreamProcessingTimeoutSeconds seconds. connections=$connectionAttempts events=$($events.Count) types=$($eventTypes -join ',') lastType=$($lastEvent.type) lastSeq=$($lastEvent.seq)"
    }
    $terminal |
        ConvertTo-Json -Depth 40 |
        Set-Content -LiteralPath (Join-Path $artifactDir "terminal-result.json") -Encoding utf8
    if ([string]::IsNullOrWhiteSpace($terminal.streamId) -or [string]::IsNullOrWhiteSpace($terminal.eventId)) {
        throw "Authoring terminal result did not include streamId and eventId lineage."
    }
    if (-not [bool] $terminal.payload.canApply) {
        $decisionDiagnostics = $terminal.payload.decisionDiagnostics
        $intentDiagnostics = $terminal.payload.intentResolution
        $previewDiagnostics = $terminal.payload.preview
        $blockReason = "$($decisionDiagnostics.terminalPreviewApplyBlockReason)"
        if ([string]::IsNullOrWhiteSpace($blockReason)) {
            $blockReason = "$($decisionDiagnostics.reviewReason)"
        }
        if ([string]::IsNullOrWhiteSpace($blockReason)) {
            $blockReason = "$($decisionDiagnostics.semanticDecisionReviewReason)"
        }
        if ([string]::IsNullOrWhiteSpace($blockReason)) {
            $blockReason = "$($decisionDiagnostics.toolLoopTerminalReason)"
        }
        if ([string]::IsNullOrWhiteSpace($blockReason)) {
            $blockReason = if ([bool] $decisionDiagnostics.requiresReview) {
                "review-required-without-reason"
            } else {
                "unspecified-terminal-apply-block"
            }
        }
        $sanitizedBlockDiagnostics = [ordered]@{
            canApply = [bool] $terminal.payload.canApply
            operationKind = "$($intentDiagnostics.operationKind)"
            artifactKind = "$($intentDiagnostics.artifactKind)"
            changeKind = "$($intentDiagnostics.changeKind)"
            intentValid = [bool] $intentDiagnostics.valid
            intentGateStatus = "$($intentDiagnostics.gate.status)"
            intentFailureCodes = @($intentDiagnostics.failureCodes)
            selectedResourcePath = "$($intentDiagnostics.selectedCandidate.resourcePath)"
            previewValid = [bool] $previewDiagnostics.valid
            previewFailureCodes = @($previewDiagnostics.failureCodes)
            previewWarnings = @($previewDiagnostics.warnings)
            terminalPreviewApplyEligible = [bool] $decisionDiagnostics.terminalPreviewApplyEligible
            terminalApplyTargetEligible = [bool] $decisionDiagnostics.terminalApplyTargetEligible
            requiresReview = [bool] $decisionDiagnostics.requiresReview
            reviewReason = "$($decisionDiagnostics.reviewReason)"
            semanticDecisionReviewRequired = [bool] $decisionDiagnostics.semanticDecisionReviewRequired
            semanticDecisionReviewReason = "$($decisionDiagnostics.semanticDecisionReviewReason)"
            semanticDecisionReviewGroundedByPreview = [bool] $decisionDiagnostics.semanticDecisionReviewGroundedByPreview
            toolLoopCompleted = [bool] $decisionDiagnostics.toolLoopCompleted
            toolLoopTerminalReason = "$($decisionDiagnostics.toolLoopTerminalReason)"
            llmResolved = [bool] $decisionDiagnostics.llmResolved
            keywordFallbackApplied = [bool] $decisionDiagnostics.keywordFallbackApplied
            previewResourceSchemaVerified = [bool] $decisionDiagnostics.previewResourceSchemaVerified
        }
        $sanitizedBlockDiagnosticsJson = $sanitizedBlockDiagnostics | ConvertTo-Json -Compress -Depth 8
        throw "Authoring turn result is not applicable: $blockReason SanitizedDiagnostics=$sanitizedBlockDiagnosticsJson"
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
