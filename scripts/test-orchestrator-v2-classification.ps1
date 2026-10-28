# Orchestrator v2 + Joint Evidence Classifier review gate
# Validates O0 dry-run aborts AND offline ownership/lifecycle classification contract.
#
# Usage:
#   .\scripts\test-orchestrator-v2-classification.ps1
#   .\scripts\test-orchestrator-v2-classification.ps1 -LogRoot logs\orch-v2-review

param(
    [string]$LogRoot = "",
    [string]$OrchScript = "",
    [string]$ClassifierScript = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $OrchScript) { $OrchScript = Join-Path $PSScriptRoot "run-joint-d1-c-orchestrator.ps1" }
if (-not $ClassifierScript) { $ClassifierScript = Join-Path $PSScriptRoot "classify-joint-d1-c.ps1" }
if (-not $LogRoot) {
    $LogRoot = Join-Path $repoRoot "logs\orch-v2-review-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding $false
$fail = 0

function Read-KV([string]$path, [string]$key) {
    if (-not (Test-Path $path)) { return $null }
    $line = Get-Content $path -Encoding UTF8 | Where-Object { $_ -match "^$key=" } | Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line -replace "^$key=", "")
}

function Read-Class([string]$dir) {
    $path = Join-Path $dir "ORCHESTRATOR_V2_CLASSIFICATION.txt"
    if (-not (Test-Path $path)) { return $null }
    return @{
        result = (Read-KV $path "result")
        reason = (Read-KV $path "reason")
        path = $path
    }
}

function Read-Final([string]$dir) {
    $path = Join-Path $dir "JOINT_EVIDENCE_CLASSIFICATION.txt"
    if (-not (Test-Path $path)) { return $null }
    return @{
        finalOutcome = (Read-KV $path "finalOutcome")
        reason = (Read-KV $path "reason")
        subtype = (Read-KV $path "subtype")
        ownershipCompliance = (Read-KV $path "ownershipCompliance")
        jb = (Read-KV $path "jb")
        cleanAttemptEstablished = (Read-KV $path "cleanAttemptEstablished")
        path = $path
    }
}

function Assert-Eq([string]$name, [string]$field, $actual, [string]$expected) {
    if ("$actual" -ne $expected) {
        throw "$name FAIL: $field=$actual expected=$expected"
    }
}

function Write-AuthLog([string]$dir, [string[]]$bodyLines) {
    $path = Join-Path $dir "M02-talkback.log"
    $header = @("08-04 15:00:00.000 1 1 I Talkback: SYNTH_FIXTURE begin")
    [System.IO.File]::WriteAllLines($path, ($header + $bodyLines), $utf8)
    $peer = Join-Path $dir "M03-talkback.log"
    [System.IO.File]::WriteAllLines($peer, @("08-04 15:00:00.000 1 1 I Talkback: SYNTH_PEER"), $utf8)
}

function Run-ClassifierCase([string]$name, [string[]]$lines, [hashtable]$expect, [string]$intentId = "R3") {
    $dir = Join-Path $LogRoot $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Write-AuthLog $dir $lines
    Write-Host ""
    Write-Host "=== $name ==="
    try {
        & $ClassifierScript -LogDir $dir -IntentId $intentId -SkipAnalyzer | Out-Null
        $fin = Read-Final $dir
        if (-not $fin) { throw "$name FAIL: missing JOINT_EVIDENCE_CLASSIFICATION.txt" }
        Assert-Eq $name "finalOutcome" $fin.finalOutcome $expect.finalOutcome
        Assert-Eq $name "reason" $fin.reason $expect.reason
        if ($expect.ContainsKey("subtype")) { Assert-Eq $name "subtype" $fin.subtype $expect.subtype }
        if ($expect.ContainsKey("ownershipCompliance")) {
            Assert-Eq $name "ownershipCompliance" $fin.ownershipCompliance $expect.ownershipCompliance
        }
        if ($expect.ContainsKey("jb")) { Assert-Eq $name "jb" $fin.jb $expect.jb }
        Write-Host "PASS $name :: $($fin.finalOutcome) / $($fin.reason) / $($fin.subtype)"
    } catch {
        Write-Host "FAIL $name : $_"
        $script:fail++
    }
}

