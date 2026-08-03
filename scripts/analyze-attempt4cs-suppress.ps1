# Attempt-4c-S classifier - SUPPRESS_SUCCESSOR_ATTEMPT topology exercise
# Evidence classification only - not R4 adoption, not Joint PASS/FAIL.

param(
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$RemoteModule = "M03",
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $LogDir)) { Write-Error "LogDir not found: $LogDir" }

$authLog = Join-Path $LogDir "auth-stream.log"
if (-not (Test-Path $authLog)) {
    $dumps = Get-ChildItem (Join-Path $LogDir "adb-dumps") -Filter "*.log" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
    if ($dumps.Count -ge 1) { $authLog = $dumps[0].FullName }
}
if (-not (Test-Path $authLog)) { Write-Error "No auth-stream.log or adb-dumps under $LogDir" }

$lines = Get-Content $authLog -ErrorAction Stop
function Find-Lines([string]$pattern) { @($lines | Where-Object { $_ -match $pattern }) }

$armed = Find-Lines "SUPPRESS_SUCCESSOR_ATTEMPT_ARMED"
$applied = Find-Lines "SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED"
$harnessSuppressed = Find-Lines "HARNESS_SUCCESSOR_SUPPRESSION_APPLIED"
$admit = Find-Lines "ADMIT_SUCCESSOR_OBLIGATION_EPISODE"
if ($RemoteModule) { $admit = @($admit | Where-Object { $_ -match "remote=$RemoteModule" }) }
$superseded = Find-Lines "RECOVERY_DELIVERY_LINEAGE_SUPERSEDED|RECOVERY_ATTEMPT_SUPERSEDED|CLOSED_SUPERSEDED|state=CLOSED_SUPERSEDED"
$obligationClosed = Find-Lines "RECOVERY_OBLIGATION_CLOSED|obligationCloseReason="
$recovered = Find-Lines "RECOVERY_COMPLETION_DECISION.*candidate=RECOVERED|RECOVERY_EDGE_RECOVERED|RECOVERY_COMPLETION_EVIDENCE_ACCEPTED"
if ($RemoteModule) { $recovered = @($recovered | Where-Object { $_ -match "remote=$RemoteModule" }) }
$adopted = Find-Lines "SUCCESSOR_OBLIGATION_ADOPTED|TRANSFERRED"
$ignoredSuppress = Find-Lines "reason=suppress_successor_attempt"

$successorAdmitted = $admit.Count -ge 1
$suppressHit = $applied.Count -ge 1 -or $harnessSuppressed.Count -ge 1
$hasSupersedeOrClosed = $superseded.Count -ge 1 -or $obligationClosed.Count -ge 1
$hasRecovered = $recovered.Count -ge 1
$hasAdoptionLeak = $adopted.Count -ge 1

$caseS = "UNKNOWN"
$notes = @()
if ($hasAdoptionLeak) { $caseS = "ABORT_ADOPTION_LEAK"; $notes += "ADOPTED/TRANSFERRED observed" }
elseif ($hasRecovered -and -not $successorAdmitted -and -not $suppressHit) { $caseS = "C"; $notes += "RECOVERED without successor/adoption" }
elseif ($successorAdmitted) { $caseS = "B"; $notes += "successor admitted - SUPPRESS miss" }
elseif ($hasSupersedeOrClosed -and -not $successorAdmitted) {
    $caseS = "A"
    if ($suppressHit) { $notes += "SUPERSEDED/CLOSED + APPLIED" } else { $notes += "SUPERSEDED/CLOSED + no successor; ARMED only" }
}
elseif ($armed.Count -ge 1 -and -not $successorAdmitted) { $caseS = "A_ARMED_ONLY"; $notes += "ARMED only; supersede/close unclear" }
else { $caseS = "INCONCLUSIVE"; $notes += "insufficient topology evidence" }

if ([string]::IsNullOrWhiteSpace($ReportPath)) { $ReportPath = Join-Path $LogDir "ATTEMPT4CS_SUPPRESS_CLASSIFICATION.txt" }

$out = @(
    "Attempt-4c-S SUPPRESS Topology Classification",
    "logDir=$LogDir",
    "authLog=$authLog",
    "remote=$RemoteModule",
    "",
    "topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR",
    "caseS=$caseS",
    "",
    "facts:",
    "  ARMED=$($armed.Count)",
    "  APPLIED=$($applied.Count)",
    "  HARNESS_SUCCESSOR_SUPPRESSION_APPLIED=$($harnessSuppressed.Count)",
    "  ADMIT_SUCCESSOR=$($admit.Count)",
    "  SUPERSEDED_OR_CLOSED_HINTS=$($superseded.Count + $obligationClosed.Count)",
    "  RECOVERED=$($recovered.Count)",
    "  ADOPTION_LEAK=$($adopted.Count)",
    "  IGNORE_SUPPRESS=$($ignoredSuppress.Count)",
    "",
    "allowed: SUPERSEDED | CLOSED_SUPERSEDED | SUCCESSOR_ADMISSION_NOT_OBSERVED",
    "forbidden: TRANSFERRED | ADOPTED",
    "",
    "notes:"
)
foreach ($n in $notes) { $out += "  - $n" }
$out += ""
$out += "next:"
switch ($caseS) {
    "A" { $out += "  -> Joint candidate (under EXERCISE_SUPPRESSED_SUCCESSOR)" }
    "A_ARMED_ONLY" { $out += "  -> extend flap/window or confirm supersede stimulation" }
    "B" { $out += "  -> fix harness SUPPRESS hit path; do not enter R4" }
    "C" { $out += "  -> STOP - inspect Completion/R4 ownership boundary" }
    "ABORT_ADOPTION_LEAK" { $out += "  -> STOP - unexpected adoption facts" }
    default { $out += "  -> inconclusive; do not promote to Joint" }
}
$out | Set-Content -Path $ReportPath -Encoding utf8
Write-Output ($out -join [Environment]::NewLine)
if ($caseS -eq "C" -or $caseS -eq "ABORT_ADOPTION_LEAK") { exit 2 }
if ($caseS -eq "B") { exit 1 }
exit 0
