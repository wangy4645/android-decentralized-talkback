# Phase 1 field device map + wireless ADB prep (M02/M04 shared USB)
param(
    [switch]$InstallOnly,
    [switch]$WirelessOnly,
    [string[]]$Devices = @("M01", "M02", "M03", "M04")
)

$ErrorActionPreference = "Stop"
# Allow -Devices M01,M03,M04 from command line
if ($Devices.Count -eq 1 -and $Devices[0] -match ",") {
    $Devices = $Devices[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}
$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source

# Field map — M04 confirmed 2026-09-04; M02/M04 share one USB cable (use wireless for concurrent runs).
$deviceMap = @{
    "M01" = @{ Serial = "HTUBB21B09220661"; Source = "S1" }
    "M02" = @{ Serial = "DSJ-2407041"; Source = "S2" }
    "M03" = @{ Serial = "MDX0220416001963"; Source = "S3" }
    "M04" = @{ Serial = "DSJ-2407011"; Source = $null }
}

$Package = "com.talkback.appprod"
$TestPackage = "com.talkback.test"
$appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"
$wirelessFile = Join-Path $repoRoot "logs\field-device-wireless.json"

function Get-WlanIp([string]$Serial) {
    $raw = & $adb -s $Serial shell ip -4 addr show wlan0 2>$null
    if (-not $raw) { return $null }
    if ($raw -is [array]) { $raw = $raw -join "`n" }
    if ($raw -match "inet\s+(\d+\.\d+\.\d+\.\d+)") { return $Matches[1] }
    return $null
}

function Enable-WirelessAdb([string]$Name, [string]$Serial) {
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") {
        Write-Warning "SKIP wireless $Name ($Serial): not connected via USB (state=$state)"
        return $null
    }
    $ip = Get-WlanIp $Serial
    if (-not $ip) {
        Write-Warning "SKIP wireless $Name ($Serial): wlan0 IP not found"
        return $null
    }
    & $adb -s $Serial tcpip 5555 | Out-Null
    Start-Sleep -Seconds 2
    $target = "${ip}:5555"
    & $adb connect $target 2>&1 | Out-Host
    Start-Sleep -Seconds 1
    $ping = try { (& $adb -s $target get-state 2>&1 | Out-String).Trim() } catch { "offline" }
    if ($ping -ne "device") {
        Write-Warning "Wireless connect failed for $Name at $target (state=$ping) — use USB-swap launch for Slice 5"
        return $null
    }
    Write-Host "WIRELESS $Name USB=$Serial -> $target"
    return @{ name = $Name; usbSerial = $Serial; wireless = $target; ip = $ip }
}

function Install-Device([string]$Name, [string]$Serial) {
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") {
        Write-Warning "SKIP install $Name ($Serial): not connected (state=$state)"
        return $false
    }
    & $adb -s $Serial install -r $appApk | Out-Host
    & $adb -s $Serial install -r $testApk | Out-Host
    Write-Host "INSTALLED $Name ($Serial)"
    return $true
}

function Resolve-Serial([string]$Name) {
    $entry = $deviceMap[$Name]
    if (-not $entry) { throw "unknown device $Name" }
    $usb = $entry.Serial
    if (Test-Path $wirelessFile) {
        $json = Get-Content $wirelessFile -Raw | ConvertFrom-Json
        $w = $json.$Name
        if ($w -and $w.wireless) {
            $ws = (& $adb -s $w.wireless get-state 2>&1 | Out-String).Trim()
            if ($ws -eq "device") { return $w.wireless }
        }
    }
    return $usb
}

Write-Host "=== Field device map ==="
foreach ($name in @("M01", "M02", "M03", "M04")) {
    $s = $deviceMap[$name].Serial
    $st = try { (& $adb -s $s get-state 2>&1 | Out-String).Trim() } catch { "offline" }
    Write-Host "$name serial=$s usbState=$st"
}

if (-not $WirelessOnly) {
    if (-not (Test-Path $appApk)) {
        Write-Host "Building APKs..."
        Push-Location $repoRoot
        try {
            & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
            if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
        } finally { Pop-Location }
    }
    foreach ($name in $Devices) {
        Install-Device $name $deviceMap[$name].Serial
    }
}

if (-not $InstallOnly) {
    $wireless = @{}
    foreach ($name in $Devices) {
        $r = Enable-WirelessAdb $name $deviceMap[$name].Serial
        if ($r) { $wireless[$name] = $r }
    }
    if ($wireless.Count -gt 0) {
        New-Item -ItemType Directory -Force -Path (Split-Path $wirelessFile -Parent) | Out-Null
        ($wireless | ConvertTo-Json -Depth 4) | Set-Content $wirelessFile -Encoding UTF8
        Write-Host "Saved wireless map: $wirelessFile"
    }
}

Write-Host ""
Write-Host "=== Ops note (M02/M04 shared USB) ==="
Write-Host "1. Install/wireless: connect M02 OR M04 via USB, run this script for that device only:"
Write-Host "   .\scripts\adr0060-phase1-field-device-prep.ps1 -Devices M02"
Write-Host "   .\scripts\adr0060-phase1-field-device-prep.ps1 -Devices M04"
Write-Host "2. Slice 5 run uses wireless endpoints from logs\field-device-wireless.json when USB offline."
Write-Host "3. Slice 5 without wireless (M02/M04 shared USB):"
Write-Host "   a) M04 USB -> start receiver (runs on device after disconnect)"
Write-Host "   b) M01+M03 USB -> start senders after 3s lead"
Write-Host "   c) Swap USB to M02 within lead window -> start S2 sender"
Write-Host "   Probe continues on all phones without USB attached."
