# PA-SR3 field adjudication (PR-PA-SR-B1) — SOURCE origin / apply / binding / hook chain only.
#
# Three-state:
#   PASS (0) · FAIL_PA_SR3 (1) · INVALID_* (2)
#
# Usage:
#   .\talkback\scripts\phase-a-sr3-field-adjudicate.ps1 -LogDir <dir>
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
    Add-Out "VERDICT=$Verdict"
    Add-Out "PA-SR3=NOT_TESTED"
    Add-Out "reason=$Reason"
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "Profile01_Meeting_chain=UNCHANGED_CLOSED"
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

Add-Out "PA-SR3 FIELD ADJUDICATION (PR-PA-SR-B1)"
Add-Out "log_dir=$LogDir"
Add-Out "criteria=meeting-product-integration-phase-a-sr3-adjudication-criteria.md"
Add-Out "primary_direction=M01_host_origin_to_M02_peer"
Add-Out "PA-SR4_to_SR6=OBSERVE_ONLY"
Add-Out ""

if (-not $m01Log -or -not $m02Log) {
    Write-InvalidAndExit "INVALID_CAPTURE" "missing_M01_or_M02_log" "FIX_CAPTURE_RERUN_PA_SR3"
}

# --- PRE-G ---
if ($groupGate.Count -gt 0) {
    Add-Out "GROUP_GATE status=$($groupGate['status']) verdict=$($groupGate['verdict'])"
    if ($groupGate["verdict"] -eq "INVALID_GROUP_PRECONDITION" -or $groupGate["status"] -eq "FAIL" -or $groupGate["PRE_G4_createAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_GROUP_PRECONDITION" $(if ($groupGate["reason"]) { $groupGate["reason"] } else { "group_precondition" }) "FIX_FIELD_GROUP_RERUN_PA_SR3"
    }
}

# --- PRE-I ---
if ($inviteJoinGate.Count -gt 0) {
    Add-Out "INVITE_JOIN_GATE status=$($inviteJoinGate['status']) hostSessionId=$($inviteJoinGate['hostSessionId'])"
    if ($inviteJoinGate["verdict"] -eq "INVALID_INVITE_JOIN_PRECONDITION" -or $inviteJoinGate["status"] -eq "FAIL" -or $inviteJoinGate["PRE_I6_peerBindH"] -ne "PASS") {
        Write-InvalidAndExit "INVALID_INVITE_JOIN_PRECONDITION" $(if ($inviteJoinGate["reason"]) { $inviteJoinGate["reason"] } else { "invite_join_precondition" }) "FIX_FIELD_INVITE_RERUN_PA_SR3"
    }
}

# --- PRE-J ---
$hostSessionId = $joinGate["hostSessionId"]
if (-not $hostSessionId) { $hostSessionId = $manifest["hostSessionId"] }
if ($joinGate.Count -gt 0) {
    Add-Out "JOIN_GATE status=$($joinGate['status']) hostSessionId=$hostSessionId"
    if ($joinGate["verdict"] -eq "INVALID_JOIN_PRECONDITION" -or $joinGate["status"] -eq "FAIL" -or $joinGate["PRE_J6_packageAdjudicationAllowed"] -ne "true") {
        Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" $(if ($joinGate["reason"]) { $joinGate["reason"] } else { "join_precondition" }) "FIX_FIELD_JOIN_RERUN_PA_SR3"
    }
}
if (-not $hostSessionId) {
    Write-InvalidAndExit "INVALID_JOIN_PRECONDITION" "missing_hostSessionId" "FIX_FIELD_JOIN_RERUN_PA_SR3"
}

$conferenceId = $manifest["conferenceId"]
$mediaKeyEpoch = $manifest["mediaKeyEpoch"]
$peerModuleId = if ($manifest["peerModuleId"]) { $manifest["peerModuleId"] } else { "M02" }
$hostModuleId = if ($manifest["hostModuleId"]) { $manifest["hostModuleId"] } else { "M01" }
$membershipVersion = if ($manifest["membershipVersion"]) { $manifest["membershipVersion"] } else { "0" }

Add-Out "identity hostSessionId=$hostSessionId conferenceId=$conferenceId mediaKeyEpoch=$mediaKeyEpoch hostModuleId=$hostModuleId peerModuleId=$peerModuleId"
Add-Out ""

# --- J0 prerequisite ---
$creationApplied = @(Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED") | Where-Object { $_ -match "session=$hostSessionId" }
if ($creationApplied.Count -eq 0) {
    $creationApplied = Get-Lines $m02Log "WIRE_INGRESS .*factType=1 outcome=APPLIED"
}
if ($creationApplied.Count -eq 0) {
    Write-InvalidAndExit "INVALID_PREREQUISITE" "creation_applied_not_observed" "RERUN_AFTER_J0_PREREQ"
}

# --- Observe-only SR4..SR6 ---
$observePath = Join-Path $LogDir "PA_SR3_OBSERVE_ONLY.txt"
$observe = New-Object System.Collections.Generic.List[string]
$observe.Add("PA-SR4_OBSERVE_ONLY")
$sr4Ingress = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=INGRESS_ACTIVITY .*sessionId=$hostSessionId")
$sr4Playout = @(Get-Lines $m01Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId") +
    @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=PLAYOUT_ACTIVITY .*sessionId=$hostSessionId")
$observe.Add("INGRESS_ACTIVITY_lines=$($sr4Ingress.Count)")
$observe.Add("PLAYOUT_ACTIVITY_lines=$($sr4Playout.Count)")
$sr5Removed = @(Get-Lines $m01Log "SHADOW hook=MEMBER_REMOVED session=$hostSessionId") + @(Get-Lines $m02Log "SHADOW hook=MEMBER_REMOVED session=$hostSessionId")
$sr6Stopped = @(Get-Lines $m01Log "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -SimpleMatch) +
    @(Get-Lines $m02Log "SHADOW hook=SESSION_STOPPED session=$hostSessionId outcome=APPLIED" -SimpleMatch)
$observe.Add("PA-SR5_MEMBER_REMOVED_lines=$($sr5Removed.Count)")
$observe.Add("PA-SR6_SESSION_STOPPED_APPLIED_lines=$($sr6Stopped.Count)")
($observe -join "`n") | Set-Content -Encoding utf8 $observePath
Add-Out "observe_only_file=PA_SR3_OBSERVE_ONLY.txt"
Add-Out ""

# --- SR3-A: origin emit ---
$originBuilt = @(Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT session=$hostSessionId factType=9 moduleId=$hostModuleId outcome=BUILT" -SimpleMatch)
$originEmitted = @(Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT session=$hostSessionId factType=9 moduleId=$hostModuleId target=$peerModuleId outcome=EMITTED" -SimpleMatch)
if ($originBuilt.Count -eq 0) {
    $originBuilt = @(Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT session=$hostSessionId factType=9 .*outcome=BUILT")
}
if ($originEmitted.Count -eq 0) {
    $originEmitted = @(Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT session=$hostSessionId factType=9 .*outcome=EMITTED .*target=$peerModuleId")
}

if ($originBuilt.Count -eq 0 -and $originEmitted.Count -eq 0) {
    $anyOrigin9 = @(Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT .*factType=9") + @(Get-Lines $m02Log "PROFILE01_ORIGIN_EMIT .*factType=9")
    if ($anyOrigin9.Count -eq 0) {
        Write-InvalidAndExit "INVALID_BUILD" "no_factType_9_origin_emit_observed" "VERIFY_B1_BUILD_INSTALLED"
    }
}

$anchorBuilt = $originBuilt | Select-Object -Last 1
$anchorEmitted = $originEmitted | Select-Object -Last 1
$anchorOrigin = if ($anchorBuilt) { $anchorBuilt } else { $anchorEmitted }

$sourceGeneration = Parse-Field $anchorOrigin "sourceGeneration"
$factDigest = Parse-Field $anchorOrigin "factDigest"
$originConferenceId = Parse-Field $anchorOrigin "conferenceId"
if ($originConferenceId) { $conferenceId = $originConferenceId }

# --- Pre-bind republication ---
$preBindDrop = @(Get-Lines $m01Log "PRE_BIND_NOT_RETAINED session=$hostSessionId factType=9" -SimpleMatch) +
    @(Get-Lines $m02Log "PRE_BIND_NOT_RETAINED session=$hostSessionId factType=9" -SimpleMatch)
$republicationRequired = $preBindDrop.Count -gt 0
$republicationObserved = $false
if ($republicationRequired -and $factDigest -and $sourceGeneration) {
    $postBindEmitted = @(Get-Lines $m01Log "PROFILE01_ORIGIN_EMIT session=$hostSessionId factType=9 .*outcome=EMITTED .*target=$peerModuleId .*sourceGeneration=$sourceGeneration .*factDigest=$factDigest")
    if ($postBindEmitted.Count -ge 1) { $republicationObserved = $true }
    if ($originEmitted.Count -ge 2) {
        $digests = @($originEmitted | ForEach-Object { Parse-Field $_ "factDigest" } | Where-Object { $_ })
        if (($digests | Select-Object -Unique).Count -eq 1 -and $digests[0] -eq $factDigest) {
            $republicationObserved = $true
        }
    }
    # B1-R2: post-bind replay is PROFILE01_SOURCE_POST_BIND_REPUBLISH (not a second ORIGIN_EMIT).
    if (-not $republicationObserved) {
        $sourcePostBindRepublish = @(
            Get-Lines $m01Log "PROFILE01_SOURCE_POST_BIND_REPUBLISH session=$hostSessionId .*target=$peerModuleId .*sourceGeneration=$sourceGeneration .*factDigest=$factDigest .*outcome=EMITTED"
        )
        if ($sourcePostBindRepublish.Count -ge 1) { $republicationObserved = $true }
    }
}

# --- SR3-B: peer ingress ---
$sourceApplied = @(Get-Lines $m02Log "WIRE_INGRESS session=$hostSessionId factType=9 outcome=APPLIED" -SimpleMatch)
if ($sourceApplied.Count -eq 0) {
    $sourceApplied = Get-Lines $m02Log "WIRE_INGRESS session=$hostSessionId factType=9 outcome=APPLIED"
}

# --- SR3-C/D: member media ready ---
$memberReadyApplied = @(Get-Lines $m02Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$hostModuleId outcome=APPLIED" -SimpleMatch)
$memberReadyDeferred = @(Get-Lines $m02Log "SHADOW hook=MEMBER_MEDIA_READY session=$hostSessionId module=$hostModuleId outcome=DEFERRED_NO_BINDING" -SimpleMatch)
$memberInstalled = @(Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_MEMBER_INSTALLED .*sessionId=$hostSessionId .*moduleId=$hostModuleId")

$failReasons = New-Object System.Collections.Generic.List[string]

if ($originEmitted.Count -eq 0) { $failReasons.Add("SR3-A_missing_ORIGIN_EMIT") }
if ($sourceApplied.Count -eq 0) { $failReasons.Add("SR3-B_missing_WIRE_INGRESS_APPLIED") }
if ($memberReadyApplied.Count -eq 0) { $failReasons.Add("SR3-C_missing_MEMBER_MEDIA_READY_APPLIED") }
if ($republicationRequired -and -not $republicationObserved) { $failReasons.Add("SR3-R_pre_bind_without_auto_republication") }
if ($memberReadyDeferred.Count -gt 0 -and $memberReadyApplied.Count -eq 0) {
    $failReasons.Add("SR3-D_only_DEFERRED_NO_BINDING")
}

Add-Out "PA-SR3 CHAIN"
Add-Out "SR3-A_ORIGIN_BUILT=$($originBuilt.Count -gt 0) ORIGIN_EMITTED=$($originEmitted.Count -gt 0)"
Add-Out "SR3-B_WIRE_INGRESS_APPLIED=$($sourceApplied.Count -gt 0)"
Add-Out "SR3-C_MEMBER_MEDIA_READY_APPLIED=$($memberReadyApplied.Count -gt 0)"
Add-Out "SR3-C_SHADOW_MEMBER_INSTALLED=$($memberInstalled.Count -gt 0)"
Add-Out "SR3-R_pre_bind_drop=$republicationRequired republication_observed=$republicationObserved"
Add-Out ""

$identityPath = Join-Path $LogDir "PA_SR3_IDENTITY.txt"
$identityLines = @(
    "conferenceId=$conferenceId",
    "sessionId=$hostSessionId",
    "senderModuleId=$hostModuleId",
    "sourceGeneration=$sourceGeneration",
    "factDigest=$factDigest",
    "membershipVersion=$membershipVersion",
    "mediaKeyEpoch=$mediaKeyEpoch",
    "ssrc=NOT_IN_OBS_V1",
    "sourceInstanceId=NOT_IN_OBS_V1",
    "republicationRequired=$republicationRequired",
    "republicationObserved=$republicationObserved"
)
($identityLines -join "`n") | Set-Content -Encoding utf8 $identityPath
Add-Out "identity_file=PA_SR3_IDENTITY.txt"
foreach ($line in $identityLines) { Add-Out $line }
Add-Out ""

if ($failReasons.Count -gt 0) {
    Add-Out "VERDICT=FAIL_PA_SR3"
    Add-Out ("reason=" + (($failReasons | Select-Object -Unique) -join ","))
    Add-Out "PA-SR3=FAIL"
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "Profile01_Meeting_chain=UNCHANGED_CLOSED"
    Add-Out "PR-PA-SR-B1=UNIT-PROVEN_FIELD_FAIL"
    Add-Out "Next=RCA_PA_SR3_SOURCE_CHAIN"
    $exitCode = 1
} else {
    Add-Out "VERDICT=PASS"
    Add-Out "PA-SR3=CLOSED / FIELD-PROVEN"
    Add-Out "PR-PA-SR-B1=CLOSED / UNIT+FIELD-PROVEN"
    Add-Out "PA-SR4_to_SR6=OBSERVE_ONLY_NOT_CLOSED"
    Add-Out "J0_status=UNCHANGED_CLOSED"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Meeting_media_cutover=NOT_AUTHORIZED"
    Add-Out "Next=PA_SR4_FIELD_OR_SHADOW_RUNTIME_ROLLUP"
    $exitCode = 0
}

$adjudicationPath = Join-Path $LogDir "ADJUDICATION.txt"
($linesOut -join "`n") | Set-Content -Encoding utf8 $adjudicationPath
Write-Host ($linesOut -join "`n")
exit $exitCode
