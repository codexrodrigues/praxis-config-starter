param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $QuickstartRoot = "",
    [string] $UiRoot = "",
    [string] $JarPath = "",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $JavaHome = "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21",
    [string] $EmbeddingProvider = "",
    [int] $BackendPort = 8088,
    [int] $UiPort = 4003,
    [int] $StartupTimeoutSec = 180,
    [int] $StreamProcessingTimeoutSeconds = 180,
    [int] $Retries = 1
)

$ErrorActionPreference = "Stop"

function Get-ListenPid([int] $Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $conn) { return $null }
    return [int] $conn.OwningProcess
}

function Wait-Url([string] $Url, [int] $TimeoutSec, [string] $Name) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become reachable before timeout: $Url"
}

function Stop-ProcAndPort($Process, [int] $Port) {
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
    $listenPid = Get-ListenPid $Port
    if ($null -ne $listenPid) {
        Stop-Process -Id $listenPid -Force -ErrorAction SilentlyContinue
    }
}

$starterRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $starterRoot
if ([string]::IsNullOrWhiteSpace($QuickstartRoot)) { $QuickstartRoot = Join-Path $workspaceRoot "praxis-api-quickstart" }
if ([string]::IsNullOrWhiteSpace($UiRoot)) { $UiRoot = Join-Path $workspaceRoot "praxis-ui-angular" }
if (-not [System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile = Join-Path $starterRoot $EnvFile }

foreach ($requiredPath in @($QuickstartRoot, $UiRoot, $EnvFile, (Join-Path $JavaHome "bin\java.exe"))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Required path not found: $requiredPath" }
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = Get-ChildItem -Path (Join-Path $QuickstartRoot "target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notmatch "(sources|javadoc|tests)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) { throw "Quickstart jar not found. Package praxis-api-quickstart first." }
    $JarPath = $jar.FullName
}

$backendUrl = "http://localhost:$BackendPort"
$uiUrl = "http://localhost:$UiPort"
$artifactRoot = Join-Path $starterRoot ("artifacts\page-builder-agentic-full-e2e\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$quickstartLogs = Join-Path $QuickstartRoot "logs"
New-Item -ItemType Directory -Force -Path $artifactRoot, $quickstartLogs | Out-Null
$backendProcess = $null
$uiProcess = $null

try {
    if ($null -ne (Get-ListenPid $BackendPort)) { throw "Port $BackendPort is already in use." }
    if ($null -ne (Get-ListenPid $UiPort)) { throw "Port $UiPort is already in use." }

    $resolvedEmbeddingProvider = if ([string]::IsNullOrWhiteSpace($EmbeddingProvider)) { $Provider } else { $EmbeddingProvider }
    if ($resolvedEmbeddingProvider -ieq "mock") {
        throw "EMBEDDING_PROVIDER=mock is not valid for the Page Builder live LLM/browser E2E. Use -EmbeddingProvider $Provider, or a documented deterministic non-LLM runner."
    }

    $starterAuthoringRoot = Join-Path $starterRoot "docs\ai\agentic-authoring"
    $workspaceAuthoringRoot = Join-Path $workspaceRoot "docs\ai\agentic-authoring"
    $authoringRoot = if (Test-Path -LiteralPath (Join-Path $starterAuthoringRoot "contracts")) {
        $starterAuthoringRoot
    } elseif (Test-Path -LiteralPath (Join-Path $workspaceAuthoringRoot "contracts")) {
        $workspaceAuthoringRoot
    } else {
        throw "Authoring contracts directory not found under starter or workspace roots."
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $authoringRoot "proofs") | Out-Null
    $backendScript = @"
Set-Location '$QuickstartRoot'
. '$EnvFile'
`$env:JAVA_HOME = '$JavaHome'
`$env:Path = '$JavaHome\bin;' + `$env:Path
`$env:PORT = '$BackendPort'
`$env:SERVER_PORT = '$BackendPort'
`$env:SPRING_PROFILES_ACTIVE = 'local'
`$env:PRAXIS_AI_PROVIDER = '$Provider'
`$env:PRAXIS_AI_GEMINI_PREFER_GENAI_API = 'false'
`$env:APP_SECURITY_READ_OPEN = 'true'
`$env:APP_SECURITY_CSRF_DISABLE = 'true'
`$env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = '$uiUrl,http://127.0.0.1:$UiPort'
`$env:CORS_ALLOWED_ORIGINS = '$uiUrl,http://127.0.0.1:$UiPort'
`$env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = 'true'
`$env:PRAXIS_AI_AUTHORING_ARTIFACTS_DIR = '$authoringRoot\proofs'
`$env:PRAXIS_AI_AUTHORING_CONTRACTS_DIR = '$authoringRoot\contracts'
`$env:PRAXIS_AI_STREAM_PROCESSING_TIMEOUT_SECONDS = '$StreamProcessingTimeoutSeconds'
`$env:PRAXIS_AI_SECURITY_CORPORATE_MODE = 'false'
`$env:PRAXIS_AI_SECURITY_ALLOW_HEADER_IDENTITY_IN_LOCAL = 'true'
`$env:PRAXIS_AI_SECURITY_LOCAL_DEFAULT_TENANT = 'desenv'
`$env:PRAXIS_AI_SECURITY_LOCAL_DEFAULT_USER = 'codex-e2e'
`$env:PRAXIS_AI_SECURITY_LOCAL_DEFAULT_ENVIRONMENT = 'local'
`$env:PRAXIS_AI_STREAM_AUTH_MODE = 'signed-url-token'
`$env:PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET = 'codex-local-e2e-token-secret-20260421'
`$env:EMBEDDING_PROVIDER = '$resolvedEmbeddingProvider'
if (`$env:PRAXIS_AI_OPENAI_MODEL) { `$env:SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL = `$env:PRAXIS_AI_OPENAI_MODEL }
& '$JavaHome\bin\java.exe' -jar '$JarPath'
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($backendScript))
    $backendProcess = Start-Process powershell.exe -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encoded) -RedirectStandardOutput (Join-Path $quickstartLogs "page-builder-agentic-full-e2e.out.log") -RedirectStandardError (Join-Path $quickstartLogs "page-builder-agentic-full-e2e.err.log") -PassThru -WindowStyle Hidden
    Wait-Url "$backendUrl/actuator/health" $StartupTimeoutSec "Quickstart backend"

    $cmd = "set PAX_PROXY_TARGET=$backendUrl&& set PLAYWRIGHT_BASE_URL=$uiUrl&& npx.cmd ng serve praxis-ui-workspace --port $UiPort --host localhost --proxy-config proxy.conf.js"
    $uiProcess = Start-Process cmd.exe -ArgumentList @("/c", $cmd) -WorkingDirectory $UiRoot -RedirectStandardOutput (Join-Path $artifactRoot "angular.out.log") -RedirectStandardError (Join-Path $artifactRoot "angular.err.log") -PassThru -WindowStyle Hidden
    Wait-Url $uiUrl $StartupTimeoutSec "Angular dev server"

    Push-Location $UiRoot
    try {
        $env:PLAYWRIGHT_BASE_URL = $uiUrl
        $env:PRAXIS_E2E_AGENTIC_VALIDATION_MODE = "full"
        $env:PRAXIS_E2E_TEST_TIMEOUT_MS = "900000"
        & cmd.exe /c "npx.cmd playwright test --config=tools/e2e/playwright/praxis-page-builder-agentic-validation.playwright.config.ts --retries=$Retries"
        if ($LASTEXITCODE -ne 0) { throw "Page-builder agentic full E2E failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }

    [pscustomobject]@{ provider = $Provider; backendBaseUrl = $backendUrl; uiBaseUrl = $uiUrl; artifactRoot = $artifactRoot; fullE2EPassed = $true } |
        ConvertTo-Json -Depth 4 |
        Tee-Object -FilePath (Join-Path $artifactRoot "result.json")
} finally {
    Stop-ProcAndPort $uiProcess $UiPort
    Stop-ProcAndPort $backendProcess $BackendPort
}
