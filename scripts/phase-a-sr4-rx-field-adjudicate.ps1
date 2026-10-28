# PA-SR4-RX field adjudication — Peer Shadow Multicast Receive Gate.
#
# Four-state:
#   PASS_PA_SR4_RX (0) · FAIL_PA_SR4_RX (1) · INVALID_TX_PREREQUISITE / INVALID_PEER_SESSION / INVALID_* (2)
#
# Valid prerequisites:
#   M01 SHADOW_TX_ACTIVITY packetsSent>0
#   M02 WIRING_SESSION_STARTED transportScopeActive=true (or peer shadow session live)
#
# Diagnostic only (never PASS):
#   SHADOW_RX_ARMED · SHADOW_RX_ACTIVITY · SHADOW_RX_ADMISSION · PLAYOUT_ACTIVITY
#
# ONLY PASS: M02 INGRESS_ACTIVITY packetsReceived>0 (or ingressAccepted>0)
#
# PASS_PA_SR4_RX does NOT close PA-SR4 full / authorize cutover / assume PLAY driver needed.
#
# Usage:
#   .\talkback\scripts\phase-a-sr4-rx-field-adjudicate.ps1 -LogDir <dir>
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
    Add-Out "PA-SR4-RX=NOT_TESTED"
    Add-Out "PA-SR4=VALID_FAIL_UNCHANGED"
    Add-Out "reason=$Reason"
    Add-Out "PR-PA-SR4-PLAY=HOLD"
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

Add-Out "PA-SR4-RX FIELD ADJUDICATION (Peer Shadow Multicast Receive Gate)"
Add-Out "log_dir=$LogDir"
Add-Out "run_card=meeting-product-integration-phase-a-sr4-rx-field-run-card.md"
Add-Out "authorization=meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-rx-implementation-authorization.md"
Add-Out "PA-SR4_full=NOT_CLOSED_BY_THIS_GATE"
Add-Out "PLAY=OBSERVE_ONLY_NOT_PASS"
Add-Out ""

if (-not $m01Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_log" "FIX_CAPTURE_RERUN_PA_SR4_RX"
}
if (-not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M02_log" "FIX_CAPTURE_RERUN_PA_SR4_RX"
}

# --- PRE-G ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR4_RX"
    }
}

# --- PRE-I ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR4_RX"
    }
}

# --- PRE-J ---
$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId"
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_RERUN_PA_SR4_RX"
    }
}
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" "missing_hostSessionId" "FIX_FIELD_JOIN_RERUN_PA_SR4_RX"
}

$conferenceId = $manifest["conferenceId"]
$hostModuleId = if ($manifest["hostModuleId"]) { $manifest["hostModuleId"] } else { "M01" }
$peerModuleId = if ($manifest["peerModuleId"]) { $manifest["peerModuleId"] } else { "M02" }
Add-Out "identity hostSessionId=$hostSessionId conferenceId=$conferenceId hostModuleId=$hostModuleId peerModuleId=$peerModuleId"
Add-Out ""

# --- Observe-only (playout / SR5/SR6) — never PASS criteria ---
$observe = New-Object System.Collections.Generic.List[string]
$observe.Add("PA_SR4_RX_OBSERVE_ONLY")
$observe.Add("driverLeavePhase=$($shadowGate['driverLeavePhase'])")
$observe.Add("driverRejoinPhase=$($shadowGate['driverRejoinPhase'])")
$observe.Add("driverEndPhase=$($shadowGate['driverEndPhase'])")
$playoutObs = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY") + @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY")
$observe.Add("PLAYOUT_ACTIVITY_lines=$($playoutObs.Count)_OBSERVE_ONLY")
$observePath = Join-Path $LogDir "PA_SR4_RX_OBSERVE_ONLY.txt"
($observe -join "`n") | Set-Content -Encoding utf8 $observePath
Add-Out "observe_only_file=PA_SR4_RX_OBSERVE_ONLY.txt"
Add-Out ""

# --- J0 / same-session prerequisite ---
$creationApplied = @(Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED") | Where-Object {
    $_ -match "session=$hostSessionId" -or $_ -notmatch "session="
}
if ($creationApplied.Count -eq 0) {
    $creationApplied = Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED"
}
if ($creationApplied.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "j0_creation_applied_not_observed" "RERUN_AFTER_J0_PREREQ"
}

