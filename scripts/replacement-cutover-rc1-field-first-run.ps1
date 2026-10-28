# RC1 field first-run — M02 peer audible ownership flip (M01 host stimulus).
param(
    [long]$EstablishmentKeyVersion = 1,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$PackageName = "com.talkback.appprod",
    [string]$Activity = "com.talkback.appprod/.ui.MainActivity",
    [string]$ProvisioningManifest = "",
    [int]$PositiveWaitSec = 120,
    [int]$ShadowObservationSec = 45,
    [int]$ShadowRejoinWaitSec = 30,
    [int]$Rc1PostCutoverSec = 20,
    [int]$Rc1PostRollbackSec = 20,
    [int]$InviteJoinWaitSec = 30,
    [int]$InviteJoinPollMs = 500,
    [switch]$SkipBuildInstall,
    [switch]$SkipEpProbe
)

$ErrorActionPreference = "Stop"
$driverScript = Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"

Write-Host "REPLACEMENT CUTOVER RC1 FIELD FIRST-RUN"
Write-Host "driver=p1c-package-field-first-run.ps1 -DriverMode PaRc1"
Write-Host "adjudicator=replacement-cutover-rc1-field-adjudicate.ps1"
Write-Host "windows=A_anchor_B_cutover_C_rollback"
Write-Host ""

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaRc1",
    "-EstablishmentKeyVersion", "$EstablishmentKeyVersion",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-PackageName", $PackageName,
    "-Activity", $Activity,
    "-PositiveWaitSec", "$PositiveWaitSec",
    "-ShadowObservationSec", "$ShadowObservationSec",
    "-ShadowRejoinWaitSec", "$ShadowRejoinWaitSec",
    "-Rc1PostCutoverSec", "$Rc1PostCutoverSec",
    "-Rc1PostRollbackSec", "$Rc1PostRollbackSec",
    "-InviteJoinWaitSec", "$InviteJoinWaitSec",
    "-InviteJoinPollMs", "$InviteJoinPollMs"
)
if ($ProvisioningManifest) { $driverArgs += @("-ProvisioningManifest", $ProvisioningManifest) }
if ($SkipBuildInstall) { $driverArgs += "-SkipBuildInstall" }
if ($SkipEpProbe) { $driverArgs += "-SkipEpProbe" }

& powershell @driverArgs
exit $LASTEXITCODE
