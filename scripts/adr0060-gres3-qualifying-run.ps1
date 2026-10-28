# G-RES-3 C4 formal qualifying run (600s measurement @ N=9).
# Requires E3 SENDMMSG_BATCH execution model for current contract path.

param(
    [ValidateSet("M01", "M02", "M03")]
    [string]$Sender = "M03",
    [ValidateSet("E3", "SENDMMSG_BATCH")]
    [string]$ExecutionModel = "E3",
    [string]$RunId = "",
    [int]$SinkDurationSec = 900,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipSinks
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

$devices = @{
    M01 = "HTUBB21B09220661"
    M02 = "DSJ-2407041"
    M03 = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$TestPackage = "com.talkback.test"
$TestRunner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$HarnessClass = "com.talkback.lab.capacity.Gres3H1cCapacityHarnessInstrumentedTest#gres3H1cFormalTopologyQualification"
$RemoteBundleRoots = @(
    "/sdcard/Android/data/$TestPackage/files/gres3-capacity",
    "/sdcard/Android/data/$Package/files/gres3-capacity"
)
$RunClass = "QUALIFYING"
$HarnessPhase = "H1c"

$serial = $devices[$Sender]
$executionModelArg = switch ($ExecutionModel) {
    "E3" { "SENDMMSG_BATCH" }
    default { $ExecutionModel }
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "gres3-qualifying-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$adb = (Get-Command adb).Source
$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

$logDir = Join-Path $repoRoot "logs/$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-DeviceOnline([string]$Name, [string]$DeviceSerial) {
    $state = (& $adb -s $DeviceSerial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") {
        throw "OFFLINE $Name ($DeviceSerial)"
    }
}

Test-DeviceOnline $Sender $serial
Test-DeviceOnline M01 $devices.M01
Test-DeviceOnline M02 $devices.M02

if (-not $SkipBuild) {
    & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
}

$appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"

if (-not $SkipInstall) {
    foreach ($name in @("M01", "M02", "M03")) {
        $s = $devices[$name]
        & $adb -s $s install -r $appApk | Out-Host
        & $adb -s $s install -r $testApk | Out-Host
        Write-Host "INSTALLED $name"
    }
}

if (-not $SkipSinks) {
    if ($SinkDurationSec -lt 900) {
        throw "SinkDurationSec must be >= 900 for QUALIFYING lifecycle"
    }
    $sinkResult = & (Join-Path $PSScriptRoot "adr0060-gres3-h1c-start-sinks.ps1") -DurationSec $SinkDurationSec
    $m01Ip = $sinkResult.M01Ip
    $m02Ip = $sinkResult.M02Ip
    $sinkBindingsVerified = $sinkResult.SinkBindingsVerified
} else {
    $envFile = Join-Path $repoRoot "config\gres3-topologies\last-discovered.env"
    if (-not (Test-Path $envFile)) { throw "last-discovered.env missing; run start-sinks first" }
    $vars = @{}
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { $vars[$Matches[1]] = $Matches[2] }
    }
    $m01Ip = $vars["m01Ip"]
    $m02Ip = $vars["m02Ip"]
    $sinkBindingsVerified = $vars["sinkBindingsVerified"]
}

& $adb -s $serial shell am force-stop $Package
& $adb -s $serial shell am force-stop $TestPackage
& $adb -s $serial logcat -c

@(
    "case=G-RES-3-C4_QUALIFYING"
    "harnessPhase=$HarnessPhase"
    "executionModel=$executionModelArg"
    "runClass=$RunClass"
    "sender=$Sender"
    "serial=$serial"
    "runId=$RunId"
    "build=$head"
    "m01Ip=$m01Ip"
    "m02Ip=$m02Ip"
    "sinkBindingsVerified=$sinkBindingsVerified"
    "sinkDurationSec=$SinkDurationSec"
    "topologyId=gres3-h1c-lab-m03-sender-v1"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

$instrumentOut = & $adb -s $serial shell am instrument -w -r `
    -e class $HarnessClass `
    -e runId $RunId `
    -e runClass $RunClass `
    -e deviceLabel $Sender `
    -e appBuildSha $head `
    -e m01Ip $m01Ip `
    -e m02Ip $m02Ip `
    -e senderSerial $serial `
    -e sinkBindingsVerified "$sinkBindingsVerified" `
    -e harnessPhase $HarnessPhase `
    -e executionModel $executionModelArg `
    $TestRunner 2>&1 | Out-String

Set-Content -Path (Join-Path $logDir "instrument.txt") -Value $instrumentOut -Encoding UTF8
Write-Host $instrumentOut
if ($instrumentOut -notmatch "OK \(") {
    throw "QUALIFYING instrumentation failed"
}

$localBundle = Join-Path $logDir "bundle"
New-Item -ItemType Directory -Force -Path $localBundle | Out-Null

function Test-Gres3QualifyingPulledBundle([string]$BundlePath) {
    if (-not (Test-Path (Join-Path $BundlePath "c4-adjudication.json"))) {
        return $false
    }
    $h1d5aPath = Join-Path $BundlePath "h1d5a-adjudication.json"
    if (-not (Test-Path $h1d5aPath)) {
        return $false
    }
    $summaryPath = Join-Path $BundlePath "summary.json"
    if (Test-Path $summaryPath) {
        try {
            $summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
            if ($summary.measurementAborted -eq $true) {
                return $true
            }
            if ($summary.runClass -ne "QUALIFYING") {
                return $false
            }
        } catch {
            return $false
        }
    }
    try {
        $h1d5a = Get-Content $h1d5aPath -Raw | ConvertFrom-Json
        if ($h1d5a.lifecycleState -eq "ABORTED_BEFORE_MEASUREMENT") {
            return $true
        }
    } catch {
        return $false
    }
    return $true
}

$pulled = $false
foreach ($remoteRoot in $RemoteBundleRoots) {
    $remotePath = "$remoteRoot/$RunId"
    $tmp = Join-Path $localBundle "_pull_tmp"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $serial pull $remotePath $tmp 2>&1 | Out-Null
    $ErrorActionPreference = $prevEap
    $nestedBundle = Join-Path $tmp $RunId
    if (Test-Gres3QualifyingPulledBundle $nestedBundle) {
        Copy-Item -Path (Join-Path $nestedBundle "*") -Destination $localBundle -Recurse -Force
        $pulled = $true
        break
    }
    if (Test-Gres3QualifyingPulledBundle $tmp) {
        Copy-Item -Path (Join-Path $tmp "*") -Destination $localBundle -Recurse -Force
        $pulled = $true
        break
    }
}
if (-not $pulled) { throw "bundle pull failed for runId=$RunId" }

& $adb -s $serial logcat -d -s GRES3_CAPACITY_H1:* > (Join-Path $logDir "logcat-gres3.txt")
& (Join-Path $PSScriptRoot "adr0060-gres3-qualifying-adjudicate.ps1") -LogDir $logDir

Write-Host "QUALIFYING_RUN_COMPLETE LogDir=$logDir"
