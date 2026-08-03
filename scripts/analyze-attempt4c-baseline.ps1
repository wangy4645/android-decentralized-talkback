# Attempt-4c baseline classifier (ADR-0022 E.21 + docs/analysis/attempt4c-baseline-checklist.md)
# Evidence classification only - not recovery PASS/FAIL, not R4 adoption verdict.

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$RemoteModule = "",
    [string]$LineageId = "L1",
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $LogDir)) {
    Write-Error "LogDir not found: $LogDir"
}

$authLog = Join-Path $LogDir "auth-stream.log"
if (-not (Test-Path $authLog)) {
    $dumps = Get-ChildItem (Join-Path $LogDir "adb-dumps") -Filter "*.log" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending
    if ($dumps.Count -ge 1) { $authLog = $dumps[0].FullName }
}
if (-not (Test-Path $authLog)) {
    Write-Error "No auth-stream.log or adb-dumps/*.log under $LogDir"
}

$lines = Get-Content $authLog -ErrorAction Stop

function Find-Lines([string]$pattern) {
    @($lines | Where-Object { $_ -match $pattern })
}

function Last-MatchField([string[]]$matchLines, [string]$fieldPattern) {
    if ($matchLines.Count -eq 0) { return $null }
    $last = $matchLines[-1]
    if ($last -match $fieldPattern) { return $Matches[1] }
    return $null
}

$superseded = Find-Lines "RECOVERY_DELIVERY_LINEAGE_SUPERSEDED"
if ($LineageId) {
    $superseded = @($superseded | Where-Object { $_ -match "offerLineageId=$LineageId" })
}
$windowClosedSuperseded = Find-Lines "RECOVERY_INGRESS_WINDOW_CLOSED.*state=CLOSED_SUPERSEDED"
if ($LineageId) {
    $windowClosedSuperseded = @($windowClosedSuperseded | Where-Object { $_ -match "offerLineageId=$LineageId" })
}
$allAbsent = Find-Lines "RECOVERY_REMOTE_INGRESS_ABSENT"
if ($LineageId) {
    $allAbsent = @($allAbsent | Where-Object { $_ -match "offerLineageId=$LineageId" })
}

# Delivery observation hygiene (INV-DELIVERY-OBS-001): three-way ABSENT class.
# Not R3 reopen - classifies evidence projection, not obligation lifecycle.
$activeDeadlineMiss = @($allAbsent | Where-Object {
    $_ -match "reason=WINDOW_DEADLINE" -and
    $_ -notmatch "recoveryAttemptId=0" -and
    $_ -notmatch "obligationGeneration=0"
})
$identityMissing = @($allAbsent | Where-Object {
    $_ -match "reason=WINDOW_DEADLINE" -and
    $_ -match "recoveryAttemptId=0" -and
    $_ -match "obligationGeneration=0"
})
$lateOnly = Find-Lines "RECOVERY_REMOTE_INGRESS_LATE_OBSERVATION_ONLY.*(LINEAGE_SUPERSEDED|CLOSED_SUPERSEDED)"
if ($LineageId) {
    $lateOnly = @($lateOnly | Where-Object { $_ -match "offerLineageId=$LineageId" })
}
$terminalAfterSupersede = @()
if ($superseded.Count -gt 0 -or $windowClosedSuperseded.Count -gt 0) {
    $terminalAfterSupersede = @($allAbsent)
}

$deliveryVerdict = "UNKNOWN"
$deliveryNotes = @()
$deliveryClass = "NONE"
if ($identityMissing.Count -gt 0) {
    $deliveryVerdict = "FAIL"
    $deliveryClass = "IDENTITY_MISSING"
    $deliveryNotes += "IDENTITY_MISSING count=$($identityMissing.Count) (synthetic 0,0 on WINDOW_DEADLINE)"
} elseif (($superseded.Count -gt 0 -or $windowClosedSuperseded.Count -gt 0) -and $allAbsent.Count -gt 0 -and $lateOnly.Count -eq 0) {
    $deliveryVerdict = "FAIL"
    $deliveryClass = "TERMINAL_AFTER_SUPERSEDE"
    $deliveryNotes += "TERMINAL_AFTER_SUPERSEDE absentAfterSupersede=$($allAbsent.Count)"
    $terminalAfterSupersede = $allAbsent
} elseif ($superseded.Count -gt 0 -and $identityMissing.Count -eq 0 -and $allAbsent.Count -eq 0) {
    $deliveryVerdict = "PASS"
    $deliveryClass = "SUPERSEDED_CLEAN"
    $deliveryNotes += "old lineage explicitly superseded (count=$($superseded.Count))"
} elseif ($activeDeadlineMiss.Count -gt 0) {
    $deliveryVerdict = "PASS"
    $deliveryClass = "ACTIVE_WINDOW_DEADLINE_MISS"
    $deliveryNotes += "ACTIVE_WINDOW_DEADLINE_MISS count=$($activeDeadlineMiss.Count) (D1-expected / active lineage)"
} elseif ($lateOnly.Count -gt 0) {
    $deliveryVerdict = "PASS"
    $deliveryClass = "TERMINAL_AFTER_SUPERSEDE"
    $deliveryNotes += "TERMINAL_AFTER_SUPERSEDE lateObservationOnly=$($lateOnly.Count) (no ABSENT fact)"
} elseif ($superseded.Count -eq 0) {
    $deliveryNotes += "no LINEAGE_SUPERSEDED for lineage=$LineageId"
}

