# ADR-0058 — Underlay dual-node multicast probe
param(
    [string]$LogDir = "",
    [ValidateSet("M01toM02", "M02toM01", "M01toM03", "M03toM01", "M02toM03", "M03toM02")]
    [string]$Direction = "M01toM02",
    [int]$SenderDurationSec = 60,
    [int]$ReceiverLeadSec = 5,
    [int]$ReceiverTailSec = 10,
    [string]$Multicast = "239.255.42.99",
    [int]$Port = 46999,
    [string]$Iface = "wlan0",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string[]]$SkipForceStopModules = @()
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
$ProbeClass = "com.talkback.lab.underlay.UnderlayMulticastProbeInstrumentedTest#underlayMulticastProbe"
$RemoteProbeDirs = @(
    "/sdcard/Android/data/$TestPackage/files/underlay-mcast-probe",
    "/sdcard/Android/data/$Package/files/underlay-mcast-probe"
)

$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $LogDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "logs\adr0058-underlay-mcast-probe-$stamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $LogDir "device-reports") | Out-Null

$runId = (Split-Path $LogDir -Leaf) -replace '^adr0058-underlay-mcast-probe-', 'probe-'
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
    "case=ADR0058_UNDERLAY_MULTICAST_DUAL_NODE_PROBE"
    "phase=E3a.1_DIRECTIONALITY"
    "direction=$Direction"
    "build=$head"
    "runId=$runId"
    "sender=$senderName"
    "receiver=$receiverName"
    "senderDurationSec=$SenderDurationSec"
    "receiverDurationSec=$receiverDurationSec"
    "multicast=$Multicast`:$Port"
    "nominalPps=50"
    "payloadBytes=120"
    "adr0057=OUT_OF_SCOPE"
    "adr0056=RETAINED"
    "groupPtt=UNTOUCHED"
    "LogDir=$LogDir"
    "started=$(Get-Date -Format o)"
) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8

function Test-DeviceOnline([string]$Name, [string]$Serial) {
    $state = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") {
        throw "OFFLINE $Name ($Serial) state=$state"
    }
}

function Invoke-Probe {
    param(
        [string]$Name,
        [string]$Serial,
        [string]$Role,
        [int]$DurationSec
    )
    $args = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", $Role,
        "-e", "runId", $runId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", $Multicast,
        "-e", "port", "$Port",
        "-e", "deviceLabel", $Name
    )
    if ($Iface) {
        $args += @("-e", "iface", $Iface)
    }
    $args += $TestRunner
    Write-Host "PROBE $Name role=$Role durationSec=$DurationSec iface=$Iface"
    $out = & $adb @args 2>&1 | Out-String
    $outPath = Join-Path $LogDir "$Name-instrument.txt"
    Set-Content -Path $outPath -Value $out -Encoding UTF8
    if ($out -notmatch "OK \(") {
        Write-Warning "Instrument may have failed on $Name — see $outPath"
    }
}

Test-DeviceOnline $senderName $senderSerial
Test-DeviceOnline $receiverName $receiverSerial

$probeModules = @($senderName, $receiverName) | Sort-Object -Unique

if (-not $SkipBuild) {
    Write-Host "BUILD talkback-app + androidTest..."
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed exit=$LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

$appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
$testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"
if (-not (Test-Path $appApk)) { throw "Missing APK: $appApk" }
if (-not (Test-Path $testApk)) { throw "Missing test APK: $testApk" }

if (-not $SkipInstall) {
    foreach ($name in $probeModules) {
        $serial = $devices[$name]
        & $adb -s $serial install -r $appApk | Out-Host
        & $adb -s $serial install -r $testApk | Out-Host
        Write-Host "INSTALLED $name"
    }
}

foreach ($name in $probeModules) {
    $serial = $devices[$name]
    if ($SkipForceStopModules -contains $name) {
        Write-Host "SKIP FORCE-STOP $name (SkipForceStopModules)"
    } else {
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        Write-Host "FORCE-STOP $name"
    }
    & $adb -s $serial logcat -c
    Write-Host "CLEAR LOGCAT $name"
}

function Pull-ProbeJson {
    param(
        [string]$Name,
        [string]$Serial,
        [string]$LocalDir
    )
    New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null
    foreach ($remote in $RemoteProbeDirs) {
        $tmp = Join-Path $LocalDir "_pull_$Name"
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        $null = & $adb -s $Serial pull $remote $tmp 2>&1
        if ($LASTEXITCODE -ne 0) { continue }
        $match = Get-ChildItem -Path $tmp -Recurse -Filter "$runId-*.json" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($match) {
            $role = if ($match.Name -match "-(sender|receiver)\.json$") { $Matches[1] } else { "unknown" }
            Copy-Item $match.FullName (Join-Path $LocalDir "$Name-$role.json") -Force
            return $true
        }
    }
    return $false
}

function Wait-ReceiverJson {
    param(
        [string]$Name,
        [string]$Serial,
        [string]$LocalDir,
        [int]$TimeoutSec
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Pull-ProbeJson -Name $Name -Serial $Serial -LocalDir $LocalDir) {
            Write-Host "RECEIVER_JSON_READY $Name"
            return $true
        }
        Start-Sleep -Seconds 5
    }
    return $false
}

