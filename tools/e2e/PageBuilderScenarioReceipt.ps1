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

    $functionalAssertions = @($Evidence.functionalAssertions)
    Assert-PraxisScenarioEvidenceCondition (
        $functionalAssertions.Count -gt 0
    ) 'Scenario functional assertions are missing.'
    foreach ($assertionId in $functionalAssertions) {
        Assert-PraxisScenarioEvidenceCondition (
            [string] $assertionId -match '^[a-z0-9][a-z0-9.-]{0,119}$'
        ) 'Scenario evidence contains a non-canonical functional assertion id.'
    }
    Assert-PraxisScenarioEvidenceCondition (
        @($functionalAssertions | Sort-Object -Unique).Count -eq $functionalAssertions.Count
    ) 'Scenario evidence contains duplicate functional assertion ids.'

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
        'terminal', 'apply', 'persistence', 'functionalAssertions', 'timingMs'
    ) 'Scenario receipt'
    Assert-PraxisScenarioEvidenceCondition (
        $Receipt.schemaVersion -eq 'praxis.page-builder-agentic-scenario-receipt/v1'
    ) "Unexpected scenario receipt schema: $($Receipt.schemaVersion)"
    Assert-PraxisScenarioEvidenceCondition (
        $Receipt.scenarioId -eq $Definition.scenarioId -and $Receipt.archetype -eq $Definition.archetype
    ) 'Scenario receipt identity diverges from the gate matrix.'
    Assert-PraxisScenarioEvidenceCondition ($RetryAttempts -ge 0) 'Scenario receipt retry count is invalid.'

    $computedAuthoringFirstPass = Assert-PraxisScenarioEvidenceCore $Receipt
    $actualAssertions = @($Receipt.functionalAssertions | Sort-Object)
    $requiredAssertions = @($Definition.requiredFunctionalAssertions | Sort-Object)
    Assert-PraxisScenarioEvidenceCondition (
        ($actualAssertions -join ',') -eq ($requiredAssertions -join ',')
    ) 'Scenario receipt functional assertions diverge from the gate matrix.'
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
        functionalAssertions = @($Receipt.functionalAssertions)
        timingMs = $Receipt.timingMs
    }
}

