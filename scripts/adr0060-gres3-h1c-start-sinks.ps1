# G-RES-3-H1c — start capacity sinks on M01 + M02 (background jobs).

param(
    [int]$DurationSec = 300
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

$devices = @{
    M01 = @{ Serial = "HTUBB21B09220661"; Ports = "47001,47002,47003,47007,47008,47009" }
    M02 = @{ Serial = "DSJ-2407041"; Ports = "47004,47005,47006" }
}
$TestRunner = "com.talkback.test/androidx.test.runner.AndroidJUnitRunner"
$SinkClass = "com.talkback.lab.capacity.Gres3CapacitySinkInstrumentedTest#holdCapacitySinks"
$adb = (Get-Command adb).Source

function Get-WlanIp([string]$Serial) {
    $ip = (& $adb -s $Serial shell "ip -4 addr show wlan0 2>/dev/null | grep inet | awk '{print `$2}' | cut -d/ -f1" 2>&1 | Out-String).Trim()
    if (-not $ip -or $ip -match "error") { throw "wlan0 IP not found for $Serial" }
    return $ip
}

function Test-UdpPortListening([string]$Serial, [int]$Port) {
    $out = (& $adb -s $Serial shell "ss -uln 2>/dev/null; netstat -an 2>/dev/null" 2>&1 | Out-String)
    return $out -match ":$Port\b"
}

$m01Ip = Get-WlanIp $devices.M01.Serial
$m02Ip = Get-WlanIp $devices.M02.Serial
Write-Host "M01 IP=$m01Ip M02 IP=$m02Ip"

$sinkJobs = @()
foreach ($name in @("M01", "M02")) {
    $cfg = $devices[$name]
    $job =
        Start-Job -Name "gres3-sink-$name" -ScriptBlock {
            param($adbPath, $serial, $ports, $duration, $runner, $class)
            & $adbPath -s $serial shell am instrument -w `
                -e class $class `
                -e ports $ports `
                -e durationSec "$duration" `
                $runner 2>&1 | Out-String
        } -ArgumentList $adb, $cfg.Serial, $cfg.Ports, $DurationSec, $TestRunner, $SinkClass
    $sinkJobs += $job
    Write-Host "Started sink job $name serial=$($cfg.Serial) ports=$($cfg.Ports)"
}

Start-Sleep -Seconds 15

$allListening = $true
foreach ($port in @(47001, 47002, 47003, 47004, 47005, 47006, 47007, 47008, 47009)) {
    $hostSerial =
        if ($port -le 47003 -or $port -ge 47007) { $devices.M01.Serial } else { $devices.M02.Serial }
    $ok = Test-UdpPortListening $hostSerial $port
    Write-Host "port $port listening=$ok"
    if (-not $ok) { $allListening = $false }
}

@(
    "m01Ip=$m01Ip"
    "m02Ip=$m02Ip"
    "sinkBindingsVerified=$allListening"
    "durationSec=$DurationSec"
) | Set-Content (Join-Path $repoRoot "config\gres3-topologies\last-discovered.env") -Encoding UTF8

if (-not $allListening) {
    throw "sink ports not all listening — abort H1c sender"
}

Write-Host "Sinks ready. Env written to config/gres3-topologies/last-discovered.env"
Write-Host "Sink jobs continue in background (DurationSec=$DurationSec)."

return @{
    M01Ip = $m01Ip
    M02Ip = $m02Ip
    SinkBindingsVerified = $allListening
    Jobs = $sinkJobs
}
