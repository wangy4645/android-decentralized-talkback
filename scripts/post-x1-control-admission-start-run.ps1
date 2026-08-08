# Post-X1 directed validation — clear logcat + start collectors (M01/M02/M03)
# Usage:
#   .\scripts\post-x1-control-admission-start-run.ps1
#   .\scripts\post-x1-control-admission-start-run.ps1 -LogDir .\logs\post-x1-directed-YYYYMMDD-HHMMSS

param(
    [string]$LogDir = "",
    [switch]$InstallApk,
    [string]$ApkPath = ".\talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
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
    $LogDir = Join-Path $repoRoot "logs\post-x1-directed-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if ($InstallApk) {
    $apkFull = Join-Path $repoRoot $ApkPath
    if (-not (Test-Path $apkFull)) {
        throw "APK not found: $apkFull (run gradlew :talkback-app:assembleDebug on feat/x1-control-admission)"
    }
    foreach ($name in @("M01", "M02", "M03")) {
        $serial = $devices[$name]
        Write-Host "Installing $name ($serial)..."
        & $adb -s $serial install -r $apkFull
    }
}

$pids = @()
foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial logcat -c
    Write-Host "CLEARED logcat $name ($serial)"
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial"
    Write-Host "COLLECTOR $name -> $out (pid=$($proc.Id))"
}

$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8
@(
    "case=post-x1-directed-validation"
    "branch=feat/x1-control-admission"
    "pr=126"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

Write-Host ""
Write-Host "LogDir=$LogDir"
Write-Host "Procedure: 3-party stable (no USER_LEAVE) -> M03 WiFi OFF 15-30s -> ON -> soak >= 5 min"