function Assert-PraxisPageBuilderScenarioEvidence([object] $Evidence) {
    Assert-PraxisScenarioEvidenceProperties $Evidence @(
        'schemaVersion', 'scenarioId', 'archetype', 'outcome', 'firstPassFunctional',
        'authoringFirstPass', 'playwrightRetryAttempts', 'interaction', 'terminal',
        'apply', 'persistence', 'functionalAssertions', 'timingMs'
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

function Assert-PraxisGovernedProjectionString(
    [AllowNull()][object] $Value,
    [int] $MaxLength,
    [string] $Context
) {
    if ($null -eq $Value) { return }
    Assert-PraxisScenarioEvidenceCondition ($Value -is [string]) "$Context must be a string or null."
    Assert-PraxisScenarioEvidenceCondition ($Value.Length -le $MaxLength) "$Context exceeds $MaxLength characters."
}

function Assert-PraxisGovernedProjectionStringArray(
    [object] $Value,
    [int] $MaxItems,
    [string] $Context
) {
    $items = @($Value)
    Assert-PraxisScenarioEvidenceCondition ($items.Count -le $MaxItems) "$Context exceeds $MaxItems items."
    foreach ($item in $items) {
        Assert-PraxisGovernedProjectionString $item 160 $Context
    }
    Assert-PraxisScenarioEvidenceCondition (
        @($items | Sort-Object -Unique).Count -eq $items.Count
    ) "$Context contains duplicate values."
}

function ConvertTo-PraxisGovernedStateProjection(
    [object] $Projection,
    [object] $Definition
) {
    Assert-PraxisScenarioEvidenceProperties $Projection @(
        'schemaVersion', 'scenarioId', 'observedDisposition', 'decisionDiagnostics', 'preview',
        'applyEligibility', 'blockingDiagnosticCodes', 'quickReplyIds',
        'governedRepairActionIds', 'canonicalActionPresent', 'canonicalActions',
        'applyLineage', 'execution'
    ) 'Governed state projection'
    Assert-PraxisScenarioEvidenceCondition (
        $Projection.schemaVersion -eq 'praxis.page-builder.governed-state-projection/v1'
    ) 'Unexpected governed state projection schema.'
    Assert-PraxisScenarioEvidenceCondition (
        [string] $Projection.scenarioId -eq [string] $Definition.scenarioId
    ) 'Governed state projection scenarioId diverges from its definition.'

    Assert-PraxisScenarioEvidenceProperties $Projection.observedDisposition @(
        'testObservedState', 'controllerState', 'domState'
    ) 'Governed state observedDisposition'
    foreach ($name in @('testObservedState', 'controllerState', 'domState')) {
        Assert-PraxisGovernedProjectionString $Projection.observedDisposition.$name 160 "observedDisposition.$name"
    }

    Assert-PraxisScenarioEvidenceProperties $Projection.decisionDiagnostics @(
        'status', 'reason', 'decisionValid', 'requiresReview'
    ) 'Governed state decisionDiagnostics'
    Assert-PraxisGovernedProjectionString $Projection.decisionDiagnostics.status 160 'decisionDiagnostics.status'
    Assert-PraxisGovernedProjectionString $Projection.decisionDiagnostics.reason 240 'decisionDiagnostics.reason'
    foreach ($name in @('decisionValid', 'requiresReview')) {
        $value = $Projection.decisionDiagnostics.$name
        Assert-PraxisScenarioEvidenceCondition (
            $null -eq $value -or $value -is [bool]
        ) "decisionDiagnostics.$name must be boolean or null."
    }

    Assert-PraxisScenarioEvidenceProperties $Projection.preview @('present', 'valid') 'Governed state preview'
    Assert-PraxisScenarioEvidenceCondition ($Projection.preview.present -is [bool]) 'preview.present must be boolean.'
    Assert-PraxisScenarioEvidenceCondition (
        $null -eq $Projection.preview.valid -or $Projection.preview.valid -is [bool]
    ) 'preview.valid must be boolean or null.'
    Assert-PraxisScenarioEvidenceProperties $Projection.applyEligibility @(
        'controllerCanApply', 'persistEnabled'
    ) 'Governed state applyEligibility'
    Assert-PraxisScenarioEvidenceCondition (
        $Projection.applyEligibility.controllerCanApply -is [bool] -and
        $Projection.applyEligibility.persistEnabled -is [bool]
    ) 'Governed state apply eligibility values must be boolean.'

    Assert-PraxisGovernedProjectionStringArray $Projection.blockingDiagnosticCodes 24 'blockingDiagnosticCodes'
    Assert-PraxisGovernedProjectionStringArray $Projection.quickReplyIds 12 'quickReplyIds'
    Assert-PraxisGovernedProjectionStringArray $Projection.governedRepairActionIds 12 'governedRepairActionIds'

    $semanticActionKeys = @(
        'kind', 'actionId', 'operationId', 'operationKind', 'artifactKind', 'changeKind',
        'semanticIntentClass', 'capabilityId'
    )
    $presenceActionKeys = @(
        'resourcePathPresent', 'targetPresent', 'componentTargetPresent',
        'resourceTargetPresent', 'surfaceTargetPresent', 'widgetTargetPresent',
        'decisionTargetPresent'
    )
    $allowedActionKeys = @($semanticActionKeys + $presenceActionKeys)
    $canonicalActions = @()
    foreach ($entry in @($Projection.canonicalActions)) {
        Assert-PraxisScenarioEvidenceProperties $entry @(
            'replyId', 'source', 'canonicalAction', 'canonicalActionToken'
        ) 'Governed canonical action entry'
        Assert-PraxisGovernedProjectionString $entry.replyId 160 'canonicalActions.replyId'
        Assert-PraxisScenarioEvidenceCondition (
            $entry.source -in @('reply.canonicalAction', 'reply.contextHints.canonicalAction')
        ) 'canonicalActions.source is not allowed.'
        Assert-PraxisGovernedProjectionString $entry.canonicalActionToken 160 'canonicalActions.canonicalActionToken'
        if ($null -ne $entry.canonicalActionToken) {
            Assert-PraxisScenarioEvidenceCondition (
                [string] $entry.canonicalActionToken -match '^[a-z0-9][a-z0-9._:-]{0,159}$'
            ) 'canonicalActionToken is not canonical.'
        }
        Assert-PraxisScenarioEvidenceCondition (
            $null -eq $entry.canonicalAction -or $entry.canonicalAction -is [psobject]
        ) 'canonicalActions.canonicalAction must be an object or null.'
        $actionProperties = if ($null -eq $entry.canonicalAction) {
            @()
        } else {
            @($entry.canonicalAction.PSObject.Properties)
        }
        foreach ($property in $actionProperties) {
            Assert-PraxisScenarioEvidenceCondition (
                $property.Name -in $allowedActionKeys
            ) "canonicalActions contains a non-whitelisted key: $($property.Name)"
            $value = $property.Value
            if ($property.Name -in $presenceActionKeys) {
                Assert-PraxisScenarioEvidenceCondition (
                    $value -is [bool]
                ) "canonicalActions.$($property.Name) must be boolean."
            } else {
                Assert-PraxisScenarioEvidenceCondition (
                    $null -eq $value -or $value -is [string] -or $value -is [bool] -or
                    $value -is [int] -or $value -is [long] -or $value -is [double] -or $value -is [decimal]
                ) "canonicalActions.$($property.Name) must be scalar or null."
            }
            if ($value -is [string]) {
                Assert-PraxisGovernedProjectionString $value 160 "canonicalActions.$($property.Name)"
                Assert-PraxisScenarioEvidenceCondition (
                    $value -match '^[a-z0-9][a-z0-9._:-]{0,159}$'
                ) "canonicalActions.$($property.Name) is not a canonical token."
            }
        }
        $directAction = $entry.source -eq 'reply.canonicalAction'
        Assert-PraxisScenarioEvidenceCondition (
            ($directAction -and $actionProperties.Count -gt 0 -and $null -eq $entry.canonicalActionToken) -or
            (-not $directAction -and $actionProperties.Count -eq 0 -and $null -ne $entry.canonicalActionToken)
        ) 'canonicalActions source, object and token are inconsistent.'
        if ($actionProperties.Count -gt 0 -or $null -ne $entry.canonicalActionToken) {
            $canonicalActions += [ordered]@{
                replyId = [string] $entry.replyId
                source = [string] $entry.source
                canonicalAction = $entry.canonicalAction
                canonicalActionToken = $entry.canonicalActionToken
            }
        }
    }
    Assert-PraxisScenarioEvidenceCondition ($canonicalActions.Count -le 12) 'canonicalActions exceeds 12 items.'
    Assert-PraxisScenarioEvidenceCondition (
        $Projection.canonicalActionPresent -is [bool] -and
        [bool] $Projection.canonicalActionPresent -eq ($canonicalActions.Count -gt 0)
    ) 'canonicalActionPresent diverges from sanitized canonicalActions.'

    Assert-PraxisScenarioEvidenceProperties $Projection.applyLineage @(
        'status', 'reason', 'patchAuthority', 'terminalReferencePresent'
    ) 'Governed state applyLineage'
    Assert-PraxisGovernedProjectionString $Projection.applyLineage.status 160 'applyLineage.status'
    Assert-PraxisGovernedProjectionString $Projection.applyLineage.reason 240 'applyLineage.reason'
    Assert-PraxisGovernedProjectionString $Projection.applyLineage.patchAuthority 160 'applyLineage.patchAuthority'
    Assert-PraxisScenarioEvidenceCondition (
        $Projection.applyLineage.terminalReferencePresent -is [bool]
    ) 'applyLineage.terminalReferencePresent must be boolean.'

    Assert-PraxisScenarioEvidenceProperties $Projection.execution @(
        'turnCount', 'attemptCount', 'retryCount'
    ) 'Governed state execution'
    foreach ($name in @('turnCount', 'attemptCount', 'retryCount')) {
        Assert-PraxisScenarioEvidenceCondition (
            [int] $Projection.execution.$name -ge 0
        ) "execution.$name must be non-negative."
    }
    Assert-PraxisScenarioEvidenceCondition (
        [int] $Projection.execution.retryCount -le [int] $Projection.execution.attemptCount
    ) 'execution.retryCount cannot exceed attemptCount.'

    return [ordered]@{
        scenarioId = [string] $Definition.scenarioId
        testTitle = [string] $Definition.testTitle
        attachmentName = [string] $Definition.attachmentName
        projection = [ordered]@{
            schemaVersion = [string] $Projection.schemaVersion
            scenarioId = [string] $Projection.scenarioId
            observedDisposition = $Projection.observedDisposition
            decisionDiagnostics = $Projection.decisionDiagnostics
            preview = $Projection.preview
            applyEligibility = $Projection.applyEligibility
            blockingDiagnosticCodes = @($Projection.blockingDiagnosticCodes)
            quickReplyIds = @($Projection.quickReplyIds)
            governedRepairActionIds = @($Projection.governedRepairActionIds)
            canonicalActionPresent = [bool] $Projection.canonicalActionPresent
            canonicalActions = @($canonicalActions)
            applyLineage = $Projection.applyLineage
            execution = $Projection.execution
        }
    }
}

function Assert-PraxisGovernedStateProjectionEvidence([object] $Evidence) {
    Assert-PraxisScenarioEvidenceProperties $Evidence @(
        'scenarioId', 'testTitle', 'attachmentName', 'projection'
    ) 'Governed state projection evidence'
    $definition = [pscustomobject]@{
        scenarioId = [string] $Evidence.scenarioId
        testTitle = [string] $Evidence.testTitle
        attachmentName = [string] $Evidence.attachmentName
    }
    ConvertTo-PraxisGovernedStateProjection $Evidence.projection $definition | Out-Null
}
