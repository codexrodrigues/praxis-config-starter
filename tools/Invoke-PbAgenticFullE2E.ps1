param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $QuickstartRoot = "",
    [string] $MetadataRoot = "",
    [string] $UiRoot = "",
    [string] $JarPath = "",
    [string] $ReferenceStarterJarPath = "",
    [ValidateSet("source-checkout", "maven-central")]
    [string] $ConfigArtifactSource = "source-checkout",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $EmbeddingProvider = "",
    [string] $ExpectedConfigVersion = "",
    [string] $ExpectedMetadataVersion = "",
    [int] $BackendPort = 8088,
    [int] $UiPort = 4003,
    [int] $StartupTimeoutSec = 180,
    [int] $UiStartupTimeoutSec = 600,
    [int] $StreamProcessingTimeoutSeconds = 0,
    [int] $ApiCatalogIndexingTimeoutSec = 900,
    [string] $ValidationMode = "smoke",
    [int] $PlaywrightTestTimeoutMs = 0,
    [int] $Retries = -1,
    [switch] $ConfirmPaidProviderRun,
    [switch] $ValidateEvidenceParsersOnly
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e\PageBuilderScenarioReceipt.ps1")

function Write-Phase([string] $Message) {
    $timestamp = Get-Date -Format "yyyy-MM-ddTHH:mm:ssK"
    Write-Host "[$timestamp] [page-builder-e2e] $Message"
}

function Get-ListenPid([int] $Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $conn) { return $null }
    return [int] $conn.OwningProcess
}

function Get-ListenConnections([int] $Port) {
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Assert-LoopbackListener([int] $Port, [string] $Name) {
    $connections = Get-ListenConnections $Port
    if ($connections.Count -eq 0) { throw "$Name has no listener on port $Port." }
    $nonLoopback = @($connections | Where-Object { $_.LocalAddress -notin @("127.0.0.1", "::1") })
    if ($nonLoopback.Count -gt 0) {
        $addresses = ($nonLoopback | ForEach-Object { $_.LocalAddress } | Sort-Object -Unique) -join ","
        throw "$Name must listen only on loopback. port=$Port unexpectedAddresses=$addresses"
    }
}

function Assert-PortReleased([int] $Port, [string] $Name) {
    $deadline = (Get-Date).AddSeconds(15)
    do {
        if ((Get-ListenConnections $Port).Count -eq 0) { return }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$Name left a listener on port $Port after cleanup."
}

function Assert-RequiredValue([string] $Name, [string] $Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "Missing required production-like setting: $Name" }
}

function Assert-PostgresUrl([string] $Name, [string] $Value) {
    Assert-RequiredValue $Name $Value
    if (-not $Value.StartsWith("jdbc:postgresql://", [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name must use jdbc:postgresql for the production-like gate."
    }
}

function Get-GitIdentity([string] $Root, [string] $Name, [string] $Materialization = "working-tree") {
    $sha = (& git -C $Root rev-parse HEAD 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch "^[0-9a-f]{40}$") {
        throw "Cannot resolve immutable Git SHA for $Name at $Root."
    }
    $changes = @(& git -C $Root status --porcelain 2>$null)
    $dirty = $changes.Count -gt 0
    if ($dirty -and $Materialization -eq "working-tree") {
        $paths = ($changes | ForEach-Object { $_.Substring([Math]::Min(3, $_.Length)) }) -join ", "
        throw "$Name checkout must be clean so its immutable SHA fully identifies the exercised source. changedPaths=$paths"
    }
    $treeSha = (& git -C $Root rev-parse 'HEAD^{tree}' 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $treeSha -notmatch "^[0-9a-f]{40}$") {
        throw "Cannot resolve immutable Git tree SHA for $Name at $Root."
    }
    return [ordered]@{
        name = $Name
        sha = $sha
        treeSha = $treeSha
        materialization = $Materialization
        dirty = $dirty
    }
}

function New-EphemeralRuntimeSecret {
    param([int] $ByteCount = 48)
    if ($ByteCount -lt 32) { throw "Ephemeral runtime secrets require at least 32 random bytes." }
    $bytes = New-Object byte[] $ByteCount
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function New-EphemeralRuntimeSecrets {
    $streamSecret = New-EphemeralRuntimeSecret
    do {
        $resourceVersionEtagSecret = New-EphemeralRuntimeSecret
    } while ($resourceVersionEtagSecret -eq $streamSecret)
    return [ordered]@{
        streamAuthTokenSecret = $streamSecret
        resourceVersionEtagSecret = $resourceVersionEtagSecret
    }
}

function Assert-EphemeralRuntimeSecretFixture {
    $secrets = New-EphemeralRuntimeSecrets
    $streamBytes = [Convert]::FromBase64String([string] $secrets.streamAuthTokenSecret)
    $resourceBytes = [Convert]::FromBase64String([string] $secrets.resourceVersionEtagSecret)
    if ($streamBytes.Length -lt 32 -or $resourceBytes.Length -lt 32) {
        throw "Ephemeral runtime secret fixture produced fewer than 32 random bytes."
    }
    if ($secrets.streamAuthTokenSecret -eq $secrets.resourceVersionEtagSecret) {
        throw "Stream auth and resource version ETag secrets must be independently generated."
    }
    $safeEvidence = [ordered]@{
        streamAuthTokenSecretPresent = -not [string]::IsNullOrWhiteSpace([string] $secrets.streamAuthTokenSecret)
        resourceVersionEtagSecretPresent = -not [string]::IsNullOrWhiteSpace([string] $secrets.resourceVersionEtagSecret)
        independent = $true
    } | ConvertTo-Json -Compress
    if ($safeEvidence.Contains([string] $secrets.streamAuthTokenSecret) -or
        $safeEvidence.Contains([string] $secrets.resourceVersionEtagSecret)) {
        throw "Sanitized runtime secret evidence leaked secret material."
    }
}

function Assert-EmptyDiagnosticEvidenceSerializationFixture {
    $publishedDiagnosticEvidence = @()
    $fixture = [pscustomobject]@{
        productionLike = $true
        diagnosticEvidence = @($publishedDiagnosticEvidence)
    } | ConvertTo-Json -Depth 4 | ConvertFrom-Json
    if ($null -eq $fixture.diagnosticEvidence -or @($fixture.diagnosticEvidence).Count -ne 0) {
        throw "Successful production-like evidence must serialize diagnosticEvidence as an empty JSON array."
    }
}

function Get-QuickstartDependencyEvidence([string] $Path, [string] $ArtifactId) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $escapedArtifactId = [regex]::Escape($ArtifactId)
        $starterEntry = $outer.Entries |
            Where-Object { $_.FullName -match "^BOOT-INF/lib/$escapedArtifactId-[^/]+\.jar$" } |
            Select-Object -First 1
        if ($null -eq $starterEntry) { throw "Quickstart jar does not contain $ArtifactId under BOOT-INF/lib." }
        $versionMatch = [regex]::Match($starterEntry.Name, "^$escapedArtifactId-(?<version>.+)\.jar$")
        if (-not $versionMatch.Success) { throw "Cannot resolve $ArtifactId version from Quickstart jar." }
        $entryStream = $starterEntry.Open()
        $sha256 = [Security.Cryptography.SHA256]::Create()
        try {
            $hashBytes = $sha256.ComputeHash($entryStream)
            $hash = ([BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()
        } finally {
            $sha256.Dispose()
            $entryStream.Dispose()
        }
        return [ordered]@{
            artifactId = $ArtifactId
            version = $versionMatch.Groups['version'].Value.Trim()
            entry = $starterEntry.FullName
            sha256 = $hash
        }
    } finally { $outer.Dispose() }
}

function Get-PlaywrightSummary([string] $ReportPath) {
    if (-not (Test-Path -LiteralPath $ReportPath)) { throw "Playwright JSON report was not generated: $ReportPath" }
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    return Get-PlaywrightSummaryFromReport $report
}

function Get-PlaywrightSummaryFromReport([object] $Report) {
    $report = $Report
    $stats = $report.stats
    if ($null -eq $stats) { throw "Playwright JSON report does not contain stats." }
    $expected = [int] $stats.expected
    $skipped = [int] $stats.skipped
    $unexpected = [int] $stats.unexpected
    $flaky = [int] $stats.flaky
    $specs = @(Get-PlaywrightSpecs @($report.suites))
    $tests = @($specs | ForEach-Object {
        $spec = $_
        foreach ($testCase in @($spec.tests)) {
            $results = @($testCase.results)
            [ordered]@{
                title = [string] $spec.title
                status = [string] $testCase.status
                attempts = $results.Count
                retryAttempts = @($results | Where-Object { [int] $_.retry -gt 0 }).Count
            }
        }
    })
    $attempts = 0
    $retryAttempts = 0
    foreach ($testResult in $tests) {
        $attempts += [int] $testResult.attempts
        $retryAttempts += [int] $testResult.retryAttempts
    }
    return [ordered]@{
        discovered = $specs.Count
        executed = $expected + $unexpected + $flaky
        passed = $expected + $flaky
        skipped = $skipped
        failed = $unexpected
        flaky = $flaky
        attempts = $attempts
        retryAttempts = $retryAttempts
        tests = $tests
        durationMs = [int64] $stats.duration
    }
}

function Get-PlaywrightSpecs([object[]] $Suites) {
    $pending = [System.Collections.Queue]::new()
    foreach ($suite in @($Suites)) {
        if ($null -ne $suite) { $pending.Enqueue($suite) }
    }
    while ($pending.Count -gt 0) {
        $suite = $pending.Dequeue()
        foreach ($spec in @($suite.specs)) { $spec }
        foreach ($childSuite in @($suite.suites)) {
            if ($null -ne $childSuite) { $pending.Enqueue($childSuite) }
        }
    }
}

function Get-PlaywrightScenarioEvidenceFromReport(
    [object] $Report,
    [object[]] $Definitions,
    [string[]] $SelectedScenarioIds,
    [switch] $AllowPartial
) {
    $evidence = @()
    $specs = @(Get-PlaywrightSpecs @($Report.suites))
    foreach ($definition in @($Definitions | Where-Object { $_.scenarioId -in $SelectedScenarioIds })) {
        try {
            $matchingSpecs = @($specs | Where-Object { $_.title -eq $definition.testTitle })
            if ($matchingSpecs.Count -ne 1) {
                throw "Scenario receipt test title must resolve exactly once: $($definition.testTitle)"
            }
            $testCases = if ($AllowPartial) {
                @($matchingSpecs[0].tests)
            } else {
                @($matchingSpecs[0].tests | Where-Object { $_.status -eq 'expected' })
            }
            if ($testCases.Count -ne 1) {
                throw "Scenario receipt test must pass exactly once: $($definition.testTitle)"
            }
            $results = @($testCases[0].results)
            $passedResults = @($results | Where-Object { $_.status -eq 'passed' })
            if (-not $AllowPartial -and $passedResults.Count -ne 1) {
                throw "Scenario receipt requires exactly one successful Playwright result: $($definition.testTitle)"
            }
            $receiptResults = if ($AllowPartial) { $results } else { $passedResults }
            $attachments = @($receiptResults | ForEach-Object {
                @($_.attachments | Where-Object { $_.name -eq $definition.attachmentName })
            })
            if ($attachments.Count -ne 1 -or $attachments[0].contentType -ne 'application/json' -or
                [string]::IsNullOrWhiteSpace([string] $attachments[0].body)) {
                throw "Scenario receipt attachment must be one inline application/json body: $($definition.attachmentName)"
            }
            try {
                $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string] $attachments[0].body))
                $receipt = $json | ConvertFrom-Json
            } catch {
                throw "Scenario receipt attachment is not valid base64 JSON: $($definition.attachmentName)"
            }
            $retryAttempts = @($results | Where-Object { [int] $_.retry -gt 0 }).Count
            $evidence += ConvertTo-PraxisPageBuilderScenarioEvidence $receipt $definition $retryAttempts
        } catch {
            if (-not $AllowPartial) { throw }
            Write-Warning "Scenario evidence was not collected from the failed Playwright run. scenarioId=$($definition.scenarioId) reason=$($_.Exception.Message)"
        }
    }
    return @($evidence)
}

function Get-PlaywrightScenarioEvidence(
    [string] $ReportPath,
    [object[]] $Definitions,
    [string[]] $SelectedScenarioIds,
    [switch] $AllowPartial
) {
    if (-not (Test-Path -LiteralPath $ReportPath)) { throw "Playwright JSON report was not generated: $ReportPath" }
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    return @(Get-PlaywrightScenarioEvidenceFromReport $report $Definitions $SelectedScenarioIds -AllowPartial:$AllowPartial)
}

function Get-PlaywrightGovernedStateProjectionsFromReport(
    [object] $Report,
    [object[]] $Definitions,
    [string[]] $SelectedScenarioIds,
    [switch] $AllowPartial
) {
    $projections = @()
    $specs = @(Get-PlaywrightSpecs @($Report.suites))
    foreach ($definition in @($Definitions | Where-Object { $_.scenarioId -in $SelectedScenarioIds })) {
        try {
            $matchingSpecs = @($specs | Where-Object { $_.title -eq $definition.testTitle })
            if ($matchingSpecs.Count -ne 1) {
                throw "Governed state projection test title must resolve exactly once: $($definition.testTitle)"
            }
            $testCases = if ($AllowPartial) {
                @($matchingSpecs[0].tests)
            } else {
                @($matchingSpecs[0].tests | Where-Object { $_.status -eq 'expected' })
            }
            if ($testCases.Count -ne 1) {
                throw "Governed state projection test must resolve exactly once: $($definition.testTitle)"
            }
            $results = @($testCases[0].results)
            $projectionResults = if ($AllowPartial) {
                $results
            } else {
                @($results | Where-Object { $_.status -eq 'passed' })
            }
            $attachments = @($projectionResults | ForEach-Object {
                @($_.attachments | Where-Object { $_.name -eq $definition.attachmentName })
            })
            if ($attachments.Count -ne 1 -or $attachments[0].contentType -ne 'application/json' -or
                [string]::IsNullOrWhiteSpace([string] $attachments[0].body)) {
                throw "Governed state projection must be one inline application/json attachment: $($definition.attachmentName)"
            }
            try {
                $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string] $attachments[0].body))
                $projection = $json | ConvertFrom-Json
            } catch {
                throw "Governed state projection attachment is not valid base64 JSON: $($definition.attachmentName)"
            }
            $projections += ConvertTo-PraxisGovernedStateProjection $projection $definition
        } catch {
            throw
        }
    }
    return @($projections)
}

