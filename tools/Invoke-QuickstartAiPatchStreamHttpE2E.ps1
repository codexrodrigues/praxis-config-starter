param(
    [string] $BaseUrl = "http://localhost:8088",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [string] $ComponentId = "praxis-table",
    [string] $ComponentType = "praxis-table",
    [string] $UserPrompt = "Aplique um ajuste visual seguro na tabela atual.",
    [int] $StreamTimeoutSec = 45,
    [string] $ArtifactsDir = ""
)

$ErrorActionPreference = "Stop"

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
            # Ignore non-JSON SSE data frames; the Praxis AI stream contract emits JSON events.
        }
    }
    return $events
}

function Get-FirstPersistedEvent($Events) {
    return @($Events | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_.eventId) -and $null -ne $_.seq -and [int]$_.seq -ge 0
    } | Sort-Object { [int]$_.seq } | Select-Object -First 1)[0]
}

$base = $BaseUrl.TrimEnd("/")
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
if ([string]::IsNullOrWhiteSpace($ArtifactsDir)) {
    $ArtifactsDir = Join-Path $workspaceRoot ("artifacts/ai-sse-smoke/" + (Get-Date -Format "yyyyMMdd-HHmmss"))
}
New-Item -ItemType Directory -Force -Path $ArtifactsDir | Out-Null

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

$startBody = @{
    componentId = $ComponentId
    componentType = $ComponentType
    userPrompt = $UserPrompt
    clientTurnId = [guid]::NewGuid().ToString()
    currentState = @{
        columns = @()
        capabilities = @()
        runtimeState = @{}
    }
} | ConvertTo-Json -Depth 10 -Compress

$startRequestPath = Join-Path $ArtifactsDir "start.request.json"
$startResponsePath = Join-Path $ArtifactsDir "start.response.json"
$startBody | Set-Content -LiteralPath $startRequestPath -Encoding UTF8

try {
    $startResponse = Invoke-WebRequest `
        -Method Post `
        -Uri "$base/api/praxis/config/ai/patch/stream/start" `
        -Headers $headers `
        -Body $startBody `
        -TimeoutSec 60
} catch {
    $errorBody = Read-ErrorBody $_
    $errorBody | Set-Content -LiteralPath (Join-Path $ArtifactsDir "start.error.json") -Encoding UTF8
    throw "SSE start failed: $errorBody"
}

$startResponse.Content | Set-Content -LiteralPath $startResponsePath -Encoding UTF8
$start = $startResponse.Content | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($start.streamId)) {
    throw "SSE start response did not include streamId."
}
if ($start.eventSchemaVersion -ne "v1") {
    throw "Unexpected SSE eventSchemaVersion: $($start.eventSchemaVersion)"
}

$streamId = $start.streamId
$query = ""
if (-not [string]::IsNullOrWhiteSpace($start.streamAccessToken)) {
    $query = "?accessToken=$([System.Uri]::EscapeDataString($start.streamAccessToken))"
}

$probeResponse = Invoke-WebRequest `
    -Method Get `
    -Uri "$base/api/praxis/config/ai/patch/stream/$streamId/probe$query" `
    -Headers $headers `
    -TimeoutSec 30
if ([int]$probeResponse.StatusCode -ne 204) {
    throw "SSE probe expected HTTP 204, got $([int]$probeResponse.StatusCode)."
}

$streamContent = ""
try {
    $streamResponse = Invoke-WebRequest `
        -Method Get `
        -Uri "$base/api/praxis/config/ai/patch/stream/$streamId$query" `
        -Headers $headers `
        -TimeoutSec $StreamTimeoutSec
    $streamContent = $streamResponse.Content
} catch {
    $streamContent = Read-ErrorBody $_
}

$streamRawPath = Join-Path $ArtifactsDir "stream.raw.sse"
$streamEventsPath = Join-Path $ArtifactsDir "stream.events.json"
$streamContent | Set-Content -LiteralPath $streamRawPath -Encoding UTF8
$events = @(ConvertFrom-SseContent $streamContent)
($events | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $streamEventsPath -Encoding UTF8

$terminalEvents = @($events | Where-Object { "$($_.type)".ToLowerInvariant() -in @("result", "error", "cancelled") })
if ($events.Count -lt 1) {
    throw "SSE stream returned no parsable JSON events."
}
if ($terminalEvents.Count -lt 1) {
    throw "SSE stream returned no terminal event."
}

$replayChecked = $false
$anchor = Get-FirstPersistedEvent $events
if ($null -ne $anchor) {
    $joiner = if ($query.Contains("?")) { "&" } else { "?" }
    $replayUri = "$base/api/praxis/config/ai/patch/stream/$streamId$query$joiner" +
        "lastEventId=$([System.Uri]::EscapeDataString($anchor.eventId))"
    try {
        $replayResponse = Invoke-WebRequest `
            -Method Get `
            -Uri $replayUri `
            -Headers $headers `
            -TimeoutSec $StreamTimeoutSec
        $replayContent = $replayResponse.Content
    } catch {
        $replayContent = Read-ErrorBody $_
    }
    $replayContent | Set-Content -LiteralPath (Join-Path $ArtifactsDir "replay.raw.sse") -Encoding UTF8
    $replayEvents = @(ConvertFrom-SseContent $replayContent)
    ($replayEvents | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath (Join-Path $ArtifactsDir "replay.events.json") -Encoding UTF8
    $persistedReplayEvents = @($replayEvents | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_.eventId) -and $null -ne $_.seq -and [int]$_.seq -gt [int]$anchor.seq
    })
    $replayChecked = $persistedReplayEvents.Count -gt 0
}

$cancelResponse = Invoke-WebRequest `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/patch/stream/$streamId/cancel$query" `
    -Headers $headers `
    -TimeoutSec 30
$cancel = $cancelResponse.Content | ConvertFrom-Json
if ("$($cancel.terminalState)" -notin @("cancelled", "completed", "not_found")) {
    throw "Unexpected SSE cancel terminalState: $($cancel.terminalState)"
}

$result = [pscustomobject]@{
    health = $health.status
    baseUrl = $base
    tenantId = $TenantId
    userId = $UserId
    environment = $Environment
    componentId = $ComponentId
    componentType = $ComponentType
    artifactsDir = $ArtifactsDir
    startStatus = [int]$startResponse.StatusCode
    probeStatus = [int]$probeResponse.StatusCode
    streamId = $streamId
    eventSchemaVersion = $start.eventSchemaVersion
    eventCount = $events.Count
    eventTypes = @($events | ForEach-Object { $_.type })
    terminalSeen = $terminalEvents.Count -gt 0
    replayChecked = $replayChecked
    cancelStatus = [int]$cancelResponse.StatusCode
    cancelTerminalState = $cancel.terminalState
}

($result | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath (Join-Path $ArtifactsDir "summary.json") -Encoding UTF8
$result | ConvertTo-Json -Depth 8
