# PA-SR4-TX field adjudication — Host Shadow Multicast Transmit Gate.
#
# Three-state:
#   PASS_PA_SR4_TX (0) · FAIL_PA_SR4_TX (1) · INVALID_* (2)
#
# Core chain (TX establish vs TX packets separated):
#   Host HOST_SESSION_PROJECTED (APPLIED|ALREADY_LIVE)     — necessary prereq
#   → SHADOW_SESSION_STARTED / SESSION_STARTED live       — necessary prereq
#   → PTT stimulus (captureON)                            — necessary for FAIL validity
#   → SHADOW_TX_ACTIVITY packetsSent>0                    — ONLY PASS criterion
#
# HOST_SESSION_PROJECTED / session live MUST NOT substitute for packetsSent evidence.
# PASS_PA_SR4_TX does NOT close PA-SR4 full / authorize RX / PLAY.
#
# Usage:
#   .\talkback\scripts\phase-a-sr4-tx-field-adjudicate.ps1 -LogDir <dir>
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
    Add-Out "PA-SR4-TX=NOT_TESTED"
    Add-Out "PA-SR4=VALID_FAIL_UNCHANGED"
    Add-Out "reason=$Reason"
    Add-Out "PR-PA-SR4-RX=NOT_AUTHORIZED"
    Add-Out "PLAY=HOLD"
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

Add-Out "PA-SR4-TX FIELD ADJUDICATION (Host Shadow Multicast Transmit Gate)"
Add-Out "log_dir=$LogDir"
Add-Out "run_card=meeting-product-integration-phase-a-sr4-tx-field-run-card.md"
Add-Out "authorization=meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-implementation-authorization.md"
Add-Out "PA-SR4_full=NOT_CLOSED_BY_THIS_GATE"
Add-Out "RX=NOT_AUTHORIZED"
Add-Out "PLAY=HOLD"
Add-Out ""

if (-not $m01Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_log" "FIX_CAPTURE_RERUN_PA_SR4_TX"
}
if (-not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M02_log" "FIX_CAPTURE_RERUN_PA_SR4_TX"
}

# --- PRE-G ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR4_TX"
    }
}

# --- PRE-I ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR4_TX"
    }
}

# --- PRE-J ---
$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId"
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_RERUN_PA_SR4_TX"
    }
}
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" "missing_hostSessionId" "FIX_FIELD_JOIN_RERUN_PA_SR4_TX"
}

$conferenceId = $manifest["conferenceId"]
$hostModuleId = if ($manifest["hostModuleId"]) { $manifest["hostModuleId"] } else { "M01" }
$peerModuleId = if ($manifest["peerModuleId"]) { $manifest["peerModuleId"] } else { "M02" }
Add-Out "identity hostSessionId=$hostSessionId conferenceId=$conferenceId hostModuleId=$hostModuleId peerModuleId=$peerModuleId"
Add-Out ""

# --- Observe-only (ingress/playout/SR5/SR6) — never PASS criteria ---
$observe = New-Object System.Collections.Generic.List[string]
$observe.Add("PA_SR4_TX_OBSERVE_ONLY")
$observe.Add("driverLeavePhase=$($shadowGate['driverLeavePhase'])")
$observe.Add("driverRejoinPhase=$($shadowGate['driverRejoinPhase'])")
$observe.Add("driverEndPhase=$($shadowGate['driverEndPhase'])")
$ingressObs = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY") + @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY")
$playoutObs = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY") + @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY")
$observe.Add("INGRESS_ACTIVITY_lines=$($ingressObs.Count)_OBSERVE_ONLY")
$observe.Add("PLAYOUT_ACTIVITY_lines=$($playoutObs.Count)_OBSERVE_ONLY")
$observePath = Join-Path $LogDir "PA_SR4_TX_OBSERVE_ONLY.txt"
($observe -join "`n") | Set-Content -Encoding utf8 $observePath
Add-Out "observe_only_file=PA_SR4_TX_OBSERVE_ONLY.txt"
Add-Out ""

# --- J0 / same-session prerequisites ---
$creationApplied = @(Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED") | Where-Object {
    $_ -match "session=$hostSessionId" -or $_ -notmatch "session="
}
if ($creationApplied.Count -eq 0) {
    $creationApplied = Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED"
}
if ($creationApplied.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "j0_creation_applied_not_observed" "RERUN_AFTER_J0_PREREQ"
}

# Host projection (TX-A) — necessary for valid TX field; not packets evidence
$hostProjected = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=HOST_SESSION_PROJECTED .*sessionId=$hostSessionId") |
    Where-Object { $_ -match "outcome=(APPLIED|ALREADY_LIVE)\b" }
$hostProjectedDeferred = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=HOST_SESSION_PROJECTED .*sessionId=$hostSessionId") |
    Where-Object { $_ -match "outcome=DEFERRED" }

