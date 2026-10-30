# Phase A Shadow Runtime — field adjudication (PA-SR1…PA-SR6).
#
# Three-state:
#   PASS (0) · INVALID_* (2) · FAIL_SHADOW_RUNTIME (1)
#
# FAIL_SHADOW_RUNTIME != J0 regression (frozen).
#
# Usage:
#   .\talkback\scripts\phase-a-shadow-runtime-adjudicate.ps1 -LogDir <dir>
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
$joinGate = Read-KvFile (Join-Path $LogDir "JOIN_GATE.txt")
$groupGate = Read-KvFile (Join-Path $LogDir "GROUP_GATE.txt")
$inviteJoinGate = Read-KvFile (Join-Path $LogDir "INVITE_JOIN_GATE.txt")
$shadowGate = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")

$invalid = New-Object System.Collections.Generic.List[string]
$fail = New-Object System.Collections.Generic.List[string]
$sr = @{}

Add-Out "PHASE A SHADOW RUNTIME ADJUDICATION"
Add-Out "log_dir=$LogDir"
Add-Out "criteria=meeting-product-integration-phase-a-shadow-runtime-adjudication-criteria.md"
Add-Out "fail_shadow_runtime_ne_j0_regression=FROZEN"
Add-Out ""

function Write-InvalidAndExit {
    param([string]$Verdict, [string]$Reason, [string]$Next)
    Add-Out "VERDICT=$Verdict"
    Add-Out "reason=$Reason"
    Add-Out "SHADOW_RUNTIME=NOT_TESTED"
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "Profile01_Meeting_chain=UNCHANGED_CLOSED"
    Add-Out "Next=$Next"
    $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
    Write-Host ($linesOut -join "`n")
    exit 2
}

if (-not $m01Log -or -not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_or_M02_log" "FIX_CAPTURE_RERUN_SHADOW_RUNTIME"
}

# --- PRE-G ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_SHADOW_RUNTIME"
    }
}

# --- PRE-I ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_SHADOW_RUNTIME"
    }
}

# --- PRE-J ---
$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId"
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_RERUN_SHADOW_RUNTIME"
    }
}
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" "missing_hostSessionId" "FIX_FIELD_JOIN_RERUN_SHADOW_RUNTIME"
}

$conferenceId = $manifest["conferenceId"]
$mediaKeyEpoch = $manifest["mediaKeyEpoch"]
$peerModuleId = if ($manifest["peerModuleId"]) { $manifest["peerModuleId"] } else { "M02" }

Add-Out "identity hostSessionId=$hostSessionId conferenceId=$conferenceId mediaKeyEpoch=$mediaKeyEpoch peerModuleId=$peerModuleId"
Add-Out ""

# --- AudioTrack ownership fence ---
# PRODUCTION_ACTIVE = ADR-0056 audible path normally holds AudioTrack (EXPECTED in Phase A).
# Hard FAIL only if shadow playout claims production ownership (not SHADOW_METRICS_ONLY).
$productionOwnerLines = @()
foreach ($log in @($m01Log, $m02Log)) {
    $productionOwnerLines += Get-Lines $log "PROFILE01_SHADOW_RUNTIME phase=AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE" -SimpleMatch
}
if ($productionOwnerLines.Count -gt 0) {
    Add-Out "AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE count=$($productionOwnerLines.Count) note=EXPECTED_ADR0056_AUDIBLE_OWNER"
} else {
    Add-Out "AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE count=0 note=not_observed_this_window"
}

$shadowOwnsProduction = @()
foreach ($log in @($m01Log, $m02Log)) {
    $shadowOwnsProduction += Get-Lines $log "PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY .*audioTrackOwner=PRODUCTION"
    $shadowOwnsProduction += Get-Lines $log "PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY .*audioTrackOwner=PRODUCTION"
}
if ($shadowOwnsProduction.Count -gt 0) {
    $sr["PA-SR4"] = New-Result "PA-SR4" "FAIL" "shadow_claimed_production_audio_track_ownership"
    $fail.Add("AUDIO_TRACK_FENCE_SHADOW_OWNS_PRODUCTION")
}