# ---- Dry-run orchestration aborts (provisional + final) ----
$scenarios = @(
    @{ name = "WAITING_FOR_D1_ARMED"; scenario = "WAITING_FOR_D1_ARMED"; result = "ABORT_ORCHESTRATION"; reason = "WAITING_FOR_D1_ARMED" },
    @{ name = "MISSING_OBLIGATION"; scenario = "MISSING_OBLIGATION"; result = "ABORT_ORCHESTRATION"; reason = "TIMEOUT_WAITING_PREREQUISITE" },
    @{ name = "SHAPE_PRECONDITION_NOT_MET"; scenario = "SHAPE_PRECONDITION_NOT_MET"; result = "ABORT_INVALID_EXERCISE"; reason = "SHAPE_PRECONDITION_NOT_MET"; subtype = "EXECUTE_BEFORE_EDGE_REQUIRED" }
)

foreach ($s in $scenarios) {
    $dir = Join-Path $LogRoot $s.name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Write-Host ""
    Write-Host "=== $($s.name) ==="
    try {
        & $OrchScript -LogDir $dir -DryRun -DryRunScenario $s.scenario -SkipAnalyzer
        $class = Read-Class $dir
        if (-not $class) { throw "$($s.name) FAIL: no ORCHESTRATOR_V2_CLASSIFICATION.txt" }
        Assert-Eq $s.name "provisional.result" $class.result $s.result
        Assert-Eq $s.name "provisional.reason" $class.reason $s.reason
        if ($class.result -match "FAIL_CLEAN_JOINT") {
            throw "$($s.name) FAIL: must not emit FAIL_CLEAN_JOINT on dry-run"
        }
        $fin = Read-Final $dir
        if (-not $fin) { throw "$($s.name) FAIL: classifier final missing" }
        Assert-Eq $s.name "finalOutcome" $fin.finalOutcome $s.result
        Assert-Eq $s.name "final.reason" $fin.reason $s.reason
        if ($s.ContainsKey("subtype") -and $s.subtype) {
            Assert-Eq $s.name "final.subtype" $fin.subtype $s.subtype
        }
        Write-Host "PASS $($s.name) :: provisional+final $($fin.finalOutcome) / $($fin.reason)"
    } catch {
        Write-Host "FAIL $($s.name): $_"
        $fail++
    }
}

$session = "8462be85-d6da-41b0-a835-877e64d6e393"
$remote = "M03"

# Compliant supersede AFTER HELD
Run-ClassifierCase "SYNTH_SUPERSEDE_AFTER_HELD" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_VALIDATION_FENCE_ARMED session=$session remote=$remote intentId=R3"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:42.871 1 1 I Talkback: RECOVERY_EDGE_STARTED session=$session remote=$remote attempt=2 recoveryReason=ICE_DISCONNECTED"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE oldState=HELD_DISPATCH newState=SUPERSEDED authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.873 1 1 I Talkback: FENCE_RELEASED session=$session remote=$remote intentId=R3 reason=SUPERSEDE"
    "08-04 15:08:42.881 1 1 I Talkback: DEFERRED_INTENT_RELEASED session=$session remote=$remote intentId=R3 terminal=SUPERSEDED reason=EDGE_STARTED:ICE_DISCONNECTED authority=DeferredIntentAuthority"
) @{
    finalOutcome = "ABORT_INVALID_EXERCISE"
    reason = "LIFECYCLE_CUT_AFTER_HELD"
    subtype = "AUTHORITY_COMPLIANT_SUPERSEDE"
    ownershipCompliance = "PASS"
    jb = "NOT_EXERCISED"
}

# Compliant supersede BEFORE HELD
Run-ClassifierCase "SYNTH_SUPERSEDE_BEFORE_HELD" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_VALIDATION_FENCE_ARMED session=$session remote=$remote intentId=R3"
    "08-04 15:08:42.871 1 1 I Talkback: RECOVERY_EDGE_STARTED session=$session remote=$remote attempt=2 recoveryReason=ICE_DISCONNECTED"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE oldState=CREATED newState=SUPERSEDED authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.873 1 1 I Talkback: FENCE_RELEASED session=$session remote=$remote intentId=R3 reason=SUPERSEDE"
    "08-04 15:08:42.881 1 1 I Talkback: DEFERRED_INTENT_RELEASED session=$session remote=$remote intentId=R3 terminal=SUPERSEDED reason=EDGE_STARTED:ICE_DISCONNECTED authority=DeferredIntentAuthority"
) @{
    finalOutcome = "ABORT_INVALID_EXERCISE"
    reason = "LIFECYCLE_CUT_BEFORE_HELD"
    subtype = "AUTHORITY_COMPLIANT_SUPERSEDE"
    ownershipCompliance = "PASS"
    jb = "NOT_EXERCISED"
}

