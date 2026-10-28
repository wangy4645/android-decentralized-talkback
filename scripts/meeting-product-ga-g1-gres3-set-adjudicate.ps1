# G1 Scale — adjudicate 3× VALID PASS @ N=9 qualifying set (semantic: capacity only).
# Exit: 0 PASS · 1 VALID FAIL · 2 INVALID / incomplete set
param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

function Read-VerdictFile([string]$Path) {
    if (-not (Test-Path $Path)) { return $null }
    $map = @{}
    Get-Content $Path | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $map[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    return $map
}

$runs = @(Get-ChildItem -Path $LogDir -Filter "run-*" -Directory -ErrorAction SilentlyContinue | Sort-Object Name)
$passCount = 0
$validFailCount = 0
$invalidCount = 0
$details = @()

foreach ($runDir in $runs) {
    $adj = Join-Path $runDir.FullName "ADJUDICATION.md"
    $exitPath = Join-Path $runDir.FullName "RUN_EXIT.txt"
    $verdict = "MISSING"
    $eligible = "false"
    if (Test-Path $adj) {
        $text = Get-Content $adj -Raw
        if ($text -match 'runVerdict:\s+(\S+)') { $verdict = $Matches[1] }
        if ($text -match 'eligibleForQualifyingSet:\s+(\S+)') { $eligible = $Matches[1] }
        if ($text -match 'VALID PASS') { $verdict = "PASS" }
        elseif ($text -match 'VALID FAIL') { $verdict = "FAIL" }
        elseif ($text -match 'INVALID') { $verdict = "INVALID" }
    }
    if (Test-Path $exitPath) {
        $exitMap = Read-VerdictFile $exitPath
        if ($exitMap['verdict']) { $verdict = $exitMap['verdict'] }
    }
    switch ($verdict) {
        "PASS" { if ($eligible -eq "true" -or $eligible -eq "True") { $passCount++ } else { $invalidCount++ } }
        "FAIL" { $validFailCount++ }
        default { $invalidCount++ }
    }
    $details += "$($runDir.Name): verdict=$verdict eligible=$eligible"
}

$required = 3
$gateVerdict = "INCOMPLETE"
$reason = "qualifying_set_incomplete"
if ($passCount -ge $required) {
    $gateVerdict = "PASS"
    $reason = "3x_valid_pass_at_n9"
} elseif ($validFailCount -gt 0 -and $passCount + $validFailCount -ge $required) {
    $gateVerdict = "VALID_FAIL"
    $reason = "capacity_not_proven_at_n9"
} elseif ($invalidCount -gt 0 -and ($passCount + $validFailCount + $invalidCount) -ge $required) {
    $gateVerdict = "INVALID"
    $reason = "harness_or_environment_incomplete"
}

$lines = @(
    "G1_SCALE"
    "gate=G-RES-3"
    "requiredRuns=3"
    "validPassCount=$passCount"
    "validFailCount=$validFailCount"
    "invalidCount=$invalidCount"
    "verdict=$gateVerdict"
    "reason=$reason"
    "semantic=scale_capacity_only"
    "notSubstitutableFor=G2,G3"
    ""
    "runs:"
) + $details

if ($gateVerdict -eq "PASS") {
    $lines += "G1_RESULT=PASS"
    $lines += "Next=G2_G3_parallel_or_GA_review_when_all_pass"
} elseif ($gateVerdict -eq "VALID_FAIL") {
    $lines += "G1_RESULT=VALID_FAIL"
    $lines += "Next=RCA_CAPACITY_NO_HARNESS_CHANGE_WITHOUT_DEFECT"
} else {
    $lines += "G1_RESULT=INVALID"
    $lines += "Next=FIX_HARNESS_OR_ENV_RERUN_G1"
}

$out = $lines -join "`n"
$out | Set-Content -Encoding utf8 (Join-Path $LogDir "G1_RESULT.txt")
$out | Set-Content -Encoding utf8 (Join-Path $LogDir "ADJUDICATION.txt")
Write-Host $out

if ($gateVerdict -eq "PASS") { exit 0 }
if ($gateVerdict -eq "VALID_FAIL") { exit 1 }
exit 2
