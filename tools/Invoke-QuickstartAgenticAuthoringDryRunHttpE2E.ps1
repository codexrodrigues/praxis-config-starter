param(
    [string] $BaseUrl = "http://localhost:8088",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local"
)

$ErrorActionPreference = "Stop"

$base = $BaseUrl.TrimEnd("/")
$headers = @{
    "Origin" = $Origin
    "Content-Type" = "application/json"
    "X-Tenant-ID" = $TenantId
    "X-User-ID" = $UserId
    "X-Env" = $Environment
}

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
$dryRun = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/dry-run" `
    -Headers $headers `
    -Body "{}" `
    -TimeoutSec 30

$gates = @($dryRun.gates)
$result = [pscustomobject]@{
    health = $health.status
    valid = [bool] $dryRun.valid
    gateCount = $gates.Count
    failureCodes = @($dryRun.failureCodes)
    warningCount = @($dryRun.warnings).Count
    hasPatch = $null -ne $dryRun.patch
}

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if (-not $result.valid) {
    throw "Agentic authoring dry-run is not valid: $($result.failureCodes -join ', ')"
}
if ($result.gateCount -lt 4) {
    throw "Agentic authoring dry-run returned fewer gates than expected."
}
if (-not $result.hasPatch) {
    throw "Agentic authoring dry-run did not return a patch."
}

$result | ConvertTo-Json -Depth 6
