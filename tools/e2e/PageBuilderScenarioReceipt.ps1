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
        'governedRevisionCount', 'correctiveTypedPromptCount', 'deterministicRepairCount'
    ) 'Scenario interaction evidence'
    $interaction = $Evidence.interaction
    Assert-PraxisScenarioEvidenceCondition (
        [int] $interaction.initialPromptCount -eq 1 -and [int] $interaction.totalTurnCount -ge 1 -and
        [int] $interaction.clarificationQuickReplyCount -ge 0 -and [int] $interaction.governedRevisionCount -ge 0 -and
        [int] $interaction.correctiveTypedPromptCount -ge 0 -and [int] $interaction.deterministicRepairCount -ge 0
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
        @('outcome', 'transport', 'blockingDiagnosticCodes', 'referencePresent', 'backendPatchAuthority') `
        'Scenario terminal evidence'
    Assert-PraxisScenarioEvidenceCondition (
        $Evidence.terminal.outcome -eq 'applicable' -and $Evidence.terminal.transport -eq 'stream' -and
        $Evidence.terminal.referencePresent -eq $true -and $Evidence.terminal.backendPatchAuthority -eq $true
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

    Assert-PraxisScenarioEvidenceProperties $Evidence.apply @(
        'terminalReferenceMatched', 'streamIdMatched', 'resultEventIdMatched',
        'payloadSha256', 'matchesPersistedPayload'
    ) 'Scenario apply evidence'
    Assert-PraxisScenarioEvidenceCondition (
        $Evidence.apply.terminalReferenceMatched -eq $true -and
        $Evidence.apply.streamIdMatched -eq $true -and
        $Evidence.apply.resultEventIdMatched -eq $true -and
        [string] $Evidence.apply.payloadSha256 -match '^[0-9a-f]{64}$' -and
        $Evidence.apply.matchesPersistedPayload -eq $true
    ) 'Scenario terminal/apply lineage evidence is incomplete.'

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
        $Evidence.apply.payloadSha256 -eq $persistence.persistedPayloadSha256 -and
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
        'firstUsefulStatus', 'firstApplicableTerminal', 'applyCompleted',
        'runtimeFunctional', 'reloadCompleted', 'total'
    ) 'Scenario timing evidence'
    foreach ($property in @(
        'firstUsefulStatus', 'firstApplicableTerminal', 'applyCompleted',
        'runtimeFunctional', 'reloadCompleted', 'total'
    )) {
        Assert-PraxisScenarioEvidenceCondition (
            [int64] $Evidence.timingMs.$property -ge 0
        ) "Scenario timing $property is invalid."
    }
    Assert-PraxisScenarioEvidenceCondition (
        [int64] $Evidence.timingMs.firstUsefulStatus -le [int64] $Evidence.timingMs.firstApplicableTerminal -and
        [int64] $Evidence.timingMs.firstApplicableTerminal -le [int64] $Evidence.timingMs.applyCompleted -and
        [int64] $Evidence.timingMs.applyCompleted -le [int64] $Evidence.timingMs.runtimeFunctional -and
        [int64] $Evidence.timingMs.runtimeFunctional -le [int64] $Evidence.timingMs.reloadCompleted -and
        [int64] $Evidence.timingMs.reloadCompleted -eq [int64] $Evidence.timingMs.total
    ) 'Scenario timing milestones are not monotonic.'

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
        'terminal', 'apply', 'persistence', 'runtime', 'timingMs'
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
        apply = $Receipt.apply
        persistence = $Receipt.persistence
        runtime = $Receipt.runtime
        timingMs = $Receipt.timingMs
    }
}

function Assert-PraxisPageBuilderScenarioEvidence([object] $Evidence) {
    Assert-PraxisScenarioEvidenceProperties $Evidence @(
        'schemaVersion', 'scenarioId', 'archetype', 'outcome', 'firstPassFunctional',
        'authoringFirstPass', 'playwrightRetryAttempts', 'interaction', 'terminal',
        'apply', 'persistence', 'runtime', 'timingMs'
    ) 'Scenario evidence'
    Assert-PraxisScenarioEvidenceCondition (
        $Evidence.schemaVersion -eq 'praxis.page-builder-agentic-scenario-receipt/v1'
    ) 'Unexpected scenario evidence schema.'
    Assert-PraxisScenarioEvidenceCondition (
        [string] $Evidence.scenarioId -match '^[a-z0-9][a-z0-9-]{0,79}$' -and
        [string] $Evidence.archetype -match '^[a-z0-9][a-z0-9-]{0,79}$'
    ) 'Scenario evidence identity is missing or non-canonical.'
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
