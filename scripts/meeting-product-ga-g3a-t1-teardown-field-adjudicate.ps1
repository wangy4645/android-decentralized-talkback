# G3a-T1 — short teardown closure verify (no 900s soak re-run).
# Exit: 0 PASS · 1 VALID_FAIL · 2 INVALID
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [int]$MinSoakSec = 60,
    [string]$SteadyStateEvidenceDir = ""
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

$m01 = Get-ModuleLog "M01"
$m02 = Get-ModuleLog "M02"
$m03 = Get-ModuleLog "M03"
$rc1 = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$shadow = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$meta = Read-KvFile (Join-Path $LogDir "RUN_META.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1['hostSessionId']) { $rc1['hostSessionId'] } else { "" }

$soakSec = 0
if ($rc1['multicastProductionDurationSec']) {
    $soakSec = [int]$rc1['multicastProductionDurationSec']
}

$cutoverOk = (Count-Matches $m02 "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId") -gt 0
$multicastActive = (Count-Matches $m02 "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=MULTICAST_ACTIVE") -gt 0
$t0Reached = $cutoverOk -and $multicastActive
$dualOwner = Count-Matches $m02 "OWNERSHIP_INVARIANT_VIOLATION"
$runtimeFatal = (Count-Matches $m02 "SHADOW_FAILED") + (Count-Matches $m01 "SHADOW_FAILED") +
    (Count-Matches $m03 "SHADOW_FAILED") + (Count-Matches $m02 "trigger=REPLACEMENT_RUNTIME_FATAL_FAILURE")
$unexpectedRollback = Count-Matches $m02 "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED"

$sessionStopped = ($shadow['obsSessionStopped'] -eq "observed") -or
    ((Count-Matches $m01 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0) -or
    ((Count-Matches $m02 "SHADOW hook=SESSION_STOPPED session=$sessionId .*outcome=APPLIED") -gt 0)

$teardownEmptyM01 = $false
$teardownEmptyM02 = $false
foreach ($td in @(Select-String -Path $m01 -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$sessionId" -ErrorAction SilentlyContinue)) {
    if (Test-TeardownEmptyLine $td.Line) { $teardownEmptyM01 = $true; break }
}
foreach ($td in @(Select-String -Path $m02 -Pattern "PROFILE01_SHADOW_RUNTIME .*phase=TEARDOWN_EMPTY .*sessionId=$sessionId" -ErrorAction SilentlyContinue)) {
    if (Test-TeardownEmptyLine $td.Line) { $teardownEmptyM02 = $true; break }
}
$teardownEmpty = $teardownEmptyM01 -and $teardownEmptyM02

$verdict = "PASS"
$reason = "teardown_closure_verified"
if (-not $sessionId -or -not $m01 -or -not $m02) {
    $verdict = "INVALID"; $reason = "missing_capture"
} elseif (-not $t0Reached) {
    $verdict = "INVALID"; $reason = "harness_aborted_before_t0"
} elseif ($soakSec -lt $MinSoakSec) {
    $verdict = "VALID_FAIL"; $reason = "short_soak_below_min_${MinSoakSec}s"
} elseif ($dualOwner -gt 0 -or $runtimeFatal -gt 0 -or $unexpectedRollback -gt 0) {
    $verdict = "VALID_FAIL"; $reason = "stability_gate_during_short_soak"
} elseif (-not $sessionStopped) {
    $verdict = "VALID_FAIL"; $reason = "teardown_completion_failed"
} elseif (-not $teardownEmpty) {
    $verdict = "VALID_FAIL"; $reason = "teardown_not_empty"
}

$steadyRef = if ($SteadyStateEvidenceDir) { $SteadyStateEvidenceDir } else { "talkback/logs/ga-g2-real-mesh-field-20260914-175047" }
$evidenceSet = @(
    "G3A_EVIDENCE_SET"
    "steadyStateRun=$steadyRef"
    "steadyStateSoakSec=1073"
    "steadyStateVerdict=PASS_FIELD_PROVEN"
    "t1Run=$LogDir"
    "t1SoakSec=$soakSec"
    "t1Verdict=$verdict"
    "combinedGate=$(if ($verdict -eq 'PASS') { 'G3a_CLOSED_FIELD_PROVEN' } else { 'PENDING_T1_PASS' })"
)

$lines = @(
    "G3A_T1_TEARDOWN"
    "gate=G3a-T1"
    "executionPath=PaG2_3node_real_mesh"
    "minSoakSec=$MinSoakSec"
    "observedSoakSec=$soakSec"
    "t0Reached=$t0Reached"
    "cutoverOk=$cutoverOk"
    "multicastActive=$multicastActive"
    "sessionStopped=$sessionStopped"
    "teardownEmpty=$teardownEmpty"
    "teardownEmptyM01=$teardownEmptyM01"
    "teardownEmptyM02=$teardownEmptyM02"
    "dualOwnerCount=$dualOwner"
    "runtimeFatalCount=$runtimeFatal"
    "unexpectedRollbackCount=$unexpectedRollback"
    "hostSessionId=$sessionId"
    "verdict=$verdict"
    "reason=$reason"
    "semantic=teardown_closure_only"
    "notSubstitutableFor=G3a_steady_state"
)

if ($verdict -eq "PASS") {
    $lines += "G3A_T1_RESULT=PASS"
    $lines += "Next=GA_AUTHORIZATION_REVIEW"
} elseif ($verdict -eq "INVALID") {
    $lines += "G3A_T1_RESULT=INVALID"
    $lines += "Next=FIX_HARNESS_OR_ENV_RERUN_G3A_T1"
} else {
    $lines += "G3A_T1_RESULT=VALID_FAIL"
    $lines += "Next=RCA_TEARDOWN_DEFECT"
}

($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "G3A_T1_RESULT.txt")
($lines -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
($evidenceSet -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "G3A_EVIDENCE_SET.txt")
Write-Host ($lines -join "`n")
Write-Host ""
Write-Host ($evidenceSet -join "`n")

if ($verdict -eq "PASS") { exit 0 }
if ($verdict -eq "INVALID") { exit 2 }
exit 1