# Supersede AFTER EXECUTED — jb remains PASS (monotonic)
Run-ClassifierCase "SYNTH_SUPERSEDE_AFTER_EXECUTED" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_VALIDATION_FENCE_ARMED session=$session remote=$remote intentId=R3"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:20.000 1 1 I Talkback: DEFERRED_INTENT_EXECUTED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:42.871 1 1 I Talkback: RECOVERY_EDGE_STARTED session=$session remote=$remote attempt=2 recoveryReason=ICE_DISCONNECTED"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE oldState=EXECUTED newState=SUPERSEDED authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.873 1 1 I Talkback: FENCE_RELEASED session=$session remote=$remote intentId=R3 reason=SUPERSEDE"
    "08-04 15:08:42.881 1 1 I Talkback: DEFERRED_INTENT_RELEASED session=$session remote=$remote intentId=R3 terminal=SUPERSEDED reason=EDGE_STARTED:ICE_DISCONNECTED authority=DeferredIntentAuthority"
) @{
    finalOutcome = "PASS_JOINT_D1_C"
    reason = "EXECUTED_MONOTONIC_AFTER_SUPERSEDE"
    subtype = "AUTHORITY_COMPLIANT_SUPERSEDE"
    ownershipCompliance = "PASS"
    jb = "PASS"
}

# Silent slot loss
Run-ClassifierCase "SYNTH_SILENT_SLOT_LOSS" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_VALIDATION_FENCE_ARMED session=$session remote=$remote intentId=R3"
    "08-04 15:08:42.871 1 1 I Talkback: RECOVERY_EDGE_STARTED session=$session remote=$remote attempt=2 recoveryReason=ICE_DISCONNECTED"
    "08-04 15:08:42.872 1 1 I Talkback: RECOVERY_MEDIA_ACTION_DEFERRED session=$session remote=$remote attempt=2 deferredReason=MEDIA_NOT_READY trigger=ICE_DISCONNECTED"
    "08-04 15:08:43.000 1 1 I Talkback: NEGOTIATION_CAN_EXECUTE session=$session remote=$remote intentId=NONE"
) @{
    finalOutcome = "FAIL_OWNERSHIP_WIPE"
    reason = "SILENT_SLOT_LOSS"
    subtype = "SILENT_SLOT_LOSS"
    ownershipCompliance = "FAIL"
}

# Missing fence release
Run-ClassifierCase "SYNTH_MISSING_FENCE_RELEASE" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.881 1 1 I Talkback: DEFERRED_INTENT_RELEASED session=$session remote=$remote intentId=R3 terminal=SUPERSEDED authority=DeferredIntentAuthority"
) @{
    finalOutcome = "FAIL_OWNERSHIP_WIPE"
    reason = "INCOMPLETE_SUPERSEDE_TRANSACTION"
    subtype = "INCOMPLETE_SUPERSEDE_TRANSACTION"
    ownershipCompliance = "FAIL"
}

# Missing terminal release
Run-ClassifierCase "SYNTH_MISSING_TERMINAL_RELEASE" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.873 1 1 I Talkback: FENCE_RELEASED session=$session remote=$remote intentId=R3 reason=SUPERSEDE"
) @{
    finalOutcome = "FAIL_OWNERSHIP_WIPE"
    reason = "INCOMPLETE_SUPERSEDE_TRANSACTION"
    subtype = "INCOMPLETE_SUPERSEDE_TRANSACTION"
    ownershipCompliance = "FAIL"
}

# Identity / attempt conflict
Run-ClassifierCase "SYNTH_IDENTITY_ATTEMPT_CONFLICT" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.873 1 1 I Talkback: FENCE_RELEASED session=$session remote=$remote intentId=R3 reason=SUPERSEDE"
    "08-04 15:08:42.881 1 1 I Talkback: DEFERRED_INTENT_RELEASED session=$session remote=$remote intentId=R3 attemptId=99 obligationGen=1 terminal=SUPERSEDED authority=DeferredIntentAuthority"
) @{
    finalOutcome = "FAIL_OWNERSHIP_WIPE"
    reason = "INCOMPLETE_SUPERSEDE_TRANSACTION"
    subtype = "INCOMPLETE_SUPERSEDE_TRANSACTION"
    ownershipCompliance = "FAIL"
}

