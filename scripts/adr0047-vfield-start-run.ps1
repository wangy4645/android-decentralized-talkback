# ADR-0047 V-field' — clear logcat, set 16M buffer, start collectors
# Usage:
#   .\scripts\adr0047-vfield-start-run.ps1
#   .\scripts\adr0047-vfield-start-run.ps1 -LogDir .\logs\adr0047-vfield-YYYYMMDD-HHMMSS

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

$adb = $null
foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "C:\Users\happy\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
    )) {
    if (Test-Path $c) { $adb = $c; break }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) { throw "adb not found" }

$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\adr0047-vfield-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$meta = @(
    "case=adr0047-vfield-passive-observation"
    "baseline=main-06e9397+"
    "merged=PR148-434313c"
    "apk=talkback-app-debug"
    "ssid=happy"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
)
$meta | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    $buf = (& $adb -s $serial logcat -g 2>&1 | Out-String).Trim()
    Write-Host "BUFFER $name ($serial): $buf"
    & $adb -s $serial logcat -c
    Write-Host "CLEARED logcat $name ($serial)"
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name -> $out (pid=$($proc.Id))"
}

$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8
Write-Host ""
Write-Host "LogDir=$LogDir"
Write-Host "Follow: docs/analysis/adr0047-vfield-run-card-001.md"
Write-Host "Stop:  .\scripts\adr0047-vfield-stop-run.ps1 -LogDir `"$LogDir`""
