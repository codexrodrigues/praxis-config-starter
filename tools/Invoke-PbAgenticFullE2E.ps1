param(
    [ValidateSet("openai", "gemini")]
    [string] $Provider = "openai",
    [string] $QuickstartRoot = "",
    [string] $MetadataRoot = "",
    [string] $UiRoot = "",
    [string] $JarPath = "",
    [string] $LocalStarterJarPath = "",
    [string] $EnvFile = ".env.openai.local.ps1",
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $EmbeddingProvider = "",
    [string] $ExpectedMetadataVersion = "",
    [int] $BackendPort = 8088,
    [int] $UiPort = 4003,
    [int] $StartupTimeoutSec = 180,
    [int] $UiStartupTimeoutSec = 600,
    [int] $StreamProcessingTimeoutSeconds = 0,
    [int] $ApiCatalogIndexingTimeoutSec = 900,
    [ValidateSet("smoke", "single-table", "full")]
    [string] $ValidationMode = "smoke",
    [int] $PlaywrightTestTimeoutMs = 0,
    [int] $Retries = -1,
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

function Get-PlaywrightScenarioEvidenceFromReport([object] $Report, [object[]] $Definitions, [string[]] $SelectedScenarioIds) {
    $evidence = @()
    $specs = @(Get-PlaywrightSpecs @($Report.suites))
    foreach ($definition in @($Definitions | Where-Object { $_.scenarioId -in $SelectedScenarioIds })) {
        $matchingSpecs = @($specs | Where-Object { $_.title -eq $definition.testTitle })
        if ($matchingSpecs.Count -ne 1) {
            throw "Scenario receipt test title must resolve exactly once: $($definition.testTitle)"
        }
        $testCases = @($matchingSpecs[0].tests | Where-Object { $_.status -eq 'expected' })
        if ($testCases.Count -ne 1) {
            throw "Scenario receipt test must pass exactly once: $($definition.testTitle)"
        }
        $results = @($testCases[0].results)
        $passedResults = @($results | Where-Object { $_.status -eq 'passed' })
        if ($passedResults.Count -ne 1) {
            throw "Scenario receipt requires exactly one successful Playwright result: $($definition.testTitle)"
        }
        $attachments = @($passedResults[0].attachments | Where-Object { $_.name -eq $definition.attachmentName })
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
    }
    return @($evidence)
}

function Get-PlaywrightScenarioEvidence([string] $ReportPath, [object[]] $Definitions, [string[]] $SelectedScenarioIds) {
    if (-not (Test-Path -LiteralPath $ReportPath)) { throw "Playwright JSON report was not generated: $ReportPath" }
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    return @(Get-PlaywrightScenarioEvidenceFromReport $report $Definitions $SelectedScenarioIds)
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
        }
        terminal = [ordered]@{ outcome = 'applicable'; transport = 'stream'; blockingDiagnosticCodes = @() }
        persistence = [ordered]@{
            version = 1
            etagPresent = $true
            persistedPayloadSha256 = ('a' * 64)
            reloadPayloadSha256 = ('a' * 64)
            reloadMatchesPersisted = $true
            reloadEtagMatches = $true
        }
        runtime = [ordered]@{
            masterRendered = $true
            detailRendered = $true
            selectionPropagated = $true
            actionDiscoveryStatus = 200
            capabilitiesDiscoveryStatus = 200
            commandStatus = 200
            duplicateCommandStatus = 409
            refreshObserved = $true
            reloadRendered = $true
        }
        timingMs = [ordered]@{
            authoringToApplicable = 10
            applyAndReadback = 20
            runtimeAndCommand = 30
            reload = 40
            total = 110
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
        [string[]] $Groups
    )

    $headers = @{
        "Origin" = $Origin
        "X-Tenant-ID" = $TenantId
        "X-Env" = $Environment
    }
    $jsonHeaders = $headers.Clone()
    $jsonHeaders["Content-Type"] = "application/json"

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
    Assert-EphemeralRuntimeSecretFixture
    Write-Output "Invoke-PbAgenticFullE2E: Playwright summary, scenario receipt, and runtime secret fixtures passed."
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
$modeMatrix = $gateMatrix.modes.$ValidationMode
if ($null -eq $modeMatrix) { throw "Validation mode is missing from the canonical gate matrix: $ValidationMode" }
$isHumanResourcesFocusedMode = $ValidationMode -in @("smoke", "single-table")
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
if ($StreamProcessingTimeoutSeconds -le 0) {
    $StreamProcessingTimeoutSeconds = [int] $gateMatrix.defaults.streamProcessingTimeoutSeconds
}
if ($PlaywrightTestTimeoutMs -le 0) {
    $PlaywrightTestTimeoutMs = [int] $gateMatrix.defaults.playwrightTestTimeoutMs
}
if ($Retries -lt 0) {
    $Retries = if ($null -ne $modeMatrix.retries) {
        [int] $modeMatrix.retries
    } else {
        [int] $gateMatrix.defaults.retries
    }
}

$null = . $EnvFile
$resolvedEmbeddingProvider = if ([string]::IsNullOrWhiteSpace($EmbeddingProvider)) { $Provider } else { $EmbeddingProvider }
if ($resolvedEmbeddingProvider -ieq "mock") {
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
Assert-RequiredValue $providerKeyName $providerKeyValue

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
$expectedStarterVersion = [string] $starterPom.project.version
$jarStarterDependency = Get-QuickstartDependencyEvidence $JarPath "praxis-config-starter"
$jarMetadataDependency = Get-QuickstartDependencyEvidence $JarPath "praxis-metadata-starter"
$jarStarterVersion = [string] $jarStarterDependency.version
$jarMetadataVersion = [string] $jarMetadataDependency.version
if ($jarStarterVersion -ne $expectedStarterVersion) {
    throw "Quickstart jar uses praxis-config-starter $jarStarterVersion, expected $expectedStarterVersion. Repackage it against the current starter."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedMetadataVersion) -and $jarMetadataVersion -ne $ExpectedMetadataVersion) {
    throw "Quickstart jar uses praxis-metadata-starter $jarMetadataVersion, expected $ExpectedMetadataVersion. Repackage it against the declared Metadata version."
}
$localStarterJar = if ([string]::IsNullOrWhiteSpace($LocalStarterJarPath)) {
    Join-Path $starterRoot "target\praxis-config-starter-$expectedStarterVersion.jar"
} else {
    $LocalStarterJarPath
}
if (-not (Test-Path -LiteralPath $localStarterJar -PathType Leaf)) {
    throw "Local praxis-config-starter jar not found at '$localStarterJar'. Build the starter artifact before packaging the Quickstart."
}
$localStarterJar = (Resolve-Path -LiteralPath $localStarterJar).Path
$localStarterJarSha256 = (Get-FileHash -LiteralPath $localStarterJar -Algorithm SHA256).Hash.ToLowerInvariant()
$configStarterArtifactEvidence = [ordered]@{
    artifactId = "praxis-config-starter"
    version = $expectedStarterVersion
    localJarSha256 = $localStarterJarSha256
    quickstartNestedJarSha256 = [string] $jarStarterDependency.sha256
    quickstartEntry = [string] $jarStarterDependency.entry
    byteIdentical = ($localStarterJarSha256 -eq [string] $jarStarterDependency.sha256)
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
$capabilitiesEvidence = $null
$runtimeSecrets = New-EphemeralRuntimeSecrets
$streamSecret = [string] $runtimeSecrets.streamAuthTokenSecret
$resourceVersionEtagSecret = [string] $runtimeSecrets.resourceVersionEtagSecret
$resultPath = Join-Path $artifactRoot "result.json"
$sourceAuditPath = Join-Path $artifactRoot "source-audit.json"
$playwrightReportPath = Join-Path $artifactRoot "playwright-results.json"
$gateFailure = $null
$pgvectorEvidence = $null
$loopbackVerified = $false
$aiRegistryEvidence = $null
$domainCatalogEvidence = $null
$apiCatalogEvidence = $null

try {
    Write-Phase "Starting Page Builder agentic E2E gate. provider=$Provider validationMode=$ValidationMode backend=$backendUrl ui=$uiUrl artifactRoot=$artifactRoot."
    if (-not $configStarterArtifactEvidence.byteIdentical) {
        throw "Quickstart nested praxis-config-starter jar does not match the current local checkout artifact. localSha256=$($configStarterArtifactEvidence.localJarSha256) nestedSha256=$($configStarterArtifactEvidence.quickstartNestedJarSha256) Reinstall the starter and clean-package the Quickstart."
    }
    Write-Phase "Verified byte-identical praxis-config-starter artifact. sha256=$($configStarterArtifactEvidence.localJarSha256)"
    if ($null -ne (Get-ListenPid $BackendPort)) { throw "Port $BackendPort is already in use." }
    if ($null -ne (Get-ListenPid $UiPort)) { throw "Port $UiPort is already in use." }

    Write-Phase "Verifying PostgreSQL pgvector extension and vector_store schema."
    $pgvectorEvidence = Invoke-PgvectorPreflight `
        $QuickstartRoot `
        $JavaHome `
        (Join-Path $starterRoot "tools\e2e\PgvectorPreflight.java") `
        $artifactRoot

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
`$env:SPRING_AI_ENABLED = 'true'
`$env:PRAXIS_AI_RAG_VECTOR_STORE_ENABLED = 'true'
`$env:PRAXIS_API_METADATA_RAG_PUBLICATION_ENABLED = 'true'
`$env:EMBEDDING_PROVIDER = '$resolvedEmbeddingProvider'
`$env:PRAXIS_DOMAIN_CATALOG_RAG_PUBLICATION_ENABLED = 'false'
`$env:PRAXIS_DOMAIN_CATALOG_RAG_PUBLICATION_ASYNC_ENABLED = 'true'
`$env:PRAXIS_PROJECT_KNOWLEDGE_RAG_PUBLICATION_ENABLED = 'true'
`$env:PRAXIS_PROJECT_KNOWLEDGE_RAG_RETRIEVAL_ENABLED = 'true'
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

    $domainCatalogGroups = if ($ValidationMode -eq "full") {
        @("human-resources", "operations")
    } else {
        @("human-resources")
    }
    $domainCatalogEvidence = Invoke-DomainCatalogIngest `
        $backendUrl $uiUrl "desenv" "local" $domainCatalogGroups

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
        if ($isHumanResourcesFocusedMode) {
            $smokeCatalogPathPrefixes = @(
                "/api/human-resources/funcionarios",
                "/api/human-resources/departamentos",
                "/api/human-resources/folhas-pagamento",
                "/api/human-resources/vw-analytics-folha-pagamento",
                "/api/human-resources/eventos-folha",
                "/api/human-resources/historicos-salariais"
            )
            $env:API_CATALOG_PATH_PREFIXES = ($smokeCatalogPathPrefixes -join ",")
            $env:CHUNK_SIZE = "20"
            Write-Phase "Focused mode: API catalog upload scoped to $($smokeCatalogPathPrefixes.Count) human-resources path prefixes."
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
            scope = if ($ValidationMode -eq "smoke") {
                "human-resources-smoke"
            } elseif ($ValidationMode -eq "single-table") {
                "human-resources-focused"
            } else {
                "full"
            }
        }
    } finally {
        Pop-Location
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
        $env:PRAXIS_E2E_AGENTIC_EXECUTION_LANE = "live"
        $env:PRAXIS_E2E_SCENARIO_IDS = $selectedScenarioIds -join ","
        $env:PRAXIS_E2E_JSON_REPORT_PATH = $playwrightReportPath
        if ($PlaywrightTestTimeoutMs -gt 0) {
            $env:PRAXIS_E2E_TEST_TIMEOUT_MS = "$PlaywrightTestTimeoutMs"
        } else {
            Remove-Item Env:\PRAXIS_E2E_TEST_TIMEOUT_MS -ErrorAction SilentlyContinue
        }
        & cmd.exe /c "npx.cmd playwright test --config=tools/e2e/playwright/praxis-page-builder-agentic-production-like.playwright.config.ts --retries=$Retries"
        $playwrightExitCode = $LASTEXITCODE
        if (Test-Path -LiteralPath $playwrightReportPath) {
            $playwrightSummary = Get-PlaywrightSummary $playwrightReportPath
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
        Write-Phase "Playwright Page Builder validation completed."
    } finally {
        Remove-Item Env:\PRAXIS_E2E_SCENARIO_IDS -ErrorAction SilentlyContinue
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

    $modelId = if ($Provider -eq "openai") { $env:PRAXIS_AI_OPENAI_MODEL } else { $env:PRAXIS_AI_GEMINI_MODEL }
    $criticalGuardTitle = [string] $gateMatrix.evidence.criticalInterceptionGuardTest
    $criticalGuardPassed = @($playwrightSummary.tests | Where-Object {
        $_.title -eq $criticalGuardTitle -and $_.status -eq "expected"
    }).Count -eq 1
    [pscustomobject]@{
        schemaVersion = "praxis.page-builder-agentic-production-like-result/v1"
        productionLike = ($null -eq $gateFailure)
        criticalEndpointMocks = if ($criticalGuardPassed) { 0 } else { $null }
        criticalInterceptionGuard = [ordered]@{
            testTitle = $criticalGuardTitle
            passed = $criticalGuardPassed
        }
        executionLane = "live"
        validationMode = $ValidationMode
        e2ePassed = ($null -eq $gateFailure)
        provider = $Provider
        model = $modelId
        embeddingProvider = $resolvedEmbeddingProvider
        datasourceKinds = [ordered]@{ application = "postgresql"; config = "postgresql" }
        dependencyAttestation = [ordered]@{
            configStarter = $configStarterArtifactEvidence
        }
        pgvector = $pgvectorEvidence
        backendBaseUrl = $backendUrl
        uiBaseUrl = $uiUrl
        loopbackOnly = $loopbackVerified
        cleanupVerified = (
            (-not $uiProcessWasStarted -or (Get-ListenConnections $UiPort).Count -eq 0) -and
            (-not $backendProcessWasStarted -or (Get-ListenConnections $BackendPort).Count -eq 0)
        )
        artifactRoot = $artifactRoot
        sourceAudit = [ordered]@{ passed = (Test-Path -LiteralPath $sourceAuditPath); artifact = "source-audit.json" }
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
            scenarios = @($modeMatrix.scenarios)
            expectedDiscovered = [int] $modeMatrix.expectedDiscovered
            minimumExecuted = [int] $modeMatrix.minimumExecuted
            expectedSkipped = [int] $modeMatrix.expectedSkipped
            requiredPassedTests = @($modeMatrix.requiredPassedTests)
            streamProcessingTimeoutSeconds = $StreamProcessingTimeoutSeconds
            playwrightTestTimeoutMs = $PlaywrightTestTimeoutMs
            retries = $Retries
        }
        playwright = $playwrightSummary
        scenarioEvidence = @($scenarioEvidence)
        failureType = if ($null -eq $gateFailure) { $null } else { $gateFailure.Exception.GetType().FullName }
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resultPath -Encoding utf8
}

if ($null -ne $gateFailure) { throw $gateFailure }
Get-Content -LiteralPath $resultPath -Raw
