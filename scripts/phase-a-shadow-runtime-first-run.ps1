# Phase A Shadow Runtime — first-run field driver (thin entry).
# Reuses PRE-G / PRE-I / PRE-J + J0 prerequisite from p1c-package-field-first-run.ps1 -DriverMode ShadowRuntime.
param(
    [long]$EstablishmentKeyVersion = 1,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$PackageName = "com.talkback.appprod",
    [string]$Activity = "com.talkback.appprod/.ui.MainActivity",
    [string]$ProvisioningManifest = "",
    [int]$PositiveWaitSec = 120,
    [int]$ShadowObservationSec = 45,
    [int]$ShadowRejoinWaitSec = 60,
    [int]$InviteJoinWaitSec = 30,
    [int]$InviteJoinPollMs = 500,
    [switch]$SkipBuildInstall,
    [switch]$SkipEpProbe
)

$ErrorActionPreference = "Stop"
$driverScript = Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"

Write-Host "PHASE A SHADOW RUNTIME FIRST-RUN"
Write-Host "driver=phase-a-shadow-runtime-first-run.ps1"
Write-Host "orchestration=p1c-package-field-first-run.ps1 -DriverMode ShadowRuntime"
Write-Host "adjudicator=phase-a-shadow-runtime-adjudicate.ps1"
Write-Host ""

if (-not (Test-Path $driverScript)) {
    throw "Missing orchestration script: $driverScript"
}

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "ShadowRuntime",
    "-EstablishmentKeyVersion", "$EstablishmentKeyVersion",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-PackageName", $PackageName,
    "-Activity", $Activity,
    "-PositiveWaitSec", "$PositiveWaitSec",
    "-ShadowObservationSec", "$ShadowObservationSec",
    "-ShadowRejoinWaitSec", "$ShadowRejoinWaitSec",
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
