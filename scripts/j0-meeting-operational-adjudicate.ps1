# Meeting Profile01 J0 — Profile01 Meeting operational correctness adjudication.
#
# Joins on: conferenceId + sessionId + mediaKeyEpoch + creationFactDigest
# Digest fence: package.membershipFactDigest == pending CREATION.factDigest
#
# Usage:
#   .\talkback\scripts\j0-meeting-operational-adjudicate.ps1 -LogDir <dir>
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Get-ModuleLog {
    param([string]$Module)
    foreach ($name in @("$Module-logcat.txt", "$Module-talkback.log", "$Module-logcat-dump.txt")) {
        $p = Join-Path $LogDir $name
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Parse-Field {
    param([string]$Line, [string]$Key)
    if ([string]::IsNullOrWhiteSpace($Line)) { return $null }
    if ($Line -match "$Key=([^ ]+)") { return $Matches[1] }
    return $null
}

function Get-LogTimestamp {
    param([string]$Line)
    if ($Line -match '(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})') { return $Matches[1] }
    if ($Line -match '^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?)') { return $Matches[1] }
    return $null
}

function Compare-LogTimestamp {
    param([string]$Left, [string]$Right)
    if (-not $Left -or -not $Right) { return $null }
    return [string]::CompareOrdinal($Left, $Right)
}

function Read-KvFile {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*#' -or $line -eq "") { return }
        if ($line -match '^([^=]+)=(.*)$') {
            $map[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $map
}

function Get-Lines {
    param(
        [string]$Path,
        [string]$Pattern,
        [switch]$SimpleMatch
    )
    if (-not $Path -or -not (Test-Path $Path)) { return @() }
    if ($SimpleMatch) {
        return @(Select-String -Path $Path -Pattern $Pattern -SimpleMatch | ForEach-Object { $_.Line })
    }
    return @(Select-String -Path $Path -Pattern $Pattern | ForEach-Object { $_.Line })
}

function Test-FactTypeLine {
    param([string]$Line, [int]$FactType)
    if ([string]::IsNullOrWhiteSpace($Line)) { return $false }
    return $Line -match "factType=$FactType(?:\s|[^0-9]|$)"
}

function New-Result {
    param(
        [string]$Id,
        [ValidateSet("PASS", "FAIL", "INVALID")]
        [string]$Status,
        [string]$Evidence = ""
    )
    return [pscustomobject]@{ Id = $Id; Status = $Status; Evidence = $Evidence }
}

$linesOut = New-Object System.Collections.Generic.List[string]
function Add-Out([string]$s) { [void]$linesOut.Add($s) }

$m01Log = Get-ModuleLog "M01"
$m02Log = Get-ModuleLog "M02"
$meta = Read-KvFile (Join-Path $LogDir "RUN_META.txt")
$pre = Read-KvFile (Join-Path $LogDir "PRECONDITIONS.txt")
$joinGate = Read-KvFile (Join-Path $LogDir "JOIN_GATE.txt")
$groupGate = Read-KvFile (Join-Path $LogDir "GROUP_GATE.txt")
$inviteJoinGate = Read-KvFile (Join-Path $LogDir "INVITE_JOIN_GATE.txt")
$neg = Read-KvFile (Join-Path $LogDir "NEGATIVE_FIXTURE.txt")

$invalid = New-Object System.Collections.Generic.List[string]
$fail = New-Object System.Collections.Generic.List[string]
$j0 = @{}

Add-Out "MEETING J0 OPERATIONAL ADJUDICATION"
Add-Out "log_dir=$LogDir"
Add-Out "identity_keys=conferenceId,sessionId,mediaKeyEpoch,creationFactDigest,membershipFactDigest"
Add-Out "continuation_gate=product_auto_supplementRegistry_put_to_P1E"
Add-Out ""

function Write-InvalidAndExit {
    param([string]$Verdict, [string]$Reason, [string]$Next)
    Add-Out "VERDICT=$Verdict"
    Add-Out "reason=$Reason"
    Add-Out "J0_FIELD=NOT_TESTED"
    Add-Out "J0_CHAIN=NOT_TESTED"
    Add-Out "P1-C_status=UNCHANGED_CLOSED"
    Add-Out "Next=$Next"
    $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
    Write-Host ($linesOut -join "`n")
    exit 2
}

# --- PRE-G ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict']) reason=$($groupGate['reason'])"
    Add-Out "GROUP_GATE PRE_G1=$($groupGate['PRE_G1_m01NoActiveGroup']) PRE_G2=$($groupGate['PRE_G2_m02NoActiveGroup']) PRE_G3=$($groupGate['PRE_G3_noGrpBoundOrPending']) PRE_G4=$($groupGate['PRE_G4_createAllowed'])"
    Add-Out ""
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_LEAVE_HARNESS_RERUN_J0_ONLY"
    }
}

# --- PRE-I ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    Add-Out "INVITE_JOIN_GATE PRE_I1=$($inviteJoinGate['PRE_I1_peerInviteForH']) PRE_I6=$($inviteJoinGate['PRE_I6_peerBindH'])"
    Add-Out ""
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_HARNESS_RERUN_J0_ONLY"
    }
}

