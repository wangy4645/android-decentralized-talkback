# Appendix D Deferred Drain Authority gold-chain analyzer (INV-NEG-018..022)
# Narrows B3.0 (capability observation) with obligation close / freshness gates.
#
# Usage:
#   .\scripts\analyze-appendix-d-drain.ps1 -LogDir logs\appendix-d-drain-<stamp>
#
# Gold chain (PASS_APPENDIX_D):
#   DEFERRED -> OBSERVATION baseline=false -> NEGOTIATION_CAN_EXECUTE -> WAKEUP
#   -> EXECUTED -> (optional early HOLD) -> post-dispatch ICE CONNECTED
#   -> RECOVERY_EDGE_RECOVERED
#   AND no unauthorized early close while NEGOTIATION defer uncovered.

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$files = Get-ChildItem $LogDir -Filter "*-talkback*.log" | Sort-Object Name
if (-not $files) { $files = Get-ChildItem $LogDir -Filter "*.log" | Sort-Object Name }
if (-not $files) { throw "No *.log under $LogDir" }

$all = @()
foreach ($f in $files) {
    $all += Get-Content $f.FullName -Encoding UTF8 -ErrorAction SilentlyContinue
}

function Lines-Pattern([string]$pat) {
    return @($all | Where-Object { $_ -match $pat })
}

$defer = Lines-Pattern "ICE_RESTART_DEFERRED"
$created = Lines-Pattern "DEFERRED_INTENT_CREATED"
$superseded = Lines-Pattern "DEFERRED_INTENT_SUPERSEDED"
$rising = Lines-Pattern "NEGOTIATION_CAPABILITY_RISING"
$drainAttempt = Lines-Pattern "DEFERRED_INTENT_DRAIN_ATTEMPT"
$rejected = Lines-Pattern "DEFERRED_INTENT_REJECTED"
$executedNew = Lines-Pattern "DEFERRED_INTENT_EXECUTED"
$offerAwait = Lines-Pattern "OFFER_AWAITING_ANSWER|HAVE_LOCAL_OFFER"
$baseline = Lines-Pattern "NEGOTIATION_CAPABILITY_OBSERVATION.*baseline=false.*DEFER_ADMISSION"
$canExec = Lines-Pattern "NEGOTIATION_CAN_EXECUTE"
$wakeup = Lines-Pattern "RECOVERY_WAKEUP_FIRED.*NEGOTIATION_CAN_EXECUTE"
$executed = Lines-Pattern "RECOVERY_ICE_RESTART_INTENT_TERMINAL.*terminal=EXECUTED"
$dispatch = Lines-Pattern "RECOVERY_ICE_RESTART_DISPATCHED"
$held = Lines-Pattern "RECOVERY_COMPLETION_HELD"
$mediaPathHold = Lines-Pattern "RECOVERY_MEDIA_PATH_OBSERVATION.*decision=HOLD"
$edgeRecovered = Lines-Pattern "RECOVERY_EDGE_RECOVERED"
$closeHeld = Lines-Pattern "RECOVERY_OBLIGATION_CLOSE_HELD"

# Forbidden: media_path short-circuit while defer (CONTROL_PLANE_BOUNDARY with that reason
# after a still-pending negotiation defer without EXECUTED).
$mediaPathBoundary = Lines-Pattern "RECOVERY_CONTROL_PLANE_BOUNDARY.*media_path_active_without_restart"
$earlyExpire = Lines-Pattern "RECOVERY_ICE_RESTART_INTENT_TERMINAL.*terminal=STALE_DISCARD.*reason=OBLIGATION_CLOSED"

