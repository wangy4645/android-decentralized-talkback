# PA-SR4-PLAY field adjudication — Peer Shadow Playout Execution Gate.
#
# Four-state:
#   PASS_PA_SR4_PLAY (0) · FAIL_PA_SR4_PLAY (1) · INVALID_* (2)
#
# Valid prerequisites:
#   M01 SHADOW_TX_ACTIVITY packetsSent>0
#   M02 WIRING_SESSION_STARTED transportScopeActive=true
#   M02 INGRESS_ACTIVITY packetsReceived>0 (or ingressAccepted>0)
#
# ONLY PASS: M02 PLAYOUT_ACTIVITY successfulPlayoutWrites>0
#
# Usage:
#   .\talkback\scripts\phase-a-sr4-play-field-adjudicate.ps1 -LogDir <dir>
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
    Add-Out "PA-SR4-PLAY=NOT_TESTED"
    Add-Out "PA-SR4=VALID_FAIL_UNCHANGED"
    Add-Out "reason=$Reason"
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

Add-Out "PA-SR4-PLAY FIELD ADJUDICATION (Peer Shadow Playout Execution Gate)"
Add-Out "log_dir=$LogDir"
Add-Out "run_card=meeting-product-integration-phase-a-sr4-play-field-run-card.md"
Add-Out "rca=meeting-product-integration-phase-a-sr4-play-rca-20260910-092105.md"
Add-Out ""

if (-not $m01Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_log" "FIX_CAPTURE_RERUN_PA_SR4_PLAY"
}
if (-not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M02_log" "FIX_CAPTURE_RERUN_PA_SR4_PLAY"
}

if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR4_PLAY"
    }
}

if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR4_PLAY"
    }
}

$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId"
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_RERUN_PA_SR4_PLAY"
    }
}
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" "missing_hostSessionId" "FIX_FIELD_JOIN_RERUN_PA_SR4_PLAY"
}

$conferenceId = $manifest["conferenceId"]
$hostModuleId = if ($manifest["hostModuleId"]) { $manifest["hostModuleId"] } else { "M01" }
$peerModuleId = if ($manifest["peerModuleId"]) { $manifest["peerModuleId"] } else { "M02" }
Add-Out "identity hostSessionId=$hostSessionId conferenceId=$conferenceId hostModuleId=$hostModuleId peerModuleId=$peerModuleId"
Add-Out ""

$creationApplied = @(Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED") | Where-Object {
    $_ -match "session=$hostSessionId" -or $_ -notmatch "session="
}
if ($creationApplied.Count -eq 0) {
    $creationApplied = Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED"
}
if ($creationApplied.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "j0_creation_applied_not_observed" "RERUN_AFTER_J0_PREREQ"
}

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

