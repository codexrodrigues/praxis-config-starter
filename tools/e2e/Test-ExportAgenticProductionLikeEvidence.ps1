$ErrorActionPreference = "Stop"

$scriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) "Export-AgenticProductionLikeEvidence.ps1"
$root = Join-Path ([IO.Path]::GetTempPath()) ("praxis-evidence-export-" + [Guid]::NewGuid().ToString("N"))
$starterRoot = Join-Path $root "starter"
$e2eRoot = Join-Path $starterRoot "artifacts\page-builder-agentic-e2e\smoke\run"
$httpArtifactRoot = Join-Path $root "http"
$httpRunRoot = Join-Path $httpArtifactRoot "run"
$outputRoot = Join-Path $root "published"

try {
    New-Item -ItemType Directory -Force -Path $e2eRoot, $httpRunRoot | Out-Null
    $identities = @(
        [ordered]@{ name = "praxis-config-starter"; sha = ("a" * 40); treeSha = ("d" * 40); materialization = "working-tree"; dirty = $false }
        [ordered]@{ name = "praxis-metadata-starter"; sha = ("a" * 40); treeSha = ("d" * 40); materialization = "git-archive"; dirty = $true }
        [ordered]@{ name = "praxis-api-quickstart"; sha = ("a" * 40); treeSha = ("d" * 40); materialization = "working-tree"; dirty = $false }
        [ordered]@{ name = "praxis-ui-angular"; sha = ("a" * 40); treeSha = ("d" * 40); materialization = "working-tree"; dirty = $false }
    )
    $configStarterJarSha256 = ("e" * 64)
    [ordered]@{
        schemaVersion = "praxis.page-builder-agentic-production-like-result/v1"
        productionLike = $true
        criticalEndpointMocks = 0
        criticalInterceptionGuard = [ordered]@{ passed = $true }
        executionLane = "live"
        e2ePassed = $true
        provider = "openai"
        model = "gpt-test"
        embeddingProvider = "openai"
        datasourceKinds = [ordered]@{ application = "postgresql"; config = "postgresql" }
        dependencyAttestation = [ordered]@{
            configStarter = [ordered]@{
                artifactId = "praxis-config-starter"
                version = "1.0.0"
                localJarSha256 = $configStarterJarSha256
                quickstartNestedJarSha256 = $configStarterJarSha256
                quickstartEntry = "BOOT-INF/lib/praxis-config-starter-1.0.0.jar"
                byteIdentical = $true
            }
        }
        pgvector = [ordered]@{ ready = $true; table = "vector_store"; embeddingType = "vector(1536)" }
        loopbackOnly = $true
        cleanupVerified = $true
        capabilities = [ordered]@{ source = "registry"; degraded = $false }
        aiRegistry = [ordered]@{ ready = $true; snapshotHash = ("b" * 64) }
        catalogs = [ordered]@{
            domain = [ordered]@{ ingested = $true; schemaVersion = "praxis.domain-catalog/v0.2" }
            api = [ordered]@{ indexingState = "READY" }
        }
        versions = [ordered]@{ configStarter = "1.0.0"; quickstartConfigDependency = "1.0.0"; metadataStarterDependency = "8.0.0"; quickstart = "1.0.0"; angularWorkspace = "1.0.0"; java = 21; node = "v20"; playwright = "1.55"; chromium = "140" }
        contractHash = ("c" * 64)
        failureType = $null
        sourceAudit = [ordered]@{ passed = $true }
        git = $identities
        matrix = [ordered]@{ expectedDiscovered = 2; minimumExecuted = 2; expectedSkipped = 0 }
        playwright = [ordered]@{ discovered = 2; executed = 2; passed = 2; skipped = 0; failed = 0 }
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    @{ passed = $true } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $e2eRoot "source-audit.json") -Encoding utf8
    @{ health = "UP"; terminalSeen = $true; replayChecked = $true; provider = "openai" } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $httpRunRoot "summary.json") -Encoding utf8

    & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $outputRoot | Out-Null
    $published = @(Get-ChildItem -LiteralPath $outputRoot -File | Select-Object -ExpandProperty Name | Sort-Object)
    if (($published -join ',') -ne 'http-sse-summary.json,production-like-result.json,source-audit.json') {
        throw "Exporter published an unexpected file set: $($published -join ',')"
    }

    $invalidOutput = Join-Path $root "invalid-published"
    $invalidResult = Get-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Raw | ConvertFrom-Json
    $invalidResult.criticalEndpointMocks = 1
    $invalidResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $invalidOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Critical endpoint mocks must be zero"
    }
    if (-not $failedClosed) { throw "Exporter did not fail closed for criticalEndpointMocks=1." }

    $invalidMaterializationOutput = Join-Path $root "invalid-materialization-published"
    $invalidResult.criticalEndpointMocks = 0
    $invalidResult.git[1].materialization = "working-tree"
    $invalidResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $invalidMaterializationOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Only Metadata may have a normalized checkout"
    }
    if (-not $failedClosed) { throw "Exporter did not fail closed for dirty Metadata without git-archive materialization." }

    $divergentDependencyOutput = Join-Path $root "divergent-dependency-published"
    $invalidResult.git[1].materialization = "git-archive"
    $invalidResult.dependencyAttestation.configStarter.quickstartNestedJarSha256 = ("f" * 64)
    $invalidResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $divergentDependencyOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Config starter dependency hashes must be equal"
    }
    if (-not $failedClosed) { throw "Exporter did not fail closed for divergent Config starter dependency hashes." }

    $falseIdentityOutput = Join-Path $root "false-identity-published"
    $invalidResult.dependencyAttestation.configStarter.quickstartNestedJarSha256 = $configStarterJarSha256
    $invalidResult.dependencyAttestation.configStarter.byteIdentical = $false
    $invalidResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $falseIdentityOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Config starter byte identity attestation must be true"
    }
    if (-not $failedClosed) { throw "Exporter did not fail closed for byteIdentical=false." }

    $missingDependencyOutput = Join-Path $root "missing-dependency-published"
    $invalidResult.PSObject.Properties.Remove("dependencyAttestation")
    $invalidResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $missingDependencyOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Config starter dependency attestation is missing"
    }
    if (-not $failedClosed) { throw "Exporter did not fail closed for missing Config starter dependency attestation." }

    Write-Output "Export-AgenticProductionLikeEvidence: positive and negative fixtures passed."
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