# --- PRE-J ---
$hostSessionId = $joinGate["hostSessionId"]
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId peerSessionId=$($joinGate['peerSessionId'])"
    Add-Out "JOIN_GATE PRE_J5=$($joinGate['PRE_J5_sameSessionId']) PRE_J6=$($joinGate['PRE_J6_packageAdjudicationAllowed'])"
    Add-Out ""
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_HARNESS_RERUN_J0_ONLY"
    }
    if (-not $hostSessionId) { $hostSessionId = $inviteJoinGate["hostSessionId"] }
}

if (-not $m01Log -or -not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_device_logs" "FIX_CAPTURE_RERUN_J0_ONLY"
}

# --- J0 forbids C-F7 negative injection ---
$negObs = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_NEGATIVE_FIXTURE .*injected=true"
if ($negObs.Count -gt 0 -or $neg["injected"] -eq "true") {
    Write-InvalidAndExit "INVALID_CAPTURE" "cf7_negative_fixture_present_j0_forbidden" "RERUN_J0_WITHOUT_C-F7"
}

# --- Forbidden test drain path ---
$f1 = @(Get-Lines $m01Log "drainIncompleteAfterSupplementReadyForTest" -SimpleMatch)
$f2 = @(Get-Lines $m02Log "drainIncompleteAfterSupplementReadyForTest" -SimpleMatch)
$forbiddenTest = @($f1 + $f2) | Where-Object { $_ }
if ($forbiddenTest.Count -gt 0) {
    $j0["J0-6"] = New-Result "J0-6" "FAIL" "forbidden_test_drain_path"
    $fail.Add("forbidden_test_drain") | Out-Null
}

# --- J0-1 Host CREATION originated ---
$creationEmitLines = Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT" -SimpleMatch
$creationEmitted = @(
    $creationEmitLines | Where-Object {
        (Test-FactTypeLine $_ 1) -and
        (Parse-Field $_ "outcome") -eq "EMITTED" -and
        (Parse-Field $_ "factDigest")
    }
)
$anchorLine = $null
if ($hostSessionId) {
    $creationEmitted | ForEach-Object {
        if ((Parse-Field $_ "session") -eq $hostSessionId) { $anchorLine = $_ }
    }
}
if (-not $anchorLine -and $creationEmitted.Count -gt 0) { $anchorLine = $creationEmitted[0] }

$anchorSessionId = if ($anchorLine) { Parse-Field $anchorLine "session" } else { $null }
$anchorConferenceId = if ($anchorLine) { Parse-Field $anchorLine "conferenceId" } else { $null }
$anchorDigest = if ($anchorLine) { Parse-Field $anchorLine "factDigest" } else { $null }

