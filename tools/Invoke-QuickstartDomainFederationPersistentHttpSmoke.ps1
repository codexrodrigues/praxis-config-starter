param(
    [string] $BaseUrl = "http://localhost:8088",
    [int] $Port = 8088,
    [string] $QuickstartRoot = "",
    [string] $JarPath = "",
    [string] $JavaHome = "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "domain-federation-persistent-smoke",
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

function ConvertTo-DeepJson([object] $Value) {
    return $Value | ConvertTo-Json -Depth 100
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

        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $outLog = Join-Path $logDir "domain-federation-persistent-smoke-$timestamp.out.log"
        $errLog = Join-Path $logDir "domain-federation-persistent-smoke-$timestamp.err.log"

        $previous = @{
            JAVA_HOME = $env:JAVA_HOME
            Path = $env:Path
            PORT = $env:PORT
            SERVER_PORT = $env:SERVER_PORT
            APP_SECURITY_READ_OPEN = $env:APP_SECURITY_READ_OPEN
            APP_SECURITY_CSRF_DISABLE = $env:APP_SECURITY_CSRF_DISABLE
            APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = $env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS
            CORS_ALLOWED_ORIGINS = $env:CORS_ALLOWED_ORIGINS
            PRAXIS_AI_AUTHORING_HTTP_ENABLED = $env:PRAXIS_AI_AUTHORING_HTTP_ENABLED
            PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED = $env:PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED
            EMBEDDING_PROVIDER = $env:EMBEDDING_PROVIDER
        }
        $env:JAVA_HOME = $JavaHome
        $env:Path = "$JavaHome\bin;$env:Path"
        $env:PORT = "$Port"
        $env:SERVER_PORT = "$Port"
        $env:APP_SECURITY_READ_OPEN = "true"
        $env:APP_SECURITY_CSRF_DISABLE = "true"
        $env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = "$Origin,https://praxisui-dev.web.app"
        $env:CORS_ALLOWED_ORIGINS = "$Origin,https://praxisui-dev.web.app"
        $env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = "true"
        $env:PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED = "true"
        $env:EMBEDDING_PROVIDER = "mock"

        $quickstartProcess = Start-Process `
            -FilePath (Join-Path $JavaHome "bin\java.exe") `
            -ArgumentList @("-jar", $JarPath) `
            -WorkingDirectory $QuickstartRoot `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -PassThru

        foreach ($key in $previous.Keys) {
            Set-Item -Path "Env:$key" -Value $previous[$key] -ErrorAction SilentlyContinue
        }
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

    $catalogBody = ConvertTo-DeepJson $catalog
    $catalogIngest = Invoke-RestMethod `
        -Method Post `
        -Uri "$base/api/praxis/config/domain-catalog/ingest" `
        -Headers $jsonHeaders `
        -Body $catalogBody `
        -TimeoutSec 90

    $serviceKey = $catalog.service.serviceKey
    $request = [ordered]@{
        schemaVersion = "praxis.domain-federation/v0.1"
        tenantId = $TenantId
        environment = $Environment
        sources = @(
            [ordered]@{
                sourceKey = "praxis-api-quickstart"
                sourceType = "microservice"
                serviceKey = $serviceKey
                serviceName = "Praxis API Quickstart"
                tenantId = $TenantId
                environment = $Environment
                semanticOwner = "Architecture"
                technicalOwner = "Platform"
                trustLevel = "authoritative"
                status = "active"
                latestReleaseKey = "domain-catalog:$serviceKey:latest"
            }
        )
        contexts = @(
            [ordered]@{
                contextKey = "human-resources"
                sourceKey = "praxis-api-quickstart"
                contextType = "bounded_context"
                label = "Human Resources"
                description = "People, employment lifecycle and payroll concepts."
                semanticOwner = "RH"
                technicalOwner = "Platform"
                tenantId = $TenantId
                environment = $Environment
                status = "active"
                latestReleaseKey = "domain-catalog:human-resources:latest"
            },
            [ordered]@{
                contextKey = "assets"
                sourceKey = "praxis-api-quickstart"
                contextType = "bounded_context"
                label = "Assets"
                description = "Vehicles, equipment and asset allocation concepts."
                semanticOwner = "Operations"
                technicalOwner = "Platform"
                tenantId = $TenantId
                environment = $Environment
                status = "active"
                latestReleaseKey = "domain-catalog:assets:latest"
            }
        )
        contracts = @(
            [ordered]@{
                contractKey = "assets.vehicle-allocation.lookup.v1"
                contractType = "lookup_option_source"
                providerSourceKey = "praxis-api-quickstart"
                providerContextKey = "assets"
                consumerContextKey = "human-resources"
                resourceKey = "assets.veiculos"
                operationKey = "vehicleAllocationLookup"
                schemaRef = "openapi://praxis-api-quickstart/api/assets/veiculos"
                compatibility = "stable"
                visibility = "internal"
                status = "active"
            }
        )
        contextRelationships = @(
            [ordered]@{
                relationshipKey = "human-resources.funcionarios.references.assets.veiculos"
                sourceContextKey = "human-resources"
                targetContextKey = "assets"
                relationshipType = "references"
                contractKey = "assets.vehicle-allocation.lookup.v1"
                direction = "source_to_target"
                ownership = "target_owned"
                confidence = 0.91
                status = "active"
            }
        )
        resolutions = @(
            [ordered]@{
                resolutionKey = "hr.allocated_vehicle.maps_to.assets.vehicle"
                sourceConceptKey = "human-resources.funcionario.veiculo_alocado"
                targetConceptKey = "assets.veiculo"
                sourceContextKey = "human-resources"
                targetContextKey = "assets"
                resolutionType = "maps_to"
                confidence = 0.9
                status = "approved"
                reviewOwner = "Architecture"
            }
        )
    }
    $requestBody = ConvertTo-DeepJson $request

    $dryRun = Invoke-RestMethod `
        -Method Post `
        -Uri "$base/api/praxis/config/domain-federation/dry-run" `
        -Headers $jsonHeaders `
        -Body $requestBody `
        -TimeoutSec 90
    if ($dryRun.valid -ne $true -or $dryRun.errorCount -ne 0) {
        throw "Expected valid dry-run with zero errors."
    }

    $ingest = Invoke-RestMethod `
        -Method Post `
        -Uri "$base/api/praxis/config/domain-federation/ingest?dryRun=false" `
        -Headers $jsonHeaders `
        -Body $requestBody `
        -TimeoutSec 90
    if ($ingest.valid -ne $true -or [string]::IsNullOrWhiteSpace($ingest.releaseKey)) {
        throw "Expected persistent ingest to return a valid release."
    }
    if ($ingest.persistedCounts.sources -lt 1 -or
        $ingest.persistedCounts.contexts -lt 1 -or
        $ingest.persistedCounts.contextRelationships -lt 1 -or
        $ingest.persistedCounts.contracts -lt 1 -or
        $ingest.persistedCounts.resolutions -lt 1) {
        throw "Expected persistent ingest counts for all federation artifact types."
    }

    $releases = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/api/praxis/config/domain-federation/releases?status=candidate&limit=10" `
        -Headers $headers `
        -TimeoutSec 60
    $release = @($releases | Where-Object { $_.releaseKey -eq $ingest.releaseKey }) | Select-Object -First 1
    if ($null -eq $release) {
        throw "Persisted release was not returned by release list."
    }

    $encodedReleaseKey = [uri]::EscapeDataString($ingest.releaseKey)
    $validation = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/api/praxis/config/domain-federation/releases/$encodedReleaseKey/validation" `
        -Headers $headers `
        -TimeoutSec 60
    if ($validation.releaseKey -ne $ingest.releaseKey -or $validation.validationReport.valid -ne $true) {
        throw "Release validation endpoint did not return the persisted valid report."
    }

    $activated = Invoke-RestMethod `
        -Method Post `
        -Uri "$base/api/praxis/config/domain-federation/releases/$encodedReleaseKey/activate" `
        -Headers $headers `
        -TimeoutSec 60
    if ($activated.releaseKey -ne $ingest.releaseKey -or $activated.status -ne "active") {
        throw "Release activation endpoint did not activate the persisted candidate release."
    }

    $contextQuery = "serviceKey=$([uri]::EscapeDataString($serviceKey))&contextKey=human-resources&relationshipType=references&resourceKey=assets.veiculos&limit=10"
    $context = Invoke-RestMethod `
        -Method Get `
        -Uri "$base/api/praxis/config/domain-federation/context?$contextQuery" `
        -Headers $headers `
        -TimeoutSec 60
    if ($context.sourceMode -ne "persisted_federation") {
        throw "Expected /domain-federation/context to use persisted_federation source mode, got $($context.sourceMode)."
    }
    $contextItemCount = @($context.context.items).Count
    $contextRelationshipCount = @($context.relationships).Count
    if ($contextItemCount -lt 1 -or $contextRelationshipCount -lt 1) {
        throw "Expected persisted federation context to return context items and relationships."
    }

    [pscustomobject]@{
        health = $health.status
        baseUrl = $base
        quickstartRoot = $QuickstartRoot
        jarPath = $JarPath
        startedQuickstart = $startedQuickstart
        persistenceEnabled = $true
        catalogItemCount = $catalogIngest.itemCount
        federationDryRunValid = $dryRun.valid
        federationIngestValid = $ingest.valid
        releaseKey = $ingest.releaseKey
        releaseStatus = $ingest.status
        persistedSources = $ingest.persistedCounts.sources
        persistedContexts = $ingest.persistedCounts.contexts
        persistedContextRelationships = $ingest.persistedCounts.contextRelationships
        persistedContracts = $ingest.persistedCounts.contracts
        persistedResolutions = $ingest.persistedCounts.resolutions
        releaseListed = $true
        validationReportValid = $validation.validationReport.valid
        activatedStatus = $activated.status
        contextSourceMode = $context.sourceMode
        contextItemCount = $contextItemCount
        contextRelationshipCount = $contextRelationshipCount
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
