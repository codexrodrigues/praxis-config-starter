function Assert-PraxisScenarioEvidenceCondition([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-PraxisScenarioEvidenceProperties(
    [object] $Value,
    [string[]] $Expected,
    [string] $Context
) {
    Assert-PraxisScenarioEvidenceCondition ($null -ne $Value) "$Context is missing."
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expectedSorted = @($Expected | Sort-Object)
    Assert-PraxisScenarioEvidenceCondition `
        (($actual -join ',') -eq ($expectedSorted -join ',')) `
        "$Context has unexpected properties. expected=$($expectedSorted -join ',') actual=$($actual -join ',')"
}

function Assert-PraxisScenarioEvidenceCore([object] $Evidence) {
    Assert-PraxisScenarioEvidenceProperties $Evidence.interaction @(
        'initialPromptCount', 'totalTurnCount', 'clarificationQuickReplyCount',
        'governedRevisionCount', 'correctiveTypedPromptCount'
    ) 'Scenario interaction evidence'
    $interaction = $Evidence.interaction
    Assert-PraxisScenarioEvidenceCondition (
        [int] $interaction.initialPromptCount -eq 1 -and [int] $interaction.totalTurnCount -ge 1 -and
        [int] $interaction.clarificationQuickReplyCount -ge 0 -and [int] $interaction.governedRevisionCount -ge 0 -and
        [int] $interaction.correctiveTypedPromptCount -ge 0
    ) 'Scenario interaction counts are invalid.'
    Assert-PraxisScenarioEvidenceCondition (
        [int] $interaction.totalTurnCount -eq (
            [int] $interaction.initialPromptCount +
            [int] $interaction.clarificationQuickReplyCount +
            [int] $interaction.governedRevisionCount +
            [int] $interaction.correctiveTypedPromptCount
        )
    ) 'Scenario interaction turn accounting is inconsistent.'

    Assert-PraxisScenarioEvidenceProperties `
        $Evidence.terminal `
        @('outcome', 'transport', 'blockingDiagnosticCodes') `
        'Scenario terminal evidence'
    Assert-PraxisScenarioEvidenceCondition (
        $Evidence.terminal.outcome -eq 'applicable' -and $Evidence.terminal.transport -eq 'stream'
    ) 'Scenario terminal authority is not applicable over stream.'
    $blockingCodes = @($Evidence.terminal.blockingDiagnosticCodes)
    foreach ($code in $blockingCodes) {
        Assert-PraxisScenarioEvidenceCondition (
            [string] $code -match '^[a-z0-9][a-z0-9._:-]{0,119}$'
        ) 'Scenario evidence contains a non-canonical blocking diagnostic code.'
    }
    Assert-PraxisScenarioEvidenceCondition (
        @($blockingCodes | Sort-Object -Unique).Count -eq $blockingCodes.Count
    ) 'Scenario evidence contains duplicate blocking diagnostic codes.'

    Assert-PraxisScenarioEvidenceProperties $Evidence.persistence @(
        'version', 'etagPresent', 'persistedPayloadSha256', 'reloadPayloadSha256',
        'reloadMatchesPersisted', 'reloadEtagMatches'
    ) 'Scenario persistence evidence'
    $persistence = $Evidence.persistence
    Assert-PraxisScenarioEvidenceCondition (
        [int] $persistence.version -ge 1 -and $persistence.etagPresent -eq $true -and
        [string] $persistence.persistedPayloadSha256 -match '^[0-9a-f]{64}$' -and
        [string] $persistence.reloadPayloadSha256 -match '^[0-9a-f]{64}$' -and
        $persistence.persistedPayloadSha256 -eq $persistence.reloadPayloadSha256 -and
        $persistence.reloadMatchesPersisted -eq $true -and $persistence.reloadEtagMatches -eq $true
    ) 'Scenario persistence evidence is incomplete or inconsistent.'

    Assert-PraxisScenarioEvidenceProperties $Evidence.runtime @(
        'masterRendered', 'detailRendered', 'selectionPropagated', 'actionDiscoveryStatus',
        'capabilitiesDiscoveryStatus', 'commandStatus', 'duplicateCommandStatus',
        'refreshObserved', 'reloadRendered'
    ) 'Scenario runtime evidence'
    $runtime = $Evidence.runtime
    Assert-PraxisScenarioEvidenceCondition (
        $runtime.masterRendered -eq $true -and $runtime.detailRendered -eq $true -and
        $runtime.selectionPropagated -eq $true -and [int] $runtime.actionDiscoveryStatus -eq 200 -and
        [int] $runtime.capabilitiesDiscoveryStatus -eq 200 -and [int] $runtime.commandStatus -eq 200 -and
        [int] $runtime.duplicateCommandStatus -eq 409 -and $runtime.refreshObserved -eq $true -and
        $runtime.reloadRendered -eq $true
    ) 'Scenario runtime evidence is incomplete.'

    Assert-PraxisScenarioEvidenceProperties $Evidence.timingMs @(
        'authoringToApplicable', 'applyAndReadback', 'runtimeAndCommand', 'reload', 'total'
    ) 'Scenario timing evidence'
    foreach ($property in @('authoringToApplicable', 'applyAndReadback', 'runtimeAndCommand', 'reload', 'total')) {
        Assert-PraxisScenarioEvidenceCondition (
            [int64] $Evidence.timingMs.$property -ge 0
        ) "Scenario timing $property is invalid."
    }

    return [int] $interaction.totalTurnCount -eq 1 -and
        [int] $interaction.clarificationQuickReplyCount -eq 0 -and
        [int] $interaction.governedRevisionCount -eq 0 -and
        [int] $interaction.correctiveTypedPromptCount -eq 0 -and
        $blockingCodes.Count -eq 0
}

function ConvertTo-PraxisPageBuilderScenarioEvidence(
    [object] $Receipt,
    [object] $Definition,
    [int] $RetryAttempts
) {
    Assert-PraxisScenarioEvidenceProperties $Receipt @(
        'schemaVersion', 'scenarioId', 'archetype', 'authoringFirstPass', 'interaction',
        'terminal', 'persistence', 'runtime', 'timingMs'
    ) 'Scenario receipt'
    Assert-PraxisScenarioEvidenceCondition (
        $Receipt.schemaVersion -eq 'praxis.page-builder-agentic-scenario-receipt/v1'
    ) "Unexpected scenario receipt schema: $($Receipt.schemaVersion)"
    Assert-PraxisScenarioEvidenceCondition (
        $Receipt.scenarioId -eq $Definition.scenarioId -and $Receipt.archetype -eq $Definition.archetype
    ) 'Scenario receipt identity diverges from the gate matrix.'
    Assert-PraxisScenarioEvidenceCondition ($RetryAttempts -ge 0) 'Scenario receipt retry count is invalid.'

    $computedAuthoringFirstPass = Assert-PraxisScenarioEvidenceCore $Receipt
    Assert-PraxisScenarioEvidenceCondition (
        [bool] $Receipt.authoringFirstPass -eq $computedAuthoringFirstPass
    ) 'Scenario receipt authoringFirstPass diverges from its interaction evidence.'
    $firstPassFunctional = $computedAuthoringFirstPass -and $RetryAttempts -eq 0

    return [ordered]@{
        schemaVersion = [string] $Receipt.schemaVersion
        scenarioId = [string] $Receipt.scenarioId
        archetype = [string] $Receipt.archetype
        outcome = if ($firstPassFunctional) { 'first-pass' } else { 'eventual-pass' }
        firstPassFunctional = $firstPassFunctional
        authoringFirstPass = $computedAuthoringFirstPass
        playwrightRetryAttempts = $RetryAttempts
        interaction = $Receipt.interaction
        terminal = $Receipt.terminal
        persistence = $Receipt.persistence
        runtime = $Receipt.runtime
        timingMs = $Receipt.timingMs
    }
}

function Assert-PraxisPageBuilderScenarioEvidence([object] $Evidence) {
    Assert-PraxisScenarioEvidenceProperties $Evidence @(
        'schemaVersion', 'scenarioId', 'archetype', 'outcome', 'firstPassFunctional',
        'authoringFirstPass', 'playwrightRetryAttempts', 'interaction', 'terminal',
        'persistence', 'runtime', 'timingMs'
    ) 'Scenario evidence'
    Assert-PraxisScenarioEvidenceCondition (
        $Evidence.schemaVersion -eq 'praxis.page-builder-agentic-scenario-receipt/v1'
    ) 'Unexpected scenario evidence schema.'
    Assert-PraxisScenarioEvidenceCondition (
        -not [string]::IsNullOrWhiteSpace([string] $Evidence.scenarioId) -and
        -not [string]::IsNullOrWhiteSpace([string] $Evidence.archetype)
    ) 'Scenario evidence identity is missing.'
    Assert-PraxisScenarioEvidenceCondition (
        [int] $Evidence.playwrightRetryAttempts -ge 0
    ) 'Scenario evidence retry count is invalid.'

    $computedAuthoringFirstPass = Assert-PraxisScenarioEvidenceCore $Evidence
    Assert-PraxisScenarioEvidenceCondition (
        [bool] $Evidence.authoringFirstPass -eq $computedAuthoringFirstPass
    ) 'Scenario authoring first-pass flag is inconsistent.'
    $computedFirstPass = $computedAuthoringFirstPass -and [int] $Evidence.playwrightRetryAttempts -eq 0
    Assert-PraxisScenarioEvidenceCondition (
        [bool] $Evidence.firstPassFunctional -eq $computedFirstPass
    ) 'Scenario first-pass result is inconsistent.'
    $expectedOutcome = if ($computedFirstPass) { 'first-pass' } else { 'eventual-pass' }
    Assert-PraxisScenarioEvidenceCondition (
        [string] $Evidence.outcome -eq $expectedOutcome
    ) 'Scenario outcome is inconsistent.'
}
