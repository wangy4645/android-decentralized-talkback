# RC1 field adjudication — audible ownership transition (M02 peer adjudication plane).
#
# Three-state: PASS_RC1 (0) · FAIL_RC1 (1) · INVALID_* (2)
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
        if ($line -match "$Key=([0-9]+)") {
            $v = [long]$Matches[1]
            if ($v -gt $max) { $max = $v }
        }
    }
    return $max
}

function Get-FirstLineIndex {
    param([string]$Path, [string]$Pattern)
    if (-not $Path -or -not (Test-Path $Path)) { return -1 }
    $i = 0
    foreach ($line in Get-Content $Path) {
        if ($line -match $Pattern) { return $i }
        $i++
    }
    return -1
}

$linesOut = New-Object System.Collections.Generic.List[string]
function Add-Out([string]$s) { [void]$linesOut.Add($s) }

function Write-VerdictAndExit {
    param([string]$Verdict, [int]$Code, [string]$Next, [hashtable]$Extra = @{})
    Add-Out ""
    Add-Out "VERDICT=$Verdict"
    Add-Out "RC1=$($Extra['rc1'])"
    if ($Extra['reason']) { Add-Out "reason=$($Extra['reason'])" }
    Add-Out "CUTOVER_SUCCEEDED=$($Extra['cutoverSucceeded'])"
    Add-Out "ROLLBACK_SUCCEEDED=$($Extra['rollbackSucceeded'])"
    Add-Out "AUTO_ROLLBACK_ONLY=$($Extra['autoRollbackOnly'])"
    Add-Out "ADR-0056=KEEP"
    Add-Out "Replacement_readiness=$($Extra['readiness'])"
    Add-Out "Next=$Next"
    ($linesOut -join "`n") | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
    Write-Host ($linesOut -join "`n")
    exit $Code
}

$m01Log = Get-ModuleLog "M01"
$m02Log = Get-ModuleLog "M02"
$rc1Gate = Read-KvFile (Join-Path $LogDir "RC1_GATE.txt")
$shadowGate = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_GATE.txt")
$manifest = Read-KvFile (Join-Path $LogDir "SHADOW_RUNTIME_MANIFEST.txt")
$sessionId = if ($manifest['hostSessionId']) { $manifest['hostSessionId'] } elseif ($rc1Gate['hostSessionId']) { $rc1Gate['hostSessionId'] } else { "" }

Add-Out "REPLACEMENT CUTOVER RC1 FIELD ADJUDICATION"
Add-Out "log_dir=$LogDir"
Add-Out "run_card=replacement-cutover-rc1-field-run-card.md"
Add-Out "adr=0062-replacement-cutover-rc1-audible-ownership-transition.md"
Add-Out "adjudication_plane=M02"
Add-Out "hostSessionId=$sessionId"

if (-not $m01Log -or -not $m02Log) {
    Write-VerdictAndExit -Verdict "INVALID_CAPTURE" -Code 2 -Next "RERUN_RC1_CAPTURE" -Extra @{
        rc1 = "NOT_TESTED"; reason = "missing_device_logs"; cutoverSucceeded = "false"
        rollbackSucceeded = "false"; autoRollbackOnly = "unknown"; readiness = "NOT_READY"
    }
}

if (-not $sessionId) {
    Write-VerdictAndExit -Verdict "INVALID_BASELINE" -Code 2 -Next "RERUN_RC1_BASELINE" -Extra @{
        rc1 = "NOT_TESTED"; reason = "missing_hostSessionId"; cutoverSucceeded = "false"
        rollbackSucceeded = "false"; autoRollbackOnly = "unknown"; readiness = "NOT_READY"
    }
}

# --- Hard fail: dual-owner at any time ---
$dualOwner = @(Get-Lines $m02Log "OWNERSHIP_INVARIANT_VIOLATION")
if ($dualOwner.Count -gt 0) {
    Write-VerdictAndExit -Verdict "FAIL_RC1" -Code 1 -Next "FIX_RC1_DUAL_OWNER_RERUN" -Extra @{
        rc1 = "FAIL"; reason = "ownership_invariant_violation_dual_owner"; cutoverSucceeded = "false"
        rollbackSucceeded = "false"; autoRollbackOnly = "false"; readiness = "NOT_READY"
    }
}

