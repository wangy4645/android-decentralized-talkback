# Phase 1 Slice 4b — M01/S1 + M02/S2 → M03 multi-source multicast SRTP
param(
    [int]$SenderDurationSec = 10,
    [int]$ReceiverLeadSec = 3,
    [int]$ReceiverTailSec = 3,
    [string]$Multicast = "239.255.42.99",
    [int]$Port = 47099,
    [string]$Iface = "wlan0",
    [int]$AlignedMediaSlot = 24576,
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
$ProbeClass = "com.talkback.lab.conference.Slice4bMulticastMultiSourceInstrumentedTest#slice4bMulticastMultiSourceNetwork"
$RemoteDirs = @(
    "/sdcard/Android/data/$TestPackage/files/slice4b-mcast-multi",
    "/sdcard/Android/data/$Package/files/slice4b-mcast-multi"
)

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = "slice4b-mcast-multi-$stamp"
$LogDir = Join-Path $repoRoot "logs\$runId"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LogDir "device-reports") | Out-Null

$receiverDurationSec = $ReceiverLeadSec + $SenderDurationSec + $ReceiverTailSec
$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

@(
    "case=PHASE1_SLICE4B_MULTICAST_MULTI_SOURCE"
    "topology=M01/S1+M02/S2->M03"
    "build=$head"
    "runId=$runId"
    "senderDurationSec=$SenderDurationSec"
    "receiverDurationSec=$receiverDurationSec"
    "multicast=$Multicast`:$Port"
    "iface=$Iface"
    "alignedMediaSlot=$AlignedMediaSlot"
    "gates=bothSources,ingressPerSource,decodePerSource,dualMix,audioTrackWrites,ingressRejectedZero"
    "performanceGates=NONE"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

function Test-DeviceOnline([string]$Name, [string]$Serial) {
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") { throw "OFFLINE $Name ($Serial) state=$state" }
}

function Invoke-Slice4b {
    param(
        [string]$Name,
        [string]$Serial,
        [string]$Role,
        [int]$DurationSec,
        [string]$SourceIdentity = $null
    )
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
        "-e", "alignedMediaSlot", "$AlignedMediaSlot",
        $TestRunner
    )
    if ($SourceIdentity) {
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
            "-e", "sourceIdentity", $SourceIdentity,
            "-e", "alignedMediaSlot", "$AlignedMediaSlot",
            $TestRunner
        )
    }
    Write-Host "SLICE4B $Name role=$Role source=$SourceIdentity durationSec=$DurationSec"
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

foreach ($name in @("M01", "M02", "M03")) {
    Test-DeviceOnline $name $devices[$name]
}

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
    foreach ($name in @("M01", "M02", "M03")) {
        $serial = $devices[$name]
        & $adb -s $serial install -r $appApk | Out-Host
        & $adb -s $serial install -r $testApk | Out-Host
        Write-Host "INSTALLED $name"
    }
}

foreach ($name in @("M01", "M02", "M03")) {
    $serial = $devices[$name]
    & $adb -s $serial shell am force-stop $Package
    & $adb -s $serial shell am force-stop $TestPackage
    & $adb -s $serial logcat -c
}

# M03 receiver first, then M01+M02 senders in parallel
$receiverJob = Start-Job -ScriptBlock {
    param($adb, $Serial, $ProbeClass, $TestRunner, $runId, $DurationSec, $Multicast, $Port, $Iface, $AlignedMediaSlot, $LogDir)
    $args = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", "receiver",
        "-e", "runId", $runId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", $Multicast,
        "-e", "port", "$Port",
        "-e", "iface", $Iface,
        "-e", "deviceLabel", "M03",
        "-e", "alignedMediaSlot", "$AlignedMediaSlot",
        $TestRunner
    )
    $out = & $adb @args 2>&1 | Out-String
    Set-Content -Path (Join-Path $LogDir "M03-instrument.txt") -Value $out -Encoding UTF8
    return $out
} -ArgumentList $adb, $devices["M03"], $ProbeClass, $TestRunner, $runId, $receiverDurationSec, $Multicast, $Port, $Iface, $AlignedMediaSlot, $LogDir

Write-Host "RECEIVER M03 started; waiting lead ${ReceiverLeadSec}s"
Start-Sleep -Seconds $ReceiverLeadSec

$senderM01Job = Start-Job -ScriptBlock {
    param($adb, $Serial, $ProbeClass, $TestRunner, $runId, $DurationSec, $Multicast, $Port, $Iface, $AlignedMediaSlot, $LogDir)
    $args = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", "sender",
        "-e", "runId", $runId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", $Multicast,
        "-e", "port", "$Port",
        "-e", "iface", $Iface,
        "-e", "deviceLabel", "M01",
        "-e", "sourceIdentity", "S1",
        "-e", "alignedMediaSlot", "$AlignedMediaSlot",
        $TestRunner
    )
    $out = & $adb @args 2>&1 | Out-String
    Set-Content -Path (Join-Path $LogDir "M01-instrument.txt") -Value $out -Encoding UTF8
    return $out
} -ArgumentList $adb, $devices["M01"], $ProbeClass, $TestRunner, $runId, $SenderDurationSec, $Multicast, $Port, $Iface, $AlignedMediaSlot, $LogDir

