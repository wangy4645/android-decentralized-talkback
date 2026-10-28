# Adjudicate G-RES-3 E3b nonblocking sendmmsg architecture remediation probe.

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $LogDir)) { throw "LogDir not found: $LogDir" }

$bundleDir = Join-Path $LogDir "bundle"
$summaryPath = Get-ChildItem -Path $bundleDir -Recurse -Filter "summary.json" | Select-Object -First 1
$h1cPath = Get-ChildItem -Path $bundleDir -Recurse -Filter "h1c-adjudication.json" | Select-Object -First 1
if (-not $summaryPath) { throw "summary.json missing" }
if (-not $h1cPath) { throw "h1c-adjudication.json missing" }

$summary = Get-Content $summaryPath.FullName -Raw | ConvertFrom-Json
$h1c = Get-Content $h1cPath.FullName -Raw | ConvertFrom-Json
$probe = $summary.sendmmsgProbe
$counters = $summary.counters

$p99Ms = [math]::Round($counters.fanoutP99Ns / 1e6, 3)
$maxMs = [math]::Round($counters.fanoutMaxNs / 1e6, 3)
$schedMaxMs = [math]::Round($counters.schedLatenessMaxNs / 1e6, 3)
$slotExecMaxMs = if ($counters.slotExecutionCompletionMaxNs) {
    [math]::Round($counters.slotExecutionCompletionMaxNs / 1e6, 3)
} else {
    $null
}

$checks = @()
function Add-Check([string]$Name, [bool]$Pass, [string]$Detail) {
    $script:checks += [PSCustomObject]@{ Name = $Name; Pass = $Pass; Detail = $Detail }
}

Add-Check "executionModel" ($summary.executionModel -eq "SENDMMSG_BATCH_NONBLOCKING") $summary.executionModel
Add-Check "topologyEligible" ($h1c.formalEvidenceTopology -eq "ELIGIBLE") $h1c.formalEvidenceTopology
Add-Check "fanoutP99_le_5ms" ($p99Ms -le 5.0) "${p99Ms}ms"
Add-Check "fanoutMax_lt_10ms" ($maxMs -lt 10.0) "${maxMs}ms"
Add-Check "partialBatchCount_eq_0" ($probe.partialBatchCount -eq 0) $probe.partialBatchCount
Add-Check "notAttempted_eq_0" ($counters.notAttemptedCount -eq 0) $counters.notAttemptedCount
Add-Check "sendFail_eq_0" ($counters.sendFailCount -eq 0) $counters.sendFailCount
Add-Check "minReturned_eq_9" ($probe.minReturnedMessages -eq 9) $probe.minReturnedMessages
Add-Check "eagain_eq_0" ($probe.eagainCount -eq 0) $probe.eagainCount
Add-Check "deadlineMiss_eq_0" ($counters.slotDeadlineMissCount -eq 0) $counters.slotDeadlineMissCount
Add-Check "fanoutIncomplete_eq_0" ($counters.fanoutIncompleteSlotCount -eq 0) $counters.fanoutIncompleteSlotCount

$tailPass = ($checks | Where-Object { $_.Name -in @("fanoutP99_le_5ms", "fanoutMax_lt_10ms") }).Pass -notcontains $false
$integrityPass = ($checks | Where-Object { $_.Name -notin @("fanoutP99_le_5ms", "fanoutMax_lt_10ms", "topologyEligible", "executionModel") }).Pass -notcontains $false
$strongSignal = $tailPass -and $integrityPass -and ($maxMs -le 8.0)

$verdict = if ($strongSignal) { "STRONG_REMEDIATION_SIGNAL" }
elseif ($tailPass -and $integrityPass) { "PASS_TAIL_AND_INTEGRITY" }
elseif ($tailPass -and -not $integrityPass) { "REJECT_LATENCY_GAMING" }
elseif (-not $tailPass -and $integrityPass) { "TAIL_FAIL_INTEGRITY_OK" }
else { "FAIL" }

$checkLines = ($checks | ForEach-Object { "$(if ($_.Pass) { 'PASS' } else { 'FAIL' }) $($_.Name): $($_.Detail)" }) -join "`n"

$report = @"
# G-RES-3 E3b Nonblocking sendmmsg Probe Adjudication

```text
executionModel:       $($summary.executionModel)
topologyEligible:     $($h1c.formalEvidenceTopology)
c3Projection:         $($h1c.c3Projection)

fanoutP99:            ${p99Ms} ms
fanoutMAX:            ${maxMs} ms
schedLatenessMAX:     ${schedMaxMs} ms
slotExecutionMAX:     ${slotExecMaxMs} ms

partialBatchCount:    $($probe.partialBatchCount)
notAttemptedCount:    $($counters.notAttemptedCount)
eagainCount:          $($probe.eagainCount)
minReturnedMessages:  $($probe.minReturnedMessages)
sendFailCount:        $($counters.sendFailCount)
deadlineMissCount:    $($counters.slotDeadlineMissCount)
fanoutIncomplete:     $($counters.fanoutIncompleteSlotCount)
worstSyscallWallNs:   $($probe.worstSyscallWallNs)
```

## Checks

$checkLines

## Verdict

$verdict

$(if ($verdict -eq "REJECT_LATENCY_GAMING") {
    "Tail looks good but integrity failed — likely partial/EAGAIN gaming, NOT remediation success."
} elseif ($verdict -eq "STRONG_REMEDIATION_SIGNAL") {
    "MAX <= 8ms with zero partial/EAGAIN — strong signal that nonblocking sendmmsg removes kernel stall tail."
} elseif ($verdict -eq "PASS_TAIL_AND_INTEGRITY") {
    "C3 tail + submission integrity pass — remediation candidate; compare margin vs E3 blocking baseline."
} else {
    "Does not meet E3b probe success criteria."
})
"@

$path = Join-Path $LogDir "ADJUDICATION.md"
Set-Content -Path $path -Value $report -Encoding UTF8
Write-Host $report

if ($verdict -in @("FAIL", "REJECT_LATENCY_GAMING", "TAIL_FAIL_INTEGRITY_OK")) { exit 3 }
exit 0
