param(
    [string] $EnvFile = ".env.openai.local.ps1"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$envPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile
} else {
    Join-Path $root $EnvFile
}

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "OpenAI env file not found: $envPath"
}

. $envPath

$apiKey = $env:PRAXIS_AI_OPENAI_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey) -or $apiKey -eq "PASTE_OPENAI_API_KEY_HERE") {
    throw "Fill PRAXIS_AI_OPENAI_API_KEY in $envPath before running this probe."
}

$baseUrl = $env:PRAXIS_AI_OPENAI_BASE_URL
if ([string]::IsNullOrWhiteSpace($baseUrl)) {
    $baseUrl = "https://api.openai.com"
}
$baseUrl = $baseUrl.TrimEnd("/")

$modelsUrl = if ($baseUrl.EndsWith("/v1")) {
    "$baseUrl/models"
} else {
    "$baseUrl/v1/models"
}

$headers = @{
    Authorization = "Bearer $apiKey"
}

try {
    $response = Invoke-RestMethod -Method Get -Uri $modelsUrl -Headers $headers -TimeoutSec 30
} catch {
    throw "OpenAI key probe failed against $modelsUrl. $($_.Exception.Message)"
}

$models = @($response.data | ForEach-Object { $_.id } | Where-Object { $_ })
$selected = $env:PRAXIS_AI_OPENAI_MODEL

Write-Host "OpenAI key probe passed."
Write-Host "Base URL: $baseUrl"
Write-Host "Configured model: $selected"
Write-Host "Models returned: $($models.Count)"

if (-not [string]::IsNullOrWhiteSpace($selected) -and $models.Count -gt 0 -and ($models -notcontains $selected)) {
    Write-Warning "Configured model '$selected' was not present in /models. The key is valid, but this model may be unavailable for the account."
}