function Get-PlaywrightGovernedStateProjections(
    [string] $ReportPath,
    [object[]] $Definitions,
    [string[]] $SelectedScenarioIds,
    [switch] $AllowPartial
) {
    if (-not (Test-Path -LiteralPath $ReportPath)) {
        throw "Playwright JSON report was not generated: $ReportPath"
    }
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    return @(Get-PlaywrightGovernedStateProjectionsFromReport `
        $report $Definitions $SelectedScenarioIds -AllowPartial:$AllowPartial)
}

function Assert-PlaywrightSummaryParserFixture {
    $fixture = [pscustomobject]@{
        stats = [pscustomobject]@{
            expected = 1
            skipped = 1
            unexpected = 0
            flaky = 1
            duration = 123
        }
        suites = @(
            [pscustomobject]@{
                specs = @(
                    [pscustomobject]@{
                        title = "expected"
                        tests = @([pscustomobject]@{
                            status = "expected"
                            results = @([pscustomobject]@{ retry = 0 })
                        })
                    }
                )
                suites = @(
                    [pscustomobject]@{
                        specs = @(
                            [pscustomobject]@{
                                title = "flaky"
                                tests = @([pscustomobject]@{
                                    status = "flaky"
                                    results = @(
                                        [pscustomobject]@{ retry = 0 },
                                        [pscustomobject]@{ retry = 1 }
                                    )
                                })
                            },
                            [pscustomobject]@{
                                title = "skipped"
                                tests = @([pscustomobject]@{ status = "skipped"; results = @() })
                            }
                        )
                        suites = $null
                    }
                )
            }
        )
    }
    $summary = Get-PlaywrightSummaryFromReport $fixture
    if ($summary.discovered -ne 3 -or $summary.executed -ne 2 -or $summary.passed -ne 2 -or
        $summary.skipped -ne 1 -or $summary.failed -ne 0 -or $summary.flaky -ne 1 -or
        $summary.attempts -ne 3 -or $summary.retryAttempts -ne 1) {
        throw "Playwright summary parser fixture diverged from the expected nested/flaky result."
    }
}

function Assert-PlaywrightScenarioReceiptParserFixture {
    $definition = [pscustomobject]@{
        scenarioId = 'live-resource-workspace-command'
        archetype = 'master-detail-command'
        testTitle = 'mission workspace'
        attachmentName = 'mission-workspace-first-pass-receipt.json'
        requiredFunctionalAssertions = @(
            'composition.master-visible',
            'composition.detail-visible'
        )
    }
    $receipt = [ordered]@{
        schemaVersion = 'praxis.page-builder-agentic-scenario-receipt/v1'
        scenarioId = 'live-resource-workspace-command'
        archetype = 'master-detail-command'
        authoringFirstPass = $true
        interaction = [ordered]@{
            initialPromptCount = 1
            totalTurnCount = 1
            clarificationQuickReplyCount = 0
            governedRevisionCount = 0
            correctiveTypedPromptCount = 0
            deterministicRepairCount = 0
        }
        terminal = [ordered]@{
            outcome = 'applicable'
            transport = 'stream'
            blockingDiagnosticCodes = @()
            referencePresent = $true
            backendPatchAuthority = $true
        }
        apply = [ordered]@{
            terminalReferenceMatched = $true
            streamIdMatched = $true
            resultEventIdMatched = $true
            payloadSha256 = ('a' * 64)
            matchesPersistedPayload = $true
        }
        persistence = [ordered]@{
            version = 1
            etagPresent = $true
            persistedPayloadSha256 = ('a' * 64)
            reloadPayloadSha256 = ('a' * 64)
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
    $encodedReceipt = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($receipt | ConvertTo-Json -Depth 10)))
    $fixture = [pscustomobject]@{
        suites = @([pscustomobject]@{
            specs = @([pscustomobject]@{
                title = 'mission workspace'
                tests = @([pscustomobject]@{
                    status = 'expected'
                    results = @([pscustomobject]@{
                        status = 'passed'
                        retry = 0
                        attachments = @([pscustomobject]@{
                            name = 'mission-workspace-first-pass-receipt.json'
                            contentType = 'application/json'
                            body = $encodedReceipt
                        })
                    })
                })
            })
            suites = @()
        })
    }
    $parsed = @(Get-PlaywrightScenarioEvidenceFromReport $fixture @($definition) @('live-resource-workspace-command'))
    if ($parsed.Count -ne 1 -or $parsed[0].firstPassFunctional -ne $true -or $parsed[0].outcome -ne 'first-pass') {
        throw 'Playwright scenario receipt parser did not materialize first-pass evidence.'
    }

    $receipt['authoringFirstPass'] = $false
    $receipt['interaction']['totalTurnCount'] = 2
    $receipt['interaction']['clarificationQuickReplyCount'] = 1
    $fixture.suites[0].specs[0].tests[0].results[0].attachments[0].body = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($receipt | ConvertTo-Json -Depth 10))
    )
    $eventual = @(Get-PlaywrightScenarioEvidenceFromReport $fixture @($definition) @('live-resource-workspace-command'))
    if ($eventual.Count -ne 1 -or $eventual[0].firstPassFunctional -ne $false -or $eventual[0].outcome -ne 'eventual-pass') {
        throw 'Playwright scenario receipt parser did not preserve an eventual-pass authoring result.'
    }

    $receipt['authoringFirstPass'] = $true
    $receipt['interaction']['totalTurnCount'] = 1
    $receipt['interaction']['clarificationQuickReplyCount'] = 0
    $fixture.suites[0].specs[0].tests[0].results[0].retry = 1
    $fixture.suites[0].specs[0].tests[0].results[0].attachments[0].body = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($receipt | ConvertTo-Json -Depth 10))
    )
    $retried = @(Get-PlaywrightScenarioEvidenceFromReport $fixture @($definition) @('live-resource-workspace-command'))
    if ($retried.Count -ne 1 -or $retried[0].firstPassFunctional -ne $false -or $retried[0].outcome -ne 'eventual-pass') {
        throw 'Playwright scenario receipt parser did not downgrade a retried result to eventual-pass.'
    }

    $fixture.suites[0].specs[0].tests[0].results[0].retry = 0
    $tableReceipt = ($receipt | ConvertTo-Json -Depth 10 | ConvertFrom-Json)
    $tableReceipt.scenarioId = 'single-table-control'
    $tableReceipt.archetype = 'single-table-control'
    $tableReceipt.functionalAssertions = @('composition.single-table-only')
    $encodedTableReceipt = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($tableReceipt | ConvertTo-Json -Depth 10))
    )
    $tableDefinition = [pscustomobject]@{
        scenarioId = 'single-table-control'
        archetype = 'single-table-control'
        testTitle = 'single-table control'
        attachmentName = 'single-table-first-pass-receipt.json'
        requiredFunctionalAssertions = @('composition.single-table-only')
    }
    $fixture.suites[0].specs += [pscustomobject]@{
        title = 'single-table control'
        tests = @([pscustomobject]@{
            status = 'expected'
            results = @([pscustomobject]@{
                status = 'passed'
                retry = 0
                attachments = @([pscustomobject]@{
                    name = 'single-table-first-pass-receipt.json'
                    contentType = 'application/json'
                    body = $encodedTableReceipt
                })
            })
        })
    }
    $fixture.suites[0].specs += [pscustomobject]@{
        title = 'failed table refinement'
        tests = @([pscustomobject]@{
            status = 'unexpected'
            results = @([pscustomobject]@{
                status = 'failed'
                retry = 0
                attachments = @()
            })
        })
    }
    $partial = @(Get-PlaywrightScenarioEvidenceFromReport `
        $fixture `
        @($definition, $tableDefinition) `
        @('live-resource-workspace-command', 'single-table-control', 'table-human-refinement') `
        -AllowPartial)
    if ($partial.Count -ne 2 -or
        $partial[0].scenarioId -ne 'live-resource-workspace-command' -or
        $partial[1].scenarioId -ne 'single-table-control' -or
        $partial[1].outcome -ne 'first-pass') {
        throw 'Playwright scenario receipt parser did not preserve the control receipt when refinement failed.'
    }

    $failedReceipt = ($tableReceipt | ConvertTo-Json -Depth 10 | ConvertFrom-Json)
    $failedReceipt.scenarioId = 'failed-control-receipt'
    $encodedFailedReceipt = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($failedReceipt | ConvertTo-Json -Depth 10))
    )
    $failedDefinition = [pscustomobject]@{
        scenarioId = 'failed-control-receipt'
        archetype = 'single-table-control'
        testTitle = 'failed control after receipt'
        attachmentName = 'single-table-first-pass-receipt.json'
        requiredFunctionalAssertions = @('composition.single-table-only')
    }
    $fixture.suites[0].specs += [pscustomobject]@{
        title = 'failed control after receipt'
        tests = @([pscustomobject]@{
            status = 'unexpected'
            results = @(
                [pscustomobject]@{
                    status = 'failed'
                    retry = 0
                    attachments = @()
                },
                [pscustomobject]@{
                    status = 'failed'
                    retry = 1
                    attachments = @([pscustomobject]@{
                        name = 'single-table-first-pass-receipt.json'
                        contentType = 'application/json'
                        body = $encodedFailedReceipt
                    })
                }
            )
        })
    }
    $failedButRecoverable = @(Get-PlaywrightScenarioEvidenceFromReport `
        $fixture `
        @($failedDefinition) `
        @('failed-control-receipt') `
        -AllowPartial)
    if ($failedButRecoverable.Count -ne 1 -or
        $failedButRecoverable[0].scenarioId -ne 'failed-control-receipt' -or
        $failedButRecoverable[0].outcome -ne 'eventual-pass' -or
        $failedButRecoverable[0].firstPassFunctional -ne $false) {
        throw 'Playwright scenario receipt parser did not preserve valid/retried evidence from a failed test result.'
    }

    $receipt['prompt'] = 'must never be published'
    $fixture.suites[0].specs[0].tests[0].results[0].attachments[0].body = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($receipt | ConvertTo-Json -Depth 10))
    )
    $failedClosed = $false
    try {
        Get-PlaywrightScenarioEvidenceFromReport $fixture @($definition) @('live-resource-workspace-command') | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match 'unexpected properties'
    }
    if (-not $failedClosed) { throw 'Playwright scenario receipt parser did not reject an unsafe extra property.' }
}

function Assert-PlaywrightGovernedStateProjectionParserFixture {
    $definition = [pscustomobject]@{
        scenarioId = 'human-refinement-pr7'
        testTitle = 'PR7 projection fixture'
        attachmentName = 'pr7-governed-state-projection.json'
    }
    $projection = [ordered]@{
        schemaVersion = 'praxis.page-builder.governed-state-projection/v1'
        scenarioId = 'human-refinement-pr7'
        observedDisposition = [ordered]@{
            testObservedState = 'review'
            controllerState = 'review'
            domState = 'table-visible'
        }
        decisionDiagnostics = [ordered]@{
            status = 'review'
            reason = $null
            decisionValid = $true
            requiresReview = $true
        }
        preview = [ordered]@{ present = $true; valid = $true }
        applyEligibility = [ordered]@{ controllerCanApply = $true; persistEnabled = $true }
        blockingDiagnosticCodes = @()
        quickReplyIds = @('review-apply')
        governedRepairActionIds = @()
        canonicalActionPresent = $true
        canonicalActions = @([ordered]@{
            replyId = 'review-apply'
            source = 'reply.contextHints.canonicalAction'
            canonicalAction = $null
            canonicalActionToken = 'apply.reviewed.preview'
        })
        applyLineage = [ordered]@{
            status = 'verified'
            reason = $null
            patchAuthority = 'backend-compiled'
            terminalReferencePresent = $true
        }
        execution = [ordered]@{ turnCount = 1; attemptCount = 1; retryCount = 0 }
    }
    $encoded = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($projection | ConvertTo-Json -Depth 10))
    )
    $fixture = [pscustomobject]@{
        suites = @([pscustomobject]@{
            specs = @([pscustomobject]@{
                title = 'PR7 projection fixture'
                tests = @([pscustomobject]@{
                    status = 'expected'
                    results = @([pscustomobject]@{
                        status = 'passed'
                        retry = 0
                        attachments = @([pscustomobject]@{
                            name = 'pr7-governed-state-projection.json'
                            contentType = 'application/json'
                            body = $encoded
                        })
                    })
                })
            })
            suites = @()
        })
    }
    $parsed = @(Get-PlaywrightGovernedStateProjectionsFromReport `
        $fixture @($definition) @('human-refinement-pr7'))
    if ($parsed.Count -ne 1 -or
        $parsed[0].scenarioId -ne 'human-refinement-pr7' -or
        $parsed[0].projection.canonicalActions.Count -ne 1) {
        throw 'Playwright governed state projection parser did not preserve the sanitized projection.'
    }

    $projection['prompt'] = 'must never be collected'
    $fixture.suites[0].specs[0].tests[0].results[0].attachments[0].body = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes(($projection | ConvertTo-Json -Depth 10))
    )
    $failedClosed = $false
    try {
        Get-PlaywrightGovernedStateProjectionsFromReport `
            $fixture @($definition) @('human-refinement-pr7') | Out-Null
    } catch {
        $failedClosed = $_.Exception.Message -match 'unexpected properties'
    }
    if (-not $failedClosed) {
        throw 'Playwright governed state projection parser did not reject an unsafe extra property.'
    }
}

function Invoke-PgvectorPreflight(
    [string] $QuickstartPath,
    [string] $JavaPath,
    [string] $SourcePath,
    [string] $OutputRoot
) {
    $classpathFile = Join-Path $QuickstartPath "target\runtime-classpath.txt"
    Push-Location $QuickstartPath
    try {
        & mvn -q dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" "-DincludeScope=runtime"
        if ($LASTEXITCODE -ne 0) { throw "Cannot build Quickstart runtime classpath for pgvector preflight." }
    } finally {
        Pop-Location
    }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    Assert-RequiredValue "Quickstart runtime classpath" $classpath
    $classesRoot = Join-Path $OutputRoot "pgvector-preflight-classes"
    New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null
    $argClasspath = $classpath.Replace('\', '/')
    $argClassesRoot = $classesRoot.Replace('\', '/')
    $argSourcePath = $SourcePath.Replace('\', '/')
    $javacArgsFile = Join-Path $OutputRoot "pgvector-preflight-javac.args"
    @(
        '-cp'
        "`"$argClasspath`""
        '-d'
        "`"$argClassesRoot`""
        "`"$argSourcePath`""
    ) | Set-Content -LiteralPath $javacArgsFile -Encoding ascii
    $javacOutput = [IO.Path]::GetTempFileName()
    $javacError = [IO.Path]::GetTempFileName()
    try {
        $javacProcess = Start-Process `
            -FilePath (Join-Path $JavaPath "bin\javac.exe") `
            -ArgumentList "@$javacArgsFile" `
            -RedirectStandardOutput $javacOutput `
            -RedirectStandardError $javacError `
            -Wait `
            -PassThru `
            -WindowStyle Hidden
        if ($javacProcess.ExitCode -ne 0) { throw "Cannot compile PgvectorPreflight.java." }
    } finally {
        Remove-Item -LiteralPath $javacOutput, $javacError -Force -ErrorAction SilentlyContinue
    }
    $runtimeClasspath = "$classesRoot$([IO.Path]::PathSeparator)$classpath"
    $argRuntimeClasspath = $runtimeClasspath.Replace('\', '/')
    $javaExecutable = Join-Path $JavaPath "bin\java.exe"
    $javaArgsFile = Join-Path $OutputRoot "pgvector-preflight-java.args"
    @(
        '-cp'
        "`"$argRuntimeClasspath`""
        'PgvectorPreflight'
    ) | Set-Content -LiteralPath $javaArgsFile -Encoding ascii
    $javaOutput = [IO.Path]::GetTempFileName()
    $javaError = [IO.Path]::GetTempFileName()
    try {
        $javaProcess = Start-Process `
            -FilePath $javaExecutable `
            -ArgumentList "@$javaArgsFile" `
            -RedirectStandardOutput $javaOutput `
            -RedirectStandardError $javaError `
            -Wait `
            -PassThru `
            -WindowStyle Hidden
        if ($javaProcess.ExitCode -ne 0) {
            throw "Pgvector preflight failed without exposing database connection details."
        }
        $output = (Get-Content -LiteralPath $javaOutput -Raw).Trim()
    } finally {
        Remove-Item -LiteralPath $javaOutput, $javaError -Force -ErrorAction SilentlyContinue
    }
    $result = $output | ConvertFrom-Json
    if ($result.ready -ne $true -or $result.table -ne "vector_store") {
        throw "Pgvector preflight did not produce governed readiness evidence."
    }
    return $result
}

function Wait-Url([string] $Url, [int] $TimeoutSec, [string] $Name) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become reachable before timeout: $Url"
}

function Wait-AiRegistryReady(
    [string] $BaseUrl,
    [string] $Origin,
    [string] $ExpectedSnapshotHash,
    [int] $TimeoutSec
) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $health = Invoke-RestMethod `
                -Method Get `
                -Uri "$BaseUrl/api/praxis/config/ai-registry/health" `
                -Headers @{
                    "Origin" = $Origin
                    "X-Tenant-ID" = "desenv"
                    "X-User-ID" = "demo"
                    "X-Env" = "local"
                } `
                -TimeoutSec 10
            $bootstrap = $health.bootstrap
            $completed = -not [string]::IsNullOrWhiteSpace([string] $bootstrap.completedAt)
            $acceptedOutcome = $bootstrap.succeeded -eq $true -or (
                $bootstrap.skipped -eq $true -and $bootstrap.skipReason -eq "snapshot-current"
            )
            if ($health.ready -eq $true -and $completed -and $acceptedOutcome -and
                $bootstrap.snapshotHash -eq $ExpectedSnapshotHash) {
                return [ordered]@{
                    ready = $true
                    snapshotHash = $ExpectedSnapshotHash
                    bootstrapOutcome = if ($bootstrap.succeeded -eq $true) { "succeeded" } else { "snapshot-current" }
                }
            }
            if ($completed -and -not [string]::IsNullOrWhiteSpace([string] $bootstrap.error)) {
                throw "AI Registry bootstrap completed with a sanitized failure."
            }
        } catch {
            if ($_.Exception.Message -eq "AI Registry bootstrap completed with a sanitized failure.") { throw }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "AI Registry did not become ready with the expected immutable snapshot hash."
}

function Wait-DomainCatalogRagReady(
    [string] $BaseUrl,
    [string] $Origin,
    [string] $TenantId,
    [string] $Environment,
    [string] $ServiceKey,
    [string] $ResourceKey,
    [int] $TimeoutSec
) {
    $encodedServiceKey = [Uri]::EscapeDataString($ServiceKey)
    $statusUrl = "$BaseUrl/api/praxis/config/domain-catalog/rag/status?serviceKey=$encodedServiceKey"
    if (-not [string]::IsNullOrWhiteSpace($ResourceKey)) {
        $statusUrl += "&resourceKey=$([Uri]::EscapeDataString($ResourceKey))"
    }
    $headers = @{
        "Origin" = $Origin
        "X-Tenant-ID" = $TenantId
        "X-Env" = $Environment
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        try {
            $status = Invoke-RestMethod `
                -Method Get `
                -Uri $statusUrl `
                -Headers $headers `
                -TimeoutSec 10
            if ($status.schemaVersion -eq "praxis.domain-catalog-rag-status/v0.1" -and
                $null -ne $status.publication) {
                $publicationStatus = ([string] $status.publication.status).ToUpperInvariant()
                if ($publicationStatus -eq "FAILED") {
                    $failureKind = if ([string]::IsNullOrWhiteSpace([string] $status.publication.failureKind)) {
                        "unknown"
                    } else {
                        [string] $status.publication.failureKind
                    }
                    throw "Domain Catalog RAG publication failed with sanitized failure kind: $failureKind"
                }
                if ($publicationStatus -eq "PUBLISHED" -and
                    $status.statusAvailable -eq $true -and
                    $status.reconciled -eq $true -and
                    [long] $status.expectedDocumentCount -gt 0) {
                    return [ordered]@{
                        schemaVersion = [string] $status.schemaVersion
                        status = $publicationStatus
                        reconciled = $true
                        expectedDocumentCount = [long] $status.expectedDocumentCount
                        actualDocumentCount = [long] $status.actualDocumentCount
                        revision = [long] $status.publication.revision
                        attempt = [int] $status.publication.attempt
                    }
                }
            }
        } catch {
            if ($_.Exception.Message.StartsWith("Domain Catalog RAG publication failed with sanitized failure kind:")) {
                throw
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Domain Catalog RAG did not reach PUBLISHED + reconciled before timeout."
}

function Stop-ProcAndPort($Process, [int] $Port) {
    if ($null -eq $Process) {
        return
    }
    if (-not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
    $listenPid = Get-ListenPid $Port
    if ($null -ne $listenPid) {
        Stop-Process -Id $listenPid -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-DomainCatalogIngest {
    param(
        [string] $BaseUrl,
        [string] $Origin,
        [string] $TenantId,
        [string] $Environment,
        [string[]] $Groups,
        [string] $ResourceKey = "",
        [bool] $RequireRag = $false,
        [int] $RagTimeoutSec = 900
    )

    $headers = @{
        "Origin" = $Origin
        "X-Tenant-ID" = $TenantId
        "X-Env" = $Environment
    }
    $jsonHeaders = $headers.Clone()
    $jsonHeaders["Content-Type"] = "application/json"

    if (-not [string]::IsNullOrWhiteSpace($ResourceKey)) {
        $encodedResourceKey = [Uri]::EscapeDataString($ResourceKey)
        Write-Phase "Loading governed domain catalog from $BaseUrl/schemas/domain?resourceKey=$encodedResourceKey."
        $catalog = Invoke-RestMethod `
            -Method Get `
            -Uri "$BaseUrl/schemas/domain?resourceKey=$encodedResourceKey" `
            -Headers @{ "Origin" = $Origin } `
            -TimeoutSec 60
        if ($catalog.schemaVersion -ne "praxis.domain-catalog/v0.2") {
            throw "Expected praxis.domain-catalog/v0.2 for resource $ResourceKey, got $($catalog.schemaVersion)."
        }
        $body = $catalog | ConvertTo-Json -Depth 100
        Write-Phase "Ingesting governed domain catalog resource $ResourceKey into praxis-config-starter."
        Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/praxis/config/domain-catalog/ingest" `
            -Headers $jsonHeaders `
            -Body $body `
            -TimeoutSec 900 | Out-Null
        Write-Phase "Governed domain catalog ingest completed for resource $ResourceKey."
        $ragEvidence = $null
        if ($RequireRag) {
            $serviceKey = [string] $catalog.service.serviceKey
            if ([string]::IsNullOrWhiteSpace($serviceKey)) {
                throw "Governed domain catalog does not declare service.serviceKey for RAG readiness."
            }
            Write-Phase "Waiting for typed Domain Catalog RAG publication evidence."
            $ragEvidence = Wait-DomainCatalogRagReady `
                $BaseUrl $Origin $TenantId $Environment $serviceKey $ResourceKey $RagTimeoutSec
        }
        return [ordered]@{
            schemaVersion = "praxis.domain-catalog/v0.2"
            source = "/schemas/domain"
            ingested = $true
            groups = @()
            resourceKeys = @($ResourceKey)
            rag = $ragEvidence
        }
    }

    if ($null -eq $Groups -or $Groups.Count -eq 0) {
        throw "At least one governed domain catalog group is required."
    }
    foreach ($group in $Groups) {
        $encodedGroup = [Uri]::EscapeDataString($group)
        Write-Phase "Loading governed domain catalog from $BaseUrl/schemas/domain?group=$encodedGroup."
        $catalog = Invoke-RestMethod `
            -Method Get `
            -Uri "$BaseUrl/schemas/domain?group=$encodedGroup" `
            -Headers @{ "Origin" = $Origin } `
            -TimeoutSec 60

        if ($catalog.schemaVersion -ne "praxis.domain-catalog/v0.2") {
            throw "Expected praxis.domain-catalog/v0.2 for group $group, got $($catalog.schemaVersion)."
        }

        $body = $catalog | ConvertTo-Json -Depth 100
        Write-Phase "Ingesting governed domain catalog group $group into praxis-config-starter."
        Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/praxis/config/domain-catalog/ingest" `
            -Headers $jsonHeaders `
            -Body $body `
            -TimeoutSec 900 | Out-Null
        Write-Phase "Governed domain catalog ingest completed for group $group."
    }
    return [ordered]@{
        schemaVersion = "praxis.domain-catalog/v0.2"
        source = "/schemas/domain"
        ingested = $true
        groups = @($Groups)
    }
}

