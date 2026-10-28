# Adjudicate G-RES-3 C4 formal qualifying run evidence (per-run closeout gate).

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$bundleDir = Join-Path $LogDir "bundle"
$c4Path = Get-ChildItem -Path $bundleDir -Recurse -Filter "c4-adjudication.json" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $c4Path) { throw "c4-adjudication.json missing" }

$summaryPath = Get-ChildItem -Path $bundleDir -Recurse -Filter "summary.json" | Select-Object -First 1
$manifestPath = Get-ChildItem -Path $bundleDir -Recurse -Filter "manifest.json" | Select-Object -First 1
$configPath = Get-ChildItem -Path $bundleDir -Recurse -Filter "config.json" | Select-Object -First 1
if (-not $summaryPath) { throw "summary.json missing" }
if (-not $manifestPath) { throw "manifest.json missing" }
if (-not $configPath) { throw "config.json missing" }

$c4 = Get-Content $c4Path.FullName -Raw | ConvertFrom-Json
$summary = Get-Content $summaryPath.FullName -Raw | ConvertFrom-Json
$manifest = Get-Content $manifestPath.FullName -Raw | ConvertFrom-Json
$config = Get-Content $configPath.FullName -Raw | ConvertFrom-Json

$preflightFailures = @()
if ($manifest.runClass -ne "QUALIFYING") {
    $preflightFailures += "manifest.runClass=$($manifest.runClass) expected QUALIFYING"
}
if ($summary.runClass -ne "QUALIFYING") {
    $preflightFailures += "summary.runClass=$($summary.runClass) expected QUALIFYING"
}
if ($config.runClass -ne "QUALIFYING") {
    $preflightFailures += "config.runClass=$($config.runClass) expected QUALIFYING"
}
if ($config.warmupSec -ne 60 -or $config.measurementSec -ne 600 -or $config.cooldownSec -ne 30) {
    $preflightFailures += "duration not frozen 60/600/30"
}

$qe = $summary.qualifyingEvidence
if (-not $qe) {
    $preflightFailures += "summary.qualifyingEvidence missing"
} else {
    if ($qe.expectedLogicalSlots -ne 30000) {
        $preflightFailures += "expectedLogicalSlots=$($qe.expectedLogicalSlots) expected 30000"
    }
    if ($qe.expectedLegSendAttempts -ne 270000) {
        $preflightFailures += "expectedLegSendAttempts=$($qe.expectedLegSendAttempts) expected 270000"
    }
    if ($qe.perLegSendAttemptCount -ne 270000) {
        $preflightFailures += "perLegSendAttemptCount=$($qe.perLegSendAttemptCount) expected 270000"
    }
    if ($qe.notAttemptedCount -ne 0) {
        $preflightFailures += "notAttemptedCount=$($qe.notAttemptedCount) expected 0"
    }
    if ($qe.partialBatchCount -ne 0) {
        $preflightFailures += "partialBatchCount=$($qe.partialBatchCount) expected 0"
    }
    if ($qe.fanoutIncompleteSlotCount -ne 0) {
        $preflightFailures += "fanoutIncompleteSlotCount=$($qe.fanoutIncompleteSlotCount) expected 0"
    }
    if ($summary.executionModel -eq "SENDMMSG_BATCH" -and $qe.minReturnedMessages -ne 9) {
        $preflightFailures += "minReturnedMessages=$($qe.minReturnedMessages) expected 9"
    }
}

$report = @"
# G-RES-3 C4 Qualifying Run Adjudication

```text
runClass:                   $($summary.runClass)
executionModel:             $($summary.executionModel)
validity:                   $($c4.validity)
c3Tier1:                    $($c4.c3Tier1)
c3Tier2:                    $($c4.c3Tier2)
runVerdict:                 $($c4.runVerdict)
eligibleForQualifyingSet:   $($c4.eligibleForQualifyingSet)
```

## Qualifying evidence (summary)

$(if ($qe) {
@"
expectedLogicalSlots:       $($qe.expectedLogicalSlots)
expectedLegSendAttempts:    $($qe.expectedLegSendAttempts)
perLegSendAttemptCount:     $($qe.perLegSendAttemptCount)
notAttemptedCount:          $($qe.notAttemptedCount)
partialBatchCount:          $($qe.partialBatchCount)
fanoutCompleteSlotCount:    $($qe.fanoutCompleteSlotCount)
fanoutIncompleteSlotCount:  $($qe.fanoutIncompleteSlotCount)
minReturnedMessages:        $($qe.minReturnedMessages)
batchSyscallDurationUs:     $($qe.batchSyscallDurationUs)   (diagnostic only)
"@
} else { "qualifyingEvidence: MISSING" })

## C4 reasons

$($c4.reasons -join ", ")

## Preflight wiring checks

$(if ($preflightFailures.Count -eq 0) { "PASS" } else { $preflightFailures -join "`n" })

## Decision

$(if ($c4.runVerdict -eq "PASS" -and $c4.eligibleForQualifyingSet -eq $true -and $preflightFailures.Count -eq 0) {
    "VALID PASS — eligible for 3-run qualifying set."
} elseif ($c4.runVerdict -eq "FAIL") {
    "VALID FAIL — capacity not proven; do not advance qualifying set."
} else {
    "INVALID — harness/evidence wiring incomplete; rerun after fix (not a capacity FAIL)."
})
"@

$path = Join-Path $LogDir "ADJUDICATION.md"
Set-Content -Path $path -Value $report -Encoding UTF8
Write-Host $report

if ($preflightFailures.Count -gt 0) { exit 2 }
if ($c4.runVerdict -eq "INVALID") { exit 1 }
if ($c4.runVerdict -eq "FAIL") { exit 3 }
exit 0
