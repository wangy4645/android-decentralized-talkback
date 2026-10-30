# G-RES-3-H1c formal topology qualification (NOT formal 3-run evidence).

param(
    [ValidateSet("M01", "M02", "M03")]
    [string]$Sender = "M03",
    [ValidateSet("H1c", "H1d")]
    [string]$HarnessPhase = "H1c",
    [ValidateSet("E0", "E1", "E3", "E3b", "SHARED_SOCKET_SEQUENTIAL", "PER_TARGET_SOCKET_SEQUENTIAL", "SENDMMSG_BATCH", "SENDMMSG_BATCH_NONBLOCKING")]
    [string]$ExecutionModel = "E0",
    [string]$RunId = "",
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

$serial = $devices[$Sender]
$executionModelArg = switch ($ExecutionModel) {
    "E3b" { "SENDMMSG_BATCH_NONBLOCKING" }
    "E3" { "SENDMMSG_BATCH" }
    "E1" { "PER_TARGET_SOCKET_SEQUENTIAL" }
    "E0" { "SHARED_SOCKET_SEQUENTIAL" }
    default { $ExecutionModel }
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "gres3-$($HarnessPhase.ToLower())-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
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
    $sinkResult = & (Join-Path $PSScriptRoot "adr0060-gres3-h1c-start-sinks.ps1") -DurationSec 300
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
    "case=G-RES-3-$HarnessPhase`_FORMAL_TOPOLOGY_QUALIFICATION"
    "harnessPhase=$HarnessPhase"
    "executionModel=$executionModelArg"
    "runClass=QUALIFICATION"
    "sender=$Sender"
    "serial=$serial"
    "runId=$RunId"
    "build=$head"
    "m01Ip=$m01Ip"
    "m02Ip=$m02Ip"
    "sinkBindingsVerified=$sinkBindingsVerified"
    "topologyId=gres3-h1c-lab-m03-sender-v1"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

$atraceRemote = "/sdcard/gres3-atrace-$RunId.out"
$atraceStarted = $false
if ($HarnessPhase -eq "H1d") {
    Write-Host "ATRACE_START sched/freq/idle/workq"
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $serial shell atrace --async_start -c -b 16384 sched freq idle workq 2>&1 | Out-Null
    $ErrorActionPreference = $prevEap
    $atraceStarted = $true
}

$instrumentOut = & $adb -s $serial shell am instrument -w -r `
    -e class $HarnessClass `
    -e runId $RunId `
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
    throw "H1c instrumentation failed"
}

if ($atraceStarted) {
    Write-Host "ATRACE_STOP pulling trace"
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $serial shell atrace --async_stop -z -o $atraceRemote 2>&1 | Out-Null
    & $adb -s $serial pull $atraceRemote (Join-Path $logDir "atrace-sched.out") 2>&1 | Out-Null
    $ErrorActionPreference = $prevEap
}

$localBundle = Join-Path $logDir "bundle"
New-Item -ItemType Directory -Force -Path $localBundle | Out-Null

function Test-Gres3PulledBundle([string]$BundlePath) {
    if (Test-Path (Join-Path $BundlePath "h1c-adjudication.json")) {
        return $true
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
        } catch {
            # fall through to h1d5a lifecycle check
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
    return $false
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
    if (Test-Gres3PulledBundle $nestedBundle) {
        Copy-Item -Path (Join-Path $nestedBundle "*") -Destination $localBundle -Recurse -Force
        $pulled = $true
        break
    }
    if (Test-Gres3PulledBundle $tmp) {
        Copy-Item -Path (Join-Path $tmp "*") -Destination $localBundle -Recurse -Force
        $pulled = $true
        break
    }
}
if (-not $pulled) { throw "bundle pull failed for runId=$RunId" }

& $adb -s $serial logcat -d -s GRES3_CAPACITY_H1:* > (Join-Path $logDir "logcat-gres3.txt")
& (Join-Path $PSScriptRoot "adr0060-gres3-h1c-adjudicate.ps1") -LogDir $logDir
