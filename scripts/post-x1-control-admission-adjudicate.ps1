# Post-X1 directed validation adjudicate (ADR-X1 post-fix pass)
# Usage: .\scripts\post-x1-control-admission-adjudicate.ps1 -LogDir logs\post-x1-directed-...

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir,
    [string]$PrimaryEdge = "M02",
    [string]$SessionHint = ""
)

$ErrorActionPreference = "Stop"
$pidFile = Join-Path $LogDir "COLLECTOR_PIDS.txt"
if (Test-Path $pidFile) {
    Get-Content $pidFile | ForEach-Object {
        if ($_ -match "collectorPid=(\d+)") {
            Stop-Process -Id ([int]$Matches[1]) -Force -ErrorAction SilentlyContinue
        }
    }
}
Start-Sleep 1

$m03 = Join-Path $LogDir "M03-talkback.log"
$out = Join-Path $LogDir "ADJUDICATION.txt"
if (-not (Test-Path $m03)) {
    "INVALID: missing M03-talkback.log" | Set-Content $out -Encoding UTF8
    exit 1
}

function First-MatchLine([string]$path, [string]$pattern) {
    $h = Select-String -Path $path -Pattern $pattern | Select-Object -First 1
    if ($h) { return $h.Line }
    return "(none)"
}

function Any-Match([string]$path, [string]$pattern) {
    return [bool](Select-String -Path $path -Pattern $pattern -Quiet)
}

