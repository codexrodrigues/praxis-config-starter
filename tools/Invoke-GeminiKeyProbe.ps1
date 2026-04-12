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
    throw "Gemini env file not found: $envPath"
}

. $envPath

$apiKey = $env:PRAXIS_AI_GEMINI_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey) -or $apiKey -eq "PASTE_GEMINI_API_KEY_HERE") {
    throw "Fill PRAXIS_AI_GEMINI_API_KEY in $envPath before running this probe."
}

$modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$([System.Uri]::EscapeDataString($apiKey))"

try {
    $response = Invoke-RestMethod -Method Get -Uri $modelsUrl -TimeoutSec 30
} catch {
    $status = $null
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
        $status = [int]$_.Exception.Response.StatusCode
    }
    if ($status) {
        throw "Gemini key probe failed with HTTP $status against Google Generative Language models endpoint."
    }
    throw "Gemini key probe failed against Google Generative Language models endpoint. $($_.Exception.Message)"
}

$models = @($response.models | ForEach-Object { $_.name } | Where-Object { $_ })
$configured = $env:PRAXIS_AI_GEMINI_MODEL
$configuredResource = if ([string]::IsNullOrWhiteSpace($configured)) {
    $null
} elseif ($configured.StartsWith("models/")) {
    $configured
} else {
    "models/$configured"
}

Write-Host "Gemini key probe passed."
Write-Host "Endpoint: https://generativelanguage.googleapis.com/v1beta/models"
Write-Host "Configured model: $configured"
Write-Host "Models returned: $($models.Count)"

if ($configuredResource -and $models.Count -gt 0 -and ($models -notcontains $configuredResource)) {
    Write-Warning "Configured model '$configured' was not present in /models. The key is valid, but this model may be unavailable for the account."
}
