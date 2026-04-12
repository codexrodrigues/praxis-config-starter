param(
    [string] $BaseUrl = "http://localhost:8088",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile
} else {
    Join-Path $root $EnvFile
}

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "AI env file not found: $envPath"
}

. $envPath

if ([string]::IsNullOrWhiteSpace($env:PRAXIS_AI_OPENAI_API_KEY) -or $env:PRAXIS_AI_OPENAI_API_KEY -eq "PASTE_OPENAI_API_KEY_HERE") {
    throw "PRAXIS_AI_OPENAI_API_KEY must be configured in $envPath."
}
if ([string]::IsNullOrWhiteSpace($env:PRAXIS_AI_GEMINI_API_KEY) -or $env:PRAXIS_AI_GEMINI_API_KEY -eq "PASTE_GEMINI_API_KEY_HERE") {
    throw "PRAXIS_AI_GEMINI_API_KEY must be configured in $envPath."
}

$base = $BaseUrl.TrimEnd("/")
$headers = @{
    "Origin" = $Origin
    "Content-Type" = "application/json"
    "X-Tenant-ID" = $TenantId
    "X-User-ID" = $UserId
    "X-Env" = $Environment
}

function Invoke-JsonPost {
    param(
        [string] $Path,
        [hashtable] $Body
    )
    Invoke-RestMethod `
        -Method Post `
        -Uri "$base$Path" `
        -Headers $headers `
        -Body ($Body | ConvertTo-Json -Compress) `
        -TimeoutSec 90
}

$health = Invoke-RestMethod -Method Get -Uri "$base/actuator/health" -TimeoutSec 10
$catalog = Invoke-RestMethod -Method Get -Uri "$base/api/praxis/config/ai/providers/catalog" -Headers $headers -TimeoutSec 30

$openAiTest = Invoke-JsonPost -Path "/api/praxis/config/ai/providers/test" -Body @{
    provider = "openai"
    model = $env:PRAXIS_AI_OPENAI_MODEL
    apiKey = $env:PRAXIS_AI_OPENAI_API_KEY
}
$geminiTest = Invoke-JsonPost -Path "/api/praxis/config/ai/providers/test" -Body @{
    provider = "gemini"
    model = $env:PRAXIS_AI_GEMINI_MODEL
    apiKey = $env:PRAXIS_AI_GEMINI_API_KEY
}
$openAiModels = Invoke-JsonPost -Path "/api/praxis/config/ai/providers/models" -Body @{
    provider = "openai"
    apiKey = $env:PRAXIS_AI_OPENAI_API_KEY
}
$geminiModels = Invoke-JsonPost -Path "/api/praxis/config/ai/providers/models" -Body @{
    provider = "gemini"
    apiKey = $env:PRAXIS_AI_GEMINI_API_KEY
}

$result = [pscustomobject]@{
    health = $health.status
    catalogProviders = @($catalog.providers).Count
    openai = [pscustomobject]@{
        model = $openAiTest.model
        success = [bool] $openAiTest.success
        message = $openAiTest.message
        modelsSuccess = [bool] $openAiModels.success
        modelsReturned = @($openAiModels.models).Count
    }
    gemini = [pscustomobject]@{
        model = $geminiTest.model
        success = [bool] $geminiTest.success
        message = $geminiTest.message
        modelsSuccess = [bool] $geminiModels.success
        modelsReturned = @($geminiModels.models).Count
    }
}

if ($result.health -ne "UP") {
    throw "Quickstart health is not UP."
}
if (-not $result.openai.success -or -not $result.openai.modelsSuccess) {
    throw "OpenAI HTTP provider E2E failed."
}
if (-not $result.gemini.success -or -not $result.gemini.modelsSuccess) {
    throw "Gemini HTTP provider E2E failed."
}

$result | ConvertTo-Json -Depth 6
