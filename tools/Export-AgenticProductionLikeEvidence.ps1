param(
    [Parameter(Mandatory = $true)]
    [string] $StarterRoot,
    [Parameter(Mandatory = $true)]
    [string] $HttpArtifactRoot,
    [Parameter(Mandatory = $true)]
    [string] $OutputRoot
)

$ErrorActionPreference = "Stop"

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Get-LatestRequiredFile([string] $Root, [string] $Filter) {
    $file = Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Filter -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $file) { throw "Required evidence file was not found: $Filter under $Root" }
    return $file
}

if (Test-Path -LiteralPath $OutputRoot) {
    throw "Sanitized evidence output already exists: $OutputRoot"
}

$e2eRoot = Join-Path $StarterRoot "artifacts\page-builder-agentic-e2e"
$resultFile = Get-LatestRequiredFile $e2eRoot "result.json"
$sourceAuditFile = Join-Path $resultFile.Directory.FullName "source-audit.json"
if (-not (Test-Path -LiteralPath $sourceAuditFile)) {
    throw "Source audit evidence is missing beside the production-like result."
}
$httpSummaryFile = Get-LatestRequiredFile $HttpArtifactRoot "summary.json"

$result = Get-Content -LiteralPath $resultFile.FullName -Raw | ConvertFrom-Json
$sourceAudit = Get-Content -LiteralPath $sourceAuditFile -Raw | ConvertFrom-Json
$httpSummary = Get-Content -LiteralPath $httpSummaryFile.FullName -Raw | ConvertFrom-Json

Assert-True ($result.schemaVersion -eq "praxis.page-builder-agentic-production-like-result/v1") "Unexpected production-like result schema."
Assert-True ($result.productionLike -eq $true) "The browser result is not production-like."
Assert-True ($null -ne $result.criticalEndpointMocks -and [int] $result.criticalEndpointMocks -eq 0) "Critical endpoint mocks must be zero."
Assert-True ($result.criticalInterceptionGuard.passed -eq $true) "The critical interception guard did not pass."
Assert-True ($result.executionLane -eq "live" -and $result.e2ePassed -eq $true) "The live browser lane did not pass."
Assert-True ($result.loopbackOnly -eq $true -and $result.cleanupVerified -eq $true) "Loopback or cleanup proof is missing."
Assert-True ($result.datasourceKinds.application -eq "postgresql" -and $result.datasourceKinds.config -eq "postgresql") "Both datasources must be PostgreSQL."
Assert-True ($result.pgvector.ready -eq $true -and $result.pgvector.table -eq "vector_store" -and ([string] $result.pgvector.embeddingType).StartsWith("vector")) "Pgvector readiness evidence is missing."
Assert-True ($result.capabilities.source -eq "registry" -and $result.capabilities.degraded -eq $false) "Capabilities must be registry-backed and non-degraded."
Assert-True ($result.aiRegistry.ready -eq $true -and (([string] $result.aiRegistry.snapshotHash) -match '^[0-9a-f]{64}$')) "AI Registry readiness or immutable snapshot evidence is missing."
Assert-True ($result.catalogs.domain.ingested -eq $true -and $result.catalogs.domain.schemaVersion -eq "praxis.domain-catalog/v0.2") "Domain Catalog evidence is missing."
Assert-True ($result.catalogs.api.indexingState -eq "READY") "API Catalog canonical indexing did not reach READY."
Assert-True (-not [string]::IsNullOrWhiteSpace([string] $result.provider) -and $result.provider -ne "mock") "A real provider is required."
Assert-True (-not [string]::IsNullOrWhiteSpace([string] $result.embeddingProvider) -and $result.embeddingProvider -ne "mock") "A real embedding provider is required."
Assert-True (-not [string]::IsNullOrWhiteSpace([string] $result.model)) "A sanitized provider model identifier is required."
Assert-True ($result.versions.java -eq 21 -and -not [string]::IsNullOrWhiteSpace([string] $result.versions.node)) "Runtime version evidence is incomplete."
Assert-True (-not [string]::IsNullOrWhiteSpace([string] $result.versions.playwright) -and -not [string]::IsNullOrWhiteSpace([string] $result.versions.chromium)) "Browser version evidence is incomplete."
Assert-True (-not [string]::IsNullOrWhiteSpace([string] $result.versions.metadataStarterDependency) -and -not [string]::IsNullOrWhiteSpace([string] $result.versions.quickstart) -and -not [string]::IsNullOrWhiteSpace([string] $result.versions.angularWorkspace)) "Maven/npm version evidence is incomplete."
Assert-True ($result.versions.configStarter -eq $result.versions.quickstartConfigDependency) "Quickstart did not package the exercised Config version."
Assert-True (([string] $result.contractHash) -match '^[0-9a-f]{64}$') "The Config/Angular contract hash is missing."
Assert-True ($null -eq $result.failureType) "A successful publication cannot retain a gate failure."
Assert-True ($result.sourceAudit.passed -eq $true -and $sourceAudit.passed -eq $true) "The real-source audit did not pass."
Assert-True ([int] $result.playwright.discovered -eq [int] $result.matrix.expectedDiscovered) "Playwright discovery diverges from the matrix."
Assert-True ([int] $result.playwright.executed -ge [int] $result.matrix.minimumExecuted) "Playwright execution is below the matrix minimum."
Assert-True ([int] $result.playwright.skipped -eq [int] $result.matrix.expectedSkipped) "Playwright skipped count diverges from the matrix."
Assert-True ([int] $result.playwright.failed -eq 0 -and [int] $result.playwright.passed -eq [int] $result.playwright.executed) "Not every executed Playwright test passed."
Assert-True (@($result.git).Count -eq 4) "The four immutable repository identities are required."
foreach ($identity in @($result.git)) {
    Assert-True (([string] $identity.sha) -match '^[0-9a-f]{40}$') "Invalid immutable SHA for $($identity.name)."
    Assert-True (([string] $identity.treeSha) -match '^[0-9a-f]{40}$') "Invalid immutable tree SHA for $($identity.name)."
    Assert-True ([string] $identity.materialization -in @('working-tree', 'git-archive')) "Invalid source materialization for $($identity.name)."
    if ($identity.dirty -eq $true) {
        Assert-True ($identity.name -eq 'praxis-metadata-starter' -and $identity.materialization -eq 'git-archive') "Only Metadata may have a normalized checkout, and only when the exact git archive is exercised."
    }
}
Assert-True ($httpSummary.health -eq "UP" -and $httpSummary.terminalSeen -eq $true -and $httpSummary.replayChecked -eq $true) "HTTP/SSE evidence is incomplete."
Assert-True ($httpSummary.provider -ne "mock") "HTTP/SSE evidence used a mock provider."

