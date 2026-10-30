# PA-SR3 field first-run — reuses Shadow Runtime orchestration; PA-SR3-only adjudicator.
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

Write-Host "PA-SR3 FIELD FIRST-RUN (PR-PA-SR-B1)"
Write-Host "driver=phase-a-sr3-field-first-run.ps1"
Write-Host "orchestration=p1c-package-field-first-run.ps1 -DriverMode PaSr3"
Write-Host "adjudicator=phase-a-sr3-field-adjudicate.ps1"
Write-Host "gate=PA-SR3_ONLY (PA-SR4..SR6 observe only)"
Write-Host ""

if (-not (Test-Path $driverScript)) {
    throw "Missing orchestration script: $driverScript"
}

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaSr3",
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