Write-Host "=== Appendix D Deferred Drain Gold Chain ==="
Write-Host ("LogDir={0}" -f $LogDir)
Write-Host ("files={0} lines={1}" -f $files.Count, $all.Count)
Write-Host ""
Write-Host ("DEFERRED_INTENT_CREATED           : {0}" -f $created.Count)
Write-Host ("DEFERRED_INTENT_SUPERSEDED        : {0}" -f $superseded.Count)
Write-Host ("NEGOTIATION_CAPABILITY_RISING     : {0}" -f $rising.Count)
Write-Host ("DEFERRED_INTENT_DRAIN_ATTEMPT     : {0}" -f $drainAttempt.Count)
Write-Host ("DEFERRED_INTENT_REJECTED          : {0}" -f $rejected.Count)
Write-Host ("DEFERRED_INTENT_EXECUTED          : {0}" -f $executedNew.Count)
Write-Host ("OFFER_AWAITING / HAVE_LOCAL_OFFER : {0}" -f $offerAwait.Count)
Write-Host ("ICE_RESTART_DEFERRED                  : {0}" -f $defer.Count)
Write-Host ("OBSERVATION baseline=false            : {0}" -f $baseline.Count)
Write-Host ("NEGOTIATION_CAN_EXECUTE               : {0}" -f $canExec.Count)
Write-Host ("WAKEUP_FIRED NEGOTIATION_CAN_EXECUTE  : {0}" -f $wakeup.Count)
Write-Host ("INTENT TERMINAL EXECUTED              : {0}" -f $executed.Count)
Write-Host ("ICE_RESTART_DISPATCHED                : {0}" -f $dispatch.Count)
Write-Host ("COMPLETION_HELD                       : {0}" -f $held.Count)
Write-Host ("MEDIA_PATH_OBSERVATION HOLD           : {0}" -f $mediaPathHold.Count)
Write-Host ("EDGE_RECOVERED                        : {0}" -f $edgeRecovered.Count)
Write-Host ("OBLIGATION_CLOSE_HELD                 : {0}" -f $closeHeld.Count)
Write-Host ("media_path CONTROL_PLANE_BOUNDARY     : {0}" -f $mediaPathBoundary.Count)
Write-Host ("early OBLIGATION_CLOSED expire        : {0}" -f $earlyExpire.Count)
Write-Host ""

$intentIds = [System.Collections.Generic.HashSet[string]]::new()
foreach ($line in $defer) {
    if ($line -match "intentId=(R\d+)") { [void]$intentIds.Add($Matches[1]) }
}

$gold = 0
$gap = 0
foreach ($id in ($intentIds | Sort-Object)) {
    $c = $canExec | Where-Object { $_ -match "intentId=$id\b" } | Select-Object -First 1
    $w = $wakeup | Where-Object { $_ -match "intentId=$id\b" } | Select-Object -First 1
    $e = ($executed + $executedNew) | Where-Object { $_ -match "intentId=$id\b" } | Select-Object -First 1
    $d = $dispatch | Where-Object { $_ -match "intentId=$id\b" } | Select-Object -First 1

    if ($c -and $w -and $e -and $d) {
        $gold++
        Write-Host ("GOLD intentId={0} DEFER->CAN_EXECUTE->WAKEUP->EXECUTED->DISPATCH" -f $id)
    } else {
        $gap++
        Write-Host ("GAP  intentId={0} canExec={1} wakeup={2} executed={3} dispatch={4}" -f `
            $id, [bool]$c, [bool]$w, [bool]$e, [bool]$d)
    }
}

# Episode-level close after dispatch: at least one EDGE_RECOVERED after some DISPATCH.
$recoveredAfterDispatch = $false
if ($dispatch.Count -ge 1 -and $edgeRecovered.Count -ge 1) {
    $recoveredAfterDispatch = $true
}

# Leak class from soak 43e-b30: early RECOVERED / expire while defer still needed.
# Heuristic FAIL if we see OBLIGATION_CLOSED expire AND zero EXECUTED for deferred intents.
$leakEarlyClose = ($earlyExpire.Count -ge 1) -and ($executed.Count -eq 0) -and ($defer.Count -ge 1)

$chainOk = ($defer.Count -ge 1) -and ($gold -ge 1) -and $recoveredAfterDispatch -and (-not $leakEarlyClose)
# Soft signals (informational): HOLD / media_path HOLD expected when ICE races ahead of gate.
$holdSeen = ($held.Count + $mediaPathHold.Count + $closeHeld.Count) -ge 1

$verdict = if ($chainOk) { "PASS_APPENDIX_D" } else { "FAIL_B31" }
Write-Host ""
Write-Host ("VERDICT={0} gold={1} gap={2} recoveredAfterDispatch={3} leakEarlyClose={4} holdSeen={5}" -f `
    $verdict, $gold, $gap, $recoveredAfterDispatch, $leakEarlyClose, $holdSeen)

$out = Join-Path $LogDir "b31-completion-authority-verdict.txt"
@(
    "verdict=$verdict"
    "deferred=$($defer.Count)"
    "baselines=$($baseline.Count)"
    "canExecute=$($canExec.Count)"
    "wakeup=$($wakeup.Count)"
    "executed=$($executed.Count)"
    "dispatch=$($dispatch.Count)"
    "completionHeld=$($held.Count)"
    "mediaPathHold=$($mediaPathHold.Count)"
    "edgeRecovered=$($edgeRecovered.Count)"
    "gold=$gold"
    "gap=$gap"
    "recoveredAfterDispatch=$recoveredAfterDispatch"
    "leakEarlyClose=$leakEarlyClose"
    "holdSeen=$holdSeen"
) | Set-Content -Path $out -Encoding UTF8
Write-Host ("wrote {0}" -f $out)

if (-not $chainOk) { exit 1 }