$pids = @()
foreach ($name in $probeModules) {
    $serial = $devices[$name]
    $out = Join-Path $LogDir "$name-probe.log"
    $proc = Start-Process -FilePath $adb -ArgumentList @(
        "-s", $serial, "logcat", "-v", "time", "-s", "UNDERLAY_MC_PROBE:I", "*:S"
    ) -RedirectStandardOutput $out -PassThru -WindowStyle Hidden
    $pids += "collectorPid=$($proc.Id) module=$name serial=$serial log=$out"
    Write-Host "COLLECTOR $name pid=$($proc.Id)"
}
$pids | Set-Content (Join-Path $LogDir "COLLECTORS.txt") -Encoding UTF8

$recvOut = Join-Path $LogDir "$receiverName-instrument.txt"
$recvErr = Join-Path $LogDir "$receiverName-instrument.err"
$recvArgs = @(
    "-s", $receiverSerial, "shell", "am", "instrument", "-w", "-r",
    "-e", "class", $ProbeClass,
    "-e", "role", "receiver",
    "-e", "runId", $runId,
    "-e", "durationSec", "$receiverDurationSec",
    "-e", "multicast", $Multicast,
    "-e", "port", "$Port",
    "-e", "deviceLabel", $receiverName
)
if ($Iface) { $recvArgs += @("-e", "iface", $Iface) }
$recvArgs += $TestRunner

Write-Host "DIRECTION $Direction sender=$senderName receiver=$receiverName"
Write-Host "RECEIVER started on $receiverName (lead ${ReceiverLeadSec}s)..."
$recvProc = Start-Process -FilePath $adb -ArgumentList $recvArgs -RedirectStandardOutput $recvOut -RedirectStandardError $recvErr -PassThru -WindowStyle Hidden
Start-Sleep -Seconds $ReceiverLeadSec
Invoke-Probe -Name $senderName -Serial $senderSerial -Role "sender" -DurationSec $SenderDurationSec
Write-Host "Waiting for receiver on $receiverName (pid=$($recvProc.Id))..."
$recvTimeoutSec = $receiverDurationSec + 120
$localReports = Join-Path $LogDir "device-reports"
$jsonReady = Wait-ReceiverJson -Name $receiverName -Serial $receiverSerial -LocalDir $localReports -TimeoutSec $recvTimeoutSec
$recvDone = $recvProc.WaitForExit(5 * 1000)
if (-not $recvDone) {
    if ($jsonReady) {
        Write-Host "Receiver instrument still running but JSON pulled — stopping adb pid=$($recvProc.Id)"
    } else {
        Write-Warning "Receiver timeout — killing pid=$($recvProc.Id)"
    }
    Stop-Process -Id $recvProc.Id -Force -ErrorAction SilentlyContinue
    if ($SkipForceStopModules -notcontains $receiverName) {
        & $adb -s $receiverSerial shell am force-stop $TestPackage
    }
}

foreach ($name in $probeModules) {
    $serial = $devices[$name]
    $local = Join-Path $LogDir "device-reports"
    $existing = Get-ChildItem $local -Filter "$name-*.json" -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "JSON already pulled for $name"
        continue
    }
    $pulled = Pull-ProbeJson -Name $name -Serial $serial -LocalDir $local
    if (-not $pulled) {
        Write-Warning "No probe JSON pulled for $name runId=$runId"
    }
}

$ErrorActionPreference = "Continue"

& (Join-Path $PSScriptRoot "adr0058-underlay-mcast-probe-stop-run.ps1") -LogDir $LogDir
& (Join-Path $PSScriptRoot "adr0058-underlay-mcast-probe-adjudicate.ps1") -LogDir $LogDir

Write-Host ""
Write-Host "DONE LogDir=$LogDir"
