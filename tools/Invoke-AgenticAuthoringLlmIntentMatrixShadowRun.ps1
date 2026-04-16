param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $JavaHome = "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21",
    [string] $MavenHome = "D:\Developer\maven\apache-maven-3.9.6",
    [switch] $FailOnDivergence,
    [switch] $FailOnProviderUnavailable,
    [double] $MinAccuracy = -1.0
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
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) {
    throw "Java executable not found under JAVA_HOME: $JavaHome"
}
if (-not (Test-Path -LiteralPath (Join-Path $MavenHome "bin\mvn.cmd"))) {
    throw "Maven executable not found under MAVEN_HOME: $MavenHome"
}

. $envPath

$env:JAVA_HOME = $JavaHome
$env:MAVEN_HOME = $MavenHome
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
$env:PRAXIS_AGENTIC_AUTHORING_LLM_INTENT_MATRIX_SHADOW = "true"
$env:PRAXIS_AGENTIC_AUTHORING_SHADOW_PROVIDER = $Provider
$env:PRAXIS_AI_PROVIDER = $Provider
$env:PRAXIS_AGENTIC_AUTHORING_SHADOW_MATRIX_FAIL_ON_DIVERGENCE = $FailOnDivergence.IsPresent.ToString().ToLowerInvariant()
$env:PRAXIS_AGENTIC_AUTHORING_SHADOW_MATRIX_FAIL_ON_PROVIDER_UNAVAILABLE = $FailOnProviderUnavailable.IsPresent.ToString().ToLowerInvariant()

if ($MinAccuracy -ge 0.0) {
    if ($MinAccuracy -gt 1.0) {
        throw "MinAccuracy must be between 0.0 and 1.0."
    }
    $env:PRAXIS_AGENTIC_AUTHORING_SHADOW_MATRIX_MIN_ACCURACY = $MinAccuracy.ToString([System.Globalization.CultureInfo]::InvariantCulture)
} else {
    Remove-Item Env:\PRAXIS_AGENTIC_AUTHORING_SHADOW_MATRIX_MIN_ACCURACY -ErrorAction SilentlyContinue
}

if ($Provider -eq "openai") {
    if ([string]::IsNullOrWhiteSpace($env:PRAXIS_AI_OPENAI_API_KEY) -or $env:PRAXIS_AI_OPENAI_API_KEY -eq "PASTE_OPENAI_API_KEY_HERE") {
        throw "PRAXIS_AI_OPENAI_API_KEY must be configured in $envPath."
    }
} else {
    if ([string]::IsNullOrWhiteSpace($env:PRAXIS_AI_GEMINI_API_KEY) -or $env:PRAXIS_AI_GEMINI_API_KEY -eq "PASTE_GEMINI_API_KEY_HERE") {
        throw "PRAXIS_AI_GEMINI_API_KEY must be configured in $envPath."
    }
}

Push-Location $root
try {
    & (Join-Path $MavenHome "bin\mvn.cmd") "-Dtest=AgenticAuthoringLlmIntentMatrixShadowIntegrationTest" test
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
