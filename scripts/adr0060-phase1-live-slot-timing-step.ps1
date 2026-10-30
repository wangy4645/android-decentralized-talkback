# LIVE_SLOT timing diagnostic — agent-driven USB-swap (attribution only; no fixes)
param(
    [ValidateSet("prep", "start-receiver", "start-senders-m01-m03", "start-sender-m02", "wait-and-pull", "status")]
    [string]$Step,
    [string]$RunId = "",
    [string]$LogDir = "",
    [int]$SenderDurationSec = 90,
    [int]$ReceiverLeadSec = 5,
    [int]$ReceiverTailSec = 15,
    [int]$BaseSeq = 12288
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
$ProbeClass = "com.talkback.lab.conference.LiveSlotTimingDiagnosticInstrumentedTest#liveSlotTimingDiagnostic"
$RemoteDirs = @(
    "/sdcard/Android/data/$TestPackage/files/live-slot-timing",
    "/sdcard/Android/data/$Package/files/live-slot-timing"
)
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $repoRoot "logs\live-slot-timing-active-run.json"

function Online([string]$Serial) {
    $raw = & $adb -s $Serial get-state 2>&1 | Out-String
    return ($raw.Trim() -eq "device")
}

function Save-State($obj) {
    New-Item -ItemType Directory -Force -Path (Split-Path $stateFile -Parent) | Out-Null
    ($obj | ConvertTo-Json -Depth 8) | Set-Content $stateFile -Encoding UTF8
}

function Load-State {
    if (-not (Test-Path $stateFile)) { throw "No active LIVE_SLOT timing run. Run prep first." }
    return Get-Content $stateFile -Raw | ConvertFrom-Json
}

function Start-ProbeProcess([string]$Name, [string]$Serial, [string]$Role, [int]$DurationSec, [string]$SourceIdentity, [string]$RunId, [string]$LogDir, [int]$BaseSeq, [long]$EpochWallMs) {
    $outLog = Join-Path $LogDir "$Name-instrument.out.txt"
    $errLog = Join-Path $LogDir "$Name-instrument.err.txt"
    $argList = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "role", $Role,
        "-e", "runId", $RunId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", "239.255.42.99",
        "-e", "port", "47099",
        "-e", "iface", "wlan0",
        "-e", "deviceLabel", $Name,
        "-e", "baseSeq", "$BaseSeq",
        "-e", "epochWallMs", "$EpochWallMs"
    )
    if ($SourceIdentity) { $argList += @("-e", "sourceIdentity", $SourceIdentity) }
    $argList += @($TestRunner)
    $p = Start-Process -FilePath $adb -ArgumentList $argList `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru
    Write-Host "STARTED $Name pid=$($p.Id) role=$Role durationSec=$DurationSec epoch=$EpochWallMs"
    return $p.Id
}

function Pull-Report([string]$Name, [string]$Serial, [string]$RunId, [string]$LogDir) {
    if (-not (Online $Serial)) { return $null }
    $localDir = Join-Path $LogDir "device-reports"
    New-Item -ItemType Directory -Force -Path $localDir | Out-Null
    foreach ($remote in $RemoteDirs) {
        $tmp = Join-Path $localDir "_pull_$Name"
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tmp | Out-Null
        $null = & $adb -s $Serial pull $remote $tmp 2>&1
        $match = Get-ChildItem -Path $tmp -Recurse -Filter "$RunId-*.json" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($match) {
            $role = if ($match.Name -match "-(sender|receiver)\.json$") { $Matches[1] } else { "unknown" }
            $dest = Join-Path $localDir "$Name-$role.json"
            Copy-Item $match.FullName $dest -Force
            return $dest
        }
    }
    return $null
}

switch ($Step) {
    "status" {
        & $adb devices -l
        if (Test-Path $stateFile) { Get-Content $stateFile -Raw } else { Write-Host "No active run." }
    }
    "prep" {
        $appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
        $testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"
        if (-not (Test-Path $appApk) -or -not (Test-Path $testApk)) {
            Push-Location $repoRoot
            try {
                & .\gradlew.bat :talkback-app:assembleDebug :android-board-talkback:assembleDebugAndroidTest
                if ($LASTEXITCODE -ne 0) { throw "build failed" }
            } finally { Pop-Location }
        }
        foreach ($name in @("M01", "M02", "M03", "M04")) {
            $serial = $devices[$name]
            if (Online $serial) {
                & $adb -s $serial install -r $appApk | Out-Host
                & $adb -s $serial install -r $testApk | Out-Host
                Write-Host "INSTALLED $name"
            } else {
                Write-Host "SKIP install $name (offline)"
            }
        }
        if (-not $RunId) {
            $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
            $RunId = "live-slot-timing-$stamp"
        }
        if (-not $LogDir) { $LogDir = Join-Path $repoRoot "logs\$RunId" }
        New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $LogDir "device-reports") | Out-Null
        $receiverDurationSec = $ReceiverLeadSec + $SenderDurationSec + $ReceiverTailSec
        $epochWallMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ($ReceiverLeadSec * 1000)
        Save-State @{
            runId = $RunId
            logDir = $LogDir
            senderDurationSec = $SenderDurationSec
            receiverLeadSec = $ReceiverLeadSec
            receiverTailSec = $ReceiverTailSec
            receiverDurationSec = $receiverDurationSec
            baseSeq = $BaseSeq
            epochWallMs = $epochWallMs
            phase = "prep"
            capacityGates = "NONE"
            fixApplied = $false
        }
        @(
            "case=PHASE1_LIVE_SLOT_TIMING_DIAGNOSTIC"
            "runId=$RunId"
            "senderDurationSec=$SenderDurationSec"
            "receiverDurationSec=$receiverDurationSec"
            "epochWallMs=$epochWallMs"
            "capacityGates=NONE"
            "fixApplied=false"
            "started=$(Get-Date -Format o)"
        ) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
        Write-Host "RUN_ID=$RunId"
        Write-Host "LogDir=$LogDir"
        Write-Host "epochWallMs=$epochWallMs"
        Write-Host "PREP_OK"
    }
    "start-receiver" {
        $st = Load-State
        $serial = $devices["M04"]
        if (-not (Online $serial)) { throw "M04 offline" }
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        & $adb -s $serial logcat -c
        $recvPid = Start-ProbeProcess "M04" $serial "receiver" ([int]$st.receiverDurationSec) $null $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs)
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "receiver-started" -Force
        $st | Add-Member -NotePropertyName receiverPid -NotePropertyValue $recvPid -Force
        $st | Add-Member -NotePropertyName receiverStartedAt -NotePropertyValue (Get-Date -Format o) -Force
        Save-State $st
        Write-Host "M04_RECEIVER_STARTED pid=$recvPid durationSec=$($st.receiverDurationSec)"
        Write-Host "You may UNPLUG M04 now."
    }
    "start-senders-m01-m03" {
        $st = Load-State
        foreach ($n in @("M01", "M03")) {
            if (-not (Online $devices[$n])) { throw "$n offline" }
            & $adb -s $devices[$n] shell am force-stop $Package
            & $adb -s $devices[$n] shell am force-stop $TestPackage
            & $adb -s $devices[$n] logcat -c
        }
        Start-Sleep -Seconds ([int]$st.receiverLeadSec)
        $p1 = Start-ProbeProcess "M01" $devices["M01"] "sender" ([int]$st.senderDurationSec) "S1" $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs)
        $p3 = Start-ProbeProcess "M03" $devices["M03"] "sender" ([int]$st.senderDurationSec) "S3" $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs)
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "m01-m03-started" -Force
        $st | Add-Member -NotePropertyName m01Pid -NotePropertyValue $p1 -Force
        $st | Add-Member -NotePropertyName m03Pid -NotePropertyValue $p3 -Force
        Save-State $st
        Write-Host "M01_S1_AND_M03_S3_STARTED pids=$p1,$p3"
        Write-Host "NEXT: swap USB to M02 within ~20s"
    }
    "start-sender-m02" {
        $st = Load-State
        $serial = $devices["M02"]
        if (-not (Online $serial)) { throw "M02 offline" }
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        & $adb -s $serial logcat -c
        $p2 = Start-ProbeProcess "M02" $serial "sender" ([int]$st.senderDurationSec) "S2" $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs)
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "m02-started" -Force
        $st | Add-Member -NotePropertyName m02Pid -NotePropertyValue $p2 -Force
        Save-State $st
        Write-Host "M02_S2_STARTED pid=$p2"
    }
    "wait-and-pull" {
        $st = Load-State
        $waitSec = [Math]::Max(30, ([int]$st.receiverDurationSec) + 30)
        Write-Host "Waiting up to ${waitSec}s for diagnostic..."
        $deadline = (Get-Date).AddSeconds($waitSec)
        $procIds = @($st.receiverPid, $st.m01Pid, $st.m03Pid, $st.m02Pid) | Where-Object { $_ }
        while ((Get-Date) -lt $deadline) {
            $alive = 0
            foreach ($procId in $procIds) {
                if (Get-Process -Id $procId -ErrorAction SilentlyContinue) { $alive += 1 }
            }
            if ($alive -eq 0) { break }
            Start-Sleep -Seconds 3
        }
        foreach ($name in @("M01", "M02", "M03", "M04")) {
            $r = Pull-Report $name $devices[$name] $st.runId $st.logDir
            Write-Host "report $name = $r"
        }
        $m04Report = Join-Path $st.logDir "device-reports\M04-receiver.json"
        $details = @()
        $completed = $false
        $q1 = "UNKNOWN"; $q2 = "UNKNOWN"; $q3 = "UNKNOWN"
        if (Test-Path $m04Report) {
            $m = (Get-Content $m04Report -Raw | ConvertFrom-Json).metrics
            $td = $m.timingDiagnostic
            $attr = $td.attribution
            $q1 = [string]$attr.q1Late
            $q2 = [string]$attr.q2Underrun
            $q3 = [string]$attr.q3Correlation
            $details += "diagnosticCompleted=$($m.diagnosticCompleted)"
            $details += "ingress S1=$($m.ingressAcceptedBySource.S1) S2=$($m.ingressAcceptedBySource.S2) S3=$($m.ingressAcceptedBySource.S3)"
            $details += "mixCycles=$($m.mixCycles) writes=$($m.successfulWrites) underrun=$($m.underrunCount) late=$($m.lateForPlayoutCount)"
            $details += "Q1=$q1 Q2=$q2 Q3=$q3"
            $details += "late atAdmit=$($td.lateSamples.atAdmit) atPull=$($td.lateSamples.atPull)"
            $details += "mediaTimeEqualsArrivalRate=$($td.admitMapping.mediaTimeEqualsArrivalRate)"
            $details += "writeIntervalUs p50=$($td.writeCadence.writeIntervalUs.p50) p95=$($td.writeCadence.writeIntervalUs.p95) p99=$($td.writeCadence.writeIntervalUs.p99)"
            $details += "cooccur both=$($td.lateUnderrunCooccurrence.cyclesWithBoth) lateOnly=$($td.lateUnderrunCooccurrence.cyclesWithLateOnly) underrunOnly=$($td.lateUnderrunCooccurrence.cyclesWithUnderrunOnly)"
            $completed = [bool]$m.diagnosticCompleted
        } else {
            $details += "M04 receiver report missing — plug M04 and re-run wait-and-pull"
        }
        $status = if ($completed) { "DIAGNOSTIC_COMPLETE" } else { "DIAGNOSTIC_INCOMPLETE" }
        $adj = @"
# Phase 1 LIVE_SLOT Timing Diagnostic

## Status

``````text
STATUS: $status
RUN_ID: $($st.runId)
CAPACITY_GATES: NONE
FIX_APPLIED: false
``````

## Attribution (auto)

``````text
Q1 late     = $q1
Q2 underrun = $q2
Q3 corr     = $q3

$($details -join "`n")
``````
"@
        Set-Content -Path (Join-Path $st.logDir "ADJUDICATION.md") -Value $adj -Encoding UTF8
        Add-Content -Path (Join-Path $st.logDir "RUN_META.txt") -Value "status=$status`nq1=$q1`nq2=$q2`nq3=$q3`nended=$(Get-Date -Format o)"
        Write-Host "STATUS=$status"
        Write-Host "Q1=$q1 Q2=$q2 Q3=$q3"
        Write-Host "LogDir=$($st.logDir)"
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "done" -Force
        $st | Add-Member -NotePropertyName status -NotePropertyValue $status -Force
        Save-State $st
        if (-not $completed) { exit 1 }
    }
}
