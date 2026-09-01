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
    $scenarioEvidence = [ordered]@{
        schemaVersion = "praxis.page-builder-agentic-scenario-receipt/v1"
        scenarioId = "live-resource-workspace-command"
        archetype = "master-detail-command"
        outcome = "first-pass"
        firstPassFunctional = $true
        authoringFirstPass = $true
        playwrightRetryAttempts = 0
        interaction = [ordered]@{
            initialPromptCount = 1
            totalTurnCount = 1
            clarificationQuickReplyCount = 0
            governedRevisionCount = 0
            correctiveTypedPromptCount = 0
            deterministicRepairCount = 0
        }
        terminal = [ordered]@{
            outcome = "applicable"
            transport = "stream"
            blockingDiagnosticCodes = @()
            referencePresent = $true
            backendPatchAuthority = $true
        }
        apply = [ordered]@{
            terminalReferenceMatched = $true
            streamIdMatched = $true
            resultEventIdMatched = $true
            payloadSha256 = ("f" * 64)
            matchesPersistedPayload = $true
        }
        persistence = [ordered]@{
            version = 1
            etagPresent = $true
            persistedPayloadSha256 = ("f" * 64)
            reloadPayloadSha256 = ("f" * 64)
            reloadMatchesPersisted = $true
            reloadEtagMatches = $true
        }
        functionalAssertions = @('composition.master-visible', 'composition.detail-visible')
        timingMs = [ordered]@{
            firstUsefulStatus = 10
            firstApplicableTerminal = 20
            applyCompleted = 30
            runtimeFunctional = 40
            reloadCompleted = 50
            total = 50
        }
    }
    [ordered]@{
        schemaVersion = "praxis.page-builder-agentic-production-like-result/v1"
        productionLike = $true
        criticalEndpointMocks = 0
        criticalInterceptionGuard = [ordered]@{ passed = $true }
        executionLane = "live"
        validationMode = "smoke"
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
        matrix = [ordered]@{
            schemaVersion = "praxis.page-builder-agentic-gate-matrix/v1"
            scenarios = @("critical-interception-guard", "live-resource-workspace-command")
            retries = 0
            domainCatalogRagRequired = $false
            domainCatalogResourceKey = $null
            apiCatalogGroup = "human-resources"
            apiCatalogPathPrefixes = @()
            requiredPassedTests = @("critical guard", "live mission")
            receiptRequirements = @([ordered]@{
                scenarioId = "live-resource-workspace-command"
                archetype = "master-detail-command"
                requiredFunctionalAssertions = @('composition.master-visible', 'composition.detail-visible')
            })
            semanticRefinementRequirements = @()
            expectedDiscovered = 2
            minimumExecuted = 2
            expectedSkipped = 0
        }
        playwright = [ordered]@{
            discovered = 2
            executed = 2
            passed = 2
            skipped = 0
            failed = 0
            flaky = 0
            attempts = 2
            retryAttempts = 0
            durationMs = 100
            tests = @(
                [ordered]@{ title = "critical guard"; status = "expected"; attempts = 1; retryAttempts = 0 },
                [ordered]@{ title = "live mission"; status = "expected"; attempts = 1; retryAttempts = 0 }
            )
        }
        evidenceValidation = [ordered]@{
            passed = $true
            artifact = "evidence-validation-summary.json"
            attestation = [ordered]@{
                schemaVersion = "praxis.page-builder-agentic-gate-run-attestation/v1"
                reportSha256 = ("9" * 64)
                durationMs = 100
                discovered = 2
                passed = 2
                retries = 0
                receipts = @([ordered]@{
                    scenarioId = "live-resource-workspace-command"
                    firstPassFunctional = $true
                    totalMs = 50
                    persistedPayloadSha256 = ("f" * 64)
                })
                semanticRefinements = @()
            }
        }
        scenarioEvidence = @($scenarioEvidence)
        diagnosticEvidence = @()
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    @{ passed = $true } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $e2eRoot "source-audit.json") -Encoding utf8
    @{
        schemaVersion = "praxis.agentic-authoring-http-sse-summary/v1"
        health = "UP"
        executionLane = "live"
        provider = "openai"
        liveGateJourney = "governed-authoring-apply"
        previewValid = $true
        applyPersisted = $true
        applyCleanupDeleted = $true
        authoringStreamId = "stream-1"
        authoringResultEventId = "event-1"
    } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $httpRunRoot "summary.json") -Encoding utf8

    & $scriptPath -StarterRoot $starterRoot -PublicationProfile "page-builder-http-sse" -HttpArtifactRoot $httpArtifactRoot -OutputRoot $outputRoot | Out-Null
    $published = @(Get-ChildItem -LiteralPath $outputRoot -File | Select-Object -ExpandProperty Name | Sort-Object)
    if (($published -join ',') -ne 'http-sse-summary.json,production-like-result.json,source-audit.json') {
        throw "Exporter published an unexpected file set: $($published -join ',')"
    }

    $httpSummaryPath = Join-Path $httpRunRoot "summary.json"
    $httpSummaryFixture = Get-Content -LiteralPath $httpSummaryPath -Raw | ConvertFrom-Json
    $httpSummaryFixture.schemaVersion = "legacy-sse-probe/v0"
    $httpSummaryFixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $httpSummaryPath -Encoding utf8
    $legacyHttpOutput = Join-Path $root "legacy-http-published"
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -PublicationProfile "page-builder-http-sse" -HttpArtifactRoot $httpArtifactRoot -OutputRoot $legacyHttpOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Unexpected HTTP/SSE evidence schema"
    }
    if (-not $failedClosed) { throw "Exporter accepted the legacy SSE probe summary as governed authoring evidence." }
    $httpSummaryFixture.schemaVersion = "praxis.agentic-authoring-http-sse-summary/v1"
    $httpSummaryFixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $httpSummaryPath -Encoding utf8

    $pageBuilderOutput = Join-Path $root "page-builder-published"
    $pageBuilderPublication = & $scriptPath `
        -StarterRoot $starterRoot `
        -PublicationProfile "page-builder" `
        -OutputRoot $pageBuilderOutput | ConvertFrom-Json
    if ($pageBuilderPublication.publicationProfile -ne "page-builder") {
        throw "Exporter did not report the selected page-builder publication profile."
    }
    $pageBuilderPublished = @(Get-ChildItem -LiteralPath $pageBuilderOutput -File | Select-Object -ExpandProperty Name | Sort-Object)
    if (($pageBuilderPublished -join ',') -ne 'production-like-result.json,source-audit.json') {
        throw "Page Builder profile published an unexpected file set: $($pageBuilderPublished -join ',')"
    }

    $missingHttpOutput = Join-Path $root "missing-http-published"
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -PublicationProfile "page-builder-http-sse" -OutputRoot $missingHttpOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "HttpArtifactRoot is required"
    }
    if (-not $failedClosed) { throw "Exporter did not require HTTP/SSE evidence for the combined publication profile." }

    $unexpectedHttpOutput = Join-Path $root "unexpected-http-published"
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -PublicationProfile "page-builder" -HttpArtifactRoot $httpArtifactRoot -OutputRoot $unexpectedHttpOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "HttpArtifactRoot is not valid"
    }
    if (-not $failedClosed) { throw "Exporter silently mixed HTTP/SSE evidence into the Page Builder profile." }

    $diagnosticOutput = Join-Path $root "diagnostic-published"
    $diagnosticResult = Get-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Raw | ConvertFrom-Json
    $diagnosticResult.diagnosticEvidence = @([ordered]@{
        scenarioId = "human-refinement-pr7"
        testTitle = "diagnostic fixture"
        attachmentName = "pr7-governed-state-projection.json"
        projection = [ordered]@{}
    })
    $diagnosticResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $diagnosticOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Successful publication cannot retain failure diagnostic projections"
    }
    if (-not $failedClosed) { throw "Exporter did not reject failure diagnostics from a successful publication." }
    $diagnosticResult.diagnosticEvidence = @()
    $diagnosticResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8

    $unsafeOutput = Join-Path $root "unsafe-scenario-published"
    $unsafeResult = Get-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Raw | ConvertFrom-Json
    $unsafeResult.scenarioEvidence[0] | Add-Member -NotePropertyName prompt -NotePropertyValue "must never be published"
    $unsafeResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $unsafeOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Scenario evidence has unexpected properties"
    }
    if (-not $failedClosed) { throw "Exporter did not reject unsafe scenario evidence." }
    $unsafeResult.scenarioEvidence[0].PSObject.Properties.Remove("prompt")
    $unsafeResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8

    $forgedScenarioOutput = Join-Path $root "forged-scenario-published"
    $forgedResult = Get-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Raw | ConvertFrom-Json
    $forgedResult.scenarioEvidence[0].apply.payloadSha256 = ("a" * 64)
    $forgedResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $forgedScenarioOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Scenario persistence evidence is incomplete or inconsistent"
    }
    if (-not $failedClosed) { throw "Exporter did not reject a forged apply/persistence hash relation." }
    $forgedResult.scenarioEvidence[0].apply.payloadSha256 = ("f" * 64)
    $forgedResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8

    $missingAssertionOutput = Join-Path $root "missing-assertion-published"
    $missingAssertionResult = Get-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Raw | ConvertFrom-Json
    $missingAssertionResult.scenarioEvidence[0].functionalAssertions = @('composition.master-visible')
    $missingAssertionResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8
    $failedClosed = $false
    try {
        & $scriptPath -StarterRoot $starterRoot -HttpArtifactRoot $httpArtifactRoot -OutputRoot $missingAssertionOutput | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match "Scenario functional assertions diverge from the published matrix requirement"
    }
    if (-not $failedClosed) { throw "Exporter did not reject a missing matrix-owned functional assertion." }
    $missingAssertionResult.scenarioEvidence[0].functionalAssertions = @(
        'composition.master-visible',
        'composition.detail-visible'
    )
    $missingAssertionResult | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $e2eRoot "result.json") -Encoding utf8

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
