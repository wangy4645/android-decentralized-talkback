# G3 Stability — PaG2 3-node production multicast soak adjudication (duration + stability hard gates).
# G3a ≥15m blocks GA · G3b ≥2h blocks ADR-0056 retirement only.
# Exit: 0 PASS · 1 VALID FAIL · 2 INVALID
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [int]$MinSoakSec = 900,
    [ValidateSet("G3a", "G3b", "G3")]
    [string]$Tier = "G3a"
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
$m03 = Get-ModuleLog "M03"
$rc1 = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$shadow = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$meta = Read-KvFile (Join-Path $LogDir "RUN_META.txt")
$g2Gate = Read-KvFile (Join-Path $LogDir "G2_GATE.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1['hostSessionId']) { $rc1['hostSessionId'] } else { "" }

if ($meta['gate'] -match '^G3') { $Tier = $meta['gate'] }

$soakSec = 0
if ($rc1['multicastProductionDurationSec']) {
    $soakSec = [int]$rc1['multicastProductionDurationSec']
}

$nodesSameL3 = $false
$nodeCount = 0
$preflightPath = Join-Path $LogDir "preflight.json"
if (Test-Path $preflightPath) {
    $pf = Get-Content $preflightPath -Raw | ConvertFrom-Json
    $up = @($pf | Where-Object { $_.Ok })
    $nodeCount = $up.Count
    $prefixes = @($up | ForEach-Object { ($_.Ip -split '\.')[0..2] -join '.' } | Select-Object -Unique)
    $nodesSameL3 = ($nodeCount -ge 3) -and ($prefixes.Count -eq 1)
}

$cutoverOk = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
$multicastActive = (Count-Matches $m02 "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=MULTICAST_ACTIVE") -gt 0
$t0Reached = $cutoverOk -and $multicastActive
$dualOwner = Count-Matches $m02 "OWNERSHIP_INVARIANT_VIOLATION"
$runtimeFatal = (Count-Matches $m02 "SHADOW_FAILED") + (Count-Matches $m01 "SHADOW_FAILED") +
    (Count-Matches $m03 "SHADOW_FAILED") + (Count-Matches $m02 "trigger=REPLACEMENT_RUNTIME_FATAL_FAILURE")
$unexpectedRollback = Count-Matches $m02 "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED"
$sessionStopped = ($shadow['obsSessionStopped'] -eq "observed") -or
    ((Count-Matches $m01 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0)
$teardownEmpty = ($shadow['obsTeardownEmpty'] -eq "observed") -or ($rc1['obsTeardownEmpty'] -eq "observed")

# Non-blocking ledger (observe only — do not hard-fail G3a)
$softMultiSource = (Count-Matches $m01 "INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0 -and
    (Count-Matches $m03 "INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0
$softObsProductionWrites = $rc1['obsProductionWrites']
$softPlc = Count-Matches $m02 "PLC"
$softUnderrun = Count-Matches $m02 "underrun"
$softJitter = Count-Matches $m02 "jitter"

$verdict = "PASS"
$reason = "long_production_multicast_soak"
if ($g2Gate['verdict'] -eq "ENV_BLOCKED") {
    $verdict = "INVALID"; $reason = "env_blocked_$($g2Gate['reason'])"
} elseif (-not $nodesSameL3) {
    $verdict = "INVALID"; $reason = "fewer_than_3_nodes_same_real_underlay"
} elseif (-not $t0Reached) {
    # T0 = CUTOVER_SUCCEEDED + MULTICAST_ACTIVE. Abort before that is harness/env INVALID —
    # never a G3 stability VALID_FAIL (soak duration gate has not started).
    $verdict = "INVALID"; $reason = "harness_aborted_before_t0"
} elseif (-not $sessionId -or -not $m01 -or -not $m02 -or -not $m03) {
    $verdict = "INVALID"; $reason = "missing_capture"
} elseif ($soakSec -lt $MinSoakSec) {
    $verdict = "VALID_FAIL"; $reason = "soak_duration_below_min_${MinSoakSec}s"
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

$blocksGa = if ($Tier -eq "G3b") { "false" } else { "true" }
$blocksRetirement = if ($Tier -eq "G3b") { "true" } else { "false" }

$lines = @(
    "G3_STABILITY"
    "tier=$Tier"
    "executionPath=PaG2_3node_real_mesh"
    "minSoakSec=$MinSoakSec"
    "observedSoakSec=$soakSec"
    "t0Reached=$t0Reached"
    "nodeCountSameL3=$nodeCount"
    "cutoverOk=$cutoverOk"
    "multicastActive=$multicastActive"
    "dualOwnerCount=$dualOwner"
    "runtimeFatalCount=$runtimeFatal"
    "unexpectedRollbackCount=$unexpectedRollback"
    "sessionStopped=$sessionStopped"
    "teardownEmpty=$teardownEmpty"
    "hostSessionId=$sessionId"
    "soft_ledger_multiSourceIngress=$softMultiSource"
    "soft_ledger_obsProductionWrites=$softObsProductionWrites"
    "soft_obs_plcHits=$softPlc"
    "soft_obs_underrunHits=$softUnderrun"
    "soft_obs_jitterHits=$softJitter"
    "blocksGa=$blocksGa"
    "blocksAdr0056Retirement=$blocksRetirement"
    "verdict=$verdict"
    "reason=$reason"
    "semantic=duration_stability_only"
    "notSubstitutableFor=G1,G2"
)

if ($verdict -eq "PASS") {
    $lines += "G3_RESULT=PASS"
    if ($Tier -eq "G3b") {
        $lines += "Next=ADR0056_RETIREMENT_REVIEW_WHEN_READY"
    } else {
        $lines += "Next=GA_AUTHORIZATION_REVIEW"
    }
} elseif ($verdict -eq "INVALID") {
    $lines += "G3_RESULT=INVALID"
    $lines += "Next=FIX_HARNESS_OR_ENV_RERUN_$Tier"
} else {
    $lines += "G3_RESULT=VALID_FAIL"
    $lines += "Next=RCA_STABILITY_DEFECT"
}

($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "G3_RESULT.txt")
($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "${Tier}_RESULT.txt")
($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($lines -join "`n")

if ($verdict -eq "PASS") { exit 0 }
if ($verdict -eq "INVALID") { exit 2 }
exit 1
