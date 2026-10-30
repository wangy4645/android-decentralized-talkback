# Meeting Product Integration — Phase A CREATION origin field proof (narrow)
# Chain: CONFERENCE_TOPOLOGY_PUBLISHED → PROFILE01_ORIGIN_EMIT → CONFERENCE_SIGNED_FACT
#        → peer WIRE_INGRESS factType=CREATION → APPLIED
# NOT harness injection. NOT full Phase A / A1–A4.
param(
    [ValidateSet("preflight", "prep", "build-install", "capture", "run", "pull", "adjudicate", "smoke")]
    [string]$Step = "smoke",
    [string]$RunId = "",
    [string]$HostDevice = "M01",
    [string]$ParticipantDevice = "M02",
    [string[]]$Devices = @("M01", "M02"),
    [int]$WaitAfterHostCreateSec = 45
)

$ErrorActionPreference = "Stop"
if ($Devices.Count -eq 1 -and $Devices[0] -match ",") {
    $Devices = $Devices[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$adb = (Get-Command adb).Source
$AppPackage = "com.talkback.appprod"
$AppActivity = "com.talkback.appprod/.ui.MainActivity"
$TestPackage = "com.talkback.appprod.test"
$ProvisionClass = "com.talkback.appprod.fieldtest.Adr0057FieldTestProvisioningInstrumentedTest#provisionTrustPreconditions"

$deviceMap = @{
    "M01" = @{ Serial = "HTUBB21B09220661"; Module = "M01" }
    "M02" = @{ Serial = "DSJ-2407041"; Module = "M02" }
    "M03" = @{ Serial = "MDX0220416001963"; Module = "M03" }
}

$LogTagPattern = "PROFILE01_ORIGIN_EMIT|CONFERENCE_TOPOLOGY_PUBLISHED|WIRE_INGRESS|CONFERENCE_SIGNED_FACT|JOIN_MEETING_TRACE|MEETING_MEDIA_SHADOW"

if (-not $RunId) {
    $RunId = "meeting-product-creation-origin-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}
$outDir = Join-Path $repoRoot "logs\$RunId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Resolve-Serial([string]$Name) {
    if (-not $deviceMap.ContainsKey($Name)) { throw "Unknown device label: $Name" }
    $usb = $deviceMap[$Name].Serial
    $wirelessFile = Join-Path $repoRoot "logs\field-device-wireless.json"
    if (Test-Path $wirelessFile) {
        $json = Get-Content $wirelessFile -Raw | ConvertFrom-Json
        $hit = $json | Where-Object { $_.name -eq $Name } | Select-Object -First 1
        if ($hit -and $hit.wireless) {
            $state = (& $adb -s $hit.wireless get-state 2>&1 | Out-String).Trim()
            if ($state -eq "device") { return $hit.wireless }
        }
    }
    return $usb
}

function Invoke-Adb([string]$Serial, [string[]]$Args) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        return ,(& $adb -s $Serial @Args 2>&1 | ForEach-Object { "$_" })
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Tap-ByResourceId([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        $localDump = Join-Path $env:TEMP "talkback-ui-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { throw "UI dump missing for ${Serial}" }
    $xml = Get-Content $localDump -Raw
    $pattern = "resource-id=`"$ResourceId`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    if ($xml -notmatch $pattern) {
        throw "Resource not found on ${Serial}: $ResourceId"
    }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    $cx = [int](($x1 + $x2) / 2)
    $cy = [int](($y1 + $y2) / 2)
    & $adb -s $Serial shell input tap $cx $cy 2>&1 | Out-Null
    Write-Host "TAP ${Serial} $ResourceId @ ($cx,$cy)"
}

function Start-App([string]$Serial, [switch]$ForceStop) {
    if ($ForceStop) {
        & $adb -s $Serial shell am force-stop $AppPackage 2>&1 | Out-Null
        Start-Sleep -Seconds 1
    }
    & $adb -s $Serial shell am start -n $AppActivity 2>&1 | Out-Null
    Start-Sleep -Seconds 4
}

function Launch-App([string]$Serial) {
    $ErrorActionPreference = 'Continue'
    & $adb -s $Serial shell am start -n $AppActivity 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    Start-Sleep -Seconds 3
}

function Clear-DeviceLog([string]$Serial) {
    & $adb -s $Serial logcat -c 2>&1 | Out-Null
}

function Invoke-Preflight {
    Write-Host "RUN_ID=$RunId"
    Write-Host "CREATION origin field proof — preflight"
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $state = (& $adb -s $serial get-state 2>&1 | Out-String).Trim()
        Write-Host "$name serial=$serial state=$state"
        if ($state -ne "device") { throw "$name offline: $state" }
    }
}

function Invoke-BuildInstall {
    Push-Location $repoRoot
    & .\gradlew.bat :talkback-app:assembleDebug :talkback-app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { Pop-Location; exit $LASTEXITCODE }
    Pop-Location
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        Write-Host "INSTALL $name ($serial)"
        & $adb -s $serial install -r (Join-Path $repoRoot "talkback-app\build\outputs\apk\debug\talkback-app-debug.apk")
        & $adb -s $serial install -r (Join-Path $repoRoot "talkback-app\build\outputs\apk\androidTest\debug\talkback-app-debug-androidTest.apk")
    }
}

function Invoke-Provision {
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        Write-Host "PROVISION $name ($serial)"
        Invoke-Adb $serial @("shell", "am", "force-stop", $AppPackage) | Out-Null
        & $adb -s $serial shell am instrument -w -e class $ProvisionClass "$TestPackage/androidx.test.runner.AndroidJUnitRunner"
        if ($LASTEXITCODE -ne 0) { throw "Provision failed on $name" }
    }
}

function Invoke-Prep {
    Invoke-Preflight
    Invoke-BuildInstall
    Invoke-Provision
    foreach ($name in $Devices) {
        Clear-DeviceLog (Resolve-Serial $name)
    }
    Write-Host "PREP complete. Next: -Step capture OR -Step run"
}

function Invoke-Capture {
    Write-Host "RUN_ID=$RunId OUT=$outDir"
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $outFile = Join-Path $outDir "$name-logcat.txt"
        Clear-DeviceLog $serial
        Write-Host "CAPTURE $name serial=$serial -> $outFile"
        Start-Process -FilePath $adb -ArgumentList @(
            "-s", $serial, "logcat", "-v", "threadtime",
            "Talkback:I", "Talkback:W", "Talkback:E",
            "MEETING_MEDIA_SHADOW:I", "MEETING_MEDIA_SHADOW:W", "MEETING_MEDIA_SHADOW:E"
        ) -RedirectStandardOutput $outFile -NoNewWindow
    }
    Write-Host "Logcat capture started. Run manual/automated sequence, then -Step pull -Step adjudicate"
}

function Tap-ByResourceIdIfPresent([string]$Serial, [string]$ResourceId) {
    $dumpPath = "/sdcard/talkback-ui-dump.xml"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $adb -s $Serial shell uiautomator dump $dumpPath 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        $localDump = Join-Path $env:TEMP "talkback-ui-$($Serial.Replace(':','_')).xml"
        & $adb -s $Serial pull $dumpPath $localDump 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prev
    }
    if (-not (Test-Path $localDump)) { return $false }
    $xml = Get-Content $localDump -Raw
    $pattern = "resource-id=`"$ResourceId`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    if ($xml -notmatch $pattern) { return $false }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    $cx = [int](($x1 + $x2) / 2)
    $cy = [int](($y1 + $y2) / 2)
    & $adb -s $Serial shell input tap $cx $cy 2>&1 | Out-Null
    Write-Host "TAP ${Serial} $ResourceId @ ($cx,$cy)"
    return $true
}

function Navigate-SettingsService([string]$Serial) {
    Tap-ByResourceId $Serial "${AppPackage}:id/nav_settings"
    Start-Sleep -Seconds 1
    Tap-ByResourceId $Serial "${AppPackage}:id/rowService"
    Start-Sleep -Seconds 1
}

function Ensure-ServiceStarted([string]$Serial) {
    Navigate-SettingsService $Serial
    $ridStart = "${AppPackage}:id/btnStartService"
    $ridStop = "${AppPackage}:id/btnStopService"
    foreach ($i in 1..2) {
        if (Tap-ByResourceIdIfPresent $Serial $ridStart) {
            Write-Host "SERVICE start tapped on $Serial; waiting 10s for runtime..."
            Start-Sleep -Seconds 10
            break
        }
        if (Tap-ByResourceIdIfPresent $Serial $ridStop) {
            Write-Host "SERVICE already running on $Serial"
            Start-Sleep -Seconds 2
            break
        }
        Write-Host "Scroll service page on $Serial (attempt $i)..."
        & $adb -s $Serial shell input swipe 360 1100 360 500 250 2>&1 | Out-Null
        Start-Sleep -Seconds 1
    }
    Navigate-TalkTab $Serial
}

function Navigate-TalkTab([string]$Serial) {
    Tap-ByResourceId $Serial "${AppPackage}:id/nav_talk"
    Start-Sleep -Seconds 2
}

function Invoke-Run {
    Write-Host "RUN_ID=$RunId — automated narrow sequence (M01 host CREATE, M02 TapToJoin)"
    $hostSerial = Resolve-Serial $HostDevice
    $peerSerial = Resolve-Serial $ParticipantDevice
    foreach ($name in $Devices) { Clear-DeviceLog (Resolve-Serial $name) }

    $ridMeeting = "${AppPackage}:id/btnModeMeeting"
    $ridPtt = "${AppPackage}:id/btnPtt"
    $ridOnline = "${AppPackage}:id/channelOnlineArea"

    Write-Host "=== Phase 1: cold-stop both; start services (peer must be online before host CREATE) ==="
    foreach ($name in $Devices) {
        Start-App (Resolve-Serial $name) -ForceStop
    }
    Ensure-ServiceStarted $hostSerial
    Write-Host "Waiting 10s after host service..."
    Start-Sleep -Seconds 10
    Ensure-ServiceStarted $peerSerial

    Write-Host "=== HOST ${HostDevice}: meeting mode → CREATE ==="
    Write-Host "Waiting 15s for peer discovery on host..."
    Start-Sleep -Seconds 15
    Tap-ByResourceId $hostSerial $ridMeeting
    Start-Sleep -Seconds 2
    Tap-ByResourceId $hostSerial $ridPtt
    Write-Host "Host CREATE tapped; waiting ${WaitAfterHostCreateSec}s (host stays foreground)..."
    Start-Sleep -Seconds $WaitAfterHostCreateSec

    Write-Host "=== Phase 2: PEER ${ParticipantDevice} TapToJoin (host stays up) ==="
    Launch-App $peerSerial
    Navigate-TalkTab $peerSerial
    Tap-ByResourceId $peerSerial $ridMeeting
    Start-Sleep -Seconds 3
    Tap-ByResourceId $peerSerial $ridOnline
    Write-Host "Peer join tapped; waiting 30s for wire ingress..."
    Start-Sleep -Seconds 30

    Invoke-Pull
    Invoke-Adjudicate
}

function Invoke-Pull {
    Write-Host "RUN_ID=$RunId OUT=$outDir"
    foreach ($name in $Devices) {
        $serial = Resolve-Serial $name
        $outFile = Join-Path $outDir "$name-logcat.txt"
        $grepFile = Join-Path $outDir "$name-origin-grep.txt"
        Write-Host "PULL $name serial=$serial"
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $dump = (& $adb -s $serial logcat -d -v threadtime 2>&1 | Out-String)
        $ErrorActionPreference = $prev
        $filtered = @($dump -split "`n" | Where-Object { $_ -match $LogTagPattern })
        if ($filtered.Count -eq 0) {
            $filtered = @($dump -split "`n" | Where-Object {
                $_ -match "PROFILE01|TOPOLOGY_PUBLISHED|WIRE_INGRESS|DEFERRED_|APPLIED|CONFERENCE_SIGNED|JOIN_MEETING|Talkback:"
            })
        }
        $dump | Set-Content -Path $outFile -Encoding UTF8
        $filtered | Set-Content -Path $grepFile -Encoding UTF8
        Write-Host "  grep lines=$($filtered.Count) -> $grepFile"
    }
}

function Count-Matches([string[]]$Lines, [string]$Pattern) {
    @($Lines | Where-Object { $_ -match $Pattern }).Count
}

function Invoke-Adjudicate {
    $hostGrep = Join-Path $outDir "$HostDevice-origin-grep.txt"
    $peerGrep = Join-Path $outDir "$ParticipantDevice-origin-grep.txt"
    if (-not (Test-Path $hostGrep)) { throw "Missing ${hostGrep} — run -Step pull first" }
    if (-not (Test-Path $peerGrep)) { throw "Missing ${peerGrep} — run -Step pull first" }

    $hostLines = Get-Content $hostGrep
    $peerLines = Get-Content $peerGrep

    $hostSessionMatch = [regex]::Match(
        ($hostLines | Where-Object { $_ -match "PROFILE01_ORIGIN_EMIT.*outcome=EMITTED" } | Select-Object -First 1),
        "session=([0-9a-fA-F-]+)"
    )
    $hostSessionId = if ($hostSessionMatch.Success) { $hostSessionMatch.Groups[1].Value } else { "" }
    if (-not $hostSessionId) {
        $hostTopo = [regex]::Match(
            ($hostLines | Where-Object { $_ -match "CONFERENCE_TOPOLOGY_PUBLISHED" } | Select-Object -First 1),
            "\|([0-9a-fA-F-]{36})\]"
        )
        if ($hostTopo.Success) { $hostSessionId = $hostTopo.Groups[1].Value }
    }

    $peerChosenCreate = Count-Matches $peerLines "JOIN_MEETING_TRACE.*chosenPath=CREATE"
    $peerJoinOutcome = @($peerLines | Where-Object { $_ -match "JOIN_MEETING_TRACE.*phase=outcome" } | Select-Object -First 1)
    $peerSessionMatch = [regex]::Match(($peerJoinOutcome -join ""), "session=([0-9a-fA-F-]+)")
    # peer session often only on SHADOW / TOPOLOGY lines after join
    if (-not $peerSessionMatch.Success) {
        $peerSessionMatch = [regex]::Match(
            ($peerLines | Where-Object { $_ -match "SHADOW hook=SESSION_STARTED|CONFERENCE_TOPOLOGY_PUBLISHED" } | Select-Object -Last 1),
            "session=([0-9a-fA-F-]+)|\|([0-9a-fA-F-]{36})\]"
        )
    }
    $peerSessionId = ""
    if ($peerSessionMatch.Success) {
        $peerSessionId = if ($peerSessionMatch.Groups[1].Value) { $peerSessionMatch.Groups[1].Value } else { $peerSessionMatch.Groups[2].Value }
    }

    $j0SameSession = ($hostSessionId -ne "" -and $peerSessionId -ne "" -and $hostSessionId -eq $peerSessionId)
    $j0Pass = ($peerChosenCreate -eq 0 -and $j0SameSession)

    $counts = @{
        j0Pass = if ($j0Pass) { 1 } else { 0 }
        j0PeerChosenCreate = $peerChosenCreate
        j0HostSessionId = $hostSessionId
        j0PeerSessionId = $peerSessionId
        hostTopologyPublished = Count-Matches $hostLines "CONFERENCE_TOPOLOGY_PUBLISHED"
        hostOriginEmitted = Count-Matches $hostLines "PROFILE01_ORIGIN_EMIT.*factType=1.*outcome=EMITTED"
        peerSignedFactObserved = Count-Matches $peerLines "CONFERENCE_SIGNED_FACT|signalType=CONFERENCE_SIGNED_FACT"
        peerWireIngressCreation = Count-Matches $peerLines "WIRE_INGRESS.*factType=1"
        peerCreationApplied = Count-Matches $peerLines "WIRE_INGRESS.*factType=1.*outcome=APPLIED"
        peerCreationRejectedVerify = Count-Matches $peerLines "WIRE_INGRESS.*factType=1.*(REJECTED_VERIFY|REJECTED_DECODE)"
        peerCreationRejectedPublish = Count-Matches $peerLines "WIRE_INGRESS.*factType=1.*REJECTED_PUBLISH"
    }

    $verdict = "INDETERMINATE"
    $verdictDetail = ""
    if (-not $j0Pass) {
        $verdict = "ENV_INVALID"
        $verdictDetail = "J0 FAIL: peerChosenCreate=$peerChosenCreate hostSession=$hostSessionId peerSession=$peerSessionId — do not score CREATION ingress"
    } elseif ($counts.hostOriginEmitted -ge 1 -and $counts.peerCreationApplied -ge 1) {
        $verdict = "PASS"
        $verdictDetail = "J0 PASS + host EMITTED + peer CREATION APPLIED"
    } elseif ($counts.hostTopologyPublished -ge 1 -and $counts.hostOriginEmitted -eq 0) {
        $verdict = "ORIGIN_EMIT_GAP"
        $verdictDetail = "J0 PASS but host did not EMITTED CREATION"
    } elseif ($counts.hostOriginEmitted -ge 1 -and $counts.peerWireIngressCreation -eq 0 -and $counts.peerSignedFactObserved -eq 0) {
        $verdict = "WIRE_DELIVERY_GAP"
        $verdictDetail = "J0 PASS + host EMITTED but peer never observed CONFERENCE_SIGNED_FACT / WIRE_INGRESS"
    } elseif ($counts.hostOriginEmitted -ge 1 -and $counts.peerCreationApplied -eq 0) {
        $verdict = "VERIFY_DECODE_OR_SESSION_BRIDGE_GAP"
        $verdictDetail = "J0 PASS + peer saw wire/ingress but CREATION not APPLIED"
    }

    Write-Host ""
    Write-Host "=== CREATION ORIGIN FIELD ADJUDICATION (J0 gated) ==="
    Write-Host "verdict=$verdict"
    Write-Host "detail=$verdictDetail"
    $counts.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0}={1}" -f $_.Key, $_.Value) }

    $header = @{
        runId = $RunId
        evidenceScope = "MEETING_PRODUCT_PHASE_A_CREATION_ORIGIN_FIELD"
        hostDevice = $HostDevice
        participantDevice = $ParticipantDevice
        capturedAt = (Get-Date).ToString("o")
        verdict = $verdict
        verdictDetail = $verdictDetail
        j0 = @{
            pass = $j0Pass
            peerChosenCreate = $peerChosenCreate
            hostSessionId = $hostSessionId
            peerSessionId = $peerSessionId
        }
        counts = $counts
        note = "J0 same-session hard gate; WIRE_INGRESS meaningless if J0 FAIL; MEMBERSHIP HOLD"
        chain = @(
            "J0 same-session",
            "PROFILE01_ORIGIN_EMIT factType=CREATION outcome=EMITTED",
            "CONFERENCE_SIGNED_FACT",
            "WIRE_INGRESS factType=CREATION",
            "APPLIED"
        )
    }
    $header | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outDir "run-header.json") -Encoding UTF8
    Write-Host "Wrote $(Join-Path $outDir 'run-header.json')"
    return $verdict
}

function Invoke-Smoke {
    Invoke-Preflight
    Write-Host ""
    Write-Host "CREATION origin field proof — smoke OK"
    Write-Host "  -Step prep          build + install + provision + clear logs"
    Write-Host "  -Step capture       start logcat (manual UX)"
    Write-Host "  -Step run           automated M01 CREATE + M02 TapToJoin + pull + adjudicate"
    Write-Host "  -Step pull          dump logs"
    Write-Host "  -Step adjudicate    ENV_INVALID | ORIGIN_EMIT_GAP | WIRE_DELIVERY_GAP | VERIFY_DECODE_OR_SESSION_BRIDGE_GAP | PASS"
}

switch ($Step) {
    "preflight" { Invoke-Preflight }
    "build-install" { Invoke-BuildInstall }
    "prep" { Invoke-Prep }
    "capture" { Invoke-Capture }
    "run" { Invoke-Run }
    "pull" { Invoke-Pull }
    "adjudicate" { Invoke-Adjudicate }
    "smoke" { Invoke-Smoke }
    default { throw "Unknown step: $Step" }
}
