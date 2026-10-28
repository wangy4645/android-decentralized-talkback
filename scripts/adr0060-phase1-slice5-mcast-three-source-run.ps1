# Phase 1 Slice 5 — M01/S1+M02/S2+M03/S3 -> M04 (USB-swap interactive launch)
param(
    [int]$SenderDurationSec = 10,
    [int]$ReceiverLeadSec = 3,
    [int]$ReceiverTailSec = 3,
    [ValidateSet("ALIGNED_SLOT", "LIVE_SLOT")]
    [string]$SlotMode = "ALIGNED_SLOT",
    [int]$BaseSeq = 12288,
    [int]$AlignedMediaSlot = 24576,
    [string]$Multicast = "239.255.42.99",
    [int]$Port = 47099,
    [string]$Iface = "wlan0",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Continue"
$devices = @{
    "M01" = "HTUBB21B09220661"
    "M02" = "DSJ-2407041"
    "M03" = "MDX0220416001963"
    "M04" = "DSJ-2407011"
}
$Package = "com.talkback.appprod"
$TestPackage = "com.talkback.test"
$TestRunner = "com.talkback.test/androidx.test.runner.AndroidJUnitRunner"
$ProbeClass = "com.talkback.lab.conference.Slice5MulticastThreeSourceInstrumentedTest#slice5MulticastThreeSourceNetwork"
$RemoteDirs = @(
    "/sdcard/Android/data/$TestPackage/files/slice5-mcast-three",
    "/sdcard/Android/data/$Package/files/slice5-mcast-three"
)

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runId = "slice5-mcast-three-$stamp"
$LogDir = Join-Path $repoRoot "logs\$runId"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LogDir "device-reports") | Out-Null

$receiverDurationSec = $ReceiverLeadSec + $SenderDurationSec + $ReceiverTailSec
$head = (git -C $repoRoot rev-parse --short HEAD 2>$null)
if (-not $head) { $head = "unknown" }

function Wait-Enter([string]$Prompt) {
    Write-Host ""
    Write-Host ">>> $Prompt" -ForegroundColor Cyan
    Read-Host "Press Enter to continue"
}

function Test-SerialOnline([string]$Serial) {
    $state = try { (& $adb -s $Serial get-state 2>&1 | Out-String).Trim() } catch { "offline" }
    return ($state -eq "device")
}

function Install-IfOnline([string]$Name, [string]$Serial, [string]$AppApk, [string]$TestApk) {
    if (-not (Test-SerialOnline $Serial)) {
        Write-Host "SKIP install $Name ($Serial) — not on USB"
        return
    }
    & $adb -s $Serial install -r $AppApk | Out-Host
    & $adb -s $Serial install -r $TestApk | Out-Host
    Write-Host "INSTALLED $Name"
}

function Start-Slice5Job {
    param([string]$Name, [string]$Serial, [string]$Role, [int]$DurationSec, [string]$SourceIdentity = $null)
    $scriptBlock = {
        param($adb, $Serial, $ProbeClass, $TestRunner, $runId, $DurationSec, $Multicast, $Port, $Iface, $SlotMode, $BaseSeq, $AlignedMediaSlot, $Name, $Role, $SourceIdentity, $LogDir)
        $extra = @()
        if ($SourceIdentity) { $extra += @("-e", "sourceIdentity", $SourceIdentity) }
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
            "-e", "slotMode", $SlotMode,
            "-e", "baseSeq", "$BaseSeq",
            "-e", "alignedMediaSlot", "$AlignedMediaSlot"
        ) + $extra + @($TestRunner)
        $out = & $adb @args 2>&1 | Out-String
        Set-Content -Path (Join-Path $LogDir "$Name-instrument.txt") -Value $out -Encoding UTF8
        return $out
    }
    return Start-Job -ScriptBlock $scriptBlock -ArgumentList `
        $adb, $Serial, $ProbeClass, $TestRunner, $runId, $DurationSec, $Multicast, $Port, $Iface, `
        $SlotMode, $BaseSeq, $AlignedMediaSlot, $Name, $Role, $SourceIdentity, $LogDir
}

function Pull-Report([string]$Name, [string]$Serial) {
    if (-not (Test-SerialOnline $Serial)) { return $null }
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

@(
    "case=PHASE1_SLICE5_MULTICAST_THREE_SOURCE"
    "topology=M01/S1+M02/S2+M03/S3->M04"
    "build=$head"
    "runId=$runId"
    "slotMode=$SlotMode"
    "senderDurationSec=$SenderDurationSec"
    "receiverDurationSec=$receiverDurationSec"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

Write-Host "=== Slice 5 USB-swap run ===" -ForegroundColor Green
Write-Host "runId=$runId slotMode=$SlotMode LogDir=$LogDir"
Write-Host ""
Write-Host "YOU WILL NEED TO:" -ForegroundColor Yellow
Write-Host "  1) USB only M04 -> start receiver (then unplug OK)"
Write-Host "  2) USB M01 + M03 -> start S1 + S3 senders"
Write-Host "  3) Swap USB to M02 -> start S2 sender (within ~15s of step 2)"
Write-Host "  4) USB M04 again -> pull reports"
Write-Host "Keep all phones on same WiFi, screen on, app may flash briefly."

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
    } finally { Pop-Location }
}

$appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"

foreach ($name in @("M01", "M02", "M03", "M04")) {
    Install-IfOnline $name $devices[$name] $appApk $testApk
}

# --- Step 1: M04 receiver ---
Wait-Enter "STEP 1: Connect ONLY M04 (serial $($devices['M04'])). Verify: adb devices"
if (-not (Test-SerialOnline $devices["M04"])) {
    Write-Error "M04 not online. Abort."
    exit 1
}
& $adb -s $devices["M04"] shell am force-stop $Package
& $adb -s $devices["M04"] shell am force-stop $TestPackage
& $adb -s $devices["M04"] logcat -c
$receiverJob = Start-Slice5Job -Name "M04" -Serial $devices["M04"] -Role "receiver" -DurationSec $receiverDurationSec
Write-Host "M04 RECEIVER started (${receiverDurationSec}s). You may UNPLUG M04 USB now." -ForegroundColor Green

# --- Step 2: M01 + M03 senders ---
Wait-Enter "STEP 2: Connect M01 + M03 USB (M04 unplugged OK). Verify both in adb devices"
foreach ($n in @("M01", "M03")) {
    if (-not (Test-SerialOnline $devices[$n])) {
        Write-Error "$n not online. Abort."
        exit 1
    }
    & $adb -s $devices[$n] shell am force-stop $Package
    & $adb -s $devices[$n] shell am force-stop $TestPackage
    & $adb -s $devices[$n] logcat -c
}
Write-Host "Waiting receiver lead ${ReceiverLeadSec}s..."
Start-Sleep -Seconds $ReceiverLeadSec
$m01Job = Start-Slice5Job -Name "M01" -Serial $devices["M01"] -Role "sender" -DurationSec $SenderDurationSec -SourceIdentity "S1"
$m03Job = Start-Slice5Job -Name "M03" -Serial $devices["M03"] -Role "sender" -DurationSec $SenderDurationSec -SourceIdentity "S3"
Write-Host "M01 S1 + M03 S3 SENDERS started." -ForegroundColor Green

# --- Step 3: M02 sender ---
Wait-Enter "STEP 3: Within 15s swap USB to M02 ONLY (serial $($devices['M02'])). Press Enter when M02 online"
if (-not (Test-SerialOnline $devices["M02"])) {
    Write-Error "M02 not online. Abort."
    exit 1
}
& $adb -s $devices["M02"] shell am force-stop $Package
& $adb -s $devices["M02"] shell am force-stop $TestPackage
& $adb -s $devices["M02"] logcat -c
$m02Job = Start-Slice5Job -Name "M02" -Serial $devices["M02"] -Role "sender" -DurationSec $SenderDurationSec -SourceIdentity "S2"
Write-Host "M02 S2 SENDER started." -ForegroundColor Green

Write-Host "Waiting for all probes to finish..."
$null = Receive-Job -Job $m01Job, $m03Job, $m02Job, $receiverJob -Wait
Remove-Job $m01Job, $m03Job, $m02Job, $receiverJob -Force

# --- Step 4: pull reports ---
Wait-Enter "STEP 4: Connect M04 USB to pull receiver report (optional: also M01-M03 for sender reports)"
foreach ($name in @("M01", "M02", "M03", "M04")) {
    $r = Pull-Report $name $devices[$name]
    Write-Host "report $name = $r"
}

$m04Report = Join-Path $LogDir "device-reports\M04-receiver.json"
$verdict = "FAIL"
$details = @()
if (Test-Path $m04Report) {
    $json = Get-Content $m04Report -Raw | ConvertFrom-Json
    $m = $json.metrics
    $details += "slotMode=$($m.slotMode)"
    $details += "ingress S1=$($m.ingressAcceptedBySource.S1) S2=$($m.ingressAcceptedBySource.S2) S3=$($m.ingressAcceptedBySource.S3)"
    $details += "decode S1=$($m.decodeSuccessBySource.S1) S2=$($m.decodeSuccessBySource.S2) S3=$($m.decodeSuccessBySource.S3)"
    $details += "tripleMix=$($m.mixCyclesWithThreeParticipants) writes=$($m.successfulWrites) rejected=$($m.ingressRejected)"
    if ($m.gateAllPass) { $verdict = "PASS" }
} else {
    $details += "M04 receiver report missing"
}

$adj = @"
# Phase 1 Slice 5 — Three-Source Multicast (M01+M02+M03 -> M04)

## Verdict

``````text
VERDICT: $verdict
RUN_ID: $runId
SLOT_MODE: $SlotMode
``````

## Gates

``````text
$($details -join "`n")
``````
"@
Set-Content -Path (Join-Path $LogDir "ADJUDICATION.md") -Value $adj -Encoding UTF8
Add-Content -Path (Join-Path $LogDir "RUN_META.txt") -Value "verdict=$verdict`nended=$(Get-Date -Format o)"

Write-Host ""
Write-Host "VERDICT=$verdict" -ForegroundColor $(if ($verdict -eq "PASS") { "Green" } else { "Red" })
Write-Host "LogDir=$LogDir"
if ($verdict -ne "PASS") { exit 1 }
