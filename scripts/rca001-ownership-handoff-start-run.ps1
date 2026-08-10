# RCA-001 ownership handoff — clear logcat, 16M buffer, start collectors
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
    $LogDir = Join-Path $repoRoot "logs\rca001-ownership-handoff-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=rca-001-ownership-handoff"
    "ssid=happy"
    "dut=M02"
    "observe=SUPERSEDED;REJECTED=0;RECEIPT→ANSWER→EDGE_RECOVERED"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name buffer=$LogBuffer"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

@'
# RCA-001 field checks (only 3)

1. SUPERSEDED: PARTICIPANT_REATTACH → HOST_RESTART
2. REJECTED existing=PARTICIPANT requested=HOST → ideal 0
3. RECEIPT → ICE → ANSWER → EDGE_RECOVERED (not ICE alone)

SSID happy · flap M02 ~5s · no leave · no mid-run retarget

Stop:
  .\scripts\rca001-ownership-handoff-stop-run.ps1 -LogDir "<LogDir>"
'@ | Set-Content (Join-Path $LogDir "TEST_STEPS.md") -Encoding UTF8

Write-Host "LogDir=$LogDir"
Write-Host "Stop: .\scripts\rca001-ownership-handoff-stop-run.ps1 -LogDir `"$LogDir`""