# --- TX prerequisite (M01) ---
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
$txPrerequisiteOk = $txOkLines.Count -gt 0 -and $txPacketsMax -gt 0

# --- Peer session (M02) ---
$peerSessionStartedHook = @(Get-Lines $m02Log "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
$peerShadowSessionPhase = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId")
$peerWiringStarted = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_SESSION_STARTED .*sessionId=$hostSessionId")
$peerWiringLiveOk = @(($peerShadowSessionPhase + $peerWiringStarted) | Where-Object { $_ -match "transportScopeActive=true" })
$peerSessionLive = ($peerSessionStartedHook.Count -gt 0) -or ($peerWiringLiveOk.Count -gt 0)

Add-Out "PREREQUISITE_SAME_SESSION"
Add-Out "J0_CREATION_APPLIED=$($creationApplied.Count -gt 0)"
Add-Out "TX_PREREQUISITE_HOST_PACKETS=$txPrerequisiteOk packetsSent_max=$txPacketsMax"
Add-Out "PEER_WIRING_SESSION_LIVE=$peerSessionLive"
Add-Out "PEER_SESSION_STARTED_HOOK=$($peerSessionStartedHook.Count -gt 0)"
Add-Out "PEER_WIRING_TRANSPORT_SCOPE=$($peerWiringLiveOk.Count -gt 0)"
Add-Out ""

if (-not $txPrerequisiteOk) {
    Write-InvalidAndExit "INVALID_TX_PREREQUISITE" "host_SHADOW_TX_ACTIVITY_packetsSent_not_gt0" "FIX_TX_PREREQUISITE_OR_RERUN_PA_SR4_TX"
}
if (-not $peerSessionLive) {
    Write-InvalidAndExit "INVALID_PEER_SESSION" "peer_wiring_session_not_live" "FIX_PEER_SHADOW_SESSION_RERUN_PA_SR4_RX"
}

# --- RX diagnostic (M02 peer) — never PASS alone ---
$rxArmedLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_RX_ARMED .*sessionId=$hostSessionId")
$rxActivityLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_RX_ACTIVITY .*sessionId=$hostSessionId")
$rxAdmissionLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_RX_ADMISSION .*sessionId=$hostSessionId")
$rxFailureLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_RX_FAILED .*sessionId=$hostSessionId")

$rxDatagramsMax = 0L
foreach ($line in $rxActivityLines) {
    $d = 0L
    $raw = Parse-Field $line "receivedDatagrams"
    if ($raw -and [long]::TryParse($raw, [ref]$d) -and $d -gt $rxDatagramsMax) { $rxDatagramsMax = $d }
}

$rxAdmissionAdmitted = 0
$rxAdmissionRejected = 0
foreach ($line in $rxAdmissionLines) {
    $outcome = Parse-Field $line "outcome"
    if ($outcome -eq "ADMITTED") { $rxAdmissionAdmitted++ }
    elseif ($outcome -eq "REJECTED") { $rxAdmissionRejected++ }
}

# --- INGRESS gate (M02 peer only) ---
function Test-IngressActivityLine([string]$Line) {
    if ($Line -notmatch "phase=INGRESS_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $packets = 0L
    $accepted = 0L
    $parsedPkt = Parse-Field $Line "packetsReceived"
    $parsedAcc = Parse-Field $Line "ingressAccepted"
    if ($parsedPkt) { [void][long]::TryParse($parsedPkt, [ref]$packets) }
    if ($parsedAcc) { [void][long]::TryParse($parsedAcc, [ref]$accepted) }
    return ($packets -gt 0) -or ($accepted -gt 0)
}

$ingressLinesPeer = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId")
$ingressOkLines = @($ingressLinesPeer | Where-Object { Test-IngressActivityLine $_ })
$ingressPacketsMax = 0L
foreach ($line in $ingressOkLines) {
    $p = 0L
    $raw = Parse-Field $line "packetsReceived"
    if ($raw -and [long]::TryParse($raw, [ref]$p) -and $p -gt $ingressPacketsMax) { $ingressPacketsMax = $p }
}

Add-Out "RX_FIELD_DIAGNOSTIC_ONLY"
Add-Out "RX_ARMED=$($rxArmedLines.Count -gt 0)"
Add-Out "RX_DATAGRAMS_RECEIVED_MAX=$rxDatagramsMax"
Add-Out "RX_ADMISSION_ADMITTED=$rxAdmissionAdmitted"
Add-Out "RX_ADMISSION_REJECTED=$rxAdmissionRejected"
Add-Out "RX_FAILURE_LINES=$($rxFailureLines.Count)"
Add-Out "INGRESS_PACKETS_MAX=$ingressPacketsMax"
Add-Out "note=diagnostic_fields_do_not_substitute_for_INGRESS_PASS"
Add-Out ""

if ($rxFailureLines.Count -gt 0) {
    Add-Out "rxFailureEvidence=$( ($rxFailureLines | Select-Object -First 1) )"
}
if ($rxAdmissionLines.Count -gt 0) {
    Add-Out "rxAdmissionEvidence=$( ($rxAdmissionLines | Select-Object -First 1) )"
}
Add-Out ""

Add-Out "PA-SR4-RX CHAIN"
Add-Out "RX-TX_PREREQUISITE_HOST_TX=$txPrerequisiteOk"
Add-Out "RX-ESTABLISH_PEER_WIRING=$peerSessionLive"
Add-Out "RX-DIAG_ARMED=$($rxArmedLines.Count -gt 0)"
Add-Out "RX-DIAG_DATAGRAMS=$($rxDatagramsMax -gt 0) max=$rxDatagramsMax"
Add-Out "RX-DIAG_ADMISSION admitted=$rxAdmissionAdmitted rejected=$rxAdmissionRejected"
Add-Out "RX-INGRESS_INGRESS_ACTIVITY=$($ingressOkLines.Count -gt 0) packetsReceived_max=$ingressPacketsMax"
Add-Out ""

$identityPath = Join-Path $LogDir "PA_SR4_RX_IDENTITY.txt"
$identityLines = @(
    "sessionId=$hostSessionId",
    "conferenceId=$conferenceId",
    "hostModuleId=$hostModuleId",
    "peerModuleId=$peerModuleId",
    "txPrerequisitePacketsSentMax=$txPacketsMax",
    "peerWiringSessionLive=$peerSessionLive",
    "rxDatagramsMax=$rxDatagramsMax",
    "rxAdmissionAdmitted=$rxAdmissionAdmitted",
    "rxAdmissionRejected=$rxAdmissionRejected",
    "ingressPacketsMax=$ingressPacketsMax",
    "ingressEvidence=$(if ($ingressOkLines.Count -gt 0) { ($ingressOkLines | Select-Object -First 1) } else { 'NONE' })"
)
($identityLines -join "`n") | Set-Content -Encoding utf8 $identityPath
Add-Out "identity_file=PA_SR4_RX_IDENTITY.txt"
foreach ($l in $identityLines) { Add-Out $l }
Add-Out ""

$rxPass = $ingressOkLines.Count -gt 0 -and $ingressPacketsMax -gt 0

if ($rxPass) {
    Add-Out "VERDICT=PASS_PA_SR4_RX"
    Add-Out "PA-SR4-RX=CLOSED / UNIT+FIELD-PROVEN"
    Add-Out "PA-SR4=VALID FAIL / evaluate PLAYOUT next"
    Add-Out "PR-PA-SR4-PLAY=HOLD"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=EVALUATE_PLAYOUT_FROM_FIELD_OBSERVATION"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
    Write-Host ($linesOut -join "`n")
    exit 0
}

$failReasons = New-Object System.Collections.Generic.List[string]
$failReasons.Add("missing_M02_INGRESS_ACTIVITY_packetsReceived_gt0")
if ($rxDatagramsMax -eq 0) {
    $failReasons.Add("rx_activity_zero_socket_or_poll_layer")
} elseif ($rxAdmissionAdmitted -eq 0 -and $rxAdmissionRejected -gt 0) {
    $failReasons.Add("rx_admission_only_rejected_resolver_layer")
} elseif ($rxAdmissionAdmitted -gt 0 -and $ingressPacketsMax -eq 0) {
    $failReasons.Add("admitted_but_ingress_zero_metrics_projection_gap")
}

Add-Out "VERDICT=FAIL_PA_SR4_RX"
Add-Out "PA-SR4-RX=FAIL"
Add-Out "PA-SR4=VALID_FAIL_UNCHANGED"
Add-Out "reason=$($failReasons -join ';')"
Add-Out "PR-PA-SR4-PLAY=HOLD"
Add-Out "ADR-0056=KEEP"
Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
Add-Out "Next=RCA_PA_SR4_RX_USE_DIAGNOSTIC_BREAKDOWN"
($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($linesOut -join "`n")
exit 1
