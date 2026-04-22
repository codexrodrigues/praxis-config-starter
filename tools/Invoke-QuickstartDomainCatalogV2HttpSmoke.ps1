param(
    [string] $BaseUrl = "http://localhost:8088",
    [int] $Port = 8088,
    [string] $QuickstartRoot = "",
    [string] $JarPath = "",
    [string] $JavaHome = "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "domain-catalog-v2-smoke",
    [string] $Environment = "local",
    [string] $ResourceKey = "human-resources.folhas-pagamento",
    [int] $StartupTimeoutSec = 180,
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

function Require-AnyItem([object[]] $Items, [string] $Description) {
    if ($Items.Count -lt 1) {
        throw "Expected at least one projected domain catalog item for $Description."
    }
}

$starterRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $starterRoot
if ([string]::IsNullOrWhiteSpace($QuickstartRoot)) {
    $QuickstartRoot = Join-Path $workspaceRoot "praxis-api-quickstart"
}

if (-not (Test-Path -LiteralPath $QuickstartRoot)) {
    throw "Quickstart root not found: $QuickstartRoot"
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = Get-ChildItem -Path (Join-Path $QuickstartRoot "target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "(sources|javadoc|tests|original)\.jar$" } |
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

        $outLog = Join-Path $logDir ("domain-catalog-v2-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".out.log")
        $errLog = Join-Path $logDir ("domain-catalog-v2-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".err.log")
        $previousJavaHome = $env:JAVA_HOME
        $previousPath = $env:Path
        $previousPort = $env:PORT
        $previousReadOpen = $env:APP_SECURITY_READ_OPEN
        $previousCsrfDisable = $env:APP_SECURITY_CSRF_DISABLE
        $previousAllowedOrigins = $env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS
        $previousCorsOrigins = $env:CORS_ALLOWED_ORIGINS
        $previousAuthoringHttp = $env:PRAXIS_AI_AUTHORING_HTTP_ENABLED
        $env:JAVA_HOME = $JavaHome
        $env:Path = "$JavaHome\bin;$env:Path"
        $env:PORT = "$Port"
        $env:APP_SECURITY_READ_OPEN = "true"
        $env:APP_SECURITY_CSRF_DISABLE = "true"
        $env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = "$Origin,https://praxisui-dev.web.app"
        $env:CORS_ALLOWED_ORIGINS = "$Origin,https://praxisui-dev.web.app"
        $env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = "true"
        $quickstartProcess = Start-Process `
            -FilePath (Join-Path $JavaHome "bin\java.exe") `
            -ArgumentList @("-jar", $JarPath) `
            -WorkingDirectory $QuickstartRoot `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -PassThru
        $env:JAVA_HOME = $previousJavaHome
        $env:Path = $previousPath
        $env:PORT = $previousPort
        $env:APP_SECURITY_READ_OPEN = $previousReadOpen
        $env:APP_SECURITY_CSRF_DISABLE = $previousCsrfDisable
        $env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = $previousAllowedOrigins
        $env:CORS_ALLOWED_ORIGINS = $previousCorsOrigins
        $env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = $previousAuthoringHttp
        $startedQuickstart = $true
    }

    $health = Wait-QuickstartHealth "$base/actuator/health" $StartupTimeoutSec
    $headers = @{
        "Origin" = $Origin
        "X-Tenant-ID" = $TenantId
        "X-Env" = $Environment
    }
    $jsonHeaders = $headers.Clone()
    $jsonHeaders["Content-Type"] = "application/json"

    $catalog = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/schemas/domain?resourceKey=$([uri]::EscapeDataString($ResourceKey))" `
        -Headers @{ "Origin" = $Origin } `
        -TimeoutSec 60

    if ($catalog.schemaVersion -ne "praxis.domain-catalog/v0.2") {
        throw "Expected praxis.domain-catalog/v0.2, got $($catalog.schemaVersion)."
    }
    if (@($catalog.aliases).Count -lt 1) {
        throw "Expected governed v0.2 catalog aliases."
    }

    $body = $catalog | ConvertTo-Json -Depth 100
    $ingest = Invoke-RestMethod `
        -Method Post `
        -Uri "$base/api/praxis/config/domain-catalog/ingest" `
        -Headers $jsonHeaders `
        -Body $body `
        -TimeoutSec 90

    $serviceKey = $catalog.service.serviceKey
    $nodeContext = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/api/praxis/config/domain-catalog/context?serviceKey=$([uri]::EscapeDataString($serviceKey))&type=node&nodeType=field&q=salario&limit=10" `
        -Headers $headers `
        -TimeoutSec 60
    $aliasContext = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/api/praxis/config/domain-catalog/context?serviceKey=$([uri]::EscapeDataString($serviceKey))&type=alias&q=salario&limit=10" `
        -Headers $headers `
        -TimeoutSec 60
    $governanceContext = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/api/praxis/config/domain-catalog/context?serviceKey=$([uri]::EscapeDataString($serviceKey))&type=governance&limit=5" `
        -Headers $headers `
        -TimeoutSec 60

    $nodeItems = @($nodeContext.items)
    $aliasItems = @($aliasContext.items)
    $governanceItems = @($governanceContext.items)
    Require-AnyItem $nodeItems "node fields"
    Require-AnyItem $aliasItems "aliases"
    Require-AnyItem $governanceItems "governance"

    $semanticItems = @($nodeItems | Where-Object {
        $_.payload.semanticOwner -or $_.payload.lifecycle -or $_.payload.businessGlossary -or
            $_.payload.resolution -or $_.payload.sourceEvidenceKeys
    })
    if ($semanticItems.Count -lt 1) {
        throw "Expected at least one node item with governed v0.2 semantic payload."
    }
    $namedAliases = @($aliasItems | Where-Object { $_.payload.alias })
    if ($namedAliases.Count -lt 1) {
        throw "Expected at least one alias item with alias."
    }

    [pscustomobject]@{
        health = $health.status
        baseUrl = $base
        quickstartRoot = $QuickstartRoot
        jarPath = $JarPath
        startedQuickstart = $startedQuickstart
        resourceKey = $ResourceKey
        catalogSchemaVersion = $catalog.schemaVersion
        serviceKey = $serviceKey
        aliasCount = @($catalog.aliases).Count
        contextCount = @($catalog.contexts).Count
        nodeCount = @($catalog.nodes).Count
        ingestItemCount = $ingest.itemCount
        projectedNodeCount = $nodeItems.Count
        projectedAliasCount = $aliasItems.Count
        projectedGovernanceCount = $governanceItems.Count
        semanticPayloadSeen = $semanticItems.Count -gt 0
        aliasPayloadSeen = $namedAliases.Count -gt 0
        typedAliasSeen = @($namedAliases | Where-Object { $_.payload.aliasType }).Count -gt 0
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
