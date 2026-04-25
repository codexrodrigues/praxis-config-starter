param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [string] $UserPrompt = "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras"
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

$body = @{
    userPrompt = $UserPrompt
    targetApp = "praxis-ui-angular"
    targetComponentId = "praxis-dynamic-page-builder"
    currentRoute = "/page-builder-ia"
    currentPage = @{}
    # This smoke validates canonical routing, not model quality; force deterministic fallback.
    provider = "deterministic-smoke-disabled"
} | ConvertTo-Json -Compress -Depth 8

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
$intent = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/intent-resolution" `
    -Headers $headers `
    -Body $body `
    -TimeoutSec 60

$failureCodes = @($intent.failureCodes)
$assistantMessage = [string] $intent.assistantMessage
$selectedResourcePath = [string] $intent.selectedCandidate.resourcePath
$gateStatus = [string] $intent.gate.status
$componentEditPlan = $intent.PSObject.Properties["componentEditPlan"]

$result = [pscustomobject]@{
    health = $health.status
    provider = $Provider
    valid = [bool] $intent.valid
    gateStatus = $gateStatus
    failureCodes = $failureCodes
    selectedResourcePath = $selectedResourcePath
    assistantMessage = $assistantMessage
    componentEditPlanPresent = $null -ne $componentEditPlan -and $null -ne $componentEditPlan.Value
}

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if ($result.valid) {
    throw "Intent resolution should not be valid for governed shared-rule authoring route."
}
if ($result.gateStatus -ne "route_required") {
    throw "Expected intent-resolution gate.status=route_required, got '$($result.gateStatus)'."
}
if ($result.failureCodes -notcontains "shared-rule-authoring-required") {
    throw "Intent resolution did not report shared-rule-authoring-required."
}
if ($result.selectedResourcePath -ne "/api/procurement/suppliers") {
    throw "Expected procurement suppliers candidate, got '$($result.selectedResourcePath)'."
}
if ($result.assistantMessage -notlike "*/api/praxis/config/domain-rules*") {
    throw "Assistant message did not route to /api/praxis/config/domain-rules."
}
if ($result.componentEditPlanPresent) {
    throw "Intent resolution returned componentEditPlan for a governed business-rule route."
}

$result | ConvertTo-Json -Depth 6
