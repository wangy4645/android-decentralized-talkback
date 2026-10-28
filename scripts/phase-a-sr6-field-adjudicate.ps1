# PA-SR6 field adjudication — M01 host Meeting end teardown.
#
# Three-state: PASS_PA_SR6 (0) · FAIL_PA_SR6 (1) · INVALID_* (2)
#
# Usage:
#   .\talkback\scripts\phase-a-sr6-field-adjudicate.ps1 -LogDir <dir>
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
    param([string]$Path, [string]$Pattern)
    if (-not $Path -or -not (Test-Path $Path)) { return @() }
    return @(Select-String -Path $Path -Pattern $Pattern | ForEach-Object { $_.Line })
}

function Get-MaxNumericField {
    param([string[]]$Lines, [string]$Key)
    $max = [long]0
    foreach ($line in $Lines) {
        $v = Parse-Field $line $Key
        if ($v -and [long]$v -gt $max) { $max = [long]$v }
    }
    return $max
}

function Get-LinesAfterLastTeardown {
    param([string]$Path, [string]$SessionId)
    if (-not $Path -or -not (Test-Path $Path)) { return @() }
    $all = Get-Content $Path
    $lastIdx = -1
    for ($i = 0; $i -lt $all.Count; $i++) {
        if ($all[$i] -match "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$SessionId") {
            $lastIdx = $i
        }
    }
    if ($lastIdx -lt 0) { return @() }
    if ($lastIdx + 1 -ge $all.Count) { return @() }
    return @($all[($lastIdx + 1)..($all.Count - 1)])
}

$linesOut = New-Object System.Collections.Generic.List[string]
function Add-Out([string]$s) { [void]$linesOut.Add($s) }

function Write-InvalidAndExit {
    param([string]$Verdict, [string]$Reason, [string]$Next)
    Add-Out ""
    Add-Out "VERDICT=$Verdict"
    Add-Out "PA-SR6=NOT_TESTED"
    Add-Out "reason=$Reason"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=$Next"
    $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
    Write-Host ($linesOut -join "`n")
    exit 2
}

function Write-FailAndExit {
    param([string]$Reason)
    Add-Out ""
    Add-Out "VERDICT=FAIL_PA_SR6"
    Add-Out "PA-SR6=FAIL"
    Add-Out "reason=$Reason"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=FIX_SR6_REGRESSION_RERUN"
    $adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
    Write-Host ($linesOut -join "`n")
    exit 1
}

$m01Log = Get-ModuleLog "M01"
$m02Log = Get-ModuleLog "M02"
$joinGate = Read-KvFile (Join-Path $LogDir "JOIN_GATE.txt")
$groupGate = Read-KvFile (Join-Path $LogDir "GROUP_GATE.txt")
$inviteJoinGate = Read-KvFile (Join-Path $LogDir "INVITE_JOIN_GATE.txt")
$uiGate = Read-KvFile (Join-Path $LogDir "UI_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$shadowGate = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")

Add-Out "PA-SR6 FIELD ADJUDICATION (M01 Host Meeting End Teardown)"
Add-Out "log_dir=$LogDir"
Add-Out "run_card=meeting-product-integration-phase-a-sr6-field-run-card.md"
Add-Out "stimulus=M01_host_Meeting_end_product_path"
Add-Out "adjudication_plane=M02_peer_primary_M01_corroboration"
Add-Out ""

if (-not $m01Log) { Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_log" "FIX_CAPTURE_RERUN_PA_SR6" }
if (-not $m02Log) { Write-InvalidAndExit "INVALID_CAPTURE" "missing_M02_log" "FIX_CAPTURE_RERUN_PA_SR6" }

if ($uiGate["verdict"] -eq "INVALID_UI_PREREQUISITE" -or $manifest["abortVerdict"] -eq "INVALID_UI_PREREQUISITE" -or $shadowGate["harnessAbort"] -eq "true") {
    $uiReason = if ($uiGate["reason"]) { $uiGate["reason"] } elseif ($manifest["abortReason"]) { $manifest["abortReason"] } elseif ($shadowGate["harnessAbortReason"]) { $shadowGate["harnessAbortReason"] } else { "harness_ui_prerequisite" }
    Write-InvalidAndExit "INVALID_UI_PREREQUISITE" $uiReason "FIX_HARNESS_UI_RERUN_PA_SR6"
}

if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR6"
    }
}