function Section([System.Text.StringBuilder]$sb, [string]$title, [string[]]$patterns) {
    [void]$sb.AppendLine("=== $title ===")
    foreach ($p in $patterns) {
        [void]$sb.AppendLine("-- $p")
        $hits = Select-String -Path $m03 -Pattern $p
        if ($hits) {
            $hits | Select-Object -First 8 | ForEach-Object { [void]$sb.AppendLine($_.Line) }
            if ($hits.Count -gt 8) { [void]$sb.AppendLine("... ($($hits.Count) total)") }
        } else {
            [void]$sb.AppendLine("(none)")
        }
        [void]$sb.AppendLine("")
    }
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("Post-X1 Directed Validation Adjudication")
[void]$sb.AppendLine("Generated: $(Get-Date -Format o)")
[void]$sb.AppendLine("LogDir: $LogDir")
[void]$sb.AppendLine("Primary edge: M03->$PrimaryEdge")
[void]$sb.AppendLine("")

# ENV
$envInvalid = Any-Match $m03 "USER_LEAVE"
[void]$sb.AppendLine("ENV_INVALID (USER_LEAVE): $envInvalid")
[void]$sb.AppendLine("")

Section $sb "O1 Delivery -> Reevaluation" @(
    "RECOVERY_REATTACH_SENT.*remote=$PrimaryEdge",
    "RECOVERY_REATTACH_RECEIPT.*remote=$PrimaryEdge",
    "REMOTE_RECEIPT_ACKED",
    "RECOVERY_CONTROL_ADMISSION_REEVALUATE.*edge=$PrimaryEdge",
    "RECOVERY_REEVALUATE.*REMOTE_RECEIPT_ACKED"
)

$o1Receipt = Any-Match $m03 "RECOVERY_REATTACH_RECEIPT.*remote=$PrimaryEdge"
$o1Reeval = Any-Match $m03 "RECOVERY_CONTROL_ADMISSION_REEVALUATE.*edge=$PrimaryEdge"
$o1Pass = (-not $o1Receipt) -or $o1Reeval

Section $sb "O2 Glare / E2" @(
    "RECOVERY_NEGOTIATION_OWNER_CONFLICT.*edge=$PrimaryEdge",
    "DROP_OWNERSHIP_CONFLICT",
    "RECOVERY_CONTROL_ADMISSION_CONFLICT",
    "REATTACH_MEDIA_ALREADY_LIVE",
    "RECOVERY_CONTROL_PLANE_BOUNDARY.*remote=$PrimaryEdge"
)

$o2Glare = Any-Match $m03 "RECOVERY_NEGOTIATION_OWNER_CONFLICT|DROP_OWNERSHIP_CONFLICT|RECOVERY_CONTROL_ADMISSION_CONFLICT"
$o2E2Bypass = Any-Match $m03 "RECOVERY_CONTROL_PLANE_BOUNDARY.*remote=$PrimaryEdge.*REATTACH_MEDIA_ALREADY_LIVE"
$o2Pass = (-not $o2Glare) -or (-not $o2E2Bypass)

Section $sb "O3 Control boundary (initiator)" @(
    "RECOVERY_REATTACH_ACCEPTED.*remote=$PrimaryEdge",
    "RECOVERY_CONTROL_PLANE_BOUNDARY.*remote=$PrimaryEdge"
)

$o3Boundary = Any-Match $m03 "RECOVERY_REATTACH_ACCEPTED.*remote=$PrimaryEdge|RECOVERY_CONTROL_PLANE_BOUNDARY.*remote=$PrimaryEdge"

Section $sb "O4 Timeout / admission defer" @(
    "RECOVERY_WATCHDOG_DEFERRED.*ADMISSION_PENDING",
    "RECOVERY_ATTEMPT_TIMEOUT.*edge=$PrimaryEdge",
    "FAILED_MEDIA_RECOVERY.*remote=$PrimaryEdge",
    "failureClass="
)

$receiptBeforeFail = $false
$reevalBeforeFail = $false
$failLine = Select-String -Path $m03 -Pattern "FAILED_MEDIA_RECOVERY.*remote=$PrimaryEdge" | Select-Object -First 1
if ($failLine) {
    $failTs = $failLine.Line.Substring(0, 18)
    $receiptBeforeFail = [bool](Select-String -Path $m03 -Pattern "RECOVERY_REATTACH_RECEIPT.*remote=$PrimaryEdge" |
        Where-Object { $_.Line.Substring(0, 18) -lt $failTs })
    $reevalBeforeFail = [bool](Select-String -Path $m03 -Pattern "RECOVERY_CONTROL_ADMISSION_REEVALUATE.*edge=$PrimaryEdge" |
        Where-Object { $_.Line.Substring(0, 18) -lt $failTs })
}
$admissionDefer = Any-Match $m03 "RECOVERY_WATCHDOG_DEFERRED.*ADMISSION_PENDING"
$o4Premature = $failLine -and $receiptBeforeFail -and $reevalBeforeFail -and (-not $o3Boundary) -and (-not $admissionDefer)

$receiptObserved = $o1Receipt -or (Any-Match $m03 "REMOTE_RECEIPT_ACKED")
$reattachRequested = Any-Match $m03 "REATTACH_REQUESTED|RECOVERY_REATTACH_SENT.*remote=$PrimaryEdge"
$deferObserved = $admissionDefer
$failedMedia = [bool]$failLine
$e2Shortcut = $o2E2Bypass

[void]$sb.AppendLine("=== X1 Evidence Matrix (M03->$PrimaryEdge) ===")
function MatrixRow([string]$id, [string]$evidence, [string]$observed, [string]$expected) {
    return "| $id | $evidence | $observed | $expected |"
}
[void]$sb.AppendLine("| ID | Evidence | Observed | Expected |")
[void]$sb.AppendLine("|----|----------|----------|----------|")
$e1obs = if ($reattachRequested) { "YES" } else { "NO" }
$e2obs = if ($receiptObserved) { "YES" } else { "NO" }
$e3obs = if ($o1Reeval) { "YES" } else { "NO" }
$e5obs = if ($o2Glare) { "YES" } else { "NO" }
$e6obs = if ($e2Shortcut) { "YES (bad)" } else { "NO/suppressed" }
$e7obs = if ($deferObserved) { "YES" } else { "NO" }
$e8obs = if ($o3Boundary) { "YES" } else { "NO" }
$e9obs = if ($o4Premature) { "YES (bad)" } elseif ($failedMedia) { "YES (review)" } else { "NO" }
[void]$sb.AppendLine((MatrixRow "E1" "REATTACH_SENT" $e1obs "required"))
[void]$sb.AppendLine((MatrixRow "E2" "REMOTE_RECEIPT_ACKED" $e2obs "required"))
[void]$sb.AppendLine((MatrixRow "E3" "RECOVERY_CONTROL_ADMISSION_REEVALUATE" $e3obs "required after E2"))
[void]$sb.AppendLine((MatrixRow "E5" "DROP_OWNERSHIP_CONFLICT / glare" $e5obs "expected under glare"))
[void]$sb.AppendLine((MatrixRow "E6" "E2 shortcut boundary" $e6obs "suppressed if glare"))
[void]$sb.AppendLine((MatrixRow "E7" "WATCHDOG_DEFERRED ADMISSION_PENDING" $e7obs "expected if glare"))
[void]$sb.AppendLine((MatrixRow "E8" "CONTROL_BOUNDARY / ACCEPTED" $e8obs "required L3"))
[void]$sb.AppendLine((MatrixRow "E9" "FAILED_MEDIA_RECOVERY premature" $e9obs "must not after E2+E3"))
[void]$sb.AppendLine("")

$failureCase = if (-not $receiptObserved) { "INCONCLUSIVE (no receipt — delivery-failure path)" }
               elseif ($receiptObserved -and (-not $o1Reeval)) { "CASE_A (receipt without reevaluate — wiring)" }
               elseif ($o1Reeval -and $o4Premature) { "CASE_B (reevaluate but premature timeout — predicate)" }
               elseif ($o3Boundary -and (-not $o4Premature)) { "NONE (success path)" }
               elseif ($o1Reeval -and (-not $o4Premature) -and (-not $o3Boundary)) { "PASS_PARTIAL (L3 pending)" }
               else { "REVIEW" }
[void]$sb.AppendLine("FAILURE_ROUTING: $failureCase")
[void]$sb.AppendLine("")

[void]$sb.AppendLine("=== L1-L4 (Validation Gate) ===")
$l1 = if (-not $receiptObserved) { "NOT_OBSERVED" } elseif ($o1Reeval) { "PASS" } else { "FAIL" }
$l2 = if (-not $receiptObserved) { "NOT_OBSERVED" } elseif ($o4Premature) { "FAIL" } else { "PASS" }
$l3 = if ($o3Boundary) { "PASS" } elseif ($failLine -and $receiptObserved -and $o1Reeval -and (-not $o4Premature)) {
    "PASS_TERMINAL"
} elseif (-not $receiptObserved) { "NOT_OBSERVED" } else { "NOT_OBSERVED" }
[void]$sb.AppendLine("L1 Control (receipt->reevaluate): $l1")
[void]$sb.AppendLine("L2 Attempt (no premature timeout):  $l2")
[void]$sb.AppendLine("L3 Recovery (boundary/terminal):     $l3")
[void]$sb.AppendLine("L4 Presence:                         OUT_OF_SCOPE (observe manually)")
[void]$sb.AppendLine("Receipt observed:                    $(if ($receiptObserved) { 'yes' } else { 'no' })")
[void]$sb.AppendLine("ADMISSION_PENDING defer observed:    $(if ($deferObserved) { 'yes' } else { 'no' })")
[void]$sb.AppendLine("")

[void]$sb.AppendLine("=== VERDICT ===")
[void]$sb.AppendLine("O1 receipt->reevaluation: $(if ($o1Pass) { 'PASS' } else { 'FAIL' })")
[void]$sb.AppendLine("O2 glare/E2 policy:        $(if ($o2Pass) { 'PASS' } else { 'FAIL' })")
[void]$sb.AppendLine("O3 control boundary:      $(if ($o3Boundary) { 'OBSERVED' } else { 'NOT_OBSERVED' })")
[void]$sb.AppendLine("O4 premature timeout:     $(if ($o4Premature) { 'YES (regression)' } else { 'NO' })")
[void]$sb.AppendLine("ADMISSION_PENDING defer:  $(if ($admissionDefer) { 'yes' } else { 'no' })")
[void]$sb.AppendLine("")

if ($envInvalid) {
    $verdict = "RUN_INVALID"
} elseif (-not $receiptObserved) {
    $verdict = "INCONCLUSIVE"
} elseif (-not $o1Pass) {
    $verdict = "FAIL_O1"
} elseif ($o4Premature) {
    $verdict = "FAIL_PREMATURE_TIMEOUT"
} elseif ($o3Boundary) {
    $verdict = "PASS_FULL"
} elseif ($l1 -eq "PASS" -and $l2 -eq "PASS") {
    $verdict = "PASS_PARTIAL"
} else {
    $verdict = "INCONCLUSIVE"
}

$gate = if ($verdict -eq "PASS_FULL") { "GATE_PASS" }
        elseif ($verdict -eq "INCONCLUSIVE") { "GATE_INCONCLUSIVE" }
        elseif ($verdict -like "FAIL*") { "GATE_FAIL" }
        elseif ($verdict -eq "RUN_INVALID") { "GATE_INVALID" }
        else { "GATE_OPEN" }
[void]$sb.AppendLine("OVERALL: $verdict")
[void]$sb.AppendLine("VALIDATION_GATE: $gate")
[void]$sb.AppendLine("FAILURE_CASE: $failureCase")

$text = $sb.ToString()
$text | Set-Content $out -Encoding UTF8
Write-Host $text
