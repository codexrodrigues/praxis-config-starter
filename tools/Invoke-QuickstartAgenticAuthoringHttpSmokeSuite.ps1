param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [int] $Port = 8088,
    [string] $QuickstartRoot = "",
    [string] $JarPath = "",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $JavaHome = "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [int] $StartupTimeoutSec = 90,
    [switch] $UseExistingQuickstart
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string] $Path, [string] $Root) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $Root $Path
}

function Get-ListeningProcessId([int] $LocalPort) {
    $conn = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $conn) {
        return $null
    }
    return [int] $conn.OwningProcess
}

function Wait-QuickstartHealth([string] $HealthUrl, [int] $TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $health = Invoke-RestMethod -Method Get -Uri $HealthUrl -TimeoutSec 5
            if ($health.status -eq "UP") {
                return $health
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)

    throw "Quickstart did not become healthy before timeout: $HealthUrl"
}

$starterRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $starterRoot
if ([string]::IsNullOrWhiteSpace($QuickstartRoot)) {
    $QuickstartRoot = Join-Path $workspaceRoot "praxis-api-quickstart"
}

$envPath = Resolve-RepoPath $EnvFile $starterRoot
if (-not (Test-Path -LiteralPath $envPath)) {
    throw "AI env file not found: $envPath"
}

if (-not (Test-Path -LiteralPath $QuickstartRoot)) {
    throw "Quickstart root not found: $QuickstartRoot"
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = Get-ChildItem -Path (Join-Path $QuickstartRoot "target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "(sources|javadoc|tests)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Quickstart jar not found under $QuickstartRoot\target. Package praxis-api-quickstart first."
    }
    $JarPath = $jar.FullName
} else {
    $JarPath = Resolve-RepoPath $JarPath $workspaceRoot
}

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Quickstart jar not found: $JarPath"
}

$base = $BaseUrl.TrimEnd("/")
$existingPid = Get-ListeningProcessId $Port
$quickstartProcess = $null
$startedQuickstart = $false
$logDir = Join-Path $QuickstartRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

try {
    if ($null -ne $existingPid) {
        if (-not $UseExistingQuickstart.IsPresent) {
            throw "Port $Port is already in use by PID $existingPid. Re-run with -UseExistingQuickstart or free the port."
        }
    } else {
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) {
            throw "java.exe not found under JavaHome: $JavaHome"
        }

        $outLog = Join-Path $logDir ("agentic-authoring-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".out.log")
        $errLog = Join-Path $logDir ("agentic-authoring-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".err.log")
        $startScript = @"
`$ErrorActionPreference = 'Stop'
Set-Location '$QuickstartRoot'
. '$envPath'
`$env:JAVA_HOME = '$JavaHome'
`$env:Path = '$JavaHome\bin;' + `$env:Path
`$env:PORT = '$Port'
`$env:SPRING_PROFILES_ACTIVE = ''
`$env:APP_SECURITY_READ_OPEN = 'true'
`$env:APP_SECURITY_CSRF_DISABLE = 'true'
`$env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = 'http://localhost:4003,http://127.0.0.1:4003,http://localhost:4200,http://127.0.0.1:4200'
`$env:CORS_ALLOWED_ORIGINS = 'http://localhost:4003,http://127.0.0.1:4003,http://localhost:4200,http://127.0.0.1:4200'
`$env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = 'true'
`$env:PRAXIS_AI_AUTHORING_ARTIFACTS_DIR = '$workspaceRoot\docs\ai\agentic-authoring\proofs'
`$env:PRAXIS_AI_AUTHORING_CONTRACTS_DIR = '$workspaceRoot\docs\ai\agentic-authoring\contracts'
`$env:PRAXIS_AI_SECURITY_CORPORATE_MODE = 'false'
`$env:PRAXIS_AI_SECURITY_ALLOW_HEADER_IDENTITY_IN_LOCAL = 'true'
`$env:EMBEDDING_PROVIDER = 'mock'
if (`$env:PRAXIS_AI_OPENAI_MODEL) {
    `$env:SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL = `$env:PRAXIS_AI_OPENAI_MODEL
}
& '$JavaHome\bin\java.exe' -jar '$JarPath'
"@
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($startScript))
        $quickstartProcess = Start-Process `
            -FilePath "powershell.exe" `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encoded) `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -PassThru `
            -WindowStyle Hidden
        $startedQuickstart = $true
    }

    $health = Wait-QuickstartHealth "$base/actuator/health" $StartupTimeoutSec

    $commonArgs = @{
        Provider = $Provider
        BaseUrl = $base
        EnvFile = $envPath
        Origin = $Origin
        TenantId = $TenantId
        UserId = $UserId
        Environment = $Environment
    }

    $plan = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringPlanHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $compile = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringCompileHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $preview = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringPreviewHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $apply = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringApplyHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $stream = & (Join-Path $PSScriptRoot "Invoke-QuickstartAiPatchStreamHttpE2E.ps1") `
        -BaseUrl $base `
        -Origin $Origin `
        -TenantId $TenantId `
        -UserId $UserId `
        -Environment $Environment | ConvertFrom-Json

    [pscustomobject]@{
        health = $health.status
        provider = $Provider
        baseUrl = $base
        quickstartRoot = $QuickstartRoot
        jarPath = $JarPath
        startedQuickstart = $startedQuickstart
        planValid = [bool] $plan.valid
        compileValid = [bool] $compile.compileValid
        previewValid = [bool] $preview.valid
        applyPersisted = [bool] $apply.applied
        applyCleanupDeleted = [bool] $apply.cleanupDeleted
        streamTerminalSeen = [bool] $stream.terminalSeen
        streamReplayChecked = [bool] $stream.replayChecked
        streamArtifactsDir = $stream.artifactsDir
    } | ConvertTo-Json -Depth 8
} finally {
    if ($startedQuickstart -and $null -ne $quickstartProcess) {
        Stop-Process -Id $quickstartProcess.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
        $childPid = Get-ListeningProcessId $Port
        if ($null -ne $childPid) {
            Stop-Process -Id $childPid -Force -ErrorAction SilentlyContinue
        }
    }
}
