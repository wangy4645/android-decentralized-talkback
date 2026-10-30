# R2 Release Smoke — GA default path (R2-A) field adjudication.
# Exit: 0 PASS · 1 VALID_FAIL · 2 INVALID
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [int]$MinSoakSec = 60
)

$ErrorActionPreference = "Stop"

function Get-ModuleLog([string]$Module) {
    foreach ($name in @("$Module-logcat.txt", "$Module-talkback.log")) {
        $p = Join-Path $LogDir $name
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Read-KvFile([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $map[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    return $map
}

function Count-Matches([string]$Path, [string]$Pattern) {
    if (-not $Path -or -not (Test-Path $Path)) { return 0 }
    return @(Select-String -Path $Path -Pattern $Pattern -ErrorAction SilentlyContinue).Count
}

function Test-TeardownEmptyLine([string]$Line) {
    return ($Line -match "transportScopeActive=false") -and
        ($Line -match "catalogEntries=0") -and
        ($Line -match "admittedCount=0") -and
        ($Line -match "activeJitterSources=0") -and
        ($Line -match "liveDecoders=0") -and
        ($Line -match "jitterBufferCount=0")
}

# ADR-0062: audible ownership is per-device resource-level mutual exclusion.
# Host cutover evidence lives on M01; M02 remains DUT for soak/shadow observation.
function Test-DeviceMulticastActive([string]$Path, [string]$SessionId) {
    if (-not $Path -or -not (Test-Path $Path)) { return $false }
    if ((Count-Matches $Path "AUDIBLE_OWNERSHIP_STATE session=$SessionId state=MULTICAST_ACTIVE") -gt 0) { return $true }
    if ((Count-Matches $Path "SHADOW_PLAYOUT_CYCLE.*sessionId=$SessionId.*audioTrackOwner=MULTICAST_PRODUCTION") -gt 0) { return $true }
    if ((Count-Matches $Path "PLAYOUT_FUNNEL_CYCLE.*sessionId=$SessionId.*audioTrackOwner=MULTICAST_PRODUCTION") -gt 0) { return $true }
    return $false
}

function Test-HostProductionWrites([string]$HostLog, [string]$SessionId, [hashtable]$Rc1Gate) {
    if ($Rc1Gate['obsProductionWrites'] -eq "observed") { return $true }
    if ((Count-Matches $HostLog "SHADOW_PLAYOUT_CYCLE.*sessionId=$SessionId.*audioTrackOwner=MULTICAST_PRODUCTION.*successfulPlayoutWrites=[1-9]") -gt 0) { return $true }
    if ((Count-Matches $HostLog "PLAYOUT_FUNNEL_CYCLE.*sessionId=$SessionId.*audioTrackOwner=MULTICAST_PRODUCTION.*successfulPlayoutWrites=[1-9]") -gt 0) { return $true }
    return $false
}

$m01 = Get-ModuleLog "M01"
$m02 = Get-ModuleLog "M02"
$m03 = Get-ModuleLog "M03"
$rc1 = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$shadow = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1['hostSessionId']) { $rc1['hostSessionId'] } else { "" }

$soakSec = 0
if ($rc1['multicastProductionDurationSec']) {
    $soakSec = [int]$rc1['multicastProductionDurationSec']
}

$autoCutover = (Count-Matches $m01 "RC1_FIELD outcome=AUTO_CUTOVER_SUCCEEDED session=$sessionId") -gt 0
if (-not $autoCutover) {
    $autoCutover = (Count-Matches $m02 "RC1_FIELD outcome=AUTO_CUTOVER_SUCCEEDED session=$sessionId") -gt 0
}
$cutoverOk = (Count-Matches $m01 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
if (-not $cutoverOk) {
    $cutoverOk = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
}
$hostMulticastActive = Test-DeviceMulticastActive $m01 $sessionId
$peerMulticastActiveM02 = Test-DeviceMulticastActive $m02 $sessionId
$peerMulticastActiveM03 = if ($m03) { Test-DeviceMulticastActive $m03 $sessionId } else { $false }
$multicastActive = $hostMulticastActive
$shadowStarted = (Count-Matches $m02 "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$sessionId") -gt 0
if (-not $shadowStarted) {
    $shadowStarted = (Count-Matches $m01 "PROFILE01_SHADOW_RUNTIME .*phase=SHADOW_SESSION_STARTED .*sessionId=$sessionId") -gt 0
}
$productionWrites = Test-HostProductionWrites $m01 $sessionId $rc1
$m01Ingress = (Count-Matches $m01 "INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0
$m03Ingress = if ($m03) { (Count-Matches $m03 "INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0 } else { $false }
$multiSource = $m01Ingress -and $m03Ingress
$dualOwner = 0
$runtimeFatal = 0
$unexpectedRollback = 0
$manualPilotEnable = $false
foreach ($path in @($m01, $m02, $m03)) {
    if (-not $path) { continue }
    $dualOwner += Count-Matches $path "OWNERSHIP_INVARIANT_VIOLATION"
    $runtimeFatal += Count-Matches $path "SHADOW_FAILED"
    $runtimeFatal += Count-Matches $path "trigger=REPLACEMENT_RUNTIME_FATAL_FAILURE"
    $unexpectedRollback += Count-Matches $path "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED"
    if ((Count-Matches $path "RC1_FIELD outcome=PILOT_ENABLED") -gt 0) { $manualPilotEnable = $true }
}

$sessionStopped = ($shadow['obsSessionStopped'] -eq "observed") -or
    ((Count-Matches $m01 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0) -or
    ((Count-Matches $m02 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0)

$teardownEmptyM01 = $false
$teardownEmptyM02 = $false
if ($m01) {
    foreach ($td in @(Select-String -Path $m01 -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$sessionId" -ErrorAction SilentlyContinue)) {
        if (Test-TeardownEmptyLine $td.Line) { $teardownEmptyM01 = $true; break }
    }
}
if ($m02) {
    foreach ($td in @(Select-String -Path $m02 -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$sessionId" -ErrorAction SilentlyContinue)) {
        if (Test-TeardownEmptyLine $td.Line) { $teardownEmptyM02 = $true; break }
    }
}
$teardownEmpty = $teardownEmptyM01 -and $teardownEmptyM02

$verdict = "PASS"
$reason = "r2_ga_default_path_release_smoke"
if (-not $sessionId -or -not $m01 -or -not $m02) {
    $verdict = "INVALID"; $reason = "missing_capture"
} elseif (-not $shadowStarted) {
    $verdict = "INVALID"; $reason = "harness_aborted_before_shadow_session"
} elseif (-not $autoCutover) {
    $verdict = "VALID_FAIL"; $reason = "auto_cutover_not_observed"
} elseif ($manualPilotEnable) {
    $verdict = "VALID_FAIL"; $reason = "manual_pilot_enable_observed_on_release_path"
} elseif (-not $multicastActive -or -not $cutoverOk) {
    $verdict = "VALID_FAIL"; $reason = "multicast_active_not_sustained"
} elseif ($soakSec -lt $MinSoakSec) {
    $verdict = "VALID_FAIL"; $reason = "soak_duration_below_min_${MinSoakSec}s"
} elseif (-not $productionWrites) {
    $verdict = "VALID_FAIL"; $reason = "production_media_not_observed"
} elseif (-not $multiSource) {
    $verdict = "VALID_FAIL"; $reason = "multi_source_ingress_not_observed"
} elseif ($dualOwner -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "dual_audible_owner"
} elseif ($runtimeFatal -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "runtime_fatal"
} elseif ($unexpectedRollback -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "unexpected_auto_rollback"
} elseif (-not $sessionStopped -or -not $teardownEmpty) {
    $verdict = "VALID_FAIL"
    $reason = if (-not $sessionStopped) { "teardown_completion_failed" } else { "teardown_not_empty" }
}

$lines = @(
    "R2_RELEASE_SMOKE"
    "case=R2-A"
    "gateFamily=R2_GA_DEFAULT_PATH"
    "buildVariant=release"
    "noAdbCutoverBroadcast=true"
    "minSoakSec=$MinSoakSec"
    "observedSoakSec=$soakSec"
    "shadowSessionStarted=$shadowStarted"
    "autoCutoverSucceeded=$autoCutover"
    "cutoverOk=$cutoverOk"
    "hostMulticastActive=$hostMulticastActive"
    "peerMulticastActiveM02=$peerMulticastActiveM02"
    "peerMulticastActiveM03=$peerMulticastActiveM03"
    "multicastActive=$multicastActive"
    "productionWrites=$productionWrites"
    "multiSourceIngress=$multiSource"
    "manualPilotEnableObserved=$manualPilotEnable"
    "dualOwnerCount=$dualOwner"
    "runtimeFatalCount=$runtimeFatal"
    "unexpectedRollbackCount=$unexpectedRollback"
    "sessionStopped=$sessionStopped"
    "teardownEmpty=$teardownEmpty"
    "teardownEmptyM01=$teardownEmptyM01"
    "teardownEmptyM02=$teardownEmptyM02"
    "hostSessionId=$sessionId"
    "verdict=$verdict"
    "reason=$reason"
)

if ($verdict -eq "PASS") {
    $lines += "R2A_RESULT=PASS"
    $lines += "Next=R2_B_KILL_SWITCH_UNIT_EVIDENCE"
} elseif ($verdict -eq "INVALID") {
    $lines += "R2A_RESULT=INVALID"
    $lines += "Next=FIX_HARNESS_OR_ENV_RERUN_R2A"
} else {
    $lines += "R2A_RESULT=VALID_FAIL"
    $lines += "Next=RCA_R1_RELEASE_INTEGRATION"
}

($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "R2A_RESULT.txt")
($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($lines -join "`n")

if ($verdict -eq "PASS") { exit 0 }
if ($verdict -eq "INVALID") { exit 2 }
exit 1
