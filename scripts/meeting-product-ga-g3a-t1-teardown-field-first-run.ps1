# G3a-T1 — short teardown closure verify on PaG2 3-node path (1–3 min soak; no 900s re-run).
param(
    [int]$SoakSec = 120,
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [string]$M03Serial = "MDX0220416001963",
    [ValidateSet("M03", "M04")]
    [string]$ThirdPeer = "M03",
    [int]$MinSoakSec = 60,
    [string]$SteadyStateEvidenceDir = "talkback/logs/ga-g2-real-mesh-field-20260914-175047",
    [switch]$SkipBuildInstall = $true,
    [switch]$ColdStartServices,
    [switch]$PreferHeadsetPlayback
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

if ($SoakSec -lt $MinSoakSec) { $SoakSec = $MinSoakSec }

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $repoRoot "talkback\logs\ga-g3a-t1-teardown-field-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

@(
    "gate=G3a-T1"
    "gateFamily=G3A_TEARDOWN_CLOSURE"
    "executionPath=PaG2_3node_real_mesh"
    "driverMode=PaG2"
    "topology=3node_real_mesh"
    "soakSec=$SoakSec"
    "minSoakSec=$MinSoakSec"
    "steadyStateEvidenceDir=$SteadyStateEvidenceDir"
    "t0=multicast_active_and_session_operational"
    "semantic=teardown_closure_only"
    "note=combines_with_175047_steady_state_on_PASS"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "G3a-T1 TEARDOWN: ${SoakSec}s short soak on PaG2 3-node (teardown closure only)"
Write-Host "steady-state evidence=$SteadyStateEvidenceDir (1073s; no re-run)"

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

& (Join-Path $PSScriptRoot "meeting-product-ga-g3a-t1-teardown-field-adjudicate.ps1") `
    -LogDir $logDir -MinSoakSec $MinSoakSec -SteadyStateEvidenceDir $SteadyStateEvidenceDir
exit $LASTEXITCODE
