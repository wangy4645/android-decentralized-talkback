# #181-B directed field gate — log collectors
param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M"
)

$ErrorActionPreference = "Stop"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "2d73067a"
    "M03" = "MDX0220416001963"
}
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\t9c-181b-directed-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

@(
    "case=181B_DIRECTED_DOMAIN_GATE"
    "issue=#181"
    "gate=domain_selection_not_operational"
    "topology=M01,M02 then +M03"
    "window_s=120_after_m03"
    "build=$head"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    if ((& $adb -s $serial get-state 2>$null) -ne "device") { Write-Warning "SKIP $name"; continue }
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    if ((& $adb -s $serial get-state 2>$null) -ne "device") { continue }
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8
Write-Host "LogDir=$LogDir"