if ($ValidateEvidenceParsersOnly.IsPresent) {
    Assert-PlaywrightSummaryParserFixture
    Assert-PlaywrightScenarioReceiptParserFixture
    Assert-PlaywrightGovernedStateProjectionParserFixture
    Assert-EphemeralRuntimeSecretFixture
    Assert-EmptyDiagnosticEvidenceSerializationFixture
    Write-Output "Invoke-PbAgenticFullE2E: Playwright summary, scenario receipt, governed projection, runtime secret, and empty diagnostic evidence fixtures passed."
    exit 0
}

$starterRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $starterRoot
if ([string]::IsNullOrWhiteSpace($QuickstartRoot)) { $QuickstartRoot = Join-Path $workspaceRoot "praxis-api-quickstart" }
if ([string]::IsNullOrWhiteSpace($MetadataRoot)) { $MetadataRoot = Join-Path $workspaceRoot "praxis-metadata-starter" }
if ([string]::IsNullOrWhiteSpace($UiRoot)) { $UiRoot = Join-Path $workspaceRoot "praxis-ui-angular" }
if (-not [System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile = Join-Path $starterRoot $EnvFile }
$matrixPath = Join-Path $starterRoot "tools\e2e\page-builder-agentic-gate-matrix.json"

Assert-RequiredValue "JAVA_HOME/-JavaHome" $JavaHome
foreach ($requiredPath in @($QuickstartRoot, $MetadataRoot, $UiRoot, $EnvFile, $matrixPath, (Join-Path $JavaHome "bin\java.exe"))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Required path not found: $requiredPath" }
}

$gateMatrix = Get-Content -LiteralPath $matrixPath -Raw | ConvertFrom-Json
if ($ValidationMode -notmatch '^[a-z0-9][a-z0-9-]*$') {
    throw "Validation mode must be a canonical matrix token: $ValidationMode"
}
$modeMatrix = $gateMatrix.modes.$ValidationMode
if ($null -eq $modeMatrix) { throw "Validation mode is missing from the canonical gate matrix: $ValidationMode" }
$executionLane = if ($null -ne $modeMatrix.executionLane) {
    [string] $modeMatrix.executionLane
} else {
    "live"
}
if ($executionLane -notin @("live", "runtime-excellence")) {
    throw "Validation mode declares an unsupported execution lane: $executionLane"
}
$providerRequired = if ($null -ne $modeMatrix.providerRequired) {
    [bool] $modeMatrix.providerRequired
} else {
    $executionLane -eq "live"
}
if ($providerRequired -ne ($executionLane -eq "live")) {
    throw "Validation mode provider requirement diverges from its execution lane: $ValidationMode"
}
if ($providerRequired -and -not $ConfirmPaidProviderRun.IsPresent) {
    throw "The live Page Builder authoring lane calls a paid provider. Re-run with -ConfirmPaidProviderRun only after approving this single live gate."
}
$modeDomainCatalogResourceKey = if ($null -ne $modeMatrix.domainCatalogResourceKey) {
    [string] $modeMatrix.domainCatalogResourceKey
} else {
    ""
}
$modeApiCatalogGroup = if ($null -ne $modeMatrix.apiCatalogGroup -and
    -not [string]::IsNullOrWhiteSpace([string] $modeMatrix.apiCatalogGroup)) {
    [string] $modeMatrix.apiCatalogGroup
} elseif (-not [string]::IsNullOrWhiteSpace($modeDomainCatalogResourceKey)) {
    $modeDomainCatalogResourceKey.Split('.')[0]
} elseif ($executionLane -eq "live") {
    "human-resources"
} else {
    ""
}
$modeApiCatalogPathPrefixes = if ($null -ne $modeMatrix.apiCatalogPathPrefixes) {
    @($modeMatrix.apiCatalogPathPrefixes | ForEach-Object { [string] $_ })
} else {
    @()
}
$modeDomainCatalogRagRequired = $false
if ($null -ne $modeMatrix.domainCatalogRagRequired) {
    if ($modeMatrix.domainCatalogRagRequired -isnot [bool]) {
        throw "Validation mode domainCatalogRagRequired must be a boolean: $ValidationMode"
    }
    $modeDomainCatalogRagRequired = [bool] $modeMatrix.domainCatalogRagRequired
}
if (-not [string]::IsNullOrWhiteSpace($modeDomainCatalogResourceKey) -and
    $modeDomainCatalogResourceKey -notmatch '^[a-z0-9][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*)+$') {
    throw "Validation mode declares an invalid canonical domain catalog resource identity: $modeDomainCatalogResourceKey"
}
if (-not [string]::IsNullOrWhiteSpace($modeApiCatalogGroup) -and
    $modeApiCatalogGroup -notmatch '^[a-z0-9][a-z0-9-]*$') {
    throw "Validation mode declares an invalid canonical API catalog group: $modeApiCatalogGroup"
}
foreach ($pathPrefix in $modeApiCatalogPathPrefixes) {
    if ($pathPrefix -notmatch '^/api/[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)+$') {
        throw "Validation mode declares an invalid canonical API catalog path prefix: $pathPrefix"
    }
}
if ($executionLane -eq "runtime-excellence" -and (
    $modeDomainCatalogRagRequired -or
    -not [string]::IsNullOrWhiteSpace($modeDomainCatalogResourceKey) -or
    -not [string]::IsNullOrWhiteSpace($modeApiCatalogGroup) -or
    $modeApiCatalogPathPrefixes.Count -gt 0)) {
    throw "Runtime-excellence mode cannot depend on Domain Catalog RAG or API Catalog ingestion: $ValidationMode"
}
$isHumanResourcesFocusedMode = $ValidationMode -in @("smoke", "single-table") -or
    $modeDomainCatalogResourceKey.StartsWith("human-resources.", [StringComparison]::Ordinal)
$selectedScenarioIds = @($modeMatrix.scenarios | ForEach-Object { [string] $_ })
if ($selectedScenarioIds.Count -eq 0) {
    throw "Validation mode must declare at least one executable scenario: $ValidationMode"
}
foreach ($scenarioId in $selectedScenarioIds) {
    if ($scenarioId -notmatch '^[a-z0-9-]+$') {
        throw "Invalid executable scenario id in the canonical gate matrix: $scenarioId"
    }
}
if (@($selectedScenarioIds | Sort-Object -Unique).Count -ne $selectedScenarioIds.Count) {
    throw "Validation mode contains duplicate executable scenario ids: $ValidationMode"
}
$humanTurnLimit = if ($null -ne $modeMatrix.humanTurnLimit) {
    [int] $modeMatrix.humanTurnLimit
} else {
    0
}
if ($humanTurnLimit -lt 0) {
    throw "Validation mode humanTurnLimit cannot be negative: $ValidationMode"
}
if ($StreamProcessingTimeoutSeconds -le 0) {
    $StreamProcessingTimeoutSeconds = [int] $gateMatrix.defaults.streamProcessingTimeoutSeconds
}
if ($PlaywrightTestTimeoutMs -le 0) {
    $PlaywrightTestTimeoutMs = [int] $gateMatrix.defaults.playwrightTestTimeoutMs
}
$domainCatalogRagTimeoutSec = [Math]::Max(
    $StartupTimeoutSec,
    [int] [Math]::Ceiling($PlaywrightTestTimeoutMs / 1000.0)
)
if ($Retries -lt 0) {
    $Retries = if ($null -ne $modeMatrix.retries) {
        [int] $modeMatrix.retries
    } else {
        [int] $gateMatrix.defaults.retries
    }
}

$null = . $EnvFile
$resolvedEmbeddingProvider = if (-not $providerRequired) {
    "not-used"
} elseif ([string]::IsNullOrWhiteSpace($EmbeddingProvider)) {
    $Provider
} else {
    $EmbeddingProvider
}
$domainCatalogRagPublicationEnabled = $modeDomainCatalogRagRequired.ToString().ToLowerInvariant()
if ($providerRequired -and $resolvedEmbeddingProvider -ieq "mock") {
    throw "EMBEDDING_PROVIDER=mock is not valid for the production-like Page Builder gate."
}
Assert-PostgresUrl "SPRING_DATASOURCE_URL" $env:SPRING_DATASOURCE_URL
Assert-RequiredValue "SPRING_DATASOURCE_USERNAME" $env:SPRING_DATASOURCE_USERNAME
Assert-RequiredValue "SPRING_DATASOURCE_PASSWORD" $env:SPRING_DATASOURCE_PASSWORD
Assert-PostgresUrl "CONFIG_DATASOURCE_URL" $env:CONFIG_DATASOURCE_URL
Assert-RequiredValue "CONFIG_DATASOURCE_USERNAME" $env:CONFIG_DATASOURCE_USERNAME
Assert-RequiredValue "CONFIG_DATASOURCE_PASSWORD" $env:CONFIG_DATASOURCE_PASSWORD
$providerKeyName = if ($Provider -eq "openai") { "PRAXIS_AI_OPENAI_API_KEY" } else { "PRAXIS_AI_GEMINI_API_KEY" }
$providerKeyValue = [Environment]::GetEnvironmentVariable($providerKeyName)
if ($providerRequired) { Assert-RequiredValue $providerKeyName $providerKeyValue }

$javaExecutable = Join-Path $JavaHome "bin\java.exe"
$javaVersion = (& cmd.exe /d /c "`"$javaExecutable`" -version 2>&1" | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Cannot execute the configured Java runtime." }
if ($javaVersion -notmatch '(?m)version "21(?:\.|\")') { throw "Java 21 is required by the production-like gate." }
$nodeVersion = (& node --version 2>$null).Trim()
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(?<major>\d+)\.' -or [int] $Matches['major'] -lt 20) {
    throw "Node.js 20 or newer is required by the production-like gate."
}
if ($null -eq (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven is required by the production-like gate."
}
$playwrightVersion = $null
$chromiumVersion = $null
Push-Location $UiRoot
try {
    $playwrightVersionText = (& cmd.exe /c "npx.cmd playwright --version" 2>$null | Out-String).Trim()
    $playwrightVersionMatch = [regex]::Match($playwrightVersionText, 'Version\s+(?<version>\S+)')
    if (-not $playwrightVersionMatch.Success) { throw "Cannot resolve Playwright version." }
    $playwrightVersion = $playwrightVersionMatch.Groups['version'].Value
    $browserDryRun = (& cmd.exe /c "npx.cmd playwright install --dry-run" 2>$null | Out-String)
    $chromiumVersionMatch = [regex]::Match($browserDryRun, 'browser:\s+chromium\s+version\s+(?<version>\S+)')
    if (-not $chromiumVersionMatch.Success) { throw "Cannot resolve Playwright Chromium version." }
    $chromiumVersion = $chromiumVersionMatch.Groups['version'].Value
} finally {
    Pop-Location
}

if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $jar = Get-ChildItem -Path (Join-Path $QuickstartRoot "target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notmatch "(sources|javadoc|tests)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) { throw "Quickstart jar not found. Package praxis-api-quickstart first." }
    $JarPath = $jar.FullName
}

[xml] $starterPom = Get-Content -LiteralPath (Join-Path $starterRoot "pom.xml") -Raw
$expectedStarterVersion = if ([string]::IsNullOrWhiteSpace($ExpectedConfigVersion)) {
    [string] $starterPom.project.version
} else {
    $ExpectedConfigVersion.Trim()
}
$jarStarterDependency = Get-QuickstartDependencyEvidence $JarPath "praxis-config-starter"
$jarMetadataDependency = Get-QuickstartDependencyEvidence $JarPath "praxis-metadata-starter"
$jarStarterVersion = [string] $jarStarterDependency.version
$jarMetadataVersion = [string] $jarMetadataDependency.version
if ($jarStarterVersion -ne $expectedStarterVersion) {
    throw "Quickstart jar uses praxis-config-starter $jarStarterVersion, expected $expectedStarterVersion. Repackage it against the declared Config version."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedMetadataVersion) -and $jarMetadataVersion -ne $ExpectedMetadataVersion) {
    throw "Quickstart jar uses praxis-metadata-starter $jarMetadataVersion, expected $ExpectedMetadataVersion. Repackage it against the declared Metadata version."
}
$referenceStarterJar = if ([string]::IsNullOrWhiteSpace($ReferenceStarterJarPath)) {
    Join-Path $starterRoot "target\praxis-config-starter-$expectedStarterVersion.jar"
} else {
    $ReferenceStarterJarPath
}
if (-not (Test-Path -LiteralPath $referenceStarterJar -PathType Leaf)) {
    throw "Reference praxis-config-starter jar not found at '$referenceStarterJar'. Resolve the selected artifact before packaging the Quickstart."
}
$referenceStarterJar = (Resolve-Path -LiteralPath $referenceStarterJar).Path
$referenceStarterJarSha256 = (Get-FileHash -LiteralPath $referenceStarterJar -Algorithm SHA256).Hash.ToLowerInvariant()
$configStarterArtifactEvidence = [ordered]@{
    artifactId = "praxis-config-starter"
    version = $expectedStarterVersion
    source = $ConfigArtifactSource
    referenceJarSha256 = $referenceStarterJarSha256
    quickstartNestedJarSha256 = [string] $jarStarterDependency.sha256
    quickstartEntry = [string] $jarStarterDependency.entry
    byteIdentical = ($referenceStarterJarSha256 -eq [string] $jarStarterDependency.sha256)
}

[xml] $quickstartPom = Get-Content -LiteralPath (Join-Path $QuickstartRoot "pom.xml") -Raw
$quickstartVersion = [string] $quickstartPom.project.version
$uiPackage = Get-Content -LiteralPath (Join-Path $UiRoot "package.json") -Raw | ConvertFrom-Json
$uiWorkspaceVersion = [string] $uiPackage.version

$gitIdentities = @(
    Get-GitIdentity $starterRoot "praxis-config-starter"
    Get-GitIdentity $MetadataRoot "praxis-metadata-starter" "git-archive"
    Get-GitIdentity $QuickstartRoot "praxis-api-quickstart"
    Get-GitIdentity $UiRoot "praxis-ui-angular"
)
$configContractText = Get-Content -LiteralPath (Join-Path $starterRoot "docs\ai\contracts\praxis-ai-api-contract-v1.1.openapi.yaml") -Raw
$configContractMatch = [regex]::Match($configContractText, 'ContractSchemaHashHeader:[\s\S]*?default:\s+(?<hash>[0-9a-f]{64})')
$uiContractText = Get-Content -LiteralPath (Join-Path $UiRoot "projects\praxis-ai\src\lib\core\contracts\ai-contract.generated.ts") -Raw
$uiContractMatch = [regex]::Match($uiContractText, "AI_CONTRACT_SCHEMA_HASH\s*=\s*'(?<hash>[0-9a-f]{64})'")
if (-not $configContractMatch.Success -or -not $uiContractMatch.Success) {
    throw "Cannot resolve Config/Angular AI contract hashes."
}
$contractHash = $configContractMatch.Groups['hash'].Value
if ($uiContractMatch.Groups['hash'].Value -ne $contractHash) {
    throw "Config and Angular AI contract hashes diverge."
}

$backendUrl = "http://127.0.0.1:$BackendPort"
$uiUrl = "http://localhost:$UiPort"
$artifactRoot = Join-Path $starterRoot ("artifacts\page-builder-agentic-e2e\$ValidationMode\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$quickstartLogs = Join-Path $QuickstartRoot "logs"
New-Item -ItemType Directory -Force -Path $artifactRoot, $quickstartLogs | Out-Null
$backendProcess = $null
$uiProcess = $null
$playwrightSummary = $null
$scenarioEvidence = @()
$governedStateProjections = @()
$publishedDiagnosticEvidence = @()
$capabilitiesEvidence = $null
$runtimeSecrets = New-EphemeralRuntimeSecrets
$streamSecret = [string] $runtimeSecrets.streamAuthTokenSecret
$resourceVersionEtagSecret = [string] $runtimeSecrets.resourceVersionEtagSecret
$resultPath = Join-Path $artifactRoot "result.json"
$sourceAuditPath = Join-Path $artifactRoot "source-audit.json"
$playwrightReportPath = Join-Path $artifactRoot "playwright-results.json"
$evidenceValidationSummaryPath = Join-Path $artifactRoot "evidence-validation-summary.json"
$evidenceValidationPassed = $false
$evidenceValidationAttestation = $null
$gateFailure = $null
$pgvectorEvidence = $null
$loopbackVerified = $false
$aiRegistryEvidence = $null
$domainCatalogEvidence = $null
$apiCatalogEvidence = $null
$javaCompilerEvidence = $null
$apiCatalogReleaseId = ""
$runtimeExcellenceEvidence = @()

try {
    Write-Phase "Starting Page Builder gate. executionLane=$executionLane providerRequired=$providerRequired validationMode=$ValidationMode backend=$backendUrl ui=$uiUrl artifactRoot=$artifactRoot."
    if (-not $configStarterArtifactEvidence.byteIdentical) {
        throw "Quickstart nested praxis-config-starter jar does not match the selected reference artifact. source=$ConfigArtifactSource referenceSha256=$($configStarterArtifactEvidence.referenceJarSha256) nestedSha256=$($configStarterArtifactEvidence.quickstartNestedJarSha256) Resolve the selected artifact and clean-package the Quickstart."
    }
    Write-Phase "Verified byte-identical praxis-config-starter artifact. source=$ConfigArtifactSource sha256=$($configStarterArtifactEvidence.referenceJarSha256)"
    if ($null -ne (Get-ListenPid $BackendPort)) { throw "Port $BackendPort is already in use." }
    if ($null -ne (Get-ListenPid $UiPort)) { throw "Port $UiPort is already in use." }

    if ($providerRequired) {
        Write-Phase "Verifying PostgreSQL pgvector extension and vector_store schema."
        $pgvectorEvidence = Invoke-PgvectorPreflight `
            $QuickstartRoot `
            $JavaHome `
            (Join-Path $starterRoot "tools\e2e\PgvectorPreflight.java") `
            $artifactRoot
    } else {
        Write-Phase "Skipping pgvector preflight because runtime excellence does not use embeddings or RAG."
    }

    if ($executionLane -eq "runtime-excellence") {
        $runtimeReceiptDefinition = @($gateMatrix.evidence.runtimeExcellenceReceipts |
            Where-Object { $_.scenarioId -in $selectedScenarioIds }) | Select-Object -First 1
        if ($null -eq $runtimeReceiptDefinition) {
            throw "Runtime-excellence mode has no canonical receipt definition: $ValidationMode"
        }
        $planFixturePath = Join-Path $starterRoot ([string] $runtimeReceiptDefinition.planFixture)
        if (-not (Test-Path -LiteralPath $planFixturePath -PathType Leaf)) {
            throw "Certified runtime-excellence UiCompositionPlan fixture not found: $planFixturePath"
        }
        Write-Phase "Validating the certified UiCompositionPlan with the Java compiler."
        Push-Location $starterRoot
        try {
            & mvn "-Dtest=AgenticAuthoringUiCompositionPlanCompilerTest#compilesCertifiedBusinessCommandRuntimeFixtureWithoutAiProvider" test
            if ($LASTEXITCODE -ne 0) { throw "Java UiCompositionPlan compiler proof failed with exit code $LASTEXITCODE." }
        } finally {
            Pop-Location
        }
        $javaCompilerEvidence = [ordered]@{
            test = "AgenticAuthoringUiCompositionPlanCompilerTest#compilesCertifiedBusinessCommandRuntimeFixtureWithoutAiProvider"
            passed = $true
            providerUsed = $false
            planFixtureSha256 = [string] $runtimeReceiptDefinition.expectedPlanFixtureSha256
        }
        Write-Phase "Building the canonical Page Builder dependency closure used by the deterministic TypeScript compiler proof."
        Push-Location $UiRoot
        try {
            & cmd.exe /c "node.exe scripts\build-libs.js --prod --only praxis-page-builder"
            if ($LASTEXITCODE -ne 0) { throw "Page Builder dependency-closure build failed with exit code $LASTEXITCODE." }
        } finally {
            Pop-Location
        }
    }

    Write-Phase "Auditing real Angular authoring sources before starting services."
    Push-Location $UiRoot
    try {
        $sourceAuditOutput = & node "tools/e2e/audit-agentic-production-like-source.mjs" --workspace $UiRoot
        if ($LASTEXITCODE -ne 0) { throw "Production-like source audit failed." }
        $sourceAuditOutput | Set-Content -LiteralPath $sourceAuditPath -Encoding utf8
    } finally {
        Pop-Location
    }

    $starterAuthoringRoot = Join-Path $starterRoot "docs\ai\agentic-authoring"
    $workspaceAuthoringRoot = Join-Path $workspaceRoot "docs\ai\agentic-authoring"
    $authoringRoot = if (Test-Path -LiteralPath (Join-Path $starterAuthoringRoot "contracts")) {
        $starterAuthoringRoot
    } elseif (Test-Path -LiteralPath (Join-Path $workspaceAuthoringRoot "contracts")) {
        $workspaceAuthoringRoot
    } else {
        throw "Authoring contracts directory not found under starter or workspace roots."
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $authoringRoot "proofs") | Out-Null
    $backendScript = @"
Set-Location '$QuickstartRoot'
. '$EnvFile'
`$env:JAVA_HOME = '$JavaHome'
`$env:Path = '$JavaHome\bin;' + `$env:Path
`$env:PORT = '$BackendPort'
`$env:SERVER_PORT = '$BackendPort'
`$env:SERVER_ADDRESS = '127.0.0.1'
`$env:SPRING_PROFILES_ACTIVE = 'local'
`$env:PRAXIS_AI_PROVIDER = '$Provider'
`$env:PRAXIS_AI_GEMINI_PREFER_GENAI_API = 'false'
`$env:APP_SECURITY_READ_OPEN = 'true'
`$env:APP_SECURITY_CSRF_DISABLE = 'true'
`$env:APP_RATE_LIMIT_ENABLED = 'false'
`$env:APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS = '$uiUrl,http://127.0.0.1:$UiPort'
`$env:CORS_ALLOWED_ORIGINS = '$uiUrl,http://127.0.0.1:$UiPort'
`$env:PRAXIS_AI_AUTHORING_HTTP_ENABLED = 'true'
`$env:PRAXIS_AI_AUTHORING_ARTIFACTS_DIR = '$authoringRoot\proofs'
`$env:PRAXIS_AI_AUTHORING_CONTRACTS_DIR = '$authoringRoot\contracts'
`$env:PRAXIS_AI_STREAM_PROCESSING_TIMEOUT_SECONDS = '$StreamProcessingTimeoutSeconds'
`$env:PRAXIS_AI_SECURITY_CORPORATE_MODE = 'false'
`$env:PRAXIS_AI_SECURITY_ALLOW_HEADER_IDENTITY_IN_LOCAL = 'true'
`$env:PRAXIS_AI_SECURITY_LOCAL_DEFAULT_TENANT = 'desenv'
`$env:PRAXIS_AI_SECURITY_LOCAL_DEFAULT_USER = 'codex-e2e'
`$env:PRAXIS_AI_SECURITY_LOCAL_DEFAULT_ENVIRONMENT = 'local'
`$env:PRAXIS_AI_STREAM_AUTH_MODE = 'signed-url-token'
`$env:PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET = '$streamSecret'
`$env:PRAXIS_RESOURCE_VERSION_ETAG_SECRET = '$resourceVersionEtagSecret'
`$env:PRAXIS_AI_REGISTRY_BOOTSTRAP_ENABLED = 'true'
`$env:SPRING_AI_ENABLED = '$($providerRequired.ToString().ToLowerInvariant())'
`$env:PRAXIS_AI_RAG_VECTOR_STORE_ENABLED = '$($providerRequired.ToString().ToLowerInvariant())'
`$env:PRAXIS_API_METADATA_RAG_PUBLICATION_ENABLED = '$($providerRequired.ToString().ToLowerInvariant())'
`$env:EMBEDDING_PROVIDER = '$resolvedEmbeddingProvider'
`$env:PRAXIS_DOMAIN_CATALOG_RAG_PUBLICATION_ENABLED = '$domainCatalogRagPublicationEnabled'
`$env:PRAXIS_DOMAIN_CATALOG_RAG_PUBLICATION_ASYNC_ENABLED = '$($providerRequired.ToString().ToLowerInvariant())'
`$env:PRAXIS_PROJECT_KNOWLEDGE_RAG_PUBLICATION_ENABLED = '$($providerRequired.ToString().ToLowerInvariant())'
`$env:PRAXIS_PROJECT_KNOWLEDGE_RAG_RETRIEVAL_ENABLED = '$($providerRequired.ToString().ToLowerInvariant())'
if (`$env:PRAXIS_AI_OPENAI_MODEL) { `$env:SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL = `$env:PRAXIS_AI_OPENAI_MODEL }
& '$JavaHome\bin\java.exe' -jar '$JarPath'
"@
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($backendScript))
    Write-Phase "Starting Quickstart backend on $backendUrl."
    $env:PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET = $streamSecret
    $env:PRAXIS_RESOURCE_VERSION_ETAG_SECRET = $resourceVersionEtagSecret
    $backendProcess = Start-Process powershell.exe -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", $encoded) -RedirectStandardOutput (Join-Path $quickstartLogs "page-builder-agentic-e2e.out.log") -RedirectStandardError (Join-Path $quickstartLogs "page-builder-agentic-e2e.err.log") -PassThru -WindowStyle Hidden
    Wait-Url "$backendUrl/actuator/health" $StartupTimeoutSec "Quickstart backend"
    Assert-LoopbackListener $BackendPort "Quickstart backend"
    Write-Phase "Quickstart backend is healthy."

    $registrySnapshotPath = Join-Path $starterRoot "src\main\resources\ai-registry\registry-snapshot.json"
    $expectedRegistrySnapshotHash = (Get-FileHash -LiteralPath $registrySnapshotPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Phase "Verifying canonical AI Registry bootstrap and immutable snapshot hash."
    $aiRegistryEvidence = Wait-AiRegistryReady $backendUrl $uiUrl $expectedRegistrySnapshotHash $StartupTimeoutSec

    if ($providerRequired) {
        $domainCatalogGroups = if ($ValidationMode -eq "full") {
            @("human-resources", "operations")
        } else {
            @($modeApiCatalogGroup)
        }
        $domainCatalogEvidence = Invoke-DomainCatalogIngest `
            $backendUrl $uiUrl "desenv" "local" $domainCatalogGroups $modeDomainCatalogResourceKey `
            $modeDomainCatalogRagRequired $domainCatalogRagTimeoutSec

        Push-Location $UiRoot
        try {
            Write-Phase "Uploading API catalog into praxis-config-starter."
        $env:BACKEND_URL = $backendUrl
        $env:CATALOG_URL = "$backendUrl/schemas/catalog"
        $env:CONFIG_ORIGIN = $uiUrl
        $env:TENANT_ID = "desenv"
        $env:ENVIRONMENT = "local"
        $apiCatalogReleaseId = if ($isHumanResourcesFocusedMode) {
            "e2e-page-builder-smoke-v1"
        } else {
            "v1"
        }
        $env:RELEASE_ID = $apiCatalogReleaseId
        $env:REQUEST_TIMEOUT_MS = "60000"
        $env:INDEXING_TIMEOUT_MS = "$($ApiCatalogIndexingTimeoutSec * 1000)"
        $env:STATUS_POLL_MS = "1000"
        $focusedApiCatalogPathPrefixes = if ($modeApiCatalogPathPrefixes.Count -gt 0) {
            @($modeApiCatalogPathPrefixes)
        } elseif ($isHumanResourcesFocusedMode) {
            @(
                "/api/human-resources/funcionarios",
                "/api/human-resources/departamentos",
                "/api/human-resources/folhas-pagamento",
                "/api/human-resources/vw-analytics-folha-pagamento",
                "/api/human-resources/eventos-folha",
                "/api/human-resources/historicos-salariais"
            )
        } else {
            @()
        }
        if ($focusedApiCatalogPathPrefixes.Count -gt 0) {
            $env:API_CATALOG_PATH_PREFIXES = ($focusedApiCatalogPathPrefixes -join ",")
            $env:CHUNK_SIZE = "20"
            Write-Phase "Focused mode: API catalog upload scoped to $($focusedApiCatalogPathPrefixes.Count) $modeApiCatalogGroup path prefixes."
        } else {
            Remove-Item Env:\API_CATALOG_PATH_PREFIXES -ErrorAction SilentlyContinue
            $env:CHUNK_SIZE = "20"
        }
        $env:PAUSE_MS = "0"
        Write-Phase "API catalog chunks must be accepted within 60 seconds per request; canonical indexing must reach READY within $ApiCatalogIndexingTimeoutSec seconds."
        & cmd.exe /c "npx.cmd ts-node --project tools/tsconfig.tools.json tools/ai-registry/upload-api-catalog.ts"
        if ($LASTEXITCODE -ne 0) { throw "API catalog upload or canonical indexing failed with exit code $LASTEXITCODE." }
        Write-Phase "API catalog upload and canonical indexing reached READY."
            $apiCatalogEvidence = [ordered]@{
                source = "/schemas/catalog"
                indexingState = "READY"
                scope = if ($focusedApiCatalogPathPrefixes.Count -gt 0) {
                    if ([string]::IsNullOrWhiteSpace($modeDomainCatalogResourceKey)) {
                        "$modeApiCatalogGroup-focused"
                    } else {
                        "resource:$modeDomainCatalogResourceKey"
                    }
                } else {
                    "full"
                }
            }
        } finally {
            Pop-Location
        }
    } else {
        Write-Phase "Skipping Domain Catalog and API Catalog ingestion because runtime excellence consumes real metadata directly and does not author through RAG."
    }

    Write-Phase "Verifying governed component capabilities without browser interception."
    $capabilities = Invoke-RestMethod `
        -Method Get `
        -Uri "$backendUrl/api/praxis/config/ai/authoring/component-capabilities" `
        -Headers @{
            "Origin" = $uiUrl
            "X-Tenant-ID" = "desenv"
            "X-User-ID" = "demo"
            "X-Env" = "local"
        } `
        -TimeoutSec 90
    if ($capabilities.diagnostics.source -ne "registry" -or $capabilities.diagnostics.degraded -ne $false) {
        $capabilityDiagnostics = [ordered]@{
            source = [string] $capabilities.diagnostics.source
            degraded = [bool] $capabilities.diagnostics.degraded
            degradationReason = [string] $capabilities.diagnostics.degradationReason
            lastSuccessfulRegistryLoadAt = [string] $capabilities.diagnostics.lastSuccessfulRegistryLoadAt
            catalogCount = @($capabilities.catalogs).Count
        }
        $sanitizedCapabilityDiagnostics = $capabilityDiagnostics | ConvertTo-Json -Compress
        throw "Component capabilities must be registry-backed and non-degraded in the production-like gate. SanitizedDiagnostics=$sanitizedCapabilityDiagnostics"
    }
    $chartCatalog = @($capabilities.catalogs | Where-Object { $_.componentId -eq "praxis-chart" }) | Select-Object -First 1
    $chartCapabilityIds = @($chartCatalog.capabilities | ForEach-Object { $_.id })
    if ($chartCapabilityIds -notcontains "data.resource.bind") {
        throw "Registry-backed praxis-chart capability data.resource.bind is missing."
    }
    $capabilitiesEvidence = [ordered]@{
        source = "registry"
        degraded = $false
        catalogCount = @($capabilities.catalogs).Count
        chartResourceBinding = $true
    }

    $cmd = "set PAX_PROXY_TARGET=$backendUrl&& set PLAYWRIGHT_BASE_URL=$uiUrl&& npx.cmd ng serve praxis-ui-workspace --port $UiPort --host localhost --proxy-config proxy.conf.js"
    Write-Phase "Starting Angular dev server on $uiUrl."
    $angularOutLog = Join-Path $artifactRoot "angular.out.log"
    $angularErrLog = Join-Path $artifactRoot "angular.err.log"
    $uiProcess = Start-Process cmd.exe -ArgumentList @("/c", $cmd) -WorkingDirectory $UiRoot -RedirectStandardOutput $angularOutLog -RedirectStandardError $angularErrLog -PassThru -WindowStyle Hidden
    try {
        Wait-Url $uiUrl $UiStartupTimeoutSec "Angular dev server"
    } catch {
        Write-Phase "Angular dev server did not become reachable. Last stdout/stderr lines follow."
        if (Test-Path -LiteralPath $angularOutLog) {
            Write-Host "--- angular.out.log tail ---"
            Get-Content -LiteralPath $angularOutLog -Tail 120 -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $angularErrLog) {
            Write-Host "--- angular.err.log tail ---"
            Get-Content -LiteralPath $angularErrLog -Tail 120 -ErrorAction SilentlyContinue
        }
        throw
    }
    Assert-LoopbackListener $UiPort "Angular dev server"
    $loopbackVerified = $true
    Write-Phase "Angular dev server is reachable."

    Push-Location $UiRoot
    try {
        Write-Phase "Running Playwright Page Builder validation. mode=$ValidationMode retries=$Retries timeoutMs=$PlaywrightTestTimeoutMs."
        $env:PLAYWRIGHT_BASE_URL = $uiUrl
        $env:PRAXIS_E2E_API_CATALOG_RELEASE_ID = $apiCatalogReleaseId
        $env:PRAXIS_E2E_AGENTIC_VALIDATION_MODE = $ValidationMode
        $env:PRAXIS_E2E_AGENTIC_EXECUTION_LANE = $executionLane
        $env:PRAXIS_E2E_SCENARIO_IDS = $selectedScenarioIds -join ","
        $env:PRAXIS_E2E_JSON_REPORT_PATH = $playwrightReportPath
        if ($executionLane -eq "runtime-excellence") {
            $env:PRAXIS_E2E_RUNTIME_EXCELLENCE_PLAN_PATH = $planFixturePath
            $env:PRAXIS_E2E_PAGE_BUILDER_MODULE_PATH = Join-Path $UiRoot "dist\praxis-page-builder\fesm2022\praxisui-page-builder.mjs"
        } else {
            Remove-Item Env:\PRAXIS_E2E_RUNTIME_EXCELLENCE_PLAN_PATH -ErrorAction SilentlyContinue
            Remove-Item Env:\PRAXIS_E2E_PAGE_BUILDER_MODULE_PATH -ErrorAction SilentlyContinue
        }
        if ($humanTurnLimit -gt 0) {
            $env:PRAXIS_E2E_HUMAN_TURN_LIMIT = "$humanTurnLimit"
            $env:PRAXIS_E2E_HUMAN_TURN_LIMIT_SOURCE = "canonical-gate-profile"
        } else {
            Remove-Item Env:\PRAXIS_E2E_HUMAN_TURN_LIMIT -ErrorAction SilentlyContinue
            Remove-Item Env:\PRAXIS_E2E_HUMAN_TURN_LIMIT_SOURCE -ErrorAction SilentlyContinue
        }
        if ($PlaywrightTestTimeoutMs -gt 0) {
            $env:PRAXIS_E2E_TEST_TIMEOUT_MS = "$PlaywrightTestTimeoutMs"
        } else {
            Remove-Item Env:\PRAXIS_E2E_TEST_TIMEOUT_MS -ErrorAction SilentlyContinue
        }
        $playwrightConfig = if ($executionLane -eq "runtime-excellence") {
            "tools/e2e/playwright/praxis-page-builder-runtime-excellence.playwright.config.ts"
        } else {
            "tools/e2e/playwright/praxis-page-builder-agentic-production-like.playwright.config.ts"
        }
        & cmd.exe /c "npx.cmd playwright test --config=$playwrightConfig --retries=$Retries"
        $playwrightExitCode = $LASTEXITCODE
        if (Test-Path -LiteralPath $playwrightReportPath) {
            # Export allowlisted per-turn evidence before later receipt or first-pass gates can fail.
            $telemetryExporterPath = Join-Path $starterRoot "tools\e2e\export-page-builder-provider-telemetry.mjs"
            & node $telemetryExporterPath --report $playwrightReportPath --out (Join-Path $artifactRoot "provider-invocations.json")
            if ($LASTEXITCODE -ne 0) { throw "Provider telemetry export failed." }
            $playwrightSummary = Get-PlaywrightSummary $playwrightReportPath
            $scenarioEvidence = @(Get-PlaywrightScenarioEvidence `
                -ReportPath $playwrightReportPath `
                -Definitions @($gateMatrix.evidence.scenarioReceipts) `
                -SelectedScenarioIds $selectedScenarioIds `
                -AllowPartial)
            $governedStateProjections = @(Get-PlaywrightGovernedStateProjections `
                -ReportPath $playwrightReportPath `
                -Definitions @($gateMatrix.evidence.governedStateProjections) `
                -SelectedScenarioIds $selectedScenarioIds `
                -AllowPartial)
        }
        if ($playwrightExitCode -ne 0) { throw "Page-builder agentic $ValidationMode E2E failed with exit code $playwrightExitCode." }
        if ($null -eq $playwrightSummary) { throw "Playwright summary is unavailable after a successful execution." }
        if ($playwrightSummary.discovered -ne [int] $modeMatrix.expectedDiscovered) {
            throw "Playwright discovered $($playwrightSummary.discovered) tests; matrix expects $($modeMatrix.expectedDiscovered)."
        }
        if ($playwrightSummary.executed -lt [int] $modeMatrix.minimumExecuted) {
            throw "Playwright executed $($playwrightSummary.executed) tests; matrix requires at least $($modeMatrix.minimumExecuted)."
        }
        if ($playwrightSummary.skipped -ne [int] $modeMatrix.expectedSkipped) {
            throw "Playwright skipped $($playwrightSummary.skipped) tests; matrix expects $($modeMatrix.expectedSkipped)."
        }
        foreach ($requiredTitle in @($modeMatrix.requiredPassedTests)) {
            $requiredTest = @($playwrightSummary.tests | Where-Object {
                $_.title -eq $requiredTitle -and $_.status -eq "expected"
            })
            if ($requiredTest.Count -ne 1) {
                throw "Required production-like Playwright proof did not pass exactly once: $requiredTitle"
            }
        }
        $scenarioEvidence = @(Get-PlaywrightScenarioEvidence `
            -ReportPath $playwrightReportPath `
            -Definitions @($gateMatrix.evidence.scenarioReceipts) `
            -SelectedScenarioIds $selectedScenarioIds)
        $governedStateProjections = @(Get-PlaywrightGovernedStateProjections `
            -ReportPath $playwrightReportPath `
            -Definitions @($gateMatrix.evidence.governedStateProjections) `
            -SelectedScenarioIds $selectedScenarioIds)
        $evidenceValidatorPath = Join-Path $starterRoot "tools\e2e\validate-page-builder-agentic-gate-evidence.mjs"
        & node $evidenceValidatorPath `
            --matrix $matrixPath `
            --mode $ValidationMode `
            --expected-runs 1 `
            --report $playwrightReportPath *> $evidenceValidationSummaryPath
        if ($LASTEXITCODE -ne 0) {
            throw "Canonical Page Builder evidence validation failed with exit code $LASTEXITCODE."
        }
        $evidenceValidationSummary = Get-Content -LiteralPath $evidenceValidationSummaryPath -Raw | ConvertFrom-Json
        $validatedRuns = @($evidenceValidationSummary.runs)
        if ($evidenceValidationSummary.schemaVersion -ne "praxis.page-builder-agentic-gate-evidence-summary/v1" -or
            $evidenceValidationSummary.mode -ne $ValidationMode -or
            [int] $evidenceValidationSummary.expectedRuns -ne 1 -or
            [int] $evidenceValidationSummary.passedRuns -ne 1 -or
            $evidenceValidationSummary.stable -ne $true -or
            $validatedRuns.Count -ne 1) {
            throw "Canonical Page Builder evidence validator returned an invalid single-run summary."
        }
        $validatedRun = $validatedRuns[0]
        $evidenceValidationAttestation = [ordered]@{
            schemaVersion = "praxis.page-builder-agentic-gate-run-attestation/v1"
            reportSha256 = [string] $validatedRun.reportSha256
            durationMs = [int64] $validatedRun.durationMs
            discovered = [int] $validatedRun.discovered
            passed = [int] $validatedRun.passed
            retries = [int] $validatedRun.retries
            receipts = @($validatedRun.receipts)
            runtimeExcellenceReceipts = @($validatedRun.runtimeExcellenceReceipts)
            semanticRefinements = @($validatedRun.semanticRefinements)
        }
        $runtimeExcellenceEvidence = @($validatedRun.runtimeExcellenceReceipts)
        $evidenceValidationPassed = $true
        Write-Phase "Playwright Page Builder validation completed."
    } finally {
        Remove-Item Env:\PRAXIS_E2E_SCENARIO_IDS -ErrorAction SilentlyContinue
        Remove-Item Env:\PRAXIS_E2E_HUMAN_TURN_LIMIT -ErrorAction SilentlyContinue
        Remove-Item Env:\PRAXIS_E2E_HUMAN_TURN_LIMIT_SOURCE -ErrorAction SilentlyContinue
        Remove-Item Env:\PRAXIS_E2E_RUNTIME_EXCELLENCE_PLAN_PATH -ErrorAction SilentlyContinue
        Remove-Item Env:\PRAXIS_E2E_PAGE_BUILDER_MODULE_PATH -ErrorAction SilentlyContinue
        Pop-Location
    }

} catch {
    $gateFailure = $_
} finally {
    Write-Phase "Stopping Page Builder E2E processes."
    $uiProcessWasStarted = $null -ne $uiProcess
    $backendProcessWasStarted = $null -ne $backendProcess
    Stop-ProcAndPort $uiProcess $UiPort
    Stop-ProcAndPort $backendProcess $BackendPort
    Remove-Item Env:\PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET -ErrorAction SilentlyContinue
    Remove-Item Env:\PRAXIS_RESOURCE_VERSION_ETAG_SECRET -ErrorAction SilentlyContinue
    try {
        if ($uiProcessWasStarted) { Assert-PortReleased $UiPort "Angular dev server" }
        if ($backendProcessWasStarted) { Assert-PortReleased $BackendPort "Quickstart backend" }
    } catch {
        if ($null -eq $gateFailure) { $gateFailure = $_ }
    }
    if ($null -ne $gateFailure) {
        $publishedDiagnosticEvidence = @($governedStateProjections)
    }

    $modelId = if (-not $providerRequired) {
        $null
    } elseif ($Provider -eq "openai") {
        $env:PRAXIS_AI_OPENAI_MODEL
    } else {
        $env:PRAXIS_AI_GEMINI_MODEL
    }
    $criticalGuardTitle = [string] $gateMatrix.evidence.criticalInterceptionGuardTest
    $criticalGuardPassed = @($playwrightSummary.tests | Where-Object {
        $_.title -eq $criticalGuardTitle -and $_.status -eq "expected"
    }).Count -eq 1
    [pscustomobject]@{
        schemaVersion = if ($executionLane -eq "runtime-excellence") {
            "praxis.page-builder-runtime-excellence-result/v1"
        } else {
            "praxis.page-builder-agentic-production-like-result/v1"
        }
        productionLike = ($null -eq $gateFailure)
        criticalEndpointMocks = if ($criticalGuardPassed -or $executionLane -eq "runtime-excellence") { 0 } else { $null }
        criticalInterceptionGuard = [ordered]@{
            testTitle = $criticalGuardTitle
            applicable = ($executionLane -eq "live")
            passed = if ($executionLane -eq "live") { $criticalGuardPassed } else { $null }
        }
        executionLane = $executionLane
        validationMode = $ValidationMode
        e2ePassed = ($null -eq $gateFailure)
        provider = if ($providerRequired) { $Provider } else { $null }
        providerRequired = $providerRequired
        model = $modelId
        embeddingProvider = $resolvedEmbeddingProvider
        datasourceKinds = [ordered]@{ application = "postgresql"; config = "postgresql" }
        dependencyAttestation = [ordered]@{
            configStarter = $configStarterArtifactEvidence
        }
        pgvector = $pgvectorEvidence
        compilerProofs = [ordered]@{
            java = $javaCompilerEvidence
            typescript = if ($runtimeExcellenceEvidence.Count -eq 1) {
                [ordered]@{
                    passed = $true
                    providerUsed = $false
                    sourceSha256 = [string] $runtimeExcellenceEvidence[0].sourceSha256
                    persistedPayloadSha256 = [string] $runtimeExcellenceEvidence[0].persistedPayloadSha256
                }
            } else {
                $null
            }
        }
        backendBaseUrl = $backendUrl
        uiBaseUrl = $uiUrl
        loopbackOnly = $loopbackVerified
        cleanupVerified = (
            (-not $uiProcessWasStarted -or (Get-ListenConnections $UiPort).Count -eq 0) -and
            (-not $backendProcessWasStarted -or (Get-ListenConnections $BackendPort).Count -eq 0)
        )
        artifactRoot = $artifactRoot
        sourceAudit = [ordered]@{ passed = (Test-Path -LiteralPath $sourceAuditPath); artifact = "source-audit.json" }
        evidenceValidation = [ordered]@{
            passed = $evidenceValidationPassed
            artifact = "evidence-validation-summary.json"
            attestation = $evidenceValidationAttestation
        }
        git = $gitIdentities
        versions = [ordered]@{
            configStarter = $expectedStarterVersion
            quickstartConfigDependency = $jarStarterVersion
            metadataStarterDependency = $jarMetadataVersion
            quickstart = $quickstartVersion
            angularWorkspace = $uiWorkspaceVersion
            java = 21
            node = $nodeVersion
            playwright = $playwrightVersion
            chromium = $chromiumVersion
        }
        contractHash = $contractHash
        capabilities = $capabilitiesEvidence
        aiRegistry = $aiRegistryEvidence
        catalogs = [ordered]@{
            domain = $domainCatalogEvidence
            api = $apiCatalogEvidence
        }
        matrix = [ordered]@{
            schemaVersion = $gateMatrix.schemaVersion
            executionLane = $executionLane
            providerRequired = $providerRequired
            scenarios = @($modeMatrix.scenarios)
            expectedDiscovered = [int] $modeMatrix.expectedDiscovered
            minimumExecuted = [int] $modeMatrix.minimumExecuted
            expectedSkipped = [int] $modeMatrix.expectedSkipped
            requiredPassedTests = @($modeMatrix.requiredPassedTests)
            streamProcessingTimeoutSeconds = $StreamProcessingTimeoutSeconds
            playwrightTestTimeoutMs = $PlaywrightTestTimeoutMs
            retries = $Retries
            humanTurnLimit = if ($humanTurnLimit -gt 0) { $humanTurnLimit } else { $null }
            domainCatalogRagRequired = $modeDomainCatalogRagRequired
            domainCatalogResourceKey = if ([string]::IsNullOrWhiteSpace($modeDomainCatalogResourceKey)) { $null } else { $modeDomainCatalogResourceKey }
            apiCatalogGroup = if ([string]::IsNullOrWhiteSpace($modeApiCatalogGroup)) { $null } else { $modeApiCatalogGroup }
            apiCatalogPathPrefixes = @($modeApiCatalogPathPrefixes)
            diagnosticProjectionRequirements = @($gateMatrix.evidence.governedStateProjections |
                Where-Object { $_.scenarioId -in $selectedScenarioIds } |
                ForEach-Object {
                    [ordered]@{
                        scenarioId = [string] $_.scenarioId
                        testTitle = [string] $_.testTitle
                        attachmentName = [string] $_.attachmentName
                    }
                })
            receiptRequirements = @($gateMatrix.evidence.scenarioReceipts |
                Where-Object { $_.scenarioId -in $selectedScenarioIds } |
                ForEach-Object {
                    [ordered]@{
                        scenarioId = [string] $_.scenarioId
                        archetype = [string] $_.archetype
                        requiredFunctionalAssertions = @($_.requiredFunctionalAssertions)
                    }
                })
            runtimeExcellenceReceiptRequirements = @($gateMatrix.evidence.runtimeExcellenceReceipts |
                Where-Object { $_.scenarioId -in $selectedScenarioIds } |
                ForEach-Object {
                    [ordered]@{
                        scenarioId = [string] $_.scenarioId
                        archetype = [string] $_.archetype
                        planFixture = [string] $_.planFixture
                        expectedPlanFixtureSha256 = [string] $_.expectedPlanFixtureSha256
                        expectedCompiledPayloadSha256 = [string] $_.expectedCompiledPayloadSha256
                        requiredFunctionalAssertions = @($_.requiredFunctionalAssertions)
                    }
                })
            semanticRefinementRequirements = @($gateMatrix.evidence.semanticRefinements |
                Where-Object { $_.scenarioId -in $selectedScenarioIds } |
                ForEach-Object {
                    [ordered]@{
                        scenarioId = [string] $_.scenarioId
                        testTitle = [string] $_.testTitle
                        attachmentName = [string] $_.attachmentName
                        turnLimitSource = [string] $_.turnLimitSource
                        requiredOperationIds = @($_.requiredOperationIds)
                    }
                })
        }
        playwright = $playwrightSummary
        scenarioEvidence = @($scenarioEvidence)
        runtimeExcellenceEvidence = @($runtimeExcellenceEvidence)
        diagnosticEvidence = @($publishedDiagnosticEvidence)
        failureType = if ($null -eq $gateFailure) { $null } else { $gateFailure.Exception.GetType().FullName }
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resultPath -Encoding utf8
}

if ($null -ne $gateFailure) { throw $gateFailure }
Get-Content -LiteralPath $resultPath -Raw
