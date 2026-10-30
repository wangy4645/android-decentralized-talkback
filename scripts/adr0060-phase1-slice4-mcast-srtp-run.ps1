# Phase 1 Slice 4 — M01→M03 dual-node multicast SRTP → Opus → AudioTrack
param(
    [ValidateSet("M01toM03", "M03toM01", "M01toM02", "M02toM01")]
    [string]$Direction = "M01toM03",
    [int]$SenderDurationSec = 8,
    [int]$ReceiverLeadSec = 3,
    [int]$ReceiverTailSec = 3,
    [string]$Multicast = "239.255.42.99",
    [int]$Port = 47099,
    [string]$Iface = "wlan0",
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Continue"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "DSJ-2407041"
    "M03" = "MDX0220416001963"
}
$Package = "com.talkback.appprod"
$TestPackage = "com.talkback.test"
$TestRunner = "com.talkback.test/androidx.test.runner.AndroidJUnitRunner"
$ProbeClass = "com.talkback.lab.conference.Slice4MulticastNetworkInstrumentedTest#slice4MulticastSrtpNetwork"
$RemoteDirs = @(
    "/sdcard/Android/data/$TestPackage/files/slice4-mcast-srtp",
    "/sdcard/Android/data/$Package/files/slice4-mcast-srtp"
)

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = "slice4-mcast-srtp-$stamp"
$LogDir = Join-Path $repoRoot "logs\$runId"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LogDir "device-reports") | Out-Null

$receiverDurationSec = $ReceiverLeadSec + $SenderDurationSec + $ReceiverTailSec
$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

if ($Direction -match "^(M0[123])to(M0[123])$") {
    $senderName = $Matches[1]; $senderSerial = $devices[$senderName]
    $receiverName = $Matches[2]; $receiverSerial = $devices[$receiverName]
} else {
    throw "Invalid direction: $Direction"
}

@(
    "case=PHASE1_SLICE4_MULTICAST_SRTP_NETWORK"
    "direction=$Direction"
    "build=$head"
    "runId=$runId"
    "sender=$senderName"
    "receiver=$receiverName"
    "senderDurationSec=$SenderDurationSec"
    "receiverDurationSec=$receiverDurationSec"
    "multicast=$Multicast`:$Port"
    "iface=$Iface"
    "gates=networkPackets,srtpIngress,decodeMix,audioTrackWrites"
    "performanceGates=NONE"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

function Test-DeviceOnline([string]$Name, [string]$Serial) {
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") { throw "OFFLINE $Name ($Serial) state=$state" }
}

function Invoke-Slice4 {
    param([string]$Name, [string]$Serial, [string]$Role, [int]$DurationSec)
    $args = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", $Role,
        "-e", "runId", $runId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", $Multicast,
        "-e", "port", "$Port",
        "-e", "iface", $Iface,
        "-e", "deviceLabel", $Name,
        $TestRunner
    )
    Write-Host "SLICE4 $Name role=$Role durationSec=$DurationSec"
    $out = & $adb @args 2>&1 | Out-String
    Set-Content -Path (Join-Path $LogDir "$Name-instrument.txt") -Value $out -Encoding UTF8
    if ($out -notmatch "OK \(") {
        Write-Warning "Instrument may have failed on $Name — see $Name-instrument.txt"
    }
    return $out
}

function Pull-Report([string]$Name, [string]$Serial) {
    $localDir = Join-Path $LogDir "device-reports"
    foreach ($remote in $RemoteDirs) {
        $tmp = Join-Path $localDir "_pull_$Name"
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        $null = & $adb -s $Serial pull $remote $tmp 2>&1
        if ($LASTEXITCODE -ne 0) { continue }
        $match = Get-ChildItem -Path $tmp -Recurse -Filter "$runId-*.json" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($match) {
            $role = if ($match.Name -match "-(sender|receiver)\.json$") { $Matches[1] } else { "unknown" }
            Copy-Item $match.FullName (Join-Path $localDir "$Name-$role.json") -Force
            return (Join-Path $localDir "$Name-$role.json")
        }
    }
    return $null
}

Test-DeviceOnline $senderName $senderSerial
Test-DeviceOnline $receiverName $receiverSerial

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
    } finally { Pop-Location }
}

$appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"

if (-not $SkipInstall) {
    foreach ($name in @($senderName, $receiverName) | Sort-Object -Unique) {
        $serial = $devices[$name]
        & $adb -s $serial install -r $appApk | Out-Host
        & $adb -s $serial install -r $testApk | Out-Host
        Write-Host "INSTALLED $name"
    }
}

foreach ($name in @($senderName, $receiverName) | Sort-Object -Unique) {
    $serial = $devices[$name]
    & $adb -s $serial shell am force-stop $Package
    & $adb -s $serial shell am force-stop $TestPackage
    & $adb -s $serial logcat -c
}

# Start receiver first, then sender after lead
$receiverJob = Start-Job -ScriptBlock {
    param($adb, $Name, $Serial, $ProbeClass, $TestRunner, $runId, $DurationSec, $Multicast, $Port, $Iface, $LogDir)
    $args = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", "receiver",
        "-e", "runId", $runId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", $Multicast,
        "-e", "port", "$Port",
        "-e", "iface", $Iface,
        "-e", "deviceLabel", $Name,
        $TestRunner
    )
    $out = & $adb @args 2>&1 | Out-String
    Set-Content -Path (Join-Path $LogDir "$Name-instrument.txt") -Value $out -Encoding UTF8
    return $out
} -ArgumentList $adb, $receiverName, $receiverSerial, $ProbeClass, $TestRunner, $runId, $receiverDurationSec, $Multicast, $Port, $Iface, $LogDir

Write-Host "RECEIVER started; waiting lead ${ReceiverLeadSec}s"
Start-Sleep -Seconds $ReceiverLeadSec

$senderOut = Invoke-Slice4 -Name $senderName -Serial $senderSerial -Role "sender" -DurationSec $SenderDurationSec
Write-Host "SENDER done; waiting receiver job"
$receiverOut = Receive-Job -Job $receiverJob -Wait
Remove-Job $receiverJob -Force

$senderReport = Pull-Report $senderName $senderSerial
$receiverReport = Pull-Report $receiverName $receiverSerial

Write-Host "senderReport=$senderReport"
Write-Host "receiverReport=$receiverReport"

# Adjudicate 4 gates from receiver report
$verdict = "FAIL"
$details = @()
if (-not $receiverReport -or -not (Test-Path $receiverReport)) {
    $details += "receiver report missing"
} else {
    $json = Get-Content $receiverReport -Raw | ConvertFrom-Json
    $m = $json.metrics
    $g1 = [bool]$m.gateNetworkPacketsArrived
    $g2 = [bool]$m.gateSrtpIngressOk
    $g3 = [bool]$m.gateDecodeMixOk
    $g4 = [bool]$m.gateAudioTrackWritesOk
    $details += "datagramsReceived=$($m.datagramsReceived)"
    $details += "ingressAccepted=$($m.ingressAccepted)"
    $details += "decodeSuccessCount=$($m.decodeSuccessCount)"
    $details += "successfulWrites=$($m.successfulWrites)"
    $details += "failedWrites=$($m.failedWrites)"
    $details += "underrunCount=$($m.underrunCount)"
    $details += "gateNetwork=$g1 gateIngress=$g2 gateDecodeMix=$g3 gateAudioTrack=$g4"
    if ($g1 -and $g2 -and $g3 -and $g4) { $verdict = "PASS" }
}

$adj = @"
# Phase 1 Slice 4 — Multicast SRTP Network (M01→M03)

## Verdict

``````text
VERDICT: $verdict
DIRECTION: $Direction
RUN_ID: $runId
``````

## Gates (PASS requires all four)

``````text
$($details -join "`n")
``````

## Notes

- Timing / underrun: recorded only — not gates
- ADR-0056 / unicast fallback / G-RES-3: untouched
"@
Set-Content -Path (Join-Path $LogDir "ADJUDICATION.md") -Value $adj -Encoding UTF8
Add-Content -Path (Join-Path $LogDir "RUN_META.txt") -Value "verdict=$verdict`nended=$(Get-Date -Format o)" -Encoding UTF8

Write-Host "VERDICT=$verdict"
Write-Host "LogDir=$LogDir"
if ($verdict -ne "PASS") { exit 1 }