# Authority-slot divergence: Authority still HELD while CAN_EXECUTE NONE and no supersede
Run-ClassifierCase "SYNTH_AUTHORITY_SLOT_DIVERGENCE" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_AUTHORITY_HELD session=$session remote=$remote intentId=R3 state=HELD_DISPATCH"
    "08-04 15:08:42.900 1 1 I Talkback: DEFERRED_INTENT_AUTHORITY_HELD session=$session remote=$remote intentId=R3 state=HELD_DISPATCH"
    "08-04 15:08:43.000 1 1 I Talkback: NEGOTIATION_CAN_EXECUTE session=$session remote=$remote intentId=NONE"
) @{
    finalOutcome = "FAIL_OWNERSHIP_WIPE"
    reason = "AUTHORITY_SLOT_DIVERGENCE"
    subtype = "AUTHORITY_SLOT_DIVERGENCE"
    ownershipCompliance = "FAIL"
}

# Live missed EDGE_STARTED: archive still has EDGE + full supersede => compliant abort
Run-ClassifierCase "SYNTH_LIVE_MISSED_EDGE_ARCHIVE_OK" @(
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1"
    "08-04 15:08:09.156 1 1 I Talkback: DEFERRED_INTENT_VALIDATION_FENCE_ARMED session=$session remote=$remote intentId=R3"
    "08-04 15:08:12.338 1 1 I Talkback: DEFERRED_INTENT_HELD session=$session remote=$remote intentId=R3 attemptId=2 obligationGen=1 hold=DISPATCH reason=dispatch_not_ready"
    "08-04 15:08:42.871 1 1 I Talkback: RECOVERY_EDGE_STARTED session=$session remote=$remote attempt=2 recoveryReason=ICE_DISCONNECTED"
    "08-04 15:08:42.872 1 1 I Talkback: DEFERRED_INTENT_SUPERSEDED session=$session remote=$remote oldIntent=R3 newIntent=NONE oldState=HELD_DISPATCH newState=SUPERSEDED authority=DeferredIntentAuthority requestingDomain=MEDIA reason=EDGE_STARTED:ICE_DISCONNECTED"
    "08-04 15:08:42.873 1 1 I Talkback: FENCE_RELEASED session=$session remote=$remote intentId=R3 reason=SUPERSEDE"
    "08-04 15:08:42.881 1 1 I Talkback: DEFERRED_INTENT_RELEASED session=$session remote=$remote intentId=R3 terminal=SUPERSEDED reason=EDGE_STARTED:ICE_DISCONNECTED authority=DeferredIntentAuthority"
) @{
    finalOutcome = "ABORT_INVALID_EXERCISE"
    reason = "LIFECYCLE_CUT_AFTER_HELD"
    subtype = "AUTHORITY_COMPLIANT_SUPERSEDE"
    ownershipCompliance = "PASS"
    jb = "NOT_EXERCISED"
}

# Field regression anchors (immutable archives)
Write-Host ""
Write-Host "=== FIELD_E16_J1_R3 ==="
try {
    $e16 = Join-Path $repoRoot "logs\joint-d1-c-e16-j1-20260804-150753"
    & $ClassifierScript -LogDir $e16 -IntentId R3 -SkipAnalyzer | Out-Null
    $fin = Read-Final $e16
    Assert-Eq "FIELD_E16_J1_R3" "finalOutcome" $fin.finalOutcome "ABORT_INVALID_EXERCISE"
    Assert-Eq "FIELD_E16_J1_R3" "reason" $fin.reason "LIFECYCLE_CUT_AFTER_HELD"
    Assert-Eq "FIELD_E16_J1_R3" "subtype" $fin.subtype "AUTHORITY_COMPLIANT_SUPERSEDE"
    Assert-Eq "FIELD_E16_J1_R3" "ownershipCompliance" $fin.ownershipCompliance "PASS"
    Assert-Eq "FIELD_E16_J1_R3" "jb" $fin.jb "NOT_EXERCISED"
    Write-Host "PASS FIELD_E16_J1_R3 :: $($fin.finalOutcome) / $($fin.reason)"
} catch {
    Write-Host "FAIL FIELD_E16_J1_R3: $_"
    $fail++
}

