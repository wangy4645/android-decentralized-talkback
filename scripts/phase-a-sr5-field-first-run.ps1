# PA-SR5 field first-run — M01 SOURCE generation replace (peer M02 adjudication).
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

Write-Host "PA-SR5 FIELD FIRST-RUN (M01 SOURCE Generation Replace)"
Write-Host "driver=phase-a-sr5-field-first-run.ps1"
Write-Host "orchestration=p1c-package-field-first-run.ps1 -DriverMode PaSr5"
Write-Host "adjudicator=phase-a-sr5-field-adjudicate.ps1"
Write-Host "stimulus=PA_SR5_ROTATE_LOCAL_SOURCE on M01 (product SOURCE succession)"
Write-Host "NOT=stimulus_M01_host_leave_or_M02_leave_rejoin"
Write-Host ""

if (-not (Test-Path $driverScript)) {
    throw "Missing orchestration script: $driverScript"
}

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaSr5",
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
