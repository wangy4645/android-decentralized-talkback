# Join Stability M02 — clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\join-stability-m02-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=JOIN_STABILITY_M02_ENTER_MEETING"
    "track=Session_Churn_Join_Stability"
    "ssid=happy"
    "dut=M02"
    "observe=GROUP_INVITE;GROUP_JOIN;CONFERENCE_REJOIN;GROUP_BUSY;RECONNECT_ACCEPT;RESYNC;roster;epoch"
    "notScore=EDGE_RECOVERED;R2a;LEASE;UVCP;REMOTE_INGRESS_ABSENT"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") {
        Write-Warning "SKIP $name ($serial) state=$state"
        continue
    }
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name buffer=$LogBuffer"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $state = & $adb -s $serial get-state 2>$null
    if ($state -ne "device") { continue }
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

Copy-Item (Join-Path $repoRoot "docs\analysis\join-stability-m02-observation-run-card.md") `
    (Join-Path $LogDir "TEST_STEPS.md") -ErrorAction SilentlyContinue

Write-Host "LogDir=$LogDir"
Write-Host "Card: docs\analysis\join-stability-m02-observation-run-card.md"
Write-Host "Stop: .\scripts\join-stability-m02-stop-run.ps1 -LogDir `"$LogDir`""