$peerSessionStartedHook = @(Get-Lines $m02Log "SHADOW hook=SESSION_STARTED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
$peerShadowSessionPhase = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$hostSessionId")
$peerWiringStarted = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_SESSION_STARTED .*sessionId=$hostSessionId")
$peerWiringLiveOk = @(($peerShadowSessionPhase + $peerWiringStarted) | Where-Object { $_ -match "transportScopeActive=true" })
$peerSessionLive = ($peerSessionStartedHook.Count -gt 0) -or ($peerWiringLiveOk.Count -gt 0)

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
$ingressAcceptedMax = 0L
$activeJitterSourcesMax = 0
$liveDecodersMax = 0
foreach ($line in $ingressOkLines) {
    $p = 0L
    $a = 0L
    $j = 0
    $d = 0
    $rawPkt = Parse-Field $line "packetsReceived"
    $rawAcc = Parse-Field $line "ingressAccepted"
    $rawJit = Parse-Field $line "activeJitterSources"
    $rawDec = Parse-Field $line "liveDecoders"
    if ($rawPkt -and [long]::TryParse($rawPkt, [ref]$p) -and $p -gt $ingressPacketsMax) { $ingressPacketsMax = $p }
    if ($rawAcc -and [long]::TryParse($rawAcc, [ref]$a) -and $a -gt $ingressAcceptedMax) { $ingressAcceptedMax = $a }
    if ($rawJit -and [int]::TryParse($rawJit, [ref]$j) -and $j -gt $activeJitterSourcesMax) { $activeJitterSourcesMax = $j }
    if ($rawDec -and [int]::TryParse($rawDec, [ref]$d) -and $d -gt $liveDecodersMax) { $liveDecodersMax = $d }
}

function Test-PlayoutActivityLine([string]$Line) {
    if ($Line -notmatch "phase=PLAYOUT_ACTIVITY") { return $false }
    if ($Line -notmatch "sessionId=$hostSessionId") { return $false }
    $writes = 0L
    $parsed = Parse-Field $Line "successfulPlayoutWrites"
    if ($parsed) { [void][long]::TryParse($parsed, [ref]$writes) }
    return ($writes -gt 0)
}

$playoutLinesPeer = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId")
$playoutOkLines = @($playoutLinesPeer | Where-Object { Test-PlayoutActivityLine $_ })
$playoutWritesMax = 0L
$audioTrackIsolationOk = $true
foreach ($line in $playoutOkLines) {
    $w = 0L
    $raw = Parse-Field $line "successfulPlayoutWrites"
    if ($raw -and [long]::TryParse($raw, [ref]$w) -and $w -gt $playoutWritesMax) { $playoutWritesMax = $w }
    $owner = Parse-Field $line "audioTrackOwner"
    if ($owner -and $owner -ne "SHADOW_METRICS_ONLY") { $audioTrackIsolationOk = $false }
}

$playoutArmedLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_ARMED .*sessionId=$hostSessionId")
$playoutCycleLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_CYCLE .*sessionId=$hostSessionId")
$playoutFailureLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_FAILED .*sessionId=$hostSessionId")
$playoutTickFailureLines = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_TICK_FAILED .*sessionId=$hostSessionId")

Add-Out "PREREQUISITE_SAME_SESSION"
Add-Out "J0_CREATION_APPLIED=$($creationApplied.Count -gt 0)"
Add-Out "TX_PREREQUISITE_HOST_PACKETS=$txPrerequisiteOk packetsSent_max=$txPacketsMax"
Add-Out "PEER_WIRING_SESSION_LIVE=$peerSessionLive"
Add-Out "INGRESS_PREREQUISITE=$($ingressOkLines.Count -gt 0) packetsReceived_max=$ingressPacketsMax ingressAccepted_max=$ingressAcceptedMax"
Add-Out ""

Add-Out "PLAY_FIELD_DIAGNOSTIC"
Add-Out "PLAY_ARMED=$($playoutArmedLines.Count -gt 0)"
Add-Out "PLAY_CYCLE_LINES=$($playoutCycleLines.Count)"
Add-Out "ACTIVE_JITTER_SOURCES_MAX=$activeJitterSourcesMax"
Add-Out "LIVE_DECODERS_MAX=$liveDecodersMax"
Add-Out "PLAYOUT_WRITES_MAX=$playoutWritesMax"
Add-Out "AUDIO_TRACK_ISOLATION=$audioTrackIsolationOk"
Add-Out "PLAY_FAILURE_LINES=$($playoutFailureLines.Count)"
Add-Out "PLAY_TICK_FAILURE_LINES=$($playoutTickFailureLines.Count)"
Add-Out ""

if (-not $txPrerequisiteOk) {
    Write-InvalidAndExit "INVALID_TX_PREREQUISITE" "host_SHADOW_TX_ACTIVITY_packetsSent_not_gt0" "FIX_TX_PREREQUISITE_OR_RERUN_PA_SR4_TX"
}
if (-not $peerSessionLive) {
    Write-InvalidAndExit "INVALID_PEER_SESSION" "peer_wiring_session_not_live" "FIX_PEER_SHADOW_SESSION_RERUN_PA_SR4_PLAY"
}
if ($ingressOkLines.Count -eq 0 -or $ingressPacketsMax -eq 0) {
    Write-InvalidAndExit "INVALID_INGRESS_PREREQUISITE" "peer_INGRESS_ACTIVITY_not_gt0" "FIX_RX_PREREQUISITE_OR_RERUN_PA_SR4_RX"
}

Add-Out "PA-SR4-PLAY CHAIN"
Add-Out "PLAY-TX_PREREQUISITE=$txPrerequisiteOk"
Add-Out "PLAY-INGRESS_PREREQUISITE=$($ingressOkLines.Count -gt 0)"
Add-Out "PLAY-DIAG_ARMED=$($playoutArmedLines.Count -gt 0)"
Add-Out "PLAY-DIAG_ACTIVE_JITTER=$($activeJitterSourcesMax -gt 0) max=$activeJitterSourcesMax"
Add-Out "PLAY-DIAG_LIVE_DECODERS=$($liveDecodersMax -gt 0) max=$liveDecodersMax"
Add-Out "PLAY-PLAYOUT_ACTIVITY=$($playoutOkLines.Count -gt 0) successfulPlayoutWrites_max=$playoutWritesMax"
Add-Out "PLAY-AUDIO_ISOLATION=$audioTrackIsolationOk"
Add-Out ""

$identityPath = Join-Path $LogDir "PA_SR4_PLAY_IDENTITY.txt"
$identityLines = @(
    "sessionId=$hostSessionId",
    "conferenceId=$conferenceId",
    "hostModuleId=$hostModuleId",
    "peerModuleId=$peerModuleId",
    "txPrerequisitePacketsSentMax=$txPacketsMax",
    "ingressPacketsMax=$ingressPacketsMax",
    "ingressAcceptedMax=$ingressAcceptedMax",
    "activeJitterSourcesMax=$activeJitterSourcesMax",
    "liveDecodersMax=$liveDecodersMax",
    "playoutWritesMax=$playoutWritesMax",
    "audioTrackIsolationOk=$audioTrackIsolationOk",
    "playoutEvidence=$(if ($playoutOkLines.Count -gt 0) { ($playoutOkLines | Select-Object -First 1) } else { 'NONE' })"
)
($identityLines -join "`n") | Set-Content -Encoding utf8 $identityPath
Add-Out "identity_file=PA_SR4_PLAY_IDENTITY.txt"
foreach ($l in $identityLines) { Add-Out $l }
Add-Out ""

$playPass = $playoutOkLines.Count -gt 0 -and $playoutWritesMax -gt 0 -and $audioTrackIsolationOk

if ($playPass) {
    Add-Out "VERDICT=PASS_PA_SR4_PLAY"
    Add-Out "PA-SR4-PLAY=CLOSED / UNIT+FIELD-PROVEN"
    Add-Out "PA-SR4=CLOSED / FIELD-PROVEN"
    Add-Out "PA-SR4_TX=PASS"
    Add-Out "PA-SR4_RX=PASS"
    Add-Out "PA-SR4_INGRESS=PASS"
    Add-Out "PA-SR4_PLAYOUT=PASS"
    Add-Out "PA-SR4_AUDIO_ISOLATION=PASS"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=PA_SR5_LEAVE_REJOIN"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
    Write-Host ($linesOut -join "`n")
    exit 0
}

$failReasons = New-Object System.Collections.Generic.List[string]
$failReasons.Add("missing_M02_PLAYOUT_ACTIVITY_successfulPlayoutWrites_gt0")
if ($ingressPacketsMax -gt 0 -and $activeJitterSourcesMax -eq 0) {
    $failReasons.Add("ingress_without_active_jitter_sources")
}
if ($activeJitterSourcesMax -gt 0 -and $liveDecodersMax -eq 0 -and $playoutWritesMax -eq 0) {
    $failReasons.Add("jitter_present_but_decode_mix_playout_not_running")
}
if (-not $audioTrackIsolationOk) {
    $failReasons.Add("audio_track_ownership_violation")
}

Add-Out "VERDICT=FAIL_PA_SR4_PLAY"
Add-Out "PA-SR4-PLAY=FAIL"
Add-Out "PA-SR4=VALID_FAIL_UNCHANGED"
Add-Out "reason=$($failReasons -join ';')"
Add-Out "ADR-0056=KEEP"
Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
Add-Out "Next=RCA_PA_SR4_PLAY_USE_DIAGNOSTIC_BREAKDOWN"
($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($linesOut -join "`n")
exit 1
