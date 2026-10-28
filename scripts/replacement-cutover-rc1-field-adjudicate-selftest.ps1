# RC1 adjudicator synthetic self-test (harness-only; no devices).
$ErrorActionPreference = "Stop"
$adj = Join-Path $PSScriptRoot "replacement-cutover-rc1-field-adjudicate.ps1"
$fixRoot = Join-Path $PSScriptRoot "fixtures\replacement-cutover-rc1-field"
$sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

function Write-Fixture {
    param([string]$Name, [string[]]$M02Lines, [hashtable]$Gate = @{}, [string]$Verdict, [int]$Exit)
    $dir = Join-Path $fixRoot $Name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    @("line") | Set-Content (Join-Path $dir "M01-logcat.txt")
    ($M02Lines) | Set-Content (Join-Path $dir "M02-logcat.txt")
    @(
        "hostSessionId=$sid"
        "obsShadowTxActivity=observed"
        "obsPeerIngressActivity=observed"
        "obsPlayoutActivity=observed"
    ) + ($Gate.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) |
        Set-Content (Join-Path $dir "SHADOW_RUNTIME_GATE.txt")
    "hostSessionId=$sid" | Set-Content (Join-Path $dir "SHADOW_RUNTIME_MANIFEST.txt")
}

$passLines = @(
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=PILOT_ENABLED session=-"
    "PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY sessionId=$sid audioTrackOwner=SHADOW_METRICS_ONLY successfulPlayoutWrites=5"
    "REPLACEMENT_CUTOVER_RC1 ANCHOR_AUDIBLE_PROBE session=$sid active=false reason=replacement_cutover_anchor_release"
    "REPLACEMENT_CUTOVER_RC1 MULTICAST_AUDIBLE_PROBE session=- active=true successfulProductionWrites=0"
    "PROFILE01_SHADOW_RUNTIME phase=AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE"
    "REPLACEMENT_CUTOVER_RC1 AUDIBLE_OWNERSHIP_STATE session=$sid state=MULTICAST_ACTIVE"
    "REPLACEMENT_CUTOVER_RC1 OWNER_VERIFY session=$sid pass=true anchorAudible=false multicastProduction=true"
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sid"
    "PROFILE01_SHADOW_RUNTIME phase=SHADOW_PLAYOUT_CYCLE sessionId=$sid audioTrackOwner=MULTICAST_PRODUCTION successfulPlayoutWrites=12"
    "REPLACEMENT_CUTOVER_RC1 MULTICAST_AUDIBLE_PROBE session=- active=false successfulProductionWrites=12"
    "REPLACEMENT_CUTOVER_RC1 ANCHOR_AUDIBLE_PROBE session=$sid active=true reason=replacement_cutover_anchor_restore"
    "REPLACEMENT_CUTOVER_RC1 AUDIBLE_OWNERSHIP_STATE session=$sid state=ANCHOR_ACTIVE"
    "REPLACEMENT_CUTOVER_RC1 OWNER_VERIFY session=$sid pass=true anchorAudible=true multicastProduction=false"
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=ROLLBACK_SUCCEEDED session=$sid detail=MANUAL"
)

Write-Fixture "pass-synthetic" $passLines @{} "PASS_RC1" 0

$failDual = $passLines + @("REPLACEMENT_CUTOVER_RC1 OWNERSHIP_INVARIANT_VIOLATION session=$sid anchorAudibleActive=true multicastAudibleActive=true")
Write-Fixture "fail-dual-owner" $failDual @{} "FAIL_RC1" 1

$failNoWrites = $passLines | Where-Object { $_ -notmatch "MULTICAST_PRODUCTION|successfulPlayoutWrites=12" }
Write-Fixture "fail-no-production-writes" $failNoWrites @{} "FAIL_RC1" 1

$failAutoRb = @(
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=PILOT_ENABLED session=-"
    "PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY sessionId=$sid audioTrackOwner=SHADOW_METRICS_ONLY successfulPlayoutWrites=3"
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=CUTOVER_FAILED session=$sid detail=production_audiotrack_acquire_failure"
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED session=$sid detail=after_acquire_failure"
    "REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=ROLLBACK_SUCCEEDED session=$sid detail=MANUAL"
    "REPLACEMENT_CUTOVER_RC1 AUDIBLE_OWNERSHIP_STATE session=$sid state=ANCHOR_ACTIVE"
)
Write-Fixture "fail-auto-rollback-only" $failAutoRb @{} "FAIL_RC1" 1

Write-Fixture "invalid-baseline" @("REPLACEMENT_CUTOVER_RC1 RC1_FIELD outcome=PILOT_ENABLED session=-") @{
    obsShadowTxActivity = "missing"
    obsPeerIngressActivity = "missing"
    obsPlayoutActivity = "missing"
} "INVALID_BASELINE" 2

$cases = @(
    @{ Name = "pass-synthetic"; ExpectExit = 0; ExpectVerdict = "PASS_RC1" }
    @{ Name = "fail-dual-owner"; ExpectExit = 1; ExpectVerdict = "FAIL_RC1" }
    @{ Name = "fail-no-production-writes"; ExpectExit = 1; ExpectVerdict = "FAIL_RC1" }
    @{ Name = "fail-auto-rollback-only"; ExpectExit = 1; ExpectVerdict = "FAIL_RC1" }
    @{ Name = "invalid-baseline"; ExpectExit = 2; ExpectVerdict = "INVALID_BASELINE" }
)

$failed = 0
foreach ($c in $cases) {
    $dir = Join-Path $fixRoot $c.Name
    Write-Host "=== SELFTEST $($c.Name) expect exit=$($c.ExpectExit) verdict=$($c.ExpectVerdict) ==="
    & powershell -NoProfile -ExecutionPolicy Bypass -File $adj -LogDir $dir | Out-Null
    $code = $LASTEXITCODE
    $adjFile = Join-Path $dir "ADJUDICATION.txt"
    $verdictLine = if (Test-Path $adjFile) {
        (Select-String -Path $adjFile -Pattern "^VERDICT=" | Select-Object -First 1).Line
    } else { "VERDICT=MISSING" }
    $ok = ($code -eq $c.ExpectExit) -and ($verdictLine -eq "VERDICT=$($c.ExpectVerdict)")
    if ($ok) { Write-Host "PASS code=$code $verdictLine" } else {
        Write-Host "FAIL code=$code expected=$($c.ExpectExit) $verdictLine expectedVerdict=$($c.ExpectVerdict)"
        $failed++
    }
    Write-Host ""
}

if ($failed -gt 0) { Write-Host "SELFTEST_RESULT=FAIL failed=$failed"; exit 1 }
Write-Host "SELFTEST_RESULT=PASS cases=$($cases.Count)"
exit 0
