# 4-source pipelineLock hold attribution — USB-swap steps (observation only)
param(
    [ValidateSet("prep", "start-receiver", "start-senders-m01-m03", "start-sender-m02", "wait-and-pull", "status")]
    [string]$Step,
    [string]$RunId = "",
    [string]$LogDir = "",
    [int]$SenderDurationSec = 60,
    [int]$ReceiverLeadSec = 5,
    [int]$ReceiverTailSec = 15,
    [int]$BaseSeq = 16384
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
$SenderProbeClass = "com.talkback.lab.conference.ReceiverScaling4SourceInstrumentedTest#receiverScaling4SourceObservation"
$ReceiverProbeClass = "com.talkback.lab.conference.ReceiverScaling4SourceLockAttributionInstrumentedTest#receiverScaling4SourceLockAttribution"
$SenderRemoteDirs = @(
    "/sdcard/Android/data/$TestPackage/files/receiver-scaling-4source",
    "/sdcard/Android/data/$Package/files/receiver-scaling-4source"
)
$ReceiverRemoteDirs = @(
    "/sdcard/Android/data/$TestPackage/files/receiver-scaling-4source-lock-attribution",
    "/sdcard/Android/data/$Package/files/receiver-scaling-4source-lock-attribution"
)
$adb = (Get-Command adb).Source
$repoRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $repoRoot "logs\receiver-scaling-4source-lock-attribution-active-run.json"

function Online([string]$Serial) {
    $raw = & $adb -s $Serial get-state 2>&1 | Out-String
    return ($raw.Trim() -eq "device")
}

function Save-State($obj) {
    New-Item -ItemType Directory -Force -Path (Split-Path $stateFile -Parent) | Out-Null
    ($obj | ConvertTo-Json -Depth 8) | Set-Content $stateFile -Encoding UTF8
}

function Load-State {
    if (-not (Test-Path $stateFile)) { throw "No active lock attribution run. Run prep first." }
    return Get-Content $stateFile -Raw | ConvertFrom-Json
}

function Start-ProbeProcess([string]$Name, [string]$Serial, [string]$Role, [int]$DurationSec, [string]$SourceIdentity, [string]$RunId, [string]$LogDir, [int]$BaseSeq, [long]$EpochWallMs, [string]$ProbeClass) {
    $outLog = Join-Path $LogDir "$Name-instrument.out.txt"
    $errLog = Join-Path $LogDir "$Name-instrument.err.txt"
    $argList = @(
        "-s", $Serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ProbeClass,
        "-e", "runId", $RunId,
        "-e", "durationSec", "$DurationSec",
        "-e", "multicast", "239.255.42.99",
        "-e", "port", "47099",
        "-e", "iface", "wlan0",
        "-e", "deviceLabel", $Name,
        "-e", "baseSeq", "$BaseSeq",
        "-e", "epochWallMs", "$EpochWallMs"
    )
    if ($Role -eq "sender") { $argList += @("-e", "role", "sender") }
    if ($SourceIdentity) { $argList += @("-e", "sourceIdentity", $SourceIdentity) }
    $argList += @($TestRunner)
    $p = Start-Process -FilePath $adb -ArgumentList $argList `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru
    Write-Host "STARTED $Name pid=$($p.Id) role=$Role probe=$ProbeClass"
    return $p.Id
}

function Pull-Report([string]$Name, [string]$Serial, [string]$RunId, [string]$LogDir, [string[]]$RemoteDirs, [string]$RoleHint) {
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
            $role = if ($match.Name -match "-(sender|receiver)\.json$") { $Matches[1] } else { $RoleHint }
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
            $RunId = "receiver-scaling-4source-lock-attribution-$stamp"
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
            evidenceClass = "RECEIVER_SCALING_LOCK_ATTRIBUTION"
            referenceScalingRun = "receiver-scaling-4source-20260904-171824"
        }
        @(
            "case=PHASE1_RECEIVER_SCALING_4SOURCE_LOCK_ATTRIBUTION"
            "evidenceClass=RECEIVER_SCALING_LOCK_ATTRIBUTION"
            "topology=3 real network sources + 1 synthetic source (S4)"
            "referenceScalingRun=receiver-scaling-4source-20260904-171824"
            "referenceHoldP99Us=16247"
            "runId=$RunId"
            "senderDurationSec=$SenderDurationSec"
            "receiverDurationSec=$receiverDurationSec"
            "capacityGates=NONE"
            "started=$(Get-Date -Format o)"
        ) | Set-Content (Join-Path $LogDir "RUN_META.txt") -Encoding UTF8
        Write-Host "RUN_ID=$RunId"
        Write-Host "LogDir=$LogDir"
        Write-Host "PREP_OK"
    }
    "start-receiver" {
        $st = Load-State
        $serial = $devices["M04"]
        if (-not (Online $serial)) { throw "M04 offline" }
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        & $adb -s $serial logcat -c
        $recvPid = Start-ProbeProcess "M04" $serial "receiver" ([int]$st.receiverDurationSec) $null $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs) $ReceiverProbeClass
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "receiver-started" -Force
        $st | Add-Member -NotePropertyName receiverPid -NotePropertyValue $recvPid -Force
        Save-State $st
        Write-Host "M04_LOCK_ATTRIBUTION_RECEIVER_STARTED pid=$recvPid"
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
        $p1 = Start-ProbeProcess "M01" $devices["M01"] "sender" ([int]$st.senderDurationSec) "S1" $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs) $SenderProbeClass
        $p3 = Start-ProbeProcess "M03" $devices["M03"] "sender" ([int]$st.senderDurationSec) "S3" $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs) $SenderProbeClass
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "m01-m03-started" -Force
        $st | Add-Member -NotePropertyName m01Pid -NotePropertyValue $p1 -Force
        $st | Add-Member -NotePropertyName m03Pid -NotePropertyValue $p3 -Force
        Save-State $st
        Write-Host "M01+M03_SENDERS_STARTED"
        Write-Host "NEXT: swap USB to M02"
    }
    "start-sender-m02" {
        $st = Load-State
        $serial = $devices["M02"]
        if (-not (Online $serial)) { throw "M02 offline" }
        & $adb -s $serial shell am force-stop $Package
        & $adb -s $serial shell am force-stop $TestPackage
        & $adb -s $serial logcat -c
        $p2 = Start-ProbeProcess "M02" $serial "sender" ([int]$st.senderDurationSec) "S2" $st.runId $st.logDir ([int]$st.baseSeq) ([long]$st.epochWallMs) $SenderProbeClass
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "m02-started" -Force
        $st | Add-Member -NotePropertyName m02Pid -NotePropertyValue $p2 -Force
        Save-State $st
        Write-Host "M02_S2_STARTED pid=$p2"
    }
    "wait-and-pull" {
        $st = Load-State
        $waitSec = [Math]::Max(30, ([int]$st.receiverDurationSec) + 30)
        Write-Host "Waiting up to ${waitSec}s..."
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
        foreach ($pair in @(
            @{ Name = "M01"; Serial = $devices["M01"]; Dirs = $SenderRemoteDirs; Hint = "sender" },
            @{ Name = "M02"; Serial = $devices["M02"]; Dirs = $SenderRemoteDirs; Hint = "sender" },
            @{ Name = "M03"; Serial = $devices["M03"]; Dirs = $SenderRemoteDirs; Hint = "sender" },
            @{ Name = "M04"; Serial = $devices["M04"]; Dirs = $ReceiverRemoteDirs; Hint = "receiver" }
        )) {
            $r = Pull-Report $pair.Name $pair.Serial $st.runId $st.logDir $pair.Dirs $pair.Hint
            Write-Host "report $($pair.Name) = $r"
        }
        $m04Report = Join-Path $st.logDir "device-reports\M04-receiver.json"
        $details = @()
        $complete = $false
        $interpretation = "UNKNOWN"
        if (Test-Path $m04Report) {
            $j = Get-Content $m04Report -Raw | ConvertFrom-Json
            $m = $j.metrics
            $attr = $m.lockAttribution
            $dom = $attr.dominance
            $rollup = $attr.rollup
            $cats = $attr.byCategoryHoldUs
            $interpretation = $dom.interpretation
            $details += "attributionComplete=$($m.attributionComplete)"
            $details += "holdP99Us=$($m.pipelineLockHoldP99Us) ref=16247"
            $details += "interpretation=$interpretation"
            $details += "primary=$($dom.primaryCategory) p99=$($dom.primaryCategoryHoldP99Us)"
            $details += "secondary=$($dom.secondaryCategory) p99=$($dom.secondaryCategoryHoldP99Us)"
            $details += "playoutMediaInsideLockP99Us=$($rollup.playoutMediaInsideLockP99Us)"
            $details += "audioTrackOutsideLockP99Us=$($rollup.audioTrackOutsideLockP99Us)"
            $details += "ingressHoldP99Us=$($rollup.ingressHoldP99Us)"
            $details += "unattributedHoldP99Us=$($rollup.unattributedHoldP99Us)"
            $details += "PLAYOUT_DECODE p99=$($cats.PLAYOUT_DECODE.p99)"
            $details += "PLAYOUT_MIX p99=$($cats.PLAYOUT_MIX.p99)"
            $details += "PLAYOUT_JITTER_PULL p99=$($cats.PLAYOUT_JITTER_PULL.p99)"
            $details += "PLAYOUT_AUDIOTRACK p99=$($cats.PLAYOUT_AUDIOTRACK.p99)"
            $details += "SYNTHETIC_ADMIT p99=$($cats.SYNTHETIC_ADMIT.p99)"
            $details += "NETWORK_ADMIT p99=$($cats.NETWORK_ADMIT.p99)"
            $details += "playoutTickTotalHoldP99Us=$($rollup.playoutTickTotalHoldP99Us)"
            $details += "ingress=$($m.ingressAcceptedBySource | ConvertTo-Json -Compress)"
            $complete = [bool]$m.attributionComplete
        } else {
            $details += "M04 receiver report missing — reconnect M04 and re-run wait-and-pull"
        }
        $route =
            switch ($interpretation) {
                "PRIMARY_PLAYOUT_MEDIA_WORK" { "STABLE_MEDIA_WORK -> may authorize 8-source after review" }
                "PRIMARY_INGRESS_ADMIT_CONTENTION" { "INGRESS_CONTENTION -> narrow admit path or decouple synthetic" }
                "PRIMARY_LOCK_SCOPE_OVERHEAD" { "LOCK_SCOPE_TOO_WIDE -> shrink critical section before 8-source" }
                default { "REVIEW_DOMINANCE -> manual adjudication" }
            }
        $status = if ($complete) { "LOCK_ATTRIBUTION_COMPLETE" } else { "LOCK_ATTRIBUTION_INCOMPLETE" }
        $adj = @"
# Phase 1 — 4-Source pipelineLock Hold Attribution

## Status

``````text
STATUS: $status
RUN_ID: $($st.runId)
EVIDENCE: RECEIVER_SCALING_LOCK_ATTRIBUTION
REFERENCE: receiver-scaling-4source-20260904-171824 (hold p99≈16247µs)
CAPACITY_GATES: NONE
``````

## Attribution

``````text
$($details -join "`n")
``````

## Route hint

``````text
$route
``````
"@
        Set-Content -Path (Join-Path $st.logDir "ADJUDICATION.md") -Value $adj -Encoding UTF8
        Add-Content -Path (Join-Path $st.logDir "RUN_META.txt") -Value "status=$status`ninterpretation=$interpretation`nended=$(Get-Date -Format o)"
        Write-Host "STATUS=$status INTERPRETATION=$interpretation"
        Write-Host "LogDir=$($st.logDir)"
        $st | Add-Member -NotePropertyName phase -NotePropertyValue "done" -Force
        $st | Add-Member -NotePropertyName status -NotePropertyValue $status -Force
        Save-State $st
        if (-not $complete) { exit 1 }
    }
}