# --- J0 prerequisite (not re-litigated) ---
$creationApplied = @(Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED") | Where-Object { $_ -match "session=$hostSessionId" -or $_ -notmatch "session=" }
if ($creationApplied.Count -eq 0) {
    $creationApplied = Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED"
}
if ($creationApplied.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "creation_applied_not_observed" "RERUN_AFTER_J0_PREREQ_OR_FIX_DRIVER"
}

# --- PA-SR1 ---
$sr1Pattern = "SHADOW hook=SESSION_STARTED session=$hostSessionId\b.*\boutcome=APPLIED"
$sr1ShadowStarted = Get-Lines $m01Log $sr1Pattern
if ($sr1ShadowStarted.Count -eq 0) {
    $sr1ShadowStarted = Get-Lines $m02Log $sr1Pattern
}
if ($sr1ShadowStarted.Count -gt 0 -and $creationApplied.Count -gt 0) {
    $sr["PA-SR1"] = New-Result "PA-SR1" "PASS" "creation_applied_and_session_started"
} else {
    $sr["PA-SR1"] = New-Result "PA-SR1" "FAIL" "missing_SESSION_STARTED_APPLIED_or_creation"
    $fail.Add("PA-SR1")
}

# --- PA-SR2 ---
$sr2Lines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId") +
    @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_SESSION_STARTED .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_SESSION_STARTED .*sessionId=$hostSessionId")
$sr2Live = @($sr2Lines | Where-Object { $_ -match "transportScopeActive=true" })
if ($sr2Live.Count -gt 0) {
    $sr["PA-SR2"] = New-Result "PA-SR2" "PASS" "wiring_live_transport_scope_active"
} else {
    $sr["PA-SR2"] = New-Result "PA-SR2" "FAIL" "missing_wiring_live_snapshot"
    $fail.Add("PA-SR2")
}

# --- PA-SR3 ---
$sr3Hook = @(Get-Lines $m01Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$peerModuleId outcome=APPLIED" -SimpleMatch) +
    @(Get-Lines $m02Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$peerModuleId outcome=APPLIED" -SimpleMatch)
$sr3Obs = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_MEMBER_INSTALLED .*sessionId=$hostSessionId") +
    @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_INSTALLED .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_MEMBER_INSTALLED .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_INSTALLED .*sessionId=$hostSessionId")
$sr3Admitted = @($sr3Obs | Where-Object { $_ -match "admittedCount=[1-9]" -or $_ -match "catalogEntries=[1-9]" })
if ($sr3Hook.Count -gt 0 -and $sr3Admitted.Count -gt 0) {
    $sr["PA-SR3"] = New-Result "PA-SR3" "PASS" "member_media_ready_and_catalog"
} else {
    $sr["PA-SR3"] = New-Result "PA-SR3" "FAIL" "missing_member_install_or_catalog"
    $fail.Add("PA-SR3")
}

# --- PA-SR4 (gate-critical) ---
function Test-IngressActivityLine([string]$Line) {
    if ($Line -notmatch "phase=INGRESS_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $packets = [long](Parse-Field $Line "packetsReceived")
    $ingress = [long](Parse-Field $Line "ingressAccepted")
    $jitter = [int](Parse-Field $Line "activeJitterSources")
    $decoders = [int](Parse-Field $Line "liveDecoders")
    return (($packets -gt 0) -or ($ingress -gt 0)) -and (($jitter -ge 1) -or ($decoders -ge 1))
}

function Test-PlayoutActivityLine([string]$Line) {
    if ($Line -notmatch "phase=PLAYOUT_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $writes = [long](Parse-Field $Line "successfulPlayoutWrites")
    $owner = Parse-Field $Line "audioTrackOwner"
    return ($writes -gt 0) -and ($owner -eq "SHADOW_METRICS_ONLY")
}

$ingressLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY") + @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY")
$playoutLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY") + @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY")
$ingressOk = @($ingressLines | Where-Object { Test-IngressActivityLine $_ })
$playoutOk = @($playoutLines | Where-Object { Test-PlayoutActivityLine $_ })

if ($ingressOk.Count -gt 0 -and $playoutOk.Count -gt 0 -and $shadowOwnsProduction.Count -eq 0) {
    $sr["PA-SR4"] = New-Result "PA-SR4" "PASS" "ingress_and_playout_runtime_activity"
} elseif ($shadowOwnsProduction.Count -eq 0) {
    if (-not $sr.ContainsKey("PA-SR4")) {
        $sr["PA-SR4"] = New-Result "PA-SR4" "FAIL" "missing_ingress_or_playout_runtime_activity"
        $fail.Add("PA-SR4")
    }
}

# --- PA-SR5 ---
if ($shadowGate["driverLeavePhase"] -eq "SKIPPED" -or $shadowGate["driverRejoinPhase"] -eq "SKIPPED") {
    $sr["PA-SR5"] = New-Result "PA-SR5" "INVALID" "driver_did_not_run_leave_rejoin"
    $invalid.Add("PA-SR5_DRIVER")
} else {
    $sr5Removed = @(Get-Lines $m01Log "SHADOW hook=MEMBER_REMOVED session=$hostSessionId module=$peerModuleId outcome=APPLIED" -SimpleMatch) +
        @(Get-Lines $m02Log "SHADOW hook=MEMBER_REMOVED session=$hostSessionId module=$peerModuleId outcome=APPLIED" -SimpleMatch)
    $sr5Revoked = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_REVOKED .*sessionId=$hostSessionId") +
        @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_REVOKED .*sessionId=$hostSessionId")
    $sr5Reinstalled = @(Get-Lines $m01Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$peerModuleId outcome=APPLIED" -SimpleMatch) +
        @(Get-Lines $m02Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$peerModuleId outcome=APPLIED" -SimpleMatch)
    if ($sr5Removed.Count -gt 0 -and $sr5Revoked.Count -gt 0 -and $sr5Reinstalled.Count -ge 2) {
        $sr["PA-SR5"] = New-Result "PA-SR5" "PASS" "leave_revoke_rejoin"
    } else {
        $sr["PA-SR5"] = New-Result "PA-SR5" "FAIL" "leave_rejoin_chain_incomplete"
        $fail.Add("PA-SR5")
    }
}

# --- PA-SR6 ---
if ($shadowGate["driverEndPhase"] -eq "SKIPPED") {
    $sr["PA-SR6"] = New-Result "PA-SR6" "INVALID" "driver_did_not_run_meeting_end"
    $invalid.Add("PA-SR6_DRIVER")
} else {
    $sr6Stopped = @(Get-Lines $m01Log "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -SimpleMatch) +
        @(Get-Lines $m02Log "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
    $teardownLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId") +
        @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId")
    $teardownEmpty = @($teardownLines | Where-Object {
            $_ -match "transportScopeActive=false" -and
            $_ -match "catalogEntries=0" -and
            $_ -match "admittedCount=0" -and
            $_ -match "activeJitterSources=0" -and
            $_ -match "liveDecoders=0"
        })
    if ($sr6Stopped.Count -gt 0 -and $teardownEmpty.Count -gt 0) {
        $sr["PA-SR6"] = New-Result "PA-SR6" "PASS" "session_stopped_and_teardown_empty"
    } else {
        $sr["PA-SR6"] = New-Result "PA-SR6" "FAIL" "missing_stop_or_teardown_empty"
        $fail.Add("PA-SR6")
    }
}

Add-Out "PA-SR RESULTS"
foreach ($id in @("PA-SR1", "PA-SR2", "PA-SR3", "PA-SR4", "PA-SR5", "PA-SR6")) {
    if ($sr.ContainsKey($id)) {
        Add-Out "$id=$($sr[$id].Status) evidence=$($sr[$id].Evidence)"
    } else {
        Add-Out "$id=MISSING"
    }
}
Add-Out ""

$anyInvalid = ($invalid.Count -gt 0) -or (@($sr.Values | Where-Object { $_.Status -eq "INVALID" }).Count -gt 0)
$anyFail = ($fail.Count -gt 0) -or (@($sr.Values | Where-Object { $_.Status -eq "FAIL" }).Count -gt 0)
$allPass = (@($sr.Values | Where-Object { $_.Status -ne "PASS" }).Count -eq 0) -and ($sr.Count -eq 6)

if ($anyInvalid) {
    $reasons = @($invalid) + @($sr.Values | Where-Object { $_.Status -eq "INVALID" } | ForEach-Object { "$($_.Id)_$($_.Evidence)" })
    Add-Out "VERDICT=INVALID_SHADOW_RUNTIME"
    Add-Out ("reason=" + (($reasons | Select-Object -Unique) -join ","))
    Add-Out "SHADOW_RUNTIME=NOT_TESTED"
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "Next=FIX_DRIVER_OR_CAPTURE_RERUN_SHADOW_RUNTIME"
    $exitCode = 2
} elseif ($anyFail -or -not $allPass) {
    $reasons = @($fail) + @($sr.Values | Where-Object { $_.Status -eq "FAIL" } | ForEach-Object { "$($_.Id)_$($_.Evidence)" })
    Add-Out "VERDICT=FAIL_SHADOW_RUNTIME"
    Add-Out ("reason=" + (($reasons | Select-Object -Unique) -join ","))
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "Profile01_Meeting_chain=UNCHANGED_CLOSED"
    Add-Out "Next=RCA_SHADOW_RUNTIME_ONLY"
    $exitCode = 1
} else {
    Add-Out "VERDICT=PASS"
    Add-Out "Phase_A_Shadow_Runtime=CLOSED / FIELD-PROVEN"
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=PHASE_A_ROLLUP_ELIGIBLE"
    $exitCode = 0
}

$adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
Write-Host ($linesOut -join "`n")
exit $exitCode
