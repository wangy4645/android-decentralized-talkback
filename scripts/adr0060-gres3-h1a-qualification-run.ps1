# G-RES-3-H1a qualification run (NOT qualifying evidence).
# Validates harness, thermal preflight, topology, and evidence bundle integrity.

param(
    [ValidateSet("M01", "M02", "M03")]
    [string]$Device = "M03",
    [string]$DeviceLabel = "",
    [string]$AppBuildSha = "",
    [string]$BaseIp = "127.0.0.1",
    [int]$BasePort = 47001,
    [string]$RunId = "",
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path $PSScriptRoot -Parent
Set-Location $repoRoot

$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "DSJ-2407041"
    "M03" = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$TestPackage = "com.talkback.test"
$TestRunner = "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
$HarnessClass = "com.talkback.lab.capacity.Gres3CapacityHarnessInstrumentedTest#gres3CapacityQualificationRun"
$RemoteBundleRoots = @(
    "/sdcard/Android/data/$TestPackage/files/gres3-capacity",
    "/sdcard/Android/data/$Package/files/gres3-capacity"
)

$serial = $devices[$Device]
if ([string]::IsNullOrWhiteSpace($DeviceLabel)) {
    $DeviceLabel = $Device
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "gres3-h1a-qual-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$adb = (Get-Command adb).Source
$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }
if ([string]::IsNullOrWhiteSpace($AppBuildSha)) {
    $AppBuildSha = $head
}

$logDir = Join-Path $repoRoot "logs/gres3-h1a-qual-$RunId"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-DeviceOnline([string]$Name, [string]$DeviceSerial) {
    $state = (& $adb -s $DeviceSerial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") {
        throw "OFFLINE $Name ($DeviceSerial) state=$state — connect device before qualification"
    }
}

@(
    "case=G-RES-3-H1a_QUALIFICATION"
    "runClass=QUALIFICATION"
    "device=$Device"
    "serial=$serial"
    "runId=$RunId"
    "build=$head"
    "appBuildSha=$AppBuildSha"
    "baseIp=$BaseIp"
    "basePort=$BasePort"
    "topologyNote=loopback harness validation only; NOT formal capacity topology"
    "formalCapacityEvidence=NOT_STARTED"
    "LogDir=$logDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Test-DeviceOnline $Device $serial

if (-not $SkipBuild) {
    Write-Host "BUILD talkback-app + androidTest..."
    & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed exit=$LASTEXITCODE" }
}

$appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"
if (-not (Test-Path $appApk)) { throw "Missing APK: $appApk" }
if (-not (Test-Path $testApk)) { throw "Missing test APK: $testApk" }

if (-not $SkipInstall) {
    & $adb -s $serial install -r $appApk | Out-Host
    & $adb -s $serial install -r $testApk | Out-Host
    Write-Host "INSTALLED $Device"
}

& $adb -s $serial shell am force-stop $Package
& $adb -s $serial shell am force-stop $TestPackage
& $adb -s $serial logcat -c

Write-Host "G-RES-3-H1a qualification runId=$RunId device=$DeviceLabel serial=$serial"
$instrumentOut = & $adb -s $serial shell am instrument -w -r `
    -e class $HarnessClass `
    -e runId $RunId `
    -e deviceLabel $DeviceLabel `
    -e appBuildSha $AppBuildSha `
    -e baseIp $BaseIp `
    -e basePort "$BasePort" `
    $TestRunner 2>&1 | Out-String

Set-Content -Path (Join-Path $logDir "instrument.txt") -Value $instrumentOut -Encoding UTF8
Write-Host $instrumentOut

if ($instrumentOut -notmatch "OK \(") {
    throw "qualification instrumentation failed — see $logDir\instrument.txt"
}

Write-Host "Pulling evidence bundle..."
$localBundle = Join-Path $logDir "bundle"
New-Item -ItemType Directory -Force -Path $localBundle | Out-Null
$pulled = $false
foreach ($remoteRoot in $RemoteBundleRoots) {
    $remotePath = "$remoteRoot/$RunId"
    $tmp = Join-Path $localBundle "_pull_tmp"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $pullOut = & $adb -s $serial pull $remotePath $tmp 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $tmp $RunId "manifest.json"))) {
        Copy-Item -Path (Join-Path $tmp $RunId "*") -Destination $localBundle -Recurse -Force
        $pulled = $true
        Write-Host "Pulled from $remotePath"
        break
    }
    if ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $tmp "manifest.json"))) {
        Copy-Item -Path (Join-Path $tmp "*") -Destination $localBundle -Recurse -Force
        $pulled = $true
        Write-Host "Pulled from $remotePath"
        break
    }
}
if (-not $pulled) {
    throw "evidence bundle not found under test or app package paths for runId=$RunId"
}

Write-Host "Pulling logcat..."
& $adb -s $serial logcat -d -s GRES3_CAPACITY_H1:* > (Join-Path $logDir "logcat-gres3.txt")

@(
    "finished=$(Get-Date -Format o)"
    "bundleLocal=$localBundle"
) | Add-Content (Join-Path $logDir "RUN_META.txt") -Encoding UTF8

Write-Host "Qualification complete. Bundle: $localBundle"