$senderM02Job = Start-Job -ScriptBlock {
    param($adb, $Serial, $ProbeClass, $TestRunner, $runId, $DurationSec, $Multicast, $Port, $Iface, $AlignedMediaSlot, $LogDir)
    $args = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", "sender",
        "-e", "runId", $runId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", $Multicast,
        "-e", "port", "$Port",
        "-e", "iface", $Iface,
        "-e", "deviceLabel", "M02",
        "-e", "sourceIdentity", "S2",
        "-e", "alignedMediaSlot", "$AlignedMediaSlot",
        $TestRunner
    )
    $out = & $adb @args 2>&1 | Out-String
    Set-Content -Path (Join-Path $LogDir "M02-instrument.txt") -Value $out -Encoding UTF8
    return $out
} -ArgumentList $adb, $devices["M02"], $ProbeClass, $TestRunner, $runId, $SenderDurationSec, $Multicast, $Port, $Iface, $AlignedMediaSlot, $LogDir

Write-Host "SENDERS M01+M02 started; waiting completion"
$null = Receive-Job -Job $senderM01Job -Wait
$null = Receive-Job -Job $senderM02Job -Wait
Remove-Job $senderM01Job, $senderM02Job -Force
Write-Host "SENDERS done; waiting receiver"
$null = Receive-Job -Job $receiverJob -Wait
Remove-Job $receiverJob -Force

$m01Report = Pull-Report "M01" $devices["M01"]
$m02Report = Pull-Report "M02" $devices["M02"]
$m03Report = Pull-Report "M03" $devices["M03"]

Write-Host "M01 report=$m01Report"
Write-Host "M02 report=$m02Report"
Write-Host "M03 report=$m03Report"

$verdict = "FAIL"
$details = @()
if (-not $m03Report -or -not (Test-Path $m03Report)) {
    $details += "M03 receiver report missing"
} else {
    $json = Get-Content $m03Report -Raw | ConvertFrom-Json
    $m = $json.metrics
    $g1 = [bool]$m.gateObservesBothSources
    $g2a = [bool]$m.gateIngressAcceptedS1
    $g2b = [bool]$m.gateIngressAcceptedS2
    $g3a = [bool]$m.gateDecodeSuccessS1
    $g3b = [bool]$m.gateDecodeSuccessS2
    $g4 = [bool]$m.gateDualParticipantMix
    $g5 = [bool]$m.gateAudioTrackWritesOk
    $g6 = [bool]$m.gateIngressRejectedZero
    $details += "ingressAccepted(S1)=$($m.ingressAcceptedBySource.S1)"
    $details += "ingressAccepted(S2)=$($m.ingressAcceptedBySource.S2)"
    $details += "decodeSuccess(S1)=$($m.decodeSuccessBySource.S1)"
    $details += "decodeSuccess(S2)=$($m.decodeSuccessBySource.S2)"
    $details += "mixCyclesWithTwoParticipants=$($m.mixCyclesWithTwoParticipants)"
    $details += "successfulWrites=$($m.successfulWrites)"
    $details += "ingressRejected=$($m.ingressRejected)"
    $details += "gateBoth=$g1 gateIngressS1=$g2a gateIngressS2=$g2b gateDecodeS1=$g3a gateDecodeS2=$g3b gateDualMix=$g4 gateAudioTrack=$g5 gateRejectedZero=$g6"
    if ($g1 -and $g2a -and $g2b -and $g3a -and $g3b -and $g4 -and $g5 -and $g6) { $verdict = "PASS" }
}

$adj = @"
# Phase 1 Slice 4b — Multi-Source Multicast SRTP (M01/S1 + M02/S2 -> M03)

## Verdict

``````text
VERDICT: $verdict
RUN_ID: $runId
TOPOLOGY: M01/S1 + M02/S2 -> M03
``````

## Gates (PASS requires all)

``````text
$($details -join "`n")
``````

## Notes

- decode/mix/playout budget, jitter depth, underrun: recorded only — not gates
- ADR-0056 / unicast fallback / G-RES-3 / 8-10 capacity: untouched
"@
Set-Content -Path (Join-Path $LogDir "ADJUDICATION.md") -Value $adj -Encoding UTF8
Add-Content -Path (Join-Path $LogDir "RUN_META.txt") -Value "verdict=$verdict`nended=$(Get-Date -Format o)" -Encoding UTF8

Write-Host "VERDICT=$verdict"
Write-Host "LogDir=$LogDir"
if ($verdict -ne "PASS") { exit 1 }