if (-not $anchorLine) {
    $j0["J0-1"] = New-Result "J0-1" "FAIL" "no_PROFILE01_ORIGIN_EMIT_factType=1_outcome=EMITTED"
    $fail.Add("J0-1_no_creation_emit") | Out-Null
} elseif ($hostSessionId -and $anchorSessionId -ne $hostSessionId) {
    $j0["J0-1"] = New-Result "J0-1" "FAIL" "creation_session_mismatch anchor=$anchorSessionId join=$hostSessionId"
    $fail.Add("J0-1_session_mismatch") | Out-Null
} else {
    $j0["J0-1"] = New-Result "J0-1" "PASS" "conferenceId=$anchorConferenceId factDigest=$anchorDigest session=$anchorSessionId"
}

# --- J0-2 Pre-bind retain ---
$retainLines = Get-Lines $m02Log "WIRE_INGRESS .*DEFERRED_NO_SESSION_RETAINED"
$retainCreation = @(
    $retainLines | Where-Object {
        (Test-FactTypeLine $_ 1) -and
        (-not $anchorSessionId -or (Parse-Field $_ "session") -eq $anchorSessionId)
    }
)
$bindMarkers = Get-Lines $m02Log "INVITE_ACCEPT|SESSION_BIND|acceptIncomingMeeting"
$bindTs = $null
if ($bindMarkers.Count -gt 0) { $bindTs = Get-LogTimestamp $bindMarkers[0] }

if ($retainCreation.Count -eq 0) {
    $j0["J0-2"] = New-Result "J0-2" "FAIL" "no_DEFERRED_NO_SESSION_RETAINED_factType=1"
    $fail.Add("J0-2_no_prebind_retain") | Out-Null
} else {
    $retainLine = $retainCreation[0]
    $retainTs = Get-LogTimestamp $retainLine
    if ($bindTs -and $retainTs -and (Compare-LogTimestamp $retainTs $bindTs) -gt 0) {
        $j0["J0-2"] = New-Result "J0-2" "FAIL" "retain_after_bind retainTs=$retainTs bindTs=$bindTs"
        $fail.Add("J0-2_retain_after_bind") | Out-Null
    } else {
        $j0["J0-2"] = New-Result "J0-2" "PASS" "DEFERRED_NO_SESSION_RETAINED factType=1 session=$anchorSessionId"
    }
}

# --- J0-3 Same-session join (gate-delegated) ---
if ($joinGate.Count -eq 0 -and $inviteJoinGate.Count -eq 0) {
    $j0["J0-3"] = New-Result "J0-3" "INVALID" "missing_JOIN_GATE_and_INVITE_JOIN_GATE"
    $invalid.Add("missing_join_gate_files") | Out-Null
} elseif ($joinGate["PRE_J5_sameSessionId"] -ne "PASS" -and $joinGate["peerSessionId"] -ne $hostSessionId) {
    $j0["J0-3"] = New-Result "J0-3" "INVALID" "same_session_not_proven"
    $invalid.Add("J0-3_same_session") | Out-Null
} else {
    $j0["J0-3"] = New-Result "J0-3" "PASS" "PRE-G/I/J delegated hostSessionId=$hostSessionId"
}

# --- J0-4 INCOMPLETE_PENDING after bind ---
$incompleteRetain = Get-Lines $m02Log "INCOMPLETE_RETAIN "
$matchingRetain = @(
    $incompleteRetain | Where-Object {
        (Parse-Field $_ "conference") -eq $anchorConferenceId -and
        (Parse-Field $_ "digest") -eq $anchorDigest -and
        (-not $anchorSessionId -or (Parse-Field $_ "session") -eq $anchorSessionId)
    }
)
$incompletePending = Get-Lines $m02Log "WIRE_INGRESS" -SimpleMatch | Where-Object {
    (Test-FactTypeLine $_ 1) -and ($_ -match "INCOMPLETE_PENDING") -and ($_ -match "MISSING_MEDIA_SUPPLEMENT")
}
$matchingPending = @(
    $incompletePending | Where-Object {
        (-not $anchorSessionId -or (Parse-Field $_ "session") -eq $anchorSessionId)
    }
)
$anchorMediaKeyEpoch = $null
if ($matchingRetain.Count -gt 0) {
    $anchorMediaKeyEpoch = Parse-Field $matchingRetain[0] "mediaKeyEpoch"
}

