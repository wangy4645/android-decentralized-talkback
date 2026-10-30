# Primary Native SRD Fault — hang capture PRE-FLIGHT (read-only)
# Run BEFORE conference. On TIER_JNI hang, run pnsrd-hang-capture-on-hang.ps1 (do NOT force-stop first).
#
# Runbook: docs/analysis/primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md

param(
    [string]$LogDir = "",
    [string]$LogBuffer = "16M"
)

$ErrorActionPreference = "Stop"
$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "DSJ-2407041"
    M03 = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\pnsrd-hang-capture-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

@(
    "case=PRIMARY_NATIVE_SRD_HANG_CAPTURE"
    "mode=READ_ONLY_PREFLIGHT"
    "package=$Package"
    "host=M01 $($devices.M01)"
    "runbook=docs/analysis/primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md"
    "lab=STOP (2PC/3PC no repro)"
    "logBuffer=$LogBuffer"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
    ""
    "ON_HANG:"
    "  .\scripts\pnsrd-hang-capture-on-hang.ps1 -LogDir `"$LogDir`""
    ""
    "AFTER_CAPTURE:"
    "  .\scripts\pnsrd-hang-capture-stop-run.ps1 -LogDir `"$LogDir`""
    ""
    "PROHIBITED: force-stop, pm clear, kill -9, SRD retry, lease force-release"
) | Set-Content (Join-Path $LogDir "CAPTURE_README.txt") -Encoding UTF8

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $state = (& $adb -s $serial get-state 2>$null | Out-String).Trim()
    $ErrorActionPreference = $prevEap
    if ($state -ne "device") {
        Write-Warning "SKIP $name ($serial) state=$state"
        continue
    }
    & $adb -s $serial logcat -G $LogBuffer | Out-Null
    & $adb -s $serial logcat -c
    Write-Host "CLEARED $name buffer=$LogBuffer"
}

$pids = @()
$m01 = $devices.M01
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
$m01State = (& $adb -s $m01 get-state 2>$null | Out-String).Trim()
$ErrorActionPreference = $prevEap
if ($m01State -eq "device") {
    $m01Out = Join-Path $LogDir "M01-full.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $m01, "logcat", "-v", "threadtime"
    ) -RedirectStandardOutput $m01Out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=M01 serial=$m01 log=$m01Out full=true"
    Write-Host "COLLECTOR M01 full logcat pid=$($proc.Id)"
}

foreach ($name in @("M02", "M03")) {
    $serial = $devices[$name]
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $peerState = (& $adb -s $serial get-state 2>$null | Out-String).Trim()
    $ErrorActionPreference = $prevEap
    if ($peerState -ne "device") { continue }
    $out = Join-Path $LogDir "$name-talkback.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "threadtime", "-s", "Talkback:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out full=false"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}

$pids | Set-Content (Join-Path $LogDir "COLLECTOR_PIDS.txt") -Encoding UTF8

Write-Host ""
Write-Host "LogDir=$LogDir"
Write-Host "Run conference (normal use). On TIER_JNI hang:"
Write-Host "  .\scripts\pnsrd-hang-capture-on-hang.ps1 -LogDir `"$LogDir`""
