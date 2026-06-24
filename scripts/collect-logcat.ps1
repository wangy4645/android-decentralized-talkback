# Talkback 现场 logcat 采集脚本（Windows PowerShell）
# 用法:
#   .\scripts\collect-logcat.ps1
#   .\scripts\collect-logcat.ps1 -DeviceId emulator-5554 -DurationSec 600 -OutDir .\test-logs

param(
    [string]$DeviceId = "",
    [int]$DurationSec = 0,
    [string]$OutDir = ".\test-logs",
    [string]$TagFilter = "talkback|Coordinator|Group|Floor|ICE|Call|Mesh|HELLO|redial"
)

$adb = $null
$candidates = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "adb"
)
foreach ($c in $candidates) {
    if ($c -eq "adb") {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $adb = $cmd.Source; break }
    } elseif (Test-Path $c) {
        $adb = $c
        break
    }
}

if (-not $adb) {
    Write-Error "adb not found. Install Android SDK platform-tools or set ANDROID_HOME."
    exit 1
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outFile = Join-Path $OutDir "talkback-logcat-$timestamp.txt"

$deviceArg = @()
if ($DeviceId) { $deviceArg = @("-s", $DeviceId) }

Write-Host "Using adb: $adb"
Write-Host "Output:  $outFile"
Write-Host "Filter:  $TagFilter"

& $adb @deviceArg logcat -c
Write-Host "logcat cleared. Collecting..."

if ($DurationSec -gt 0) {
    $job = Start-Job -ScriptBlock {
        param($adbPath, $deviceArgs, $pattern, $path)
        & $adbPath @deviceArgs logcat -v time | Select-String -Pattern $pattern | Tee-Object -FilePath $path
    } -ArgumentList $adb, $deviceArg, $TagFilter, $outFile
    Start-Sleep -Seconds $DurationSec
    Stop-Job $job -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    Write-Host "Collected $DurationSec seconds."
} else {
    Write-Host "Press Ctrl+C to stop collection."
    & $adb @deviceArg logcat -v time | Select-String -Pattern $TagFilter | Tee-Object -FilePath $outFile
}

Write-Host "Saved to $outFile"