Write-Host ""
Write-Host "=== FIELD_201352_R16 ==="
try {
    $r16 = Join-Path $repoRoot "logs\joint-d1-c-20260801-201352"
    & $ClassifierScript -LogDir $r16 -IntentId R16 -SkipAnalyzer | Out-Null
    $fin = Read-Final $r16
    Assert-Eq "FIELD_201352_R16" "finalOutcome" $fin.finalOutcome "FAIL_OWNERSHIP_WIPE"
    Assert-Eq "FIELD_201352_R16" "reason" $fin.reason "SILENT_SLOT_LOSS"
    Assert-Eq "FIELD_201352_R16" "ownershipCompliance" $fin.ownershipCompliance "FAIL"
    Write-Host "PASS FIELD_201352_R16 :: $($fin.finalOutcome) / $($fin.reason)"
} catch {
    Write-Host "FAIL FIELD_201352_R16: $_"
    $fail++
}

Write-Host ""
Write-Host "=== FIELD_E16_CLEAN_R4 ==="
try {
    $clean = Join-Path $repoRoot "logs\joint-d1-c-e16-clean-20260804-161614"
    & $ClassifierScript -LogDir $clean -IntentId R4 -ExerciseShape EXECUTE_BEFORE_EDGE -SkipAnalyzer | Out-Null
    $fin = Read-Final $clean
    Assert-Eq "FIELD_E16_CLEAN_R4" "finalOutcome" $fin.finalOutcome "FAIL_CLEAN_JOINT"
    Assert-Eq "FIELD_E16_CLEAN_R4" "reason" $fin.reason "J_E_D1_NOT_ESTABLISHED"
    Assert-Eq "FIELD_E16_CLEAN_R4" "ownershipCompliance" $fin.ownershipCompliance "PASS"
    Assert-Eq "FIELD_E16_CLEAN_R4" "jb" $fin.jb "PASS"
    Assert-Eq "FIELD_E16_CLEAN_R4" "cleanAttemptEstablished" $fin.cleanAttemptEstablished "True"
    Write-Host "PASS FIELD_E16_CLEAN_R4 :: $($fin.finalOutcome) / $($fin.reason) / CLEAN=$($fin.cleanAttemptEstablished)"
} catch {
    Write-Host "FAIL FIELD_E16_CLEAN_R4: $_"
    $fail++
}

Write-Host ""

Write-Host ""
Write-Host "=== T2_ACTIVATION_SUCCESSOR_STARTED ==="
try {
    $dir = Join-Path $LogRoot "T2_ACTIVATION_SUCCESSOR_STARTED"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $session = "sess-act-1"
    $remote = "M03"
    Write-AuthLog $dir @(
        "08-04 16:00:00.000 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R9 attemptId=1 obligationGen=1",
        "08-04 16:00:01.000 1 1 I Talkback: ADMIT_SUCCESSOR_OBLIGATION_EPISODE session=$session remote=$remote obligationGen=2 attempt=2",
        "08-04 16:00:01.001 1 1 I Talkback: RECOVERY_SUCCESSOR_STARTED session=$session remote=$remote local=M02 obligationGen=2 attempt=2 pathway=NEW_OBLIGATION_EPISODE trigger=ADMIT_SUCCESSOR_OBLIGATION_EPISODE activationKind=SUCCESSOR_START",
        "08-04 16:00:01.002 1 1 I Talkback: RECOVERY_MEDIA_OWNER_ASSIGNED session=$session remote=$remote attempt=2 owner=HOST_RESTART"
    )
    # Minimal report so clean path can combine gates if needed
    $report = @"
JOINT_D1_C_REPORT
result=NOT_EXERCISED
reason=synth
J-A PASS
J-B PASS
J-C PASS
J-D PASS
J-E FAIL
J-F PASS
J-G PASS
"@
    [IO.File]::WriteAllText((Join-Path $dir "JOINT_D1_C_REPORT.txt"), $report, $utf8)
    & $ClassifierScript -LogDir $dir -IntentId R9 -SkipAnalyzer | Out-Null
    $act = Read-KV (Join-Path $dir "JOINT_EVIDENCE_CLASSIFICATION.txt") "ACTIVATION_EVIDENCE"
    $kind = Read-KV (Join-Path $dir "JOINT_EVIDENCE_CLASSIFICATION.txt") "ACTIVATION_KIND"
    Assert-Eq "T2_ACTIVATION_SUCCESSOR_STARTED" "ACTIVATION_EVIDENCE" $act "YES"
    Assert-Eq "T2_ACTIVATION_SUCCESSOR_STARTED" "ACTIVATION_KIND" $kind "SUCCESSOR_START"
    Write-Host "PASS T2_ACTIVATION_SUCCESSOR_STARTED :: ACTIVATION_EVIDENCE=$act kind=$kind"
} catch {
    Write-Host "FAIL T2_ACTIVATION_SUCCESSOR_STARTED: $_"
    $fail++
}

