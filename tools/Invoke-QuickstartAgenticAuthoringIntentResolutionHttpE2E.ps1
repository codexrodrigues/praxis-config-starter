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

$previewBody = @{
    userPrompt = $UserPrompt
    targetApp = "praxis-ui-angular"
    targetComponentId = "praxis-dynamic-page-builder"
    currentRoute = "/page-builder-ia"
    currentPage = @{}
    provider = "deterministic-smoke-disabled"
    intentResolution = $intent
} | ConvertTo-Json -Compress -Depth 16

$preview = Invoke-RestMethod `
    -Method Post `
    -Uri "$base/api/praxis/config/ai/authoring/page-preview" `
    -Headers $headers `
    -Body $previewBody `
    -TimeoutSec 60

$failureCodes = @($intent.failureCodes)
$assistantMessage = [string] $intent.assistantMessage
$selectedResourcePath = [string] $intent.selectedCandidate.resourcePath
$gateStatus = [string] $intent.gate.status
$componentEditPlan = $intent.PSObject.Properties["componentEditPlan"]
$previewFailureCodes = @($preview.failureCodes)
$previewWarnings = @($preview.warnings)
$previewUiCompositionPlan = $preview.PSObject.Properties["uiCompositionPlan"]
$previewCompiledFormPatch = $preview.PSObject.Properties["compiledFormPatch"]
$previewUiCompositionPlanPresent = $null -ne $previewUiCompositionPlan -and $null -ne $previewUiCompositionPlan.Value
$previewCompiledFormPatchPresent = $false
if ($null -ne $previewCompiledFormPatch -and $null -ne $previewCompiledFormPatch.Value) {
    $compiledFormPatchJson = $previewCompiledFormPatch.Value | ConvertTo-Json -Compress -Depth 16
    $previewCompiledFormPatchPresent = (
        -not [string]::IsNullOrWhiteSpace($compiledFormPatchJson) -and
        $compiledFormPatchJson -ne "{}" -and
        $compiledFormPatchJson -ne "null"
    )
}
$pagePreviewSharedRuleRouteBlocked = (
    -not [bool] $preview.valid -and
    $previewFailureCodes -contains "intent-resolution-shared-rule-route-required" -and
    $previewWarnings -contains "preview-skipped-invalid-intent-resolution" -and
    -not $previewUiCompositionPlanPresent -and
    -not $previewCompiledFormPatchPresent
)

$result = [pscustomobject]@{
    health = $health.status
    provider = $Provider
    valid = [bool] $intent.valid
    gateStatus = $gateStatus
    failureCodes = $failureCodes
    selectedResourcePath = $selectedResourcePath
    assistantMessage = $assistantMessage
    componentEditPlanPresent = $null -ne $componentEditPlan -and $null -ne $componentEditPlan.Value
    pagePreviewValid = [bool] $preview.valid
    pagePreviewFailureCodes = $previewFailureCodes
    pagePreviewWarnings = $previewWarnings
    pagePreviewSharedRuleRouteBlocked = $pagePreviewSharedRuleRouteBlocked
    pagePreviewUiCompositionPlanPresent = $previewUiCompositionPlanPresent
    pagePreviewCompiledFormPatchPresent = $previewCompiledFormPatchPresent
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
if ($result.assistantMessage -notlike "*/api/praxis/config/domain-rules/intake*") {
    throw "Assistant message did not name the canonical domain-rules intake endpoint."
}
if ($result.assistantMessage -notlike "*/api/praxis/config/domain-rules/simulations*") {
    throw "Assistant message did not name the canonical domain-rules simulations endpoint."
}
if ($result.componentEditPlanPresent) {
    throw "Intent resolution returned componentEditPlan for a governed business-rule route."
}
if ($result.pagePreviewValid) {
    throw "Page preview should not be valid for governed shared-rule authoring route."
}
if ($result.pagePreviewFailureCodes -notcontains "intent-resolution-shared-rule-route-required") {
    throw "Page preview did not report intent-resolution-shared-rule-route-required."
}
if ($result.pagePreviewWarnings -notcontains "preview-skipped-invalid-intent-resolution") {
    throw "Page preview did not report preview-skipped-invalid-intent-resolution."
}
if ($result.pagePreviewUiCompositionPlanPresent) {
    throw "Page preview returned uiCompositionPlan for a governed business-rule route."
}
if ($result.pagePreviewCompiledFormPatchPresent) {
    throw "Page preview returned compiledFormPatch for a governed business-rule route."
}
if (-not $result.pagePreviewSharedRuleRouteBlocked) {
    throw "Page preview did not preserve the canonical shared-rule routing block."
}

$result | ConvertTo-Json -Depth 6
