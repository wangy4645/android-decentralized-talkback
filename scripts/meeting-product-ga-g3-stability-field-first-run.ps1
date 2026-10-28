# G3 Stability — production multicast soak on PaG2 3-node Real-Mesh path.
# G3a (≥15m) = GA minimum blocker · G3b (≥2h) = ADR-0056 retirement confidence (does not block GA).
# T0 = MULTICAST_ACTIVE + session operational; soak timer starts at T0 only.
param(
    [ValidateSet("G3a", "G3b")]
    [string]$Tier = "G3a",
    [int]$SoakSec = 0,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [ValidateSet("M03", "M04")]
    [string]$ThirdPeer = "M03",
    [switch]$SkipBuildInstall = $true,
    [switch]$ColdStartServices,
    [switch]$PreferHeadsetPlayback
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

if ($SoakSec -le 0) {
    $SoakSec = if ($Tier -eq "G3b") { 7200 } else { 900 }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logPrefix = if ($Tier -eq "G3b") { "ga-g3b-stability-field" } else { "ga-g3a-stability-field" }
$logDir = Join-Path $repoRoot "talkback\logs\$logPrefix-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

@(
    "gate=$Tier"
    "gateFamily=G3_STABILITY"
    "executionPath=PaG2_3node_real_mesh"
    "driverMode=PaG2"
    "topology=3node_real_mesh"
    "soakSec=$SoakSec"
    "t0=multicast_active_and_session_operational"
    "blocksGa=$(if ($Tier -eq 'G3a') { 'true' } else { 'false' })"
    "blocksAdr0056Retirement=$(if ($Tier -eq 'G3b') { 'true' } else { 'false' })"
    "semantic=duration_stability_only"
    "note=soak_window_starts_at_T0_not_session_create"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "$Tier STABILITY: ${SoakSec}s on PaG2 3-node Real-Mesh (G2 FIELD-PROVEN path)"
Write-Host "T0=production multicast ACTIVE + session operational"
Write-Host "blocksGa=$(if ($Tier -eq 'G3a') { 'YES' } else { 'NO — retirement confidence only' })"

$g2Args = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "meeting-product-ga-g2-real-mesh-field-first-run.ps1"),
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-M03Serial", $M03Serial,
    "-ThirdPeer", $ThirdPeer,
    "-PilotUseSec", "$SoakSec",
    "-LogDirOverride", $logDir,
    "-SkipFinalAdjudication"
)
if ($SkipBuildInstall) { $g2Args += "-SkipBuildInstall" }
if (-not $ColdStartServices) { $g2Args += "-SkipServiceColdStart" }
if ($PreferHeadsetPlayback) { $g2Args += "-PreferHeadsetPlayback" }

& powershell @g2Args
$driverExit = $LASTEXITCODE

& (Join-Path $PSScriptRoot "meeting-product-ga-g3-stability-field-adjudicate.ps1") `
    -LogDir $logDir -MinSoakSec $SoakSec -Tier $Tier
exit $LASTEXITCODE
