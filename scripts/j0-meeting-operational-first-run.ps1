# Meeting Profile01 J0 — first-run field driver (thin entry).
# Reuses PRE-G / PRE-I / PRE-J orchestration from p1c-package-field-first-run.ps1 -DriverMode J0.
# No C-F7 · no harness republish · j0-meeting-operational-adjudicate.ps1 only.
param(
    [long]$EstablishmentKeyVersion = 1,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$PackageName = "com.talkback.appprod",
    [string]$Activity = "com.talkback.appprod/.ui.MainActivity",
    [string]$ProvisioningManifest = "",
    [int]$PositiveWaitSec = 120,
    [int]$InviteJoinWaitSec = 30,
    [int]$InviteJoinPollMs = 500,
    [switch]$SkipBuildInstall,
    [switch]$SkipEpProbe,
    [switch]$AllowCf7Injection,
    [switch]$AllowHarnessRepublish,
    [switch]$AllowManualContinuationDrain
)

$ErrorActionPreference = "Stop"
$driverScript = Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"

Write-Host "J0 MEETING OPERATIONAL FIRST-RUN"
Write-Host "driver=j0-meeting-operational-first-run.ps1"
Write-Host "orchestration=p1c-package-field-first-run.ps1 -DriverMode J0"
Write-Host "adjudicator=j0-meeting-operational-adjudicate.ps1"
Write-Host ""

# --- Hard pre-run forbidden checks ---
$forbidden = @()
if ($AllowCf7Injection) { $forbidden += "C-F7 injection must remain OFF for J0" }
if ($AllowHarnessRepublish) { $forbidden += "P1C_REPUBLISH_AFTER_BIND / harness-only post-bind must remain OFF" }
if ($AllowManualContinuationDrain) { $forbidden += "manual continuation drain must remain OFF" }
if ($env:J0_ALLOW_CF7 -eq "1") { $forbidden += "env J0_ALLOW_CF7=1 forbidden" }
if ($env:J0_ALLOW_HARNESS_REPUBLISH -eq "1") { $forbidden += "env J0_ALLOW_HARNESS_REPUBLISH=1 forbidden" }
if ($env:J0_ALLOW_MANUAL_DRAIN -eq "1") { $forbidden += "env J0_ALLOW_MANUAL_DRAIN=1 forbidden" }

if ($forbidden.Count -gt 0) {
    Write-Error ("J0 driver pre-run check FAILED:`n - " + ($forbidden -join "`n - "))
}

Write-Host "Pre-run manifest (frozen OFF):"
Write-Host "  C-F7 injection                 OFF"
Write-Host "  P1C_REPUBLISH_AFTER_BIND       OFF"
Write-Host "  harness-only post-bind         OFF"
Write-Host "  manual continuation drain      OFF"
Write-Host ""

if (-not (Test-Path $driverScript)) {
    throw "Missing orchestration script: $driverScript"
}

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "J0",
    "-EstablishmentKeyVersion", "$EstablishmentKeyVersion",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-PackageName", $PackageName,
    "-Activity", $Activity,
    "-PositiveWaitSec", "$PositiveWaitSec",
    "-InviteJoinWaitSec", "$InviteJoinWaitSec",
    "-InviteJoinPollMs", "$InviteJoinPollMs"
)
if ($ProvisioningManifest) {
    $driverArgs += @("-ProvisioningManifest", $ProvisioningManifest)
}
if ($SkipBuildInstall) { $driverArgs += "-SkipBuildInstall" }
if ($SkipEpProbe) { $driverArgs += "-SkipEpProbe" }

& powershell @driverArgs
exit $LASTEXITCODE
