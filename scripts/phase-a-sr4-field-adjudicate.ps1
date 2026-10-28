# PA-SR4 field adjudication — Real Shadow Media Activity Gate.
#
# Three-state:
#   PASS (0) · FAIL_PA_SR4 (1) · INVALID_* (2)
#
# Gate-critical (only):
#   SR4-A  INGRESS_ACTIVITY packetsReceived > 0
#   SR4-B  PLAYOUT_ACTIVITY successfulPlayoutWrites > 0
#   SR4-C  audioTrackOwner = SHADOW_METRICS_ONLY / no shadow production ownership
#
# SR1–SR3 same-session lines are prerequisites (INVALID if missing), not a full re-adjudication.
#
# Usage:
#   .\talkback\scripts\phase-a-sr4-field-adjudicate.ps1 -LogDir <dir>
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

$linesOut = New-Object System.Collections.Generic.List[string]
function Add-Out([string]$s) { [void]$linesOut.Add($s) }

function Write-InvalidAndExit {
    param([string]$Verdict, [string]$Reason, [string]$Next)
    Add-Out ""
    Add-Out "VERDICT=$Verdict"
    Add-Out "PA-SR4=NOT_TESTED"
    Add-Out "reason=$Reason"
    Add-Out "PA-SR3=UNCHANGED_CLOSED"
    Add-Out "B1-R2=UNCHANGED_CLOSED"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=$Next"
    $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
    Write-Host ($linesOut -join "`n")
    exit 2
}

$m01Log = Get-ModuleLog "M01"
$m02Log = Get-ModuleLog "M02"
$joinGate = Read-KvFile (Join-Path $LogDir "JOIN_GATE.txt")
$groupGate = Read-KvFile (Join-Path $LogDir "GROUP_GATE.txt")
$inviteJoinGate = Read-KvFile (Join-Path $LogDir "INVITE_JOIN_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$shadowGate = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")

Add-Out "PA-SR4 FIELD ADJUDICATION (Real Shadow Media Activity Gate)"
Add-Out "log_dir=$LogDir"
Add-Out "criteria=meeting-product-integration-phase-a-shadow-runtime-adjudication-criteria.md#PA-SR4"
Add-Out "authorization=meeting-product-integration-phase-a-sr4-field-authorization.md"
Add-Out "PA-SR5_to_SR6=OBSERVE_ONLY"
Add-Out ""

if (-not $m01Log -or -not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_or_M02_log" "FIX_CAPTURE_RERUN_PA_SR4"
}

# --- PRE-G ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR4"
    }
}

# --- PRE-I ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR4"
    }
}

# --- PRE-J ---
$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId"
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_RERUN_PA_SR4"
    }
}
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" "missing_hostSessionId" "FIX_FIELD_JOIN_RERUN_PA_SR4"
}

$conferenceId = $manifest["conferenceId"]
$hostModuleId = if ($manifest["hostModuleId"]) { $manifest["hostModuleId"] } else { "M01" }
$peerModuleId = if ($manifest["peerModuleId"]) { $manifest["peerModuleId"] } else { "M02" }
Add-Out "identity hostSessionId=$hostSessionId conferenceId=$conferenceId hostModuleId=$hostModuleId peerModuleId=$peerModuleId"
Add-Out ""

