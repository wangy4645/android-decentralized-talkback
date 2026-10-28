# PA-SR4-PLAY field first-run — Peer Shadow Playout Execution Gate (harness-only).
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

Write-Host "PA-SR4-PLAY FIELD FIRST-RUN (Peer Shadow Playout Execution Gate)"
Write-Host "driver=phase-a-sr4-play-field-first-run.ps1"
Write-Host "orchestration=p1c-package-field-first-run.ps1 -DriverMode PaSr4Play"
Write-Host "adjudicator=phase-a-sr4-play-field-adjudicate.ps1"
Write-Host "gate=PA-SR4-PLAY (closes PA-SR4 on PASS)"
Write-Host "stimulus=3x_M01_PTT (NOT a PASS criterion)"
Write-Host "tx_prerequisite=SHADOW_TX_ACTIVITY packetsSent>0 on M01"
Write-Host "ingress_prerequisite=M02 INGRESS_ACTIVITY packetsReceived>0"
Write-Host "pass_requires=M02 PLAYOUT_ACTIVITY successfulPlayoutWrites>0"
Write-Host ""

if (-not (Test-Path $driverScript)) {
    throw "Missing orchestration script: $driverScript"
}

$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaSr4Play",
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
