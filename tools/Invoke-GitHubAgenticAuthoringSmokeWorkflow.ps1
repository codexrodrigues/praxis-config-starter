param(
    [string] $Repository = "codexrodrigues/praxis-config-starter",
    [string] $WorkflowFile = "agentic-authoring-smoke.yml",
    [string] $Ref = "main",
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $QuickstartRef = "cbc5cec318a77e998e786e486861a3c92727519e",
    [string] $MetadataRef = "567b35bce2fa229bb06c5662c57fc96902e04f09",
    [string] $UiRef = "03786b9513b10b37898035dfa71f1598db3b87df",
    [int] $QuickstartStartupTimeoutSeconds = 180,
    [switch] $RunPageBuilderFullE2E,
    [ValidateSet("smoke", "full")]
    [string] $PageBuilderE2EMode = "smoke",
    [int] $PageBuilderE2ETimeoutMinutes = 30,
    [string] $Token = "",
    [int] $PollIntervalSec = 15,
    [int] $TimeoutSec = 1800,
    [switch] $NoWait
)

$ErrorActionPreference = "Stop"

foreach ($downstreamRef in @{
    QuickstartRef = $QuickstartRef
    MetadataRef = $MetadataRef
    UiRef = $UiRef
}.GetEnumerator()) {
    if ($downstreamRef.Value -notmatch '^[0-9a-fA-F]{40}$') {
        throw "$($downstreamRef.Key) must be an immutable 40-character commit SHA."
    }
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = $env:GH_TOKEN
}
if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = $env:GITHUB_TOKEN
}
if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = $env:GITHUB_PAT
}
if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "GitHub token not found. Set GH_TOKEN, GITHUB_TOKEN, GITHUB_PAT, or pass -Token."
}

$headers = @{
    "Accept" = "application/vnd.github+json"
    "Authorization" = "Bearer $Token"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent" = "praxis-agentic-authoring-smoke"
}

$base = "https://api.github.com/repos/$Repository"
$workflowId = [System.Uri]::EscapeDataString($WorkflowFile)
$startedAt = [DateTimeOffset]::UtcNow

$dispatchBody = @{
    ref = $Ref
    inputs = @{
        provider = $Provider
        quickstart_ref = $QuickstartRef
        metadata_ref = $MetadataRef
        ui_ref = $UiRef
        run_page_builder_full_e2e = [bool] $RunPageBuilderFullE2E.IsPresent
        page_builder_e2e_mode = $PageBuilderE2EMode
        page_builder_e2e_timeout_minutes = [string] $PageBuilderE2ETimeoutMinutes
        quickstart_startup_timeout_seconds = [string] $QuickstartStartupTimeoutSeconds
    }
} | ConvertTo-Json -Depth 6 -Compress

Invoke-RestMethod `
    -Method Post `
    -Uri "$base/actions/workflows/$workflowId/dispatches" `
    -Headers $headers `
    -Body $dispatchBody `
    -ContentType "application/json" | Out-Null

if ($NoWait.IsPresent) {
    [pscustomobject]@{
        repository = $Repository
        workflow = $WorkflowFile
        ref = $Ref
        provider = $Provider
        quickstartRef = $QuickstartRef
        metadataRef = $MetadataRef
        uiRef = $UiRef
        runPageBuilderFullE2E = [bool] $RunPageBuilderFullE2E.IsPresent
        pageBuilderE2EMode = $PageBuilderE2EMode
        pageBuilderE2ETimeoutMinutes = $PageBuilderE2ETimeoutMinutes
        quickstartStartupTimeoutSeconds = $QuickstartStartupTimeoutSeconds
        dispatched = $true
        waiting = $false
    } | ConvertTo-Json -Depth 4
    exit 0
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$run = $null
do {
    Start-Sleep -Seconds $PollIntervalSec
    $runs = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/actions/workflows/$workflowId/runs?branch=$([System.Uri]::EscapeDataString($Ref))&event=workflow_dispatch&per_page=10" `
        -Headers $headers

    $run = @($runs.workflow_runs | Where-Object {
        [DateTimeOffset]::Parse($_.created_at) -ge $startedAt.AddMinutes(-2) -and
        $_.head_branch -eq $Ref
    } | Sort-Object { [DateTimeOffset]::Parse($_.created_at) } -Descending | Select-Object -First 1)[0]
} while ($null -eq $run -and (Get-Date) -lt $deadline)

if ($null -eq $run) {
    throw "Workflow dispatch succeeded, but no run appeared before timeout."
}

do {
    $run = Invoke-RestMethod -Method Get -Uri $run.url -Headers $headers
    if ($run.status -in @("completed", "cancelled")) {
        break
    }
    Start-Sleep -Seconds $PollIntervalSec
} while ((Get-Date) -lt $deadline)

if ($run.status -ne "completed") {
    throw "Workflow run did not complete before timeout. Run URL: $($run.html_url)"
}

$result = [pscustomobject]@{
    repository = $Repository
    workflow = $WorkflowFile
    runId = $run.id
    runNumber = $run.run_number
    ref = $Ref
    provider = $Provider
    quickstartRef = $QuickstartRef
    metadataRef = $MetadataRef
    uiRef = $UiRef
    runPageBuilderFullE2E = [bool] $RunPageBuilderFullE2E.IsPresent
    pageBuilderE2EMode = $PageBuilderE2EMode
    pageBuilderE2ETimeoutMinutes = $PageBuilderE2ETimeoutMinutes
    quickstartStartupTimeoutSeconds = $QuickstartStartupTimeoutSeconds
    status = $run.status
    conclusion = $run.conclusion
    url = $run.html_url
}

if ($run.conclusion -ne "success") {
    $result | ConvertTo-Json -Depth 5
    throw "Workflow run completed with conclusion '$($run.conclusion)'. Run URL: $($run.html_url)"
}

$result | ConvertTo-Json -Depth 5