if ($matchingRetain.Count -eq 0) {
    $j0["J0-4"] = New-Result "J0-4" "FAIL" "no_INCOMPLETE_RETAIN_for_anchor_digest"
    $fail.Add("J0-4_no_incomplete_retain") | Out-Null
} elseif ($matchingPending.Count -eq 0) {
    $j0["J0-4"] = New-Result "J0-4" "FAIL" "no_INCOMPLETE_PENDING_MISSING_MEDIA_SUPPLEMENT"
    $fail.Add("J0-4_no_incomplete_pending") | Out-Null
} else {
    $j0["J0-4"] = New-Result "J0-4" "PASS" "INCOMPLETE_RETAIN+INCOMPLETE_PENDING digest=$anchorDigest mediaKeyEpoch=$anchorMediaKeyEpoch"
}

# --- J0-5 Package prerequisite (continuation, not P1-C re-proof) ---
$postBindObs = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_POST_BIND_REPUBLISH "
$postBindOrigin = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_ORIGIN .*POST_BIND_EMITTED"
$postBindCoord = Get-Lines $m01Log "PROFILE01_PACKAGE_POST_BIND_REPUBLISH "
$productReasonPattern = 'reason=(admission_ready_|peer_media_connected|membership_accepted_no_media)'
$harnessReasonPattern = 'reason=field_harness_post_bind|P1C_REPUBLISH_AFTER_BIND'
$productCoordHits = @($postBindCoord | Where-Object { $_ -match $productReasonPattern })
$harnessCoordHits = @($postBindCoord | Where-Object { $_ -match $harnessReasonPattern })
$productPostBind = ($postBindObs.Count -gt 0) -or ($postBindOrigin.Count -gt 0)
$harnessOnly = ($harnessCoordHits.Count -gt 0) -and ($productCoordHits.Count -eq 0) -and ($postBindObs.Count -eq 0) -and ($postBindOrigin.Count -eq 0)

$ingestLines = Get-Lines $m02Log "PROFILE01_MEDIA_KEY_INGEST .*outcome=APPLIED"
$readyLines = Get-Lines $m02Log "PROFILE01_MEDIA_SUPPLEMENT .*state=READY"
$matchingIngest = @(
    $ingestLines | Where-Object {
        (Parse-Field $_ "conferenceId") -eq $anchorConferenceId -and
        (-not $anchorMediaKeyEpoch -or (Parse-Field $_ "mediaKeyEpoch") -eq $anchorMediaKeyEpoch)
    }
)
$matchingReady = @(
    $readyLines | Where-Object {
        (Parse-Field $_ "conferenceId") -eq $anchorConferenceId -and
        (-not $anchorMediaKeyEpoch -or (Parse-Field $_ "mediaKeyEpoch") -eq $anchorMediaKeyEpoch)
    }
)

# Digest fence from package lines
$packageDigestLines = Get-Lines $m01Log "PROFILE01_MEDIA_KEY_PACKAGE_(ORIGIN|POST_BIND_REPUBLISH) "
$packageDigest = $null
foreach ($pl in $packageDigestLines) {
    $d = Parse-Field $pl "membershipFactDigest"
    if ($d) { $packageDigest = $d; break }
}
$digestFencePass = ($packageDigest -and $anchorDigest -and $packageDigest -eq $anchorDigest)

