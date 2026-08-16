param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $BaseUrl = "http://localhost:8088",
    [int] $Port = 8088,
    [string] $QuickstartRoot = "",
    [string] $JarPath = "",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $JavaHome = "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21",
    [string] $EmbeddingProvider = "",
    [string] $Origin = "http://localhost:4200",
    [string] $TenantId = "agentic-authoring-e2e",
    [string] $UserId = "codex-local",
    [string] $Environment = "local",
    [int] $StartupTimeoutSec = 180,
    [int] $StreamProcessingTimeoutSeconds = 180,
    [switch] $DomainRuleLifecycleOnly,
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

$starterRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $starterRoot
$starterAuthoringRoot = Join-Path $starterRoot "docs\ai\agentic-authoring"
$workspaceAuthoringRoot = Join-Path $workspaceRoot "docs\ai\agentic-authoring"
$authoringRoot = if (Test-Path -LiteralPath (Join-Path $starterAuthoringRoot "contracts")) {
    $starterAuthoringRoot
} else {
    $workspaceAuthoringRoot
}
if ([string]::IsNullOrWhiteSpace($QuickstartRoot)) {
    $QuickstartRoot = Join-Path $workspaceRoot "praxis-api-quickstart"
}

$envPath = Resolve-RepoPath $EnvFile $starterRoot
if (-not (Test-Path -LiteralPath $envPath)) {
    throw "AI env file not found: $envPath"
}