Write-Host ""
Write-Host "=== T2_ADMIT_ONLY_NO_ACTIVATION ==="
try {
    $dir = Join-Path $LogRoot "T2_ADMIT_ONLY_NO_ACTIVATION"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $session = "sess-act-2"
    $remote = "M03"
    Write-AuthLog $dir @(
        "08-04 16:00:00.000 1 1 I Talkback: DEFERRED_INTENT_CREATED session=$session remote=$remote intentId=R9 attemptId=1 obligationGen=1",
        "08-04 16:00:01.000 1 1 I Talkback: ADMIT_SUCCESSOR_OBLIGATION_EPISODE session=$session remote=$remote obligationGen=2 attempt=2",
        "08-04 16:00:01.002 1 1 I Talkback: RECOVERY_MEDIA_OWNER_ASSIGNED session=$session remote=$remote attempt=2 owner=HOST_RESTART"
    )
    $report = @"
JOINT_D1_C_REPORT
result=NOT_EXERCISED
reason=synth
J-A PASS
J-B PASS
J-C PASS
J-D PASS
J-E FAIL
J-F PASS
J-G PASS
"@
    [IO.File]::WriteAllText((Join-Path $dir "JOINT_D1_C_REPORT.txt"), $report, $utf8)
    & $ClassifierScript -LogDir $dir -IntentId R9 -SkipAnalyzer | Out-Null
    $act = Read-KV (Join-Path $dir "JOINT_EVIDENCE_CLASSIFICATION.txt") "ACTIVATION_EVIDENCE"
    Assert-Eq "T2_ADMIT_ONLY_NO_ACTIVATION" "ACTIVATION_EVIDENCE" $act "NO"
    $fin = Read-Final $dir
    # T0: delivery/admit without activation => J-E reason stays D1_NOT_ESTABLISHED when J-E FAIL
    if ($fin.finalOutcome -eq "FAIL_CLEAN_JOINT") {
        Assert-Eq "T2_ADMIT_ONLY_NO_ACTIVATION" "reason" $fin.reason "J_E_D1_NOT_ESTABLISHED"
    }
    Write-Host "PASS T2_ADMIT_ONLY_NO_ACTIVATION :: ACTIVATION_EVIDENCE=$act reason=$($fin.reason)"
} catch {
    Write-Host "FAIL T2_ADMIT_ONLY_NO_ACTIVATION: $_"
    $fail++
}

Write-Host ""
Write-Host "=== T2_FIELD_CLEAN_NO_ACTIVATION_HISTORICAL ==="
try {
    $clean = Join-Path $repoRoot "logs\joint-d1-c-e16-clean-20260804-161614"
    $act = Read-KV (Join-Path $clean "JOINT_EVIDENCE_CLASSIFICATION.txt") "ACTIVATION_EVIDENCE"
    # Reclassify to refresh tokens
    & $ClassifierScript -LogDir $clean -IntentId R4 -ExerciseShape EXECUTE_BEFORE_EDGE -SkipAnalyzer | Out-Null
    $act = Read-KV (Join-Path $clean "JOINT_EVIDENCE_CLASSIFICATION.txt") "ACTIVATION_EVIDENCE"
    $kind = Read-KV (Join-Path $clean "JOINT_EVIDENCE_CLASSIFICATION.txt") "ACTIVATION_KIND"
    Assert-Eq "T2_FIELD_CLEAN_HIST" "ACTIVATION_EVIDENCE" $act "NO"
    Assert-Eq "T2_FIELD_CLEAN_HIST" "ACTIVATION_KIND" $kind "NONE"
    Write-Host "PASS T2_FIELD_CLEAN_HIST :: ACTIVATION_EVIDENCE=$act"
} catch {
    Write-Host "FAIL T2_FIELD_CLEAN_HIST: $_"
    $fail++
}

if ($fail -gt 0) {
    Write-Host "REVIEW_GATE=FAIL ($fail scenario(s))"
    exit 1
}
Write-Host "REVIEW_GATE=PASS (O0 dry-run + Joint Evidence Classifier contract verified)"
Write-Host "LogRoot=$LogRoot"
exit 0