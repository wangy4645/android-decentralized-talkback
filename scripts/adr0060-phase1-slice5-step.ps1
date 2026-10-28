# Slice5 field step helper — non-interactive, agent-driven
param(
    [ValidateSet("prep", "start-receiver", "start-senders-m01-m03", "start-sender-m02", "wait-and-pull", "status")]
    [string]$Step,
    [string]$RunId = "",
    [string]$LogDir = "",
    [int]$SenderDurationSec = 10,
    [int]$ReceiverLeadSec = 3,
    [int]$ReceiverTailSec = 3,
    [string]$SlotMode = "ALIGNED_SLOT",
    [int]$BaseSeq = 12288,
    [int]$AlignedMediaSlot = 24576
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
$stateFile = Join-Path $repoRoot "logs\slice5-active-run.json"

function Online([string]$Serial) {
    $raw = & $adb -s $Serial get-state 2>&1 | Out-String
    return ($raw.Trim() -eq "device")
}

function Save-State($obj) {
    New-Item -ItemType Directory -Force -Path (Split-Path $stateFile -Parent) | Out-Null
    ($obj | ConvertTo-Json -Depth 6) | Set-Content $stateFile -Encoding UTF8
}

function Load-State {
    if (-not (Test-Path $stateFile)) { throw "No active Slice5 run state. Run prep first." }
    return Get-Content $stateFile -Raw | ConvertFrom-Json
}

function Start-ProbeProcess([string]$Name, [string]$Serial, [string]$Role, [int]$DurationSec, [string]$SourceIdentity, [string]$RunId, [string]$LogDir, [string]$SlotMode, [int]$BaseSeq, [int]$AlignedMediaSlot) {
    # Detached process — survives parent script exit (unlike Start-Job).
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
        "-e", "slotMode", $SlotMode,
        "-e", "baseSeq", "$BaseSeq",
        "-e", "alignedMediaSlot", "$AlignedMediaSlot"
    )
    if ($SourceIdentity) {
        $argList += @("-e", "sourceIdentity", $SourceIdentity)
    }
    $argList += @($TestRunner)
    $p = Start-Process -FilePath $adb -ArgumentList $argList `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru
    Write-Host "STARTED $Name pid=$($p.Id) role=$Role durationSec=$DurationSec"
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
        Write-Host "=== adb devices ==="
        & $adb devices -l
        if (Test-Path $stateFile) {
            Write-Host "=== active run ==="
            Get-Content $stateFile -Raw
        } else {
            Write-Host "No active run state."
        }
    }
    "prep" {
        $appApk = Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk"
        $testApk = Join-Path $repoRoot "android-board-talkback\build\outputs\apk\androidTest\debug\android-board-talkback-debug-androidTest.apk"
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
            $RunId = "slice5-mcast-three-$stamp"
        }
        if (-not $LogDir) {
            $LogDir = Join-Path $repoRoot "logs\$RunId"
        }
        New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $LogDir "device-reports") | Out-Null
        $receiverDurationSec = $ReceiverLeadSec + $SenderDurationSec + $ReceiverTailSec
        Save-State @{
            runId = $RunId
            logDir = $LogDir
            slotMode = $SlotMode
            senderDurationSec = $SenderDurationSec
            receiverLeadSec = $ReceiverLeadSec
            receiverTailSec = $ReceiverTailSec
            receiverDurationSec = $receiverDurationSec
            baseSeq = $BaseSeq
            alignedMediaSlot = $AlignedMediaSlot
            phase = "prep"
        }
        Write-Host "RUN_ID=$RunId"
        Write-Host "LogDir=$LogDir"
        Write-Host "PREP_OK"
    }
    "start-receiver" {
        $st = Load-State
        $serial = $devices["M04"]
        if (-not (Online $serial)) { throw "M04 offline — plug M04 USB first" }
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        & $adb -s $serial logcat -c
        $recvPid = Start-ProbeProcess "M04" $serial "receiver" ([int]$st.receiverDurationSec) $null $st.runId $st.logDir $st.slotMode ([int]$st.baseSeq) ([int]$st.alignedMediaSlot)
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
        $lead = [int]$st.receiverLeadSec
        Write-Host "Waiting lead ${lead}s..."
        Start-Sleep -Seconds $lead
        $p1 = Start-ProbeProcess "M01" $devices["M01"] "sender" ([int]$st.senderDurationSec) "S1" $st.runId $st.logDir $st.slotMode ([int]$st.baseSeq) ([int]$st.alignedMediaSlot)
        $p3 = Start-ProbeProcess "M03" $devices["M03"] "sender" ([int]$st.senderDurationSec) "S3" $st.runId $st.logDir $st.slotMode ([int]$st.baseSeq) ([int]$st.alignedMediaSlot)
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "m01-m03-started" -Force
        $st | Add-Member -NotePropertyName m01Pid -NotePropertyValue $p1 -Force
        $st | Add-Member -NotePropertyName m03Pid -NotePropertyValue $p3 -Force
        $st | Add-Member -NotePropertyName sendersStartedAt -NotePropertyValue (Get-Date -Format o) -Force
        Save-State $st
        Write-Host "M01_S1_AND_M03_S3_STARTED pids=$p1,$p3"
        Write-Host "NEXT: swap USB to M02 within ~20s, then say ready"
    }
    "start-sender-m02" {
        $st = Load-State
        $serial = $devices["M02"]
        if (-not (Online $serial)) { throw "M02 offline — swap USB to M02 first" }
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        & $adb -s $serial logcat -c
        $p2 = Start-ProbeProcess "M02" $serial "sender" ([int]$st.senderDurationSec) "S2" $st.runId $st.logDir $st.slotMode ([int]$st.baseSeq) ([int]$st.alignedMediaSlot)
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "m02-started" -Force
        $st | Add-Member -NotePropertyName m02Pid -NotePropertyValue $p2 -Force
        Save-State $st
        Write-Host "M02_S2_STARTED pid=$p2"
    }
    "wait-and-pull" {
        $st = Load-State
        $waitSec = [Math]::Max(20, ([int]$st.receiverDurationSec) + 15)
        Write-Host "Waiting up to ${waitSec}s for probes..."
        $deadline = (Get-Date).AddSeconds($waitSec)
        $pids = @($st.receiverPid, $st.m01Pid, $st.m03Pid, $st.m02Pid) | Where-Object { $_ }
        while ((Get-Date) -lt $deadline) {
            $alive = 0
            foreach ($pid in $pids) {
                try {
                    $p = Get-Process -Id $pid -ErrorAction SilentlyContinue
                    if ($p) { $alive += 1 }
                } catch {}
            }
            if ($alive -eq 0) { break }
            Start-Sleep -Seconds 2
        }
        foreach ($name in @("M01", "M02", "M03", "M04")) {
            $r = Pull-Report $name $devices[$name] $st.runId $st.logDir
            Write-Host "report $name = $r"
        }
        $m04Report = Join-Path $st.logDir "device-reports\M04-receiver.json"
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
            $details += "M04 receiver report missing — plug M04 and re-run wait-and-pull"
        }
        $adj = @"
# Phase 1 Slice 5 — Three-Source Multicast

## Verdict

``````text
VERDICT: $verdict
RUN_ID: $($st.runId)
SLOT_MODE: $($st.slotMode)
``````

## Gates

``````text
$($details -join "`n")
``````
"@
        Set-Content -Path (Join-Path $st.logDir "ADJUDICATION.md") -Value $adj -Encoding UTF8
        Write-Host "VERDICT=$verdict"
        Write-Host "LogDir=$($st.logDir)"
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "done" -Force
        $st | Add-Member -NotePropertyName verdict -NotePropertyValue $verdict -Force
        Save-State $st
        if ($verdict -ne "PASS") { exit 1 }
    }
}