# Back-compat alias
$phantomAbsent = $identityMissing

$admissionAccepted = Find-Lines "SUCCESSOR_ADMISSION_ACCEPTED"
$obligationOpened = Find-Lines "RECOVERY_OBLIGATION_OPENED"
$admitEpisode = Find-Lines "ADMIT_SUCCESSOR_OBLIGATION_EPISODE"
if ($RemoteModule) {
    $admissionAccepted = @($admissionAccepted | Where-Object { $_ -match "remote=$RemoteModule" })
    $obligationOpened = @($obligationOpened | Where-Object { $_ -match "remote=$RemoteModule" })
    $admitEpisode = @($admitEpisode | Where-Object { $_ -match "remote=$RemoteModule" })
}

$admissionObserved = ($admissionAccepted.Count -gt 0) -or ($obligationOpened.Count -gt 0) -or ($admitEpisode.Count -gt 0)
$admissionStatus = if ($admissionObserved) { "OBSERVED" } else { "NOT_OBSERVED" }
$proxyFacts = @()
if ($obligationOpened.Count -gt 0) { $proxyFacts += "RECOVERY_OBLIGATION_OPENED" }
if ($admitEpisode.Count -gt 0) { $proxyFacts += "ADMIT_SUCCESSOR_OBLIGATION_EPISODE" }
if ($admissionAccepted.Count -gt 0) { $proxyFacts += "SUCCESSOR_ADMISSION_ACCEPTED" }

$released = Find-Lines "DEFERRED_INTENT_RELEASED"
$held = Find-Lines "DEFERRED_INTENT_HELD|RECOVERY_COMPLETION_HELD.*domain=NEGOTIATION"
$canExecute = Find-Lines "NEGOTIATION_CAN_EXECUTE"
$iceDispatched = Find-Lines "ICE_RESTART_DISPATCHED"

$deferredVerdict = "UNKNOWN"
if ($released.Count -gt 0 -and $held.Count -eq 0) {
    $deferredVerdict = "PASS"
} elseif ($held.Count -gt 0) {
    $deferredVerdict = "UNKNOWN"
} elseif ($released.Count -eq 0 -and ($canExecute.Count -gt 0 -or $iceDispatched.Count -gt 0)) {
    $deferredVerdict = "PASS"
}

$controlFacts = Find-Lines "RECOVERY_CONTROL_RECONCILIATION_FACT"
if ($RemoteModule) {
    $controlFacts = @($controlFacts | Where-Object { $_ -match "remote=$RemoteModule" })
}
$unwiredFacts = Find-Lines "CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED"
$checkedFacts = Find-Lines "CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED"
$recovered = Find-Lines "RECOVERY_COMPLETION_DECISION.*candidate=RECOVERED|RECOVERY_COMPLETION_EVIDENCE_ACCEPTED"
if ($RemoteModule) {
    $recovered = @($recovered | Where-Object { $_ -match "remote=$RemoteModule" })
}

$disposition = Last-MatchField $controlFacts "membershipProbeDisposition=(\w+)"
$membershipConverged = Last-MatchField $controlFacts "membershipEpochConverged=(true|false)"
$controlReason = Last-MatchField $controlFacts "reason=(\S+)"

$controlCase = "NONE"
if ($disposition -eq "UNWIRED" -or ($unwiredFacts.Count -gt 0 -and $checkedFacts.Count -eq 0)) {
    $controlCase = "Case A"
} elseif ($disposition -eq "CHECKED" -and $membershipConverged -eq "true") {
    $controlCase = "Case B"
} elseif ($disposition -eq "CHECKED" -and $membershipConverged -eq "false") {
    $controlCase = "Case C"
} elseif ($controlFacts.Count -gt 0) {
    $controlCase = "MIXED"
    if ($membershipConverged -eq "false" -and $controlReason -eq "MEMBERSHIP_EPOCH_MISMATCH") {
        $controlCase = "Case C"
    } elseif ($membershipConverged -eq "true") {
        $controlCase = "Case B"
    } elseif ($unwiredFacts.Count -gt 0) {
        $controlCase = "Case A"
    }
}

$recoveredObserved = $recovered.Count -gt 0
$e18Violation = $false
$completionViolation = $false

if ($controlCase -eq "Case A" -and $recoveredObserved) { $e18Violation = $true }
if ($controlCase -eq "Case A" -and $membershipConverged -eq "true") { $e18Violation = $true }
if ($controlCase -eq "Case C" -and $recoveredObserved) { $completionViolation = $true }