if ($harnessOnly) {
    $j0["J0-5"] = New-Result "J0-5" "FAIL" "harness_only_post_bind_no_product_EMITTED"
    $fail.Add("J0-5_harness_only") | Out-Null
} elseif (-not $productPostBind) {
    $j0["J0-5"] = New-Result "J0-5" "FAIL" "product_POST_BIND_EMITTED_absent"
    $fail.Add("J0-5_no_post_bind") | Out-Null
} elseif ($matchingIngest.Count -eq 0 -or $matchingReady.Count -eq 0) {
    $j0["J0-5"] = New-Result "J0-5" "FAIL" "APPLIED_or_READY_missing_for_anchor"
    $fail.Add("J0-5_applied_ready") | Out-Null
} elseif (-not $digestFencePass) {
    $j0["J0-5"] = New-Result "J0-5" "FAIL" "digest_fence package=$packageDigest creation=$anchorDigest"
    $fail.Add("J0-5_digest_fence") | Out-Null
} else {
    $j0["J0-5"] = New-Result "J0-5" "PASS" "POST_BIND+APPLIED+READY digest_fence=PASS productAuto=True"
}

# --- J0-6 P1-E automatic drain ---
$drainLines = Get-Lines $m02Log "SESSION_INCOMPLETE_DRAIN "
$matchingDrain = @(
    $drainLines | Where-Object {
        (Parse-Field $_ "conference") -eq $anchorConferenceId -and
        (-not $anchorMediaKeyEpoch -or (Parse-Field $_ "mediaKeyEpoch") -eq $anchorMediaKeyEpoch) -and
        [int](Parse-Field $_ "count") -ge 1
    }
)
$readyTs = if ($matchingReady.Count) { Get-LogTimestamp $matchingReady[0] } else { $null }
$drainTs = if ($matchingDrain.Count) { Get-LogTimestamp $matchingDrain[0] } else { $null }

if (-not $j0["J0-6"] -or $j0["J0-6"].Status -ne "FAIL") {
    if ($matchingDrain.Count -eq 0) {
        $j0["J0-6"] = New-Result "J0-6" "FAIL" "no_SESSION_INCOMPLETE_DRAIN_for_anchor"
        $fail.Add("J0-6_no_drain") | Out-Null
    } elseif ($readyTs -and $drainTs -and (Compare-LogTimestamp $drainTs $readyTs) -lt 0) {
        $j0["J0-6"] = New-Result "J0-6" "FAIL" "drain_before_READY drainTs=$drainTs readyTs=$readyTs"
        $fail.Add("J0-6_drain_before_ready") | Out-Null
    } else {
        $cnt = Parse-Field $matchingDrain[0] "count"
        $j0["J0-6"] = New-Result "J0-6" "PASS" "SESSION_INCOMPLETE_DRAIN count=$cnt product_auto=True"
    }
}

# --- J0-7 CREATION APPLIED ---
$appliedCreation = Get-Lines $m02Log "WIRE_INGRESS" -SimpleMatch | Where-Object {
    (Test-FactTypeLine $_ 1) -and (Parse-Field $_ "outcome") -eq "APPLIED"
}
$matchingApplied = @(
    $appliedCreation | Where-Object {
        (-not $anchorSessionId -or (Parse-Field $_ "session") -eq $anchorSessionId)
    }
)
$appliedTs = if ($matchingApplied.Count) { Get-LogTimestamp $matchingApplied[-1] } else { $null }

$retainAfterApplied = @()
if ($appliedTs) {
    $retainAfterApplied = @(
        $incompleteRetain | Where-Object {
            (Parse-Field $_ "digest") -eq $anchorDigest -and
            (Get-LogTimestamp $_) -and
            (Compare-LogTimestamp (Get-LogTimestamp $_) $appliedTs) -gt 0
        }
    )
}

if ($matchingApplied.Count -eq 0) {
    $j0["J0-7"] = New-Result "J0-7" "FAIL" "no_WIRE_INGRESS_factType=1_outcome=APPLIED"
    $fail.Add("J0-7_no_applied") | Out-Null
} elseif ($drainTs -and $appliedTs -and (Compare-LogTimestamp $appliedTs $drainTs) -lt 0) {
    $j0["J0-7"] = New-Result "J0-7" "FAIL" "APPLIED_before_drain appliedTs=$appliedTs drainTs=$drainTs"
    $fail.Add("J0-7_applied_before_drain") | Out-Null
} elseif ($retainAfterApplied.Count -gt 0) {
    $j0["J0-7"] = New-Result "J0-7" "FAIL" "INCOMPLETE_RETAIN_after_APPLIED"
    $fail.Add("J0-7_retain_after_applied") | Out-Null
} else {
    $j0["J0-7"] = New-Result "J0-7" "PASS" "WIRE_INGRESS factType=1 outcome=APPLIED session=$anchorSessionId"
}