if (-not (Test-Path -LiteralPath $QuickstartRoot)) {
    throw "Quickstart root not found: $QuickstartRoot"
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = Get-ChildItem -Path (Join-Path $QuickstartRoot "target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "(sources|javadoc|tests)\.jar$" } |
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
$governanceAuthorUsername = "$UserId-author"
$governanceApproverAUsername = "$UserId-approver-a"
$governanceApproverBUsername = "$UserId-approver-b"
$governancePublisherUsername = "$UserId-publisher"
$governanceOperatorUsername = "$UserId-operator"
$governanceAuditorUsername = "$UserId-auditor"
$governanceAuthorPassword = [Guid]::NewGuid().ToString("N")
$governanceApproverAPassword = [Guid]::NewGuid().ToString("N")
$governanceApproverBPassword = [Guid]::NewGuid().ToString("N")
$governancePublisherPassword = [Guid]::NewGuid().ToString("N")
$governanceOperatorPassword = [Guid]::NewGuid().ToString("N")
$governanceAuditorPassword = [Guid]::NewGuid().ToString("N")
$expectAuthorApprovalIamRejection = $DomainRuleLifecycleOnly.IsPresent
$corporateMode = if ($expectAuthorApprovalIamRejection) { "true" } else { "false" }
$governanceLabEnvironment = @"
`$env:APP_AUTH_GOVERNANCE_LAB_ENABLED = 'true'
`$env:APP_AUTH_GOVERNANCE_AUTHOR_USERNAME = '$governanceAuthorUsername'
`$env:APP_AUTH_GOVERNANCE_AUTHOR_PASSWORD = '$governanceAuthorPassword'
`$env:APP_AUTH_GOVERNANCE_APPROVER_A_USERNAME = '$governanceApproverAUsername'
`$env:APP_AUTH_GOVERNANCE_APPROVER_A_PASSWORD = '$governanceApproverAPassword'
`$env:APP_AUTH_GOVERNANCE_APPROVER_B_USERNAME = '$governanceApproverBUsername'
`$env:APP_AUTH_GOVERNANCE_APPROVER_B_PASSWORD = '$governanceApproverBPassword'
`$env:APP_AUTH_GOVERNANCE_PUBLISHER_USERNAME = '$governancePublisherUsername'
`$env:APP_AUTH_GOVERNANCE_PUBLISHER_PASSWORD = '$governancePublisherPassword'
`$env:APP_AUTH_GOVERNANCE_OPERATOR_USERNAME = '$governanceOperatorUsername'
`$env:APP_AUTH_GOVERNANCE_OPERATOR_PASSWORD = '$governanceOperatorPassword'
`$env:APP_AUTH_GOVERNANCE_AUDITOR_USERNAME = '$governanceAuditorUsername'
`$env:APP_AUTH_GOVERNANCE_AUDITOR_PASSWORD = '$governanceAuditorPassword'
"@
if ($DomainRuleLifecycleOnly) {
$governanceLabEnvironment += [Environment]::NewLine + @"
`$env:PRAXIS_AI_SECURITY_ALLOW_DEFAULT_TENANT_IN_CORPORATE = 'true'
`$env:PRAXIS_AI_SECURITY_SERVER_DEFAULT_TENANT = '$TenantId'
`$env:PRAXIS_AI_SECURITY_SERVER_DEFAULT_ENVIRONMENT = '$Environment'
"@
}

try {
    if ($null -ne $existingPid) {
        if (-not $UseExistingQuickstart.IsPresent) {
            throw "Port $Port is already in use by PID $existingPid. Re-run with -UseExistingQuickstart or free the port."
        }
    } else {
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) {
            throw "java.exe not found under JavaHome: $JavaHome"
        }

        $resolvedEmbeddingProvider = if ([string]::IsNullOrWhiteSpace($EmbeddingProvider)) {
            if ($DomainRuleLifecycleOnly) { "mock" } else { $Provider }
        } else { $EmbeddingProvider }
        if ($resolvedEmbeddingProvider -ieq "mock" -and -not $DomainRuleLifecycleOnly) {
            throw "EMBEDDING_PROVIDER=mock is not valid for the live agentic authoring HTTP smoke suite. Use -EmbeddingProvider $Provider, or a documented deterministic non-LLM runner."
        }

        $outLog = Join-Path $logDir ("agentic-authoring-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".out.log")
        $errLog = Join-Path $logDir ("agentic-authoring-smoke-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".err.log")
        $startScript = @"
`$ErrorActionPreference = 'Stop'
Set-Location '$QuickstartRoot'
. '$envPath'
`$env:JAVA_HOME = '$JavaHome'
`$env:Path = '$JavaHome\bin;' + `$env:Path
`$env:PORT = '$Port'
`$env:SPRING_PROFILES_ACTIVE = ''
`$env:PRAXIS_AI_PROVIDER = '$Provider'
`$env:APP_SECURITY_READ_OPEN = 'true'
`$env:APP_SECURITY_CSRF_DISABLE = 'true'
`$env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = 'http://localhost:4003,http://127.0.0.1:4003,http://localhost:4200,http://127.0.0.1:4200'
`$env:CORS_ALLOWED_ORIGINS = 'http://localhost:4003,http://127.0.0.1:4003,http://localhost:4200,http://127.0.0.1:4200'
`$env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = 'true'
`$env:PRAXIS_AI_AUTHORING_ARTIFACTS_DIR = '$authoringRoot\proofs'
`$env:PRAXIS_AI_AUTHORING_CONTRACTS_DIR = '$authoringRoot\contracts'
`$env:PRAXIS_AI_STREAM_PROCESSING_TIMEOUT_SECONDS = '$StreamProcessingTimeoutSeconds'
`$env:PRAXIS_AI_SECURITY_CORPORATE_MODE = '$corporateMode'
`$env:PRAXIS_AI_SECURITY_ALLOW_HEADER_IDENTITY_IN_LOCAL = 'true'
`$env:EMBEDDING_PROVIDER = '$resolvedEmbeddingProvider'
$governanceLabEnvironment
if (`$env:PRAXIS_AI_OPENAI_MODEL) {
    `$env:SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL = `$env:PRAXIS_AI_OPENAI_MODEL
}
& '$JavaHome\bin\java.exe' -jar '$JarPath'
"@
        $launchTokens = $null
        $launchParseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseInput(
            $startScript,
            [ref] $launchTokens,
            [ref] $launchParseErrors) | Out-Null
        if ($launchParseErrors.Count -gt 0) {
            $launchDiagnostics = $launchParseErrors |
                ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Message)" }
            throw "Generated Quickstart launch script is invalid: $($launchDiagnostics -join '; ')"
        }
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($startScript))
        $quickstartProcess = Start-Process `
            -FilePath "powershell.exe" `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encoded) `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -PassThru `
            -WindowStyle Hidden
        $startedQuickstart = $true
    }

    $health = Wait-QuickstartHealth "$base/actuator/health" $StartupTimeoutSec

    $commonArgs = @{
        Provider = $Provider
        BaseUrl = $base
        EnvFile = $envPath
        Origin = $Origin
        TenantId = $TenantId
        UserId = $UserId
        Environment = $Environment
    }

    $domainRuleArgs = @{} + $commonArgs
    $domainRuleArgs.AuthorUsername = $governanceAuthorUsername
    $domainRuleArgs.AuthorPassword = $governanceAuthorPassword
    $domainRuleArgs.ReviewerUsername = $governanceApproverAUsername
    $domainRuleArgs.ReviewerPassword = $governanceApproverAPassword
    $domainRuleArgs.ExpectAuthorApprovalIamRejection = $expectAuthorApprovalIamRejection
    $domainRuleLifecycle = & (Join-Path $PSScriptRoot "Invoke-QuickstartDomainRuleLifecycleHttpE2E.ps1") @domainRuleArgs | ConvertFrom-Json
    if ($DomainRuleLifecycleOnly) {
        [pscustomobject]@{
            health = $health.status
            provider = "not-used"
            baseUrl = $base
            quickstartRoot = $QuickstartRoot
            jarPath = $JarPath
            startedQuickstart = $startedQuickstart
            domainRuleLifecycleOnly = $true
            domainRuleAppliedCreationBlocked = [bool] $domainRuleLifecycle.appliedCreationBlocked
            domainRuleSelfApprovalBlocked = [bool] $domainRuleLifecycle.selfApprovalBlocked
            domainRuleAuthenticatedAuthor = [string] $domainRuleLifecycle.authenticatedAuthor
            domainRuleAuthenticatedReviewer = [string] $domainRuleLifecycle.authenticatedReviewer
            domainRuleAppliedMaterializationHasAppliedAt = [bool] $domainRuleLifecycle.appliedMaterializationHasAppliedAt
            domainRuleTerminalDefinitionTransitionBlocked = [bool] $domainRuleLifecycle.terminalDefinitionTransitionBlocked
            domainRuleTerminalMaterializationTransitionBlocked = [bool] $domainRuleLifecycle.terminalMaterializationTransitionBlocked
            domainRuleTerminalPublishBlocked = [bool] $domainRuleLifecycle.terminalPublishBlocked
            domainRuleSemanticSourceHashesDiffer = [bool] $domainRuleLifecycle.semanticSourceHashesDiffer
            domainRuleBackendValidationSemanticSourceHashesDiffer = [bool] $domainRuleLifecycle.backendValidationSemanticSourceHashesDiffer
        } | ConvertTo-Json -Depth 8
        return
    }

    $intentResolution = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringIntentResolutionHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $plan = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringPlanHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $compile = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringCompileHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $preview = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringPreviewHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $apply = & (Join-Path $PSScriptRoot "Invoke-QuickstartAgenticAuthoringApplyHttpE2E.ps1") @commonArgs | ConvertFrom-Json
    $stream = & (Join-Path $PSScriptRoot "Invoke-QuickstartAiPatchStreamHttpE2E.ps1") `
        -BaseUrl $base `
        -Origin $Origin `
        -TenantId $TenantId `
        -UserId $UserId `
        -Environment $Environment `
        -Provider $Provider | ConvertFrom-Json
    $domainRuleIntentRoutingSeen = (
        $intentResolution.gateStatus -eq "route_required" -and
        -not [bool] $intentResolution.componentEditPlanPresent -and
        [bool] $intentResolution.pagePreviewSharedRuleRouteBlocked
    )

    [pscustomobject]@{
        health = $health.status
        provider = $Provider
        baseUrl = $base
        quickstartRoot = $QuickstartRoot
        jarPath = $JarPath
        startedQuickstart = $startedQuickstart
        intentRouteRequired = $intentResolution.gateStatus -eq "route_required"
        intentSelectedResourcePath = $intentResolution.selectedResourcePath
        domainRuleIntentRoutingSeen = $domainRuleIntentRoutingSeen
        domainRulePagePreviewRouteBlocked = [bool] $intentResolution.pagePreviewSharedRuleRouteBlocked
        domainRuleAppliedCreationBlocked = [bool] $domainRuleLifecycle.appliedCreationBlocked
        domainRuleSelfApprovalBlocked = [bool] $domainRuleLifecycle.selfApprovalBlocked
        domainRuleAuthenticatedAuthor = [string] $domainRuleLifecycle.authenticatedAuthor
        domainRuleAuthenticatedReviewer = [string] $domainRuleLifecycle.authenticatedReviewer
        domainRuleAppliedMaterializationHasAppliedAt = [bool] $domainRuleLifecycle.appliedMaterializationHasAppliedAt
        domainRuleTerminalDefinitionTransitionBlocked = [bool] $domainRuleLifecycle.terminalDefinitionTransitionBlocked
        domainRuleTerminalMaterializationTransitionBlocked = [bool] $domainRuleLifecycle.terminalMaterializationTransitionBlocked
        domainRuleTerminalPublishBlocked = [bool] $domainRuleLifecycle.terminalPublishBlocked
        domainRuleSemanticSourceHashesDiffer = [bool] $domainRuleLifecycle.semanticSourceHashesDiffer
        domainRuleBackendValidationSemanticSourceHashesDiffer = [bool] $domainRuleLifecycle.backendValidationSemanticSourceHashesDiffer
        domainRulePublicationCreatedDiagnosticsSeen = [bool] $domainRuleLifecycle.publicationCreatedDiagnosticsSeen
        domainRulePublicationSelectedExistingDiagnosticsSeen = [bool] $domainRuleLifecycle.publicationSelectedExistingDiagnosticsSeen
        domainRulePublicationReusedDiagnosticsSeen = [bool] $domainRuleLifecycle.publicationReusedDiagnosticsSeen
        domainRulePublicationBlockedDiagnosticsSeen = [bool] $domainRuleLifecycle.publicationBlockedDiagnosticsSeen
        domainRuleIntakeDecisionDiagnosticsSeen = [bool] $domainRuleLifecycle.intakeDecisionDiagnosticsSeen
        domainRuleDecisionDiagnosticsSeen = [bool] $domainRuleLifecycle.decisionDiagnosticsSeen
        domainRuleMaterializationDecisionDiagnosticsSeen = [bool] $domainRuleLifecycle.materializationDecisionDiagnosticsSeen
        domainRuleMaterializationSourceHashDiagnosticsSeen = [bool] $domainRuleLifecycle.materializationSourceHashDiagnosticsSeen
        domainRuleProcurementOptionSourcePolicySeen = [bool] $domainRuleLifecycle.procurementOptionSourcePolicySeen
        domainRuleProcurementBackendValidationPolicySeen = [bool] $domainRuleLifecycle.procurementBackendValidationPolicySeen
        planValid = [bool] $plan.valid
        compileValid = [bool] $compile.compileValid
        previewValid = [bool] $preview.valid
        applyPersisted = [bool] $apply.applied
        applyCleanupDeleted = [bool] $apply.cleanupDeleted
        streamTerminalSeen = [bool] $stream.terminalSeen
        streamReplayChecked = [bool] $stream.replayChecked
        streamArtifactsDir = $stream.artifactsDir
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