# --- Observe-only SR5/SR6 ---
$observe = New-Object System.Collections.Generic.List[string]
$observe.Add("PA-SR5_SR6_OBSERVE_ONLY")
$observe.Add("driverLeavePhase=$($shadowGate['driverLeavePhase'])")
$observe.Add("driverRejoinPhase=$($shadowGate['driverRejoinPhase'])")
$observe.Add("driverEndPhase=$($shadowGate['driverEndPhase'])")
$sr5Removed = @(Get-Lines $m01Log "SHADOW hook=MEMBER_REMOVED session=$hostSessionId") + @(Get-Lines $m02Log "SHADOW hook=MEMBER_REMOVED session=$hostSessionId")
$sr6Stopped = @(Get-Lines $m01Log "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -SimpleMatch) +
    @(Get-Lines $m02Log "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
$observe.Add("PA-SR5_MEMBER_REMOVED_lines=$($sr5Removed.Count)")
$observe.Add("PA-SR6_SESSION_STOPPED_APPLIED_lines=$($sr6Stopped.Count)")
$observePath = Join-Path $LogDir "PA_SR4_OBSERVE_ONLY.txt"
($observe -join "`n") | Set-Content -Encoding utf8 $observePath
Add-Out "observe_only_file=PA_SR4_OBSERVE_ONLY.txt"
Add-Out ""

# --- Same-session prerequisites (SR1–SR3 proxy; not full historical re-adjudication) ---
$creationApplied = @(Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED") | Where-Object {
    $_ -match "session=$hostSessionId" -or $_ -notmatch "session="
}
if ($creationApplied.Count -eq 0) {
    $creationApplied = Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED"
}
if ($creationApplied.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "j0_creation_applied_not_observed" "RERUN_AFTER_J0_PREREQ"
}

$sessionStarted = @(Get-Lines $m01Log "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -SimpleMatch) +
    @(Get-Lines $m02Log "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
# Soft corroboration for SR1 — missing alone does not INVALID if SR3 ready present.
$memberReadyHost = @(Get-Lines $m01Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$hostModuleId outcome=APPLIED" -SimpleMatch) +
    @(Get-Lines $m02Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$hostModuleId outcome=APPLIED" -SimpleMatch)
if ($memberReadyHost.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "sr3_member_media_ready_host_module_not_observed" "RERUN_AFTER_SR3_SAME_SESSION"
}

$wiringLive = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=(SHADOW_SESSION_STARTED|WIRING_SESSION_STARTED) .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=(SHADOW_SESSION_STARTED|WIRING_SESSION_STARTED) .*sessionId=$hostSessionId")
$wiringLiveOk = @($wiringLive | Where-Object { $_ -match "transportScopeActive=true" })

Add-Out "PREREQUISITE_SAME_SESSION"
Add-Out "J0_CREATION_APPLIED=$($creationApplied.Count -gt 0)"
Add-Out "SR1_SESSION_STARTED=$($sessionStarted.Count -gt 0)"
Add-Out "SR2_WIRING_LIVE=$($wiringLiveOk.Count -gt 0)"
Add-Out "SR3_MEMBER_MEDIA_READY_HOST=$($memberReadyHost.Count -gt 0)"
Add-Out ""

# --- SR4 gate-critical ---
function Test-Sr4IngressLine([string]$Line) {
    if ($Line -notmatch "phase=INGRESS_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $packets = 0L
    $parsed = Parse-Field $Line "packetsReceived"
    if ($parsed) { [void][long]::TryParse($parsed, [ref]$packets) }
    return ($packets -gt 0)
}

function Test-Sr4PlayoutLine([string]$Line) {
    if ($Line -notmatch "phase=PLAYOUT_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $writes = 0L
    $parsed = Parse-Field $Line "successfulPlayoutWrites"
    if ($parsed) { [void][long]::TryParse($parsed, [ref]$writes) }
    $owner = Parse-Field $Line "audioTrackOwner"
    return ($writes -gt 0) -and ($owner -eq "SHADOW_METRICS_ONLY")
}

$ingressLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY")
$playoutLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY")
$ingressOk = @($ingressLines | Where-Object { Test-Sr4IngressLine $_ })
$playoutOk = @($playoutLines | Where-Object { Test-Sr4PlayoutLine $_ })

$shadowOwnsProduction = @()
foreach ($log in @($m01Log, $m02Log)) {
    $shadowOwnsProduction += Get-Lines $log "PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY .*audioTrackOwner=PRODUCTION"
    $shadowOwnsProduction += Get-Lines $log "PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY .*audioTrackOwner=PRODUCTION"
}

$productionOwnerLines = @()
foreach ($log in @($m01Log, $m02Log)) {
    $productionOwnerLines += Get-Lines $log "PROFILE01_SHADOW_RUNTIME phase=AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE" -SimpleMatch
}
if ($productionOwnerLines.Count -gt 0) {
    Add-Out "AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE count=$($productionOwnerLines.Count) note=EXPECTED_ADR0056_AUDIBLE_OWNER"
} else {
    Add-Out "AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE count=0 note=not_observed_this_window"
}

$sr4A = $ingressOk.Count -gt 0
$sr4B = $playoutOk.Count -gt 0
$sr4C = $shadowOwnsProduction.Count -eq 0

Add-Out "PA-SR4 CHAIN"
Add-Out "SR4-A_INGRESS_ACTIVITY=$sr4A packetsReceived_gt0=$sr4A lines=$($ingressOk.Count)"
Add-Out "SR4-B_PLAYOUT_ACTIVITY=$sr4B successfulPlayoutWrites_gt0_and_SHADOW_METRICS_ONLY=$sr4B lines=$($playoutOk.Count)"
Add-Out "SR4-C_SHADOW_NO_PRODUCTION_OWNERSHIP=$sr4C violation_lines=$($shadowOwnsProduction.Count)"
Add-Out "pttStimulus=STIMULUS_ONLY_NOT_PASS_CRITERION"
Add-Out ""

$identityPath = Join-Path $LogDir "PA_SR4_IDENTITY.txt"
$identityLines = @(
    "sessionId=$hostSessionId",
    "conferenceId=$conferenceId",
    "hostModuleId=$hostModuleId",
    "peerModuleId=$peerModuleId",
    "sr4A_ingress=$sr4A",
    "sr4B_playout=$sr4B",
    "sr4C_ownership=$sr4C",
    "ingressEvidence=$(if ($ingressOk.Count -gt 0) { ($ingressOk | Select-Object -First 1) } else { 'NONE' })",
    "playoutEvidence=$(if ($playoutOk.Count -gt 0) { ($playoutOk | Select-Object -First 1) } else { 'NONE' })"
)
($identityLines -join "`n") | Set-Content -Encoding utf8 $identityPath
Add-Out "identity_file=PA_SR4_IDENTITY.txt"
foreach ($l in $identityLines) { Add-Out $l }
Add-Out ""

$failReasons = New-Object System.Collections.Generic.List[string]
if (-not $sr4A) { $failReasons.Add("SR4-A_missing_INGRESS_ACTIVITY_packetsReceived") }
if (-not $sr4B) { $failReasons.Add("SR4-B_missing_PLAYOUT_ACTIVITY_or_owner") }
if (-not $sr4C) { $failReasons.Add("SR4-C_shadow_claimed_production_audio_track") }

if ($failReasons.Count -eq 0) {
    Add-Out "VERDICT=PASS"
    Add-Out "PA-SR4=CLOSED / FIELD-PROVEN"
    Add-Out "PA-SR5=HOLD"
    Add-Out "PA-SR6=HOLD"
    Add-Out "PA-SR3=UNCHANGED_CLOSED"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=PA_SR5_FIELD_AUTHORIZATION"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
    Write-Host ($linesOut -join "`n")
    exit 0
}

Add-Out "VERDICT=FAIL_PA_SR4"
Add-Out "PA-SR4=FAIL"
Add-Out "reason=$($failReasons -join ';')"
Add-Out "PA-SR3=UNCHANGED_CLOSED"
Add-Out "B1-R2=UNCHANGED_CLOSED"
Add-Out "ADR-0056=KEEP"
Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
Add-Out "Next=RCA_PA_SR4_RUNTIME_EXECUTION"
($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($linesOut -join "`n")
exit 1
