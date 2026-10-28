# PA-SR5 field adjudication — M01 SOURCE generation replace (peer M02 plane).
#
# Three-state: PASS_PA_SR5 (0) · FAIL_PA_SR5 (1) · INVALID_* (2)
#
# Usage:
#   .\talkback\scripts\phase-a-sr5-field-adjudicate.ps1 -LogDir <dir>
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

$linesOut = New-Object System.Collections.Generic.List[string]
function Add-Out([string]$s) { [void]$linesOut.Add($s) }

function Write-InvalidAndExit {
    param([string]$Verdict, [string]$Reason, [string]$Next)
    Add-Out ""
    Add-Out "VERDICT=$Verdict"
    Add-Out "PA-SR5=NOT_TESTED"
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
    Add-Out "VERDICT=FAIL_PA_SR5"
    Add-Out "PA-SR5=FAIL"
    Add-Out "reason=$Reason"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=FIX_SR5_REGRESSION_RERUN"
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

Add-Out "PA-SR5 FIELD ADJUDICATION (M01 SOURCE Generation Replace)"
Add-Out "log_dir=$LogDir"
Add-Out "run_card=meeting-product-integration-phase-a-sr5-field-run-card.md"
Add-Out "stimulus=M01_SOURCE_generation_replace_MEMBER_REPLACED"
Add-Out "adjudication_plane=M02_peer"
Add-Out ""

if (-not $m01Log) { Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_log" "FIX_CAPTURE_RERUN_PA_SR5" }
if (-not $m02Log) { Write-InvalidAndExit "INVALID_CAPTURE" "missing_M02_log" "FIX_CAPTURE_RERUN_PA_SR5" }

if ($uiGate["verdict"] -eq "INVALID_UI_PREREQUISITE" -or $manifest["abortVerdict"] -eq "INVALID_UI_PREREQUISITE" -or $shadowGate["harnessAbort"] -eq "true") {
    $uiReason = if ($uiGate["reason"]) { $uiGate["reason"] } elseif ($manifest["abortReason"]) { $manifest["abortReason"] } elseif ($shadowGate["harnessAbortReason"]) { $shadowGate["harnessAbortReason"] } else { "harness_ui_prerequisite" }
    Write-InvalidAndExit "INVALID_UI_PREREQUISITE" $uiReason "FIX_HARNESS_UI_RERUN_PA_SR5"
}

if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR5"
    }
}

if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR5"
    }
}

$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_SESSION" "missing_hostSessionId" "FIX_CAPTURE_RERUN_PA_SR5"
}
Add-Out "hostSessionId=$hostSessionId"

if ($shadowGate["driverLeavePhase"] -eq "COMPLETE" -or $shadowGate["driverRejoinPhase"] -eq "COMPLETE") {
    Write-InvalidAndExit "INVALID_STIMULUS" "legacy_M02_leave_rejoin_detected_not_authorized_for_SR5" "RERUN_WITH_PaSr5_DRIVER"
}

# Window A — SR4-class baseline on peer
$txMax = Get-MaxNumericField (Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_TX_ACTIVITY .*sessionId=$hostSessionId") "packetsSent"
$ingressMax = Get-MaxNumericField (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId") "packetsReceived"
$playoutMax = Get-MaxNumericField (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId") "successfulPlayoutWrites"

$installLines = Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=(WIRING_MEMBER_INSTALLED|SHADOW_MEMBER_INSTALLED) .*moduleId=M01 .*sourceGeneration="
$g1 = $shadowGate["captureG1"]
if (-not $g1 -and $installLines.Count -gt 0) {
    $g1 = Parse-Field $installLines[0] "sourceGeneration"
}
if (-not $g1) {
    Write-InvalidAndExit "INVALID_WINDOW_A" "missing_G1_capture" "FIX_OBS_OR_RERUN_PA_SR5"
}

Add-Out "WINDOW_A tx_packetsSent_max=$txMax ingress_packetsReceived_max=$ingressMax playout_writes_max=$playoutMax captureG1=$g1"
if ($txMax -le 0 -or $ingressMax -le 0 -or $playoutMax -le 0) {
    Write-InvalidAndExit "INVALID_SR4_PREREQUISITE" "window_A_sr4_baseline_not_met" "FIX_SR4_BASELINE_RERUN_PA_SR5"
}

# Window B — revoke G1
$revokedG1 = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_REVOKED .*moduleId=M01 .*sourceGeneration=$g1")
$memberReplaced = @(Get-Lines $m02Log "SHADOW hook=MEMBER_REPLACED session=$hostSessionId module=M01 outcome=APPLIED")
Add-Out "WINDOW_B revokedG1_lines=$($revokedG1.Count) memberReplaced=$($memberReplaced.Count)"
if ($revokedG1.Count -lt 1 -or $memberReplaced.Count -lt 1) {
    Write-FailAndExit "window_B_revoke_or_member_replaced_missing"
}

# Window C — G2 install + resume
$g2 = $null
foreach ($line in (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_INSTALLED .*moduleId=M01 .*sourceGeneration=")) {
    $gen = Parse-Field $line "sourceGeneration"
    if ($gen -and [long]$gen -gt [long]$g1) {
        if (-not $g2 -or [long]$gen -gt [long]$g2) { $g2 = $gen }
    }
}
$replacedObs = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_MEMBER_REPLACED .*moduleId=M01 .*oldSourceGeneration=$g1")
$postPlayout = Get-MaxNumericField (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId") "successfulPlayoutWrites"
Add-Out "WINDOW_C g2=$g2 shadowMemberReplaced_obs=$($replacedObs.Count) postPlayout_max=$postPlayout"

if (-not $g2 -or [long]$g2 -le [long]$g1) {
    Write-FailAndExit "window_C_g2_not_advanced"
}
if ($postPlayout -le 0) {
    Write-FailAndExit "window_C_playout_not_resumed"
}

# Negative: no G1 resurrection after G2 boundary
$resurrect = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=WIRING_MEMBER_INSTALLED .*moduleId=M01 .*sourceGeneration=$g1")
if ($resurrect.Count -gt 1) {
    Write-FailAndExit "g1_resurrection_after_replace"
}

Add-Out ""
Add-Out "VERDICT=PASS_PA_SR5"
Add-Out "PA-SR5=PASS"
Add-Out "g1=$g1 g2=$g2"
Add-Out "ADR-0056=KEEP"
Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
Add-Out "Next=PA_SR6_TEARDOWN_FIELD"

$adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
Write-Host ($linesOut -join "`n")
exit 0
