# G2 Real Deployment — single-run field adjudication (semantic: real underlay 3+ nodes only).
# Exit: 0 PASS · 1 VALID FAIL · 2 INVALID/ENV_BLOCKED
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

$gate = Read-KvFile (Join-Path $LogDir "G2_GATE.txt")
$preflightPath = Join-Path $LogDir "preflight.json"
$m01 = Get-ModuleLog "M01"
$m02 = Get-ModuleLog "M02"
$m03 = Get-ModuleLog "M03"
$rc1 = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$shadow = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1['hostSessionId']) { $rc1['hostSessionId'] } else { "" }

if ($gate['verdict'] -eq "ENV_BLOCKED") {
    $lines = @(
        "G2_REAL_DEPLOYMENT"
        "verdict=ENV_BLOCKED"
        "reason=$($gate['reason'])"
        "semantic=real_underlay_3node_only"
        "notSubstitutableFor=G1,G3"
        "G2_RESULT=ENV_BLOCKED"
        "Next=CONNECT_M03_SAME_MESH_L3_RERUN"
    )
    ($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "G2_RESULT.txt")
    Write-Host ($lines -join "`n")
    exit 2
}

$nodesSameL3 = $false
$nodeCount = 0
if (Test-Path $preflightPath) {
    $pf = Get-Content $preflightPath -Raw | ConvertFrom-Json
    $up = @($pf | Where-Object { $_.Ok })
    $nodeCount = $up.Count
    $prefixes = @($up | ForEach-Object { ($_.Ip -split '\.')[0..2] -join '.' } | Select-Object -Unique)
    $nodesSameL3 = ($nodeCount -ge 3) -and ($prefixes.Count -eq 1)
}

$membershipM03 = (Count-Matches $m03 "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$sessionId") -gt 0 -or
    (Count-Matches $m03 "SESSION_BIND_DRAIN session=$sessionId") -gt 0
$membershipM02 = (Count-Matches $m02 "CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED .* session=$sessionId") -gt 0
$membershipConverged = $membershipM02 -and $membershipM03

$cutoverOk = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
$multicastActive = (Count-Matches $m02 "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=MULTICAST_ACTIVE") -gt 0
$m01Ingress = (Count-Matches $m01 "INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0
$m03Ingress = (Count-Matches $m03 "INGRESS_ACTIVITY|WIRE_INGRESS .*outcome=APPLIED") -gt 0
$multiSource = $m01Ingress -and $m03Ingress
$lifecycleOk = (Count-Matches $m03 "leave|rejoin|MEMBER") -ge 0  # observe; not hard-fail if absent
$sessionStopped = ($shadow['obsSessionStopped'] -eq "observed") -or
    ((Count-Matches $m01 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0)
$teardownEmpty = ($shadow['obsTeardownEmpty'] -eq "observed") -or ($rc1['obsTeardownEmpty'] -eq "observed")
$dualOwner = Count-Matches $m02 "OWNERSHIP_INVARIANT_VIOLATION"
$runtimeFatal = (Count-Matches $m02 "SHADOW_FAILED") + (Count-Matches $m01 "SHADOW_FAILED") +
    (Count-Matches $m02 "trigger=REPLACEMENT_RUNTIME_FATAL_FAILURE")
$unexpectedRollback = Count-Matches $m02 "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED"

$verdict = "PASS"
$reason = "real_mesh_3node_production_meeting"
if (-not $nodesSameL3) {
    $verdict = "ENV_BLOCKED"; $reason = "fewer_than_3_nodes_same_real_underlay"
} elseif (-not $sessionId) {
    $verdict = "INVALID"; $reason = "harness_aborted_before_session"
} elseif (-not $m01 -or -not $m02 -or -not $m03) {
    $verdict = "INVALID"; $reason = "missing_capture"
} elseif ($dualOwner -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "dual_audible_owner"
} elseif ($runtimeFatal -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "runtime_fatal"
} elseif (-not $membershipConverged) {
    $verdict = "VALID_FAIL"; $reason = "membership_not_converged_3node"
} elseif (-not $cutoverOk -or -not $multicastActive) {
    $verdict = "VALID_FAIL"; $reason = "production_multicast_not_observed"
} elseif (-not $multiSource) {
    $verdict = "VALID_FAIL"; $reason = "multi_source_ingress_not_observed"
} elseif ($unexpectedRollback -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "unexpected_auto_rollback"
} elseif (-not $sessionStopped) {
    $verdict = "VALID_FAIL"; $reason = "meeting_end_not_observed"
} elseif (-not $teardownEmpty) {
    $verdict = "VALID_FAIL"; $reason = "teardown_not_empty"
}

$lines = @(
    "G2_REAL_DEPLOYMENT"
    "nodeCountSameL3=$nodeCount"
    "membershipConverged=$membershipConverged"
    "cutoverOk=$cutoverOk"
    "multicastActive=$multicastActive"
    "multiSourceIngress=$multiSource"
    "dualOwnerCount=$dualOwner"
    "runtimeFatalCount=$runtimeFatal"
    "unexpectedRollbackCount=$unexpectedRollback"
    "sessionStopped=$sessionStopped"
    "teardownEmpty=$teardownEmpty"
    "hostSessionId=$sessionId"
    "verdict=$verdict"
    "reason=$reason"
    "semantic=real_underlay_3node_only"
    "notSubstitutableFor=G1,G3"
)

if ($verdict -eq "PASS") {
    $lines += "G2_RESULT=PASS"
    $lines += "Next=GA_REVIEW_WHEN_G1_G3_PASS"
} elseif ($verdict -eq "ENV_BLOCKED") {
    $lines += "G2_RESULT=ENV_BLOCKED"
    $lines += "Next=CONNECT_M03_SAME_MESH_L3_RERUN"
} elseif ($verdict -eq "INVALID") {
    $lines += "G2_RESULT=INVALID"
    $lines += "Next=FIX_HARNESS_OR_ENV_RERUN_G2"
} else {
    $lines += "G2_RESULT=VALID_FAIL"
    $lines += "Next=RCA_DEPLOYMENT_DEFECT"
}

($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "G2_RESULT.txt")
($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host ($lines -join "`n")

if ($verdict -eq "PASS") { exit 0 }
if ($verdict -in @("ENV_BLOCKED", "INVALID")) { exit 2 }
exit 1
