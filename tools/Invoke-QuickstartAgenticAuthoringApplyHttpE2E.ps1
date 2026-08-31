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
    [string] $ExpectedResourcePath = "/api/operations/incidentes",
    [switch] $ValidateContinuationContractOnly,
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

function Get-AuthoringBlockDiagnostics($Terminal) {
    $decisionDiagnostics = $Terminal.payload.decisionDiagnostics
    $intentDiagnostics = $Terminal.payload.intentResolution
    $previewDiagnostics = $Terminal.payload.preview
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
    $diagnostics = [ordered]@{
        canApply = [bool] $Terminal.payload.canApply
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
    return [pscustomobject]@{
        Reason = $blockReason
        Json = ($diagnostics | ConvertTo-Json -Compress -Depth 8)
    }
}

function Get-GovernedReviewContinuation($Terminal, [string] $CanonicalResourcePath) {
    $matches = @($Terminal.payload.quickReplies | Where-Object {
        $_.id -eq "governed-review-revise" -and
        $_.contextHints.source -eq "governed-review-gate" -and
        $_.contextHints.kind -eq "governed-review-repair" -and
        $_.contextHints.resourcePath -eq $CanonicalResourcePath -and
        -not [string]::IsNullOrWhiteSpace("$($_.semanticDecision.decisionId)") -and
        $_.semanticDecision.constraints.source -eq "server-issued-quick-reply" -and
        $_.semanticDecision.constraints.quickReplyId -eq "governed-review-revise" -and
        $_.semanticDecision.selectedResource.resourcePath -eq $CanonicalResourcePath
    })
    if ($matches.Count -eq 1) {
        return $matches[0]
    }
    return $null
}

function Invoke-AuthoringTurn(
    [string] $Prompt,
    [string] $SessionId,
    [array] $ConversationMessages,
    $ContextHints,
    $ActiveSemanticDecision,
    [string] $ArtifactName
) {
    $turnRequest = [ordered]@{
        userPrompt = $Prompt
        provider = $Provider
        model = $model
        apiKey = $apiKey
        clientTurnId = [guid]::NewGuid().ToString()
        targetApp = "praxis-api-quickstart"
        targetComponentId = "praxis-dynamic-page-builder"
        currentPage = @{
            widgets = @()
        }
        contextHints = $ContextHints
    }
    if (-not [string]::IsNullOrWhiteSpace($SessionId)) {
        $turnRequest.sessionId = $SessionId
    }
    if ($null -ne $ConversationMessages -and $ConversationMessages.Count -gt 0) {
        $turnRequest.conversationMessages = $ConversationMessages
    }
    if ($null -ne $ActiveSemanticDecision) {
        $turnRequest.activeSemanticDecision = $ActiveSemanticDecision
    }
    $turnBody = $turnRequest | ConvertTo-Json -Depth 40 -Compress

    $startResponse = Invoke-WebRequest `
        -Method Post `
        -Uri "$base/api/praxis/config/ai/authoring/turn/stream/start" `
        -Headers $headers `
        -Body $turnBody `
        -TimeoutSec 120
    $start = $startResponse.Content | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($start.streamId) -or [string]::IsNullOrWhiteSpace($start.threadId)) {
        throw "Authoring turn start did not include streamId and threadId."
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
        $terminalEvent = @($events | Where-Object {
            "$($_.type)".ToLowerInvariant() -in @("result", "error", "cancelled")
        } | Select-Object -Last 1)[0]
        if ($null -ne $terminalEvent) {
            break
        }
    } while ((Get-Date) -lt $streamDeadline)

    $events |
        ConvertTo-Json -Depth 40 |
        Set-Content -LiteralPath (Join-Path $artifactDir "$ArtifactName-stream-events.json") -Encoding utf8
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
        Set-Content -LiteralPath (Join-Path $artifactDir "$ArtifactName-terminal-result.json") -Encoding utf8
    if ([string]::IsNullOrWhiteSpace($terminal.streamId) -or [string]::IsNullOrWhiteSpace($terminal.eventId)) {
        throw "Authoring terminal result did not include streamId and eventId lineage."
    }
    return [pscustomobject]@{
        Start = $start
        Events = $events
        Terminal = $terminal
    }
}

if ($ValidateContinuationContractOnly.IsPresent) {
    $validFixture = @'
{
  "payload": {
    "quickReplies": [
      {
        "id": "governed-review-revise",
        "label": "Display copy must not authorize this continuation",
        "contextHints": {
          "source": "governed-review-gate",
          "kind": "governed-review-repair",
          "resourcePath": "/api/operations/incidentes"
        },
        "semanticDecision": {
          "decisionId": "decision-issued-by-backend",
          "constraints": {
            "source": "server-issued-quick-reply",
            "quickReplyId": "governed-review-revise"
          },
          "selectedResource": {
            "resourcePath": "/api/operations/incidentes"
          }
        }
      }
    ]
  }
}
'@ | ConvertFrom-Json
    $selectedFixture = Get-GovernedReviewContinuation $validFixture "/api/operations/incidentes"
    if ($null -eq $selectedFixture -or $selectedFixture.semanticDecision.decisionId -ne "decision-issued-by-backend") {
        throw "Canonical governed review continuation fixture was not selected."
    }

    $displayOnlyFixture = @'
{
  "payload": {
    "quickReplies": [
      {
        "id": "governed-review-revise",
        "label": "Revisar pontos pendentes",
        "prompt": "Revise a previa bloqueada"
      }
    ]
  }
}
'@ | ConvertFrom-Json
    if ($null -ne (Get-GovernedReviewContinuation $displayOnlyFixture "/api/operations/incidentes")) {
        throw "Display copy incorrectly authorized a governed review continuation."
    }

    $duplicateFixture = [pscustomobject]@{
        payload = [pscustomobject]@{
            quickReplies = @($selectedFixture, $selectedFixture)
        }
    }
    if ($null -ne (Get-GovernedReviewContinuation $duplicateFixture "/api/operations/incidentes")) {
        throw "Ambiguous duplicate governed review continuations were not rejected."
    }

    [pscustomobject]@{
        validCanonicalContinuationSelected = $true
        displayCopyAuthorityRejected = $true
        duplicateAuthorityRejected = $true
    } | ConvertTo-Json -Depth 4
    return
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
    $health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
    $artifactDir = Join-Path $root "target\agentic-authoring\apply-http-e2e"
    New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

    $baseContextHints = @{
        agenticApplyTarget = $applyTarget
    }
    $firstTurn = Invoke-AuthoringTurn `
        -Prompt $UserPrompt `
        -SessionId "" `
        -ConversationMessages @() `
        -ContextHints $baseContextHints `
        -ActiveSemanticDecision $null `
        -ArtifactName "turn-1"
    $terminal = $firstTurn.Terminal
    $authoringTurnCount = 1
    $reviewContinuationUsed = $false
    $reviewContinuationReplyId = $null
    $reviewContinuationDecisionId = $null

    if (-not [bool] $terminal.payload.canApply) {
        $repairReply = Get-GovernedReviewContinuation $terminal $ExpectedResourcePath
        if ($null -eq $repairReply) {
            $blocked = Get-AuthoringBlockDiagnostics $terminal
            throw "Authoring turn result is not applicable and did not expose exactly one canonical governed review continuation: $($blocked.Reason) SanitizedDiagnostics=$($blocked.Json)"
        }
        $repairReply |
            ConvertTo-Json -Depth 40 |
            Set-Content -LiteralPath (Join-Path $artifactDir "turn-1-selected-review-continuation.json") -Encoding utf8

        $continuationContextHints = @{
            agenticApplyTarget = $applyTarget
        }
        foreach ($property in $repairReply.contextHints.PSObject.Properties) {
            $continuationContextHints[$property.Name] = $property.Value
        }
        $firstAssistantMessage = "$($terminal.payload.assistantMessage)"
        $priorConversation = @(
            @{
                id = "apply-http-e2e-turn-1-user"
                role = "user"
                text = $UserPrompt
                createdAt = [DateTimeOffset]::UtcNow.AddSeconds(-1).ToString("o")
            },
            @{
                id = "apply-http-e2e-turn-1-assistant"
                role = "assistant"
                text = $firstAssistantMessage
                createdAt = [DateTimeOffset]::UtcNow.ToString("o")
            }
        )
        # The reply prompt is display/conversation copy. The backend-issued semantic decision
        # below is the authoritative continuation and is validated structurally above.
        $continuationPrompt = "$($repairReply.prompt)"
        if ([string]::IsNullOrWhiteSpace($continuationPrompt)) {
            throw "Canonical governed review continuation did not include conversation copy."
        }
        $secondTurn = Invoke-AuthoringTurn `
            -Prompt $continuationPrompt `
            -SessionId "$($firstTurn.Start.threadId)" `
            -ConversationMessages $priorConversation `
            -ContextHints $continuationContextHints `
            -ActiveSemanticDecision $repairReply.semanticDecision `
            -ArtifactName "turn-2"
        $authoringTurnCount = 2
        $reviewContinuationUsed = $true
        $reviewContinuationReplyId = "$($repairReply.id)"
        $reviewContinuationDecisionId = "$($repairReply.semanticDecision.decisionId)"

        if ($secondTurn.Start.threadId -ne $firstTurn.Start.threadId) {
            throw "Governed review continuation did not preserve the original authoring thread."
        }
        $secondStartEvent = @($secondTurn.Events | Where-Object {
            "$($_.type)".ToLowerInvariant() -eq "status" -and $_.payload.state -eq "started"
        } | Select-Object -First 1)[0]
        if ($null -eq $secondStartEvent -or
            "$($secondStartEvent.payload.activeSemanticDecisionId)" -ne $reviewContinuationDecisionId) {
            throw "Governed review continuation did not acknowledge the backend-issued semantic decision."
        }
        $terminal = $secondTurn.Terminal
        if (-not [bool] $terminal.payload.canApply) {
            $blocked = Get-AuthoringBlockDiagnostics $terminal
            throw "Authoring turn result remained non-applicable after the single governed review continuation: $($blocked.Reason) SanitizedDiagnostics=$($blocked.Json)"
        }
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
        authoringTurnCount = $authoringTurnCount
        authoringThreadId = $firstTurn.Start.threadId
        reviewContinuationUsed = $reviewContinuationUsed
        reviewContinuationReplyId = $reviewContinuationReplyId
        reviewContinuationDecisionId = $reviewContinuationDecisionId
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
    if ($result.submitUrl -ne $ExpectedResourcePath) {
        throw "Persisted submitUrl is not the expected canonical create endpoint: $ExpectedResourcePath."
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