if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR6"
    }
}

$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_SESSION" "missing_hostSessionId" "FIX_CAPTURE_RERUN_PA_SR6"
}
Add-Out "hostSessionId=$hostSessionId"

if ($shadowGate["driverRotatePhase"] -eq "COMPLETE" -or $shadowGate["sr5Window"]) {
    Write-InvalidAndExit "INVALID_STIMULUS" "sr5_rotate_or_wrong_driver_detected" "RERUN_WITH_PaSr6_DRIVER"
}
if ($shadowGate["driverLeavePhase"] -eq "COMPLETE" -or $shadowGate["driverRejoinPhase"] -eq "COMPLETE") {
    Write-InvalidAndExit "INVALID_STIMULUS" "legacy_M02_leave_rejoin_detected_not_authorized_for_SR6" "RERUN_WITH_PaSr6_DRIVER"
}

# Window A — operational baseline (anti-false-pass)
$txMax = Get-MaxNumericField (Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_TX_ACTIVITY .*sessionId=$hostSessionId") "packetsSent"
$ingressMax = Get-MaxNumericField (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId") "packetsReceived"
$playoutMax = Get-MaxNumericField (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId") "successfulPlayoutWrites"
$peerWiring = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=(WIRING_SESSION_STARTED|SHADOW_SESSION_STARTED) .*sessionId=$hostSessionId .*transportScopeActive=true")
$conferenceId = $shadowGate["captureConferenceId"]
if (-not $conferenceId) {
    $confLine = (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*sessionId=$hostSessionId .*conferenceId=") | Select-Object -First 1
    if ($confLine) { $conferenceId = Parse-Field $confLine "conferenceId" }
}

Add-Out "WINDOW_A tx_packetsSent_max=$txMax ingress_packetsReceived_max=$ingressMax playout_writes_max=$playoutMax peerWiring_lines=$($peerWiring.Count) conferenceId=$conferenceId"
if ($txMax -le 0 -or $ingressMax -le 0 -or $playoutMax -le 0 -or $peerWiring.Count -lt 1) {
    Write-InvalidAndExit "INVALID_BASELINE" "window_A_operational_media_not_proven" "FIX_SR4_BASELINE_RERUN_PA_SR6"
}

# Window B — end transition
$sessionStoppedPattern = "SHADOW hook=SESSION_STOPPED session=$hostSessionId .*outcome=APPLIED"
$sessionStoppedM01 = @(Get-Lines $m01Log $sessionStoppedPattern)
$sessionStoppedM02 = @(Get-Lines $m02Log $sessionStoppedPattern)
$sessionStoppedFailedM01 = @(Get-Lines $m01Log "SHADOW_FAILED hook=SESSION_STOPPED session=$hostSessionId")
$sessionStoppedFailedM02 = @(Get-Lines $m02Log "SHADOW_FAILED hook=SESSION_STOPPED session=$hostSessionId")
$playoutDisarmed = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_DISARMED .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_PLAYOUT_DISARMED .*sessionId=$hostSessionId")
$teardownM01 = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId")
$teardownM02 = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$hostSessionId")
$teardownLines = @($teardownM01 + $teardownM02)

function Test-TeardownEmptyLine([string]$Line) {
    if ($Line -notmatch "transportScopeActive=false") { return $false }
    if ((Parse-Field $Line "catalogEntries") -ne "0") { return $false }
    if ((Parse-Field $Line "admittedCount") -ne "0") { return $false }
    if ((Parse-Field $Line "activeJitterSources") -ne "0") { return $false }
    if ((Parse-Field $Line "liveDecoders") -ne "0") { return $false }
    if ((Parse-Field $Line "jitterBufferCount") -ne "0") { return $false }
    return $true
}

$teardownOk = @($teardownLines | Where-Object { Test-TeardownEmptyLine $_ })
$teardownOkM01 = @($teardownM01 | Where-Object { Test-TeardownEmptyLine $_ })
$teardownOkM02 = @($teardownM02 | Where-Object { Test-TeardownEmptyLine $_ })
$memberRevoked = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_REVOKED .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_REVOKED .*sessionId=$hostSessionId")

Add-Out "WINDOW_B sessionStopped_M01=$($sessionStoppedM01.Count) sessionStopped_M02=$($sessionStoppedM02.Count) sessionStoppedFailed_M01=$($sessionStoppedFailedM01.Count) sessionStoppedFailed_M02=$($sessionStoppedFailedM02.Count) playoutDisarmed=$($playoutDisarmed.Count) teardownEmpty_ok=$($teardownOk.Count) teardownEmpty_M01=$($teardownOkM01.Count) teardownEmpty_M02=$($teardownOkM02.Count) memberRevoked=$($memberRevoked.Count)"

if ($sessionStoppedFailedM01.Count -gt 0 -or $sessionStoppedFailedM02.Count -gt 0) {
    Write-FailAndExit "window_B_session_stopped_shadow_failed_hook"
}
if ($sessionStoppedM01.Count -lt 1 -and $sessionStoppedM02.Count -lt 1) {
    Write-FailAndExit "window_B_session_stopped_missing"
}
if ($playoutDisarmed.Count -lt 1) {
    Write-FailAndExit "window_B_playout_clock_not_disarmed"
}
if ($teardownOkM02.Count -lt 1) {
    Write-FailAndExit "window_B_peer_teardown_empty_missing"
}
if ($teardownOkM01.Count -lt 1) {
    Write-FailAndExit "window_B_host_teardown_empty_missing"
}

# RX disarm inferred: no post-teardown RX arm
$postTeardownM02 = Get-LinesAfterLastTeardown $m02Log $hostSessionId
$postTeardownM01 = Get-LinesAfterLastTeardown $m01Log $hostSessionId
$postRxArmed = @($postTeardownM01 + $postTeardownM02) | Where-Object { $_ -match "phase=SHADOW_RX_ARMED .*outcome=APPLIED" }
if ($postRxArmed.Count -gt 0) {
    Write-FailAndExit "window_C_rx_rearmed_after_teardown"
}

# Window C — quiescence
$quiescenceSec = $shadowGate["quiescenceSec"]
if (-not $quiescenceSec) { $quiescenceSec = "30" }
$ingressDelta = $shadowGate["quiescenceIngressDelta"]
$playoutDelta = $shadowGate["quiescencePlayoutDelta"]
$postIngressLines = @($postTeardownM01 + $postTeardownM02) | Where-Object { $_ -match "phase=(INGRESS_ACTIVITY|SHADOW_RX_ACTIVITY) .*sessionId=$hostSessionId" }
$postPlayoutLines = @($postTeardownM01 + $postTeardownM02) | Where-Object { $_ -match "phase=(PLAYOUT_ACTIVITY|SHADOW_PLAYOUT_CYCLE) .*sessionId=$hostSessionId" }
$postInstall = @($postTeardownM01 + $postTeardownM02) | Where-Object { $_ -match "phase=(WIRING_MEMBER_INSTALLED|SHADOW_MEMBER_INSTALLED) .*sessionId=$hostSessionId" }
$postTransportLive = @($postTeardownM01 + $postTeardownM02) | Where-Object { $_ -match "sessionId=$hostSessionId .*transportScopeActive=true" }

Add-Out "WINDOW_C quiescenceSec=$quiescenceSec ingressDelta=$ingressDelta playoutDelta=$playoutDelta postIngress_lines=$($postIngressLines.Count) postPlayout_lines=$($postPlayoutLines.Count) postInstall_lines=$($postInstall.Count) postTransportLive_lines=$($postTransportLive.Count)"

if ($ingressDelta -and [long]$ingressDelta -gt 0) { Write-FailAndExit "window_C_ingress_resumed" }
if ($playoutDelta -and [long]$playoutDelta -gt 0) { Write-FailAndExit "window_C_playout_resumed" }
if ($postIngressLines.Count -gt 0) { Write-FailAndExit "window_C_post_teardown_ingress_activity" }
if ($postPlayoutLines.Count -gt 0) { Write-FailAndExit "window_C_post_teardown_playout_activity" }
if ($postInstall.Count -gt 0) { Write-FailAndExit "window_C_binding_resurrection" }
if ($postTransportLive.Count -gt 0) { Write-FailAndExit "window_C_transport_scope_revived" }

Add-Out ""
Add-Out "VERDICT=PASS_PA_SR6"
Add-Out "PA-SR6=PASS"
Add-Out "hostSessionId=$hostSessionId"
Add-Out "ADR-0056=KEEP"
Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
Add-Out "Next=PHASE_A_CLOSED_REPLACEMENT_READINESS_REVIEW"

$adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
Write-Host ($linesOut -join "`n")
exit 0
