# Controlled Pilot Run-1 — product use observation (reuses RC1 cutover + SR6 Meeting end).
# No mandatory rollback. No new Kotlin. SkipBuildInstall by default (code freeze).
param(
    [long]$EstablishmentKeyVersion = 1,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$PackageName = "com.talkback.appprod",
    [string]$Activity = "com.talkback.appprod/.ui.MainActivity",
    [string]$ProvisioningManifest = "",
    [int]$PositiveWaitSec = 120,
    [int]$ShadowObservationSec = 45,
    [int]$Rc1PostCutoverSec = 20,
    [int]$PilotUseSec = 60,
    [int]$InviteJoinWaitSec = 30,
    [int]$InviteJoinPollMs = 500,
    [switch]$SkipBuildInstall = $true,
    [switch]$SkipEpProbe,
    [switch]$ForceBuildInstall
)

$ErrorActionPreference = "Stop"
$driverScript = Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"

Write-Host "CONTROLLED PILOT RUN-1"
Write-Host "driver=p1c-package-field-first-run.ps1 -DriverMode PaPilot1"
Write-Host "adjudicator=controlled-pilot-run1-field-adjudicate.ps1"
Write-Host "flow=Meeting→healthy→cutover→multicast use→Meeting end→teardown"
Write-Host "mandatoryRollback=false"
Write-Host ""

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaPilot1",
    "-EstablishmentKeyVersion", "$EstablishmentKeyVersion",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-PackageName", $PackageName,
    "-Activity", $Activity,
    "-PositiveWaitSec", "$PositiveWaitSec",
    "-ShadowObservationSec", "$ShadowObservationSec",
    "-Rc1PostCutoverSec", "$Rc1PostCutoverSec",
    "-PilotUseSec", "$PilotUseSec",
    "-InviteJoinWaitSec", "$InviteJoinWaitSec",
    "-InviteJoinPollMs", "$InviteJoinPollMs"
)
if ($ProvisioningManifest) { $driverArgs += @("-ProvisioningManifest", $ProvisioningManifest) }
if ($ForceBuildInstall) {
    # omit SkipBuildInstall
} elseif ($SkipBuildInstall) {
    $driverArgs += "-SkipBuildInstall"
}
if ($SkipEpProbe) { $driverArgs += "-SkipEpProbe" }

& powershell @driverArgs
exit $LASTEXITCODE
