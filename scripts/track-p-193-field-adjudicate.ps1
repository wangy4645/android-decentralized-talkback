# #193 Track P adjudicate — M01→M02 watchdog defer vs terminal disposition
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

function Resolve-M01Log {
    param([string]$Dir)
    foreach ($name in @("M01-logcat-dump.txt", "M01-talkback.log")) {
        $p = Join-Path $Dir $name
        if (Test-Path $p) { return $p }
    }
    throw "M01 log not found under $Dir"
}

function Count-Hits {
    param([string]$Path, [string]$Pattern)
    if (-not (Test-Path $Path)) { return 0 }
    @(Select-String -Path $Path -Pattern $Pattern).Count
}

function First-Line {
    param([string]$Path, [string]$Pattern)
    $m = Select-String -Path $Path -Pattern $Pattern | Select-Object -First 1
    if ($m) { return $m.Line.Trim() }
    return $null
}

function Resolve-TrackPRunMode {
    param([string]$Dir)
    $metaPath = Join-Path $Dir "RUN_META.txt"
    if (-not (Test-Path $metaPath)) { return "auto" }
    $meta = Get-Content $metaPath -Raw
    if ($meta -match 'directed_repro=post_fix|expect_at_timeout=ATTEMPT_TIMEOUT_terminal') {
        return "post-fix"
    }
    if ($meta -match 'directed_repro=1|directed_repro=#2|RCA_REPRO|prior_verdict=INCONCLUSIVE') {
        return "pre-fix"
    }
    return "auto"
}

$m01 = Resolve-M01Log -Dir $LogDir
$runMode = Resolve-TrackPRunMode -Dir $LogDir
Write-Host "LogDir=$LogDir"
Write-Host "M01=$m01"
Write-Host "RunMode=$runMode"
Write-Host ""

$reattach = Count-Hits $m01 "REATTACH_ACCEPTED.*remote=M02|RECOVERY_REATTACH_ACCEPTED.*remote=M02"
$icePending = Count-Hits $m01 "remote=M02.*ICE_TRANSPORT_PENDING|edge=M02.*ICE_TRANSPORT_PENDING|reason=ICE_TRANSPORT_PENDING.*remote=M02"
$attemptTimeout = Count-Hits $m01 "RECOVERY_ATTEMPT_TIMEOUT.*edge=M02|RECOVERY_ATTEMPT_TIMEOUT.*remote=M02|RECOVERY_DECISION.*edge=M02.*ATTEMPT_TIMEOUT"
$watchdogDeferred = Count-Hits $m01 "RECOVERY_WATCHDOG_DEFERRED.*edge=M02|RECOVERY_WATCHDOG_DEFERRED.*remote=M02"
$watchdogDeferredAtFire = Count-Hits $m01 "RECOVERY_WATCHDOG_DEFERRED.*edge=M02.*CAPABILITY_UNAVAILABLE_AT_FIRE"
$terminalSuccess = Count-Hits $m01 "RECOVERY_ATTEMPT_STATE.*remote=M02.*to=ATTEMPT_SUCCEEDED|RECOVERY_ATTEMPT_STATE.*remote=M02.*to=ATTEMPT_FAILED"
$timeoutDecision = Count-Hits $m01 "RECOVERY_DECISION.*edge=M02.*ATTEMPT_TIMEOUT|RECOVERY_FINAL_EVALUATION.*edge=M02.*ATTEMPT_TIMEOUT"

