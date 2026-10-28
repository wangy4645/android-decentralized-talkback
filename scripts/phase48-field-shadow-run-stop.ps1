# ADR-0056 Phase 4-8 — Field shadow evidence run STOP (pull logcat evidence).
param(
    [Parameter(Mandatory = $true)]
    [string]$RunId,
    [string[]]$DeviceSerials = @(
        "HTUBB21B09220661",
        "DSJ-2407041",
        "MDX0220416001963"
    ),
    [string]$PrimarySerial = "MDX0220416001963"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

$logDir = Join-Path $repoRoot "logs/$RunId"
if (-not (Test-Path $logDir)) {
    throw "Missing log directory: $logDir — run start script first"
}

$adb = (Get-Command adb).Source
$primaryLog = Join-Path $logDir "logcat-shadow-field-$PrimarySerial.txt"
& $adb -s $PrimarySerial logcat -d -s SHADOW_FIELD_EVIDENCE:* > $primaryLog

foreach ($serial in $DeviceSerials) {
    if ($serial -eq $PrimarySerial) { continue }
    $path = Join-Path $logDir "logcat-shadow-field-$serial.txt"
    & $adb -s $serial logcat -d -s SHADOW_FIELD_EVIDENCE:* > $path
}

$evidencePath = Join-Path $logDir "SHADOW_FIELD_EVIDENCE.txt"
if (Test-Path $primaryLog) {
    Get-Content $primaryLog | Where-Object { $_ -match "SHADOW_FIELD_EVIDENCE|SHADOW_FIELD_RUN_EVIDENCE|mismatchRecord|END_SHADOW" } |
        Set-Content $evidencePath -Encoding UTF8
}

@(
    "finished=$(Get-Date -Format o)"
    "primarySerial=$PrimarySerial"
    "evidenceFile=$evidencePath"
) | Add-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "Phase 4-8 STOP runId=$RunId"
Write-Host "Evidence: $evidencePath"
Write-Host "Adjudicate: scripts/phase48-field-shadow-adjudicate.ps1 -RunId $RunId"