$forbiddenPatterns = @("TRANSFERRED", "SUCCESSOR_OBLIGATION_ADOPTED", "inheritObligation")
$r4Leaks = @()
foreach ($pat in $forbiddenPatterns) {
    $hits = Find-Lines $pat
    foreach ($h in $hits) { $r4Leaks += $h }
}
$r4Leakage = if ($r4Leaks.Count -eq 0) { "NONE" } else { "PRESENT" }

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $LogDir "ATTEMPT4C_BASELINE_CLASSIFICATION.txt"
}


# --- Harness integrity (debug control path only; not Case A/B/C) ---
$releaseDelivered = Find-Lines "DEBUG_DISPATCH_DELIVERED.*PR52C_RELEASE_DISPATCH"
$releaseCompleted = Find-Lines "DEBUG_DISPATCH_COMPLETED action=PR52C_RELEASE_DISPATCH"
$releaseTimeout = Find-Lines "DEBUG_DISPATCH_TIMEOUT action=PR52C_RELEASE_DISPATCH"
$releaseSkipped = Find-Lines "DEBUG_DISPATCH_SKIPPED action=PR52C_RELEASE_DISPATCH"
$anrKill = Find-Lines "ANR in com.talkback.appprod|Killing .*com.talkback.appprod"
$releaseStatus = if ($releaseTimeout.Count -gt 0) { "timeout" } elseif ($releaseCompleted.Count -gt 0) { "completed" } elseif ($releaseSkipped.Count -gt 0) { "skipped" } elseif ($releaseDelivered.Count -gt 0) { "delivered" } else { "NONE" }
$processLifecycle = if ($anrKill.Count -gt 0) { "DEATH_OBSERVED" } else { "ALIVE_OR_UNKNOWN" }
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("Attempt-4c Baseline Classification")
[void]$sb.AppendLine("logDir=$LogDir")
[void]$sb.AppendLine("authLog=$authLog")
[void]$sb.AppendLine("remote=$(if ($RemoteModule) { $RemoteModule } else { 'ALL' }) lineage=$LineageId")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Delivery:")
[void]$sb.AppendLine("    $deliveryVerdict")
foreach ($n in $deliveryNotes) { [void]$sb.AppendLine("    notes: $n") }
[void]$sb.AppendLine("    class=$deliveryClass")
[void]$sb.AppendLine("    supersededCount=$($superseded.Count) activeDeadlineMiss=$($activeDeadlineMiss.Count) identityMissing=$($identityMissing.Count) terminalAfterSupersede=$($terminalAfterSupersede.Count)")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("SuccessorAdmission:")
[void]$sb.AppendLine("    $admissionStatus")
[void]$sb.AppendLine("    proxyFacts: $(if ($proxyFacts.Count) { $proxyFacts -join ', ' } else { 'none' })")
[void]$sb.AppendLine("    notes: admission only - not adoption")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("DeferredIntent:")
[void]$sb.AppendLine("    $deferredVerdict")
[void]$sb.AppendLine("    released=$($released.Count) held=$($held.Count) canExecute=$($canExecute.Count) iceDispatched=$($iceDispatched.Count)")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Control:")
[void]$sb.AppendLine("    $controlCase")
[void]$sb.AppendLine("    membershipProbeDisposition=$disposition")
[void]$sb.AppendLine("    membershipEpochConverged=$membershipConverged")
[void]$sb.AppendLine("    reason=$controlReason")
[void]$sb.AppendLine("    recoveredObserved=$recoveredObserved")
[void]$sb.AppendLine("    E18_VIOLATION: $e18Violation")
[void]$sb.AppendLine("    COMPLETION_VIOLATION: $completionViolation")
[void]$sb.AppendLine("    controlFactCount=$($controlFacts.Count)")
foreach ($l in ($controlFacts | Select-Object -Last 2)) { [void]$sb.AppendLine("    sample: $l") }
[void]$sb.AppendLine("")
[void]$sb.AppendLine("Adoption:")
[void]$sb.AppendLine("    NOT_EVALUATED (R4-impl pending)")
[void]$sb.AppendLine("    ADOPTION_STATUS=NOT_EVALUATED")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("HarnessIntegrity:")
[void]$sb.AppendLine("    PR52C_RELEASE_DISPATCH: $releaseStatus")
[void]$sb.AppendLine("    ProcessLifecycle: $processLifecycle")
[void]$sb.AppendLine("    note: debug control path only - not recovery Case A/B/C")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("R4_LEAKAGE:")
[void]$sb.AppendLine("    $r4Leakage")
foreach ($l in ($r4Leaks | Select-Object -First 5)) { [void]$sb.AppendLine("    $l") }
[void]$sb.AppendLine("")
[void]$sb.AppendLine("discipline: no single PASS/FAIL - see docs/analysis/attempt4c-baseline-checklist.md")

$text = $sb.ToString()
$text | Set-Content -Path $ReportPath -Encoding UTF8
Write-Output $text

if ($e18Violation -or $completionViolation -or $deliveryVerdict -eq "FAIL") { exit 1 }
exit 0