Write-Host "=== 5 REQUIRED OBSERVATION POINTS (M01, M01->M02) ==="
$points = [ordered]@{
    "1 REATTACH_ACCEPTED" = $reattach
    "2 ICE_TRANSPORT_PENDING" = $icePending
    "3 ATTEMPT_TIMEOUT" = $attemptTimeout
    "4 RECOVERY_WATCHDOG_DEFERRED" = $watchdogDeferred
    "5 terminal ATTEMPT_SUCCEEDED|FAILED" = $terminalSuccess
}
$evidenceOk = $true
foreach ($k in $points.Keys) {
    $n = $points[$k]
    $ok = $n -ge 1
    if ($k -eq "4 RECOVERY_WATCHDOG_DEFERRED") {
        $ok = switch ($runMode) {
            "post-fix" { $n -eq 0 }
            "pre-fix" { $n -ge 1 }
            default { $true }
        }
    } elseif ($k -ne "5 terminal ATTEMPT_SUCCEEDED|FAILED") {
        if (-not $ok) { $evidenceOk = $false }
    }
    $label = if ($k -eq "4 RECOVERY_WATCHDOG_DEFERRED") {
        switch ($runMode) {
            "post-fix" { if ($ok) { "expected=0" } else { "FAIL>0" } }
            "pre-fix" { if ($ok) { "repro seen" } else { "MISS repro" } }
            default { if ($ok -or $n -ge 1) { "seen" } else { "none" } }
        }
    } elseif ($ok -or $k -like "5*") {
        "seen"
    } else {
        "MISS"
    }
    Write-Host ("  {0,-40} {1,4} {2}" -f $k, $n, $label)
}

Write-Host ""
Write-Host "=== SAMPLE LINES ==="
foreach ($pat in @(
    "RECOVERY_REATTACH_ACCEPTED.*remote=M02",
    "ICE_TRANSPORT_PENDING.*remote=M02",
    "RECOVERY_ATTEMPT_TIMEOUT.*edge=M02",
    "RECOVERY_WATCHDOG_DEFERRED.*edge=M02",
    "RECOVERY_ATTEMPT_STATE.*remote=M02.*ATTEMPT_FAILED",
    "RECOVERY_ATTEMPT_STATE.*remote=M02.*ATTEMPT_SUCCEEDED"
)) {
    $line = First-Line $m01 $pat
    if ($line) { Write-Host $line }
}

Write-Host ""
Write-Host "=== ADR-0055 TRACK P CONTRACT ==="
$contractPass = $false
$contractFail = $false
$fixVerified = $false
if ($attemptTimeout -ge 1 -and $terminalSuccess -ge 1 -and $timeoutDecision -ge 1) {
    $contractPass = $true
}
if ($attemptTimeout -ge 1 -and $watchdogDeferred -ge 1 -and $terminalSuccess -eq 0) {
    $contractFail = $true
}
if ($contractPass -and $watchdogDeferred -eq 0) {
    $fixVerified = $true
}
if ($attemptTimeout -ge 1 -and $watchdogDeferred -ge 1 -and $terminalSuccess -ge 1) {
    Write-Host "  NOTE: both WATCHDOG_DEFERRED and terminal — inspect ordering manually"
}

if ($fixVerified -and ($runMode -eq "post-fix" -or $runMode -eq "auto")) {
    Write-Host "  TRACK_P_FIX: VERIFIED (timeout -> terminal; WATCHDOG_DEFERRED=0)"
    Write-Host "  TRACK_P_CONTRACT: PASS (timeout -> decision -> terminal)"
} elseif ($contractPass) {
    Write-Host "  TRACK_P_CONTRACT: PASS (timeout -> decision -> terminal)"
} elseif ($contractFail) {
    Write-Host "  TRACK_P_CONTRACT: FAIL (timeout -> WATCHDOG_DEFERRED, no terminal)"
    Write-Host "  RCA_REPRO: STABLE (#193 hypothesis confirmed)"
    Write-Host "  IMPL: Track P fix authorized under ADR-0055"
} else {
    Write-Host "  TRACK_P_CONTRACT: INCONCLUSIVE (check log / extend wait)"
}

if (-not $evidenceOk) {
    Write-Host "  EVIDENCE: INCOMPLETE (missing required observation points)"
} elseif ($runMode -eq "post-fix" -and $watchdogDeferred -gt 0) {
    Write-Host "  EVIDENCE: FAIL (post-fix expects WATCHDOG_DEFERRED=0)"
} elseif ($runMode -eq "pre-fix" -and $watchdogDeferred -eq 0 -and $contractFail) {
    Write-Host "  EVIDENCE: INCOMPLETE (pre-fix repro expects WATCHDOG_DEFERRED)"
}

Write-Host ""
Write-Host "Session hint:"
First-Line $m01 "session=[a-f0-9-]{36}" | ForEach-Object { Write-Host "  $_" }