# --- Emit ---
Add-Out "ANCHOR conferenceId=$anchorConferenceId sessionId=$anchorSessionId mediaKeyEpoch=$anchorMediaKeyEpoch creationFactDigest=$anchorDigest packageDigest=$packageDigest"
Add-Out "CREATION_EMIT_LINE=$anchorLine"
Add-Out "INCOMPLETE_RETAIN_LINE=$(if ($matchingRetain.Count) { $matchingRetain[0] } else { '' })"
Add-Out "DRAIN_LINE=$(if ($matchingDrain.Count) { $matchingDrain[0] } else { '' })"
Add-Out "CREATION_APPLIED_LINE=$(if ($matchingApplied.Count) { $matchingApplied[-1] } else { '' })"
Add-Out "digest_fence=$(if ($digestFencePass) { 'PASS' } else { 'FAIL' })"
Add-Out ""

foreach ($id in @("J0-1", "J0-2", "J0-3", "J0-4", "J0-5", "J0-6", "J0-7")) {
    if ($j0[$id]) {
        Add-Out ("{0} {1}  {2}" -f $j0[$id].Id, $j0[$id].Status, $j0[$id].Evidence)
    }
}
Add-Out ""

$anyInvalid = ($invalid.Count -gt 0) -or (@($j0.Values | Where-Object { $_.Status -eq "INVALID" }).Count -gt 0)
$anyFail = ($fail.Count -gt 0) -or (@($j0.Values | Where-Object { $_.Status -eq "FAIL" }).Count -gt 0)
$allPass = (@($j0.Values | Where-Object { $_.Status -ne "PASS" }).Count -eq 0) -and ($j0.Count -eq 7)

if ($anyInvalid) {
    $reasons = @($invalid) + @($j0.Values | Where-Object { $_.Status -eq "INVALID" } | ForEach-Object { "$($_.Id)_$($_.Evidence)" })
    Add-Out "VERDICT=INVALID_J0_CHAIN"
    Add-Out ("reason=" + (($reasons | Select-Object -Unique) -join ","))
    Add-Out "J0_FIELD=NOT_TESTED"
    Add-Out "J0_CHAIN=NOT_TESTED"
    Add-Out "P1-C_status=UNCHANGED_CLOSED"
    Add-Out "Next=FIX_HARNESS_OR_CAPTURE_RERUN_J0_ONLY"
    $exitCode = 2
} elseif ($anyFail -or -not $allPass) {
    $reasons = @($fail) + @($j0.Values | Where-Object { $_.Status -eq "FAIL" } | ForEach-Object { "$($_.Id)_$($_.Evidence)" })
    Add-Out "VERDICT=FAIL_J0_CHAIN"
    Add-Out ("reason=" + (($reasons | Select-Object -Unique) -join ","))
    Add-Out "Next=RCA_J0_CONTINUATION_HOLD_PHASE_A"
    Add-Out "P1-C_status=UNCHANGED_CLOSED"
    $exitCode = 1
} else {
    Add-Out "VERDICT=PASS"
    Add-Out "identity_fence=PASS"
    Add-Out "continuation_path=product_auto"
    Add-Out "Meeting_J0=CLOSED / FIELD-PROVEN"
    Add-Out "Profile01_Meeting_chain=CLOSED / FIELD-PROVEN"
    Add-Out "Next=PHASE_A_ELIGIBLE_NEXT_GATE"
    Add-Out "note=CREATION_APPLIED != media_cutover; ADR-0056 KEEP"
    $exitCode = 0
}

$adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
Write-Host ($linesOut -join "`n")
exit $exitCode
