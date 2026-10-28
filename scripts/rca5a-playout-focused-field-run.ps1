# RCA5-A focused field — M01 host + M02 join → shadow ingress/playout → RC1 cutover → production soak.
# Does NOT gate on M03 TX (RCA4). Use after manual service start on all nodes.
param(
    [string]$M01Serial = "HTUBB21B09220661",
    [string]$M02Serial = "DSJ-2407041",
    [int]$PilotUseSec = 60,
    [int]$ShadowObservationSec = 45,
    [switch]$SkipServiceColdStart = $true,
    [switch]$SkipBuildInstall = $true
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logDir = Join-Path $repoRoot "talkback\logs\rca5a-playout-focused-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$buildSha = ""
Push-Location (Join-Path $repoRoot "talkback")
try { $buildSha = (git rev-parse --short HEAD 2>$null).Trim() } catch {}
Pop-Location

@(
    "gate=RCA5A_PLAYOUT_FOCUSED"
    "semantic=M01_multicast_to_M02_audible"
    "buildSha=$buildSha"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
    "skipServiceColdStart=$SkipServiceColdStart"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "RCA5-A PLAYOUT FOCUSED FIELD"
Write-Host "logDir=$logDir"
Write-Host "buildSha=$buildSha"
Write-Host "driver=PaPilot1 (M01+M02 only; no M03 TX gate)"
if ($SkipServiceColdStart) {
    Write-Host "NOTE: services must already be running on M01 and M02 (manual start OK)"
}

$driverScript = Join-Path $PSScriptRoot "p1c-package-field-first-run.ps1"
$driverArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $driverScript,
    "-DriverMode", "PaPilot1",
    "-M01Serial", $M01Serial,
    "-M02Serial", $M02Serial,
    "-PilotUseSec", "$PilotUseSec",
    "-ShadowObservationSec", "$ShadowObservationSec",
    "-Rc1PostCutoverSec", "20"
)
if ($SkipBuildInstall) { $driverArgs += "-SkipBuildInstall" }
if ($SkipServiceColdStart) { $driverArgs += "-SkipServiceColdStart" }

& powershell @driverArgs
$driverExit = $LASTEXITCODE

$driverLog = Get-ChildItem -Path (Join-Path $repoRoot "talkback\logs") -Filter "controlled-pilot-run1-field-*" -Directory -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $driverLog) {
    $driverLog = Get-ChildItem -Path (Join-Path $repoRoot "talkback\logs") -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "pilot|PaPilot|rca5" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
if ($driverLog -and $driverLog.FullName -ne $logDir) {
    Copy-Item -Path (Join-Path $driverLog.FullName "*") -Destination $logDir -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -Force (Join-Path $logDir "RUN_META.txt") (Join-Path $driverLog.FullName "RUN_META.txt") -ErrorAction SilentlyContinue
    $logDir = $driverLog.FullName
}

& (Join-Path $PSScriptRoot "rca5a-playout-focused-adjudicate.ps1") -LogDir $logDir
exit $LASTEXITCODE
