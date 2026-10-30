# Adjudicate G-RES-3-H1a qualification bundle (harness validity only; NOT G-RES-3 capacity PASS).

param(
    [Parameter(Mandatory = $true)]
    [string]$LogDir
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $LogDir)) {
    throw "LogDir not found: $LogDir"
}

$bundleDir = Join-Path $LogDir "bundle"
$manifestCandidate = Get-ChildItem -Path $bundleDir -Recurse -Filter "manifest.json" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($manifestCandidate) {
    $bundleDir = Split-Path $manifestCandidate.FullName -Parent
}

$manifestPath = Join-Path $bundleDir "manifest.json"
$summaryPath = Join-Path $bundleDir "summary.json"
$configPath = Join-Path $bundleDir "config.json"
$rawPath = Join-Path $bundleDir "raw/slot-metrics.csv"

$checks = [ordered]@{
    instrumentOk = $false
    manifestPresent = $false
    configPresent = $false
    summaryPresent = $false
    rawFlushed = $false
    runClassQualification = $false
    topologyNineLegs = $false
    thermalPreflightPass = $false
    qualificationPass = $false
}

$instrumentPath = Join-Path $LogDir "instrument.txt"
if ((Test-Path $instrumentPath) -and (Get-Content $instrumentPath -Raw) -match "OK \(") {
    $checks.instrumentOk = $true
}

if (Test-Path $manifestPath) {
    $checks.manifestPresent = $true
    $manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.runClass -eq "QUALIFICATION") { $checks.runClassQualification = $true }
    if ($manifest.requiredLegCount -eq 9 -and $manifest.topology.Count -eq 9) {
        $checks.topologyNineLegs = $true
    }
    if ($manifest.thermalPreflight.passesPreflight -eq $true) {
        $checks.thermalPreflightPass = $true
    }
}

if (Test-Path $configPath) { $checks.configPresent = $true }
if (Test-Path $summaryPath) {
    $checks.summaryPresent = $true
    $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
    if ($summary.qualification.qualificationPass -eq $true) {
        $checks.qualificationPass = $true
    }
}
if (Test-Path $rawPath) { $checks.rawFlushed = $true }

$harnessValidityPass =
    $checks.instrumentOk -and
    $checks.manifestPresent -and
    $checks.configPresent -and
    $checks.summaryPresent -and
    $checks.rawFlushed -and
    $checks.runClassQualification -and
    $checks.topologyNineLegs -and
    $checks.thermalPreflightPass -and
    $checks.qualificationPass

$verdict = if ($harnessValidityPass) { "HARNESS_VALIDITY_PASS" } else { "HARNESS_VALIDITY_FAIL" }

$report = @"
# G-RES-3-H1a Qualification Adjudication

```text
verdict:                  $verdict
formal capacity evidence: NOT STARTED
G-RES-3 status:           EVIDENCE PENDING
topology class:           loopback harness (127.0.0.1) — NOT formal capacity topology
```

## Mechanical checks

| Check | Result |
|-------|--------|
| instrumentation OK | $($checks.instrumentOk) |
| manifest.json | $($checks.manifestPresent) |
| config.json | $($checks.configPresent) |
| summary.json | $($checks.summaryPresent) |
| raw/slot-metrics.csv | $($checks.rawFlushed) |
| runClass=QUALIFICATION | $($checks.runClassQualification) |
| topology 9 legs | $($checks.topologyNineLegs) |
| thermal preflight pass | $($checks.thermalPreflightPass) |
| qualificationPass (summary) | $($checks.qualificationPass) |

## Notes

- This adjudication validates **harness validity only**.
- It does **not** assert G-RES-3 capacity PASS or ProvenNMax>=9.
- Formal 3×QUALIFYING requires frozen real/representative 9-target topology.

Bundle: $bundleDir
"@

$reportPath = Join-Path $LogDir "ADJUDICATION.md"
Set-Content -Path $reportPath -Value $report -Encoding UTF8
Write-Host $report
Write-Host "Wrote $reportPath"

if (-not $harnessValidityPass) { exit 1 }