$resultJson = $result | ConvertTo-Json -Depth 20
$sourceAuditJson = $sourceAudit | ConvertTo-Json -Depth 20
$httpSummaryJson = $httpSummary | ConvertTo-Json -Depth 20
$publishedText = @($resultJson, $sourceAuditJson, $httpSummaryJson) -join "`n"
$secretPatterns = @(
    '(?i)sk-[a-z0-9_-]{20,}',
    '(?i)gh[pousr]_[a-z0-9]{20,}',
    '(?i)jdbc:postgresql://[^\s"'']+@',
    '(?i)[?&](password|token|api_key|apikey)='
)
foreach ($pattern in $secretPatterns) {
    Assert-True (-not [regex]::IsMatch($publishedText, $pattern)) "Sanitized evidence matched a forbidden credential pattern."
}

New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$resultJson | Set-Content -LiteralPath (Join-Path $OutputRoot "production-like-result.json") -Encoding utf8
$sourceAuditJson | Set-Content -LiteralPath (Join-Path $OutputRoot "source-audit.json") -Encoding utf8
$httpSummaryJson | Set-Content -LiteralPath (Join-Path $OutputRoot "http-sse-summary.json") -Encoding utf8
$publishedFiles = @(Get-ChildItem -LiteralPath $OutputRoot -Recurse -File)

[pscustomobject]@{
    schemaVersion = "praxis.agentic-authoring-publication/v1"
    passed = $true
    files = @($publishedFiles | ForEach-Object { $_.Name } | Sort-Object)
} | ConvertTo-Json -Depth 4