# Shadow session live on host
$sessionStartedHook = @(Get-Lines $m01Log "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
$shadowSessionPhase = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId")
$wiringStarted = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_SESSION_STARTED .*sessionId=$hostSessionId")
$wiringLiveOk = @(($shadowSessionPhase + $wiringStarted) | Where-Object { $_ -match "transportScopeActive=true" })
$hostSessionLive = ($sessionStartedHook.Count -gt 0) -or ($shadowSessionPhase.Count -gt 0) -or ($wiringLiveOk.Count -gt 0)

# SR3 soft corroboration (host SOURCE install) — missing alone does not INVALID if host session live + projected
$memberReadyHost = @(Get-Lines $m01Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$hostModuleId outcome=APPLIED" -SimpleMatch) +
    @(Get-Lines $m02Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$hostModuleId outcome=APPLIED" -SimpleMatch)

Add-Out "PREREQUISITE_SAME_SESSION"
Add-Out "J0_CREATION_APPLIED=$($creationApplied.Count -gt 0)"
Add-Out "HOST_SESSION_PROJECTED=$($hostProjected.Count -gt 0) deferred_lines=$($hostProjectedDeferred.Count)"
Add-Out "HOST_SHADOW_SESSION_LIVE=$hostSessionLive"
Add-Out "SR1_SESSION_STARTED_HOOK=$($sessionStartedHook.Count -gt 0)"
Add-Out "SR2_WIRING_LIVE=$($wiringLiveOk.Count -gt 0)"
Add-Out "SR3_MEMBER_MEDIA_READY_HOST=$($memberReadyHost.Count -gt 0)_SOFT"
Add-Out ""

if ($hostProjected.Count -eq 0 -and -not $hostSessionLive) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "host_shadow_session_not_established" "FIX_HOST_PROJECTION_OR_SESSION_STARTED_RERUN_PA_SR4_TX"
}
if ($hostProjected.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "host_session_projected_not_observed" "FIX_HOST_PROJECTION_RERUN_PA_SR4_TX"
}
if (-not $hostSessionLive) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "host_shadow_session_not_live" "FIX_SHADOW_SESSION_STARTED_RERUN_PA_SR4_TX"
}

# --- PTT stimulus (required for FAIL validity; not PASS criterion) ---
$captureOn = @(Get-Lines $m01Log "PTT_TIMING event=captureON session=$hostSessionId" -SimpleMatch)
if ($captureOn.Count -eq 0) {
    $captureOn = @(Get-Lines $m01Log "PTT_TIMING .*event=captureON .*session=$hostSessionId")
}
$pttStimulusOk = $captureOn.Count -gt 0
if (-not $pttStimulusOk -and $shadowGate["obsCaptureOn"] -eq "observed") {
    $pttStimulusOk = $true
}

# --- TX gate-critical ---
function Test-TxActivityLine([string]$Line) {
    if ($Line -notmatch "phase=SHADOW_TX_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $packets = 0L
    $parsed = Parse-Field $Line "packetsSent"
    if ($parsed) { [void][long]::TryParse($parsed, [ref]$packets) }
    return ($packets -gt 0)
}

$txLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_TX_ACTIVITY")
$txOkLines = @($txLines | Where-Object { Test-TxActivityLine $_ })
$txPacketsMax = 0L
foreach ($line in $txOkLines) {
    $p = 0L
    $raw = Parse-Field $line "packetsSent"
    if ($raw -and [long]::TryParse($raw, [ref]$p) -and $p -gt $txPacketsMax) { $txPacketsMax = $p }
}

$fencedLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_TX_FENCED .*sessionId=$hostSessionId")
$failedLines = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_TX_FAILED .*sessionId=$hostSessionId")

# Fence breakdown (diagnostic only — does not change gate contract)
$fenceReasons = @{}
foreach ($line in $fencedLines) {
    $reason = Parse-Field $line "reason"
    if (-not $reason) { $reason = "UNKNOWN" }
    if (-not $fenceReasons.ContainsKey($reason)) { $fenceReasons[$reason] = 0 }
    $fenceReasons[$reason] = $fenceReasons[$reason] + 1
}

function Test-FenceReasonPresent([string]$Token) {
    foreach ($k in $fenceReasons.Keys) {
        if ($k -match $Token) { return $true }
    }
    return $false
}

$shadowSessionLiveDiag = $hostSessionLive
$localMemberBindingDiag = ($memberReadyHost.Count -gt 0) -and -not (Test-FenceReasonPresent "NO_MEMBER_BINDING|NO_REGISTRY_MEMBER")
$localSourceCurrentDiag = -not (Test-FenceReasonPresent "NO_LOCAL_SOURCE|SOURCE_GENERATION_MISMATCH")
$mediaKeyEpochAlignedDiag = -not (Test-FenceReasonPresent "MEDIA_KEY_EPOCH_MISMATCH|REGISTRY_EPOCH_MISMATCH")
$sourceGenerationCurrentDiag = -not (Test-FenceReasonPresent "SOURCE_GENERATION_MISMATCH")
$productionCaptureArmedDiag = $pttStimulusOk -and -not (Test-FenceReasonPresent "TRANSPORT_NOT_READY")

Add-Out "PA-SR4-TX CHAIN"
Add-Out "TX-ESTABLISH_HOST_SESSION_PROJECTED=$($hostProjected.Count -gt 0)"
Add-Out "TX-ESTABLISH_SHADOW_SESSION_LIVE=$hostSessionLive"
Add-Out "TX-STIMULUS_PTT_CAPTURE_ON=$pttStimulusOk lines=$($captureOn.Count)"
Add-Out "TX-SEND_SHADOW_TX_ACTIVITY=$($txOkLines.Count -gt 0) packetsSent_max=$txPacketsMax"
Add-Out "note=TX-ESTABLISH_is_necessary_prereq_NOT_packets_evidence"
Add-Out ""

Add-Out "FENCE_BREAKDOWN_DIAGNOSTIC_ONLY"
Add-Out "shadowSessionLive=$shadowSessionLiveDiag"
Add-Out "localMemberBinding=$localMemberBindingDiag"
Add-Out "localSourceCurrent=$localSourceCurrentDiag"
Add-Out "mediaKeyEpochAligned=$mediaKeyEpochAlignedDiag"
Add-Out "sourceGenerationCurrent=$sourceGenerationCurrentDiag"
Add-Out "productionCaptureArmed=$productionCaptureArmedDiag"
Add-Out "SHADOW_TX_FENCED_lines=$($fencedLines.Count)"
Add-Out "SHADOW_TX_FAILED_lines=$($failedLines.Count)"
if ($fenceReasons.Count -gt 0) {
    foreach ($k in ($fenceReasons.Keys | Sort-Object)) {
        Add-Out "fenceReason_$k=$($fenceReasons[$k])"
    }
} else {
    Add-Out "fenceReason=NONE"
}
if ($fencedLines.Count -gt 0) {
    Add-Out "fenceEvidence=$( ($fencedLines | Select-Object -First 1) )"
}
if ($failedLines.Count -gt 0) {
    Add-Out "failedEvidence=$( ($failedLines | Select-Object -First 1) )"
}
Add-Out ""

$identityPath = Join-Path $LogDir "PA_SR4_TX_IDENTITY.txt"
$identityLines = @(
    "sessionId=$hostSessionId",
    "conferenceId=$conferenceId",
    "hostModuleId=$hostModuleId",
    "peerModuleId=$peerModuleId",
    "hostSessionProjected=$($hostProjected.Count -gt 0)",
    "hostShadowSessionLive=$hostSessionLive",
    "pttCaptureOn=$pttStimulusOk",
    "txActivityPacketsSentMax=$txPacketsMax",
    "txEvidence=$(if ($txOkLines.Count -gt 0) { ($txOkLines | Select-Object -First 1) } else { 'NONE' })"
)
($identityLines -join "`n") | Set-Content -Encoding utf8 $identityPath
Add-Out "identity_file=PA_SR4_TX_IDENTITY.txt"
foreach ($l in $identityLines) { Add-Out $l }
Add-Out ""

$txPass = $txOkLines.Count -gt 0 -and $txPacketsMax -gt 0

if ($txPass) {
    Add-Out "VERDICT=PASS_PA_SR4_TX"
    Add-Out "PA-SR4-TX=CLOSED / UNIT+FIELD-PROVEN"
    Add-Out "PA-SR4=VALID_FAIL / RX_NEXT"
    Add-Out "PR-PA-SR4-RX=DESIGN_NEXT / NOT_AUTHORIZED"
    Add-Out "PLAY=HOLD"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=PR_PA_SR4_RX_AUTHORIZATION_SEPARATE"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
    Write-Host ($linesOut -join "`n")
    exit 0
}

# Valid FAIL requires stimulus executed; otherwise INVALID (TX not tested)
if (-not $pttStimulusOk) {
    Write-InvalidAndExit "INVALID_STIMULUS" "ptt_captureON_not_observed_tx_not_tested" "RERUN_WITH_M01_PTT_STIMULUS"
}

$failReasons = New-Object System.Collections.Generic.List[string]
$failReasons.Add("missing_SHADOW_TX_ACTIVITY_packetsSent_gt0")
if ($fencedLines.Count -gt 0 -and $txOkLines.Count -eq 0) {
    $failReasons.Add("only_SHADOW_TX_FENCED")
}
if ($failedLines.Count -gt 0 -and $txOkLines.Count -eq 0) {
    $failReasons.Add("only_SHADOW_TX_FAILED")
}

Add-Out "VERDICT=FAIL_PA_SR4_TX"
Add-Out "PA-SR4-TX=FAIL"
Add-Out "PA-SR4=VALID_FAIL_UNCHANGED"
Add-Out "reason=$($failReasons -join ';')"
Add-Out "PR-PA-SR4-RX=NOT_AUTHORIZED"
Add-Out "PLAY=HOLD"
Add-Out "ADR-0056=KEEP"
Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
Add-Out "Next=RCA_PA_SR4_TX_EGRESS_GATES_USE_FENCE_BREAKDOWN"
($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($linesOut -join "`n")
exit 1