# --- Distinguish cutover success vs auto-rollback-only ---
$cutoverSucceeded = (Get-Lines $m02Log "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId").Count -gt 0
$cutoverFailed = (Get-Lines $m02Log "RC1_FIELD outcome=CUTOVER_FAILED session=$sessionId").Count -gt 0
$autoRollback = (Get-Lines $m02Log "RC1_FIELD outcome=AUTO_ROLLBACK_SUCCEEDED session=$sessionId").Count -gt 0
$rollbackSucceeded = (Get-Lines $m02Log "RC1_FIELD outcome=ROLLBACK_SUCCEEDED session=$sessionId").Count -gt 0
$autoRollbackOnly = (-not $cutoverSucceeded) -and ($cutoverFailed -or $autoRollback)

Add-Out "cutoverSucceededLog=$cutoverSucceeded cutoverFailedLog=$cutoverFailed autoRollbackLog=$autoRollback rollbackSucceededLog=$rollbackSucceeded"

if ($autoRollbackOnly) {
    Write-VerdictAndExit -Verdict "FAIL_RC1" -Code 1 -Next "FIX_RC1_CUTOVER_HANDOFF_RERUN" -Extra @{
        rc1 = "FAIL"; reason = "auto_rollback_without_cutover_success"; cutoverSucceeded = "false"
        rollbackSucceeded = $(if ($rollbackSucceeded) { "true" } else { "false" })
        autoRollbackOnly = "true"; readiness = "NOT_READY"
    }
}

# --- Window A: anchor owner baseline ---
$pilotEnabled = (Get-Lines $m02Log "RC1_FIELD outcome=PILOT_ENABLED").Count -gt 0
$windowAOps = $shadowGate['obsShadowTxActivity'] -eq "observed" -and
    $shadowGate['obsPeerIngressActivity'] -eq "observed" -and
    $shadowGate['obsPlayoutActivity'] -eq "observed"
