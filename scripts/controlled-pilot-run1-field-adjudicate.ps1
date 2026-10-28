# Controlled Pilot Run-1 — light product-ops adjudication (reuses RC1/SR6 OBS).
# Exit: 0 PASS · 1 FAIL · 2 INVALID
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
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

$m01 = Get-ModuleLog "M01"
$m02 = Get-ModuleLog "M02"
$rc1 = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$shadow = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1['hostSessionId']) { $rc1['hostSessionId'] } else { "" }

$started = Get-Date
if (Test-Path (Join-Path $LogDir "RUN_META.txt")) {
    $meta = Get-Content (Join-Path $LogDir "RUN_META.txt") -Raw
    if ($meta -match 'run_stamp=([0-9]{8}-[0-9]{6})') {
        try { $started = [DateTime]::ParseExact($Matches[1], "yyyyMMdd-HHmmss", $null) } catch {}
    }
}
$durationSec = [int]((Get-Date) - $started).TotalSeconds

$cutoverOk = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
$cutoverFail = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_FAILED session=$sessionId") -gt 0
$ownershipViolations = Count-Matches $m02 "OWNERSHIP_INVARIANT_VIOLATION"
$runtimeFatals = (Count-Matches $m02 "SHADOW_FAILED") + (Count-Matches $m01 "SHADOW_FAILED") +
    (Count-Matches $m02 "trigger=REPLACEMENT_RUNTIME_FATAL_FAILURE")
# Session-end ownership release is expected during Meeting end — not a fatal.
$unexpectedRollback = Count-Matches $m02 "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED"
$manualRollback = Count-Matches $m02 "RC1_FIELD outcome=ROLLBACK_SUCCEEDED .*detail=MANUAL"
$multicastActive = (Count-Matches $m02 "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=MULTICAST_ACTIVE") -gt 0
$sessionStopped = ($shadow['obsSessionStopped'] -eq "observed") -or
    ((Count-Matches $m01 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0) -or
    ((Count-Matches $m02 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0)
$teardownEmpty = ($shadow['obsTeardownEmpty'] -eq "observed") -or ($rc1['obsTeardownEmpty'] -eq "observed")
$multicastDuration = if ($rc1['multicastProductionDurationSec']) { $rc1['multicastProductionDurationSec'] } else { "0" }

# Soft observations (record only)
$softPlc = Count-Matches $m02 "PLC"
$softUnderrun = Count-Matches $m02 "underrun"
$softJitter = Count-Matches $m02 "jitter"

$cutover = if ($cutoverOk -and -not $cutoverFail) { "SUCCESS" } elseif ($cutoverFail -or -not $cutoverOk) { "FAIL" } else { "UNKNOWN" }
$sessionCompleted = if ($sessionStopped) { "true" } else { "false" }
$teardownOk = if ($teardownEmpty) { "true" } else { "false" }

$verdict = "PASS"
$reason = "multicast_production_meeting_completed"
if (-not $sessionId -or -not $m01 -or -not $m02) {
    $verdict = "INVALID"; $reason = "missing_capture"
} elseif ($ownershipViolations -gt 0) {
    $verdict = "FAIL"; $reason = "dual_audible_owner"
} elseif ($runtimeFatals -gt 0) {
    $verdict = "FAIL"; $reason = "runtime_fatal"
} elseif ($cutover -ne "SUCCESS") {
    $verdict = "FAIL"; $reason = "cutover_failed"
} elseif ($unexpectedRollback -gt 0) {
    $verdict = "FAIL"; $reason = "unexpected_auto_rollback"
} elseif (-not $multicastActive) {
    $verdict = "FAIL"; $reason = "multicast_production_not_observed"
} elseif ($sessionCompleted -ne "true") {
    $verdict = "FAIL"; $reason = "session_not_completed"
} elseif ($teardownOk -ne "true") {
    $verdict = "FAIL"; $reason = "teardown_not_empty"
}

$lines = @(
    "RUN_1"
    "duration=$durationSec"
    "cutover=$cutover"
    "multicastProductionDuration=$multicastDuration"
    "unexpectedRollbackCount=$unexpectedRollback"
    "manualRollbackCount=$manualRollback"
    "ownershipViolationCount=$ownershipViolations"
    "runtimeFatalCount=$runtimeFatals"
    "sessionCompleted=$sessionCompleted"
    "teardownEmpty=$teardownOk"
    "verdict=$verdict"
    "reason=$reason"
    "hostSessionId=$sessionId"
    "soft_obs_plcHits=$softPlc"
    "soft_obs_underrunHits=$softUnderrun"
    "soft_obs_jitterHits=$softJitter"
    "ADR-0056=KEEP"
    "mandatoryRollback=false"
)

if ($verdict -eq "PASS") {
    $lines += "CONTROLLED_PILOT_RUN_1=PASS"
    $lines += "Next=CONTINUE_RUN_2"
} elseif ($verdict -eq "INVALID") {
    $lines += "CONTROLLED_PILOT_RUN_1=INVALID"
    $lines += "Next=RERUN_PILOT_1"
} else {
    $lines += "CONTROLLED_PILOT_RUN_1=FAIL"
    $lines += "Next=RCA_PILOT_DEFECT_NO_KOTLIN_WITHOUT_DEFECT"
}

($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "RUN_1_RESULT.txt")
Write-Host ($lines -join "`n")

if ($verdict -eq "PASS") { exit 0 }
if ($verdict -eq "INVALID") { exit 2 }
exit 1