$preCutoverShadowOnly = (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*audioTrackOwner=SHADOW_METRICS_ONLY .*sessionId=$sessionId").Count -gt 0
if (-not $pilotEnabled -or -not $windowAOps) {
    Write-VerdictAndExit -Verdict "INVALID_BASELINE" -Code 2 -Next "RERUN_RC1_WINDOW_A" -Extra @{
        rc1 = "NOT_TESTED"; reason = "window_A_not_operational"; cutoverSucceeded = "false"
        rollbackSucceeded = "false"; autoRollbackOnly = "false"; readiness = "NOT_READY"
    }
}
Add-Out "windowA=pilotEnabled=$pilotEnabled operationalMedia=$windowAOps preCutoverShadowOnly=$preCutoverShadowOnly"

# --- Window B: cutover with production continuity ---
$multicastActive = (Get-Lines $m02Log "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=MULTICAST_ACTIVE").Count -gt 0
$anchorReleased = (Get-Lines $m02Log "ANCHOR_AUDIBLE_PROBE session=$sessionId active=false reason=replacement_cutover_anchor_release").Count -gt 0
$productionAcquired = (Get-Lines $m02Log "MULTICAST_AUDIBLE_PROBE .*active=true").Count -gt 0 -or
    (Get-Lines $m02Log "phase=AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE").Count -gt 0
$ownerVerifyPassB = (Get-Lines $m02Log "OWNER_VERIFY session=$sessionId pass=true .*multicastProduction=true").Count -gt 0

$cutoverLineIdx = Get-FirstLineIndex $m02Log "RC1_FIELD outcome=CUTOVER_SUCCEEDED session=$sessionId"
$postCutoverPlayoutLines = if ($cutoverLineIdx -ge 0 -and (Test-Path $m02Log)) {
    $all = Get-Content $m02Log
    if ($cutoverLineIdx + 1 -lt $all.Count) { @($all[($cutoverLineIdx + 1)..($all.Count - 1)]) } else { @() }
} else { @() }
$productionWritesPostCutover = Get-MaxNumericField $postCutoverPlayoutLines "successfulPlayoutWrites"
$productionOwnerPostCutover = (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*audioTrackOwner=MULTICAST_PRODUCTION .*sessionId=$sessionId").Count -gt 0
$productionWritesOk = ($productionWritesPostCutover -gt 0) -or
    (Get-MaxNumericField (Get-Lines $m02Log "PROFILE01_SHADOW_RUNTIME .*audioTrackOwner=MULTICAST_PRODUCTION .*sessionId=$sessionId") "successfulPlayoutWrites") -gt 0

Add-Out "windowB=multicastActive=$multicastActive anchorReleased=$anchorReleased productionAcquired=$productionAcquired ownerVerifyPassB=$ownerVerifyPassB productionWritesOk=$productionWritesOk"

if (-not $cutoverSucceeded -or -not $multicastActive -or -not $anchorReleased -or -not $productionAcquired -or -not $ownerVerifyPassB -or -not $productionWritesOk) {
    Write-VerdictAndExit -Verdict "FAIL_RC1" -Code 1 -Next "FIX_RC1_WINDOW_B_RERUN" -Extra @{
        rc1 = "FAIL"; reason = "window_B_cutover_incomplete_or_no_production_media"; cutoverSucceeded = "false"
        rollbackSucceeded = $(if ($rollbackSucceeded) { "true" } else { "false" })
        autoRollbackOnly = "false"; readiness = "NOT_READY"
    }
}

# --- Window C: manual rollback + anchor restore ---
$anchorRestored = (Get-Lines $m02Log "ANCHOR_AUDIBLE_PROBE session=$sessionId active=true reason=replacement_cutover_anchor_restore").Count -gt 0
$multicastReleased = (Get-Lines $m02Log "MULTICAST_AUDIBLE_PROBE session=$sessionId active=false").Count -gt 0
$anchorActiveState = (Get-Lines $m02Log "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=ANCHOR_ACTIVE").Count -gt 0
$ownerVerifyPassC = (Get-Lines $m02Log "OWNER_VERIFY session=$sessionId pass=true .*anchorAudible=true multicastProduction=false").Count -gt 0 -or
    (Get-Lines $m02Log "OWNER_VERIFY session=$sessionId pass=true .*multicastProduction=false").Count -gt 0

$rollbackLineIdx = Get-FirstLineIndex $m02Log "RC1_FIELD outcome=ROLLBACK_SUCCEEDED session=$sessionId"
$postRollbackZombie = $false
if ($rollbackLineIdx -ge 0 -and (Test-Path $m02Log)) {
    $tail = @(Get-Content $m02Log | Select-Object -Skip ($rollbackLineIdx + 1))
    foreach ($line in $tail) {
        if ($line -match "MULTICAST_AUDIBLE_PROBE session=$sessionId active=true") {
            $postRollbackZombie = $true
            break
        }
    }
}

Add-Out "windowC=rollbackSucceeded=$rollbackSucceeded anchorRestored=$anchorRestored multicastReleased=$multicastReleased anchorActiveState=$anchorActiveState ownerVerifyPassC=$ownerVerifyPassC postRollbackZombie=$postRollbackZombie"

if (-not $rollbackSucceeded -or -not $anchorRestored -or -not $multicastReleased -or -not $anchorActiveState -or -not $ownerVerifyPassC -or $postRollbackZombie) {
    Write-VerdictAndExit -Verdict "FAIL_RC1" -Code 1 -Next "FIX_RC1_WINDOW_C_RERUN" -Extra @{
        rc1 = "FAIL"; reason = "window_C_rollback_incomplete_or_zombie_multicast"; cutoverSucceeded = "true"
        rollbackSucceeded = "false"; autoRollbackOnly = "false"; readiness = "NOT_READY"
    }
}

Write-VerdictAndExit -Verdict "PASS_RC1" -Code 0 -Next "READY_FOR_CONTROLLED_PILOT" -Extra @{
    rc1 = "PASS"; reason = "audible_ownership_flip_reversible"; cutoverSucceeded = "true"
    rollbackSucceeded = "true"; autoRollbackOnly = "false"
    readiness = "READY_FOR_CONTROLLED_PILOT"
}
